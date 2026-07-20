package dev.narayan.rose

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
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
import dev.narayan.rose.filejob.FileJob
import dev.narayan.rose.filejob.FileJobType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import android.util.LruCache
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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

// ColorOS-style "swipe over checkboxes to multi-select" support: tracks the
// on-screen bounds of every currently-composed checkbox so a single
// continuous drag starting on one checkbox can toggle every other checkbox
// it passes over, all sharing the same select/deselect direction.
@Stable
internal class CheckboxDragSelectState {
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
    fromHome: Boolean = false,
    highlightFile: FileItem? = null, // New parameter
    isFromAllFiles: Boolean = false, // New parameter for seamless transition
    sharedTransitionScope: SharedTransitionScope? = null, // New parameter
    animatedVisibilityScope: AnimatedVisibilityScope? = null, // New parameter
    onExitToHome: (() -> Unit)? = null,
    onFileClick: (FileItem) -> Unit = {},
    onShareClick: (List<FileItem>) -> Unit = {},
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
) {
    val context = LocalContext.current

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<FileItem?>(null) }
    var showExtractionDialog by remember { mutableStateOf<FileItem?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentView by remember(startPath, startCategory, startRecent) {
        mutableStateOf(
            when {
                startCategory != null -> "Category"
                startRecent -> "Recent"
                else -> "Files"
            }
        )
    }

    // Screens: "Main", "Settings", "About"
    var activeScreen by remember { mutableStateOf("Main") }

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
                        if (type != null && title != null) viewModel.browseCategory(type, title)
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

    LaunchedEffect(startPath, startCategory, startRecent) {
        if (highlightFile != null) {
            viewModel.highlightedFile = highlightFile
        }
        when {
            startCategory != null -> viewModel.browseCategory(startCategory.first, startCategory.second)
            startRecent -> viewModel.loadRecentFiles()
            startPath != null -> {
                if (SafManager.isSafUri(startPath)) {
                    viewModel.loadFiles(startPath)
                } else {
                    val file = File(startPath)
                    val archiveExtensions = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
                    val isArchive = file.extension.lowercase() in archiveExtensions ||
                            file.name.lowercase().let { name -> archiveExtensions.any { name.endsWith(".$it") } }

                    if (file.isFile && isArchive) {
                        viewModel.openArchive(file)
                    } else if (file.isDirectory || startPath != viewModel.currentPath || viewModel.files.isEmpty()) {
                        viewModel.loadFiles(startPath)
                    }
                }
            }
        }
    }

    BackHandler(enabled = true) {
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
            if (fromHome || currentView == "Recent" || currentView == "Category") {
                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
            }
        } else if (viewModel.currentZipFile != null) {
            // If we came from Home/Recent and just opened this zip, go back to Home/Recent
            // instead of the zip's parent folder.
            if (viewModel.currentZipEntryPath.isNotEmpty()) {
                viewModel.navigateZipUp()
            } else if (fromHome && viewModel.currentZipFile?.absolutePath == startPath) {
                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
            } else {
                viewModel.loadFiles(viewModel.currentPath)
            }
        } else if (currentView == "Category") {
            if (viewModel.categoryBucketId != null) {
                viewModel.browseCategory(FileType.IMAGE, "Photos")
            } else {
                viewModel.exitCategoryMode()
                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
            }
        } else if (currentView == "Recent") {
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

    val displayedFiles = if (currentView == "Recent") {
        viewModel.recentFiles
    } else if (currentView == "Category") {
        viewModel.categoryFiles
    } else if (searchQuery.isBlank()) {
        viewModel.files
    } else {
        viewModel.searchResults
    }

    // With the full-screen crossfade no longer keyed on `path`, the LazyColumn/LazyVerticalGrid
    // call sites persist across folder navigation, so their scroll state persists too unless we
    // reset it ourselves. Key this on whatever actually identifies "a different list of files":
    // the path for "Files", the category title for "Category", and a constant for "Recent".
    val scrollResetKey = when (currentView) {
        "Files" -> "${viewModel.currentPath}_${viewModel.sortBy}_${viewModel.sortOrder}"
        "Category" -> "${viewModel.categoryTitle ?: ""}_${viewModel.categoryBucketId ?: ""}_${viewModel.sortBy}_${viewModel.sortOrder}"
        else -> "${currentView}_${viewModel.sortBy}_${viewModel.sortOrder}"
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
    var pendingScrollReset by remember { mutableStateOf(false) }
    LaunchedEffect(scrollResetKey) {
        pendingScrollReset = true
    }
    LaunchedEffect(displayedFiles, pendingScrollReset) {
        if (pendingScrollReset && displayedFiles.isNotEmpty()) {
            // Material Files style: check if we have a saved scroll position for
            // this path (e.g. from navigating back). If so, restore it; otherwise
            // start at the top for a new folder/sort.
            val savedPos = if (currentView == "Files") viewModel.getScrollPosition(viewModel.currentPath) else null
            if (savedPos != null) {
                listState.scrollToItem(savedPos.first, savedPos.second)
                gridState.scrollToItem(savedPos.first, savedPos.second)
            } else {
                listState.scrollToItem(0)
                gridState.scrollToItem(0)
            }
            pendingScrollReset = false
        }
    }

    val currentIsGridView = if (currentView == "Category") viewModel.isCategoryGridView else viewModel.isGridView

    // Continuously save the current scroll position for the active path.
    // To avoid saving the OLD path's scroll position into the NEW path's
    // slot immediately after navigation, we track the 'active' path separately
    // and only update it once the corresponding list has actually arrived.
    var pathForSaving by remember { mutableStateOf(viewModel.currentPath) }
    LaunchedEffect(displayedFiles, viewModel.isLoading) {
        if (!viewModel.isLoading && displayedFiles.isNotEmpty() && currentView == "Files") {
            pathForSaving = viewModel.currentPath
        }
    }

    LaunchedEffect(listState, gridState, pathForSaving, currentView, currentIsGridView) {
        if (currentView == "Files" && pathForSaving.isNotEmpty()) {
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
                if (pos != null) {
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
    val skipEntranceAnimation = currentView == "Recent"

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

    LaunchedEffect(viewModel.highlightedFile, displayedFiles) {
        val highlighted = viewModel.highlightedFile
        if (highlighted != null && displayedFiles.isNotEmpty()) {
            val index = displayedFiles.indexOfFirst { it.file.absolutePath == highlighted.file.absolutePath }
            if (index != -1) {
                // Slight delay to ensure list is settled for smoother animation
                kotlinx.coroutines.delay(100)
                listState.animateScrollToItem(index)
                // Clear highlight after 3 seconds
                kotlinx.coroutines.delay(3000)
                viewModel.highlightedFile = null
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
                    onBack = { activeScreen = "Main" }
                )
            }
            else -> {
                Scaffold(
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
                                        SelectionTopBar(viewModel)
                                    }
                                    "Search" -> {
                                        SearchTopBar(searchQuery, onQueryChange = {
                                            searchQuery = it
                                            viewModel.searchFiles(it)
                                        }, onBack = {
                                            isSearching = false
                                            searchQuery = ""
                                            viewModel.searchFiles("")
                                        })
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
                                                if (fromHome) {
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                }
                                            } else if (viewModel.currentZipFile != null) {
                                                if (viewModel.currentZipEntryPath.isNotEmpty()) {
                                                    viewModel.navigateZipUp()
                                                } else if (fromHome && viewModel.currentZipFile?.absolutePath == startPath) {
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                } else {
                                                    viewModel.loadFiles(viewModel.currentPath)
                                                }
                                            } else if (currentView == "Category") {
                                                if (viewModel.categoryBucketId != null) {
                                                    viewModel.browseCategory(FileType.IMAGE, "Photos")
                                                } else {
                                                    viewModel.exitCategoryMode()
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                }
                                            } else if (currentView == "Recent") {
                                                onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                            } else {
                                                if (!viewModel.navigateUp()) {
                                                    onExitToHome?.invoke() ?: (context as? android.app.Activity)?.finish()
                                                }
                                            }
                                        }

                                        MainTopBar(
                                            title = when (currentView) {
                                                "Recent" -> "Recent"
                                                "Category" -> viewModel.categoryTitle ?: "Category"
                                                else -> "All Files"
                                            },
                                            path = if (currentView == "Files") viewModel.currentPath else null,
                                            archiveName = viewModel.currentZipFile?.name?.let { zipName ->
                                                val subPath = viewModel.currentZipEntryPath.trimEnd('/')
                                                if (subPath.isEmpty()) zipName else "$zipName/$subPath"
                                            },
                                            viewModel = viewModel,
                                            currentView = currentView,
                                            sharedTransitionScope = sharedTransitionScope,
                                            animatedVisibilityScope = animatedVisibilityScope,
                                            onSearchClick = { isSearching = true },
                                            onRefreshClick = {
                                                when (currentView) {
                                                    "Recent" -> viewModel.loadRecentFiles()
                                                    "Category" -> {
                                                        val type = viewModel.categoryFilterType
                                                        val title = viewModel.categoryTitle
                                                        if (type != null && title != null) viewModel.browseCategory(type, title)
                                                    }
                                                    else -> viewModel.loadFiles(viewModel.currentPath)
                                                }
                                            },
                                            onNewFolderClick = { showCreateFolderDialog = true },
                                            onSettingsClick = { activeScreen = "Settings" },
                                            onNavigate = { viewModel.navigateTo(it) },
                                            onBack = { handleBack() }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    bottomBar = {
                        AnimatedVisibility(
                            visible = viewModel.isSelectionMode,
                            enter = slideInVertically(animationSpec = tween(400, easing = FastOutSlowInEasing)) { it },
                            exit = slideOutVertically(animationSpec = tween(400, easing = FastOutSlowInEasing)) { it }
                        ) {
                                SelectionBottomBar(
                                viewModel = viewModel,
                                currentView = currentView,
                                onShare = { onShareClick(viewModel.selectedFiles.toList()) },
                                onDeleteClick = {
                                    if (viewModel.confirmBeforeDelete) {
                                        pendingDelete = PendingDelete.Selection(viewModel.selectedFiles.size)
                                    } else {
                                        viewModel.deleteSelected()
                                    }
                                },
                                onCompressClick = { showCompressDialog = true },
                                onRenameClick = {
                                    showRenameDialog = viewModel.selectedFiles.firstOrNull()
                                },
                                onExtractClick = {
                                    showExtractionDialog = viewModel.selectedFiles.firstOrNull()
                                },
                                isAllSelected = viewModel.selectedFiles.size == displayedFiles.size && displayedFiles.isNotEmpty(),
                                onSelectAllClick = {
                                    if (viewModel.selectedFiles.size == displayedFiles.size) {
                                        viewModel.exitSelectionMode()
                                    } else {
                                        viewModel.selectAll(displayedFiles)
                                    }
                                }
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
                                                contentDescription = "Cancel",
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
                                                .clickable {
                                                    if (currentView == "Category" || currentView == "Recent") {
                                                        Toast.makeText(context, "Cannot extract in ${currentView.lowercase()} view. Open a folder first.", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        viewModel.extractArchive(viewModel.extractionSource!!, File(viewModel.currentPath))
                                                    }
                                                }
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
                                                "Extract Here",
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
                                                contentDescription = "Cancel",
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
                                                .clickable {
                                                    if (currentView == "Category" || currentView == "Recent") {
                                                        Toast.makeText(context, "Cannot paste in ${currentView.lowercase()} view. Open a folder first.", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        viewModel.pasteFiles()
                                                    }
                                                }
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
                                                if (viewModel.isCopyOperation) "Paste Here" else "Move Here",
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
                    PullToRefreshBox(
                        isRefreshing = viewModel.isRefreshing,
                        onRefresh = {
                            when {
                                currentView == "Recent" -> viewModel.loadRecentFiles()
                                currentView == "Category" -> {
                                    val type = viewModel.categoryFilterType
                                    val title = viewModel.categoryTitle
                                    if (type != null && title != null) viewModel.browseCategory(type, title)
                                }
                                viewModel.currentZipFile != null -> viewModel.openArchive(viewModel.currentZipFile!!, viewModel.currentZipEntryPath)
                                else -> viewModel.loadFiles(viewModel.currentPath, isManualRefresh = true)
                            }
                        },
                        modifier = Modifier.padding(paddingValues).fillMaxSize()
                    ) {
                        Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { coords ->
                            checkboxDragSelectState.containerBounds = coords.boundsInWindow()
                        }.then(
                            if (viewModel.isSelectionMode) {
                                Modifier.pointerInput(checkboxDragSelectState, displayedFiles) {
                                    detectCheckboxDragSelect(
                                        dragSelectState = checkboxDragSelectState,
                                        displayedFiles = displayedFiles,
                                        viewModel = viewModel
                                    )
                                }
                            } else Modifier
                        )) {
                            val isRestricted = (viewModel.currentPath.contains("/Android/data") || viewModel.currentPath.contains("/Android/obb"))
                            val noSafPermission = isRestricted && viewModel.files.isEmpty() && !SafManager.hasPermission(LocalContext.current, viewModel.currentPath)

                            if (noSafPermission && !viewModel.isLoading) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Lock,
                                            contentDescription = null,
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            "Access Restricted",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "Android 11+ restricts access to this folder. You can browse it using the system file manager.",
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(24.dp))
                                        Button(
                                            onClick = {
                                                viewModel.retrySaf(viewModel.currentPath)
                                            },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text("Grant Permission")
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        TextButton(
                                            onClick = {
                                                val docId = if (viewModel.currentPath.contains("Android/data")) "primary:Android/data" else "primary:Android/obb"
                                                val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "vnd.android.document/directory")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    // Try to open in DocumentsUI
                                                    val packageInfos = context.packageManager.queryIntentActivities(this, 0)
                                                    packageInfos.find { it.activityInfo.packageName.endsWith(".documentsui") }?.let {
                                                        setPackage(it.activityInfo.packageName)
                                                    }
                                                }
                                                try {
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "System viewer not found", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        ) {
                                            Text("Open System Viewer")
                                        }
                                    }
                                }
                            } else {
                                // Material Files style: Central loading indicator that appears after a short delay
                                // if the folder/category scan is taking a moment.
                                //
                                // isContentLoading is computed once per recomposition (not re-derived
                                // separately inside the effect) and reused below to gate the list/grid
                                // itself. Previously the list branch only checked `!viewModel.isLoading`
                                // for the EMPTY-state placeholder but still fell through to rendering a
                                // real (zero-item) LazyColumn/LazyVerticalGrid while a category was still
                                // loading - the crossfade had nothing to show yet, the spinner above was
                                // still inside its 150ms debounce, and the combination painted a fully
                                // blank frame. Compose only repainted once something unrelated (a touch
                                // or scroll) forced another frame, by which point the data had already
                                // arrived - exactly the "blank until I touch the screen" symptom. Category
                                // hit this far more often than folder/archive browsing because a MediaStore
                                // query is frequently fast enough to race the debounce.
                                val isContentLoading = (viewModel.isLoading || viewModel.isRefreshing || viewModel.isRecursiveSearching) && displayedFiles.isEmpty()

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
                                        val displayedFilesFinal = if (state.view == "Recent") {
                                            viewModel.recentFiles
                                        } else if (state.view == "Category") {
                                            viewModel.categoryFiles
                                        } else if (searchQuery.isBlank()) {
                                            viewModel.files
                                        } else {
                                            viewModel.searchResults
                                        }

                                        if (displayedFilesFinal.isEmpty() && !viewModel.isLoading && !viewModel.isRecursiveSearching) {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(if (searchQuery.isEmpty()) "No files found" else "No results for \"$searchQuery\"")
                                            }
                                        } else if (if (currentView == "Category") viewModel.isCategoryGridView else viewModel.isGridView) {
                                            LazyVerticalGrid(
                                                columns = GridCells.Adaptive(minSize = viewModel.gridItemSize.cellMinSize),
                                                state = gridState,
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = PaddingValues(8.dp)
                                            ) {
                                                items(displayedFilesFinal, key = { it.file.absolutePath }, contentType = { "grid_item" }) { fileItem ->
                                                    val index = displayedFilesFinal.indexOf(fileItem)
                                                    FileGridItem(
                                                        fileItem = fileItem,
                                                        isSelected = viewModel.selectedFiles.contains(fileItem),
                                                        showDetails = viewModel.showDetails,
                                                        showExtension = viewModel.showFileExtensions,
                                                        iconSize = viewModel.gridItemSize.iconSize,
                                                        isVirtual = viewModel.currentZipFile != null,
                                                        index = index, // Pass index for staggered animation
                                                        scrollResetKey = scrollResetKey, // Pass key to restart animation on path change
                                                        hasAnimatedBefore = skipEntranceAnimation || animatedItemKeys.contains(fileItem.file.absolutePath),
                                                        onAnimationStart = { animatedItemKeys.add(fileItem.file.absolutePath) },
                                                        modifier = if (skipEntranceAnimation) Modifier else Modifier.animateItem(),
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
                                                                            viewModel.navigateTo(fileItem.file)
                                                                        }
                                                                        currentView = "Files"
                                                                    }
                                                                } else if (fileItem.fileType == FileType.ZIP) {
                                                                    if (isSearching) {
                                                                        isSearching = false
                                                                        searchQuery = ""
                                                                        viewModel.searchFiles("")
                                                                    }
                                                                    viewModel.openArchive(fileItem.file)
                                                                    currentView = "Files"
                                                                } else {
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
                                        } else {
                                            LazyColumn(
                                                modifier = Modifier.fillMaxSize(),
                                                state = listState,
                                                contentPadding = PaddingValues(vertical = 8.dp)
                                            ) {
                                                item(key = "top_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
                                                itemsIndexed(displayedFilesFinal, key = { _, item -> item.file.absolutePath }, contentType = { _, _ -> "list_item" }) { index, fileItem ->
                                                    val isSelected = viewModel.selectedFiles.contains(fileItem)
                                                    val isHighlighted = viewModel.highlightedFile?.file?.absolutePath == fileItem.file.absolutePath
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
                                                        modifier = if (skipEntranceAnimation) Modifier else Modifier.animateItem(),
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
                                                                            viewModel.navigateTo(fileItem.file)
                                                                        }
                                                                        currentView = "Files"
                                                                    }
                                                                } else if (fileItem.fileType == FileType.ZIP) {
                                                                    if (isSearching) {
                                                                        isSearching = false
                                                                        searchQuery = ""
                                                                        viewModel.searchFiles("")
                                                                    }
                                                                    viewModel.openArchive(fileItem.file)
                                                                    currentView = "Files"
                                                                } else {
                                                                    onFileClick(fileItem)
                                                                }
                                                            }
                                                        },
                                                        onLongClick = {
                                                            viewModel.toggleSelection(fileItem)
                                                        },
                                                        onDelete = {
                                                            if (viewModel.confirmBeforeDelete) {
                                                                pendingDelete = PendingDelete.Single(fileItem)
                                                            } else {
                                                                viewModel.deleteFile(fileItem)
                                                            }
                                                        },
                                                        onRename = { newName -> viewModel.renameFile(fileItem, newName) },
                                                        onRenameRequest = { showRenameDialog = fileItem },
                                                        onShare = { onShareClick(listOf(fileItem)) },
                                                        onCopy = { viewModel.toggleSelection(fileItem); viewModel.copySelected() },
                                                        onCut = { viewModel.toggleSelection(fileItem); viewModel.moveSelected() },
                                                        onOpenLocation = if (searchQuery.isNotEmpty()) {
                                                            {
                                                                val parent = fileItem.file.parentFile
                                                                if (parent != null) {
                                                                    isSearching = false
                                                                    searchQuery = ""
                                                                    viewModel.loadFiles(parent.absolutePath)
                                                                    viewModel.highlightedFile = fileItem
                                                                }
                                                            }
                                                        } else null,
                                                        onExtract = { showExtractionDialog = fileItem },
                                                        onProperties = { viewModel.showProperties(fileItem) },
                                                        onPaste = { viewModel.navigateTo(fileItem.file); viewModel.pasteFiles() },
                                                        viewModel = viewModel,
                                                        dragSelectState = checkboxDragSelectState,
                                                        isDividerVisible = viewModel.showListDividers && index != displayedFilesFinal.lastIndex && !isSelected && !viewModel.selectedFiles.contains(displayedFilesFinal.getOrNull(index + 1))
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Background Jobs Progress Bar
                                val activeJobs by viewModel.activeJobs.collectAsState()
                                if (activeJobs.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(16.dp)
                                    ) {
                                        ActiveJobsCard(activeJobs = activeJobs.values.toList())
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
                                            viewModel.extractArchive(fileItem.file)
                                            showExtractionDialog = null
                                            viewModel.exitSelectionMode()
                                        },
                                        onSelectLocation = {
                                            viewModel.prepareExtraction(fileItem.file)
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
            onDismiss = { showCompressDialog = false },
            onConfirm = { name ->
                viewModel.compressSelected(name)
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
        DeleteConfirmationDialog(
            pending = pending,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                when (pending) {
                    is PendingDelete.Single -> viewModel.deleteFile(pending.fileItem)
                    is PendingDelete.Selection -> viewModel.deleteSelected()
                }
                pendingDelete = null
            }
        )
    }
}

sealed class PendingDelete {
    data class Single(val fileItem: FileItem) : PendingDelete()
    data class Selection(val count: Int) : PendingDelete()
}

@Composable
fun DeleteConfirmationDialog(
    pending: PendingDelete,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Delete?")
            }
        },
        text = {
            Text(
                when (pending) {
                    is PendingDelete.Single -> "Delete \"${pending.fileItem.name}\"? This can't be undone."
                    is PendingDelete.Selection -> "Delete ${pending.count} selected item(s)? This can't be undone."
                }
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: RoseViewModel,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Appearance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Text(
                "Theme",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeModeChip("System", viewModel.themeMode == ThemeMode.SYSTEM) { viewModel.setThemeMode(ThemeMode.SYSTEM) }
                ThemeModeChip("Light", viewModel.themeMode == ThemeMode.LIGHT) { viewModel.setThemeMode(ThemeMode.LIGHT) }
                ThemeModeChip("Dark", viewModel.themeMode == ThemeMode.DARK) { viewModel.setThemeMode(ThemeMode.DARK) }
            }

            SettingsSwitchRow(
                title = "Black AMOLED mode",
                subtitle = "Use true black backgrounds while in dark mode",
                checked = viewModel.amoledMode,
                onCheckedChange = { viewModel.setAmoledMode(it) }
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingsSwitchRow(
                    title = "Dynamic color",
                    subtitle = "Use colors generated from your wallpaper (Android 12+)",
                    checked = viewModel.dynamicColorEnabled,
                    onCheckedChange = { viewModel.setDynamicColor(it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                "Browsing",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            SettingsSwitchRow(
                title = "Grid view",
                subtitle = "Show files as a grid instead of a list",
                checked = viewModel.isGridView,
                onCheckedChange = { viewModel.setGridView(it) }
            )
            if (viewModel.isGridView) {
                Text(
                    "Grid item size",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeModeChip("Small", viewModel.gridItemSize == GridItemSize.SMALL) { viewModel.setGridItemSize(GridItemSize.SMALL) }
                    ThemeModeChip("Medium", viewModel.gridItemSize == GridItemSize.MEDIUM) { viewModel.setGridItemSize(GridItemSize.MEDIUM) }
                    ThemeModeChip("Large", viewModel.gridItemSize == GridItemSize.LARGE) { viewModel.setGridItemSize(GridItemSize.LARGE) }
                }
            }
            SettingsSwitchRow(
                title = "Folders first",
                subtitle = "Always list folders before files",
                checked = viewModel.foldersFirst,
                onCheckedChange = { viewModel.setFoldersFirst(it) }
            )
            SettingsSwitchRow(
                title = "Show file details",
                subtitle = "Show size and modified date under each file",
                checked = viewModel.showDetails,
                onCheckedChange = { viewModel.setShowDetails(it) }
            )
            SettingsSwitchRow(
                title = "Show file extensions",
                subtitle = "Show \".mp4\", \".pdf\" etc. after the file name",
                checked = viewModel.showFileExtensions,
                onCheckedChange = { viewModel.setShowFileExtensions(it) }
            )
            SettingsSwitchRow(
                title = "Show list dividers",
                subtitle = "Show thin divider lines between items in Recent files, Quick access, and folder lists",
                checked = viewModel.showListDividers,
                onCheckedChange = { viewModel.setShowListDividers(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                "Navigation",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Text(
                "Start page",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeModeChip("Landing page", viewModel.startPage == StartPage.HOME) { viewModel.setStartPage(StartPage.HOME) }
                ThemeModeChip("All files", viewModel.startPage == StartPage.ALL_FILES) { viewModel.setStartPage(StartPage.ALL_FILES) }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                "Safety",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            SettingsSwitchRow(
                title = "Confirm before delete",
                subtitle = "Ask for confirmation before deleting files or folders",
                checked = viewModel.confirmBeforeDelete,
                onCheckedChange = { viewModel.setConfirmBeforeDelete(it) }
            )
            SettingsSwitchRow(
                title = "Use Recycle Bin",
                subtitle = "Deleted files are moved to Recycle Bin and kept for 30 days",
                checked = viewModel.useRecycleBin,
                onCheckedChange = { viewModel.setUseRecycleBin(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                "Home Screen",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            SettingsSwitchRow(
                title = "Show Quick Access",
                subtitle = "Show the Quick Access section on landing page",
                checked = viewModel.showQuickAccess,
                onCheckedChange = { viewModel.setShowQuickAccess(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                "Advanced",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            SettingsSwitchRow(
                title = "Show hidden files",
                subtitle = "Show files and folders starting with a dot",
                checked = viewModel.showHiddenFiles,
                onCheckedChange = { viewModel.toggleHiddenFiles() }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                "Support",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://github.com/NarayanChetri/ROSE/issues") }
                    .padding(vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Report an issue", fontWeight = FontWeight.Medium)
                    Text("Found a bug? Let us know on GitHub", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.BugReport, null, tint = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(32.dp))

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                "Storage",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                context.imageLoader.memoryCache?.clear()
                                context.imageLoader.diskCache?.clear()
                            }
                            Toast.makeText(context, "Thumbnail cache cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Clear thumbnail cache", fontWeight = FontWeight.Medium)
                    Text(
                        "Free up space used by cached image and video thumbnails",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Default.CleaningServices, null, tint = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun ThemeModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_logo),
                                contentDescription = null,
                                modifier = Modifier.padding(8.dp),
                                tint = Color.Unspecified
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("ROSE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Reliable Open-Source Explorer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "ROSE is a modern, privacy-focused file manager built with Jetpack Compose. It aims to provide a fast and beautiful experience while giving you full control over your files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    AboutItem(Icons.Default.Info, "Version", "1.0.0 (1)")
                    AboutItem(Icons.Default.Code, "View on GitHub", null) {
                        uriHandler.openUri("https://github.com/NarayanChetri/ROSE")
                    }
                    AboutItem(Icons.Default.Description, "Licenses", null)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Author", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))

                    AboutItem(Icons.Default.Person, "Developer", "Narayan Chetri")
                    AboutItem(Icons.Default.Code, "Follow on GitHub", null) {
                        uriHandler.openUri("https://github.com/NarayanChetri/")
                    }
                    AboutItem(Icons.Default.Email, "Contact", "Narayanchetri.dev@gmail.com") {
                        uriHandler.openUri("mailto:Narayanchetri.dev@gmail.com")
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
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(24.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onSearchClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onNewFolderClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNavigate: (File) -> Unit,
    onBack: () -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Column {
        TopAppBar(
            title = {
                val isAlbum = viewModel.categoryBucketId != null
                if (title == "All Files" && sharedTransitionScope != null && animatedVisibilityScope != null) {
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
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                            if (currentView != "Category" && viewModel.currentZipFile == null) {
                                DropdownMenuItem(
                                    text = { Text("New Folder", modifier = Modifier.padding(vertical = 4.dp)) },
                                    onClick = { onNewFolderClick(); showMoreMenu = false },
                                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            }
                            DropdownMenuItem(
                                text = { Text("Show Hidden Files", modifier = Modifier.padding(vertical = 4.dp)) },
                                onClick = { viewModel.toggleHiddenFiles(); showMoreMenu = false },
                                leadingIcon = {
                                    Checkbox(
                                        checked = viewModel.showHiddenFiles,
                                        onCheckedChange = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text("Refresh", modifier = Modifier.padding(vertical = 4.dp)) },
                                onClick = { onRefreshClick(); showMoreMenu = false },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text("Settings", modifier = Modifier.padding(vertical = 4.dp)) },
                                onClick = { onSettingsClick(); showMoreMenu = false },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        SortMenu(expanded = showSortMenu, viewModel = viewModel, onDismiss = { showSortMenu = false })
                    }
                }
            }
        )
        if (path != null) {
            val breadcrumbPath = if (archiveName != null) "$path > [$archiveName]" else path
            Breadcrumbs(breadcrumbPath, onNavigate)
        }
    }
}

@Composable
fun Breadcrumbs(path: String, onNavigate: (File) -> Unit) {
    val rootPath = Environment.getExternalStorageDirectory().absolutePath
    val relativePath = if (path.startsWith(rootPath)) {
        "Internal shared storage" + path.removePrefix(rootPath)
    } else {
        path
    }

    val parts = relativePath.split("/").filter { it.isNotEmpty() }
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        parts.forEachIndexed { index, part ->
            Text(
                text = part,
                style = MaterialTheme.typography.bodyMedium,
                color = if (index == parts.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    val targetPath = if (relativePath.startsWith("Internal shared storage")) {
                        val subParts = parts.subList(1, index + 1)
                        if (subParts.isEmpty()) {
                            File(Environment.getExternalStorageDirectory().absolutePath)
                        } else {
                            File(Environment.getExternalStorageDirectory(), subParts.joinToString("/"))
                        }
                    } else {
                        File("/" + parts.subList(0, index + 1).joinToString("/"))
                    }
                    onNavigate(targetPath)
                }
            )
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
fun SelectionTopBar(viewModel: RoseViewModel) {
    TopAppBar(
        title = {
            Text(
                "${viewModel.selectedFiles.size} selected",
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
            // Select All removed from here as it is now in the "More" menu
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
    onSelectAllClick: () -> Unit
) {
    val context = LocalContext.current

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 16.dp
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionBottomBarItem(
                icon = Icons.Outlined.Share,
                label = "Share",
                onClick = onShare,
                modifier = Modifier.weight(1f)
            )
            SelectionBottomBarItem(
                icon = Icons.Outlined.ContentCopy,
                label = "Copy",
                onClick = { viewModel.copySelected() },
                modifier = Modifier.weight(1f)
            )
            SelectionBottomBarItem(
                icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                label = "Move",
                onClick = { viewModel.moveSelected() },
                modifier = Modifier.weight(1f)
            )
            SelectionBottomBarItem(
                icon = Icons.Outlined.Delete,
                label = "Delete",
                onClick = onDeleteClick,
                modifier = Modifier.weight(1f)
            )

            var showMoreMenu by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                SelectionBottomBarItem(
                    icon = Icons.Default.MoreVert,
                    label = "More",
                    onClick = { showMoreMenu = true },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isAllSelected) "Unselect All" else "Select All") },
                        onClick = {
                            onSelectAllClick()
                            showMoreMenu = false
                        },
                        leadingIcon = { Icon(if (isAllSelected) Icons.Default.Deselect else Icons.Default.SelectAll, null) }
                    )
                    if (currentView != "Recent") {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                        DropdownMenuItem(
                            text = { Text("Compress") },
                            onClick = {
                                onCompressClick()
                                showMoreMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Archive, null) }
                        )
                    }
                    if (viewModel.selectedFiles.size == 1) {
                        val fileItem = viewModel.selectedFiles.first()
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                onRenameClick()
                                showMoreMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, null) }
                        )
                        if (fileItem.isDirectory) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text("Add to Quick access") },
                                onClick = {
                                    viewModel.addQuickAccessFolder(fileItem.file.absolutePath)
                                    Toast.makeText(context, "Added to Quick access", Toast.LENGTH_SHORT).show()
                                    viewModel.exitSelectionMode()
                                    showMoreMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Add, null) }
                            )
                        }
                        if (fileItem.fileType == FileType.ZIP) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text("Extract") },
                                onClick = {
                                    onExtractClick()
                                    showMoreMenu = false
                                },
                                leadingIcon = { Icon(Icons.Outlined.Unarchive, null) }
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                        DropdownMenuItem(
                            text = { Text("Properties") },
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

@Composable
fun SelectionBottomBarItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
            color = tint
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
                placeholder = { Text("Search files...") },
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
                Icon(Icons.Default.ArrowBack, null)
            }
        }
    )
}

@Composable
fun SortMenu(expanded: Boolean, viewModel: RoseViewModel, onDismiss: () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
    ) {
        val isCategory = viewModel.categoryFilterType != null
        DropdownMenuItem(
            text = { Text("List", modifier = Modifier.padding(vertical = 4.dp)) },
            onClick = { if (isCategory) viewModel.setCategoryGridView(false) else viewModel.setGridView(false) },
            trailingIcon = { RadioButton(selected = !(if (isCategory) viewModel.isCategoryGridView else viewModel.isGridView), onClick = null) },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
        DropdownMenuItem(
            text = { Text("Grid", modifier = Modifier.padding(vertical = 4.dp)) },
            onClick = { if (isCategory) viewModel.setCategoryGridView(true) else viewModel.setGridView(true) },
            trailingIcon = { RadioButton(selected = if (isCategory) viewModel.isCategoryGridView else viewModel.isGridView, onClick = null) },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 2.dp)

        SortMenuItem("Name", RoseViewModel.SortBy.NAME, viewModel)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
        SortMenuItem("Type", RoseViewModel.SortBy.TYPE, viewModel)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
        SortMenuItem("Size", RoseViewModel.SortBy.SIZE, viewModel)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
        SortMenuItem("Last modified", RoseViewModel.SortBy.DATE, viewModel)

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 2.dp)

        DropdownMenuItem(
            text = { Text("Folders first", modifier = Modifier.padding(vertical = 4.dp)) },
            onClick = {
                viewModel.setFoldersFirst(!viewModel.foldersFirst)
            },
            trailingIcon = {
                Checkbox(
                    checked = viewModel.foldersFirst,
                    onCheckedChange = null
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
    onAnimationStart: () -> Unit = {}
) {
    // Material Files-style staggered entrance animation - plays once per item
    // per folder session, not on every re-entry into the viewport.
    // Animates translationY (slide up) and alpha (fade in) with a stagger based on index.
    val animatedProgress = remember(scrollResetKey, fileItem.file.absolutePath) {
        Animatable(if (hasAnimatedBefore) 1f else 0f)
    }
    val density = LocalDensity.current
    LaunchedEffect(scrollResetKey, fileItem.file.absolutePath) {
        if (!hasAnimatedBefore) {
            onAnimationStart()
            val delay = (index % 8 * 12).toLong() // Even faster stagger
            kotlinx.coroutines.delay(delay)
            animatedProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing)
            )
        }
    }

    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    val backgroundColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) selectionColor else Color.Transparent,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "GridItemSelection"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                alpha = animatedProgress.value
                translationY = with(density) { (1f - animatedProgress.value) * 40.dp.toPx() } // Slide up from 40dp
            }
            .padding(8.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(backgroundColor)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            FileIcon(fileItem, iconSize = iconSize, isVirtual = isVirtual, viewModel = (LocalContext.current as? androidx.activity.ComponentActivity)?.let { (it as? MainActivity)?.viewModel })
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
                    "${fileItem.itemCount ?: 0} items",
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
internal fun FileListItem(
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
    onOpenLocation: (() -> Unit)? = null, // New parameter
    onPaste: (() -> Unit)? = null,
    onExtract: (() -> Unit)? = null,
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
    val animatedProgress = remember(scrollResetKey, fileItem.file.absolutePath) {
        Animatable(if (hasAnimatedBefore) 1f else 0f)
    }
    val density = LocalDensity.current
    LaunchedEffect(scrollResetKey, fileItem.file.absolutePath) {
        if (!hasAnimatedBefore) {
            onAnimationStart()
            val delay = (index % 8 * 12).toLong() // Even faster stagger
            kotlinx.coroutines.delay(delay)
            animatedProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing)
            )
        }
    }

    val context = LocalContext.current

    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

    val backgroundColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            isSelected -> selectionColor
            isHighlighted -> highlightColor
            else -> Color.Transparent
        },
        animationSpec = if (isHighlighted) snap() else tween(300, easing = FastOutSlowInEasing),
        label = "ItemHighlight"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                alpha = animatedProgress.value
                translationY = with(density) { (1f - animatedProgress.value) * 40.dp.toPx() }
            }
    ) {
        ListItem(
            headlineContent = {
                Text(
                    displayNameFor(fileItem, showExtension),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (fileItem.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                    style = if (fileItem.isDirectory) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                )
            },
            supportingContent = {
                val dateStr = formatItemDate(fileItem.lastModified)
                if (fileItem.isDirectory) {
                    val count = fileItem.itemCount ?: 0
                    Text("$count items | $dateStr")
                } else if (showDetails) {
                    Text("${formatFileSize(fileItem.size)} | $dateStr")
                }
            },
            leadingContent = {
                Box(contentAlignment = Alignment.Center) {
                    FileIcon(fileItem, isVirtual = isVirtual, viewModel = viewModel)
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
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                } else {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = { showMenu = false; onShare() },
                                leadingIcon = { Icon(Icons.Default.Share, null) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = { showMenu = false; onRenameRequest() },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text("Copy") },
                                onClick = { showMenu = false; onCopy() },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text("Move") },
                                onClick = { showMenu = false; onCut() },
                                leadingIcon = { Icon(Icons.Default.ContentCut, null) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { showMenu = false; onDelete() },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(
                                text = { Text("Properties") },
                                onClick = { showMenu = false; onProperties() },
                                leadingIcon = { Icon(Icons.Default.Info, null) }
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .background(backgroundColor)
        )
        // The divider now lives inside the same animated Column as its item
        // (sharing animatedProgress via the Column's graphicsLayer above) instead
        // of being drawn separately by the caller. Dividers have no animation of
        // their own, so when they used to render outside this fade/slide wrapper
        // they'd appear instantly while the item above/below was still fading in
        // from Category's async data load - showing as a "blank list, dividers
        // only" flash. Tying it to the same layer makes that permanently
        // impossible: the divider can only ever be as visible as its item.
        if (isDividerVisible) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 68.dp, end = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
fun FileIcon(
    fileItem: FileItem,
    iconSize: Dp = 52.dp,
    isVirtual: Boolean = false,
    folderTint: Color = Color(0xFF70C1FF),
    viewModel: RoseViewModel? = null
) {
    val context = LocalContext.current
    val modifier = Modifier.size(iconSize).clip(RoundedCornerShape(12.dp))

    val jobs by (viewModel?.activeJobs?.collectAsState() ?: remember { mutableStateOf(emptyMap<String, FileJob>()) })

    // derivedStateOf so only the one icon whose own job actually changed
    // recomposes, instead of every visible icon re-running on every progress
    // tick of any unrelated download/copy/move elsewhere in the app.
    val activeJob by remember(fileItem.file.absolutePath) {
        derivedStateOf {
            jobs.values.find { job ->
                when (val type = job.type) {
                    is FileJobType.Download -> type.source.path == fileItem.file.absolutePath
                    is FileJobType.Copy -> type.sources.any { it.path == fileItem.file.absolutePath }
                    is FileJobType.Move -> type.sources.any { it.path == fileItem.file.absolutePath }
                    else -> false
                }
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (fileItem.fileType) {
            FileType.IMAGE, FileType.VIDEO -> {
                val placeholderIcon = if (fileItem.fileType == FileType.IMAGE) Icons.Default.Image else Icons.Default.Movie

                if (isVirtual && fileItem.virtualZipSource != null) {
                    val zipThumb by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = fileItem.file.absolutePath) {
                        value = withContext(Dispatchers.IO) {
                            try {
                                java.util.zip.ZipFile(fileItem.virtualZipSource).use { zip ->
                                    val entry = zip.getEntry(fileItem.zipEntryPath ?: fileItem.name)
                                    if (entry != null && entry.size < 10 * 1024 * 1024) { // Only for files < 10MB
                                        zip.getInputStream(entry).use { input ->
                                            android.graphics.BitmapFactory.decodeStream(input)
                                        }
                                    } else null
                                }
                            } catch (e: Exception) { null }
                        }
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
                            .videoFrameMillis(1000)
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
                        Icon(
                            Icons.Default.Folder,
                            null,
                            modifier = Modifier.fillMaxSize(),
                            tint = folderTint
                        )
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

        val job = activeJob
        if (job != null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (job.isIndeterminate) {
                        // Unknown total size (common for cloud sources like
                        // Google Drive until the transfer is underway) - a
                        // determinate ring bound to job.progress would just sit
                        // frozen at 0%, so show a real spinner instead.
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(iconSize * 0.6f)
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = job.progress,
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(iconSize * 0.6f)
                        )
                        Text(
                            "${(job.progress * 100).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
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
                Text("Properties", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PropertyRow("Name", fileItem.name)
                PropertyRow("Type", fileItem.fileType.name)
                PropertyRow("Size", if (fileItem.isDirectory) viewModel.folderSize?.let { formatFileSize(it) } ?: "Calculating..." else formatFileSize(fileItem.size))
                PropertyRow("Path", fileItem.file.absolutePath)
                PropertyRow("Modified", SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(Date(fileItem.lastModified)))
            }
        },
        confirmButton = {
            Button(
                onClick = { onDismiss() },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Close") }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun CompressDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var folderName by remember { mutableStateOf("Archive") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Compress to Zip")
            }
        },
        text = {
            TextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Zip file name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                suffix = { Text(".zip") }
            )
        },
        confirmButton = {
            Button(
                onClick = { if (folderName.isNotBlank()) onConfirm(folderName) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Compress") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
        title = { Text("Extract Archive") },
        text = {
            Column {
                Text(
                    "How would you like to extract \"${item.name}\"?",
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
                            Text("Extract here", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text("Extract to a new folder in the same directory", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text("Select location", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text("Choose a specific folder to extract to", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
fun CreateFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Create Folder")
            }
        },
        text = {
            TextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Folder Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { if (folderName.isNotBlank()) onCreate(folderName) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Rename")
            }
        },
        text = {
            TextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                label = { Text("New Name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = { if (textFieldValue.text.isNotBlank()) onRename(textFieldValue.text) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        shape = RoundedCornerShape(28.dp)
    )
}

fun displayNameFor(fileItem: FileItem, showExtension: Boolean): String {
    if (showExtension || fileItem.isDirectory) return fileItem.name
    val dotIndex = fileItem.name.lastIndexOf('.')
    return if (dotIndex > 0) fileItem.name.substring(0, dotIndex) else fileItem.name
}

@Composable
fun ActiveJobsCard(activeJobs: List<FileJob>) {
    val job = activeJobs.firstOrNull() ?: return
    Card(
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
                    is FileJobType.Copy -> "Copying files..."
                    is FileJobType.Move -> "Moving files..."
                    is FileJobType.Download -> "Saving offline..."
                    is FileJobType.Delete -> "Deleting files..."
                    is FileJobType.Recycle -> "Moving to Bin..."
                    else -> "Processing..."
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
                    if (bytesLabel != null) "Downloading… $bytesLabel" else "Downloading…"
                } else if (job.totalItems > 1) {
                    "${job.processedItems + 1} of ${job.totalItems} | ${(job.progress * 100).toInt()}%"
                } else {
                    "${(job.progress * 100).toInt()}% completed"
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