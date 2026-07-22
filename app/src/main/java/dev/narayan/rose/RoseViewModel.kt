package dev.narayan.rose

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.narayan.rose.filejob.JobManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class GridItemSize(val cellMinSize: Dp, val iconSize: Dp) {
    SMALL(84.dp, 48.dp),
    MEDIUM(110.dp, 64.dp),
    LARGE(150.dp, 88.dp)
}

// Every "Recent files" and category (Archives, Documents, ...) listing in this
// app is powered by a MediaStore query. MediaStore only learns about a file
// through its own scanner - it does NOT automatically notice files this app
// writes directly with java.io (ZipOutputStream, File.renameTo, raw copies,
// etc). Without an explicit rescan, a freshly-zipped archive or a renamed/
// moved file stays invisible to Recent/Archives, sometimes for hours, no
// matter how many times the user pulls to refresh - the query itself is
// working fine, it's just querying a database that was never told the file
// exists. Call this right after any operation that creates, modifies, moves,
// renames or deletes a real file on disk.
fun rescanForMediaStore(context: android.content.Context, vararg files: File) {
    if (files.isEmpty()) return
    android.media.MediaScannerConnection.scanFile(
        context,
        files.map { it.absolutePath }.toTypedArray(),
        null,
        null
    )
}

// Directory names that are never useful to walk into: heavy app-private
// caches Android hides behind a permission wall anyway (Android/data,
// Android/obb), thumbnail caches, and trash folders other apps leave
// behind. Combined with skipping any name starting with "." (hidden
// dot-folders, including this app's own .thumbnails-style caches), this
// keeps the fallback filesystem walk below fast and bounded instead of
// crawling gigabytes of stuff no one is looking for.
private val fsScanJunkDirNames = setOf(".thumbnails", ".trashed", ".Trash", "LOST.DIR", "\$RECYCLE.BIN", "cache", ".cache")
private const val FS_SCAN_MAX_DIRS = 20000

private fun fsScanShouldSkipDir(dir: File): Boolean {
    val name = dir.name
    if (name.startsWith(".")) return true
    if (name in fsScanJunkDirNames) return true
    val parentName = dir.parentFile?.name
    if (parentName == "Android" && (name == "data" || name == "obb")) return true
    return false
}

/**
 * MediaStore only knows about a file once its own scanner has actually seen
 * it - a zip copied in from a computer, placed by another file manager, or
 * saved by an app that never triggers a scan can sit invisible to any
 * MediaStore-only listing (Archives, search) indefinitely. This walks the
 * real filesystem as a completeness fallback, skipping hidden and junk/
 * system folders so it stays fast, and is meant to be merged with - not
 * replace - the fast MediaStore query.
 */
fun scanFilesystemForExtensions(
    extensions: Set<String>,
    root: File = Environment.getExternalStorageDirectory(),
    maxResults: Int = 500
): List<File> {
    val results = mutableListOf<File>()
    val stack = ArrayDeque<File>()
    stack.addLast(root)
    var dirsVisited = 0
    while (stack.isNotEmpty() && results.size < maxResults && dirsVisited < FS_SCAN_MAX_DIRS) {
        val dir = stack.removeLast()
        dirsVisited++
        val children = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
        for (child in children) {
            if (results.size >= maxResults) break
            if (child.isDirectory) {
                if (!fsScanShouldSkipDir(child)) stack.addLast(child)
            } else {
                val ext = child.name.substringAfterLast('.', "").lowercase()
                if (ext in extensions) results.add(child)
            }
        }
    }
    return results
}

/** Same fallback walk as [scanFilesystemForExtensions], matching by filename instead of extension. */
fun scanFilesystemForQuery(query: String, root: File, maxResults: Int = 100): List<File> {
    val results = mutableListOf<File>()
    val stack = ArrayDeque<File>()
    stack.addLast(root)
    val lowerQuery = query.lowercase()
    var dirsVisited = 0
    while (stack.isNotEmpty() && results.size < maxResults && dirsVisited < FS_SCAN_MAX_DIRS) {
        val dir = stack.removeLast()
        dirsVisited++
        val children = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
        for (child in children) {
            if (results.size >= maxResults) break
            if (child.isDirectory) {
                if (!fsScanShouldSkipDir(child)) stack.addLast(child)
            } else if (child.name.lowercase().contains(lowerQuery)) {
                results.add(child)
            }
        }
    }
    return results
}

class RoseViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsManager(application)

    var currentPath by mutableStateOf(Environment.getExternalStorageDirectory().absolutePath)
        private set

    var files by mutableStateOf(listOf<FileItem>())
        private set

    // Deliberately separate from `files`. Category browsing used to write its
    // MediaStore results straight into `files` - the same state "All Files"
    // reads from. Since loadFiles() intentionally keeps old content on screen
    // until new data is ready (to avoid a blank flash), exiting a category
    // back to "All Files" would show the leftover category results for a
    // moment before the real folder listing replaced them. Giving Category
    // its own state makes that cross-contamination impossible.
    var categoryFiles by mutableStateOf(listOf<FileItem>())
        private set

    var recentFiles by mutableStateOf(listOf<FileItem>())
        private set

    var showHiddenFiles by mutableStateOf(settings.showHiddenFiles)
        private set

    var sortBy by mutableStateOf(
        try { SortBy.valueOf(settings.sortBy) } catch (e: IllegalArgumentException) { SortBy.NAME }
    )
        private set

    var sortOrder by mutableStateOf(
        try { SortOrder.valueOf(settings.sortOrder) } catch (e: IllegalArgumentException) { SortOrder.ASCENDING }
    )
        private set

    var selectedFiles = mutableStateListOf<FileItem>()
        private set

    var isSelectionMode by mutableStateOf(false)
        private set

    // View & display preferences: persisted through SettingsManager so they
    // survive process death / app restarts.
    private val _isGridView = mutableStateOf(settings.isGridView)
    val isGridView: Boolean by _isGridView

    private val _isCategoryGridView = mutableStateOf(settings.isCategoryGridView)
    val isCategoryGridView: Boolean by _isCategoryGridView

    private val _foldersFirst = mutableStateOf(settings.foldersFirst)
    val foldersFirst: Boolean by _foldersFirst

    private val _showDetails = mutableStateOf(settings.showDetails)
    val showDetails: Boolean by _showDetails

    private val _confirmBeforeDelete = mutableStateOf(settings.confirmBeforeDelete)
    val confirmBeforeDelete: Boolean by _confirmBeforeDelete

    private val _showFileExtensions = mutableStateOf(settings.showFileExtensions)
    val showFileExtensions: Boolean by _showFileExtensions

    private val _showListDividers = mutableStateOf(settings.showListDividers)
    val showListDividers: Boolean by _showListDividers

    private val _useRecycleBin = mutableStateOf(settings.useRecycleBin)
    val useRecycleBin: Boolean by _useRecycleBin

    private val _notificationPrimerShown = mutableStateOf(settings.notificationPrimerShown)
    val notificationPrimerShown: Boolean by _notificationPrimerShown

    fun markNotificationPrimerShown() {
        _notificationPrimerShown.value = true
        settings.notificationPrimerShown = true
    }

    private val _showQuickAccess = mutableStateOf(settings.showQuickAccess)
    val showQuickAccess: Boolean by _showQuickAccess

    private val _showExternalStorage = mutableStateOf(settings.showExternalStorage)
    val showExternalStorage: Boolean by _showExternalStorage

    private var _quickAccessExpanded = mutableStateOf(settings.quickAccessExpanded)
    val quickAccessExpanded: Boolean by _quickAccessExpanded

    private var _externalStorageExpanded = mutableStateOf(settings.externalStorageExpanded)
    val externalStorageExpanded: Boolean by _externalStorageExpanded

    private val _gridItemSize = mutableStateOf(
        try { GridItemSize.valueOf(settings.gridItemSize) } catch (e: IllegalArgumentException) { GridItemSize.MEDIUM }
    )
    val gridItemSize: GridItemSize by _gridItemSize

    // Theme preferences - also persisted.
    private val _themeMode = mutableStateOf(settings.themeMode)
    val themeMode: ThemeMode by _themeMode

    private val _amoledMode = mutableStateOf(settings.amoledMode)
    val amoledMode: Boolean by _amoledMode

    var dynamicColorEnabled by mutableStateOf(settings.dynamicColor)
        private set

    private val _startPage = mutableStateOf(settings.startPage)
    val startPage: StartPage by _startPage

    var clipboardFiles = mutableStateListOf<FileItem>()
        private set

    var clipboardSourceZip by mutableStateOf<File?>(null)
        private set

    var extractionSource by mutableStateOf<File?>(null)
        private set

    var isCopyOperation by mutableStateOf(true) // true for copy, false for move
        private set

    var pendingSafPath by mutableStateOf<String?>(null)
        private set

    var lastDeniedSafPath by mutableStateOf<String?>(null)
        private set

    var onNotificationPermissionRequest: (() -> Unit)? = null

    fun clearPendingSaf() {
        pendingSafPath = null
    }

    fun retrySaf(path: String) {
        lastDeniedSafPath = null
        pendingSafPath = path
    }

    fun onSafResult(granted: Boolean, path: String) {
        pendingSafPath = null
        if (!granted) {
            lastDeniedSafPath = path
        } else {
            lastDeniedSafPath = null
            // Use showLoading = false to avoid UI jump if we are already in the folder
            loadFiles(path, showLoading = path != currentPath)
        }
    }

    var errorMessage by mutableStateOf<String?>(null)

    fun clearError() { errorMessage = null }

    var propertiesFile by mutableStateOf<FileItem?>(null)
    var highlightedFile by mutableStateOf<FileItem?>(null)

    var storageInfo by mutableStateOf<StorageInfo?>(null)
        private set

    var recycleBinItems by mutableStateOf<List<RecycledItem>>(emptyList())
        private set

    // Guards the periodic poll (see RecycleBinScreen) from re-fetching disk
    // state and clobbering the optimistic removal animation while a bulk
    // delete/restore/empty operation is in flight. Without this, the poll
    // could briefly resurrect items mid-animation (since the physical file
    // op hadn't finished yet), then wipe them again once it did - the
    // "quickly removes, shows some back, then vanishes" glitch.
    private var recycleBinBulkOperationActive = false

    fun loadRecycleBin() {
        if (recycleBinBulkOperationActive) return
        viewModelScope.launch(Dispatchers.IO) {
            val items = RecycleBinManager.listItems(getApplication())
            withContext(Dispatchers.Main) {
                if (!recycleBinBulkOperationActive) {
                    recycleBinItems = items
                }
            }
        }
    }

    fun deleteRecycleBinItemPermanently(item: RecycledItem) {
        // Optimistic removal for smooth animation
        recycleBinItems = recycleBinItems.filter { it.id != item.id }

        viewModelScope.launch(Dispatchers.IO) {
            RecycleBinManager.deletePermanently(getApplication(), item)
        }
    }

    fun deleteRecycleBinItemsPermanently(items: Set<RecycledItem>) {
        recycleBinBulkOperationActive = true
        viewModelScope.launch(Dispatchers.Main) {
            val toDelete = items.toList()
            toDelete.forEach { item ->
                // Physical deletion happens one by one with real feedback.
                // We wait for each one to finish before removing it from the UI,
                // so the "reappearing" glitch is physically impossible.
                withContext(Dispatchers.IO) {
                    RecycleBinManager.deletePermanently(getApplication(), item)
                }
                recycleBinItems = recycleBinItems.filter { it.id != item.id }
                delay(10) // Small delay for visual separation
            }
            recycleBinBulkOperationActive = false
        }
    }

    fun emptyRecycleBinPermanently() {
        recycleBinBulkOperationActive = true
        viewModelScope.launch(Dispatchers.Main) {
            val toDelete = recycleBinItems.toList()
            toDelete.forEach { item ->
                withContext(Dispatchers.IO) {
                    RecycleBinManager.deletePermanently(getApplication(), item)
                }
                recycleBinItems = recycleBinItems.filter { it.id != item.id }
                delay(10)
            }
            recycleBinBulkOperationActive = false
        }
    }

    fun restoreRecycleBinItems(context: android.content.Context, items: Set<RecycledItem>) {
        val ids = items.map { it.id }
        val names = items.map { it.originalName }
        recycleBinBulkOperationActive = true

        // Restoration runs in a background Service (it needs to survive the
        // screen closing/app backgrounding), but the UI used to fake its own
        // "one item disappears every 60ms" animation completely disconnected
        // from that real work. If the actual restore took longer than the
        // fake animation + the 1s grace period below, recycleBinBulkOperationActive
        // flipped back to false while files were still physically sitting in
        // the bin - the next 2s poll (see RecycleBinScreen) would then reload
        // the real on-disk list, making those not-yet-restored items pop back
        // into view, only to vanish again a few seconds later once the
        // background job actually finished them. That's the "disappears, a
        // few reappear, then disappear again" glitch.
        //
        // Fixed by tracking the exact job we just started (via a caller-
        // supplied id) and only ever removing a row from the UI the instant
        // that job's own completedPaths says that specific file has truly
        // been restored - and only clearing the "bulk operation in progress"
        // guard once that real job is done, not on a guessed timer.
        val jobId = dev.narayan.rose.filejob.FileJobService.startRestore(context, ids, names)

        viewModelScope.launch(Dispatchers.Main) {
            val pendingIds = ids.toMutableSet()
            try {
                withTimeoutOrNull(120_000) {
                    var seenJob = false
                    while (isActive) {
                        val job = JobManager.activeJobs.value[jobId]
                        if (job != null) {
                            seenJob = true
                            if (job.completedPaths.isNotEmpty()) {
                                val doneSoFar = job.completedPaths
                                pendingIds.removeAll(doneSoFar)
                                recycleBinItems = recycleBinItems.filter { it.id !in doneSoFar }
                            }
                        } else if (seenJob || pendingIds.isEmpty()) {
                            // Job finished and was removed from JobManager (or every
                            // requested item is already accounted for) - nothing left
                            // to wait on.
                            break
                        }
                        delay(50)
                    }
                }
            } finally {
                // Whether the job finished normally, timed out, or errored, make
                // sure every item that was asked to be restored is gone from the
                // list by the time we're done - the job itself is the source of
                // truth for *when* each item disappears, this is only a final
                // safety net.
                recycleBinItems = recycleBinItems.filter { it.id !in ids.toSet() }
                recycleBinBulkOperationActive = false
            }
        }
    }

    data class StorageInfo(
        val totalBytes: Long,
        val availableBytes: Long,
        val usedBytes: Long,
        val usedFraction: Float
    )

    fun loadStorageInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stats = StatFs(Environment.getExternalStorageDirectory().path)
                val total = stats.totalBytes
                val available = stats.availableBytes
                val used = (total - available).coerceAtLeast(0)
                val fraction = if (total > 0) used.toFloat() / total.toFloat() else 0f

                withContext(Dispatchers.Main) {
                    storageInfo = StorageInfo(total, available, used, fraction)
                }
            } catch (e: Exception) {
                // Ignore or log error
            }
        }
    }

    var folderSize by mutableStateOf<Long?>(null)
        private set

    fun showProperties(fileItem: FileItem) {
        propertiesFile = fileItem
        folderSize = null
        if (fileItem.isDirectory) {
            viewModelScope.launch(Dispatchers.IO) {
                folderSize = calculateFolderSize(fileItem.file)
            }
        }
    }

    fun closeProperties() {
        propertiesFile = null
        folderSize = null
    }

    @Suppress("NewApi")
    private fun calculateFolderSize(directory: File): Long {
        return try {
            val path = java.nio.file.Paths.get(directory.absolutePath)
            var totalSize = 0L
            java.nio.file.Files.walk(path).use { stream ->
                stream.forEach { p ->
                    if (java.nio.file.Files.isRegularFile(p)) {
                        totalSize += java.nio.file.Files.size(p)
                    }
                }
            }
            totalSize
        } catch (e: Exception) {
            0L
        }
    }

    var currentZipFile by mutableStateOf<File?>(null)
    // Virtual directory path within the open zip ("" = root). Lets folders
    // inside an archive be browsed without re-reading the zip each time.
    var currentZipEntryPath by mutableStateOf("")
    private var zipEntriesCache: List<ZipEntry> = emptyList()

    var isLoading by mutableStateOf(false)
    var isRefreshing by mutableStateOf(false)

    // Set whenever one of our own file jobs (delete/recycle/move/etc.) just
    // affected the currently-open folder. While inside this window, the
    // FileObserver/poll-driven silentRefresh() below is skipped: our own
    // optimistic removal (see the `affectedPaths` handling in init{}) already
    // updated `files` precisely, and LazyColumn's item-removal animation
    // (Modifier.animateItem()) is currently playing for that removal. A
    // silentRefresh landing mid-animation replaces the whole `files` list at
    // once, which cancels/derails that in-flight animation - this is what
    // caused deletes after the first one in a session to "scramble" instead
    // of sliding out smoothly. The watcher fires again on its own very
    // shortly after (every 400ms/1s), so this only delays a live-refresh
    // tick, it never skips one for good.
    private var suppressSilentRefreshUntil = 0L
    private var loadJob: Job? = null

    // silentRefresh() (folder watcher / poll) and loadFiles() both write to
    // `files` from their own independent coroutines, with no cancellation
    // between the two. Without this, whichever one happened to finish LAST
    // won, regardless of which was actually the most recently requested -
    // so a sort change's loadFiles() could occasionally lose the race to an
    // in-flight silentRefresh() that started slightly earlier (e.g. from the
    // 1s poll) and land with the pre-sort order, showing as "sort doesn't
    // stick until I manually refresh". Every write now captures the current
    // generation before doing IO and only applies its result if no newer
    // request has started since - so the most recently requested update
    // always wins, no matter which coroutine finishes first.
    private var filesGeneration = 0
    var searchResults = mutableStateListOf<FileItem>()
    var isRecursiveSearching by mutableStateOf(false)

    // path -> (lastModified at time of scan, computed recursive size). Lets
    // repeated sorts/re-visits skip re-walking a folder that hasn't changed.
    private val folderSizeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    /** Fills in real recursive folder sizes (computed in parallel, cached by
     * path+mtime) so "sort by size" places folders where their actual
     * contents put them, instead of every folder tying at 0 and clumping
     * together in the middle of same-sized files. Only ever called when
     * sortBy == SIZE, so it never slows down normal browsing/navigation. */
    private suspend fun withFolderSizes(list: List<FileItem>): List<FileItem> = coroutineScope {
        list.map { item ->
            async(Dispatchers.IO) {
                if (!item.isDirectory) return@async item
                val mtime = item.file.lastModified()
                val cached = folderSizeCache[item.file.absolutePath]
                val size = if (cached != null && cached.first == mtime) {
                    cached.second
                } else {
                    calculateFolderSize(item.file).also {
                        folderSizeCache[item.file.absolutePath] = mtime to it
                    }
                }
                item.copy(size = size)
            }
        }.awaitAll()
    }

    var hasRunDividerAnimation by mutableStateOf(false)
    var hasRunStorageAnimation by mutableStateOf(false)
    var hasRunEntranceAnimation by mutableStateOf(false)

    // Home screen scroll position, saved continuously while Home is visible and
    // re-applied every time Home is (re)composed - e.g. after opening a folder
    // from Quick Access, a category, or the Recycle Bin and pressing back. This
    // is intentionally scoped to Home only: folder-to-folder navigation inside
    // "Files" already keeps its own scroll state per path and doesn't touch this.
    var homeScrollIndex by mutableStateOf(0)
    var homeScrollOffset by mutableStateOf(0)

    // Scroll state for Recycle Bin to preserve position when going back and forth
    var recycleBinScrollIndex by mutableStateOf(0)
    var recycleBinScrollOffset by mutableStateOf(0)

    // Material Files style: map to track scroll positions per directory path,
    // so going back to a parent folder restores its previous scroll state.
    private val pathScrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun saveScrollPosition(path: String, index: Int, offset: Int) {
        if (path.isEmpty()) return
        pathScrollPositions[path] = index to offset
    }

    fun getScrollPosition(path: String): Pair<Int, Int>? {
        return pathScrollPositions[path]
    }

    private var rootCache: List<FileItem>? = null

    fun preLoadRoot() {
        if (rootCache != null) {
            // If we have a cache, use it immediately to prevent jitter
            if (currentPath == Environment.getExternalStorageDirectory().absolutePath && files.isEmpty()) {
                files = rootCache!!
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val root = Environment.getExternalStorageDirectory()
                val rootFiles = root.listFiles()
                    ?.filter { !it.name.startsWith(".") || showHiddenFiles }
                    ?.map { FileItem(it) }
                    ?.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
                    ?: emptyList()

                rootCache = rootFiles

                // If we are currently at root and list is empty, update it
                withContext(Dispatchers.Main) {
                    if (currentPath == root.absolutePath && files.isEmpty()) {
                        files = rootFiles
                    }
                }
            } catch (e: Exception) {
                // Ignore errors during pre-load
            }
        }
    }

    // ----- Live directory watching (Fix: external transfers / downloads -----
    // landing in the folder you're currently browsing used to only show up
    // after a manual pull-to-refresh, and with no size updates in between.

    private var directoryObserver: android.os.FileObserver? = null
    private var watcherJob: Job? = null
    private var pollJob: Job? = null
    private var watchedPath: String? = null
    private val watcherEvents = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private fun startWatchingDirectory(path: String) {
        if (watchedPath == path && (directoryObserver != null || pollJob != null)) return
        stopWatchingDirectory()

        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return

        watchedPath = path

        val mask = android.os.FileObserver.CREATE or android.os.FileObserver.DELETE or
                android.os.FileObserver.MODIFY or android.os.FileObserver.MOVED_FROM or
                android.os.FileObserver.MOVED_TO or android.os.FileObserver.CLOSE_WRITE

        // FileObserver construction (or delivery of its events) can silently
        // fail on some devices/ROMs. It used to be `?: return` here, which
        // meant a failed FileObserver also skipped setting up the poll below
        // - so on a device where it failed, NEITHER mechanism ran, and live
        // updates never worked at all no matter what. The poll now always
        // starts regardless of whether this succeeds.
        val observer = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                object : android.os.FileObserver(dir, mask) {
                    override fun onEvent(event: Int, relativePath: String?) {
                        watcherEvents.trySend(Unit)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                object : android.os.FileObserver(dir.absolutePath, mask) {
                    override fun onEvent(event: Int, relativePath: String?) {
                        watcherEvents.trySend(Unit)
                    }
                }
            }
        } catch (e: Exception) {
            null
        }

        if (observer != null) {
            observer.startWatching()
            directoryObserver = observer

            // A file being written arrives as a burst of many raw filesystem
            // events (every chunk written fires MODIFY). Coalesce them into one
            // cheap re-stat of the folder every ~400ms, so names/sizes update
            // live - like Material Files - without hammering the UI thread.
            watcherJob = viewModelScope.launch {
                for (unit in watcherEvents) {
                    delay(400)
                    while (watcherEvents.tryReceive().isSuccess) { /* drain the rest of this burst */ }
                    if (currentPath == path && watchedPath == path && currentZipFile == null && categoryFilterType == null) {
                        silentRefresh(path)
                    }
                }
            }
        }

        // FileObserver's MODIFY event isn't reliably delivered for writes
        // coming from another app going through the FUSE/MediaStore layer on
        // every device - some ROMs only forward the final CLOSE_WRITE, which
        // would show the finished file but never a "growing" size in between.
        // This poll is what actually guarantees the gradual size-increase
        // behavior: a plain re-stat of the folder every second, independent
        // of any event delivery, so a downloading/incoming file's size just
        // reflects whatever is really on disk right now.
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (currentPath == path && watchedPath == path && currentZipFile == null && categoryFilterType == null) {
                    silentRefresh(path)
                }
            }
        }
    }

    private fun stopWatchingDirectory() {
        directoryObserver?.stopWatching()
        directoryObserver = null
        watcherJob?.cancel()
        watcherJob = null
        pollJob?.cancel()
        pollJob = null
        silentRefreshJob?.cancel()
        silentRefreshJob = null
        watchedPath = null
    }

    /** Same listing as loadFiles, but never toggles isLoading/isRefreshing -
     * so a live update from the folder watcher just quietly updates names,
     * item counts and sizes in place, with no spinner or flicker. */
    private var silentRefreshJob: Job? = null
    // Separate from filesGeneration on purpose: silentRefresh (the background
    // FileObserver/poll tick) used to share that counter with loadFiles, so a
    // poll landing while an explicit loadFiles() was still in flight (e.g. the
    // reload after a sort change, which walks folder sizes for Sort by Size
    // and can take a moment) would bump the shared counter and make loadFiles
    // silently discard its own, authoritative result at commit time - that's
    // what made a sort change "sometimes" require a manual refresh to actually
    // show. This counter only needs to protect silentRefresh from an older
    // silentRefresh; it has no business invalidating loadFiles or vice versa.
    private var silentRefreshGeneration = 0
    private var categoryGeneration = 0
    private fun silentRefresh(path: String) {
        // The FileObserver watcher and the poll can both ask for a refresh
        // around the same time. Letting both run meant two independent
        // folder-size computations could finish out of order - a slower one
        // started earlier landing on Main *after* a faster, newer one - and
        // whichever happened to win overwrote `files` with stale/partial
        // data. That's what was scrambling folder positions under Sort by
        // Size. Only ever one of these in flight at a time now.
        silentRefreshJob?.cancel()
        val requestGeneration = ++silentRefreshGeneration
        silentRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val directory = File(path)
                if (!directory.exists() || !directory.isDirectory) return@launch

                val rawFiles = directory.listFiles() ?: return@launch
                val filteredFiles = if (!showHiddenFiles) {
                    rawFiles.filter { !it.name.startsWith(".") }
                } else {
                    rawFiles.toList()
                }

                val fileList = filteredFiles.map { file ->
                    FileItem(
                        file = file,
                        itemCount = if (file.isDirectory) {
                            val children = file.listFiles()
                            if (!showHiddenFiles) children?.count { !it.name.startsWith(".") } else children?.size
                        } else null
                    )
                }

                val sizedFileList = if (sortBy == SortBy.SIZE) withFolderSizes(fileList) else fileList
                val sorted = sortFileList(sizedFileList)

                withContext(Dispatchers.Main) {
                    if (requestGeneration == silentRefreshGeneration && currentPath == path && System.currentTimeMillis() >= suppressSilentRefreshUntil) {
                        files = sorted
                        if (path == Environment.getExternalStorageDirectory().absolutePath) {
                            rootCache = sorted
                        }
                    }
                }
            } catch (e: Exception) {
                // Transient errors (a file mid-write, briefly missing, etc.)
                // are expected here - just skip this tick, the next
                // filesystem event will trigger another attempt.
            }
        }
    }

    enum class SortBy { NAME, DATE, SIZE, TYPE }
    enum class SortOrder { ASCENDING, DESCENDING }

    init {
        // We no longer load recent files here to prevent potential
        // startup crashes if permissions are not yet granted.
        // HomeScreen will trigger loading when it becomes visible.

        // Clean up expired items from recycle bin
        loadRecycleBin()

        // React to job events (Copy, Move, Download, Delete, Recycle, Restore)
        viewModelScope.launch {
            JobManager.jobEvents.collect { result ->
                val job = result.job

                // If a job just finished, refresh the file list to ensure disk state is synced.
                // For deletions, we also explicitly remove from the current list immediately
                // to prevent them from "reappearing" if the background scan is slow.
                val affectedPaths = job.completedPaths
                if (affectedPaths.isNotEmpty()) {
                    // Give the LazyColumn's own removal animation room to play
                    // out before the folder watcher's next silentRefresh tick.
                    suppressSilentRefreshUntil = System.currentTimeMillis() + 1200

                    // Optimistically decrement category counts for files we know
                    // were actually removed (Delete/Recycle). Re-querying MediaStore
                    // right away instead used to show the stale, pre-delete count for
                    // a while for every category except Photos/Videos (whose
                    // MediaStore rows tend to update fastest) - the scanFile pass
                    // that tells MediaStore a file is gone (see FileJob) is async and
                    // isn't done yet by the time this event fires.
                    val isRemoval = job.type is dev.narayan.rose.filejob.FileJobType.Delete ||
                            job.type is dev.narayan.rose.filejob.FileJobType.Recycle
                    if (isRemoval) {
                        val removedItems = (files + recentFiles + categoryFiles + searchResults)
                            .filter { it.file.absolutePath in affectedPaths && !it.isDirectory }
                            .distinctBy { it.file.absolutePath }

                        val removedTypes = removedItems.map { it.fileType }
                        val removedBuckets = removedItems.mapNotNull { it.bucketId }.toSet()

                        if (removedTypes.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                val updated = categoryCounts.toMutableMap()
                                removedTypes.forEach { type ->
                                    updated[type]?.let { current -> updated[type] = (current - 1).coerceAtLeast(0) }
                                }
                                categoryCounts = updated

                                // Update album counts in categoryFiles if we are in the album list
                                if (categoryFilterType == FileType.IMAGE && categoryBucketId == null && removedBuckets.isNotEmpty()) {
                                    categoryFiles = categoryFiles.map { item ->
                                        if (item.isDirectory && item.bucketId in removedBuckets) {
                                            item.copy(itemCount = (item.itemCount ?: 0) - 1)
                                        } else {
                                            item
                                        }
                                    }
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        files = files.filter { it.file.absolutePath !in affectedPaths }
                        recentFiles = recentFiles.filter { it.file.absolutePath !in affectedPaths }
                        categoryFiles = categoryFiles.filter { it.file.absolutePath !in affectedPaths }
                        searchResults.removeAll { it.file.absolutePath in affectedPaths }

                        // If a recycle/restore happened, refresh the bin items too
                        if (job.type is dev.narayan.rose.filejob.FileJobType.Recycle ||
                            job.type is dev.narayan.rose.filejob.FileJobType.Restore) {
                            loadRecycleBin()
                        }
                    }
                    // Refresh category counts from MediaStore for eventual
                    // consistency, once its async scan from the delete has had a
                    // realistic chance to finish. An immediate force=true query
                    // here would just read the still-stale index and flicker the
                    // count we already corrected above back to the old value.
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(1500)
                        loadCategoryCounts(force = true)
                    }
                }

                val targetPath = when (val type = job.type) {
                    is dev.narayan.rose.filejob.FileJobType.Copy -> type.targetDir.toString()
                    is dev.narayan.rose.filejob.FileJobType.Move -> type.targetDir.toString()
                    else -> null
                }

                // Only reload if we're in the target folder of a copy/move.
                // For deletions or source folders of a move, the manual filtering
                // above is enough to remove the items instantly with animation,
                // and avoids re-scanning the folder before the filesystem has synced.
                if (targetPath != null && targetPath == currentPath && currentPath.isNotEmpty()) {
                    loadFiles(currentPath, showLoading = false)
                }

                // Handle offline download success/failure
                val downloadType = job.type as? dev.narayan.rose.filejob.FileJobType.Download ?: return@collect
                val sourcePath = downloadType.source.path
                val targetFile = File(downloadType.targetFile.toString())

                if (result.success) {
                    if (!offlineFiles.contains(sourcePath)) {
                        val updated = offlineFiles + sourcePath
                        offlineFiles = updated
                        settings.offlineFiles = updated
                    }
                } else {
                    // Download failed or was cancelled
                    if (targetFile.exists()) targetFile.delete()
                    if (offlineFiles.contains(sourcePath)) {
                        val updated = offlineFiles - sourcePath
                        offlineFiles = updated
                        settings.offlineFiles = updated
                    }
                    if (result.error != null) {
                        errorMessage = "Couldn't save ${downloadType.source.displayName} offline: ${result.error}"
                    }
                }
            }
        }

        // Live update: refresh when items are processed in a job targeting current folder
        viewModelScope.launch {
            var lastProcessedMap = mutableMapOf<String, Int>()
            JobManager.activeJobs.collect { jobs ->
                var shouldRefresh = false
                jobs.forEach { (id, job) ->
                    val target = when (val type = job.type) {
                        is dev.narayan.rose.filejob.FileJobType.Copy -> type.targetDir.toString()
                        is dev.narayan.rose.filejob.FileJobType.Move -> type.targetDir.toString()
                        else -> null
                    }

                    if (target == currentPath && currentPath.isNotEmpty()) {
                        val prevProcessed = lastProcessedMap[id] ?: 0
                        if (job.processedItems > prevProcessed) {
                            shouldRefresh = true
                        }
                    }
                    lastProcessedMap[id] = job.processedItems
                }

                // Clean up finished jobs from our tracker
                lastProcessedMap.keys.retainAll(jobs.keys)

                if (shouldRefresh) {
                    loadFiles(currentPath, showLoading = false)
                }
            }
        }
    }

    // ----- Theme settings -----

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        settings.themeMode = mode
    }

    fun setAmoledMode(enabled: Boolean) {
        _amoledMode.value = enabled
        settings.amoledMode = enabled
    }

    fun setDynamicColor(enabled: Boolean) {
        dynamicColorEnabled = enabled
        settings.dynamicColor = enabled
    }

    // ----- Display / view settings -----

    fun setGridView(enabled: Boolean) {
        _isGridView.value = enabled
        settings.isGridView = enabled
    }

    fun setCategoryGridView(enabled: Boolean) {
        _isCategoryGridView.value = enabled
        settings.isCategoryGridView = enabled
    }

    fun setFoldersFirst(enabled: Boolean) {
        _foldersFirst.value = enabled
        settings.foldersFirst = enabled
        loadFiles(currentPath)
    }

    fun setShowDetails(enabled: Boolean) {
        _showDetails.value = enabled
        settings.showDetails = enabled
    }

    fun setConfirmBeforeDelete(enabled: Boolean) {
        _confirmBeforeDelete.value = enabled
        settings.confirmBeforeDelete = enabled
    }

    fun setShowFileExtensions(enabled: Boolean) {
        _showFileExtensions.value = enabled
        settings.showFileExtensions = enabled
    }

    fun setShowListDividers(enabled: Boolean) {
        _showListDividers.value = enabled
        settings.showListDividers = enabled
    }

    fun setUseRecycleBin(enabled: Boolean) {
        _useRecycleBin.value = enabled
        settings.useRecycleBin = enabled
    }

    fun setShowQuickAccess(enabled: Boolean) {
        _showQuickAccess.value = enabled
        settings.showQuickAccess = enabled
    }

    fun setShowExternalStorage(enabled: Boolean) {
        _showExternalStorage.value = enabled
        settings.showExternalStorage = enabled
    }

    fun setQuickAccessExpanded(expanded: Boolean) {
        _quickAccessExpanded.value = expanded
        settings.quickAccessExpanded = expanded
    }

    fun setExternalStorageExpanded(expanded: Boolean) {
        _externalStorageExpanded.value = expanded
        settings.externalStorageExpanded = expanded
    }

    fun setStartPage(page: StartPage) {
        _startPage.value = page
        settings.startPage = page
    }

    fun setGridItemSize(size: GridItemSize) {
        _gridItemSize.value = size
        settings.gridItemSize = size.name
    }

    fun toggleHiddenFiles() {
        showHiddenFiles = !showHiddenFiles
        settings.showHiddenFiles = showHiddenFiles
        loadFiles(currentPath)
    }

    fun setSortOrder(sort: SortBy) {
        if (sortBy == sort) {
            sortOrder = if (sortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
        } else {
            sortBy = sort
            sortOrder = if (sort == SortBy.DATE) SortOrder.DESCENDING else SortOrder.ASCENDING
        }
        settings.sortBy = sortBy.name
        settings.sortOrder = sortOrder.name

        // Material Files style: changing sort should reset scroll positions for
        // the current navigation session, as the list layout has changed.
        pathScrollPositions.clear()

        // If we're in a virtual view (Category or Zip), we don't reload from currentPath
        // but instead just re-sort the existing lists.
        if (categoryFilterType != null) {
            categoryFiles = sortFileList(categoryFiles)
        } else if (currentZipFile != null) {
            files = sortFileList(files)
        } else if (currentPath.isEmpty()) {
            // Recent files should not be manually sorted as per requirements
        } else {
            // Re-sort what's already in memory right away so the change is visible
            // instantly, every time - relying only on the async loadFiles() below
            // meant this update could lose a race against the live folder
            // watcher/poll's own silentRefresh() landing at a similar time, which
            // is what made sorting feel like it "sometimes" needed a manual
            // refresh to actually show. The full reload still runs after this to
            // pick up authoritative sizes/metadata (e.g. real folder sizes for
            // Sort by Size), but the visible order is correct immediately.
            files = sortFileList(files)
            loadFiles(currentPath, showLoading = false)
        }
    }

    private fun sortFileList(list: List<FileItem>): List<FileItem> {
        val comparator = when (sortBy) {
            SortBy.NAME -> compareBy<FileItem> { it.name.lowercase() }
            SortBy.DATE -> compareBy<FileItem> { it.lastModified }.thenBy { it.name.lowercase() }
            SortBy.SIZE -> compareBy<FileItem> { it.size }.thenBy { it.name.lowercase() }
            SortBy.TYPE -> compareBy<FileItem> { it.fileType }.thenBy { it.name.lowercase() }
        }

        val finalComparator = if (sortOrder == SortOrder.DESCENDING) {
            comparator.reversed()
        } else {
            comparator
        }

        return list.sortedWith(
            if (foldersFirst) {
                compareByDescending<FileItem> { it.isDirectory }.thenComparing(finalComparator)
            } else {
                finalComparator
            }
        )
    }

    fun loadFiles(path: String, isManualRefresh: Boolean = false, showLoading: Boolean = true) {
        // If the path is actually a file, it's likely an archive we want to open.
        val file = File(path)
        val archiveExtensions = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
        val isArchive = file.exists() && file.isFile && (file.extension.lowercase() in archiveExtensions ||
                file.name.lowercase().let { name -> archiveExtensions.any { name.endsWith(".$it") } })

        if (isArchive) {
            openArchive(file)
            return
        }

        // Material Files style: if navigating to a new path, open it instantly, clear
        // current files, and show a loading spinner if the scan takes more than a moment.
        loadJob?.cancel()
        if (path != currentPath) {
            currentPath = path
            files = emptyList()
            isLoading = true
        } else {
            if (isManualRefresh) isRefreshing = true else if (showLoading) isLoading = true
        }

        val requestGeneration = ++filesGeneration
        loadJob = viewModelScope.launch {
            try {
                currentZipFile = null
                currentZipEntryPath = ""
                val directory = File(path)
                // ... (rest of the logic)

                // Android/data and Android/obb can never be reached via raw java.io.File,
                // even with MANAGE_EXTERNAL_STORAGE, on Android 12+ - that block is
                // hardcoded at the filesystem layer. They go through SafManager's single
                // root-level SAF grant instead (see SafManager.kt for why that's the
                // correct, current, no-root way - it's what Material Files does too).
                val isRestricted = SafManager.isRestrictedPath(path) ||
                        (path.startsWith("/") && !path.startsWith(Environment.getExternalStorageDirectory().absolutePath))

                val result = withContext(Dispatchers.IO) {
                    if (isRestricted) {
                        if (SafManager.hasPermission(getApplication(), path)) {
                            SafManager.listFiles(getApplication(), path).let { list ->
                                if (!showHiddenFiles) list.filter { !it.name.startsWith(".") } else list
                            }
                        } else {
                            // Don't trigger if already pending or if user recently denied this path
                            if (pendingSafPath == null && lastDeniedSafPath != path) {
                                withContext(Dispatchers.Main) {
                                    pendingSafPath = path
                                }
                            }
                            emptyList()
                        }
                    } else if (directory.exists() && directory.isDirectory) {
                        val rawFiles = directory.listFiles() ?: emptyArray()

                        val filteredFiles = if (!showHiddenFiles) {
                            rawFiles.filter { !it.name.startsWith(".") }
                        } else {
                            rawFiles.toList()
                        }

                        val fileList = filteredFiles.map { file ->
                            FileItem(
                                file = file,
                                itemCount = if (file.isDirectory) {
                                    val children = file.listFiles()
                                    if (!showHiddenFiles) {
                                        children?.count { !it.name.startsWith(".") }
                                    } else {
                                        children?.size
                                    }
                                } else null
                            )
                        }

                        // Folders report size 0 by default (a real total means
                        // recursively walking every folder, which is too slow to
                        // do on every navigation). Only pay that cost when the
                        // user has actually chosen to sort by size.
                        val sizedFileList = if (sortBy == SortBy.SIZE) {
                            withFolderSizes(fileList)
                        } else {
                            fileList
                        }

                        sortFileList(sizedFileList)
                    } else {
                        emptyList()
                    }
                }

                withContext(Dispatchers.Main) {
                    if (requestGeneration != filesGeneration) return@withContext
                    files = result
                    isLoading = false
                    isRefreshing = false
                    if (path == Environment.getExternalStorageDirectory().absolutePath) {
                        rootCache = result
                    }
                    if (!isRestricted && directory.exists() && directory.isDirectory) {
                        startWatchingDirectory(path)
                    } else {
                        stopWatchingDirectory()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    isRefreshing = false
                }
            }
        }
    }

    private var searchJob: Job? = null

    fun searchFiles(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults.clear()
            isRecursiveSearching = false
            return
        }

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(200)

            withContext(Dispatchers.Main) {
                searchResults.clear()
                isRecursiveSearching = true
            }

            val results = mutableListOf<FileItem>()
            val queryUri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(MediaStore.Files.FileColumns.DATA)

            val selection = if (currentPath.isNotEmpty() && currentPath != Environment.getExternalStorageDirectory().absolutePath) {
                "${MediaStore.Files.FileColumns.DATA} LIKE ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            } else {
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            }

            val selectionArgs = if (currentPath.isNotEmpty() && currentPath != Environment.getExternalStorageDirectory().absolutePath) {
                arrayOf("$currentPath%", "%$query%")
            } else {
                arrayOf("%$query%")
            }

            try {
                getApplication<Application>().contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataCol) ?: continue
                        val file = File(path)
                        // Note: file.exists() can be slow on large lists, but needed for accuracy
                        if (file.exists()) {
                            results.add(FileItem(file))
                        }
                        if (results.size >= 100) break
                    }
                }
            } catch (e: Exception) {}

            // MediaStore-only search misses anything MediaStore never scanned
            // (a zip copied in manually, WhatsApp/Telegram files, etc). Fill
            // remaining slots with a direct filesystem walk of the same scope,
            // deduping against what MediaStore already found.
            if (results.size < 100) {
                try {
                    val alreadyFound = results.mapTo(mutableSetOf()) { it.file.absolutePath }
                    val scanRoot = if (currentPath.isNotEmpty() && currentPath != Environment.getExternalStorageDirectory().absolutePath) {
                        File(currentPath)
                    } else {
                        Environment.getExternalStorageDirectory()
                    }
                    scanFilesystemForQuery(query, scanRoot, maxResults = 100 - results.size).forEach { file ->
                        if (file.absolutePath !in alreadyFound) {
                            results.add(FileItem(file))
                            alreadyFound.add(file.absolutePath)
                        }
                    }
                } catch (e: Exception) {}
            }

            withContext(Dispatchers.Main) {
                searchResults.addAll(results)
                isRecursiveSearching = false
            }
        }
    }

    fun openArchive(file: File, initialEntryPath: String = "") {
        stopWatchingDirectory()
        currentPath = file.parent ?: Environment.getExternalStorageDirectory().absolutePath
        currentZipFile = file
        currentZipEntryPath = initialEntryPath
        files = emptyList()
        isLoading = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = java.util.zip.ZipFile(file).use { it.entries().asSequence().toList() }
                zipEntriesCache = entries
                val sorted = sortFileList(buildZipDirectoryListing(entries, initialEntryPath, file))
                withContext(Dispatchers.Main) {
                    if (currentZipFile == file) {
                        files = sorted
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to open archive: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    // Builds the direct children (files + subfolders) of `dirPath` from the
    // already-read entry list, synthesizing folders for entries that never
    // got an explicit directory entry in the zip. No disk I/O - the central
    // directory was already read once in openArchive, so this is instant.
    private fun buildZipDirectoryListing(entries: List<ZipEntry>, dirPath: String, zipFile: File): List<FileItem> {
        val prefix = dirPath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val children = LinkedHashMap<String, FileItem>()

        // Tracks, for each direct subfolder of this level, the distinct names
        // of ITS OWN direct children (files or subfolders one level deeper).
        // A Set (not a running count) is essential here: a subfolder can have
        // many entries nested under it at various depths, but they all belong
        // to the same handful of immediate children - e.g. 50 files inside
        // "Photos/2024/" should make "2024" count as 1 item under "Photos",
        // not 50. Without this, every folder's "N items" label read 0
        // (FileItem.itemCount defaults to null/0, since the entries below
        // were never populating it at all) no matter what was really inside.
        val directChildNames = HashMap<String, MutableSet<String>>()

        for (entry in entries) {
            val name = entry.name.trimStart('/')
            if (!name.startsWith(prefix)) continue
            val remainder = name.removePrefix(prefix)
            if (remainder.isEmpty()) continue
            val slashIndex = remainder.indexOf('/')
            if (slashIndex == -1) {
                children[remainder] = FileItem(
                    file = File(zipFile, name),
                    isDirectory = false,
                    name = remainder,
                    size = entry.size,
                    lastModified = entry.time,
                    extension = remainder.substringAfterLast('.', "").lowercase(),
                    virtualZipSource = zipFile,
                    zipEntryPath = name
                )
            } else {
                val childName = remainder.substring(0, slashIndex)
                children.getOrPut(childName) {
                    FileItem(
                        file = File(zipFile, prefix + childName),
                        isDirectory = true,
                        name = childName,
                        size = 0,
                        lastModified = entry.time,
                        extension = "",
                        virtualZipSource = zipFile,
                        zipEntryPath = "$prefix$childName/"
                    )
                }

                // Everything after childName's own slash, up to its next
                // slash (or the end), is one direct child of childName.
                val grandRemainder = remainder.substring(slashIndex + 1)
                if (grandRemainder.isNotEmpty()) {
                    val grandSlash = grandRemainder.indexOf('/')
                    val grandName = if (grandSlash == -1) grandRemainder else grandRemainder.substring(0, grandSlash)
                    directChildNames.getOrPut(childName) { mutableSetOf() }.add(grandName)
                }
            }
        }

        return children.values.map { item ->
            if (item.isDirectory) {
                item.copy(itemCount = directChildNames[item.name]?.size ?: 0)
            } else {
                item
            }
        }
    }

    // Browses into a subfolder of the currently open zip using the cached
    // entry list - purely in-memory, no re-reading of the archive.
    fun navigateZipInto(entryPath: String) {
        val zipFile = currentZipFile ?: return
        exitSelectionMode()
        currentZipEntryPath = entryPath
        files = sortFileList(buildZipDirectoryListing(zipEntriesCache, entryPath, zipFile))
    }

    // Steps one level up within the open zip. Returns false once already at
    // the archive root, so the caller can fall back to exiting the archive.
    fun navigateZipUp(): Boolean {
        val zipFile = currentZipFile ?: return false
        if (currentZipEntryPath.isEmpty()) return false
        val parent = currentZipEntryPath.trimEnd('/').substringBeforeLast('/', "")
        val parentPath = if (parent.isEmpty()) "" else "$parent/"
        exitSelectionMode()
        currentZipEntryPath = parentPath
        files = sortFileList(buildZipDirectoryListing(zipEntriesCache, parentPath, zipFile))
        return true
    }


    fun clearExtraction() {
        extractionSource = null
    }

    fun prepareExtraction(file: File) {
        extractionSource = file
    }

    fun extractArchive(file: File, destDir: File = File(file.parent, file.nameWithoutExtension)) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!destDir.exists()) destDir.mkdirs()

                java.util.zip.ZipFile(file).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        val entryFile = File(destDir, entry.name)
                        if (!entryFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                            return@forEach
                        }
                        if (entry.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                entryFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    clearExtraction()
                    loadFiles(currentPath)
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    private fun fetchRecentFileItems(): List<FileItem> {
        val recents = mutableListOf<FileItem>()
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE
            )
            val queryUri = MediaStore.Files.getContentUri("external")
            getApplication<Application>().contentResolver.query(
                queryUri,
                projection,
                null,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                var count = 0
                while (cursor.moveToNext() && count < 50) {
                    val path = cursor.getString(dataCol) ?: continue
                    val file = File(path)
                    if (file.exists() && file.isFile) {
                        val name = cursor.getString(nameCol) ?: file.name
                        val size = cursor.getLong(sizeCol)
                        val date = cursor.getLong(dateCol) * 1000
                        val mime = cursor.getString(mimeCol)

                        val extension = file.extension.lowercase()
                        recents.add(FileItem(
                            file = file,
                            isDirectory = false,
                            name = name,
                            size = size,
                            lastModified = date,
                            extension = extension,
                            mimeType = mime
                        ))
                        count++
                    }
                }
            }
        } catch (e: Exception) {}
        return recents
    }

    fun loadRecentFiles() {
        stopWatchingDirectory()
        // Material Files style: don't clear the list before loading. Keeps the UI stable
        // and preserves scroll position while the background query is running.
        if (recentFiles.isEmpty()) isLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val recents = fetchRecentFileItems()
                withContext(Dispatchers.Main) {
                    recentFiles = recents
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    isRefreshing = false
                }
            }
        }
    }

    fun navigateTo(directory: File) {
        if (currentZipFile != null) return
        if (directory.isDirectory) {
            exitSelectionMode()
            loadFiles(directory.absolutePath)
        }
    }

    fun navigateUp(): Boolean {
        if (currentZipFile != null) {
            if (navigateZipUp()) return true
            return false
        }
        if (isSelectionMode) {
            exitSelectionMode()
            return true
        }
        if (SafManager.isSafUri(currentPath)) {
            // For now, navigating up from SAF just goes back to home
            // (or we could try to parse the URI and find parent document)
            return false
        }
        val currentFile = File(currentPath)
        val parent = currentFile.parentFile
        if (parent != null && (parent.canRead() || SafManager.isRestrictedPath(parent.absolutePath)) && currentPath != Environment.getExternalStorageDirectory().absolutePath) {
            loadFiles(parent.absolutePath)
            return true
        }
        return false
    }

    // Selection logic
    fun toggleSelection(fileItem: FileItem) {
        if (selectedFiles.contains(fileItem)) {
            selectedFiles.remove(fileItem)
            if (selectedFiles.isEmpty()) {
                isSelectionMode = false
            }
        } else {
            selectedFiles.add(fileItem)
            isSelectionMode = true
        }
    }

    // Explicit set (rather than toggle), used by the checkbox swipe-to-select
    // gesture where a single drag needs to force every item it passes over
    // to the same target state (all selected, or all deselected).
    fun setSelected(fileItem: FileItem, selected: Boolean) {
        if (selected) {
            if (!selectedFiles.contains(fileItem)) {
                selectedFiles.add(fileItem)
                isSelectionMode = true
            }
        } else {
            if (selectedFiles.remove(fileItem)) {
                if (selectedFiles.isEmpty()) {
                    isSelectionMode = false
                }
            }
        }
    }

    /**
     * Bulk-updates selection for a range of files during a drag-select gesture.
     * Items within [start]..[end] are set to [target] state, while items outside
     * this range are reverted to their [initialPaths] state.
     */
    fun setSelectedRange(files: List<FileItem>, start: Int, end: Int, target: Boolean, initialPaths: Set<String>) {
        val rangePaths = mutableSetOf<String>()
        for (i in start..end) {
            if (i in files.indices) {
                rangePaths.add(files[i].file.absolutePath)
            }
        }

        val newSelectionPaths = if (target) {
            initialPaths + rangePaths
        } else {
            initialPaths - rangePaths
        }

        // Use a set for O(1) lookups during the sync
        val currentPaths = selectedFiles.map { it.file.absolutePath }.toSet()

        // 1. Remove items that should no longer be selected
        selectedFiles.removeAll { it.file.absolutePath !in newSelectionPaths }

        // 2. Add items that are now selected but weren't before
        val toAdd = files.filter { it.file.absolutePath in newSelectionPaths && it.file.absolutePath !in currentPaths }
        if (toAdd.isNotEmpty()) {
            selectedFiles.addAll(toAdd)
        }

        isSelectionMode = selectedFiles.isNotEmpty()
    }

    fun exitSelectionMode() {
        selectedFiles.clear()
        isSelectionMode = false
    }

    fun clearClipboard() {
        clipboardFiles.clear()
        clipboardSourceZip = null
    }

    fun selectAll(currentFiles: List<FileItem>) {
        selectedFiles.clear()
        selectedFiles.addAll(currentFiles)
        isSelectionMode = true
    }

    // Clipboard logic
    fun copySelected() {
        clipboardFiles.clear()
        clipboardFiles.addAll(selectedFiles)
        // Snapshot which archive (if any) these entries came from *now*,
        // because currentZipFile gets reset to null as soon as the user
        // navigates anywhere else - relying on it at paste time was the
        // cause of the crash when pasting outside an opened zip.
        clipboardSourceZip = currentZipFile
        isCopyOperation = true
        exitSelectionMode()
    }

    fun moveSelected() {
        // Cut/move doesn't make sense for virtual zip entries - there's no
        // real file to move, only an archive entry to extract.
        if (currentZipFile != null) return
        clipboardFiles.clear()
        clipboardFiles.addAll(selectedFiles)
        clipboardSourceZip = null
        isCopyOperation = false
        exitSelectionMode()
    }

    fun compressSelected(zipName: String) {
        val filesToZip = selectedFiles.toList()
        val destFile = File(currentPath, if (zipName.lowercase().endsWith(".zip")) zipName else "$zipName.zip")

        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                FileOutputStream(destFile).use { fos ->
                    ZipOutputStream(fos).use { zipOut ->
                        filesToZip.forEach { item ->
                            addToZip(item.file, item.name, zipOut)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    exitSelectionMode()
                    loadFiles(currentPath)
                    highlightedFile = FileItem(destFile)
                    rescanForMediaStore(getApplication(), destFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to compress: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    isRefreshing = false
                }
            }
        }
    }

    private fun addToZip(file: File, fileName: String, zipOut: ZipOutputStream) {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null && children.isNotEmpty()) {
                for (child in children) {
                    addToZip(child, "$fileName/${child.name}", zipOut)
                }
            } else {
                // Empty directory
                zipOut.putNextEntry(ZipEntry("$fileName/"))
                zipOut.closeEntry()
            }
        } else {
            FileInputStream(file).use { fis ->
                val zipEntry = ZipEntry(fileName)
                zipOut.putNextEntry(zipEntry)
                fis.copyTo(zipOut)
                zipOut.closeEntry()
            }
        }
    }

    fun pasteFiles() {
        val itemsToPaste = clipboardFiles.toList()
        val sourceZip = clipboardSourceZip
        val copying = isCopyOperation
        val targetDir = currentPath

        if (sourceZip != null) {
            // Zip extraction still handled in ViewModel for now or could be a job too
            viewModelScope.launch(Dispatchers.IO) {
                isLoading = true
                val extractedDests = mutableListOf<File>()
                try {
                    java.util.zip.ZipFile(sourceZip).use { zip ->
                        itemsToPaste.forEach { fileItem ->
                            val dest = File(targetDir, fileItem.name)
                            extractEntry(zip, fileItem.zipEntryPath ?: fileItem.name, dest)
                            extractedDests.add(dest)
                        }
                    }
                } catch (e: Exception) {}
                withContext(Dispatchers.Main) {
                    clipboardFiles.clear()
                    clipboardSourceZip = null
                    loadFiles(targetDir)
                    isLoading = false
                    rescanForMediaStore(getApplication(), *extractedDests.toTypedArray())
                }
            }
        } else {
            val sources = itemsToPaste.map { it.file.absolutePath }
            val displayNames = itemsToPaste.map { it.name }
            if (copying) {
                dev.narayan.rose.filejob.FileJobService.startCopy(getApplication(), sources, displayNames, targetDir)
            } else {
                dev.narayan.rose.filejob.FileJobService.startMove(getApplication(), sources, displayNames, targetDir)
            }
            clipboardFiles.clear()
            exitSelectionMode()
            // We can't easily highlight the pasted files because they are processed in background
            // But we can reload the view after a short delay or use a listener
            viewModelScope.launch {
                kotlinx.coroutines.delay(500)
                loadFiles(targetDir)
            }
        }
    }

    // Extracts a single entry, or - when entryName is a folder - every entry
    // nested under it, preserving the folder's internal structure under dest.
    private fun extractEntry(zip: java.util.zip.ZipFile, entryName: String, dest: File) {
        val entry = zip.getEntry(entryName)
        if (entry != null && !entry.isDirectory) {
            dest.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        // Folder (explicit entry or only implied by nested files): pull every
        // entry whose name starts with this prefix and rebuild it under dest.
        dest.mkdirs()
        val prefix = entryName.trimEnd('/') + "/"
        zip.entries().asSequence().forEach { child ->
            if (child.name.startsWith(prefix) && !child.isDirectory) {
                val relative = child.name.removePrefix(prefix)
                val childDest = File(dest, relative)
                childDest.parentFile?.mkdirs()
                zip.getInputStream(child).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun copyFile(source: File, dest: File) {
        if (source.isDirectory) {
            if (!dest.exists()) dest.mkdirs()
            source.listFiles()?.forEach {
                copyFile(it, File(dest, it.name))
            }
        } else {
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun deleteSelected() {
        val itemsToDelete = selectedFiles.toList()

        val sources = itemsToDelete.map { it.file.absolutePath }
        val names = itemsToDelete.map { it.name }

        // No optimistic instant removal here anymore: the file list is left
        // as-is, and each item disappears (with its normal remove animation)
        // one by one, in real time, as FileOperationRunner reports it in
        // job.completedPaths - see the `activeJobs` collector in init{} and
        // `displayedFilesFinal` in FileExplorerScreen. Stripping everything
        // out up-front made every selected file vanish at once while the
        // progress card at the bottom kept counting on its own.
        if (useRecycleBin) {
            dev.narayan.rose.filejob.FileJobService.startRecycle(getApplication(), sources, names)
        } else {
            dev.narayan.rose.filejob.FileJobService.startDelete(getApplication(), sources, names)
        }
        exitSelectionMode()
    }

    fun deleteFile(fileItem: FileItem) {
        val path = fileItem.file.absolutePath

        // See deleteSelected() above - no optimistic removal, so the item
        // animates out for real once the job actually completes it.
        if (useRecycleBin && !SafManager.isRestrictedPath(path)) {
            dev.narayan.rose.filejob.FileJobService.startRecycle(getApplication(), listOf(path), listOf(fileItem.name))
        } else {
            dev.narayan.rose.filejob.FileJobService.startDelete(getApplication(), listOf(path), listOf(fileItem.name))
        }
    }

    fun resetFiles() {
        // We no longer clear everything here. Instead, we only reset state
        // that shouldn't persist across views. Clearing `files` causes
        // a "blank flash" during transitions.
        categoryTitle = null
        categoryFilterType = null
        currentZipFile = null
        currentZipEntryPath = ""

        // `categoryFiles` used to be left untouched here on the (wrong)
        // assumption that clearing it risked the same "blank flash" as
        // `files`. It doesn't - Category always shows its own loading
        // spinner while browseCategory() re-queries - but leaving it dirty
        // meant: browse Category A -> back to Home -> browse Category B.
        // The new FileExplorerScreen composition mounts and renders its
        // very first frame *before* browseCategory(B)'s LaunchedEffect has
        // a chance to run, so that first frame read whatever `categoryFiles`
        // still held from Category A - a one-frame flash of the wrong
        // category's files. Clearing it here, synchronously before the new
        // screen is even created, closes that window completely.
        categoryFiles = emptyList()
    }

    fun renameFile(fileItem: FileItem, newName: String) {
        val path = fileItem.file.absolutePath

        if (SafManager.isRestrictedPath(path)) {
            viewModelScope.launch(Dispatchers.IO) {
                val success = SafManager.rename(getApplication(), path, newName)
                withContext(Dispatchers.Main) {
                    if (success) loadFiles(currentPath)
                }
            }
            return
        }

        val newFile = File(fileItem.file.parent, newName)
        if (fileItem.file.renameTo(newFile)) {
            val updatedItem = FileItem(newFile)
            rescanForMediaStore(getApplication(), fileItem.file, newFile)

            // Update all visible lists immediately for instant feedback
            files = files.map {
                if (it.file.absolutePath == fileItem.file.absolutePath) updatedItem else it
            }

            recentFiles = recentFiles.map {
                if (it.file.absolutePath == fileItem.file.absolutePath) updatedItem else it
            }

            val searchIndex = searchResults.indexOfFirst { it.file.absolutePath == fileItem.file.absolutePath }
            if (searchIndex != -1) {
                searchResults[searchIndex] = updatedItem
            }

            // If we're in a normal directory, re-sort to keep order correct
            if (categoryFilterType == null && currentPath.isNotEmpty()) {
                files = sortFileList(files)
            }
        } else {
            errorMessage = "Couldn't rename folder. It might be in use or restricted."
        }
    }

    fun createFolder(name: String) {
        if (SafManager.isRestrictedPath(currentPath)) {
            viewModelScope.launch(Dispatchers.IO) {
                val path = "${currentPath.trimEnd('/')}/$name"
                val success = SafManager.createDirectory(getApplication(), path)
                withContext(Dispatchers.Main) {
                    if (success) loadFiles(currentPath)
                }
            }
            return
        }

        val newFolder = File(currentPath, name)
        if (newFolder.mkdir()) {
            loadFiles(currentPath)
        }
    }

    fun saveSharedFiles(uris: List<Uri>, destPath: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            var success = true
            try {
                val restrictedDest = SafManager.isRestrictedPath(destPath)
                uris.forEach { uri ->
                    val fileName = getFileNameFromUri(uri) ?: "shared_file_${System.currentTimeMillis()}"
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(uri)?.use { input ->
                        val output = if (restrictedDest) {
                            SafManager.openOutputStreamForNewFile(getApplication(), "${destPath.trimEnd('/')}/$fileName")
                        } else {
                            File(destPath, fileName).outputStream()
                        }
                        output?.use { out -> input.copyTo(out) } ?: run { success = false }
                    } ?: run { success = false }
                }
            } catch (e: Exception) {
                success = false
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    onComplete(success)
                    if (currentPath == destPath) loadFiles(destPath)
                }
            }
        }
    }

    fun extractSharedZip(uri: Uri, destPath: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            var success = true
            try {
                val tempFile = File(getApplication<Application>().cacheDir, "temp_extract.zip")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val restrictedDest = SafManager.isRestrictedPath(destPath)

                if (restrictedDest) {
                    SafManager.createDirectory(getApplication(), destPath)
                    java.util.zip.ZipFile(tempFile).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            val entryPath = "${destPath.trimEnd('/')}/${entry.name.trimEnd('/')}"
                            if (entry.isDirectory) {
                                SafManager.createDirectory(getApplication(), entryPath)
                            } else {
                                val parentPath = entryPath.substringBeforeLast("/")
                                SafManager.createDirectory(getApplication(), parentPath)
                                zip.getInputStream(entry).use { input ->
                                    SafManager.openOutputStreamForNewFile(getApplication(), entryPath)
                                        ?.use { output -> input.copyTo(output) }
                                }
                            }
                        }
                    }
                } else {
                    val destDir = File(destPath)
                    if (!destDir.exists()) destDir.mkdirs()

                    java.util.zip.ZipFile(tempFile).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            val entryFile = File(destDir, entry.name)
                            if (entry.isDirectory) {
                                entryFile.mkdirs()
                            } else {
                                entryFile.parentFile?.mkdirs()
                                zip.getInputStream(entry).use { input ->
                                    entryFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                    }
                }
                tempFile.delete()
            } catch (e: Exception) {
                success = false
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    onComplete(success)
                    if (currentPath == destPath) loadFiles(destPath)
                }
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    // ----- Category browsing (Home screen) -----

    var categoryTitle by mutableStateOf<String?>(null)
        private set

    var categoryFilterType by mutableStateOf<FileType?>(null)
        private set

    var categoryBucketId by mutableStateOf<String?>(null)
        private set

    var categoryCounts by mutableStateOf<Map<FileType, Int>>(emptyMap())
        private set

    var isCategoryCountsLoading by mutableStateOf(false)
        private set

    /** Optimized counting using MediaStore query counts */
    private var lastCategoryLoadTime = 0L
    fun loadCategoryCounts(force: Boolean = false) {
        if (isCategoryCountsLoading) return

        val now = System.currentTimeMillis()
        if (categoryCounts.isNotEmpty() && !force && (now - lastCategoryLoadTime < 30000)) return

        viewModelScope.launch(Dispatchers.IO) {
            isCategoryCountsLoading = true
            lastCategoryLoadTime = now
            val counts = mutableMapOf<FileType, Int>()
            val resolver = getApplication<Application>().contentResolver

            // Helper to count by selection
            fun countSelection(selection: String, excludeWhatsappTelegram: Boolean = true): Int {
                val excludedFolders = if (excludeWhatsappTelegram) {
                    listOf("WhatsApp/Media", "Telegram", ".thumbnails", "Android/data", "Android/obb")
                } else {
                    listOf(".thumbnails", "Android/data", "Android/obb")
                }
                val excludeSelection = excludedFolders.joinToString(" AND ") {
                    "${MediaStore.MediaColumns.DATA} NOT LIKE '%/$it/%'"
                }

                // Exclude hidden files and folders
                val noHiddenSelection = "${MediaStore.MediaColumns.DATA} NOT LIKE '%/.%' AND ${MediaStore.MediaColumns.DATA} NOT LIKE '%/..%'"

                val finalSelection = if (selection.isNotEmpty()) {
                    "($selection) AND ($excludeSelection) AND ($noHiddenSelection)"
                } else {
                    "($excludeSelection) AND ($noHiddenSelection)"
                }

                return try {
                    val uri = MediaStore.Files.getContentUri("external")
                    resolver.query(uri, arrayOf(MediaStore.Files.FileColumns._ID), finalSelection, null, null)?.use { cursor ->
                        cursor.count
                    } ?: 0
                } catch (e: Throwable) { 0 }
            }

            counts[FileType.IMAGE] = countSelection("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.MediaColumns.DATA} LIKE '%.jpg' OR ${MediaStore.MediaColumns.DATA} LIKE '%.jpeg' OR ${MediaStore.MediaColumns.DATA} LIKE '%.png' OR ${MediaStore.MediaColumns.DATA} LIKE '%.webp' OR ${MediaStore.MediaColumns.DATA} LIKE '%.gif' OR ${MediaStore.MediaColumns.DATA} LIKE '%.bmp' OR ${MediaStore.MediaColumns.DATA} LIKE '%.heic' OR ${MediaStore.MediaColumns.DATA} LIKE '%.heif' OR ${MediaStore.MediaColumns.DATA} LIKE '%.JPG' OR ${MediaStore.MediaColumns.DATA} LIKE '%.JPEG' OR ${MediaStore.MediaColumns.DATA} LIKE '%.PNG'")
            counts[FileType.VIDEO] = countSelection("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO} OR ${MediaStore.MediaColumns.DATA} LIKE '%.mp4' OR ${MediaStore.MediaColumns.DATA} LIKE '%.mkv' OR ${MediaStore.MediaColumns.DATA} LIKE '%.mov' OR ${MediaStore.MediaColumns.DATA} LIKE '%.avi' OR ${MediaStore.MediaColumns.DATA} LIKE '%.3gp' OR ${MediaStore.MediaColumns.DATA} LIKE '%.flv' OR ${MediaStore.MediaColumns.DATA} LIKE '%.wmv' OR ${MediaStore.MediaColumns.DATA} LIKE '%.MP4'")
            counts[FileType.AUDIO] = countSelection("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO} OR ${MediaStore.MediaColumns.DATA} LIKE '%.mp3' OR ${MediaStore.MediaColumns.DATA} LIKE '%.wav' OR ${MediaStore.MediaColumns.DATA} LIKE '%.ogg' OR ${MediaStore.MediaColumns.DATA} LIKE '%.m4a' OR ${MediaStore.MediaColumns.DATA} LIKE '%.flac' OR ${MediaStore.MediaColumns.DATA} LIKE '%.aac' OR ${MediaStore.MediaColumns.DATA} LIKE '%.MP3'")

            counts[FileType.PDF] = countSelection("${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/pdf' OR ${MediaStore.MediaColumns.DATA} LIKE '%.pdf' OR ${MediaStore.MediaColumns.DATA} LIKE '%.PDF'")
            counts[FileType.APK] = countSelection("${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/vnd.android.package-archive' OR ${MediaStore.MediaColumns.DATA} LIKE '%.apk' OR ${MediaStore.MediaColumns.DATA} LIKE '%.APK'")
            counts[FileType.ZIP] = countSelection("${MediaStore.MediaColumns.DATA} LIKE '%.zip' OR ${MediaStore.MediaColumns.DATA} LIKE '%.rar' OR ${MediaStore.MediaColumns.DATA} LIKE '%.7z' OR ${MediaStore.MediaColumns.DATA} LIKE '%.tar' OR ${MediaStore.MediaColumns.DATA} LIKE '%.gz' OR ${MediaStore.MediaColumns.DATA} LIKE '%.ZIP'", excludeWhatsappTelegram = false)
            counts[FileType.DOCUMENT] = countSelection("${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'text/%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/vnd.ms-%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/vnd.openxmlformats-officedocument%' OR ${MediaStore.MediaColumns.DATA} LIKE '%.txt' OR ${MediaStore.MediaColumns.DATA} LIKE '%.doc%' OR ${MediaStore.MediaColumns.DATA} LIKE '%.xls%' OR ${MediaStore.MediaColumns.DATA} LIKE '%.ppt%' OR ${MediaStore.MediaColumns.DATA} LIKE '%.pdf' OR ${MediaStore.MediaColumns.DATA} LIKE '%.rtf' OR ${MediaStore.MediaColumns.DATA} LIKE '%.TXT' OR ${MediaStore.MediaColumns.DATA} LIKE '%.DOC%' OR ${MediaStore.MediaColumns.DATA} LIKE '%.PDF'")

            withContext(Dispatchers.Main) {
                categoryCounts = counts
                isCategoryCountsLoading = false
            }
        }
    }

    fun browseCategory(type: FileType, title: String, bucketId: String? = null) {
        // ... (check to avoid re-querying if already showing)
        if (categoryFilterType == type && categoryTitle == title && categoryBucketId == bucketId && System.currentTimeMillis() < suppressSilentRefreshUntil) {
            return
        }

        stopWatchingDirectory()
        // Material Files style: don't clear the list if we're just refreshing
        // the exact same listing (same type AND same album/bucket). That
        // keeps the UI stable and allows animateItem() to smoothly slide
        // out removed items rather than the whole list flashing blank.
        //
        // A bucketId change (e.g. leaving an album back to the parent album
        // grid, or opening a different album) is a genuinely different
        // listing even though `type` stays the same - it used to be treated
        // like a same-listing refresh, so the OLD album's photos stayed on
        // screen (under the NEW crossfade key, since categoryTitle/
        // categoryBucketId are updated synchronously below) until the fresh
        // query finished, then swapped in - a one-frame flash of the wrong
        // list right as the "back" animation started. Clearing here too
        // closes that window, same fix as the type-change case.
        if (categoryFilterType != type || categoryBucketId != bucketId) {
            categoryFiles = emptyList()
            isLoading = true
        }
        currentZipFile = null
        currentZipEntryPath = ""
        categoryTitle = title
        categoryFilterType = type
        categoryBucketId = bucketId
        currentPath = ""

        val requestGeneration = ++categoryGeneration
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = mutableListOf<FileItem>()
                val queryUri = MediaStore.Files.getContentUri("external")
                val projection = mutableListOf(
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.MIME_TYPE
                )

                if (type == FileType.IMAGE) {
                    projection.add(MediaStore.Images.Media.BUCKET_ID)
                    projection.add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                }

                var categorySelection = when (type) {
                    FileType.IMAGE -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.MediaColumns.DATA} LIKE '%.jpg' OR ${MediaStore.MediaColumns.DATA} LIKE '%.jpeg' OR ${MediaStore.MediaColumns.DATA} LIKE '%.png' OR ${MediaStore.MediaColumns.DATA} LIKE '%.webp' OR ${MediaStore.MediaColumns.DATA} LIKE '%.JPG' OR ${MediaStore.MediaColumns.DATA} LIKE '%.JPEG'"
                    FileType.VIDEO -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO} OR ${MediaStore.MediaColumns.DATA} LIKE '%.mp4' OR ${MediaStore.MediaColumns.DATA} LIKE '%.mkv' OR ${MediaStore.MediaColumns.DATA} LIKE '%.MP4'"
                    FileType.AUDIO -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO} OR ${MediaStore.MediaColumns.DATA} LIKE '%.mp3' OR ${MediaStore.MediaColumns.DATA} LIKE '%.m4a' OR ${MediaStore.MediaColumns.DATA} LIKE '%.MP3'"
                    FileType.PDF -> "${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/pdf' OR ${MediaStore.MediaColumns.DATA} LIKE '%.pdf' OR ${MediaStore.MediaColumns.DATA} LIKE '%.PDF'"
                    FileType.APK -> "${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/vnd.android.package-archive' OR ${MediaStore.MediaColumns.DATA} LIKE '%.apk' OR ${MediaStore.MediaColumns.DATA} LIKE '%.APK'"
                    FileType.ZIP -> "${MediaStore.MediaColumns.DATA} LIKE '%.zip' OR ${MediaStore.MediaColumns.DATA} LIKE '%.rar' OR ${MediaStore.MediaColumns.DATA} LIKE '%.7z' OR ${MediaStore.MediaColumns.DATA} LIKE '%.tar' OR ${MediaStore.MediaColumns.DATA} LIKE '%.gz' OR ${MediaStore.MediaColumns.DATA} LIKE '%.ZIP'"
                    FileType.DOCUMENT -> "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'text/%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/vnd.ms-%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/vnd.openxmlformats-officedocument%' OR ${MediaStore.MediaColumns.DATA} LIKE '%.txt' OR ${MediaStore.MediaColumns.DATA} LIKE '%.doc%' OR ${MediaStore.MediaColumns.DATA} LIKE '%.xls%' OR ${MediaStore.MediaColumns.DATA} LIKE '%.ppt%' OR ${MediaStore.MediaColumns.DATA} LIKE '%.pdf' OR ${MediaStore.MediaColumns.DATA} LIKE '%.TXT' OR ${MediaStore.MediaColumns.DATA} LIKE '%.DOC%'"
                    else -> null
                }

                if (type == FileType.IMAGE && bucketId != null) {
                    categorySelection = "($categorySelection) AND (${MediaStore.Images.Media.BUCKET_ID} = '$bucketId')"
                }

                val excludedFolders = if (type == FileType.ZIP) {
                    listOf(".thumbnails", "Android/data", "Android/obb")
                } else {
                    listOf("WhatsApp/Media", "Telegram", ".thumbnails", "Android/data", "Android/obb")
                }
                val excludeSelection = excludedFolders.joinToString(" AND ") {
                    "${MediaStore.MediaColumns.DATA} NOT LIKE '%/$it/%'"
                }

                // Exclude hidden files and folders
                val noHiddenSelection = "${MediaStore.MediaColumns.DATA} NOT LIKE '%/.%' AND ${MediaStore.MediaColumns.DATA} NOT LIKE '%/..%'"

                val selection = if (categorySelection != null) {
                    "($categorySelection) AND ($excludeSelection) AND ($noHiddenSelection)"
                } else {
                    "($excludeSelection) AND ($noHiddenSelection)"
                }

                val albumMap = mutableMapOf<String, Pair<String, Int>>() // BucketID -> (BucketName, Count)
                val albumLastModified = mutableMapOf<String, Long>()
                val albumThumbnail = mutableMapOf<String, String>()

                getApplication<Application>().contentResolver.query(queryUri, projection.toTypedArray(), selection, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                    val bucketIdCol = if (type == FileType.IMAGE) cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID) else -1
                    val bucketNameCol = if (type == FileType.IMAGE) cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME) else -1

                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataCol) ?: continue
                        val date = cursor.getLong(dateCol) * 1000
                        val bId = if (bucketIdCol != -1) cursor.getString(bucketIdCol) else null

                        if (type == FileType.IMAGE && bucketId == null) {
                            val id = bId ?: "unknown"
                            val bName = cursor.getString(bucketNameCol) ?: "Unknown Album"
                            val current = albumMap.getOrDefault(id, bName to 0)
                            albumMap[id] = bName to (current.second + 1)
                            if (date > (albumLastModified[id] ?: 0L)) {
                                albumLastModified[id] = date
                                albumThumbnail[id] = path
                            }
                            continue
                        }

                        val name = cursor.getString(nameCol) ?: File(path).name
                        val size = cursor.getLong(sizeCol)
                        val mime = cursor.getString(mimeCol)

                        val file = File(path)
                        if (file.exists() && file.isFile) {
                            results.add(FileItem(
                                file = file,
                                isDirectory = false,
                                name = name,
                                size = size,
                                lastModified = date,
                                extension = name.substringAfterLast('.', "").lowercase(),
                                mimeType = mime,
                                bucketId = bId
                            ))
                        }
                    }
                }

                if (type == FileType.IMAGE && bucketId == null) {
                    albumMap.forEach { (id, pair) ->
                        val bName = pair.first
                        val bCount = pair.second
                        val thumbPath = albumThumbnail[id]
                        val albumDir = if (thumbPath != null) File(thumbPath).parentFile else null

                        results.add(FileItem(
                            file = albumDir ?: File("album:$id"), // Special path fallback
                            isDirectory = true,
                            name = bName,
                            size = 0,
                            lastModified = albumLastModified[id] ?: 0L,
                            extension = "",
                            itemCount = bCount,
                            bucketId = id,
                            thumbnailPath = thumbPath
                        ))
                    }
                }

                withContext(Dispatchers.Main) {
                    if (requestGeneration == categoryGeneration) {
                        categoryFiles = sortFileList(results)
                        isLoading = false
                        isRefreshing = false
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    if (requestGeneration == categoryGeneration) {
                        errorMessage = "Failed to load category: ${e.message}"
                        isLoading = false
                        isRefreshing = false
                    }
                }
            }
        }
    }

    fun exitCategoryMode() {
        categoryTitle = null
        categoryFilterType = null
        categoryFiles = emptyList()
        categoryBucketId = null
        loadFiles(currentPath, showLoading = false)
    }

    // ----- Quick access (Home screen) -----

    var quickAccessRemoved by mutableStateOf(settings.quickAccessRemoved)
        private set

    var quickAccessCustomPaths by mutableStateOf(settings.quickAccessCustomPaths)
        private set

    var externalStorages by mutableStateOf(
        settings.externalStorages.map {
            val parts = it.split("|")
            StorageDevice.Logical(parts[0], Uri.parse(parts[1]))
        }
    )
        private set

    var storageDevices = mutableStateListOf<StorageDevice>()
        private set

    fun loadStorageDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            val devices = mutableListOf<StorageDevice>()

            // 1. Internal Storage
            val internalFile = Environment.getExternalStorageDirectory()
            val internalStats = StatFs(internalFile.path)
            devices.add(
                StorageDevice.Physical(
                    name = "Internal Storage",
                    path = internalFile.absolutePath,
                    totalBytes = internalStats.totalBytes,
                    availableBytes = internalStats.availableBytes,
                    isSdCard = false
                )
            )

            // 2. SD Cards and other physical volumes
            val sm = getApplication<Application>().getSystemService(android.content.Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            sm.storageVolumes.forEach { volume ->
                if (!volume.isPrimary && volume.state == Environment.MEDIA_MOUNTED) {
                    val path = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        volume.directory?.absolutePath
                    } else {
                        // Fallback for older APIs - this is a bit hacky but often works
                        // or we could use reflection to call getPath()
                        try {
                            val getPath = volume.javaClass.getMethod("getPath")
                            getPath.invoke(volume) as String
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (path != null) {
                        val stats = StatFs(path)
                        devices.add(
                            StorageDevice.Physical(
                                name = volume.getDescription(getApplication()) ?: "SD Card",
                                path = path,
                                totalBytes = stats.totalBytes,
                                availableBytes = stats.availableBytes,
                                isSdCard = volume.isRemovable
                            )
                        )
                    }
                }
            }

            withContext(Dispatchers.Main) {
                storageDevices.clear()
                storageDevices.addAll(devices)
            }
        }
    }

    fun addExternalStorage(name: String, treeUri: Uri) {
        val newStorage = StorageDevice.Logical(name, treeUri)
        if (externalStorages.any { it.treeUri == treeUri }) return

        val updated = externalStorages + newStorage
        externalStorages = updated
        settings.externalStorages = updated.map { "${it.name}|${it.treeUri}" }
        loadStorageDevices()
    }

    fun removeExternalStorage(storage: StorageDevice.Logical) {
        val updated = externalStorages.filter { it.treeUri != storage.treeUri }
        externalStorages = updated
        settings.externalStorages = updated.map { "${it.name}|${it.treeUri}" }
        loadStorageDevices()
    }

    var onRequestAddStorage: () -> Unit = {}

    var offlineFiles: Set<String> by mutableStateOf(settings.offlineFiles)
    val activeJobs = JobManager.activeJobs

    // Some cloud providers (Google Drive in particular) report a display
    // name with no extension for ordinary binary files even when the MIME
    // type is known - reconstruct it so the saved copy is a real .mp4/.png
    // on disk instead of an extension-less "unknown file". Used consistently
    // for save/remove/lookup so all three agree on the actual filename.
    private fun offlineFileName(fileItem: FileItem): String =
        SafManager.ensureFileExtension(fileItem.name, fileItem.mimeType)

    fun toggleOfflineStatus(fileItem: FileItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = fileItem.file.absolutePath
            val fileName = offlineFileName(fileItem)
            if (offlineFiles.contains(path)) {
                // Remove from offline
                val localFile = File(getApplication<Application>().getExternalFilesDir("offline"), fileName)
                if (localFile.exists()) localFile.delete()

                val updated = offlineFiles - path
                withContext(Dispatchers.Main) {
                    offlineFiles = updated
                    settings.offlineFiles = updated
                }
            } else {
                // Save as offline.
                try {
                    val offlineDir = getApplication<Application>().getExternalFilesDir("offline") ?: return@launch
                    if (!offlineDir.exists()) offlineDir.mkdirs()

                    val localFile = File(offlineDir, fileName)

                    dev.narayan.rose.filejob.FileJobService.startDownload(
                        getApplication(),
                        path,
                        fileName,
                        localFile.absolutePath
                    )
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Failed to save offline: ${e.message}"
                    }
                }
            }
        }
    }

    fun isFileOffline(path: String): Boolean = offlineFiles.contains(path)

    fun getLocalOfflineFile(fileItem: FileItem): File? {
        if (!isFileOffline(fileItem.file.absolutePath)) return null
        val offlineDir = getApplication<Application>().getExternalFilesDir("offline") ?: return null
        val localFile = File(offlineDir, offlineFileName(fileItem))
        return if (localFile.exists()) localFile else null
    }

    /** Long-press "remove" for one of the built-in Downloads/Camera/Documents
     * quick access entries. Identified by a stable id rather than path. */
    fun removeDefaultQuickAccess(id: String) {
        val updated = quickAccessRemoved + id
        quickAccessRemoved = updated
        settings.quickAccessRemoved = updated
    }

    fun addQuickAccessFolder(path: String) {
        if (quickAccessCustomPaths.contains(path)) return
        val updated = quickAccessCustomPaths + path
        quickAccessCustomPaths = updated
        settings.quickAccessCustomPaths = updated
    }

    fun removeQuickAccessFolder(path: String) {
        val updated = quickAccessCustomPaths - path
        quickAccessCustomPaths = updated
        settings.quickAccessCustomPaths = updated
    }

    override fun onCleared() {
        super.onCleared()
        stopWatchingDirectory()
    }
}