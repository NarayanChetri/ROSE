package dev.narayan.rose

import android.content.Context
import android.content.Intent
import dev.narayan.rose.document.MarkdownText
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.LruCache
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.core.graphics.drawable.toBitmap
import dev.narayan.rose.filejob.FileJob
import dev.narayan.rose.filejob.FileJobType
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.scrollBy
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val LARGE_FILE_THRESHOLD = 500 * 1024 * 1024L

// ColorOS-style "swipe over checkboxes to multi-select" support: tracks the
// on-screen bounds of every currently-composed checkbox so a single
// continuous drag starting on one checkbox can toggle every other checkbox
// it passes over, all sharing the same select/deselect direction.
@Stable
class CheckboxDragSelectState {
    val itemBounds = mutableMapOf<String, Rect>()
    var lastToggledKey: String? = null // For compatibility with RecycleBinScreen
    var dragPosition by mutableStateOf<Offset?>(null)
    var containerBounds by mutableStateOf<Rect?>(null)

    // Internal state for the current drag session (non-observable)
    var startIndex: Int = -1
    val initialSelectedPaths = mutableSetOf<String>()
    var selectTarget: Boolean = true
    var lastTargetIndex: Int = -1
}

@Composable
internal fun rememberCheckboxDragSelectState() = remember { CheckboxDragSelectState() }

// Container-level replacement for the old per-checkbox `detectDragGestures`.
// The old version attached the gesture directly to each Checkbox, which meant
// the whole gesture died the moment the checkbox that *started* the drag got
// scrolled far enough off-screen to be recycled by the LazyColumn - exactly
// what tends to happen a second or two into a bottom/top-edge auto-scroll,
// since the origin checkbox is usually nowhere near the edge doing the
// scrolling. Living here, on the same persistent Box used for
// `containerBounds`, this coroutine is never torn down by list recycling, so
// a slide-select + auto-scroll keeps working no matter how far the list
// scrolls, until the finger actually lifts.
//
// To keep the exact behavior the per-checkbox version got "for free" by
// being the innermost element (Compose hit-tests/dispatches inner-most-first
// during the Main pass, so a checkbox's own gesture detector used to always
// get first refusal over the list's scrolling), this reads pointer events on
// PointerEventPass.Initial - which runs top-down, *before* any child
// (LazyColumn's own scroll gesture, PullToRefreshBox's nested scroll, the
// Checkbox's ripple/click) sees them in its own Main pass. That lets a drag
// that starts on a checkbox reliably win over list scrolling once it turns
// into an actual drag, while a drag that starts anywhere else is left
// completely untouched (never consumed) so scrolling, pull-to-refresh, row
// taps and long-presses all behave exactly as if this detector weren't here.
private suspend fun PointerInputScope.detectCheckboxDragSelect(
    dragSelectState: CheckboxDragSelectState,
    displayedFiles: List<FileItem>,
    viewModel: RoseViewModel
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val containerOrigin = dragSelectState.containerBounds?.topLeft ?: Offset.Zero
        val windowDownPos = containerOrigin + down.position
        val originKey = dragSelectState.itemBounds.entries
            .firstOrNull { (_, rect) -> rect.contains(windowDownPos) }
            ?.key
            ?: return@awaitEachGesture // Didn't start on a checkbox - leave this gesture alone entirely.

        val touchSlop = viewConfiguration.touchSlop
        var dragging = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break

            if (!dragging) {
                if (!change.pressed) break // Lifted before slop
                if ((change.position - down.position).getDistance() < touchSlop) continue

                dragging = true
                change.consume()

                // Initialize drag session
                val startIdx = displayedFiles.indexOfFirst { it.file.absolutePath == originKey }
                if (startIdx == -1) break

                dragSelectState.startIndex = startIdx
                dragSelectState.lastTargetIndex = startIdx
                dragSelectState.selectTarget = !viewModel.selectedFiles.any { it.file.absolutePath == originKey }
                dragSelectState.initialSelectedPaths.clear()
                viewModel.selectedFiles.forEach { dragSelectState.initialSelectedPaths.add(it.file.absolutePath) }

                viewModel.setSelected(displayedFiles[startIdx], dragSelectState.selectTarget)
                dragSelectState.dragPosition = containerOrigin + change.position
                continue
            }

            change.consume()
            if (!change.pressed) break // Finger lifted - end of gesture.

            val windowPos = containerOrigin + change.position
            dragSelectState.dragPosition = windowPos

            performDragSelect(dragSelectState, displayedFiles, viewModel)
        }

        dragSelectState.startIndex = -1
        dragSelectState.lastTargetIndex = -1
        dragSelectState.dragPosition = null
    }
}


/**
 * Updates the selection range based on the current drag position. This is called both from
 * the pointer input loop and the auto-scroll loop to ensure selection stays in sync
 * even when the finger is still but the list is scrolling.
 */
