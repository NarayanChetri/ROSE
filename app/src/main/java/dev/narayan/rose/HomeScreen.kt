package dev.narayan.rose

import android.app.Application
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import coil.decode.VideoFrameDecoder
import coil.request.videoFrameMillis
import java.io.File

private data class HomeCategory(
    @StringRes val labelRes: Int,
    val type: FileType?,
    val icon: ImageVector,
    val color: Color,
    val isFavourites: Boolean = false,
    val isFtp: Boolean = false
)

private val page1Categories = listOf(
    HomeCategory(R.string.category_photos, FileType.IMAGE, Icons.Default.Image, Color(0xFF4C8DFF)),
    HomeCategory(R.string.category_videos, FileType.VIDEO, Icons.Default.VideoLibrary, Color(0xFF9C6ADE)),
    HomeCategory(R.string.category_audio, FileType.AUDIO, Icons.Default.MusicNote, Color(0xFFE0904C)),
    HomeCategory(R.string.category_documents, FileType.DOCUMENT, Icons.Default.Description, Color(0xFF4C9A72)),
    HomeCategory(R.string.category_apks, FileType.APK, Icons.Default.Android, Color(0xFF4CAF7D)),
    HomeCategory(R.string.category_archives, FileType.ZIP, Icons.Default.Archive, Color(0xFFC98A2E))
)

