package dev.narayan.rose

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.decode.VideoFrameDecoder
import coil.request.videoFrameMillis
import java.io.File

private data class HomeCategory(
    val label: String,
    val type: FileType,
    val icon: ImageVector,
    val color: Color,
)

private val homeCategories = listOf(
    HomeCategory("Photos", FileType.IMAGE, Icons.Default.Image, Color(0xFF4C8DFF)),
    HomeCategory("Videos", FileType.VIDEO, Icons.Default.VideoLibrary, Color(0xFF9C6ADE)),
    HomeCategory("Audio", FileType.AUDIO, Icons.Default.MusicNote, Color(0xFFE0904C)),
    HomeCategory("Documents", FileType.DOCUMENT, Icons.Default.Description, Color(0xFF4C9A72)),
    HomeCategory("APKs", FileType.APK, Icons.Default.Android, Color(0xFF4CAF7D)),
    HomeCategory("Archives", FileType.ZIP, Icons.Default.Archive, Color(0xFFC98A2E))
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    viewModel: RoseViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onOpenPath: (String, FileItem?) -> Unit,
    onOpenCategory: (FileType, String) -> Unit,
    onOpenRecent: () -> Unit,
    onFileClick: (FileItem) -> Unit,
    onShareClick: (FileItem) -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onRecycleBinClick: () -> Unit
) {
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
            if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
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


    var visible by remember { mutableStateOf(viewModel.hasRunEntranceAnimation) }
    LaunchedEffect(Unit) {
        if (!visible) {
            // Small delay for the first time entrance
            kotlinx.coroutines.delay(100)
            visible = true
            viewModel.hasRunEntranceAnimation = true
        }
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

    // Silent refresh for categories and storage while on landing page
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60000) // Every 60 seconds
            if (!isSearching) {
                viewModel.loadCategoryCounts(force = true)
                viewModel.loadStorageInfo()
            }
        }
    }
    var searchQuery by remember { mutableStateOf("") }

    BackHandler(enabled = isSearching) {
        isSearching = false
        searchQuery = ""
        viewModel.searchFiles("")
    }

    // Dialog states for recent file actions
    var fileForRename by remember { mutableStateOf<FileItem?>(null) }
    var fileForDelete by remember { mutableStateOf<FileItem?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        topBar = {
            Surface(shadowElevation = 0.dp) {
                Column {
                    if (isSearching) {
                        SearchTopBar(
                            query = searchQuery,
                            onQueryChange = {
                                searchQuery = it
                                viewModel.searchFiles(it)
                            },
                            onBack = {
                                isSearching = false
                                searchQuery = ""
                                viewModel.searchFiles("")
                            }
                        )
                    } else {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        modifier = Modifier.size(44.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_app_logo),
                                            contentDescription = null,
                                            tint = Color.Unspecified,
                                            modifier = Modifier.padding(6.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        "ROSE",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { isSearching = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search")
                                }
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        shape = RoundedCornerShape(16.dp),
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Settings", modifier = Modifier.padding(vertical = 4.dp)) },
                                            onClick = { showMenu = false; onSettingsClick() },
                                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = { Text("About", modifier = Modifier.padding(vertical = 4.dp)) },
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
                                        android.widget.Toast.makeText(context, "Cannot extract on Home screen. Open a folder first.", android.widget.Toast.LENGTH_SHORT).show()
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
                                        android.widget.Toast.makeText(context, "Cannot paste on Home screen. Open a folder first.", android.widget.Toast.LENGTH_SHORT).show()
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (isSearching && searchQuery.isNotEmpty()) {
                item(key = "search_spacer") { Spacer(modifier = Modifier.height(8.dp)) }

                if (viewModel.searchResults.isEmpty() && !viewModel.isRecursiveSearching) {
                    item(key = "no_results") {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No results for \"$searchQuery\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    itemsIndexed(viewModel.searchResults, key = { _, item -> item.file.absolutePath }) { index, item ->
                        SearchResultItem(
                            item = item,
                            clipboardHasFiles = viewModel.clipboardFiles.isNotEmpty(),
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
                                viewModel.toggleSelection(item)
                                viewModel.copySelected()
                                // Navigate to file explorer to allow pasting
                                onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null)
                                isSearching = false
                                searchQuery = ""
                                viewModel.searchFiles("")
                            },
                            onCut = {
                                viewModel.toggleSelection(item)
                                viewModel.moveSelected()
                                // Navigate to file explorer to allow pasting
                                onOpenPath(Environment.getExternalStorageDirectory().absolutePath, null)
                                isSearching = false
                                searchQuery = ""
                                viewModel.searchFiles("")
                            },
                            onExtract = {
                                viewModel.prepareExtraction(item.file)
                                android.widget.Toast.makeText(context, "Archive ready. Navigate to a folder to extract.", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onPaste = { viewModel.navigateTo(item.file, item.isDirectory); viewModel.pasteFiles() }
                        )
                        if (viewModel.showListDividers && index != viewModel.searchResults.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 72.dp, end = 12.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                        }
                    }
                }

                if (viewModel.isRecursiveSearching) {
                    item(key = "search_progress") {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                            isLoading = viewModel.isCategoryCountsLoading,
                            onCategoryClick = { category -> onOpenCategory(category.type, category.label) }
                        )
                    }
                }

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
                            onOpenPath = { path -> onOpenPath(path, null) },
                            onFileClick = onFileClick,
                            onShareClick = onShareClick,
                            onRenameClick = { fileForRename = it },
                            onDeleteClick = { fileForDelete = it },
                            onExtractClick = {
                                viewModel.prepareExtraction(it.file)
                                android.widget.Toast.makeText(context, "Archive ready. Navigate to a folder to extract.", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onPropertiesClick = { viewModel.showProperties(it) }
                        )
                    }
                }

                if (viewModel.showQuickAccess) {
                    item(key = "quick_access") {
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(tween(300, delayMillis = 130)) + slideInVertically(tween(300, delayMillis = 130)) { it / 12 }
                        ) {
                            QuickAccessSection(viewModel = viewModel, onOpenPath = { path -> onOpenPath(path, null) })
                        }
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

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    // Dialogs
    viewModel.propertiesFile?.let { item ->
        PropertiesDialog(fileItem = item, onDismiss = { viewModel.closeProperties() }, viewModel = viewModel)
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
        AlertDialog(
            onDismissRequest = { fileForDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Delete File")
                }
            },
            text = { Text("Are you sure you want to delete \"${item.name}\"? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteFile(item)
                        fileForDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { fileForDelete = null }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }
}

@Composable
private fun SearchResultItem(
    item: FileItem,
    clipboardHasFiles: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onOpenLocation: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onExtract: () -> Unit = {},
    onPaste: () -> Unit
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
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                ) {
                    if (item.isDirectory && clipboardHasFiles) {
                        DropdownMenuItem(
                            text = { Text("Paste here", modifier = Modifier.padding(vertical = 4.dp)) },
                            onClick = { showMenu = false; onPaste() },
                            leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                    }
                    DropdownMenuItem(
                        text = { Text("Open location", modifier = Modifier.padding(vertical = 4.dp)) },
                        onClick = { showMenu = false; onOpenLocation() },
                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                    DropdownMenuItem(
                        text = { Text("Copy", modifier = Modifier.padding(vertical = 4.dp)) },
                        onClick = { showMenu = false; onCopy() },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                    DropdownMenuItem(
                        text = { Text("Move", modifier = Modifier.padding(vertical = 4.dp)) },
                        onClick = { showMenu = false; onCut() },
                        leadingIcon = { Icon(Icons.Default.ContentCut, null) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    )
                    if (item.fileType == FileType.ZIP) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                        DropdownMenuItem(
                            text = { Text("Extract", modifier = Modifier.padding(vertical = 4.dp)) },
                            onClick = { showMenu = false; onExtract() },
                            leadingIcon = { Icon(Icons.Outlined.Unarchive, null) },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
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

    var animationStarted by remember { mutableStateOf(viewModel.hasRunStorageAnimation) }
    val animatedFraction by animateFloatAsState(
        targetValue = if (animationStarted) storageInfo?.usedFraction ?: 0f else 0f,
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
                            "All Files",
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
                        "used of ${formatFileSize(storageInfo.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            } else {
                Box(modifier = Modifier.height(32.dp)) // Placeholder
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun CategoryGrid(
    counts: Map<FileType, Int>,
    isLoading: Boolean,
    onCategoryClick: (HomeCategory) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        homeCategories.chunked(3).forEachIndexed { rowIndex, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEachIndexed { colIndex, category ->
                    CategoryCard(
                        category = category,
                        count = counts[category.type] ?: 0,
                        isLoading = isLoading,
                        index = rowIndex * 3 + colIndex,
                        modifier = Modifier.weight(1f),
                        onClick = { onCategoryClick(category) }
                    )
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: HomeCategory,
    count: Int,
    isLoading: Boolean,
    index: Int = 0,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val animatedCount by androidx.compose.animation.core.animateIntAsState(
        targetValue = count,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "CategoryCountAnimation"
    )

    // Staggered entrance animation, plays once when the card first enters
    // composition. Uses a plain Animatable + graphicsLayer (NOT
    // Modifier.animateItem(), which only works inside a Lazy scope and
    // would blank the screen here since this grid is a regular Column/Row).
    val density = LocalDensity.current
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index * 25).toLong())
        animatedProgress.animateTo(1f, tween(durationMillis = 220, easing = LinearOutSlowInEasing))
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier
            .padding(2.dp)
            .aspectRatio(1f)
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
                category.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                if (isLoading && count == 0) "…" else animatedCount.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    onOpenPath: (String) -> Unit,
    onFileClick: (FileItem) -> Unit,
    onShareClick: (FileItem) -> Unit,
    onRenameClick: (FileItem) -> Unit,
    onDeleteClick: (FileItem) -> Unit,
    onExtractClick: (FileItem) -> Unit,
    onPropertiesClick: (FileItem) -> Unit
) {
    val recents = recentFiles.take(5)
    if (recents.isEmpty()) return

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Recent files",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                var isRotating by remember { mutableStateOf(false) }
                val rotation by animateFloatAsState(
                    targetValue = if (isRotating) 360f else 0f,
                    animationSpec = if (isRotating) tween(durationMillis = 800, easing = FastOutSlowInEasing) else tween(0),
                    label = "RecentRefreshRotation",
                    finishedListener = { isRotating = false }
                )

                IconButton(
                    onClick = {
                        isRotating = true
                        viewModel.loadRecentFiles()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation }
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenRecent() }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "See all",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().animateContentSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 2000.dp),
                userScrollEnabled = false
            ) {
                itemsIndexed(recents, key = { _, item -> item.file.absolutePath }) { index, item ->
                    Column(modifier = Modifier.animateItem()) {
                        var showFileMenu by remember { mutableStateOf(false) }
                        val color = recentFileIconFor(item.fileType).second

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onFileClick(item) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                FileIcon(
                                    fileItem = item,
                                    iconSize = 48.dp, // Unified size for better alignment
                                    folderTint = color,
                                    viewModel = viewModel
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
                                    "${formatFileSize(item.size)} | ${formatItemDate(item.lastModified)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box {
                                IconButton(onClick = { showFileMenu = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "File actions",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DropdownMenu(
                                    expanded = showFileMenu,
                                    onDismissRequest = { showFileMenu = false },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    offset = androidx.compose.ui.unit.DpOffset(x = (-8).dp, y = 0.dp)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Open location", modifier = Modifier.padding(vertical = 4.dp)) },
                                        onClick = {
                                            showFileMenu = false
                                            onOpenPath(item.file.parent ?: item.file.absolutePath)
                                        },
                                        leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                    DropdownMenuItem(
                                        text = { Text("Share", modifier = Modifier.padding(vertical = 4.dp)) },
                                        onClick = { showFileMenu = false; onShareClick(item) },
                                        leadingIcon = { Icon(Icons.Default.Share, null) },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                    DropdownMenuItem(
                                        text = { Text("Rename", modifier = Modifier.padding(vertical = 4.dp)) },
                                        onClick = { showFileMenu = false; onRenameClick(item) },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                    DropdownMenuItem(
                                        text = { Text("Properties", modifier = Modifier.padding(vertical = 4.dp)) },
                                        onClick = { showFileMenu = false; onPropertiesClick(item) },
                                        leadingIcon = { Icon(Icons.Default.Info, null) },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                    if (item.fileType == FileType.ZIP) {
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                        DropdownMenuItem(
                                            text = { Text("Extract", modifier = Modifier.padding(vertical = 4.dp)) },
                                            onClick = { showFileMenu = false; onExtractClick(item) },
                                            leadingIcon = { Icon(Icons.Outlined.Unarchive, null) },
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp).alpha(0.3f))
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) },
                                        onClick = { showFileMenu = false; onDeleteClick(item) },
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                        if (showDividers && index != recents.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(start = 66.dp, end = 16.dp))
                        }
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
    val custom: Boolean
)

private val defaultQuickAccessFolders = listOf(
    Triple("downloads", Environment.DIRECTORY_DOWNLOADS, "Downloads"),
    Triple("camera", Environment.DIRECTORY_DCIM, "Camera"),
    Triple("documents", Environment.DIRECTORY_DOCUMENTS, "Documents")
)

private val defaultQuickAccessIcons: Map<String, ImageVector> = mapOf(
    "downloads" to Icons.Default.Download,
    "camera" to Icons.Default.CameraAlt,
    "documents" to Icons.Default.Folder
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickAccessSection(viewModel: RoseViewModel, onOpenPath: (String) -> Unit) {
    var showAddDialog by remember { mutableStateOf(false) }
    var itemPendingRemoval by remember { mutableStateOf<QuickItem?>(null) }

    val items = remember(viewModel.quickAccessRemoved, viewModel.quickAccessCustomPaths) {
        val defaultItems = defaultQuickAccessFolders.mapNotNull { (id, dir, label) ->
            if (viewModel.quickAccessRemoved.contains(id)) return@mapNotNull null
            var folder = Environment.getExternalStoragePublicDirectory(dir)
            if (id == "camera") {
                val cameraFolder = File(folder, "Camera")
                if (cameraFolder.exists()) {
                    folder = cameraFolder
                }
            }
            if (folder.exists()) QuickItem(id, label, defaultQuickAccessIcons.getValue(id), folder.absolutePath, false) else null
        }

        val customItems = viewModel.quickAccessCustomPaths.mapNotNull { path ->
            val folder = File(path)
            if (folder.exists() && folder.isDirectory) QuickItem(path, folder.name, Icons.Default.Folder, path, true) else null
        }
        defaultItems + customItems
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Quick access",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.setQuickAccessExpanded(!viewModel.quickAccessExpanded) }) {
                Icon(
                    if (viewModel.quickAccessExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (viewModel.quickAccessExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

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
                    itemsIndexed(items, key = { _, item -> item.path }) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .combinedClickable(
                                    onClick = { onOpenPath(item.path) },
                                    onLongClick = { itemPendingRemoval = item }
                                )
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileIcon(fileItem = FileItem(File(item.path)), iconSize = 24.dp, folderTint = MaterialTheme.colorScheme.primary, viewModel = viewModel)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(item.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                        if (viewModel.showListDividers) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAddDialog = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AddCircleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Add folders", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
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
            onDismiss = { showAddDialog = false },
            onFolderSelected = { path ->
                viewModel.addQuickAccessFolder(path)
                showAddDialog = false
            }
        )
    }

    itemPendingRemoval?.let { item ->
        AlertDialog(
            onDismissRequest = { itemPendingRemoval = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Remove Folder")
                }
            },
            text = { Text("Remove \"${item.label}\" from Quick access? You can add it back any time.") },
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
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { itemPendingRemoval = null }) { Text("Cancel") }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageSection(
    viewModel: RoseViewModel,
    onOpenPath: (String) -> Unit
) {
    val allDevices = viewModel.storageDevices
    // Filter to only show physical SD cards (removable volumes)
    val devices = allDevices.filter {
        it is StorageDevice.Physical && it.isSdCard
    }

    if (devices.isEmpty()) return

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "External Storage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.setExternalStorageExpanded(!viewModel.externalStorageExpanded) }) {
                Icon(
                    if (viewModel.externalStorageExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (viewModel.externalStorageExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

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
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 2000.dp),
                    userScrollEnabled = false
                ) {
                    itemsIndexed(devices, key = { _, device ->
                        when(device) {
                            is StorageDevice.Physical -> device.path
                            is StorageDevice.Logical -> device.treeUri.toString()
                        }
                    }) { index, device ->
                        StorageDeviceItem(
                            device = device,
                            modifier = Modifier.animateItem(),
                            onClick = {
                                when (device) {
                                    is StorageDevice.Physical -> onOpenPath(device.path)
                                    is StorageDevice.Logical -> onOpenPath(device.treeUri.toString())
                                }
                            },
                            onLongClick = {}
                        )
                        if (viewModel.showListDividers && index != devices.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageDeviceItem(
    device: StorageDevice,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (device) {
            is StorageDevice.Physical -> if (device.isSdCard) Icons.Default.SdCard else Icons.Default.SdStorage
            is StorageDevice.Logical -> {
                val isGoogleDrive = device.treeUri.authority?.contains("com.google.android.apps.docs") == true
                if (isGoogleDrive) Icons.Default.CloudQueue else Icons.Default.Cloud
            }
        }

        val iconColor = when (device) {
            is StorageDevice.Physical -> MaterialTheme.colorScheme.primary
            is StorageDevice.Logical -> {
                val isGoogleDrive = device.treeUri.authority?.contains("com.google.android.apps.docs") == true
                if (isGoogleDrive) Color(0xFF4285F4) else MaterialTheme.colorScheme.secondary
            }
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .background(iconColor.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(22.dp))
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (device is StorageDevice.Physical) {
                val used = device.totalBytes - device.availableBytes
                Text(
                    "${formatFileSize(used)} used of ${formatFileSize(device.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (device is StorageDevice.Logical) {
                Text(
                    if (device.treeUri.authority?.contains("com.google.android.apps.docs") == true) "Google Drive" else "Cloud Storage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerDialog(onDismiss: () -> Unit, onFolderSelected: (String) -> Unit) {
    val rootDir = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(rootDir) }
    val subDirs = remember(currentDir) {
        currentDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }
    val vm = (androidx.compose.ui.platform.LocalContext.current as? androidx.activity.ComponentActivity)?.let { (it as? MainActivity)?.viewModel }

    // Full-screen immersive browser (matches the app's own file listing screen)
    // instead of a small popup window/dialog.
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
                        Text(
                            if (currentDir == rootDir) "Internal storage" else currentDir.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (currentDir != rootDir) currentDir.parentFile?.let { currentDir = it } else onDismiss()
                        }) {
                            Icon(
                                if (currentDir != rootDir) Icons.Default.ArrowUpward else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    }
                )
            },
            bottomBar = {
                BottomAppBar {
                    Button(
                        onClick = { onFolderSelected(currentDir.absolutePath) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select this folder")
                    }
                }
            }
        ) { padding ->
            if (subDirs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        "No subfolders here",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                // Staggered fade + slide-in entrance, restarted every time the
                // browsed folder changes - matching the main file listing screen.
                val animatedItemKeys = remember(currentDir) { androidx.compose.runtime.mutableStateSetOf<String>() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(subDirs, key = { _, dir -> dir.absolutePath }) { index, dir ->
                        FolderPickerRow(
                            dir = dir,
                            index = index,
                            scrollResetKey = currentDir,
                            hasAnimatedBefore = animatedItemKeys.contains(dir.absolutePath),
                            onAnimationStart = { animatedItemKeys.add(dir.absolutePath) },
                            viewModel = vm,
                            onClick = { currentDir = dir }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPickerRow(
    dir: File,
    index: Int,
    scrollResetKey: Any,
    hasAnimatedBefore: Boolean,
    onAnimationStart: () -> Unit,
    viewModel: RoseViewModel?,
    onClick: () -> Unit
) {
    val animatedProgress = remember(scrollResetKey, dir.absolutePath) {
        Animatable(if (hasAnimatedBefore) 1f else 0f)
    }
    val density = LocalDensity.current
    LaunchedEffect(scrollResetKey, dir.absolutePath) {
        if (!hasAnimatedBefore) {
            onAnimationStart()
            kotlinx.coroutines.delay((index % 8 * 12).toLong())
            animatedProgress.animateTo(1f, tween(durationMillis = 200, easing = LinearOutSlowInEasing))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = animatedProgress.value
                translationY = (1f - animatedProgress.value) * with(density) { 40.dp.toPx() }
            }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileIcon(fileItem = FileItem(dir), iconSize = 40.dp, folderTint = MaterialTheme.colorScheme.primary, viewModel = viewModel)
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            dir.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
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
            modifier = Modifier.padding(16.dp),
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
                    "Recycle Bin",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Clean up files deleted in the last 30 days",
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