private fun performDragSelect(
    state: CheckboxDragSelectState,
    files: List<FileItem>,
    viewModel: RoseViewModel
) {
    val pos = state.dragPosition ?: return
    val startIndex = state.startIndex
    if (startIndex == -1) return

    // Find which item index is currently under (or nearest to) the finger.
    var targetIndex = -1

    // 1. Direct hit-test against visible items
    val itemUnderFinger = state.itemBounds.entries.firstOrNull { it.value.contains(pos) }
    if (itemUnderFinger != null) {
        targetIndex = files.indexOfFirst { it.file.absolutePath == itemUnderFinger.key }
    } else {
        // 2. Fallback: Find by Y-coordinate match (handles dragging between checkboxes in a row)
        val itemsInRow = files.mapIndexedNotNull { index, item ->
            val rect = state.itemBounds[item.file.absolutePath]
            if (rect != null && pos.y >= rect.top && pos.y <= rect.bottom) index to rect else null
        }

        if (itemsInRow.isNotEmpty()) {
            // Pick the item in this row horizontally closest to the finger
            targetIndex = itemsInRow.minByOrNull { (_, rect) ->
                when {
                    pos.x < rect.left -> rect.left - pos.x
                    pos.x > rect.right -> pos.x - rect.right
                    else -> 0f
                }
            }?.first ?: -1
        } else {
            // 3. Fallback for auto-scroll: if finger is above/below all currently visible items,
            // assume the target is the first or last visible item respectively.
            val visibleIndices = files.mapIndexedNotNull { index, item ->
                if (state.itemBounds.containsKey(item.file.absolutePath)) index else null
            }
            if (visibleIndices.isNotEmpty()) {
                val firstVisible = visibleIndices.first()
                val lastVisible = visibleIndices.last()
                val firstRect = state.itemBounds[files[firstVisible].file.absolutePath]!!
                val lastRect = state.itemBounds[files[lastVisible].file.absolutePath]!!

                if (pos.y < firstRect.top) targetIndex = firstVisible
                else if (pos.y > lastRect.bottom) targetIndex = lastVisible
            }
        }
    }

    // Only trigger a ViewModel update if the target index has actually moved to a new item.
    if (targetIndex != -1 && targetIndex != state.lastTargetIndex) {
        state.lastTargetIndex = targetIndex
        viewModel.setSelectedRange(
            files,
            minOf(startIndex, targetIndex),
            maxOf(startIndex, targetIndex),
            state.selectTarget,
            state.initialSelectedPaths
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun FileExplorerScreen(
    viewModel: RoseViewModel = viewModel(),
    startPath: String? = null,
    startCategory: Pair<FileType, String>? = null,
    startRecent: Boolean = false,
    startFavourites: Boolean = false,
    fromHome: Boolean = false,
    highlightFile: FileItem? = null, // New parameter
    isFromAllFiles: Boolean = false, // New parameter for seamless transition
    resumePath: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null, // New parameter
    animatedVisibilityScope: AnimatedVisibilityScope? = null, // New parameter
    onExitToHome: (() -> Unit)? = null,
    onFileClick: (FileItem) -> Unit = {},
    onOpenWithClick: (FileItem) -> Unit = {},
    onShareClick: (List<FileItem>) -> Unit = {},
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    backHandlerEnabled: Boolean = true,
    onSearchActiveChange: (Boolean) -> Unit = {},
    onOpenPath: ((String, FileItem?) -> Unit)? = null,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets
) {
    val context = LocalContext.current

    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<FileItem?>(null) }
    var showExtractionDialog by remember { mutableStateOf<FileItem?>(null) }
    var pendingDelete: PendingDelete? by remember { mutableStateOf<PendingDelete?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(isSearching) {
        onSearchActiveChange(isSearching)
    }
    var currentView by remember(startPath, startCategory, startRecent, startFavourites) {
        mutableStateOf(
            when {
                startCategory != null -> "Category"
                startRecent -> "Recent"
                startFavourites -> "Favourites"
                else -> "Files"
            }
        )
    }

    // Screens: "Main", "Settings", "About"
    var activeScreen by remember { mutableStateOf("Main") }
    var lastNonZipView by remember { mutableStateOf(currentView) }

    val coroutineScope = rememberCoroutineScope()
    val isDocumentCategory = currentView == "Category" && viewModel.categoryFilterType == FileType.DOCUMENT
    val docCategoryCounts = remember(viewModel.categoryFiles, isDocumentCategory) {
        if (isDocumentCategory) computeDocumentCategoryCounts(viewModel.categoryFiles) else emptyMap()
    }
    val orderedDocCategories = remember(viewModel.categoryFiles, docCategoryCounts, isDocumentCategory) {
        if (isDocumentCategory) getOrderedDocumentCategories(viewModel.categoryFiles, docCategoryCounts) else emptyList()
    }
    val docPagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { if (orderedDocCategories.isNotEmpty()) orderedDocCategories.size else 1 }
    )
    val activeDocCategory = remember(docPagerState.currentPage, orderedDocCategories) {
        orderedDocCategories.getOrElse(docPagerState.currentPage.coerceIn(0, (orderedDocCategories.size - 1).coerceAtLeast(0))) {
            DocumentTypeCategory.ALL
        }
    }

    val lifecycleOwner: androidx.lifecycle.LifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event: androidx.lifecycle.Lifecycle.Event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                // Refresh when coming back from other apps (like PC transfer tools)
                // Fix: Don't trigger auto-load if we're already loading something (like opening an archive from Home)
                if (viewModel.isLoading || viewModel.isRefreshing || viewModel.currentZipFile != null) return@LifecycleEventObserver

                when {
                    currentView == "Recent" -> viewModel.loadRecentFiles()
                    currentView == "Category" -> {
                        val type = viewModel.categoryFilterType
                        val title = viewModel.categoryTitle
                        val bucketId = viewModel.categoryBucketId
                        if (type != null && title != null) viewModel.browseCategory(type, title, bucketId = bucketId)
                    }
                    else -> {
                        // Only auto-refresh if it's a normal directory view
                        if (viewModel.currentPath.isNotEmpty()) {
                            viewModel.loadFiles(viewModel.currentPath, showLoading = false)
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(startPath, startCategory, startRecent, startFavourites, resumePath) {
        if (highlightFile != null) {
            viewModel.highlightedFile = highlightFile
        }
        if (resumePath != null) {
            if (viewModel.currentPath != resumePath || viewModel.files.isEmpty()) {
                viewModel.loadFiles(resumePath)
            } else {
                viewModel.loadFiles(resumePath, isManualRefresh = true, showLoading = false)
            }
            return@LaunchedEffect
        }
        when {
            startCategory != null -> {
                if (viewModel.categoryFilterType != startCategory.first || (viewModel.categoryBucketId == null && viewModel.categoryTitle != startCategory.second) || (viewModel.categoryFiles.isEmpty() && !viewModel.isCategoryLoading)) {
                    viewModel.browseCategory(startCategory.first, startCategory.second)
                }
            }
            startRecent -> {
                viewModel.clearScrollPosition("recent")
                viewModel.loadRecentFiles()
                listState.scrollToItem(0, 0)
                gridState.scrollToItem(0, 0)
            }
            startFavourites -> {
                viewModel.clearScrollPosition("favourites")
                listState.scrollToItem(0, 0)
                gridState.scrollToItem(0, 0)
            }
            startPath != null -> {
                if (SafManager.isSafUri(startPath)) {
                    viewModel.loadFiles(startPath)
                } else {
                    val file = File(startPath)
                    val archiveExtensions = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "apk", "xapk", "apks")
                    val isArchive = file.extension.lowercase() in archiveExtensions ||
                            file.name.lowercase().let { name -> archiveExtensions.any { name.endsWith(".$it") } }

                    val isDirectory = file.isDirectory || SafManager.isRestrictedPath(startPath) || (viewModel.useRoot && RootManager.isRootPath(startPath))

                    if (file.isFile && isArchive) {
                        viewModel.openArchive(file)
                    } else if (isDirectory || startPath != viewModel.currentPath || viewModel.files.isEmpty()) {
                        viewModel.loadFiles(startPath)
                    }
                }
            }
        }
    }

    // Live USB/SD removal feedback: when the volume the user is browsing
    // is ejected, navigate straight back to Home and show a brief message
    // rather than leaving the user on a stale, empty directory listing.
    LaunchedEffect(viewModel.storageRemovedEvent) {
        val ejectedPath = viewModel.storageRemovedEvent ?: return@LaunchedEffect
        viewModel.clearStorageRemovedEvent()
        // Show a Toast so the user understands why the screen changed
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.toast_usb_drive_removed),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        // Navigate back to Home
        onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
    }

    BackHandler(enabled = backHandlerEnabled) {

        if (viewModel.propertiesFile != null) {
            viewModel.closeProperties()
        } else if (activeScreen != "Main") {
            activeScreen = "Main"
        } else if (viewModel.isSelectionMode) {
            viewModel.exitSelectionMode()
        } else if (isSearching) {
            isSearching = false
            searchQuery = ""
            viewModel.searchFiles("")
            viewModel.activeSearchFilter = SearchFilter.ALL
        } else if (viewModel.currentZipFile != null) {
            // If we are deep inside a zip, go up one level.
            // If we are at the root of the zip, exit the zip view.
            if (viewModel.currentZipEntryPath.isNotEmpty()) {
                viewModel.navigateZipUp()
            } else {
                // At zip root. Decide whether to exit to Home or return to parent folder.
                if (fromHome && (viewModel.currentZipFile?.absolutePath == startPath || viewModel.currentZipSourcePath == startPath)) {
                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                } else if (lastNonZipView != "Files") {
                    currentView = lastNonZipView
                    if (currentView == "Recent") viewModel.loadRecentFiles()
                    else if (currentView == "Category") {
                        val type = viewModel.categoryFilterType
                        val title = viewModel.categoryTitle
                        if (type != null && title != null) viewModel.browseCategory(type, title, bucketId = viewModel.categoryBucketId)
                    }
                    viewModel.closeArchive()
                } else {
                    viewModel.closeArchive()
                    viewModel.loadFiles(viewModel.currentPath)
                }
            }
        } else if (currentView == "Category") {
            if (viewModel.categoryBucketId != null) {
                viewModel.browseCategory(FileType.IMAGE, context.getString(R.string.category_photos))
            } else {
                viewModel.exitCategoryMode()
                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
            }
        } else if (currentView == "Recent" || currentView == "Favourites") {
            viewModel.resetFiles()
            viewModel.exitCategoryMode()
            onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
        } else {
            // Quick Access (and any other fromHome entry point, e.g. a file's
            // "Open location") opens a folder that isn't actually the storage
            // root, so the old `navigateUp()` call below happily found a real
            // parent to go to and walked into it instead of returning to
            // Home - back from e.g. Downloads landed in the parent of
            // Downloads rather than the home screen. Only once the user has
            // navigated at least one level deeper than the Quick Access entry
            // point should back start climbing the folder tree; from the
            // entry point itself, back should always return straight to Home
            // (whose scroll position is already preserved separately).
            if (fromHome && viewModel.currentPath == startPath) {
                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
            } else if (!viewModel.navigateUp()) {
                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
            }
        }
    }

    val displayedFiles = if (isSearching && searchQuery.isNotBlank()) {
        if (currentView == "Category") viewModel.searchResults
        else if (currentView == "Favourites") viewModel.sortFileList(viewModel.getFavouriteFileItems().filter { it.name.contains(searchQuery, ignoreCase = true) })
        else viewModel.filteredSearchResults
    } else if (currentView == "Recent") {
        viewModel.recentFiles
    } else if (currentView == "Favourites") {
        viewModel.sortFileList(viewModel.getFavouriteFileItems())
    } else if (currentView == "Category") {
        viewModel.categoryFiles
    } else {
        viewModel.files
    }

    val effectiveDisplayedFiles = if (isDocumentCategory) {
        filterDocumentFiles(displayedFiles, activeDocCategory)
    } else {
        displayedFiles
    }


    // With the full-screen crossfade no longer keyed on `path`, the LazyColumn/LazyVerticalGrid
    // call sites persist across folder navigation, so their scroll state persists too unless we
    // reset it ourselves. Key this on whatever actually identifies "a different list of files":
    // the path for "Files" (including archive file & inner entry path for virtual zip views),
    // the category title for "Category", and a constant for "Recent".
    val activeScrollKey = when (currentView) {
        "Files" -> if (viewModel.currentZipFile != null) {
            "zip://${viewModel.currentZipFile?.absolutePath}:${viewModel.currentZipEntryPath}"
        } else {
            viewModel.currentPath
        }
        "Category" -> "category:${viewModel.categoryTitle ?: ""}:${viewModel.categoryBucketId ?: ""}"
        "Recent" -> "recent"
        else -> currentView
    }

    var refreshAnimationToken by remember { mutableStateOf(0) }
    var isPullRefreshing by remember { mutableStateOf(false) }

    val scrollResetKey = if (isSearching) {
        "search_${searchQuery}_${viewModel.activeSearchFilter}_$currentView"
    } else when (currentView) {
        "Files" -> "${activeScrollKey}_${viewModel.sortBy}_${viewModel.sortOrder}_$refreshAnimationToken"
        "Category" -> "${viewModel.categoryTitle ?: ""}_${viewModel.categoryBucketId ?: ""}_${viewModel.sortBy}_${viewModel.sortOrder}_$refreshAnimationToken"
        else -> "${currentView}_${viewModel.sortBy}_${viewModel.sortOrder}_$refreshAnimationToken"
    }
    // Folder navigation updates `scrollResetKey` and its file list together
    // (loadFiles assigns currentPath + files atomically), so scrolling to 0
    // right away is safe there. A sort change is different: sortBy/sortOrder
    // flip synchronously the instant you tap a sort option, but the actual
    // re-sorted list only lands afterwards, once loadFiles finishes on IO.
    // Resetting the scroll immediately used to race LazyColumn's own
    // "keep the same key in view" anchoring: it would jump to the top for a
    // frame, then the moment the real re-sorted list arrived, quietly
    // auto-scroll back to wherever that anchored item ended up - which is
    // exactly the "sort doesn't scroll to top" bug. So a reset is marked
    // pending on key change, and only actually performed once the
    // corresponding (already re-sorted) list has arrived.
    var lastScrollResetKey by remember { mutableStateOf(scrollResetKey) }
    var pendingScrollReset by remember { mutableStateOf(false) }

    if (scrollResetKey != lastScrollResetKey) {
        pendingScrollReset = true
    }

    LaunchedEffect(scrollResetKey) {
        pendingScrollReset = true
    }
    LaunchedEffect(displayedFiles, pendingScrollReset) {
        if (pendingScrollReset && displayedFiles.isNotEmpty()) {
            // Material Files style: check if we have a saved scroll position for
            // this path (e.g. from navigating back). If so, restore it; otherwise
            // start at the top for a new folder/sort.
            val savedPos = if (activeScrollKey == "recent") null else viewModel.getScrollPosition(activeScrollKey)
            if (savedPos != null) {
                val targetIndex = savedPos.first.coerceIn(0, (displayedFiles.size - 1).coerceAtLeast(0))
                listState.scrollToItem(targetIndex, savedPos.second)
                gridState.scrollToItem(targetIndex, savedPos.second)
            } else {
                listState.scrollToItem(0)
                gridState.scrollToItem(0)
            }
            pendingScrollReset = false
            lastScrollResetKey = scrollResetKey
        }
    }

    // Root cause of the "rows overlap for a moment right after scrolling
    // then applying a sort" glitch: a sort resorts `files` (and therefore
    // `displayedFiles`) synchronously, in the same recomposition where
    // `scrollResetKey` changes - so on the very next frame the still-mostly
    // -unchanged set of visible item keys gets reordered while
    // `Modifier.animateItem()` is attached, and LazyColumn/LazyVerticalGrid
    // start animating those keys to their new positions. A beat later the
    // `pendingScrollReset` effect above fires `scrollToItem(...)`, an
    // instant (non-animated) jump. If any item's own reorder animation
    // (from a scroll offset other than 0, i.e. you'd scrolled first) is
    // still in flight when that jump lands, its animateItem node is left
    // targeting a now-stale offset for one frame, which paints as two rows
    // sharing the same slot. Because it only happens while an animation is
    // actually in flight, it never reproduces from the top of the list, and
    // once it happens the animator's state is back in sync, so it never
    // shows up on the very next sort tap either. Suppressing the move
    // animation for the same window the scroll-reset jump uses avoids the
    // collision entirely - it doesn't touch the entrance animation, the
    // scroll-position-restore logic, or the reorder animation for any sort
    // change that doesn't also need a scroll jump.

    val currentIsGridView = if (currentView == "Category" || currentView == "Favourites") viewModel.isCategoryGridView else viewModel.isGridView

    // Continuously save the current scroll position for the active path.
    // To avoid saving the OLD path's scroll position into the NEW path's
    // slot immediately after navigation, we track the 'active' path separately
    // and only update it once the corresponding list has actually arrived.
    var pathForSaving by remember { mutableStateOf(activeScrollKey) }
    LaunchedEffect(displayedFiles, viewModel.isLoading, activeScrollKey) {
        if (!viewModel.isLoading && displayedFiles.isNotEmpty()) {
            pathForSaving = activeScrollKey
        }
    }

    LaunchedEffect(listState, gridState, pathForSaving, currentView, currentIsGridView) {
        if (pathForSaving.isNotEmpty()) {
            snapshotFlow {
                if (currentIsGridView) {
                    if (gridState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                        gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                    } else null
                } else {
                    if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    } else null
                }
            }.collect { pos ->
                if (pos != null && pathForSaving == activeScrollKey) {
                    viewModel.saveScrollPosition(pathForSaving, pos.first, pos.second)
                }
            }
        }
    }

    // Material Files-style entrance animation (fade + slide up) should play
    // once per item, the first time it appears after opening a folder/sort -
    // not every single time it scrolls back into the viewport. LazyColumn
    // only keeps composition state for items actually on/near screen, so
    // scrolling an item away and back used to recreate its `remember` slot
    // from scratch, replaying the fade-in from zero each time - which is
    // what showed up as a quick, jittery "pop" instead of one smooth
    // animation. This set persists for the lifetime of the current folder
    // (keyed on scrollResetKey) and is consulted by FileListItem/FileGridItem.
    val animatedItemKeys = remember(scrollResetKey) { androidx.compose.runtime.mutableStateSetOf<String>() }

    // Shared across the visible list items for swipe-to-select on checkboxes.
    val checkboxDragSelectState = rememberCheckboxDragSelectState()

    // Auto-scroll logic for swipe-to-select (ColorOS-style continuous drag).
    // IMPORTANT: this used to be `LaunchedEffect(checkboxDragSelectState.dragPosition)`,
    // which restarted the whole coroutine on every single pointer-move update
    // (dragPosition changes dozens of times/sec while dragging). Cancelling and
    // relaunching a coroutine that many times per second is what made the
    // scroll feel stuttery/non-smooth once a drag entered the scroll zone -
    // each restart threw away the in-flight `delay(16)` and only ever got one
    // scrollBy() in before being torn down again. Now this effect is started
    // exactly once and runs its own steady, frame-paced loop for the entire
    // lifetime of the screen, simply reading the latest drag position/bounds
    // on every frame - nothing about a finger moving cancels or restarts it.
    val autoScrollDensity = LocalDensity.current
    LaunchedEffect(autoScrollDensity, displayedFiles, listState, gridState, currentIsGridView) {
        val threshold = with(autoScrollDensity) { 100.dp.toPx() }
        val maxScrollAmount = with(autoScrollDensity) { 26.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val pos = checkboxDragSelectState.dragPosition
            val bounds = checkboxDragSelectState.containerBounds
            if (pos != null && bounds != null) {
                val factor = when {
                    pos.y < bounds.top + threshold -> -((bounds.top + threshold - pos.y) / threshold).coerceIn(0f, 1.2f)
                    pos.y > bounds.bottom - threshold -> ((pos.y - (bounds.bottom - threshold)) / threshold).coerceIn(0f, 1.2f)
                    else -> 0f
                }
                if (factor != 0f) {
                    val amount = maxScrollAmount * factor
                    if (currentIsGridView) {
                        gridState.scrollBy(amount)
                    } else {
                        listState.scrollBy(amount)
                    }

                    // Sync selection while scrolling even if the finger is perfectly still
                    performDragSelect(checkboxDragSelectState, displayedFiles, viewModel)
                }
            }
        }
    }

    // Category/Recent data is fetched asynchronously (MediaStore query) and
    // routinely lands *after* the screen's own enter transition (AnimatedContent)
    // has already finished settling. When that happens, the newly-inserted rows'
    // own per-item entrance animation (fade + slide, see FileListItem/FileGridItem)
    // could end up composited into an already-static layer and never actually get
    // drawn on screen - showing as a blank list with only the dividers visible
    // (the dividers aren't part of the animated/alpha-faded content, so they always
    // render immediately). Forcing extra `view.invalidate()` calls used to paper
    // over this, but it only "usually" worked. The reliable fix is to not run that
    // per-item entrance animation for Recent at all - see hasAnimatedBefore
    // below - so there's nothing that can get stuck invisible in the first place.
    // Category now uses it as requested by user, but Recent stays skipped.
    val skipEntranceAnimation = true

    // Live folder updates (silentRefresh from the FileObserver/poll in the
    // ViewModel) swap `files` in place without touching scrollResetKey, so
    // the scroll-to-top logic above doesn't fire for them - a new file that
    // just landed and got sorted to the top (e.g. sort by Date Modified)
    // would otherwise land off-screen, above the current scroll position,
    // and go completely unnoticed until the user manually scrolled up.
    var lastTopKey by remember(scrollResetKey) { mutableStateOf<String?>(null) }
    var lastListSize by remember(scrollResetKey) { mutableStateOf(0) }
    LaunchedEffect(displayedFiles.firstOrNull()?.file?.absolutePath, scrollResetKey) {
        val newTopKey = displayedFiles.firstOrNull()?.file?.absolutePath
        val previousTopKey = lastTopKey
        val previousSize = lastListSize
        val newSize = displayedFiles.size
        lastTopKey = newTopKey
        lastListSize = newSize
        // Only a genuine growth of the list (a new file landing) should pull the
        // view back up to reveal it. A delete also changes the top key (when the
        // old top item was the one removed) but shrinks the list - forcing a
        // scroll-to-top for that case is what was hijacking the delete's own
        // slide-up removal animation and yanking the user back to the top instead.
        if (previousTopKey != null && newTopKey != null && newTopKey != previousTopKey &&
            newSize > previousSize &&
            !pendingScrollReset && listState.firstVisibleItemIndex == 0 && !listState.isScrollInProgress
        ) {
            listState.animateScrollToItem(0)
            gridState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(viewModel.highlightedFile, viewModel.highlightedPaths, effectiveDisplayedFiles) {
        val hasHighlight = viewModel.highlightedFile != null || viewModel.highlightedPaths.isNotEmpty()
        if (hasHighlight && effectiveDisplayedFiles.isNotEmpty()) {
            val index = effectiveDisplayedFiles.indexOfFirst {
                viewModel.isPathHighlighted(it.file.absolutePath)
            }
            if (index != -1) {
                // Slight delay to ensure list is settled for smoother animation
                kotlinx.coroutines.delay(100)
                val scrollIndex = if (!currentIsGridView) index + 1 else index
                if (currentIsGridView) {
                    gridState.animateScrollToItem(scrollIndex)
                } else {
                    listState.animateScrollToItem(scrollIndex)
                }
                // Clear highlight after 1.5 seconds pulse effect
                kotlinx.coroutines.delay(1500)
                viewModel.clearHighlight()
            }
        }
    }

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    Crossfade(targetState = activeScreen, label = "ScreenTransition") { screen ->
        when (screen) {
            "Settings" -> {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { activeScreen = "Main" }
                )
            }
            "About" -> {
                AboutScreen(
                    viewModel = viewModel,
                    onBack = { activeScreen = "Main" }
                )
            }
            else -> {
                val homeBottomPadding = if (fromHome && !viewModel.isSelectionMode) {
                    68.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                } else {
                    0.dp
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = contentWindowInsets,
                    topBar = {
                        Surface(
                            shadowElevation = 3.dp,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .animateContentSize()
                                .then(
                                    if (isFromAllFiles && sharedTransitionScope != null && animatedVisibilityScope != null) {
                                        with(sharedTransitionScope) {
                                            Modifier.sharedBounds(
                                                rememberSharedContentState(key = "all_files_capsule"),
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                enter = fadeIn(),
                                                exit = fadeOut(),
                                                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
                                            )
                                        }
                                    } else Modifier
                                )
                        ) {
                            AnimatedContent(
                                targetState = if (viewModel.isSelectionMode) "Selection" else if (isSearching) "Search" else "Main",
                                transitionSpec = {
                                    (fadeIn(tween(220, easing = FastOutSlowInEasing)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it / 4 })
                                        .togetherWith(fadeOut(tween(120)))
                                },
                                label = "TopBarTransition"
                            ) { target ->
                                when (target) {
                                    "Selection" -> {
                                        SelectionTopBar(
                                            viewModel = viewModel,
                                            isAllSelected = viewModel.selectedFiles.size == effectiveDisplayedFiles.size && effectiveDisplayedFiles.isNotEmpty(),
                                            onSelectAllClick = {
                                                if (viewModel.selectedFiles.size == effectiveDisplayedFiles.size) {
                                                    viewModel.exitSelectionMode()
                                                } else {
                                                    viewModel.selectAll(effectiveDisplayedFiles)
                                                }
                                            }
                                        )
                                    }
                                    "Search" -> {
                                        Column {
                                            SearchTopBar(searchQuery, onQueryChange = {
                                                searchQuery = it
                                                viewModel.searchFiles(it)
                                            }, onBack = {
                                                isSearching = false
                                                searchQuery = ""
                                                viewModel.searchFiles("")
                                                viewModel.activeSearchFilter = SearchFilter.ALL
                                            })
                                            if (currentView != "Category") {
                                                SearchFilterChipsRow(
                                                    selectedFilter = viewModel.activeSearchFilter,
                                                    onFilterSelected = { viewModel.activeSearchFilter = it },
                                                    counts = viewModel.searchFilterCounts
                                                )
                                            } else if (isDocumentCategory) {
                                                DocumentFilterChipsRow(
                                                    categories = orderedDocCategories,
                                                    selectedCategory = activeDocCategory,
                                                    onCategorySelected = { cat ->
                                                        val targetIndex = orderedDocCategories.indexOf(cat)
                                                        if (targetIndex >= 0) {
                                                            coroutineScope.launch {
                                                                docPagerState.animateScrollToPage(targetIndex)
                                                            }
                                                        }
                                                    },
                                                    counts = docCategoryCounts
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        val handleBack = {
                                            if (viewModel.propertiesFile != null) {
                                                viewModel.closeProperties()
                                            } else if (activeScreen != "Main") {
                                                activeScreen = "Main"
                                            } else if (viewModel.isSelectionMode) {
                                                viewModel.exitSelectionMode()
                                            } else if (isSearching) {
                                                isSearching = false
                                                searchQuery = ""
                                                viewModel.searchFiles("")
                                                viewModel.activeSearchFilter = SearchFilter.ALL
                                            } else if (viewModel.currentZipFile != null) {
                                                if (viewModel.currentZipEntryPath.isNotEmpty()) {
                                                    viewModel.navigateZipUp()
                                                } else if (fromHome && (viewModel.currentZipFile?.absolutePath == startPath || viewModel.currentZipSourcePath == startPath)) {
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                } else if (lastNonZipView != "Files") {
                                                    currentView = lastNonZipView
                                                    if (currentView == "Recent") viewModel.loadRecentFiles()
                                                    else if (currentView == "Category") {
                                                        val type = viewModel.categoryFilterType
                                                        val title = viewModel.categoryTitle
                                                        if (type != null && title != null) viewModel.browseCategory(type, title, bucketId = viewModel.categoryBucketId)
                                                    }
                                                    viewModel.closeArchive()
                                                } else {
                                                    viewModel.closeArchive()
                                                    viewModel.loadFiles(viewModel.currentPath)
                                                }
                                            } else if (currentView == "Category") {
                                                if (viewModel.categoryBucketId != null) {
                                                    viewModel.browseCategory(FileType.IMAGE, context.getString(R.string.category_photos))
                                                } else {
                                                    viewModel.exitCategoryMode()
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                }
                                            } else if (currentView == "Recent" || currentView == "Favourites") {
                                                viewModel.resetFiles()
                                                viewModel.exitCategoryMode()
                                                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                            } else {
                                                if (fromHome && viewModel.currentPath == startPath) {
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                } else if (!viewModel.navigateUp()) {
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                }
                                            }
                                        }

                                        val shouldGroupTop = currentView == "Recent" && searchQuery.isBlank()

                                        Column {
                                            MainTopBar(
                                                title = when (currentView) {
                                                    "Recent" -> stringResource(R.string.home_section_recent_files)
                                                    "Category" -> viewModel.categoryTitle ?: stringResource(R.string.category_files)
                                                    "Favourites" -> stringResource(R.string.title_favourite_files)
                                                    else -> stringResource(R.string.category_all_files)
                                                },
                                                path = if (currentView == "Files") viewModel.currentPath else null,
                                                archiveName = viewModel.currentZipFile?.name?.let { zipName ->
                                                    val subPath = viewModel.currentZipEntryPath.trimEnd('/')
                                                    if (subPath.isEmpty()) zipName else "$zipName/$subPath"
                                                },
                                                viewModel = viewModel,
                                                currentView = currentView,
                                                totalItems = if (shouldGroupTop) displayedFiles.size else null,
                                                sharedTransitionScope = sharedTransitionScope,
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                onSearchClick = { isSearching = true },
                                                onRefreshClick = {
                                                    when (currentView) {
                                                        "Recent" -> viewModel.loadRecentFiles()
                                                        "Category" -> {
                                                            val type = viewModel.categoryFilterType
                                                            val title = viewModel.categoryTitle
                                                            if (type != null && title != null) viewModel.browseCategory(type, title, bucketId = viewModel.categoryBucketId, forceRefresh = true)
                                                        }
                                                        else -> viewModel.loadFiles(viewModel.currentPath)
                                                    }
                                                },
                                                onNewFileClick = { showCreateFileDialog = true },
                                                onNewFolderClick = { showCreateFolderDialog = true },
                                                onSettingsClick = { activeScreen = "Settings" },
                                                onNavigate = { viewModel.navigateTo(it, isActuallyDirectory = true) },
                                                onBack = { handleBack() }
                                            )
                                            if (isDocumentCategory) {
                                                DocumentFilterChipsRow(
                                                    categories = orderedDocCategories,
                                                    selectedCategory = activeDocCategory,
                                                    onCategorySelected = { cat ->
                                                        val targetIndex = orderedDocCategories.indexOf(cat)
                                                        if (targetIndex >= 0) {
                                                            coroutineScope.launch {
                                                                docPagerState.animateScrollToPage(targetIndex)
                                                            }
                                                        }
                                                    },
                                                    counts = docCategoryCounts
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        val isInsideHomePager = fromHome && startRecent
                        AnimatedVisibility(
                            visible = viewModel.isSelectionMode,
                            enter = slideInVertically(
                                animationSpec = tween(
                                    durationMillis = 350,
                                    delayMillis = if (isInsideHomePager) 140 else 0,
                                    easing = FastOutSlowInEasing
                                )
                            ) { it },
                            exit = slideOutVertically(
                                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                            ) { it }
                        ) {
                            SelectionBottomBar(
                                viewModel = viewModel,
                                currentView = currentView,
                                onShare = { onShareClick(viewModel.selectedFiles.toList()) },
                                onDeleteClick = {
                                    val totalSize = viewModel.selectedFiles.sumOf { it.size }
                                    if (viewModel.confirmBeforeDelete || totalSize > LARGE_FILE_THRESHOLD) {
                                        pendingDelete = PendingDelete.Selection(viewModel.selectedFiles.size, totalSize)
                                    } else {
                                        viewModel.deleteSelected()
                                    }
                                },
                                onCompressClick = { showCompressDialog = true },
                                onRenameClick = {
                                    showRenameDialog = viewModel.selectedFiles.firstOrNull()
                                },
                                onExtractClick = {
                                    val fileItem = viewModel.selectedFiles.firstOrNull()
                                    if (fileItem != null) {
                                        if (currentView == "Category" || currentView == "Recent" || currentView == "Favourites") {
                                            viewModel.prepareExtraction(fileItem.file)
                                            viewModel.exitSelectionMode()
                                            Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), Toast.LENGTH_SHORT).show()
                                        } else {
                                            showExtractionDialog = fileItem
                                        }
                                    }
                                },
                                isAllSelected = viewModel.selectedFiles.size == effectiveDisplayedFiles.size && effectiveDisplayedFiles.isNotEmpty(),
                                onSelectAllClick = {
                                    if (viewModel.selectedFiles.size == effectiveDisplayedFiles.size) {
                                        viewModel.exitSelectionMode()
                                    } else {
                                        viewModel.selectAll(effectiveDisplayedFiles)
                                    }
                                },
                                onOpenArchiveClick = { fileItem ->
                                    if (isSearching) {
                                        isSearching = false
                                        searchQuery = ""
                                        viewModel.searchFiles("")
                                    }
                                    lastNonZipView = currentView
                                    viewModel.openArchive(fileItem.file)
                                    currentView = "Files"
                                },
                                onOpenPath = onOpenPath
                            )
                        }
                    },
                    floatingActionButton = {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Extract Here FAB
                            AnimatedVisibility(
                                visible = viewModel.extractionSource != null,
                                enter = scaleIn(animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeIn(),
                                exit = scaleOut(animationSpec = tween(300)) + fadeOut()
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    tonalElevation = 4.dp,
                                    shadowElevation = 6.dp
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.clearExtraction() },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.action_cancel),
                                                tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        VerticalDivider(
                                            modifier = Modifier
                                                .height(24.dp)
                                                .padding(horizontal = 4.dp),
                                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.1f)
                                        )
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .then(
                                                    if (currentView == "Category" || currentView == "Recent" || currentView == "Favourites" || viewModel.currentZipFile != null) {
                                                        Modifier.alpha(0.5f)
                                                    } else {
                                                        Modifier.clickable {
                                                            val dest = File(viewModel.currentPath, viewModel.extractionSource!!.nameWithoutExtension)
                                                            viewModel.startExtraction(viewModel.extractionSource!!, dest)
                                                        }
                                                    }
                                                )
                                                .padding(horizontal = 8.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Outlined.Unarchive,
                                                null,
                                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                stringResource(R.string.action_extract_here),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer
                                            )
                                        }
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = viewModel.clipboardFiles.isNotEmpty(),
                                enter = scaleIn(animationSpec = tween(400, easing = FastOutSlowInEasing)) + fadeIn(),
                                exit = scaleOut(animationSpec = tween(300)) + fadeOut()
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    tonalElevation = 4.dp,
                                    shadowElevation = 6.dp
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.clearClipboard() },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.action_cancel),
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        VerticalDivider(
                                            modifier = Modifier
                                                .height(24.dp)
                                                .padding(horizontal = 4.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
                                        )
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .then(
                                                    if (currentView == "Category" || currentView == "Recent" || currentView == "Favourites" || viewModel.currentZipFile != null) {
                                                        Modifier.alpha(0.5f)
                                                    } else {
                                                        Modifier.clickable {
                                                            viewModel.pasteFiles()
                                                        }
                                                    }
                                                )
                                                .padding(horizontal = 8.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.ContentPaste,
                                                null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                if (viewModel.isCopyOperation) stringResource(R.string.action_paste_here) else stringResource(R.string.action_move_here),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                ) { paddingValues ->
                    val refreshScope = rememberCoroutineScope()
                    val pullToRefreshState = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = isPullRefreshing,
                        onRefresh = {
                            if (viewModel.isSelectionMode || viewModel.isLoading || isPullRefreshing) return@PullToRefreshBox
                            isPullRefreshing = true
                            refreshScope.launch {
                                val startTime = System.currentTimeMillis()
                                try {
                                    when {
                                        currentView == "Recent" -> {
                                            viewModel.loadRecentFiles()
                                        }
                                        currentView == "Favourites" -> {
                                            refreshAnimationToken++
                                        }
                                        currentView == "Category" -> {
                                            val type = viewModel.categoryFilterType
                                            val title = viewModel.categoryTitle
                                            if (type != null && title != null) {
                                                viewModel.browseCategory(type, title, bucketId = viewModel.categoryBucketId, forceRefresh = true)
                                            }
                                        }
                                        viewModel.currentZipFile != null -> {
                                            viewModel.openArchive(viewModel.currentZipFile!!, viewModel.currentZipEntryPath)
                                        }
                                        else -> {
                                            viewModel.loadFiles(viewModel.currentPath, isManualRefresh = true)
                                        }
                                    }
                                    while (viewModel.isLoading || viewModel.isCategoryLoading || viewModel.isRecentLoading) {
                                        kotlinx.coroutines.delay(40)
                                    }
                                } finally {
                                    val elapsed = System.currentTimeMillis() - startTime
                                    val minSpinDuration = 650L
                                    if (elapsed < minSpinDuration) {
                                        kotlinx.coroutines.delay(minSpinDuration - elapsed)
                                    }
                                    isPullRefreshing = false
                                    viewModel.isRefreshing = false
                                    refreshAnimationToken++
                                }
                            }
                        },
                        state = pullToRefreshState,
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                state = pullToRefreshState,
                                isRefreshing = isPullRefreshing,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        },
                        modifier = Modifier.padding(paddingValues).fillMaxSize()
                    ) {
                        Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { coords ->
                            checkboxDragSelectState.containerBounds = coords.boundsInWindow()
                        }.then(
                            if (viewModel.isSelectionMode) {
                                Modifier.pointerInput(checkboxDragSelectState, effectiveDisplayedFiles) {
                                    detectCheckboxDragSelect(
                                        dragSelectState = checkboxDragSelectState,
                                        displayedFiles = effectiveDisplayedFiles,
                                        viewModel = viewModel
                                    )
                                }
                            } else Modifier
                        )) {
                            val isRestricted = (viewModel.currentPath.contains("/Android/data") || viewModel.currentPath.contains("/Android/obb"))
                            val hasShizuku = ShizukuManager.isAvailable() && ShizukuManager.hasPermission()
                            val hasSaf = SafManager.hasPermission(LocalContext.current, viewModel.currentPath)

                            val isRemote = viewModel.isRemotePath(viewModel.currentPath)

                            // Show the card if access was explicitly denied by the load process for a filesystem folder
                            val accessDenied = viewModel.accessDenied && currentView == "Files" && viewModel.categoryFilterType == null && !isRemote

                            if (accessDenied && !viewModel.isLoading) {
                                val isSystemRestricted = isRestricted
                                RestrictedFolderView(
                                    path = viewModel.currentPath,
                                    isShizukuAuthorized = hasShizuku,
                                    title = if (isSystemRestricted) stringResource(R.string.restricted_system_folder_title) else stringResource(R.string.drive_access_required_title),
                                    description = if (isSystemRestricted) stringResource(R.string.restricted_system_folder_desc)
                                    else stringResource(R.string.drive_access_required_desc),
                                    onGrantShizuku = {
                                        if (ShizukuManager.isAvailable()) {
                                            viewModel.onShizukuResult(false, viewModel.currentPath) // Reset
                                            viewModel.retryShizuku(viewModel.currentPath)
                                        } else {
                                            ShizukuManager.requestBinder(context)
                                            val launchIntent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                            if (launchIntent != null) {
                                                context.startActivity(launchIntent)
                                            }
                                            Toast.makeText(context, context.getString(R.string.toast_shizuku_not_running), Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    onGrantSaf = {
                                        viewModel.retrySaf(viewModel.currentPath)
                                    },
                                    onRefresh = {
                                        ShizukuManager.requestBinder(context)
                                        viewModel.loadFiles(viewModel.currentPath, isManualRefresh = true)
                                    }
                                )
                            } else {
                                // Material Files style: Central loading indicator that appears after a short delay
                                // if the folder/category scan is taking a moment.
                                //
                                // We include a check for the initial transition state (view changed but
                                // VM hasn't started loading yet) to prevent the "No files found" text
                                // from flickering on screen for a single frame.
                                val isInitialLoad = (currentView == "Category" && viewModel.categoryFilterType == null) ||
                                        (currentView == "Recent" && viewModel.recentFiles.isEmpty() && viewModel.categoryFilterType == null)
                                val isContentLoading = if (isSearching && searchQuery.isNotBlank()) {
                                    false // Searching renders immediately inside AnimatedContent via SearchLoadingView / SearchEmptyStateView
                                } else when (currentView) {
                                    "Category" -> (viewModel.isCategoryLoading || viewModel.isRefreshing || isInitialLoad) && displayedFiles.isEmpty()
                                    "Recent" -> (viewModel.isRecentLoading || viewModel.isRefreshing || isInitialLoad) && displayedFiles.isEmpty()
                                    else -> (viewModel.isLoading || viewModel.isRefreshing) && displayedFiles.isEmpty()
                                }

                                var showCenteredLoading by remember { mutableStateOf(false) }
                                LaunchedEffect(isContentLoading) {
                                    if (isContentLoading) {
                                        kotlinx.coroutines.delay(150) // Short delay to avoid flicker on fast folders
                                        showCenteredLoading = true
                                    } else {
                                        showCenteredLoading = false
                                    }
                                }

                                AnimatedVisibility(
                                    visible = showCenteredLoading,
                                    enter = fadeIn(tween(150)),
                                    exit = fadeOut(tween(100))
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(36.dp),
                                            strokeWidth = 3.5.dp,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                        )
                                    }
                                }

                                // Identifies a genuine "screen" for the crossfade: a real switch
                                // between Files / Category / Recent.
                                val explorerContext = remember(currentView, viewModel.categoryTitle, viewModel.categoryBucketId) {
                                    ExplorerContext(currentView, viewModel.categoryTitle, "")
                                }

                                // Content (including the slide-up + fade entrance animation on each
                                // item) only composes once loading has genuinely finished. While
                                // isContentLoading is true, the spinner above is the only thing on
                                // screen - never an empty list - so there is nothing left to "appear"
                                // on the next touch/scroll; it's already there the instant data lands.
                                if (!isContentLoading) {
                                    AnimatedContent(
                                        targetState = explorerContext,
                                        transitionSpec = {
                                            if (targetState.view == initialState.view) {
                                                fadeIn(tween(150)) togetherWith fadeOut(tween(100))
                                            } else {
                                                (fadeIn(tween(220, easing = LinearOutSlowInEasing)) +
                                                        scaleIn(initialScale = 0.92f, animationSpec = tween(220, easing = LinearOutSlowInEasing)))
                                                    .togetherWith(fadeOut(tween(120, easing = FastOutLinearInEasing)) +
                                                            scaleOut(targetScale = 1.05f, animationSpec = tween(120, easing = FastOutLinearInEasing)))
                                            }
                                        },
                                        label = "FolderTransition",
                                        modifier = Modifier.fillMaxSize()
                                    ) { state ->
                                        val displayedFilesFinal = if (isSearching && searchQuery.isNotBlank()) {
                                            if (state.view == "Category") viewModel.searchResults
                                            else if (state.view == "Favourites") viewModel.sortFileList(viewModel.getFavouriteFileItems().filter { it.name.contains(searchQuery, ignoreCase = true) })
                                            else viewModel.filteredSearchResults
                                        } else if (state.view == "Recent") {
                                            viewModel.recentFiles
                                        } else if (state.view == "Favourites") {
                                            viewModel.sortFileList(viewModel.getFavouriteFileItems())
                                        } else if (state.view == "Category") {
                                            viewModel.categoryFiles
                                        } else {
                                            viewModel.files
                                        }

                                        val shouldGroup = state.view == "Recent" && searchQuery.isBlank()

                                        val groupedItems = remember(displayedFilesFinal, shouldGroup) {
                                            if (!shouldGroup) emptyList<ListItemType>()
                                            else {
                                                val list = mutableListOf<ListItemType>()
                                                displayedFilesFinal.groupBy { formatDateHeader(context, it.lastModified) }
                                                    .forEach { (header, files) ->
                                                        list.add(ListItemType.Header(header, files.size))
                                                        files.forEach { list.add(ListItemType.File(it)) }
                                                    }
                                                list
                                            }
                                        }

                                        val isCurrentlyLoading = when (state.view) {
                                            "Category" -> viewModel.isCategoryLoading || isPullRefreshing
                                            "Recent" -> viewModel.isRecentLoading || isPullRefreshing
                                            "Favourites" -> false
                                            else -> viewModel.isLoading || isPullRefreshing
                                        }

                                        val isNavigatingOrResetting = pendingScrollReset || isPullRefreshing || (scrollResetKey != lastScrollResetKey)

                                        if (isSearching && searchQuery.isNotBlank() && displayedFilesFinal.isEmpty()) {
                                            if (viewModel.isRecursiveSearching) {
                                                SearchLoadingView(
                                                    query = searchQuery,
                                                    categoryName = if (state.view == "Category") viewModel.categoryTitle else null,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                SearchEmptyStateView(
                                                    query = searchQuery,
                                                    filter = viewModel.activeSearchFilter,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        } else if (displayedFilesFinal.isEmpty() && !isCurrentlyLoading) {
                                            if (state.view == "Favourites") {
                                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        modifier = Modifier.padding(32.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.FavoriteBorder,
                                                            contentDescription = null,
                                                            tint = Color(0xFFE11D48).copy(alpha = 0.5f),
                                                            modifier = Modifier.size(64.dp)
                                                        )
                                                        Spacer(modifier = Modifier.height(16.dp))
                                                        Text(
                                                            text = stringResource(R.string.empty_favourite_files),
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Text(
                                                            text = stringResource(R.string.empty_favourite_files_subtitle),
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                        )
                                                    }
                                                }
                                            } else {
                                                if (isRemote && searchQuery.isEmpty()) {
                                                    RemoteStorageEmptyView(
                                                        path = viewModel.currentPath,
                                                        onRefresh = { viewModel.loadFiles(viewModel.currentPath, isManualRefresh = true) }
                                                    )
                                                } else {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        Text(if (searchQuery.isEmpty()) stringResource(R.string.no_files_found) else stringResource(R.string.no_results_for_query, searchQuery))
                                                    }
                                                }
                                            }
                                        } else if (isDocumentCategory) {
                                            HorizontalPager(
                                                state = docPagerState,
                                                modifier = Modifier.fillMaxSize()
                                            ) { pageIndex ->
                                                val pageCat = orderedDocCategories.getOrElse(pageIndex) { DocumentTypeCategory.ALL }
                                                val pageFiles = remember(displayedFilesFinal, pageCat) {
                                                    filterDocumentFiles(displayedFilesFinal, pageCat)
                                                }
                                                val pageGridState = if (pageIndex == 0) gridState else remember(pageCat.id) { androidx.compose.foundation.lazy.grid.LazyGridState() }
                                                val pageListState = if (pageIndex == 0) listState else remember(pageCat.id) { androidx.compose.foundation.lazy.LazyListState() }

                                                if (pageFiles.isEmpty() && !isCurrentlyLoading) {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                            Icon(
                                                                imageVector = pageCat.icon,
                                                                contentDescription = null,
                                                                tint = pageCat.accentColor.copy(alpha = 0.5f),
                                                                modifier = Modifier.size(48.dp)
                                                            )
                                                            Spacer(modifier = Modifier.height(8.dp))
                                                            Text(
                                                                text = if (pageCat == DocumentTypeCategory.ALL) {
                                                                    if (searchQuery.isEmpty()) stringResource(R.string.no_files_found) else stringResource(R.string.no_results_for_query, searchQuery)
                                                                } else {
                                                                    "No ${pageCat.label} files found"
                                                                },
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                 } else if (viewModel.isCategoryGridView) {
                                                    LazyVerticalGrid(
                                                        columns = GridCells.Adaptive(minSize = viewModel.gridItemSize.cellMinSize),
                                                        state = pageGridState,
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentPadding = PaddingValues(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 8.dp + homeBottomPadding)
                                                    ) {
                                                        items(pageFiles, key = { it.file.absolutePath }, contentType = { "grid_item" }) { fileItem ->
                                                            val index = pageFiles.indexOf(fileItem)
                                                            val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                            FileGridItem(
                                                                fileItem = fileItem,
                                                                isSelected = viewModel.selectedFiles.contains(fileItem),
                                                                isHighlighted = isHighlighted,
                                                                showDetails = viewModel.showDetails,
                                                                showExtension = viewModel.showFileExtensions,
                                                                iconSize = viewModel.gridItemSize.iconSize,
                                                                isVirtual = false,
                                                                index = index,
                                                                scrollResetKey = scrollResetKey,
                                                                hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                                onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                                viewModel = viewModel,
                                                                modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                    Modifier
                                                                } else {
                                                                    Modifier.animateItem(
                                                                        fadeInSpec = null,
                                                                        fadeOutSpec = null,
                                                                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                    )
                                                                },
                                                                onClick = {
                                                                    if (viewModel.isSelectionMode) {
                                                                        viewModel.toggleSelection(fileItem)
                                                                    } else if (System.currentTimeMillis() - viewModel.lastSwipeToHomeTimestamp > 500L) {
                                                                        onFileClick(fileItem)
                                                                    }
                                                                },
                                                                onLongClick = {
                                                                    viewModel.toggleSelection(fileItem)
                                                                }
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    LazyColumn(
                                                        modifier = Modifier.fillMaxSize(),
                                                        state = pageListState,
                                                        contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp + homeBottomPadding)
                                                    ) {
                                                        itemsIndexed(pageFiles, key = { _, item -> item.file.absolutePath }, contentType = { _, _ -> "list_item" }) { index, fileItem ->
                                                            val isSelected = viewModel.selectedFiles.contains(fileItem)
                                                            val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                            FileListItem(
                                                                fileItem = fileItem,
                                                                isSelected = isSelected,
                                                                isHighlighted = isHighlighted,
                                                                isSelectionMode = viewModel.isSelectionMode,
                                                                showDetails = viewModel.showDetails,
                                                                showExtension = viewModel.showFileExtensions,
                                                                showListDividers = viewModel.showListDividers,
                                                                isVirtual = false,
                                                                clipboardHasFiles = viewModel.clipboardFiles.isNotEmpty(),
                                                                index = index,
                                                                scrollResetKey = scrollResetKey,
                                                                hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                                onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                                modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                    Modifier
                                                                } else {
                                                                    Modifier.animateItem(
                                                                        fadeInSpec = null,
                                                                        fadeOutSpec = null,
                                                                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                    )
                                                                },
                                                                onClick = {
                                                                    if (viewModel.isSelectionMode) {
                                                                        viewModel.toggleSelection(fileItem)
                                                                    } else if (System.currentTimeMillis() - viewModel.lastSwipeToHomeTimestamp > 500L) {
                                                                        onFileClick(fileItem)
                                                                    }
                                                                },
                                                                onLongClick = {
                                                                    viewModel.toggleSelection(fileItem)
                                                                },
                                                                onDelete = {
                                                                    val totalSize = fileItem.size
                                                                    if (viewModel.confirmBeforeDelete || totalSize > LARGE_FILE_THRESHOLD) {
                                                                        pendingDelete = PendingDelete.Single(fileItem)
                                                                    } else {
                                                                        viewModel.deleteFile(fileItem)
                                                                    }
                                                                },
                                                                onRename = { newName -> viewModel.renameFile(fileItem, newName) },
                                                                onRenameRequest = { showRenameDialog = fileItem },
                                                                onOpenWith = { onOpenWithClick(fileItem) },
                                                                onShare = { onShareClick(listOf(fileItem)) },
                                                                onCopy = {
                                                                    viewModel.toggleSelection(fileItem)
                                                                    viewModel.copySelected()
                                                                    if (currentView == "Category" || currentView == "Recent" || currentView == "Favourites") {
                                                                        onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                    }
                                                                },
                                                                onCut = {
                                                                    viewModel.toggleSelection(fileItem)
                                                                    viewModel.moveSelected()
                                                                    if (currentView == "Category" || currentView == "Recent" || currentView == "Favourites") {
                                                                        onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                    }
                                                                },
                                                                onOpenLocation = null,
                                                                onExtract = {
                                                                    viewModel.prepareExtraction(fileItem.file)
                                                                    Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), Toast.LENGTH_SHORT).show()
                                                                },
                                                                onProperties = { viewModel.showProperties(fileItem) },
                                                                viewModel = viewModel,
                                                                dragSelectState = checkboxDragSelectState,
                                                                isDividerVisible = viewModel.showListDividers && index != pageFiles.lastIndex && !isSelected && !viewModel.selectedFiles.contains(pageFiles.getOrNull(index + 1))
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } else if (if (currentView == "Category" || currentView == "Favourites") viewModel.isCategoryGridView else viewModel.isGridView) {
                                            LazyVerticalGrid(
                                                columns = GridCells.Adaptive(minSize = viewModel.gridItemSize.cellMinSize),
                                                state = gridState,
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = PaddingValues(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 8.dp + homeBottomPadding)
                                            ) {
                                                if (shouldGroup) {
                                                    items(
                                                        items = groupedItems,
                                                        key = { item ->
                                                            when (item) {
                                                                is ListItemType.Header -> "header_${item.title}"
                                                                is ListItemType.File -> item.fileItem.file.absolutePath
                                                            }
                                                        },
                                                        span = { item ->
                                                            if (item is ListItemType.Header) GridItemSpan(maxLineSpan)
                                                            else GridItemSpan(1)
                                                        }
                                                    ) { item ->
                                                        when (item) {
                                                            is ListItemType.Header -> DateHeader(item.title, item.count)
                                                            is ListItemType.File -> {
                                                                val fileItem = item.fileItem
                                                                val indexInFullList = displayedFilesFinal.indexOf(fileItem)
                                                                val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                                FileGridItem(
                                                                    fileItem = fileItem,
                                                                    isSelected = viewModel.selectedFiles.contains(fileItem),
                                                                    isHighlighted = isHighlighted,
                                                                    showDetails = viewModel.showDetails,
                                                                    showExtension = viewModel.showFileExtensions,
                                                                    iconSize = viewModel.gridItemSize.iconSize,
                                                                    isVirtual = viewModel.currentZipFile != null,
                                                                    index = indexInFullList,
                                                                    scrollResetKey = scrollResetKey,
                                                                    hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                                    onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                                    viewModel = viewModel,
                                                                    modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                        Modifier
                                                                    } else {
                                                                        Modifier.animateItem(
                                                                            fadeInSpec = null,
                                                                            fadeOutSpec = null,
                                                                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                        )
                                                                    },
                                                                    onClick = {
                                                                        if (viewModel.isSelectionMode) {
                                                                            viewModel.toggleSelection(fileItem)
                                                                        } else {
                                                                            if (fileItem.isDirectory) {
                                                                                if (isSearching) {
                                                                                    isSearching = false
                                                                                    searchQuery = ""
                                                                                    viewModel.searchFiles("")
                                                                                }
                                                                                val isAlbum = (fileItem.bucketId != null || fileItem.file.path.contains("album:")) && currentView == "Category"
                                                                                if (isAlbum) {
                                                                                    val bucketId = fileItem.bucketId ?: fileItem.file.path.substringAfter("album:")
                                                                                    viewModel.browseCategory(FileType.IMAGE, fileItem.name, bucketId)
                                                                                } else {
                                                                                    if (viewModel.currentZipFile != null && fileItem.zipEntryPath != null) {
                                                                                        viewModel.navigateZipInto(fileItem.zipEntryPath)
                                                                                    } else {
                                                                                        viewModel.navigateTo(fileItem.file, fileItem.isDirectory)
                                                                                    }
                                                                                    currentView = "Files"
                                                                                }
                                                                            } else if (fileItem.fileType == FileType.ZIP) {
                                                                                if (isSearching) {
                                                                                    isSearching = false
                                                                                    searchQuery = ""
                                                                                    viewModel.searchFiles("")
                                                                                }
                                                                                lastNonZipView = currentView
                                                                                viewModel.openArchive(fileItem.file)
                                                                                currentView = "Files"
                                                                            } else if (System.currentTimeMillis() - viewModel.lastSwipeToHomeTimestamp > 500L) {
                                                                                onFileClick(fileItem)
                                                                            }
                                                                        }
                                                                    },
                                                                    onLongClick = {
                                                                        viewModel.toggleSelection(fileItem)
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    items(displayedFilesFinal, key = { it.file.absolutePath }, contentType = { "grid_item" }) { fileItem ->
                                                        val index = displayedFilesFinal.indexOf(fileItem)
                                                        val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                        FileGridItem(
                                                            fileItem = fileItem,
                                                            isSelected = viewModel.selectedFiles.contains(fileItem),
                                                            isHighlighted = isHighlighted,
                                                            showDetails = viewModel.showDetails,
                                                            showExtension = viewModel.showFileExtensions,
                                                            iconSize = viewModel.gridItemSize.iconSize,
                                                            isVirtual = viewModel.currentZipFile != null,
                                                            index = index, // Pass index for staggered animation
                                                            scrollResetKey = scrollResetKey, // Pass key to restart animation on path change
                                                            hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                            onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                            viewModel = viewModel,
                                                            modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                Modifier
                                                            } else {
                                                                Modifier.animateItem(
                                                                    fadeInSpec = null,
                                                                    fadeOutSpec = null,
                                                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                )
                                                            },
                                                            onClick = {
                                                                if (viewModel.isSelectionMode) {
                                                                    viewModel.toggleSelection(fileItem)
                                                                } else {
                                                                    if (fileItem.isDirectory) {
                                                                        if (isSearching) {
                                                                            isSearching = false
                                                                            searchQuery = ""
                                                                            viewModel.searchFiles("")
                                                                        }
                                                                        val isAlbum = (fileItem.bucketId != null || fileItem.file.path.contains("album:")) && currentView == "Category"
                                                                        if (isAlbum) {
                                                                            val bucketId = fileItem.bucketId ?: fileItem.file.path.substringAfter("album:")
                                                                            viewModel.browseCategory(FileType.IMAGE, fileItem.name, bucketId)
                                                                        } else {
                                                                            if (viewModel.currentZipFile != null && fileItem.zipEntryPath != null) {
                                                                                viewModel.navigateZipInto(fileItem.zipEntryPath)
                                                                            } else {
                                                                                viewModel.navigateTo(fileItem.file, fileItem.isDirectory)
                                                                            }
                                                                            currentView = "Files"
                                                                        }
                                                                    } else if (fileItem.fileType == FileType.ZIP) {
                                                                        if (isSearching) {
                                                                            isSearching = false
                                                                            searchQuery = ""
                                                                            viewModel.searchFiles("")
                                                                        }
                                                                        lastNonZipView = currentView
                                                                        viewModel.openArchive(fileItem.file)
                                                                        currentView = "Files"
                                                                    } else if (System.currentTimeMillis() - viewModel.lastSwipeToHomeTimestamp > 500L) {
                                                                        onFileClick(fileItem)
                                                                    }
                                                                }
                                                            },
                                                            onLongClick = {
                                                                viewModel.toggleSelection(fileItem)
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
                                                state = listState,
                                                contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp + homeBottomPadding)
                                            ) {
                                                if (shouldGroup) {
                                                    items(
                                                        groupedItems,
                                                        key = { item ->
                                                            when (item) {
                                                                is ListItemType.Header -> "header_${item.title}"
                                                                is ListItemType.File -> item.fileItem.file.absolutePath
                                                            }
                                                        }
                                                    ) { item ->
                                                        when (item) {
                                                            is ListItemType.Header -> DateHeader(item.title, item.count)
                                                            is ListItemType.File -> {
                                                                val fileItem = item.fileItem
                                                                val isSelected = viewModel.selectedFiles.contains(fileItem)
                                                                val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                                val indexInFullList = displayedFilesFinal.indexOf(fileItem)
                                                                val isRoot = viewModel.currentPath == Environment.getExternalStorageDirectory().absolutePath
                                                                FileListItem(
                                                                    fileItem = fileItem,
                                                                    isSelected = isSelected,
                                                                    isHighlighted = isHighlighted,
                                                                    isSelectionMode = viewModel.isSelectionMode,
                                                                    showDetails = if (fileItem.isDirectory && isRoot) false else viewModel.showDetails,
                                                                    showExtension = viewModel.showFileExtensions,
                                                                    showListDividers = viewModel.showListDividers,
                                                                    isVirtual = viewModel.currentZipFile != null,
                                                                    clipboardHasFiles = viewModel.clipboardFiles.isNotEmpty(),
                                                                    index = indexInFullList,
                                                                    scrollResetKey = scrollResetKey,
                                                                    hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                                    onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                                    modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                        Modifier
                                                                    } else {
                                                                        Modifier.animateItem(
                                                                            fadeInSpec = null,
                                                                            fadeOutSpec = null,
                                                                            placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                        )
                                                                    },
                                                                    onClick = {
                                                                        if (viewModel.isSelectionMode) {
                                                                            viewModel.toggleSelection(fileItem)
                                                                        } else {
                                                                            if (fileItem.isDirectory) {
                                                                                if (isSearching) {
                                                                                    isSearching = false
                                                                                    searchQuery = ""
                                                                                    viewModel.searchFiles("")
                                                                                }
                                                                                val isAlbum = (fileItem.bucketId != null || fileItem.file.path.contains("album:")) && currentView == "Category"
                                                                                if (isAlbum) {
                                                                                    val bucketId = fileItem.bucketId ?: fileItem.file.path.substringAfter("album:")
                                                                                    viewModel.browseCategory(FileType.IMAGE, fileItem.name, bucketId)
                                                                                } else {
                                                                                    if (viewModel.currentZipFile != null && fileItem.zipEntryPath != null) {
                                                                                        viewModel.navigateZipInto(fileItem.zipEntryPath)
                                                                                    } else {
                                                                                        viewModel.navigateTo(fileItem.file, fileItem.isDirectory)
                                                                                    }
                                                                                    currentView = "Files"
                                                                                }
                                                                            } else if (fileItem.fileType == FileType.ZIP) {
                                                                                if (isSearching) {
                                                                                    isSearching = false
                                                                                    searchQuery = ""
                                                                                    viewModel.searchFiles("")
                                                                                }
                                                                                lastNonZipView = currentView
                                                                                viewModel.openArchive(fileItem.file)
                                                                                currentView = "Files"
                                                                            } else if (System.currentTimeMillis() - viewModel.lastSwipeToHomeTimestamp > 500L) {
                                                                                onFileClick(fileItem)
                                                                            }
                                                                        }
                                                                    },
                                                                    onLongClick = {
                                                                        viewModel.toggleSelection(fileItem)
                                                                    },
                                                                    onDelete = {
                                                                        if (viewModel.confirmBeforeDelete || fileItem.size > LARGE_FILE_THRESHOLD) {
                                                                            pendingDelete = PendingDelete.Single(fileItem)
                                                                        } else {
                                                                            viewModel.deleteFile(fileItem)
                                                                        }
                                                                    },
                                                                    onRename = { newName -> viewModel.renameFile(fileItem, newName) },
                                                                    onRenameRequest = { showRenameDialog = fileItem },
                                                                    onOpenWith = { onOpenWithClick(fileItem) },
                                                                    onShare = { onShareClick(listOf(fileItem)) },
                                                                    onCopy = {
                                                                        viewModel.toggleSelection(fileItem)
                                                                        viewModel.copySelected()
                                                                        if (currentView == "Category" || currentView == "Recent" || currentView == "Favourites") {
                                                                            onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                        }
                                                                    },
                                                                    onCut = {
                                                                        viewModel.toggleSelection(fileItem)
                                                                        viewModel.moveSelected()
                                                                        if (currentView == "Category" || currentView == "Recent" || currentView == "Favourites") {
                                                                            onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                        }
                                                                    },
                                                                    onOpenLocation = if (searchQuery.isNotEmpty() || currentView == "Recent" || currentView == "Favourites") {
                                                                        {
                                                                            val parent = fileItem.file.parentFile
                                                                            if (parent != null) {
                                                                                isSearching = false
                                                                                searchQuery = ""
                                                                                if ((currentView == "Recent" || currentView == "Favourites") && onOpenPath != null) {
                                                                                    onOpenPath(parent.absolutePath, fileItem)
                                                                                } else {
                                                                                    viewModel.navigateTo(parent, true)
                                                                                    viewModel.highlightedFile = fileItem
                                                                                    currentView = "Files"
                                                                                }
                                                                            }
                                                                        }
                                                                    } else null,
                                                                    onExtract = {
                                                                        val entries = if (fileItem.zipEntryPath != null) listOf(fileItem.zipEntryPath) else null
                                                                        if (currentView == "Category" || currentView == "Recent" || currentView == "Favourites") {
                                                                            viewModel.prepareExtraction(fileItem.file, entries)
                                                                            Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), Toast.LENGTH_SHORT).show()
                                                                        } else {
                                                                            // If inside a zip, we want to extract the selected entry
                                                                            if (entries != null) {
                                                                                viewModel.prepareExtraction(fileItem.file, entries)
                                                                            }
                                                                            showExtractionDialog = fileItem
                                                                        }
                                                                    },
                                                                    onOpenArchive = {
                                                                        if (isSearching) {
                                                                            isSearching = false
                                                                            searchQuery = ""
                                                                            viewModel.searchFiles("")
                                                                        }
                                                                        lastNonZipView = currentView
                                                                        viewModel.openArchive(fileItem.file)
                                                                        currentView = "Files"
                                                                    },
                                                                    onProperties = { viewModel.showProperties(fileItem) },
                                                                    viewModel = viewModel,
                                                                    dragSelectState = checkboxDragSelectState,
                                                                    isDividerVisible = viewModel.showListDividers && indexInFullList != displayedFilesFinal.lastIndex && !isSelected && !viewModel.selectedFiles.contains(displayedFilesFinal.getOrNull(indexInFullList + 1))
                                                                )
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    item(key = "top_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
                                                    itemsIndexed(displayedFilesFinal, key = { _, item -> item.file.absolutePath }, contentType = { _, _ -> "list_item" }) { index, fileItem ->
                                                        val isSelected = viewModel.selectedFiles.contains(fileItem)
                                                        val isHighlighted = viewModel.isPathHighlighted(fileItem.file.absolutePath)
                                                        val isRoot = viewModel.currentPath == Environment.getExternalStorageDirectory().absolutePath
                                                        FileListItem(
                                                            fileItem = fileItem,
                                                            isSelected = isSelected,
                                                            isHighlighted = isHighlighted,
                                                            isSelectionMode = viewModel.isSelectionMode,
                                                            showDetails = if (fileItem.isDirectory && isRoot) false else viewModel.showDetails,
                                                            showExtension = viewModel.showFileExtensions,
                                                            showListDividers = viewModel.showListDividers,
                                                            isVirtual = viewModel.currentZipFile != null,
                                                            clipboardHasFiles = viewModel.clipboardFiles.isNotEmpty(),
                                                            index = index, // Pass index for staggered animation
                                                            scrollResetKey = scrollResetKey, // Pass key to restart animation on path change
                                                            hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                            onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                            modifier = if (skipEntranceAnimation || isNavigatingOrResetting) {
                                                                Modifier
                                                            } else {
                                                                Modifier.animateItem(
                                                                    fadeInSpec = null,
                                                                    fadeOutSpec = null,
                                                                    placementSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                                                )
                                                            },
                                                            onClick = {
                                                                if (viewModel.isSelectionMode) {
                                                                    viewModel.toggleSelection(fileItem)
                                                                } else {
                                                                    if (fileItem.isDirectory) {
                                                                        if (isSearching) {
                                                                            isSearching = false
                                                                            searchQuery = ""
                                                                            viewModel.searchFiles("")
                                                                        }
                                                                        val isAlbum = (fileItem.bucketId != null || fileItem.file.path.contains("album:")) && currentView == "Category"
                                                                        if (isAlbum) {
                                                                            val bucketId = fileItem.bucketId ?: fileItem.file.path.substringAfter("album:")
                                                                            viewModel.browseCategory(FileType.IMAGE, fileItem.name, bucketId)
                                                                        } else {
                                                                            if (viewModel.currentZipFile != null && fileItem.zipEntryPath != null) {
                                                                                viewModel.navigateZipInto(fileItem.zipEntryPath)
                                                                            } else {
                                                                                viewModel.navigateTo(fileItem.file, fileItem.isDirectory)
                                                                            }
                                                                            currentView = "Files"
                                                                        }
                                                                    } else if (fileItem.fileType == FileType.ZIP) {
                                                                        if (isSearching) {
                                                                            isSearching = false
                                                                            searchQuery = ""
                                                                            viewModel.searchFiles("")
                                                                        }
                                                                        lastNonZipView = currentView
                                                                        viewModel.openArchive(fileItem.file)
                                                                        currentView = "Files"
                                                                    } else if (System.currentTimeMillis() - viewModel.lastSwipeToHomeTimestamp > 500L) {
                                                                        onFileClick(fileItem)
                                                                    }
                                                                }
                                                            },
                                                            onLongClick = {
                                                                viewModel.toggleSelection(fileItem)
                                                            },
                                                            onDelete = {
                                                                if (viewModel.confirmBeforeDelete || fileItem.size > LARGE_FILE_THRESHOLD) {
                                                                    pendingDelete = PendingDelete.Single(fileItem)
                                                                } else {
                                                                    viewModel.deleteFile(fileItem)
                                                                }
                                                            },
                                                            onRename = { newName -> viewModel.renameFile(fileItem, newName) },
                                                            onRenameRequest = { showRenameDialog = fileItem },
                                                            onOpenWith = { onOpenWithClick(fileItem) },
                                                            onShare = { onShareClick(listOf(fileItem)) },
                                                            onCopy = {
                                                                viewModel.toggleSelection(fileItem)
                                                                viewModel.copySelected()
                                                                if (currentView == "Category" || currentView == "Recent" || currentView == "Favourites") {
                                                                    onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                }
                                                            },
                                                            onCut = {
                                                                viewModel.toggleSelection(fileItem)
                                                                viewModel.moveSelected()
                                                                if (currentView == "Category" || currentView == "Recent" || currentView == "Favourites") {
                                                                    onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                                                                }
                                                            },
                                                            onOpenLocation = if (searchQuery.isNotEmpty() || currentView == "Recent" || currentView == "Favourites") {
                                                                {
                                                                    val parent = fileItem.file.parentFile
                                                                    if (parent != null) {
                                                                        isSearching = false
                                                                        searchQuery = ""
                                                                        if ((currentView == "Recent" || currentView == "Favourites") && onOpenPath != null) {
                                                                            onOpenPath(parent.absolutePath, fileItem)
                                                                        } else {
                                                                            viewModel.navigateTo(parent, true)
                                                                            viewModel.highlightedFile = fileItem
                                                                            currentView = "Files"
                                                                        }
                                                                    }
                                                                }
                                                            } else null,
                                                            onExtract = {
                                                                if (currentView == "Category" || currentView == "Recent" || currentView == "Favourites") {
                                                                    viewModel.prepareExtraction(fileItem.file)
                                                                    Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), Toast.LENGTH_SHORT).show()
                                                                } else {
                                                                    showExtractionDialog = fileItem
                                                                }
                                                            },
                                                            onOpenArchive = {
                                                                if (isSearching) {
                                                                    isSearching = false
                                                                    searchQuery = ""
                                                                    viewModel.searchFiles("")
                                                                }
                                                                lastNonZipView = currentView
                                                                viewModel.openArchive(fileItem.file)
                                                                currentView = "Files"
                                                            },
                                                            onProperties = { viewModel.showProperties(fileItem) },
                                                            viewModel = viewModel,
                                                            dragSelectState = checkboxDragSelectState,
                                                            isDividerVisible = viewModel.showListDividers && index != displayedFilesFinal.lastIndex && !isSelected && !viewModel.selectedFiles.contains(displayedFilesFinal.getOrNull(index + 1))
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (viewModel.propertiesFile != null) {
                                    PropertiesDialog(
                                        fileItem = viewModel.propertiesFile!!,
                                        onDismiss = { viewModel.closeProperties() },
                                        viewModel = viewModel
                                    )
                                }

                                showExtractionDialog?.let { fileItem ->
                                    ExtractionDialog(
                                        item = fileItem,
                                        onDismiss = { showExtractionDialog = null },
                                        onExtractHere = {
                                            val destDir = if (viewModel.currentZipFile != null || fileItem.zipEntryPath != null) {
                                                File(viewModel.currentPath)
                                            } else {
                                                File(fileItem.file.parent ?: Environment.getExternalStorageDirectory().absolutePath, fileItem.file.nameWithoutExtension)
                                            }
                                            val entries = if (fileItem.zipEntryPath != null) listOf(fileItem.zipEntryPath) else null
                                            viewModel.startExtraction(fileItem.file, destDir, entries)
                                            showExtractionDialog = null
                                            viewModel.exitSelectionMode()
                                        },
                                        onSelectLocation = {
                                            val entries = if (fileItem.zipEntryPath != null) listOf(fileItem.zipEntryPath) else null
                                            viewModel.prepareExtraction(fileItem.file, entries)
                                            showExtractionDialog = null
                                            viewModel.exitSelectionMode()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateFileDialog) {
        CreateFileDialog(
            onDismiss = { showCreateFileDialog = false },
            onCreate = { name ->
                viewModel.createFile(name)
                showCreateFileDialog = false
            }
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { name ->
                viewModel.createFolder(name)
                showCreateFolderDialog = false
            }
        )
    }

    if (showCompressDialog) {
        CompressDialog(
            itemCount = viewModel.selectedFiles.size,
            onDismiss = { showCompressDialog = false },
            onConfirm = { name, format, password ->
                viewModel.compressSelected(name, format, password)
                showCompressDialog = false
            }
        )
    }

    showRenameDialog?.let { fileItem ->
        RenameDialog(
            initialName = fileItem.name,
            onDismiss = { showRenameDialog = null },
            onRename = { newName ->
                viewModel.renameFile(fileItem, newName)
                showRenameDialog = null
                viewModel.exitSelectionMode()
            }
        )
    }

    pendingDelete?.let { pending ->
        val isOnRemovable = when (pending) {
            is PendingDelete.Single -> viewModel.isRemovableStorage(pending.fileItem.file.absolutePath)
            is PendingDelete.Selection -> viewModel.isCurrentPathRemovable()
        }
        DeleteConfirmationDialog(
            useRecycleBin = viewModel.useRecycleBin,
            pending = pending,
            onDismiss = { pendingDelete = null },
            onConfirm = { permanently ->
                when (pending) {
                    is PendingDelete.Single -> viewModel.deleteFile(pending.fileItem, permanently)
                    is PendingDelete.Selection -> viewModel.deleteSelected(permanently)
                }
                pendingDelete = null
            },
            isOnRemovableStorage = isOnRemovable
        )
    }
}


sealed class PendingDelete {
    data class Single(val fileItem: FileItem) : PendingDelete()
    data class Selection(val count: Int, val totalSize: Long = 0L) : PendingDelete()
}

@Composable
fun DeleteConfirmationDialog(
    useRecycleBin: Boolean,
    pending: PendingDelete,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    isOnRemovableStorage: Boolean = false
) {
    val totalSize = when (pending) {
        is PendingDelete.Single -> pending.fileItem.size
        is PendingDelete.Selection -> pending.totalSize
    }
    val isLarge = totalSize > LARGE_FILE_THRESHOLD
    var permanently by remember { mutableStateOf(!useRecycleBin) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (permanently) Icons.Default.DeleteForever else Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                val titleText = when {
                    isLarge -> stringResource(R.string.delete_large_item_title)
                    pending is PendingDelete.Single -> {
                        val name = pending.fileItem.name
                        if (name.length <= 26) stringResource(R.string.delete_single_confirm, name)
                        else if (pending.fileItem.isDirectory) stringResource(R.string.delete_folder_title)
                        else stringResource(R.string.delete_file_title)
                    }
                    pending is PendingDelete.Selection -> {
                        if (pending.count == 1) stringResource(R.string.delete_one_item_title)
                        else stringResource(R.string.delete_items_title, pending.count)
                    }
                    else -> stringResource(R.string.delete_item_title)
                }
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (isLarge) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.delete_total_size, formatFileSize(totalSize)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (pending is PendingDelete.Single && pending.fileItem.name.length > 26) {
                    Text(
                        text = pending.fileItem.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                val statusText = if (useRecycleBin && !permanently) {
                    stringResource(R.string.delete_moved_to_bin_short)
                } else {
                    stringResource(R.string.delete_cannot_be_undone_short)
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                if (!isOnRemovableStorage && (useRecycleBin || isLarge)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        onClick = { permanently = !permanently },
                        shape = RoundedCornerShape(12.dp),
                        color = if (permanently) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Checkbox(
                                checked = permanently,
                                onCheckedChange = { permanently = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.error,
                                    checkmarkColor = MaterialTheme.colorScheme.onError
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.delete_permanently),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (permanently) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (permanently) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(permanently) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = if (permanently) Icons.Default.DeleteForever else Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.action_delete),
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    viewModel: RoseViewModel,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val context = LocalContext.current
    val updateResult = viewModel.updateCheckResult

    LaunchedEffect(updateResult) {
        if (updateResult is UpdateCheckResult.UpToDate) {
            Toast.makeText(context, context.getString(R.string.toast_up_to_date), Toast.LENGTH_SHORT).show()
            viewModel.resetUpdateCheck()
        } else if (updateResult is UpdateCheckResult.Error) {
            Toast.makeText(context, updateResult.message, Toast.LENGTH_SHORT).show()
            viewModel.resetUpdateCheck()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_about), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Modernized Header - logo now fills the squircle edge-to-edge.
            // Surface clips its content to `shape` automatically, so a
            // fillMaxSize() image gets the rounded corners for free with no
            // extra clip() needed and no gap for the container color to
            // show through.
            Surface(
                modifier = Modifier.size(80.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground_base),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                stringResource(R.string.app_description),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.about_app_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Information Section together in one card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    AboutItem(Icons.Default.Info, stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
                    AboutItem(Icons.Default.Code, stringResource(R.string.about_view_on_github), stringResource(R.string.about_github_subtitle)) {
                        uriHandler.openUri("https://github.com/NarayanChetri/ROSE")
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    AboutItem(Icons.Default.Person, stringResource(R.string.about_author_name), stringResource(R.string.about_author_role))
                    AboutItem(Icons.Default.Email, stringResource(R.string.about_contact), stringResource(R.string.about_contact_email)) {
                        uriHandler.openUri("mailto:Narayanchetri.dev@gmail.com")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Update Check Button
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.checkForUpdates() },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (updateResult is UpdateCheckResult.Checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.about_checking_for_updates), style = MaterialTheme.typography.labelLarge)
                    } else {
                        Icon(Icons.Default.Update, null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.about_check_for_updates), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}


@Composable
fun AboutItem(icon: ImageVector, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onClick != null) {
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MainTopBar(
    title: String,
    path: String?,
    archiveName: String?,
    viewModel: RoseViewModel,
    currentView: String,
    totalItems: Int? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onSearchClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onNewFileClick: () -> Unit,
    onNewFolderClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNavigate: (File) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Column {
        TopAppBar(
            title = {
                val isAlbum = viewModel.categoryBucketId != null
                Column {
                    if (title == stringResource(R.string.category_all_files) && sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            Text(
                                title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.sharedBounds(
                                    rememberSharedContentState(key = "all_files_title"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
                                )
                            )
                        }
                    } else {
                        Text(
                            title,
                            style = if (isAlbum) MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp) else MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (totalItems != null) {
                        Text(
                            stringResource(R.string.total_items_in_total, totalItems),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                }
            },
            actions = {
                if (currentView != "Recent") {
                    IconButton(onClick = onSearchClick) { Icon(Icons.Default.Search, null) }
                    IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Default.Sort, null) }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                        ) {
                            val currentCustomStorage = viewModel.storageDevices.filterIsInstance<StorageDevice.DocumentTree>().firstOrNull { dev ->
                                path != null && (path == dev.path || path.startsWith(dev.path) || path.replace("://", ":/").startsWith(dev.path.replace("://", ":/")))
                            }
                            if (currentCustomStorage != null) {
                                val isCurrentStorageLocked = viewModel.isStorageLocked(currentCustomStorage.path) || currentCustomStorage.isLocked
                                DropdownMenuItem(
                                    text = { Text(if (isCurrentStorageLocked) "Remove Fingerprint Lock" else "Lock This Folder", modifier = Modifier.padding(vertical = 4.dp)) },
                                    onClick = {
                                        showMoreMenu = false
                                        val activity = context as? androidx.fragment.app.FragmentActivity
                                        if (activity != null && VaultSecurityManager.isBiometricAvailable(context)) {
                                            VaultSecurityManager.authenticate(
                                                activity = activity,
                                                title = if (isCurrentStorageLocked) "Unlock ${currentCustomStorage.name}" else "Lock ${currentCustomStorage.name}",
                                                subtitle = "Authenticate fingerprint to confirm",
                                                onSuccess = {
                                                    viewModel.setStorageLocked(currentCustomStorage.path, !isCurrentStorageLocked)
                                                    Toast.makeText(
                                                        context,
                                                        if (isCurrentStorageLocked) "Fingerprint lock removed" else "Folder locked with fingerprint",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                onError = { err ->
                                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        } else {
                                            viewModel.setStorageLocked(currentCustomStorage.path, !isCurrentStorageLocked)
                                            Toast.makeText(
                                                context,
                                                if (isCurrentStorageLocked) "Lock removed" else "Folder locked",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isCurrentStorageLocked) Icons.Default.LockOpen else Icons.Default.Fingerprint,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            }
                            if (currentView != "Category" && viewModel.currentZipFile == null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_new_file), modifier = Modifier.padding(vertical = 4.dp)) },
                                    onClick = { onNewFileClick(); showMoreMenu = false },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null) },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_new_folder), modifier = Modifier.padding(vertical = 4.dp)) },
                                    onClick = { onNewFolderClick(); showMoreMenu = false },
                                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            }
                            DropdownMenuItem(
                                text = { Text(if (viewModel.showHiddenFiles) stringResource(R.string.action_hide_hidden_files) else stringResource(R.string.action_show_hidden_files), modifier = Modifier.padding(vertical = 4.dp)) },
                                onClick = { viewModel.toggleHiddenFiles(); showMoreMenu = false },
                                leadingIcon = { Icon(if (viewModel.showHiddenFiles) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_refresh), modifier = Modifier.padding(vertical = 4.dp)) },
                                onClick = { onRefreshClick(); showMoreMenu = false },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_settings), modifier = Modifier.padding(vertical = 4.dp)) },
                                onClick = { onSettingsClick(); showMoreMenu = false },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        SortMenu(expanded = showSortMenu, viewModel = viewModel, currentView = currentView, onDismiss = { showSortMenu = false })
                    }
                }
            }
        )
        if (path != null) {
            val breadcrumbPath = if (archiveName != null) "$path > [$archiveName]" else path
            Breadcrumbs(
                path = breadcrumbPath,
                storageDevices = viewModel.storageDevices,
                viewModel = viewModel,
                onNavigate = onNavigate
            )
        }
    }
}


@Composable
fun Breadcrumbs(
    path: String,
    storageDevices: List<StorageDevice> = emptyList(),
    viewModel: RoseViewModel? = null,
    onNavigate: (File) -> Unit
) {
    val context = LocalContext.current
    val internalRootPath = Environment.getExternalStorageDirectory().absolutePath
    val internalStorageLabel = stringResource(R.string.breadcrumbs_internal_storage)
    val rootLabel = stringResource(R.string.breadcrumbs_root)
    val isInternal = path == internalRootPath || path.startsWith("$internalRootPath/")
    val isRootBrowsing = !isInternal && (path == "/" || dev.narayan.rose.RootManager.isRootPath(path))

    val isSaf = SafManager.isSafUri(path)
    val knownTreeDevice = if (!isInternal && !isRootBrowsing) {
        storageDevices.filterIsInstance<StorageDevice.DocumentTree>().firstOrNull { dev ->
            val devClean = dev.path.replace("://", ":/").trimEnd('/')
            val pathClean = path.replace("://", ":/").trimEnd('/')
            path == dev.path || path.startsWith(dev.path) || pathClean == devClean || pathClean.startsWith("$devClean/")
        }
    } else null

    // Match physical device from storageDevices list (e.g. SD Card, USB OTG drive)
    val knownPhysicalDevice = if (!isInternal && !isRootBrowsing && knownTreeDevice == null) {
        storageDevices.filterIsInstance<StorageDevice.Physical>().firstOrNull { dev ->
            dev.isSdCard && (path == dev.path || path.startsWith("${dev.path}/"))
        }
    } else null

    // Fallback: If storageDevices hasn't loaded yet or volume wasn't enumerated, detect /storage/<volume-id>
    val storageVolumeRoot = if (!isInternal && !isRootBrowsing && knownTreeDevice == null && knownPhysicalDevice == null && path.startsWith("/storage/")) {
        val segment = path.removePrefix("/storage/").substringBefore('/')
        if (segment.isNotEmpty() && segment != "emulated" && segment != "self") {
            "/storage/$segment"
        } else null
    } else null

    val (activeStorageRootPath, activeStorageLabel) = when {
        isInternal -> internalRootPath to internalStorageLabel
        isRootBrowsing -> "/" to rootLabel
        knownTreeDevice != null -> knownTreeDevice.path to knownTreeDevice.name
        knownPhysicalDevice != null -> knownPhysicalDevice.path to knownPhysicalDevice.name
        storageVolumeRoot != null -> storageVolumeRoot to File(storageVolumeRoot).name
        isSaf -> path to (Uri.parse(path).lastPathSegment ?: "Drive")
        else -> "" to ""
    }

    val relativePath = when {
        isInternal -> internalStorageLabel + path.removePrefix(internalRootPath)
        isRootBrowsing -> if (path == "/") rootLabel else "$rootLabel$path"
        knownTreeDevice != null -> {
            val devClean = knownTreeDevice.path.replace("://", ":/").trimEnd('/')
            val pathClean = path.replace("://", ":/").trimEnd('/')
            if (path == knownTreeDevice.path || pathClean == devClean) {
                knownTreeDevice.name
            } else {
                val subPath = pathClean.removePrefix(devClean).trimStart('/')
                "${knownTreeDevice.name}/$subPath"
            }
        }
        knownPhysicalDevice != null -> activeStorageLabel + path.removePrefix(knownPhysicalDevice.path)
        storageVolumeRoot != null -> activeStorageLabel + path.removePrefix(storageVolumeRoot)
        isSaf -> activeStorageLabel
        else -> path
    }

    val parts = relativePath.split("/").filter { it.isNotEmpty() }
    val scrollState = rememberScrollState()
    var showStorageDropdown by remember { mutableStateOf(false) }

    val hasMultipleStorages = storageDevices.size > 1

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        parts.forEachIndexed { index, part ->
            if (index == 0 && hasMultipleStorages) {
                // Interactive Storage Switcher Dropdown Trigger
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showStorageDropdown = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = part,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (parts.size == 1) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (parts.size == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
                        )
                        val isCurrentTreeLocked = knownTreeDevice != null && ((viewModel?.isStorageLocked(knownTreeDevice.path) == true) || knownTreeDevice.isLocked)
                        if (isCurrentTreeLocked) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked with fingerprint",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Switch storage",
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(if (showStorageDropdown) 180f else 0f),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    DropdownMenu(
                        expanded = showStorageDropdown,
                        onDismissRequest = { showStorageDropdown = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)
                    ) {
                        storageDevices.forEachIndexed { devIndex, dev ->
                            val isCurrent = when (dev) {
                                is StorageDevice.Root -> isRootBrowsing
                                is StorageDevice.DocumentTree -> path == dev.path || path.startsWith(dev.path)
                                is StorageDevice.Physical -> {
                                    if (dev.isSdCard) {
                                        path == dev.path || path.startsWith("${dev.path}/") ||
                                                (storageVolumeRoot != null && dev.path == storageVolumeRoot)
                                    } else {
                                        isInternal
                                    }
                                }
                            }
                            val isDevLocked = (viewModel?.isStorageLocked(dev.path) == true) || dev.isLocked
                            val isRootDev = dev is StorageDevice.Root
                            val isSd = dev is StorageDevice.Physical && dev.isSdCard
                            val isUsb = dev is StorageDevice.Physical && dev.isUsb
                            val isDocTree = dev is StorageDevice.DocumentTree
                            val devIcon = when {
                                isRootDev -> Icons.Default.Tag
                                isUsb -> Icons.Default.Usb
                                isSd -> Icons.Default.SdCard
                                isDocTree -> if (dev.isCloud) Icons.Default.Cloud else Icons.Default.Storage
                                else -> Icons.Default.PhoneAndroid
                            }
                            val devColor = when {
                                isRootDev -> Color(0xFFF59E0B)
                                isUsb -> Color(0xFF0D9488)
                                isSd -> Color(0xFF3B82F6)
                                isDocTree -> if (dev.isCloud) Color(0xFF4285F4) else Color(0xFF8B5CF6)
                                else -> MaterialTheme.colorScheme.primary
                            }
                            val devDisplayName = when {
                                isRootDev -> stringResource(R.string.storage_root)
                                isSd || isUsb -> dev.name
                                isDocTree -> dev.name
                                else -> stringResource(R.string.breadcrumbs_internal_storage)
                            }

                            DropdownMenuItem(
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .background(devColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(devIcon, contentDescription = null, tint = devColor, modifier = Modifier.size(18.dp))
                                    }
                                },
                                text = {
                                    Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = devDisplayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isCurrent) devColor else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isDevLocked) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Lock,
                                                    contentDescription = "Locked with fingerprint",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                            if (isRootDev) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                                                    contentColor = Color(0xFFF59E0B),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "SU",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        val subtitle = when {
                                            isRootDev -> stringResource(R.string.storage_root_desc)
                                            dev.totalBytes > 0L -> {
                                                val freeStr = Formatter.formatFileSize(context, dev.availableBytes)
                                                val totalStr = Formatter.formatFileSize(context, dev.totalBytes)
                                                stringResource(R.string.storage_free_of_total, freeStr, totalStr)
                                            }
                                            dev is StorageDevice.DocumentTree -> dev.providerName ?: if (dev.isCloud) {
                                                stringResource(R.string.storage_cloud)
                                            } else {
                                                stringResource(R.string.storage_badge_custom)
                                            }
                                            SafManager.isSafUri(dev.path) -> dev.name
                                            else -> dev.path
                                        }
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                trailingIcon = if (isCurrent) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = devColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                } else null,
                                onClick = {
                                    showStorageDropdown = false
                                    if (!isCurrent) {
                                        if (isDevLocked) {
                                            val activity = context as? androidx.fragment.app.FragmentActivity
                                            if (activity != null && VaultSecurityManager.isBiometricAvailable(context)) {
                                                VaultSecurityManager.authenticate(
                                                    activity = activity,
                                                    title = "Unlock $devDisplayName",
                                                    subtitle = "Touch fingerprint sensor to open folder",
                                                    onSuccess = {
                                                        onNavigate(File(dev.path))
                                                    },
                                                    onError = { err ->
                                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            } else {
                                                onNavigate(File(dev.path))
                                            }
                                        } else {
                                            onNavigate(File(dev.path))
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            )
                            if (devIndex < storageDevices.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 58.dp, end = 14.dp),
                                    thickness = 0.6.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = part,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (index == parts.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val targetPath = when {
                            index == 0 && activeStorageRootPath.isNotEmpty() -> File(activeStorageRootPath)
                            isSaf -> File(activeStorageRootPath)
                            isInternal -> {
                                val subParts = parts.subList(1, index + 1)
                                if (subParts.isEmpty()) File(internalRootPath)
                                else File(internalRootPath, subParts.joinToString("/"))
                            }
                            isRootBrowsing -> {
                                val subParts = parts.subList(1, index + 1)
                                if (subParts.isEmpty()) File("/")
                                else File("/" + subParts.joinToString("/"))
                            }
                            knownPhysicalDevice != null -> {
                                val subParts = parts.subList(1, index + 1)
                                if (subParts.isEmpty()) File(knownPhysicalDevice.path)
                                else File(knownPhysicalDevice.path, subParts.joinToString("/"))
                            }
                            storageVolumeRoot != null -> {
                                val subParts = parts.subList(1, index + 1)
                                if (subParts.isEmpty()) File(storageVolumeRoot)
                                else File(storageVolumeRoot, subParts.joinToString("/"))
                            }
                            else -> File("/" + parts.subList(0, index + 1).joinToString("/"))
                        }
                        onNavigate(targetPath)
                    }
                )
            }
            if (index < parts.lastIndex) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).padding(horizontal = 4.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    LaunchedEffect(parts.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    viewModel: RoseViewModel,
    isAllSelected: Boolean,
    onSelectAllClick: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                stringResource(R.string.selection_count, viewModel.selectedFiles.size),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        },
        navigationIcon = {
            IconButton(onClick = { viewModel.exitSelectionMode() }) {
                Icon(Icons.Default.Close, null)
            }
        },
        actions = {
            IconButton(onClick = onSelectAllClick) {
                Icon(
                    imageVector = if (isAllSelected) Icons.Default.Deselect else Icons.Default.Checklist,
                    contentDescription = if (isAllSelected) stringResource(R.string.action_unselect_all) else stringResource(R.string.action_select_all)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
fun SelectionBottomBar(
    viewModel: RoseViewModel,
    currentView: String,
    onShare: () -> Unit,
    onDeleteClick: () -> Unit,
    onCompressClick: () -> Unit,
    onRenameClick: () -> Unit,
    onExtractClick: () -> Unit,
    isAllSelected: Boolean,
    onSelectAllClick: () -> Unit,
    onOpenArchiveClick: ((FileItem) -> Unit)? = null,
    onOpenPath: ((String, FileItem?) -> Unit)? = null
) {
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
        ) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionBottomBarItem(
                    icon = Icons.Outlined.Share,
                    label = stringResource(R.string.action_share),
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                )
                SelectionBottomBarItem(
                    icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                    label = stringResource(R.string.action_move),
                    onClick = {
                        val isVirtual = currentView == "Category" || currentView == "Recent" || currentView == "Favourites"
                        viewModel.moveSelected()
                        if (isVirtual) {
                            onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = viewModel.currentZipFile == null
                )
                SelectionBottomBarItem(
                    icon = Icons.Outlined.Delete,
                    label = stringResource(R.string.action_delete),
                    onClick = onDeleteClick,
                    modifier = Modifier.weight(1f),
                    enabled = viewModel.currentZipFile == null
                )
                SelectionBottomBarItem(
                    icon = Icons.Outlined.ContentCopy,
                    label = stringResource(R.string.action_copy),
                    onClick = {
                        val isVirtual = currentView == "Category" || currentView == "Recent" || currentView == "Favourites"
                        viewModel.copySelected()
                        if (isVirtual) {
                            onOpenPath?.invoke(Environment.getExternalStorageDirectory().absolutePath, null)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                var showMoreMenu by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    SelectionBottomBarItem(
                        icon = Icons.Default.MoreVert,
                        label = stringResource(R.string.action_more),
                        onClick = { showMoreMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (showMoreMenu) {
                        val density = LocalDensity.current
                        val gapPx = with(density) { 8.dp.roundToPx() }
                        val endMarginPx = with(density) { 8.dp.roundToPx() }
                        val popupPositionProvider = remember(gapPx, endMarginPx) {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize
                                ): androidx.compose.ui.unit.IntOffset {
                                    val x = (windowSize.width - popupContentSize.width - endMarginPx).coerceAtLeast(0)
                                    val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        }
                        androidx.compose.ui.window.Popup(
                            popupPositionProvider = popupPositionProvider,
                            onDismissRequest = { showMoreMenu = false },
                            properties = androidx.compose.ui.window.PopupProperties(focusable = true)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 3.dp,
                                shadowElevation = 8.dp,
                                modifier = Modifier.width(IntrinsicSize.Max)
                            ) {
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    if (currentView != "Recent" && viewModel.currentZipFile == null) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_compress)) },
                                            onClick = {
                                                onCompressClick()
                                                showMoreMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.Default.Archive, null) }
                                        )
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (isAllSelected) stringResource(R.string.action_unselect_all) else stringResource(R.string.action_select_all)) },
                                        onClick = {
                                            onSelectAllClick()
                                            showMoreMenu = false
                                        },
                                        leadingIcon = { Icon(if (isAllSelected) Icons.Default.Deselect else Icons.Default.Checklist, null) }
                                    )
                                    if (viewModel.selectedFiles.size == 1 && viewModel.currentZipFile == null) {
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_rename)) },
                                            onClick = {
                                                onRenameClick()
                                                showMoreMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                                        )
                                    }
                                    val fileOnlySelected = viewModel.selectedFiles.filter { !it.isDirectory && !viewModel.isCloudStorage(it.file.absolutePath) }
                                    if (fileOnlySelected.isNotEmpty() && viewModel.currentZipFile == null) {
                                        val allFav = fileOnlySelected.all { viewModel.isFileInFavourites(it.file.absolutePath) }
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (allFav) stringResource(R.string.action_remove_from_favourites)
                                                    else stringResource(R.string.action_add_to_favourites)
                                                )
                                            },
                                            onClick = {
                                                if (allFav) {
                                                    viewModel.removeFavourites(fileOnlySelected)
                                                    Toast.makeText(context, context.getString(R.string.toast_removed_from_favourites), Toast.LENGTH_SHORT).show()
                                                } else {
                                                    viewModel.addFavourites(fileOnlySelected)
                                                    Toast.makeText(context, context.getString(R.string.toast_added_to_favourites), Toast.LENGTH_SHORT).show()
                                                }
                                                viewModel.exitSelectionMode()
                                                showMoreMenu = false
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    if (allFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                    null,
                                                    tint = Color(0xFFE11D48)
                                                )
                                            }
                                        )
                                    }
                                    if (fileOnlySelected.isNotEmpty() && viewModel.currentZipFile == null) {
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_move_to_safe)) },
                                            onClick = {
                                                val toMove = fileOnlySelected.toList()
                                                viewModel.exitSelectionMode()
                                                showMoreMenu = false
                                                viewModel.moveToVault(toMove) { count ->
                                                    if (count > 0) {
                                                        Toast.makeText(context, context.getString(R.string.toast_moved_to_safe), Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Lock,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        )
                                    }
                                    val savableOfflineFiles = fileOnlySelected.filter {
                                        !it.isDirectory && viewModel.isCloudStorage(it.file.absolutePath)
                                    }
                                    if (savableOfflineFiles.isNotEmpty() && viewModel.currentZipFile == null) {
                                        val allOffline = savableOfflineFiles.all { viewModel.isFileOffline(it.file.absolutePath) }
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (allOffline) stringResource(R.string.action_remove_offline)
                                                    else stringResource(R.string.action_save_offline)
                                                )
                                            },
                                            onClick = {
                                                savableOfflineFiles.forEach { viewModel.toggleOfflineStatus(it) }
                                                val msg = if (allOffline) {
                                                    context.getString(R.string.toast_removed_from_offline)
                                                } else {
                                                    context.getString(R.string.toast_saving_offline)
                                                }
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                viewModel.exitSelectionMode()
                                                showMoreMenu = false
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    if (allOffline) Icons.Default.CloudDone else Icons.Default.Download,
                                                    null,
                                                    tint = if (allOffline) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        )
                                    }
                                    if (viewModel.selectedFiles.size == 1) {
                                        val fileItem = viewModel.selectedFiles.first()
                                        if (fileItem.isDirectory && viewModel.currentZipFile == null) {
                                            val isAlreadyInQuickAccess = viewModel.isFolderInQuickAccess(fileItem.file.absolutePath)
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (isAlreadyInQuickAccess) stringResource(R.string.action_already_in_quick_access)
                                                        else stringResource(R.string.action_add_to_quick_access)
                                                    )
                                                },
                                                enabled = !isAlreadyInQuickAccess,
                                                onClick = {
                                                    if (!isAlreadyInQuickAccess) {
                                                        viewModel.addQuickAccessFolder(fileItem.file.absolutePath)
                                                        Toast.makeText(context, context.getString(R.string.toast_added_to_quick_access), Toast.LENGTH_SHORT).show()
                                                    }
                                                    viewModel.exitSelectionMode()
                                                    showMoreMenu = false
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        if (isAlreadyInQuickAccess) Icons.Default.Check else Icons.Default.Add,
                                                        null
                                                    )
                                                }
                                            )
                                        }
                                        if (fileItem.fileType == FileType.ZIP && viewModel.currentZipFile == null) {
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.action_extract)) },
                                                onClick = {
                                                    onExtractClick()
                                                    showMoreMenu = false
                                                },
                                                leadingIcon = { Icon(Icons.Outlined.Unarchive, null) }
                                            )
                                        }
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_properties)) },
                                            onClick = {
                                                viewModel.showProperties(fileItem)
                                                viewModel.exitSelectionMode()
                                                showMoreMenu = false
                                            },
                                            leadingIcon = { Icon(Icons.Default.Info, null) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun SelectionBottomBarItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true
) {
    val finalTint = if (enabled) tint else tint.copy(alpha = 0.38f)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = finalTint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = finalTint
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onBack: () -> Unit) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.search_files_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        },
        navigationIcon = {
            IconButton(onClick = { onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            }
        }
    )
}

@Composable
fun SortMenu(expanded: Boolean, viewModel: RoseViewModel, currentView: String, onDismiss: () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
    ) {
        SortMenuItem(stringResource(R.string.sort_name), RoseViewModel.SortBy.NAME, viewModel)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
        SortMenuItem(stringResource(R.string.sort_type), RoseViewModel.SortBy.TYPE, viewModel)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
        SortMenuItem(stringResource(R.string.sort_size), RoseViewModel.SortBy.SIZE, viewModel)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
        SortMenuItem(stringResource(R.string.sort_last_modified), RoseViewModel.SortBy.DATE, viewModel)

        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))

        val isGridView = if (currentView == "Category" || currentView == "Favourites") viewModel.isCategoryGridView else viewModel.isGridView

        DropdownMenuItem(
            text = { Text(if (isGridView) stringResource(R.string.view_list) else stringResource(R.string.view_grid)) },
            onClick = {
                if (currentView == "Category" || currentView == "Favourites") {
                    viewModel.setCategoryGridView(!isGridView)
                } else {
                    viewModel.setGridView(!isGridView)
                }
                onDismiss()
            },
            leadingIcon = {
                Icon(
                    if (isGridView) Icons.Default.ViewList else Icons.Default.ViewModule,
                    contentDescription = null
                )
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}


@Composable
fun SortMenuItem(label: String, sortBy: RoseViewModel.SortBy, viewModel: RoseViewModel) {
    val isSelected = viewModel.sortBy == sortBy
    DropdownMenuItem(
        text = { Text(label, modifier = Modifier.padding(vertical = 4.dp)) },
        onClick = { viewModel.setSortOrder(sortBy) },
        trailingIcon = {
            if (isSelected) {
                Icon(
                    if (viewModel.sortOrder == RoseViewModel.SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    fileItem: FileItem,
    isSelected: Boolean,
    showDetails: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showExtension: Boolean = true,
    iconSize: Dp = 64.dp,
    isVirtual: Boolean = false,
    index: Int = 0,
    scrollResetKey: Any = Unit,
    hasAnimatedBefore: Boolean = true,
    onAnimationStart: () -> Unit = {},
    isHighlighted: Boolean = false,
    viewModel: RoseViewModel? = null
) {
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)

    val pulseAlpha by if (isHighlighted) {
        val infiniteTransition = rememberInfiniteTransition(label = "GridPulseAlphaTransition")
        infiniteTransition.animateFloat(
            initialValue = 0.08f,
            targetValue = 0.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "GridPulseAlpha"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val backgroundColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            isSelected -> selectionColor
            isHighlighted -> MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
            else -> Color.Transparent
        },
        animationSpec = if (isHighlighted) snap() else tween(600, easing = FastOutSlowInEasing),
        label = "GridItemSelection"
    )

    val isHidden = fileItem.name.startsWith(".")
    val itemAlpha = if (isHidden) 0.55f else 1f

    Column(
        modifier = (if (itemAlpha < 1f) modifier.graphicsLayer { alpha = itemAlpha } else modifier)
            .padding(8.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(backgroundColor)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val vm = viewModel ?: (LocalContext.current as? androidx.activity.ComponentActivity)?.let { (it as? MainActivity)?.viewModel }
        val isFav = remember(fileItem.file.absolutePath, vm?.favouriteFiles) {
            if (fileItem.isDirectory || vm?.isCloudStorage(fileItem.file.absolutePath) == true) false else vm?.isFileInFavourites(fileItem.file.absolutePath) == true
        }
        Box(contentAlignment = Alignment.Center) {
            FileIcon(fileItem, iconSize = iconSize, isVirtual = isVirtual, viewModel = vm, showFavouriteBadge = isFav)
            if (isSelected) {
                Box(
                    modifier = Modifier.size(iconSize * 0.5f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, null, tint = Color.White)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            displayNameFor(fileItem, showExtension),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (fileItem.isDirectory) FontWeight.Bold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (showDetails) {
            if (fileItem.isDirectory) {
                Text(
                    stringResource(R.string.items_count, fileItem.itemCount ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    formatFileSize(fileItem.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    fileItem: FileItem,
    isSelected: Boolean,
    showDetails: Boolean,
    isVirtual: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onRenameRequest: () -> Unit = {},
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onProperties: () -> Unit,
    viewModel: RoseViewModel,
    modifier: Modifier = Modifier,
    showExtension: Boolean = true,
    showListDividers: Boolean = true,
    clipboardHasFiles: Boolean = false,
    isSelectionMode: Boolean = false,
    isHighlighted: Boolean = false,
    onOpenWith: (() -> Unit)? = null, // New parameter
    onOpenLocation: (() -> Unit)? = null, // New parameter
    onExtract: (() -> Unit)? = null,
    onOpenArchive: (() -> Unit)? = null,
    index: Int = 0,
    scrollResetKey: Any = Unit,
    hasAnimatedBefore: Boolean = true,
    onAnimationStart: () -> Unit = {},
    dragSelectState: CheckboxDragSelectState? = null,
    isDividerVisible: Boolean = false
) {
    // Material Files-style staggered entrance animation - plays once per item
    // per folder session (see animatedItemKeys in FileExplorerScreen), not
    // every time this row scrolls back into view.
    val context = LocalContext.current

    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

    val pulseAlpha by if (isHighlighted) {
        val infiniteTransition = rememberInfiniteTransition(label = "ItemPulseAlphaTransition")
        infiniteTransition.animateFloat(
            initialValue = 0.08f,
            targetValue = 0.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ItemPulseAlpha"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val backgroundColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            isSelected -> selectionColor
            isHighlighted -> MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
            else -> Color.Transparent
        },
        animationSpec = if (isHighlighted) snap() else tween(600, easing = FastOutSlowInEasing),
        label = "ItemHighlight"
    )

    val isHidden = fileItem.name.startsWith(".")
    val itemAlpha = if (isHidden) 0.55f else 1f

    Column(
        modifier = if (itemAlpha < 1f) modifier.graphicsLayer { alpha = itemAlpha } else modifier
    ) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayNameFor(fileItem, showExtension),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (fileItem.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                        style = if (fileItem.isDirectory) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            },
            supportingContent = {
                val dateStr = formatItemDate(fileItem.lastModified)
                if (fileItem.isDirectory) {
                    val count = fileItem.itemCount ?: 0
                    Text(stringResource(R.string.item_count_and_date, count, dateStr))
                } else if (showDetails) {
                    Text(stringResource(R.string.item_size_and_date, formatFileSize(fileItem.size), dateStr))
                }
            },
            leadingContent = {
                val isFav = remember(fileItem.file.absolutePath, viewModel?.favouriteFiles) {
                    if (fileItem.isDirectory || viewModel?.isCloudStorage(fileItem.file.absolutePath) == true) false else viewModel?.isFileInFavourites(fileItem.file.absolutePath) == true
                }
                Box(contentAlignment = Alignment.Center) {
                    FileIcon(fileItem, iconSize = 44.dp, isVirtual = isVirtual, viewModel = viewModel, showFavouriteBadge = isFav)
                }
            },
            trailingContent = {
                if (isSelectionMode) {
                    // The actual drag-to-select gesture (hit-testing, toggling, driving
                    // auto-scroll) now lives one level up, at the container in
                    // FileExplorerScreen - see detectCheckboxDragSelect. That detector
                    // needs to know where every visible checkbox currently is, so this
                    // row still registers/unregisters its own bounds; it just no longer
                    // owns any part of the gesture itself, so nothing here gets torn
                    // down when this row scrolls off-screen mid-drag.
                    val boundsModifier = if (dragSelectState != null) {
                        DisposableEffect(fileItem.file.absolutePath) {
                            onDispose {
                                // Item scrolled out of the visible window and got disposed by
                                // LazyColumn - if we don't remove its stale bounds here, a
                                // later drag could still hit-test against wherever this row
                                // used to be and toggle the wrong (now off-screen) file.
                                dragSelectState.itemBounds.remove(fileItem.file.absolutePath)
                            }
                        }
                        Modifier.onGloballyPositioned { coords ->
                            dragSelectState.itemBounds[fileItem.file.absolutePath] = coords.boundsInWindow()
                        }
                    } else Modifier

                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = boundsModifier,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    )
                } else if (fileItem.isDirectory) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                } else {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.content_desc_more_options),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            if (onOpenLocation != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_open_location)) },
                                    onClick = { showMenu = false; onOpenLocation() },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, null) }
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_open_with)) },
                                onClick = { showMenu = false; onOpenWith?.invoke() },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_share)) },
                                onClick = { showMenu = false; onShare() },
                                leadingIcon = { Icon(Icons.Default.Share, null) }
                            )
                            val isCloud = viewModel?.isCloudStorage(fileItem.file.absolutePath) == true
                            if (!isCloud && !isVirtual && !fileItem.isDirectory && viewModel?.currentZipFile == null && viewModel != null) {
                                val isFav = viewModel.isFileInFavourites(fileItem.file.absolutePath)
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isFav) stringResource(R.string.action_remove_from_favourites)
                                            else stringResource(R.string.action_add_to_favourites)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        if (isFav) {
                                            viewModel.removeFavouriteFile(fileItem.file.absolutePath)
                                            Toast.makeText(context, context.getString(R.string.toast_removed_from_favourites), Toast.LENGTH_SHORT).show()
                                        } else {
                                            viewModel.addFavouriteFile(fileItem.file.absolutePath)
                                            Toast.makeText(context, context.getString(R.string.toast_added_to_favourites), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            null,
                                            tint = Color(0xFFE11D48)
                                        )
                                    }
                                )
                            }
                            val canSaveOffline = !isVirtual && !fileItem.isDirectory && viewModel?.currentZipFile == null && isCloud
                            if (canSaveOffline && viewModel != null) {
                                val isOffline = viewModel.isFileOffline(fileItem.file.absolutePath)
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isOffline) stringResource(R.string.action_remove_offline)
                                            else stringResource(R.string.action_save_offline)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        viewModel.toggleOfflineStatus(fileItem)
                                        val msg = if (isOffline) {
                                            context.getString(R.string.toast_removed_from_offline)
                                        } else {
                                            context.getString(R.string.toast_saving_offline)
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isOffline) Icons.Default.CloudDone else Icons.Default.Download,
                                            contentDescription = null,
                                            tint = if (isOffline) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                )
                            }
                            if (!isVirtual && !fileItem.isDirectory && !isCloud && viewModel?.currentZipFile == null && viewModel != null) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_move_to_safe)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.moveToVault(listOf(fileItem)) { count ->
                                            if (count > 0) {
                                                Toast.makeText(context, context.getString(R.string.toast_moved_to_safe), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Lock,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                )
                            }
                            if (!isVirtual) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_rename)) },
                                    onClick = { showMenu = false; onRenameRequest() },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                                )
                            }
                            if ((fileItem.fileType == FileType.ZIP || isVirtual) && !isVirtual) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_extract)) },
                                    onClick = { showMenu = false; onExtract?.invoke() },
                                    leadingIcon = { Icon(Icons.Outlined.Unarchive, null) }
                                )
                            }
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 0.dp)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .background(backgroundColor)
        )
        if (isDividerVisible) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            )
        }
    }
}


fun getFolderSpecialSymbol(name: String, path: String? = null): ImageVector? {
    val cleanName = name.trim().lowercase()
    val cleanPath = path?.trimEnd('/', '\\')?.lowercase() ?: ""
    return when {
        cleanName == "download" || cleanName == "downloads" || cleanPath.endsWith("/download") || cleanPath.endsWith("/downloads") ->
            Icons.Default.Download
        cleanName == "documents" || cleanName == "document" || cleanName == "doc" || cleanName == "docs" || cleanPath.endsWith("/documents") ->
            Icons.Default.Description
        cleanName == "dcim" || cleanName == "camera" || cleanPath.endsWith("/dcim") || cleanPath.endsWith("/dcim/camera") || cleanPath.endsWith("/camera") ->
            Icons.Default.CameraAlt
        cleanName == "pictures" || cleanName == "photos" || cleanName == "screenshots" || cleanPath.endsWith("/pictures") ->
            Icons.Default.Image
        cleanName == "music" || cleanName == "audio" || cleanPath.endsWith("/music") ->
            Icons.Default.MusicNote
        cleanName == "movies" || cleanName == "videos" || cleanName == "video" || cleanPath.endsWith("/movies") || cleanPath.endsWith("/video") ->
            Icons.Default.Movie
        else -> null
    }
}

@Composable
fun FileIcon(
    fileItem: FileItem,
    iconSize: Dp = 52.dp,
    isVirtual: Boolean = false,
    folderTint: Color = Color(0xFF70C1FF),
    viewModel: RoseViewModel? = null,
    showFavouriteBadge: Boolean = false
) {
    val context = LocalContext.current
    var isThumbnailLoaded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.size(iconSize), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            when (fileItem.fileType) {
                FileType.IMAGE, FileType.VIDEO -> {
                    val placeholderIcon = if (fileItem.fileType == FileType.IMAGE) Icons.Default.Image else Icons.Default.Movie

                if (isVirtual && fileItem.virtualZipSource != null) {
                    val zipThumb by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = fileItem.file.absolutePath, key2 = viewModel?.cachedZipPassword) {
                        value = withContext(Dispatchers.IO) {
                            val entryPath = fileItem.zipEntryPath ?: fileItem.name
                            val bytes = ArchiveManager.getEntryBytes(fileItem.virtualZipSource, entryPath, passphrase = viewModel?.cachedZipPassword)
                            if (bytes != null) {
                                try {
                                    val bounds = android.graphics.BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

                                    val targetPx = 200
                                    var sampleSize = 1
                                    while (bounds.outWidth / sampleSize > targetPx * 2 ||
                                        bounds.outHeight / sampleSize > targetPx * 2
                                    ) {
                                        sampleSize *= 2
                                    }

                                    val opts = android.graphics.BitmapFactory.Options().apply {
                                        inSampleSize = sampleSize
                                    }
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                } catch (e: Exception) {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                        if (value != null) isThumbnailLoaded = true
                    }

                    if (zipThumb != null) {
                        Image(
                            bitmap = zipThumb!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        FileTypeBadge(placeholderIcon, if (fileItem.fileType == FileType.IMAGE) Color(0xFF4C8DFF) else Color(0xFF9C6ADE), iconSize, Modifier.fillMaxSize())
                    }
                } else if (SafManager.isSafUri(fileItem.file.absolutePath)) {
                    val uriString = fileItem.file.absolutePath
                    val normalizedUri = remember(uriString) {
                        val n = if (uriString.startsWith("/content:/")) {
                            uriString.substring(1).replaceFirst("content:/", "content://")
                        } else if (uriString.startsWith("/content://")) {
                            uriString.substring(1)
                        } else {
                            uriString
                        }
                        Uri.parse(n)
                    }

                    val offlineFile = viewModel?.getLocalOfflineFile(fileItem)

                    if (offlineFile != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(offlineFile)
                                .memoryCacheKey("${offlineFile.absolutePath}_${fileItem.lastModified}")
                                .diskCacheKey("${offlineFile.absolutePath}_${fileItem.lastModified}")
                                .size(128)
                                .allowHardware(true)
                                .apply {
                                    if (fileItem.fileType == FileType.VIDEO) {
                                        decoderFactory(VideoFrameDecoder.Factory())
                                        videoFrameMillis(1000)
                                    }
                                }
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = rememberVectorPainter(placeholderIcon),
                            error = rememberVectorPainter(placeholderIcon)
                        )
                    } else {
                        val safThumb by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = uriString) {
                            value = withContext(Dispatchers.IO) {
                                try {
                                    DocumentsContract.getDocumentThumbnail(
                                        context.contentResolver,
                                        normalizedUri,
                                        android.graphics.Point(128, 128),
                                        null
                                    )
                                } catch (e: Exception) { null }
                            }
                        }

                        if (safThumb != null) {
                            Image(
                                bitmap = safThumb!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            // Do NOT use AsyncImage with an arbitrary SAF content:// URI:
                            // ContentResolver gives Coil a plain stream with no random access,
                            // so decoding even a 128px thumbnail forces it to pull down the
                            // ENTIRE remote file first. DocumentsContract.getDocumentThumbnail
                            // above already tried the cheap, provider-native thumbnail path and
                            // failed/returned null - only known-local authorities (this device's
                            // own storage / MediaStore / our own FileProvider) are safe to hand
                            // to AsyncImage directly. Everything else - Google Drive, any other
                            // cloud provider, or one we don't recognize - falls back to a badge
                            // rather than silently burning the user's data plan.
                            val authority = normalizedUri.authority ?: ""
                            val isKnownLocalAuthority = authority == "com.android.externalstorage.documents" ||
                                    authority == "com.android.providers.media.documents" ||
                                    authority == "media" ||
                                    authority.endsWith(".provider") // our own FileProvider authority

                            if (isKnownLocalAuthority) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(normalizedUri)
                                        .memoryCacheKey("${uriString}_${fileItem.lastModified}")
                                        .diskCacheKey("${uriString}_${fileItem.lastModified}")
                                        .size(128)
                                        .crossfade(true)
                                        .apply {
                                            if (fileItem.fileType == FileType.VIDEO) {
                                                decoderFactory(VideoFrameDecoder.Factory())
                                                videoFrameMillis(1000)
                                            }
                                        }
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    placeholder = rememberVectorPainter(placeholderIcon),
                                    error = rememberVectorPainter(placeholderIcon)
                                )
                            } else {
                                FileTypeBadge(placeholderIcon, if (fileItem.fileType == FileType.IMAGE) Color(0xFF4C8DFF) else Color(0xFF9C6ADE), iconSize, Modifier.fillMaxSize())
                            }
                        }
                    }
                } else if (SafManager.isRestrictedPath(fileItem.file.absolutePath)) {
                    val thumbBitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = fileItem.file.absolutePath) {
                        value = RestrictedThumbnailLoader.loadThumbnail(context, fileItem.file.absolutePath, fileItem.fileType)
                    }
                    if (thumbBitmap != null) {
                        Image(
                            bitmap = thumbBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        FileTypeBadge(placeholderIcon, if (fileItem.fileType == FileType.IMAGE) Color(0xFF4C8DFF) else Color(0xFF9C6ADE), iconSize, Modifier.fillMaxSize())
                    }
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(fileItem.file)
                            .memoryCacheKey("${fileItem.file.absolutePath}_${fileItem.lastModified}")
                            .diskCacheKey("${fileItem.file.absolutePath}_${fileItem.lastModified}")
                            .size(128)
                            .allowHardware(true)
                            .apply {
                                if (fileItem.fileType == FileType.VIDEO) {
                                    decoderFactory(VideoFrameDecoder.Factory())
                                }
                            }
                            .crossfade(80)
                            .videoFrameMillis(0)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = rememberVectorPainter(placeholderIcon),
                        error = rememberVectorPainter(placeholderIcon)
                    )
                }
            }
            FileType.APK -> {
                val icon by produceState<Drawable?>(initialValue = apkIconCache.get(fileItem.file.absolutePath), key1 = fileItem.file) {
                    if (!isVirtual) {
                        val cached = apkIconCache.get(fileItem.file.absolutePath)
                        if (cached != null) {
                            value = cached
                        } else if (!SafManager.isSafUri(fileItem.file.absolutePath)) {
                            val loaded = withContext(Dispatchers.IO) { getApkIcon(context, fileItem.file) }
                            if (loaded != null) apkIconCache.put(fileItem.file.absolutePath, loaded)
                            value = loaded
                        }
                    }
                }
                if (icon != null) {
                    Image(
                        bitmap = icon!!.toBitmap().asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(iconSize * 0.15f)
                    )
                } else {
                    FileTypeBadge(Icons.Default.Android, FileIconColors.apk, iconSize, Modifier.fillMaxSize())
                }
            }
            FileType.FOLDER -> {
                val appIcon by produceState<Drawable?>(initialValue = packageIconCache.get(fileItem.name), key1 = fileItem.name) {
                    if (fileItem.name.contains(".")) {
                        val cached = packageIconCache.get(fileItem.name)
                        if (cached != null) {
                            value = cached
                        } else {
                            val loaded = withContext(Dispatchers.IO) { getPackageIcon(context, fileItem.name) }
                            if (loaded != null) packageIconCache.put(fileItem.name, loaded)
                            value = loaded
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (fileItem.thumbnailPath != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(fileItem.thumbnailPath)
                                .memoryCacheKey("${fileItem.thumbnailPath}_thumb")
                                .size(256)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = rememberVectorPainter(Icons.Default.Folder),
                            error = rememberVectorPainter(Icons.Default.Folder)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.1f))
                        )
                    } else {
                        val specialSymbol = getFolderSpecialSymbol(fileItem.name, fileItem.file.absolutePath)
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Folder,
                                null,
                                modifier = Modifier.fillMaxSize(),
                                tint = folderTint
                            )
                            if (specialSymbol != null) {
                                Icon(
                                    specialSymbol,
                                    contentDescription = null,
                                    tint = Color(0xFF1976D2).copy(alpha = 0.85f),
                                    modifier = Modifier
                                        .size(iconSize * 0.42f)
                                        .offset(y = iconSize * 0.06f)
                                )
                            }
                        }
                    }

                    if (appIcon != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 2.dp, y = 2.dp)
                                .size(iconSize * 0.45f)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                                .padding(1.dp)
                        ) {
                            Image(
                                bitmap = appIcon!!.toBitmap().asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
            FileType.ZIP -> FileTypeBadge(Icons.Default.Archive, FileIconColors.archive, iconSize, Modifier.fillMaxSize())
            FileType.PDF -> FileTypeBadge(Icons.Default.PictureAsPdf, FileIconColors.pdf, iconSize, Modifier.fillMaxSize())
            FileType.AUDIO -> FileTypeBadge(Icons.Default.MusicNote, FileIconColors.audio, iconSize, Modifier.fillMaxSize())
            FileType.DOCUMENT -> FileTypeBadge(Icons.Default.Description, FileIconColors.document, iconSize, Modifier.fillMaxSize())
            else -> FileTypeBadge(Icons.Default.InsertDriveFile, MaterialTheme.colorScheme.secondary, iconSize, Modifier.fillMaxSize())
        }
    }

    if (fileItem.isEncrypted && !isThumbnailLoaded && viewModel?.cachedZipPassword == null) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size(iconSize * 0.35f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                .padding(2.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = stringResource(R.string.content_desc_encrypted),
                modifier = Modifier.size(iconSize * 0.22f),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showFavouriteBadge && viewModel?.isCloudStorage(fileItem.file.absolutePath) != true) {
        val badgeAlignment = if (fileItem.isEncrypted && !isThumbnailLoaded && viewModel?.cachedZipPassword == null) {
            Alignment.TopEnd
        } else {
            Alignment.BottomEnd
        }
        val badgeOffsetX = 2.dp
        val badgeOffsetY = if (fileItem.isEncrypted && !isThumbnailLoaded && viewModel?.cachedZipPassword == null) (-2).dp else 2.dp
        val badgeSize = (iconSize * 0.42f).coerceIn(14.dp, 20.dp)
        val heartSize = (iconSize * 0.24f).coerceIn(8.dp, 12.dp)

        Box(
            modifier = Modifier
                .align(badgeAlignment)
                .offset(x = badgeOffsetX, y = badgeOffsetY)
                .size(badgeSize)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(1.5.dp)
                .background(Color(0xFFE11D48), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                modifier = Modifier.size(heartSize),
                tint = Color.White
            )
        }
    }

    val isOffline = viewModel?.isFileOffline(fileItem.file.absolutePath) == true
    if (isOffline) {
        val offlineBadgeSize = (iconSize * 0.44f).coerceIn(16.dp, 22.dp)
        val iconInnerSize = (iconSize * 0.28f).coerceIn(10.dp, 14.dp)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-3).dp, y = 3.dp)
                .shadow(elevation = 3.dp, shape = CircleShape)
                .size(offlineBadgeSize)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(1.5.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF10B981),
                            Color(0xFF047857)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CloudDone,
                contentDescription = stringResource(R.string.status_offline_available),
                modifier = Modifier.size(iconInnerSize),
                tint = Color.White
            )
        }
    }
}
}



private object FileIconColors {
    val archive = Color(0xFFC98A2E)
    val pdf = Color(0xFFC1554C)
    val audio = Color(0xFF5B7FDB)
    val document = Color(0xFF4C9A72)
    val apk = Color(0xFF4CAF7D)
}

@Composable
private fun FileTypeBadge(icon: ImageVector, color: Color, iconSize: Dp, modifier: Modifier) {
    Box(
        modifier = modifier.background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        val multiplier = if (icon == Icons.Default.Android) 0.55f else 0.65f
        Icon(icon, null, tint = color, modifier = Modifier.size(iconSize * multiplier))
    }
}


// In-memory cache for extracted APK icons, keyed by absolute path.
// Extracting an icon means parsing the whole archive's manifest via
// PackageManager, which is one of the more expensive things this screen
// does per item - caching it makes re-scrolling past .apk files instant.
private val apkIconCache = LruCache<String, Drawable>(60)
private val packageIconCache = LruCache<String, Drawable>(100)

fun getApkIcon(context: Context, file: File): Drawable? {
    return try {
        val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        packageInfo?.applicationInfo?.let {
            it.sourceDir = file.absolutePath
            it.publicSourceDir = file.absolutePath
            it.loadIcon(context.packageManager)
        }
    } catch (e: Exception) { null }
}

fun getPackageIcon(context: Context, packageName: String): Drawable? {
    return try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: Exception) { null }
}

@Composable
fun PropertiesDialog(fileItem: FileItem, onDismiss: () -> Unit, viewModel: RoseViewModel) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.action_properties), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PropertyRow(stringResource(R.string.prop_name), fileItem.name)
                PropertyRow(stringResource(R.string.prop_type), fileItem.fileType.name)
                PropertyRow(stringResource(R.string.prop_size), if (fileItem.isDirectory) viewModel.folderSize?.let { formatFileSize(it) } ?: stringResource(R.string.status_calculating) else formatFileSize(fileItem.size))
                PropertyRow(stringResource(R.string.prop_path), fileItem.file.absolutePath)
                PropertyRow(stringResource(R.string.prop_modified), SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(Date(fileItem.lastModified)))
            }
        },
        confirmButton = {
            Button(
                onClick = { onDismiss() },
                shape = RoundedCornerShape(12.dp)
            ) { Text(stringResource(R.string.action_close)) }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun CompressDialog(
    itemCount: Int = 1,
    onDismiss: () -> Unit,
    onConfirm: (String, ArchiveFormat, String?) -> Unit
) {
    var folderName by remember { mutableStateOf("Archive") }
    var selectedFormat by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Archive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.compress_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (itemCount > 1) {
                        stringResource(R.string.compress_items_count, itemCount)
                    } else {
                        stringResource(R.string.compress_items_count_single)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Format Selector Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.compress_format_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ArchiveFormat.values().forEach { format ->
                            val isSelected = selectedFormat == format
                            Surface(
                                onClick = { selectedFormat = format },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = if (isSelected) {
                                    androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                } else null,
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = format.displayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // File Name Input Field
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.compress_file_name_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    leadingIcon = {
                        Icon(
                            Icons.Default.FolderZip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    suffix = {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = selectedFormat.extension,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                )

                // Password Section (Animated - Only shown when ZIP format is selected)
                AnimatedVisibility(
                    visible = selectedFormat == ArchiveFormat.ZIP,
                    enter = expandVertically(animationSpec = tween(250)) + fadeIn(animationSpec = tween(250)),
                    exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.compress_password_label)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (showPassword) "Hide password" else "Show password",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                        Text(
                            text = stringResource(R.string.compress_password_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (folderName.isNotBlank()) {
                        onConfirm(
                            folderName.trim(),
                            selectedFormat,
                            if (selectedFormat == ArchiveFormat.ZIP) password.takeIf { it.isNotEmpty() } else null
                        )
                    }
                },
                enabled = folderName.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Icon(
                    Icons.Default.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.action_compress),
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun PropertyRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}


@Composable
private fun ExtractionDialog(
    item: FileItem,
    onDismiss: () -> Unit,
    onExtractHere: () -> Unit,
    onSelectLocation: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Unarchive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        },
        title = { Text(stringResource(R.string.extract_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.extract_dialog_message, item.name),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    onClick = onExtractHere,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.extract_here_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.extract_here_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    onClick = onSelectLocation,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.extract_select_location_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.extract_select_location_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun CreateFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var folderName by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CreateNewFolder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.create_folder_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                placeholder = { 
                    Text(
                        stringResource(R.string.create_folder_name_label),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    ) 
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                ),
                trailingIcon = {
                    if (folderName.isNotEmpty()) {
                        IconButton(onClick = { folderName = "" }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Words
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (folderName.isNotBlank()) onCreate(folderName.trim())
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = { if (folderName.isNotBlank()) onCreate(folderName.trim()) },
                enabled = folderName.isNotBlank(),
                shape = RoundedCornerShape(14.dp)
            ) { 
                Text(stringResource(R.string.action_create), fontWeight = FontWeight.SemiBold) 
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp)
            ) { 
                Text(stringResource(R.string.action_cancel)) 
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun CreateFileDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val defaultName = "New text file.txt"
    val dotIndex = defaultName.lastIndexOf('.')
    val selectionEnd = if (dotIndex > 0) dotIndex else defaultName.length
    var textFieldValue by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = defaultName,
                selection = androidx.compose.ui.text.TextRange(0, selectionEnd)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.create_file_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                placeholder = { 
                    Text(
                        stringResource(R.string.create_file_name_label),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    ) 
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                ),
                trailingIcon = {
                    if (textFieldValue.text.isNotEmpty()) {
                        IconButton(onClick = { textFieldValue = androidx.compose.ui.text.input.TextFieldValue("") }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Sentences
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (textFieldValue.text.isNotBlank()) onCreate(textFieldValue.text.trim())
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = { if (textFieldValue.text.isNotBlank()) onCreate(textFieldValue.text.trim()) },
                enabled = textFieldValue.text.isNotBlank(),
                shape = RoundedCornerShape(14.dp)
            ) { 
                Text(stringResource(R.string.action_create), fontWeight = FontWeight.SemiBold) 
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp)
            ) { 
                Text(stringResource(R.string.action_cancel)) 
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.password_dialog_title))
            }
        },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password_label)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = { if (password.isNotBlank()) onConfirm(password) },
                shape = RoundedCornerShape(12.dp)
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun RenameDialog(initialName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val dotIndex = initialName.lastIndexOf('.')
    val selectionEnd = if (dotIndex > 0) dotIndex else initialName.length
    var textFieldValue by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = initialName,
                selection = androidx.compose.ui.text.TextRange(0, selectionEnd)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.rename_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                placeholder = { 
                    Text(
                        stringResource(R.string.rename_new_name_label),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    ) 
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                ),
                trailingIcon = {
                    if (textFieldValue.text.isNotEmpty()) {
                        IconButton(onClick = { textFieldValue = androidx.compose.ui.text.input.TextFieldValue("") }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Words
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (textFieldValue.text.isNotBlank()) onRename(textFieldValue.text.trim())
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = { if (textFieldValue.text.isNotBlank()) onRename(textFieldValue.text.trim()) },
                enabled = textFieldValue.text.isNotBlank(),
                shape = RoundedCornerShape(14.dp)
            ) { 
                Text(stringResource(R.string.action_rename), fontWeight = FontWeight.SemiBold) 
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp)
            ) { 
                Text(stringResource(R.string.action_cancel)) 
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp)
    )
}

fun displayNameFor(fileItem: FileItem, showExtension: Boolean): String {
    if (showExtension || fileItem.isDirectory) return fileItem.name
    val dotIndex = fileItem.name.lastIndexOf('.')
    return if (dotIndex > 0) fileItem.name.substring(0, dotIndex) else fileItem.name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveJobsCard(activeJobs: List<FileJob>, onClick: () -> Unit = {}) {
    val job = activeJobs.firstOrNull() ?: return
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (job.isIndeterminate) {
                    // Unknown total size (common for cloud sources like Google
                    // Drive until the transfer is underway) - job.progress never
                    // leaves 0 in this state, so a determinate ring bound to it
                    // just looked stuck. Show a real spinner instead.
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { job.progress },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                val operationText = when (job.type) {
                    is FileJobType.Copy -> stringResource(R.string.job_copying_files_ellipsis)
                    is FileJobType.Move -> stringResource(R.string.job_moving_files_ellipsis)
                    is FileJobType.Download -> stringResource(R.string.job_saving_offline_ellipsis)
                    is FileJobType.Delete -> stringResource(R.string.job_deleting_files_ellipsis)
                    is FileJobType.Recycle -> stringResource(R.string.job_moving_to_bin_ellipsis)
                    is FileJobType.Restore -> stringResource(R.string.job_restoring_files_ellipsis)
                    is FileJobType.Extract -> stringResource(R.string.job_extracting_archive_ellipsis)
                    is FileJobType.Compress -> stringResource(R.string.job_creating_archive_ellipsis)
                    else -> stringResource(R.string.job_processing_ellipsis)
                }
                Text(
                    text = operationText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                val progressText = if (job.isIndeterminate) {
                    val bytesLabel = when {
                        job.processedBytes >= 1024 * 1024 -> "%.1f MB".format(job.processedBytes / (1024.0 * 1024.0))
                        job.processedBytes > 0 -> "%.0f KB".format(job.processedBytes / 1024.0)
                        else -> null
                    }
                    if (bytesLabel != null) stringResource(R.string.job_downloading_with_bytes, bytesLabel) else stringResource(R.string.job_downloading)
                } else if (job.totalItems > 1) {
                    stringResource(R.string.job_progress_items_and_percent, job.processedItems + 1, job.totalItems, (job.progress * 100).toInt())
                } else {
                    stringResource(R.string.job_percent_completed, (job.progress * 100).toInt())
                }
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (activeJobs.size > 1) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        "+${activeJobs.size - 1}",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // Signals this bar expands back into the full dialog on tap - matches
            // the "minimise" affordance of the dialog's own close/back action.
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.content_desc_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(20.dp)
            )
        }
    }
}


// Identifies a genuine "screen" for the full-screen crossfade.
data class ExplorerContext(
    val view: String,
    val categoryTitle: String?,
    val path: String
)

private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFormatter = SimpleDateFormat("d MMMM", Locale.getDefault())

fun formatItemDate(timestamp: Long): String {
    val date = Date(timestamp)
    val now = Calendar.getInstance()
    val itemDate = Calendar.getInstance().apply { time = date }

    return if (now.get(Calendar.YEAR) == itemDate.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == itemDate.get(Calendar.DAY_OF_YEAR)) {
        timeFormatter.format(date)
    } else {
        dateFormatter.format(date)
    }
}


fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var s = size.toDouble()
    var unitIndex = 0
    while (s >= 1024 && unitIndex < units.size - 1) {
        s /= 1024
        unitIndex++
    }
    return "%.1f %s".format(s, units[unitIndex])
}

private sealed class ListItemType {
    data class Header(val title: String, val count: Int) : ListItemType()
    data class File(val fileItem: FileItem) : ListItemType()
}

@Composable
private fun DateHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(8.dp))
        VerticalDivider(
            modifier = Modifier.height(14.dp),
            thickness = 1.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.items_count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


private fun formatDateHeader(context: Context, timestamp: Long): String {
    val now = Calendar.getInstance()
    val itemDate = Calendar.getInstance().apply { timeInMillis = timestamp }

    val nowCalendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val itemCalendar = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val actualDiffDays = ((nowCalendar.timeInMillis - itemCalendar.timeInMillis) / (24 * 60 * 60 * 1000)).toInt()

    return when {
        actualDiffDays == 0 -> context.getString(R.string.date_today)
        actualDiffDays == 1 -> context.getString(R.string.date_yesterday)
        actualDiffDays in 2..3 -> context.getString(R.string.date_days_ago, actualDiffDays)
        else -> SimpleDateFormat("dd-MM-yy", Locale.getDefault()).format(Date(timestamp))
    }
}


@Composable
fun RestrictedFolderView(
    path: String,
    isShizukuAuthorized: Boolean = false,
    onGrantShizuku: () -> Unit,
    onGrantSaf: () -> Unit,
    title: String = stringResource(R.string.restricted_folder_default_title),
    description: String = stringResource(R.string.restricted_folder_default_desc),
    onRefresh: (() -> Unit)? = null
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                val isSystemRestricted = path.contains("/Android/data") || path.contains("/Android/obb")

                if (isSystemRestricted) {
                    val isShizukuRunning = ShizukuManager.isAvailable()
                    Button(
                        onClick = onGrantShizuku,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isShizukuRunning) Icons.Outlined.VerifiedUser else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                if (isShizukuRunning) stringResource(R.string.action_grant_shizuku) else "Start Shizuku Service",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (!isShizukuRunning && onRefresh != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onRefresh) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Check Again", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = { uriHandler.openUri("https://shizuku.rikka.app/download/") }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_how_to_setup_shizuku), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Button(
                        onClick = onGrantSaf,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.FolderSpecial, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.action_grant_storage_access), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = onGrantShizuku, modifier = Modifier.alpha(0.7f)) {
                        Text(stringResource(R.string.action_use_shizuku_instead), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color
) {
    val annotatedString = remember(text) {
        buildAnnotatedString {
            val lines = text.split("\n")
            lines.forEachIndexed { index, line ->
                var l = line

                // Bullet points
                if (l.trim().startsWith("- ") || l.trim().startsWith("* ")) {
                    append(" • ")
                    l = l.trim().substring(2)
                }

                // Bold: **text**
                val boldRegex = Regex("\\*\\*(.*?)\\*\\*")
                var lastIdx = 0
                boldRegex.findAll(l).forEach { match ->
                    append(l.substring(lastIdx, match.range.first))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(match.groupValues[1])
                    }
                    lastIdx = match.range.last + 1
                }
                append(l.substring(lastIdx))

                if (index < lines.size - 1) append("\n")
            }
        }
    }
    Text(annotatedString, style = style, color = color)
}

@Composable
fun RemoteStorageEmptyView(
    path: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lower = path.lowercase()
    val (icon, color, title, desc) = when {
        lower.startsWith("mega://") -> Triple(
            Icons.Default.CloudSync,
            Color(0xFFD9272E),
            "Mega Cloud Storage"
        ) to "Connected to Mega account. This folder is empty."
        lower.startsWith("onedrive://") -> Triple(
            Icons.Default.Cloud,
            Color(0xFF0078D4),
            "Microsoft OneDrive"
        ) to "Connected to OneDrive account. This folder is empty."
        lower.startsWith("gdrive://") -> Triple(
            Icons.Default.Cloud,
            Color(0xFF0F9D58),
            "Google Drive"
        ) to "Connected to Google Drive. This folder is empty."
        lower.startsWith("dropbox://") -> Triple(
            Icons.Default.CloudQueue,
            Color(0xFF0061FF),
            "Dropbox Cloud Storage"
        ) to "Connected to Dropbox account. This folder is empty."
        lower.startsWith("smb://") -> Triple(
            Icons.Default.Dns,
            Color(0xFF0284C7),
            "Windows Share (SMB)"
        ) to "Connected to SMB server. This share is empty."
        lower.startsWith("ftp://") || lower.startsWith("ftps://") -> Triple(
            Icons.Default.SwapHoriz,
            Color(0xFFEA580C),
            "FTP Server"
        ) to "Connected to FTP server. Directory is empty."
        lower.startsWith("sftp://") -> Triple(
            Icons.Default.Terminal,
            Color(0xFF16A34A),
            "SFTP Server (SSH)"
        ) to "Connected to SFTP server. Directory is empty."
        lower.startsWith("webdav://") || lower.startsWith("webdavs://") -> Triple(
            Icons.Default.FolderShared,
            Color(0xFF7C3AED),
            "WebDAV Server"
        ) to "Connected to WebDAV server. Directory is empty."
        else -> Triple(
            Icons.Default.Cloud,
            MaterialTheme.colorScheme.primary,
            "Remote Storage"
        ) to "Connected to remote service. No files found."
    }.let { (triple, description) ->
        listOf(triple.first, triple.second, triple.third, description)
    }.let {
        @Suppress("UNCHECKED_CAST")
        Tuple4(it[0] as androidx.compose.ui.graphics.vector.ImageVector, it[1] as Color, it[2] as String, it[3] as String)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF22C55E).copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(Color(0xFF22C55E), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF15803D)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            FilledTonalButton(
                onClick = onRefresh,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