private val page2Categories = listOf(
    HomeCategory(
        labelRes = R.string.category_favourites,
        type = null,
        icon = Icons.Default.Favorite,
        color = Color(0xFFE11D48),
        isFavourites = true
    ),
    HomeCategory(
        labelRes = R.string.category_ftp_server,
        type = null,
        icon = Icons.Default.WifiTethering,
        color = Color(0xFF00897B),
        isFtp = true
    )
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    viewModel: RoseViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenPath: (String, FileItem?) -> Unit,
    onOpenQuickAccess: ((String) -> Unit)? = null,
    onOpenCategory: (FileType, String) -> Unit,
    onOpenRecent: () -> Unit,
    onOpenFavourites: () -> Unit = {},
    onOpenFtpServer: () -> Unit = {},
    onFileClick: (FileItem) -> Unit,
    onOpenWithClick: (FileItem) -> Unit,
    onShareClick: (FileItem) -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onRecycleBinClick: () -> Unit,
    onSearchActiveChange: (Boolean) -> Unit = {},
    canSwipeToRecent: Boolean = true
) {
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Refresh everything when coming back to home screen
        viewModel.loadStorageInfo()
        viewModel.loadCategoryCounts(force = false) // Don't force scan every time
        viewModel.loadRecentFiles()
        viewModel.loadStorageDevices()
        viewModel.preLoadRoot() // Pre-load All Files for smooth transition
    }

    // Saved continuously while Home is visible. Since homeListState is now
    // initialized in MainActivity, its position is preserved naturally even
    // after Home is disposed and re-added during navigation. We update the
    // VM state so it survives even more complex transitions.
    // Save scroll position - only when items are actually present and user has scrolled.
    // Hoisting listState in MainActivity already preserves the position during
    // normal navigation; this sync with VM is for even deeper persistence.
    LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                (listState.isScrollInProgress || listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)
            ) {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            } else null
        }.collect { pos ->
            if (pos != null) {
                viewModel.homeScrollIndex = pos.first
                viewModel.homeScrollOffset = pos.second
            }
        }
    }

    // Restore scroll on mount ONLY if the listState was actually reset to (0,0)
    // (e.g. by Activity recreation or navigation disposal) and we have a
    // non-zero saved position.
    LaunchedEffect(Unit) {
        if (viewModel.homeScrollIndex != 0 || viewModel.homeScrollOffset != 0) {
            val listStateWasReset = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            if (listStateWasReset) {
                // Material Files style: wait for the list to have enough items to
                // actually reach the saved index before scrolling. This prevents
                // "partial" scrolls that end up at the bottom of a half-loaded list.
                snapshotFlow { listState.layoutInfo.totalItemsCount }.collect { count ->
                    if (count > viewModel.homeScrollIndex) {
                        listState.scrollToItem(viewModel.homeScrollIndex, viewModel.homeScrollOffset)
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }
        }
    }


    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        viewModel.hasRunEntranceAnimation = true
    }

    // Animation only runs once per app launch
    val dividerScale by animateFloatAsState(
        targetValue = if (viewModel.hasRunDividerAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "DividerAnimation"
    )

    LaunchedEffect(Unit) {
        if (!viewModel.hasRunDividerAnimation) {
            // Delay slightly to start after screen transition
            kotlinx.coroutines.delay(100)
            viewModel.hasRunDividerAnimation = true
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(isSearching) {
        onSearchActiveChange(isSearching)
    }

    LaunchedEffect(Unit) {
        viewModel.exitCategoryMode()
        viewModel.resetFiles()
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.exitCategoryMode()
                viewModel.resetFiles()
                viewModel.loadStorageInfo()
                viewModel.loadCategoryCounts(force = true)
                viewModel.loadRecentFiles()
                viewModel.loadStorageDevices()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Silent refresh for categories, storage, and recent files while on landing page
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(10000) // Every 10 seconds
            if (!isSearching) {
                viewModel.loadRecentFiles()
                viewModel.loadCategoryCounts(force = true)
                viewModel.loadStorageInfo()
            }
        }
    }
    var searchQuery by remember { mutableStateOf("") }

    BackHandler(enabled = isSearching) {
        isSearching = false
        searchQuery = ""
        viewModel.searchFiles(query = "", filterType = null, bucketId = null, scopePath = null)
        viewModel.activeSearchFilter = SearchFilter.ALL
    }

    // Dialog states for recent file actions
    var fileForRename by remember { mutableStateOf<FileItem?>(null) }
    var fileForDelete by remember { mutableStateOf<FileItem?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(shadowElevation = 0.dp) {
                Column {
                    if (isSearching) {
                        SearchTopBar(
                            query = searchQuery,
                            onQueryChange = {
                                searchQuery = it
                                viewModel.searchFiles(query = it, filterType = null, bucketId = null, scopePath = null)
                            },
                            onBack = {
                                isSearching = false
                                searchQuery = ""
                                viewModel.searchFiles(query = "", filterType = null, bucketId = null, scopePath = null)
                                viewModel.activeSearchFilter = SearchFilter.ALL
                            }
                        )
                        SearchFilterChipsRow(
                            selectedFilter = viewModel.activeSearchFilter,
                            onFilterSelected = { viewModel.activeSearchFilter = it },
                            counts = viewModel.searchFilterCounts
                        )
                    } else {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_launcher_foreground_base),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { isSearching = true }) {
                                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.content_desc_search))
                                }
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.content_desc_more_options))
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        shape = RoundedCornerShape(16.dp),
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_settings), modifier = Modifier.padding(vertical = 4.dp)) },
                                            onClick = { showMenu = false; onSettingsClick() },
                                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_about), modifier = Modifier.padding(vertical = 4.dp)) },
                                            onClick = { showMenu = false; onAboutClick() },
                                            leadingIcon = { Icon(Icons.Default.Info, null) },
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .graphicsLayer(scaleX = dividerScale, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                }
            }
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp, end = 4.dp)
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
                                    .alpha(0.5f)
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
                                    .alpha(0.5f)
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        ) {
            if (isSearching && searchQuery.isNotEmpty()) {
                val currentSearchResults = viewModel.filteredSearchResults
                item(key = "search_spacer") { Spacer(modifier = Modifier.height(4.dp)) }

                if (currentSearchResults.isEmpty()) {
                    if (viewModel.isRecursiveSearching) {
                        item(key = "search_loading") {
                            SearchLoadingView(
                                query = searchQuery,
                                categoryName = if (viewModel.activeSearchFilter != SearchFilter.ALL) viewModel.activeSearchFilter.label else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 48.dp, bottom = 32.dp)
                            )
                        }
                    } else {
                        item(key = "no_results") {
                            SearchEmptyStateView(
                                query = searchQuery,
                                filter = viewModel.activeSearchFilter,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 48.dp, bottom = 32.dp)
                            )
                        }
                    }
                } else {
                    itemsIndexed(currentSearchResults, key = { _, item -> item.file.absolutePath }) { index, item ->
                        SearchResultItem(
                            item = item,
                            modifier = Modifier.animateItem(),
                            onClick = {
                                if (item.isDirectory || item.fileType == FileType.ZIP) {
                                    onOpenPath(item.file.absolutePath, null)
                                } else {
                                    onFileClick(item)
                                }
                            },
                            onOpenLocation = { onOpenPath(item.file.parent ?: item.file.absolutePath, item) },
                            onCopy = {
                                viewModel.copySingle(item)
                                coroutineScope.launch {
                                    kotlinx.coroutines.yield()
                                    onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null)
                                }
                                isSearching = false
                                searchQuery = ""
                                viewModel.searchFiles("")
                                viewModel.searchFiles(query = "", filterType = null, bucketId = null, scopePath = null)
                                viewModel.activeSearchFilter = SearchFilter.ALL
                            },
                            onCut = {
                                viewModel.moveSingle(item)
                                coroutineScope.launch {
                                    kotlinx.coroutines.yield()
                                    onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null)
                                }
                                isSearching = false
                                searchQuery = ""
                                viewModel.searchFiles("")
                                viewModel.searchFiles(query = "", filterType = null, bucketId = null, scopePath = null)
                                viewModel.activeSearchFilter = SearchFilter.ALL
                            },
                            onExtract = {
                                viewModel.prepareExtraction(item.file)
                                android.widget.Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onOpenArchive = { onOpenPath(item.file.absolutePath, null) },
                            viewModel = viewModel,
                            onShare = { onShareClick(item) },
                            onDelete = { fileForDelete = item },
                            onRename = { fileForRename = item },
                            onProperties = { viewModel.showProperties(item) }
                        )
                        if (viewModel.showListDividers && index != currentSearchResults.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp, end = 12.dp),
                                thickness = 0.6.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            } else {
                item(key = "storage_capsule") {
                    Spacer(modifier = Modifier.height(16.dp))
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 12 }
                    ) {
                        StorageCapsule(
                            viewModel = viewModel,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onClick = { onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null) }
                        )
                    }
                }

                item(key = "category_grid") {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(300, delayMillis = 50)) + slideInVertically(tween(300, delayMillis = 50)) { it / 12 }
                    ) {
                        CategoryGrid(
                            counts = viewModel.categoryCounts,
                            favouriteCount = viewModel.favouriteFiles.size,
                            isLoading = viewModel.isCategoryCountsLoading,
                            hasRunCountAnimation = viewModel.hasRunCategoryCountAnimation,
                            onAnimationFinished = { viewModel.hasRunCategoryCountAnimation = true },
                            onCategoryClick = { category ->
                                val now = System.currentTimeMillis()
                                if (now - viewModel.lastNavigationTimestamp < 500L || animatedVisibilityScope.transition.isRunning) {
                                    return@CategoryGrid
                                }
                                viewModel.hasRunCategoryCountAnimation = true
                                if (category.isFavourites) {
                                    onOpenFavourites()
                                } else if (category.isFtp) {
                                    onOpenFtpServer()
                                } else if (category.type != null) {
                                    onOpenCategory(category.type, context.getString(category.labelRes))
                                }
                            },
                            onOpenRecent = onOpenRecent,
                            canSwipeToRecent = canSwipeToRecent,
                            categoryPageIndex = viewModel.categoryPageIndex,
                            onCategoryPageChange = { viewModel.categoryPageIndex = it },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                }

                if (viewModel.showRecentFiles) {
                    item(key = "recent_files") {
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(tween(300, delayMillis = 100)) + slideInVertically(tween(300, delayMillis = 100)) { it / 12 }
                        ) {
                            RecentFilesSection(
                                viewModel = viewModel,
                                recentFiles = viewModel.recentFiles,
                                showDividers = viewModel.showListDividers,
                                onOpenRecent = onOpenRecent,
                                onOpenPath = onOpenPath,
                                onFileClick = onFileClick,
                                onOpenWithClick = onOpenWithClick,
                                onShareClick = onShareClick,
                                onRenameClick = { fileForRename = it },
                                onDeleteClick = { fileForDelete = it },
                                onPropertiesClick = { viewModel.showProperties(it) },
                                onExtractClick = {
                                    viewModel.prepareExtraction(it.file)
                                    android.widget.Toast.makeText(context, context.getString(R.string.toast_archive_ready_extract), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }

                item(key = "quick_access") {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(300, delayMillis = 130)) + slideInVertically(tween(300, delayMillis = 130)) { it / 12 }
                    ) {
                        QuickAccessSection(
                            viewModel = viewModel,
                            onOpenPath = { path ->
                                if (onOpenQuickAccess != null) {
                                    onOpenQuickAccess(path)
                                } else {
                                    onOpenPath(path, null)
                                }
                            }
                        )
                    }
                }

                if (viewModel.showExternalStorage) {
                    item(key = "external_storage") {
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(tween(300, delayMillis = 160)) + slideInVertically(tween(300, delayMillis = 160)) { it / 12 }
                        ) {
                            StorageSection(
                                viewModel = viewModel,
                                onOpenPath = { path -> onOpenPath(path, null) }
                            )
                        }
                    }
                }

                item(key = "recycle_bin") {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(300, delayMillis = 190)) + slideInVertically(tween(300, delayMillis = 190)) { it / 12 }
                    ) {
                        RecycleBinSection(onClick = onRecycleBinClick)
                    }
                }

            }
        }
    }

    // Dialogs
    viewModel.propertiesFile?.let { item ->
        PropertiesDialog(
            fileItem = item,
            onDismiss = { viewModel.closeProperties() },
            viewModel = viewModel,
            onOpenLocation = { targetPath, targetItem ->
                viewModel.closeProperties()
                onOpenPath(targetPath, targetItem)
            }
        )
    }

    fileForRename?.let { item ->
        RenameDialog(
            initialName = item.name,
            onDismiss = { fileForRename = null },
            onRename = { newName ->
                viewModel.renameFile(item, newName)
                fileForRename = null
            }
        )
    }

    fileForDelete?.let { item ->
        var permanently by remember { mutableStateOf(!viewModel.useRecycleBin) }
        AlertDialog(
            onDismissRequest = { fileForDelete = null },
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
                    val titleText = if (item.name.length <= 26) {
                        stringResource(R.string.delete_single_confirm, item.name)
                    } else if (item.isDirectory) {
                        stringResource(R.string.delete_folder_title)
                    } else {
                        stringResource(R.string.delete_file_title)
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (item.name.length > 26) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    val statusText = if (viewModel.useRecycleBin && !permanently) {
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
                    if (viewModel.useRecycleBin) {
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
                    onClick = {
                        viewModel.deleteFile(item, permanently)
                        fileForDelete = null
                    },
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
                    onClick = { fileForDelete = null },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

@Composable
private fun SearchResultItem(
    item: FileItem,
    viewModel: RoseViewModel,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onOpenLocation: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onProperties: () -> Unit,
    onExtract: () -> Unit = {},
    onOpenArchive: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    item.file.parent ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatFileSize(item.size)} | ${formatItemDate(item.lastModified)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        leadingContent = {
            Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                FileIcon(fileItem = item, viewModel = (androidx.compose.ui.platform.LocalContext.current as? androidx.activity.ComponentActivity)?.let { (it as? MainActivity)?.viewModel })
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, null)
                }
                WindowsFileDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    fileItem = item,
                    viewModel = viewModel,
                    isVirtual = false,
                    onCopy = onCopy,
                    onCut = onCut,
                    onDelete = onDelete,
                    onShare = onShare,
                    onProperties = onProperties,
                    onRenameRequest = onRename,
                    onOpenLocation = onOpenLocation,
                    onExtract = if (item.fileType == FileType.ZIP) { onExtract } else null,
                    offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun StorageCapsule(
    viewModel: RoseViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit
) {
    val storageInfo = viewModel.storageInfo

    val storageFraction = storageInfo?.usedFraction ?: 0f
    val targetBarColor = when {
        storageFraction >= 0.90f -> Color(0xFFF43F5E) // Rose / Coral Red (Critical)
        storageFraction >= 0.75f -> Color(0xFFF59E0B) // Warm Amber (Caution)
        else -> MaterialTheme.colorScheme.primary     // Calm Blue / Theme Accent (Healthy)
    }
    val barColor by animateColorAsState(
        targetValue = targetBarColor,
        animationSpec = tween(durationMillis = 600),
        label = "StorageHealthColor"
    )

    var animationStarted by remember { mutableStateOf(viewModel.hasRunStorageAnimation) }
    val animatedFraction by animateFloatAsState(
        targetValue = if (animationStarted) storageFraction else 0f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "StorageProgress"
    )

    LaunchedEffect(Unit) {
        if (!viewModel.hasRunStorageAnimation) {
            kotlinx.coroutines.delay(500) // Delay to start after entrance animation
            animationStarted = true
            viewModel.hasRunStorageAnimation = true
        }
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                with(sharedTransitionScope) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(key = "all_files_capsule"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
                    )
                }
            )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SdStorage, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    with(sharedTransitionScope) {
                        Text(
                            stringResource(R.string.category_all_files),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.sharedBounds(
                                rememberSharedContentState(key = "all_files_title"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (storageInfo != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatFileSize(storageInfo.usedBytes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.storage_used_of_total, formatFileSize(storageInfo.totalBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            } else {
                Box(modifier = Modifier.height(32.dp)) // Placeholder
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (animatedFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(barColor)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CategoryGrid(
    counts: Map<FileType, Int>,
    favouriteCount: Int,
    isLoading: Boolean,
    hasRunCountAnimation: Boolean,
    onAnimationFinished: () -> Unit,
    onCategoryClick: (HomeCategory) -> Unit,
    onOpenRecent: () -> Unit,
    canSwipeToRecent: Boolean = true,
    categoryPageIndex: Int = 0,
    onCategoryPageChange: (Int) -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    var animationStarted by remember(hasRunCountAnimation) { mutableStateOf(hasRunCountAnimation) }

    LaunchedEffect(hasRunCountAnimation) {
        if (!hasRunCountAnimation) {
            kotlinx.coroutines.delay(60)
            animationStarted = true
            kotlinx.coroutines.delay(1400)
            onAnimationFinished()
        }
    }

    val categoryPagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = categoryPageIndex,
        pageCount = { 2 }
    )
    val coroutineScope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop

    LaunchedEffect(categoryPagerState.settledPage) {
        onCategoryPageChange(categoryPagerState.settledPage)
    }

    LaunchedEffect(categoryPageIndex) {
        if (!categoryPagerState.isScrollInProgress && categoryPagerState.currentPage != categoryPageIndex) {
            categoryPagerState.scrollToPage(categoryPageIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                    val dragPointerId = down.id
                    var isGestureClaimed = false
                    var prevPositionX = down.position.x
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == dragPointerId } ?: break

                            if (!change.pressed) {
                                if (isGestureClaimed) {
                                    change.consume()
                                }
                                break
                            }

                            val currentX = change.position.x
                            val currentY = change.position.y
                            val totalDx = currentX - down.position.x
                            val totalDy = currentY - down.position.y

                            if (!isGestureClaimed) {
                                val totalDistanceSq = totalDx * totalDx + totalDy * totalDy
                                if (totalDistanceSq > touchSlop * touchSlop) {
                                    // Vertical dominant -> let LazyColumn scroll vertically
                                    if (kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)) {
                                        break
                                    }

                                    val currentPage = categoryPagerState.currentPage
                                    if (currentPage == 0) {
                                        if (totalDx > 0f) {
                                            // Swiping right on Page 0: user is swiping from Home to Recent files!
                                            // Do NOT consume. Leave unconsumed so outer HorizontalPager takes it natively!
                                            break
                                        } else {
                                            // Swiping left on Page 0: user is swiping from Categories to Favourites.
                                            settleJob?.cancel()
                                            isGestureClaimed = true
                                            prevPositionX = currentX
                                            change.consume()
                                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        }
                                    } else {
                                        // currentPage == 1 (Favourites)
                                        if (totalDx > 0f) {
                                            // Swiping right on Page 1: user is swiping back from Favourites to Categories.
                                            settleJob?.cancel()
                                            isGestureClaimed = true
                                            prevPositionX = currentX
                                            change.consume()
                                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        } else {
                                            // Swiping left on Page 1: end of pages, do nothing.
                                            break
                                        }
                                    }
                                }
                            } else {
                                val deltaX = currentX - prevPositionX
                                prevPositionX = currentX
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                                categoryPagerState.dispatchRawDelta(-deltaX)
                            }
                        }
                    } finally {
                        if (isGestureClaimed) {
                            val velocityX = velocityTracker.calculateVelocity().x
                            val currentFraction = categoryPagerState.currentPage + categoryPagerState.currentPageOffsetFraction
                            val targetPage = when {
                                velocityX < -500f -> 1
                                velocityX > 500f -> 0
                                else -> if (currentFraction >= 0.5f) 1 else 0
                            }
                            settleJob = coroutineScope.launch {
                                categoryPagerState.animateScrollToPage(
                                    page = targetPage,
                                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                )
                            }
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = categoryPagerState,
            userScrollEnabled = false,
            pageSpacing = 20.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val pageCategories = if (page == 0) page1Categories else page2Categories
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val pageOffset = ((categoryPagerState.currentPage - page) + categoryPagerState.currentPageOffsetFraction).let { kotlin.math.abs(it) }
                        val clampedOffset = pageOffset.coerceIn(0f, 1f)
                        val scale = 1f - (clampedOffset * 0.18f)
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - (clampedOffset * 0.75f)
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Row 1 (first 3 items)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val row1 = pageCategories.take(3)
                    row1.forEachIndexed { colIndex, category ->
                        val count = if (category.isFavourites) favouriteCount else counts[category.type]
                        CategoryCard(
                            category = category,
                            count = count,
                            animationStarted = animationStarted,
                            hasRunCountAnimation = hasRunCountAnimation,
                            index = colIndex,
                            modifier = Modifier.weight(1f),
                            onClick = { onCategoryClick(category) },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                    repeat(3 - row1.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                // Row 2 (items 3 to 5) - keeps identical height for both pages
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val row2 = pageCategories.drop(3).take(3)
                    if (row2.isNotEmpty()) {
                        row2.forEachIndexed { colIndex, category ->
                            val count = if (category.isFavourites) favouriteCount else counts[category.type]
                            CategoryCard(
                                category = category,
                                count = count,
                                animationStarted = animationStarted,
                                hasRunCountAnimation = hasRunCountAnimation,
                                index = 3 + colIndex,
                                modifier = Modifier.weight(1f),
                                onClick = { onCategoryClick(category) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                        repeat(3 - row2.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    } else {
                        // Invisible placeholder preserving exact aspect ratio of cards
                        repeat(3) {
                            Spacer(modifier = Modifier.weight(1f).padding(1.dp).aspectRatio(1f))
                        }
                    }
                }
            }
        }

        // Dot indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(2) { pageIndex ->
                val isSelected = categoryPagerState.currentPage == pageIndex
                val width by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (isSelected) 16.dp else 6.dp,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 250),
                    label = "CategoryIndicatorWidth"
                )
                val color by androidx.compose.animation.animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 250),
                    label = "CategoryIndicatorColor"
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .size(width = width, height = 5.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            if (categoryPagerState.currentPage != pageIndex) {
                                settleJob?.cancel()
                                settleJob = coroutineScope.launch {
                                    categoryPagerState.animateScrollToPage(
                                        page = pageIndex,
                                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                    )
                                }
                            }
                        }
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CategoryCard(
    category: HomeCategory,
    count: Int?,
    animationStarted: Boolean,
    hasRunCountAnimation: Boolean,
    index: Int = 0,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val actualCount = count ?: 0
    val targetCount = if (animationStarted || hasRunCountAnimation) actualCount else 0
    val animatedCount by androidx.compose.animation.core.animateIntAsState(
        targetValue = targetCount,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "CategoryCountAnimation"
    )

    val categoryKey = when {
        category.isFavourites -> "category_card_FAVOURITES"
        category.isFtp -> "category_card_FTP"
        category.type != null -> "category_card_${category.type.name}"
        else -> null
    }

    val cardSharedBounds = if (categoryKey != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = categoryKey),
                animatedVisibilityScope = animatedVisibilityScope,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(260)),
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds()
            )
        }
    } else Modifier

    // Staggered entrance animation, plays once when the card first enters
    // composition. Uses a plain Animatable + graphicsLayer (NOT
    // Modifier.animateItem(), which only works inside a Lazy scope and
    // would blank the screen here since this grid is a regular Column/Row).
    val density = LocalDensity.current
    val animatedProgress = remember { Animatable(if (hasRunCountAnimation) 1f else 0f) }
    var lastCardClickTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        if (!hasRunCountAnimation) {
            kotlinx.coroutines.delay((index * 25).toLong())
            animatedProgress.animateTo(1f, tween(durationMillis = 220, easing = LinearOutSlowInEasing))
        }
    }

    Card(
        onClick = {
            val now = System.currentTimeMillis()
            if (now - lastCardClickTime > 500L) {
                lastCardClickTime = now
                onClick()
            }
        },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .then(cardSharedBounds)
            .graphicsLayer {
                alpha = animatedProgress.value
                translationY = (1f - animatedProgress.value) * with(density) { 24.dp.toPx() }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(category.color.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(category.icon, null, tint = category.color, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(category.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            val countText = if (category.isFtp) {
                val ftpState by dev.narayan.rose.ftpserver.FtpServerService.state.collectAsState()
                when (ftpState) {
                    dev.narayan.rose.ftpserver.FtpServerState.RUNNING -> stringResource(R.string.ftp_server_state_summary_running)
                    dev.narayan.rose.ftpserver.FtpServerState.STARTING -> stringResource(R.string.ftp_server_state_summary_starting)
                    else -> stringResource(R.string.ftp_server_state_summary_stopped)
                }
            } else if (count == null) {
                "..."
            } else {
                animatedCount.toString()
            }
            Text(
                countText,
                style = MaterialTheme.typography.bodySmall,
                color = if (category.isFtp) {
                    val ftpState by dev.narayan.rose.ftpserver.FtpServerService.state.collectAsState()
                    if (ftpState == dev.narayan.rose.ftpserver.FtpServerState.RUNNING) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (category.isFtp) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentFilesSection(
    viewModel: RoseViewModel,
    recentFiles: List<FileItem>,
    showDividers: Boolean,
    onOpenRecent: () -> Unit,
    onOpenPath: (String, FileItem?) -> Unit,
    onFileClick: (FileItem) -> Unit,
    onOpenWithClick: (FileItem) -> Unit,
    onShareClick: (FileItem) -> Unit,
    onRenameClick: (FileItem) -> Unit = {},
    onDeleteClick: (FileItem) -> Unit = {},
    onExtractClick: (FileItem) -> Unit = {},
    onPropertiesClick: (FileItem) -> Unit = {}
) {
    val recents = recentFiles.take(5)
    if (recents.isEmpty()) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        viewModel.setRecentFilesExpanded(!viewModel.recentFilesExpanded)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.home_recent_files),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = { viewModel.setRecentFilesExpanded(!viewModel.recentFilesExpanded) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (viewModel.recentFilesExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (viewModel.recentFilesExpanded) stringResource(R.string.content_desc_collapse) else stringResource(R.string.content_desc_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        androidx.compose.animation.AnimatedVisibility(
            visible = viewModel.recentFilesExpanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                recents.forEachIndexed { index, item ->
                    Column {
                        var showFileMenu by remember { mutableStateOf(false) }
                        val color = recentFileIconFor(item.fileType).second

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (System.currentTimeMillis() - viewModel.lastSwipeToHomeTimestamp > 500L) {
                                        onFileClick(item)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                val isFav = remember(item.file.absolutePath, viewModel.favouriteFiles) {
                                    if (item.isDirectory) false else viewModel.isFileInFavourites(item.file.absolutePath)
                                }
                                FileIcon(
                                    fileItem = item,
                                    iconSize = 48.dp, // Unified size for better alignment
                                    folderTint = color,
                                    viewModel = viewModel,
                                    showFavouriteBadge = isFav
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    stringResource(R.string.item_size_and_date, formatFileSize(item.size), formatItemDate(item.lastModified)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box {
                                IconButton(onClick = { showFileMenu = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.content_desc_file_actions),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                WindowsFileDropdownMenu(
                                    expanded = showFileMenu,
                                    onDismissRequest = { showFileMenu = false },
                                    fileItem = item,
                                    viewModel = viewModel,
                                    isVirtual = false,
                                    onCopy = {
                                        viewModel.copySingle(item)
                                        coroutineScope.launch {
                                            kotlinx.coroutines.yield()
                                            onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null)
                                        }
                                    },
                                    onCut = {
                                        viewModel.moveSingle(item)
                                        coroutineScope.launch {
                                            kotlinx.coroutines.yield()
                                            onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null)
                                        }
                                    },
                                    onDelete = { onDeleteClick(item) },
                                    onShare = { onShareClick(item) },
                                    onProperties = { onPropertiesClick(item) },
                                    onRenameRequest = { onRenameClick(item) },
                                    onOpenWith = { onOpenWithClick(item) },
                                    onOpenLocation = { onOpenPath(item.file.parent ?: item.file.absolutePath, item) },
                                    onExtract = if (item.fileType == FileType.ZIP) { { onExtractClick(item) } } else null,
                                    offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                                )
                            }
                        }
                        if (showDividers && index != recents.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp, end = 16.dp),
                                thickness = 0.6.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                    thickness = 0.6.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecent() }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.action_see_all),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = stringResource(R.string.action_see_all),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
}

private fun recentFileIconFor(type: FileType): Pair<ImageVector, Color> = when (type) {
    FileType.IMAGE -> Icons.Default.Image to Color(0xFF4C8DFF)
    FileType.VIDEO -> Icons.Default.VideoLibrary to Color(0xFF9C6ADE)
    FileType.AUDIO -> Icons.Default.MusicNote to Color(0xFFE0904C)
    FileType.DOCUMENT -> Icons.Default.Description to Color(0xFF4C9A72)
    FileType.APK -> Icons.Default.Android to Color(0xFF4CAF7D)
    FileType.ZIP -> Icons.Default.Archive to Color(0xFFC98A2E)
    FileType.PDF -> Icons.Default.PictureAsPdf to Color(0xFFC1554C)
    FileType.FOLDER -> Icons.Default.Folder to folderIconFallbackColor
    else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFF8A8F98)
}

private val folderIconFallbackColor = Color(0xFF4C8DFF)

private data class QuickItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val path: String,
    val custom: Boolean,
    @StringRes val labelRes: Int? = null
)

private val defaultQuickAccessFolders = listOf(
    Triple("downloads", Environment.DIRECTORY_DOWNLOADS, R.string.category_downloads),
    Triple("camera", Environment.DIRECTORY_DCIM, R.string.folder_camera),
    Triple("documents", Environment.DIRECTORY_DOCUMENTS, R.string.category_documents)
)

private val defaultQuickAccessIcons: Map<String, ImageVector> = mapOf(
    "downloads" to Icons.Default.Download,
    "camera" to Icons.Default.CameraAlt,
    "documents" to Icons.Default.Description
)

private fun getQuickAccessFolderType(item: QuickItem): String? {
    if (item.id == "downloads" || item.id == "camera" || item.id == "documents") {
        return item.id
    }
    val path = item.path.trimEnd('/', '\\')
    val downloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath.trimEnd('/', '\\')
    val dcimPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath.trimEnd('/', '\\')
    val documentsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath.trimEnd('/', '\\')
    val name = File(item.path).name

    return when {
        path.equals(downloadsPath, ignoreCase = true) || name.equals("Download", ignoreCase = true) || name.equals("Downloads", ignoreCase = true) -> "downloads"
        path.equals(dcimPath, ignoreCase = true) || path.endsWith("/DCIM/Camera", ignoreCase = true) || name.equals("Camera", ignoreCase = true) -> "camera"
        path.equals(documentsPath, ignoreCase = true) || name.equals("Documents", ignoreCase = true) -> "documents"
        else -> null
    }
}

@Composable
private fun QuickAccessItemIcon(item: QuickItem) {
    val folderType = getQuickAccessFolderType(item)
    val symbol = when (folderType) {
        "downloads" -> Icons.Default.Download
        "camera" -> Icons.Default.CameraAlt
        "documents" -> Icons.Default.Description
        else -> null
    }
    Box(
        modifier = Modifier.size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = folderIconFallbackColor,
            modifier = Modifier.fillMaxSize()
        )
        if (symbol != null) {
            Icon(
                imageVector = symbol,
                contentDescription = null,
                tint = Color(0xFF1976D2).copy(alpha = 0.85f),
                modifier = Modifier
                    .size(32.dp * 0.42f)
                    .offset(y = 32.dp * 0.06f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickAccessSection(viewModel: RoseViewModel, onOpenPath: (String) -> Unit) {
    var showAddDialog by remember { mutableStateOf(false) }
    var itemPendingRemoval by remember { mutableStateOf<QuickItem?>(null) }

    val items = remember(viewModel.quickAccessRemoved, viewModel.quickAccessCustomPaths) {
        val defaultItems = defaultQuickAccessFolders.mapNotNull { (id, dir, labelRes) ->
            if (viewModel.quickAccessRemoved.contains(id)) return@mapNotNull null
            var folder = Environment.getExternalStoragePublicDirectory(dir)
            if (id == "camera") {
                val cameraFolder = File(folder, "Camera")
                if (cameraFolder.exists()) {
                    folder = cameraFolder
                }
            }
            if (folder.exists()) QuickItem(id, "", defaultQuickAccessIcons.getValue(id), folder.absolutePath, false, labelRes) else null
        }

        val seenNormalizedPaths = defaultItems.map { viewModel.normalizePath(it.path) }.toMutableSet()
        val customItems = viewModel.quickAccessCustomPaths.mapNotNull { path ->
            val folder = File(path)
            val normalized = viewModel.normalizePath(path)
            if (folder.exists() && folder.isDirectory && seenNormalizedPaths.add(normalized)) {
                QuickItem(path, folder.name, Icons.Default.Folder, path, true)
            } else null
        }
        defaultItems + customItems
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        viewModel.setQuickAccessExpanded(!viewModel.quickAccessExpanded)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.home_quick_access),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = { viewModel.setQuickAccessExpanded(!viewModel.quickAccessExpanded) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (viewModel.quickAccessExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (viewModel.quickAccessExpanded) stringResource(R.string.content_desc_collapse) else stringResource(R.string.content_desc_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))

        androidx.compose.animation.AnimatedVisibility(
            visible = viewModel.quickAccessExpanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 2000.dp),
                    userScrollEnabled = false
                ) {
                    itemsIndexed(items, key = { _, item -> "${item.id}_${item.path}" }) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .combinedClickable(
                                    onClick = { onOpenPath(item.path) },
                                    onLongClick = { itemPendingRemoval = item }
                                )
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            QuickAccessItemIcon(item = item)
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                item.labelRes?.let { stringResource(it) } ?: item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (viewModel.showListDividers) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAddDialog = true }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.AddCircleOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                stringResource(R.string.action_add_folders),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        FolderPickerDialog(
            viewModel = viewModel,
            onDismiss = { showAddDialog = false },
            onFolderSelected = { path ->
                viewModel.addQuickAccessFolder(path)
                showAddDialog = false
            }
        )
    }

    itemPendingRemoval?.let { item ->
        val itemDisplayName = item.labelRes?.let { stringResource(it) } ?: item.label
        AlertDialog(
            onDismissRequest = { itemPendingRemoval = null },
            icon = {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderDelete,
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
                        text = stringResource(R.string.dialog_remove_folder_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_remove_quick_access_message, itemDisplayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (item.custom) {
                            viewModel.removeQuickAccessFolder(item.path)
                        } else {
                            viewModel.removeDefaultQuickAccess(item.id)
                        }
                        itemPendingRemoval = null
                    },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.action_remove),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { itemPendingRemoval = null },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageSection(
    viewModel: RoseViewModel,
    onOpenPath: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val allDevices = viewModel.storageDevices

    // Physical SD Card(s) (non-primary physical removable volume that is not USB)
    val sdDevices = allDevices.filterIsInstance<StorageDevice.Physical>().filter { it.isSdCard && !it.isUsb }

    // Physical USB Drive(s)
    val usbDevices = allDevices.filterIsInstance<StorageDevice.Physical>().filter { it.isUsb }

    // Root device (if enabled in settings)
    val rootDevice = if (viewModel.useRoot) {
        allDevices.firstOrNull { it is StorageDevice.Root }
    } else null

    // User-added DocumentTree / Cloud / External storages
    val treeDevices = allDevices.filterIsInstance<StorageDevice.DocumentTree>()

    val hasAnyStorages = sdDevices.isNotEmpty() || usbDevices.isNotEmpty() || rootDevice != null || treeDevices.isNotEmpty()

    var storagePendingRemoval by remember { mutableStateOf<StorageDevice.DocumentTree?>(null) }
    var pendingStorageUriForLock by remember { mutableStateOf<android.net.Uri?>(null) }

    val openDocumentTreeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val auth = uri.authority ?: ""
            val uriStr = uri.toString()
            val isGoogleDrive = uriStr.startsWith("gdrive://") ||
                    auth.contains("docs.storage") ||
                    auth.contains("com.google.android.apps.docs")

            val activity = context as? androidx.fragment.app.FragmentActivity
            if (isGoogleDrive && activity != null && BiometricSecurityManager.isBiometricAvailable(context)) {
                pendingStorageUriForLock = uri
            } else {
                viewModel.addCustomStorage(uri)
                Toast.makeText(context, context.getString(R.string.toast_storage_added), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        viewModel.setExternalStorageExpanded(!viewModel.externalStorageExpanded)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.storage_storages_and_drives),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(
                onClick = { viewModel.setExternalStorageExpanded(!viewModel.externalStorageExpanded) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (viewModel.externalStorageExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (viewModel.externalStorageExpanded) stringResource(R.string.content_desc_collapse) else stringResource(R.string.content_desc_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))

        androidx.compose.animation.AnimatedVisibility(
            visible = viewModel.externalStorageExpanded,
            enter = androidx.compose.animation.expandVertically(animationSpec = tween(250)) + androidx.compose.animation.fadeIn(tween(200)),
            exit = androidx.compose.animation.shrinkVertically(animationSpec = tween(200)) + androidx.compose.animation.fadeOut(tween(150))
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().animateContentSize()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Empty state if no external devices or custom storages are present
                    if (!hasAnyStorages) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.storage_no_external_devices),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.storage_no_external_devices_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    // 1. SD Card(s) - Real detected physical SD card(s)
                    if (sdDevices.isNotEmpty()) {
                        sdDevices.forEach { device ->
                            val used = device.totalBytes - device.availableBytes
                            val subtitle = if (device.totalBytes > 0) {
                                stringResource(R.string.storage_amount_used_of_total, formatFileSize(used), formatFileSize(device.totalBytes))
                            } else {
                                stringResource(R.string.storage_sd_card)
                            }
                            StorageItemRow(
                                title = device.name,
                                subtitle = subtitle,
                                icon = Icons.Default.SdCard,
                                iconColor = Color(0xFF3B82F6),
                                badgeText = stringResource(R.string.storage_badge_sd),
                                badgeColor = Color(0xFF3B82F6),
                                onClick = { onOpenPath(device.path) }
                            )
                            if (viewModel.showListDividers) {
                                StorageDivider()
                            }
                        }
                    }

                    // 2. USB / Pen Drive (OTG) - Real detected physical USB volume(s)
                    if (usbDevices.isNotEmpty()) {
                        usbDevices.forEach { device ->
                            val used = device.totalBytes - device.availableBytes
                            val subtitle = if (device.totalBytes > 0) {
                                stringResource(R.string.storage_amount_used_of_total, formatFileSize(used), formatFileSize(device.totalBytes))
                            } else {
                                "OTG Storage Device"
                            }
                            StorageItemRow(
                                title = device.name,
                                subtitle = subtitle,
                                icon = Icons.Default.Usb,
                                iconColor = Color(0xFF0D9488),
                                badgeText = stringResource(R.string.storage_badge_otg),
                                badgeColor = Color(0xFF0D9488),
                                onClick = { onOpenPath(device.path) }
                            )
                            if (viewModel.showListDividers) {
                                StorageDivider()
                            }
                        }
                    }

                    // 3. Root Storage (if enabled)
                    if (rootDevice != null) {
                        StorageItemRow(
                            title = stringResource(R.string.storage_root),
                            subtitle = stringResource(R.string.storage_root_desc),
                            icon = Icons.Default.Tag,
                            iconColor = Color(0xFFF59E0B),
                            badgeText = "SU",
                            badgeColor = Color(0xFFF59E0B),
                            onClick = { onOpenPath(rootDevice.path) }
                        )
                        if (viewModel.showListDividers) {
                            StorageDivider()
                        }
                    }

                    // 4. User-added DocumentTree / Cloud / External storages
                    if (treeDevices.isNotEmpty()) {
                        treeDevices.forEach { device ->
                            val (itemIcon, itemColor, itemBadgeText) = when (device.storageType) {
                                CustomStorageType.GOOGLE_DRIVE -> Triple(Icons.Default.Cloud, Color(0xFF0F9D58), "Google Drive")
                                CustomStorageType.DOCUMENT_TREE -> if (device.isCloud) {
                                    Triple(Icons.Default.Cloud, Color(0xFF0F9D58), "Google Drive")
                                } else {
                                    Triple(Icons.Default.Storage, Color(0xFF8B5CF6), stringResource(R.string.storage_badge_custom))
                                }
                                else -> if (device.isCloud) {
                                    Triple(Icons.Default.Cloud, Color(0xFF0F9D58), "Google Drive")
                                } else {
                                    Triple(Icons.Default.Storage, Color(0xFF8B5CF6), stringResource(R.string.storage_badge_custom))
                                }
                            }
                            val subtitle = device.providerName ?: itemBadgeText
                            val isLocked = viewModel.isStorageLocked(device.path) || device.isLocked
                            var showItemMenu by remember { mutableStateOf(false) }
                            StorageItemRow(
                                title = device.name,
                                subtitle = subtitle,
                                icon = itemIcon,
                                iconColor = itemColor,
                                badgeText = itemBadgeText,
                                badgeColor = itemColor,
                                isLocked = isLocked,
                                onClick = {
                                    if (isLocked) {
                                        val activity = context as? androidx.fragment.app.FragmentActivity
                                        if (activity != null && BiometricSecurityManager.isBiometricAvailable(context)) {
                                            BiometricSecurityManager.authenticate(
                                                activity = activity,
                                                title = "Unlock ${device.name}",
                                                subtitle = "Scan fingerprint to access this folder",
                                                onSuccess = {
                                                    onOpenPath(device.path)
                                                },
                                                onError = { err ->
                                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        } else {
                                            onOpenPath(device.path)
                                        }
                                    } else {
                                        onOpenPath(device.path)
                                    }
                                },
                                onLongClick = { storagePendingRemoval = device },
                                trailingContent = {
                                    Box {
                                        IconButton(
                                            onClick = { showItemMenu = true },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = stringResource(R.string.content_desc_more_options),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showItemMenu,
                                            onDismissRequest = { showItemMenu = false },
                                            shape = RoundedCornerShape(14.dp),
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            val isGdriveOrCloud = device.storageType == CustomStorageType.GOOGLE_DRIVE || device.isCloud || isLocked
                                            if (isGdriveOrCloud) {
                                                DropdownMenuItem(
                                                    text = { Text(if (isLocked) "Remove Fingerprint Lock" else "Lock with Fingerprint") },
                                                    onClick = {
                                                        showItemMenu = false
                                                        val activity = context as? androidx.fragment.app.FragmentActivity
                                                        if (activity != null && BiometricSecurityManager.isBiometricAvailable(context)) {
                                                            BiometricSecurityManager.authenticate(
                                                                activity = activity,
                                                                title = if (isLocked) "Unlock ${device.name}" else "Lock ${device.name}",
                                                                subtitle = "Authenticate fingerprint to confirm",
                                                                onSuccess = {
                                                                    viewModel.setStorageLocked(device.path, !isLocked)
                                                                    Toast.makeText(
                                                                        context,
                                                                        if (isLocked) "Fingerprint lock removed" else "Folder locked with fingerprint",
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                },
                                                                onError = { err ->
                                                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                                }
                                                            )
                                                        } else {
                                                            viewModel.setStorageLocked(device.path, !isLocked)
                                                            Toast.makeText(
                                                                context,
                                                                if (isLocked) "Lock removed" else "Folder locked",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            if (isLocked) Icons.Default.LockOpen else Icons.Default.Fingerprint,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                )
                                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                            }
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.action_remove)) },
                                                onClick = {
                                                    showItemMenu = false
                                                    storagePendingRemoval = device
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                            if (viewModel.showListDividers) {
                                StorageDivider()
                            }
                        }
                    }

                    // Divider before bottom button
                    StorageDivider()

                    // 5. Add Google Drive or Storage button row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openDocumentTreeLauncher.launch(null)
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = stringResource(R.string.storage_add_cloud_or_network),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    storagePendingRemoval?.let { dev ->
        AlertDialog(
            onDismissRequest = { storagePendingRemoval = null },
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
                        imageVector = Icons.Default.Delete,
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
                    Text(
                        text = stringResource(R.string.dialog_remove_storage_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_remove_storage_message, dev.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeCustomStorage(dev.uriString)
                        storagePendingRemoval = null
                        Toast.makeText(context, context.getString(R.string.toast_storage_removed), Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.action_remove),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { storagePendingRemoval = null },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    pendingStorageUriForLock?.let { uri ->
        val rawDocName = remember(uri) {
            try {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name
            } catch (e: Exception) {
                null
            } ?: "Folder"
        }
        val docName = rawDocName.trim()

        Dialog(
            onDismissRequest = {
                viewModel.addCustomStorage(uri)
                pendingStorageUriForLock = null
                Toast.makeText(context, context.getString(R.string.toast_storage_added), Toast.LENGTH_SHORT).show()
            }
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Fingerprint Glow Badge
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                                    )
                                ),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                shape = RoundedCornerShape(24.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "Lock Folder?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Folder Name Pill Badge with Google Drive icon
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                        border = BorderStroke(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color(0xFF0F9D58).copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = null,
                                    tint = Color(0xFF0F9D58),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = docName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Google Drive Folder",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Require fingerprint authentication each time you open this folder to protect your files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Primary Action: Lock with Fingerprint
                    Button(
                        onClick = {
                            val activity = context as? androidx.fragment.app.FragmentActivity
                            if (activity != null && BiometricSecurityManager.isBiometricAvailable(context)) {
                                BiometricSecurityManager.authenticate(
                                    activity = activity,
                                    title = "Lock $docName",
                                    subtitle = "Confirm fingerprint to lock this folder",
                                    onSuccess = {
                                        viewModel.addCustomStorage(uri)
                                        viewModel.setStorageLocked(uri.toString(), true)
                                        pendingStorageUriForLock = null
                                        Toast.makeText(context, "Folder added and locked with fingerprint", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { err ->
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            } else {
                                viewModel.addCustomStorage(uri)
                                viewModel.setStorageLocked(uri.toString(), true)
                                pendingStorageUriForLock = null
                                Toast.makeText(context, "Folder added and locked", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Lock with Fingerprint",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Secondary Action: No, Keep Unlocked
                    FilledTonalButton(
                        onClick = {
                            viewModel.addCustomStorage(uri)
                            pendingStorageUriForLock = null
                            Toast.makeText(context, context.getString(R.string.toast_storage_added), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "No, Keep Unlocked",
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

}

@Composable
private fun StorageDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageItemRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    badgeText: String? = null,
    badgeColor: Color = iconColor,
    isLocked: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(iconColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isLocked) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked with fingerprint",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (badgeText != null) {
                    Surface(
                        color = badgeColor.copy(alpha = 0.14f),
                        contentColor = badgeColor,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (trailingContent != null) {
            trailingContent()
        } else {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerDialog(
    viewModel: RoseViewModel,
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    val rootDir = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(rootDir) }
    val isCurrentDirAdded = viewModel.isFolderInQuickAccess(currentDir.absolutePath)

    // Use FileItems to match the main listing and provide item counts
    val subDirs = remember(currentDir) {
        currentDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.map { dir ->
                FileItem(
                    file = dir,
                    isDirectory = true,
                    name = dir.name,
                    size = 0,
                    lastModified = dir.lastModified(),
                    extension = "",
                    itemCount = dir.list { _, name -> !name.startsWith(".") }?.size ?: 0
                )
            }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    // Full-screen immersive browser (matches the app's own file listing screen)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler {
            if (currentDir != rootDir) currentDir.parentFile?.let { currentDir = it } else onDismiss()
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (currentDir == rootDir) stringResource(R.string.storage_internal) else currentDir.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isCurrentDirAdded) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ) {
                                    Text(
                                        text = stringResource(R.string.tag_added),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (currentDir != rootDir) currentDir.parentFile?.let { currentDir = it } else onDismiss()
                        }) {
                            Icon(
                                if (currentDir != rootDir) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                                contentDescription = stringResource(R.string.content_desc_back)
                            )
                        }
                    },
                    actions = {
                        if (currentDir != rootDir) {
                            IconButton(onClick = { currentDir = rootDir }) {
                                Icon(Icons.Default.Home, contentDescription = stringResource(R.string.content_desc_go_to_root))
                            }
                        }
                    }
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                        Button(
                            onClick = {
                                if (!isCurrentDirAdded) {
                                    onFolderSelected(currentDir.absolutePath)
                                }
                            },
                            enabled = !isCurrentDirAdded,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Check, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isCurrentDirAdded) stringResource(R.string.folder_already_added)
                                else stringResource(R.string.action_select_this_folder),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
        ) { padding ->
            if (subDirs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.folder_picker_no_subfolders),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                val animatedItemKeys = remember(currentDir) { androidx.compose.runtime.mutableStateSetOf<String>() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    itemsIndexed(subDirs, key = { _, item -> item.file.absolutePath }) { index, item ->
                        val isAdded = viewModel.isFolderInQuickAccess(item.file.absolutePath)
                        FolderPickerRow(
                            item = item,
                            index = index,
                            scrollResetKey = currentDir,
                            hasAnimatedBefore = animatedItemKeys.contains(item.file.absolutePath),
                            onAnimationStart = { animatedItemKeys.add(item.file.absolutePath) },
                            viewModel = viewModel,
                            isAdded = isAdded,
                            onClick = { currentDir = item.file }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp), thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPickerRow(
    item: FileItem,
    index: Int,
    scrollResetKey: Any,
    hasAnimatedBefore: Boolean,
    onAnimationStart: () -> Unit,
    viewModel: RoseViewModel?,
    isAdded: Boolean = false,
    onClick: () -> Unit
) {
    val animatedProgress = remember(scrollResetKey, item.file.absolutePath) {
        Animatable(if (hasAnimatedBefore) 1f else 0f)
    }
    val density = LocalDensity.current
    LaunchedEffect(scrollResetKey, item.file.absolutePath) {
        if (!hasAnimatedBefore) {
            onAnimationStart()
            kotlinx.coroutines.delay((index % 8 * 12).toLong())
            animatedProgress.animateTo(1f, tween(durationMillis = 200, easing = LinearOutSlowInEasing))
        }
    }
    
    ListItem(
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    item.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isAdded) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.tag_added),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        },
        supportingContent = {
            val count = item.itemCount ?: 0
            val dateStr = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(item.lastModified))
            Text(stringResource(R.string.item_count_and_date, count, dateStr))
        },
        leadingContent = {
            FileIcon(
                fileItem = item,
                iconSize = 40.dp,
                folderTint = MaterialTheme.colorScheme.primary,
                viewModel = viewModel
            )
        },
        trailingContent = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        },
        modifier = Modifier
            .graphicsLayer {
                alpha = animatedProgress.value
                translationY = (1f - animatedProgress.value) * with(density) { 40.dp.toPx() }
            }
            .clickable { onClick() }
    )
}

@Composable
private fun RecycleBinSection(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.DeleteSweep,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_section_recycle_bin),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.home_recycle_bin_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}