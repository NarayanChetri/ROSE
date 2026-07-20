package dev.narayan.rose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(
    onBack: () -> Unit,
    viewModel: RoseViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
    val context = LocalContext.current
    val rawItems = viewModel.recycleBinItems

    LaunchedEffect(Unit) {
        viewModel.loadRecycleBin()
    }

    // Material Files style: periodic poll for "live" updates (size changes, etc.)
    // while the user is looking at the screen.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            viewModel.loadRecycleBin()
        }
    }

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
                viewModel.recycleBinScrollIndex = pos.first
                viewModel.recycleBinScrollOffset = pos.second
            }
        }
    }

    // Restore scroll on mount ONLY if the listState was actually reset to (0,0)
    // (e.g. by Activity recreation or navigation disposal) and we have a
    // non-zero saved position.
    LaunchedEffect(Unit) {
        if (viewModel.recycleBinScrollIndex != 0 || viewModel.recycleBinScrollOffset != 0) {
            val listStateWasReset = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            if (listStateWasReset) {
                // Material Files style: wait for the list to have enough items to
                // actually reach the saved index before scrolling.
                snapshotFlow { listState.layoutInfo.totalItemsCount }.collect { count ->
                    if (count > viewModel.recycleBinScrollIndex) {
                        listState.scrollToItem(viewModel.recycleBinScrollIndex, viewModel.recycleBinScrollOffset)
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }
        }
    }

    var sortBy by remember { mutableStateOf(RoseViewModel.SortBy.DATE) }
    var sortOrder by remember { mutableStateOf(RoseViewModel.SortOrder.DESCENDING) }

    val items = remember(rawItems, sortBy, sortOrder) {
        val sorted = when (sortBy) {
            RoseViewModel.SortBy.NAME -> rawItems.sortedBy { it.originalName.lowercase() }
            RoseViewModel.SortBy.DATE -> rawItems.sortedBy { it.deletionTime }
            RoseViewModel.SortBy.SIZE -> rawItems.sortedBy { it.size }
            else -> rawItems
        }
        if (sortOrder == RoseViewModel.SortOrder.DESCENDING) sorted.reversed() else sorted
    }

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selectedItems = remember(selectedIds, items) { items.filter { it.id in selectedIds }.toSet() }
    var isSelectionMode by remember { mutableStateOf(false) }

    // Material Files style: track the top item and list size to detect when
    // a change should or should NOT trigger a scroll-to-top.
    var lastTopKey by remember { mutableStateOf<String?>(null) }
    var lastListSize by remember { mutableStateOf(0) }

    LaunchedEffect(items.firstOrNull()?.id) {
        val newTopKey = items.firstOrNull()?.id
        val previousTopKey = lastTopKey
        val previousSize = lastListSize
        val newSize = items.size
        lastTopKey = newTopKey
        lastListSize = newSize

        // A delete shrinks the list. If we detect the list shrunk, we skip any
        // automatic scroll-to-top logic that might be triggered by the key change.
        // This is what allows the slide-up animation to play out in-place.
        if (previousTopKey != null && newTopKey != null && newTopKey != previousTopKey &&
            newSize > previousSize && listState.firstVisibleItemIndex == 0
        ) {
            listState.animateScrollToItem(0)
        }
    }

    // Material Files-style entrance animation keys (fade + slide up)
    val animatedItemKeys = remember(sortBy, sortOrder) { androidx.compose.runtime.mutableStateSetOf<String>() }

    var showDeleteConfirm by remember { mutableStateOf<RecycledItem?>(null) }
    var showEmptyConfirm by remember { mutableStateOf(false) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var propertyItem by remember { mutableStateOf<RecycledItem?>(null) }

    fun exitSelection() {
        selectedIds = emptySet()
        isSelectionMode = false
    }

    BackHandler {
        if (isSelectionMode) {
            exitSelection()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Column {
                            Text("${selectedItems.size} selected")
                            Text(
                                "${items.size} items total",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (selectedIds.size == items.size) selectedIds = emptySet()
                            else selectedIds = items.map { it.id }.toSet()
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("Recycle Bin")
                            Text(
                                "${items.size} items total",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                RecycledSortItem("Name", RoseViewModel.SortBy.NAME, sortBy, sortOrder) {
                                    if (sortBy == it) sortOrder = if (sortOrder == RoseViewModel.SortOrder.ASCENDING) RoseViewModel.SortOrder.DESCENDING else RoseViewModel.SortOrder.ASCENDING
                                    else { sortBy = it; sortOrder = RoseViewModel.SortOrder.ASCENDING }
                                    showSortMenu = false
                                }
                                RecycledSortItem("Date", RoseViewModel.SortBy.DATE, sortBy, sortOrder) {
                                    if (sortBy == it) sortOrder = if (sortOrder == RoseViewModel.SortOrder.ASCENDING) RoseViewModel.SortOrder.DESCENDING else RoseViewModel.SortOrder.ASCENDING
                                    else { sortBy = it; sortOrder = RoseViewModel.SortOrder.DESCENDING }
                                    showSortMenu = false
                                }
                                RecycledSortItem("Size", RoseViewModel.SortBy.SIZE, sortBy, sortOrder) {
                                    if (sortBy == it) sortOrder = if (sortOrder == RoseViewModel.SortOrder.ASCENDING) RoseViewModel.SortOrder.DESCENDING else RoseViewModel.SortOrder.ASCENDING
                                    else { sortBy = it; sortOrder = RoseViewModel.SortOrder.DESCENDING }
                                    showSortMenu = false
                                }
                            }
                        }
                        if (items.isNotEmpty()) {
                            IconButton(onClick = { showEmptyConfirm = true }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Empty", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                BottomAppBar(
                    actions = {
                        Button(
                            onClick = {
                                viewModel.restoreRecycleBinItems(context, selectedItems)
                                exitSelection()
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Restore, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Restore")
                        }
                        Button(
                            onClick = { showBulkDeleteConfirm = true },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.DeleteForever, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete")
                        }
                    }
                )
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Recycle bin is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Items will be deleted after 30 days",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "top_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
                itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                    val isSelected = selectedItems.contains(item)
                    RecycledItemView(
                        item = item,
                        isSelected = isSelected,
                        isSelectionMode = isSelectionMode,
                        onRestore = {
                            dev.narayan.rose.filejob.FileJobService.startRestore(context, listOf(item.id), listOf(item.originalName))
                        },
                        onDeletePermanently = { showDeleteConfirm = item },
                        onProperties = { propertyItem = item },
                        onClick = {
                            if (isSelectionMode) {
                                selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                                if (selectedIds.isEmpty()) isSelectionMode = false
                            } else {
                                // Try to play/open
                                (context as? MainActivity)?.let { activity ->
                                    activity.openRecycledFile(item)
                                }
                            }
                        },
                        onLongClick = {
                            if (!isSelectionMode) {
                                isSelectionMode = true
                                selectedIds = setOf(item.id)
                            }
                        },
                        viewModel = viewModel,
                        index = index,
                        hasAnimatedBefore = animatedItemKeys.contains(item.id),
                        onAnimationStart = { animatedItemKeys.add(item.id) },
                        modifier = Modifier.animateItem()
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    propertyItem?.let { item ->
        AlertDialog(
            onDismissRequest = { propertyItem = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Properties")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PropertyRow("Original Name", item.originalName)
                    PropertyRow("Original Path", item.originalPath)
                    PropertyRow("Deletion Date", formatItemDate(item.deletionTime))
                    PropertyRow("Size", formatFileSize(item.size))
                }
            },
            confirmButton = {
                Button(onClick = { propertyItem = null }) { Text("Close") }
            }
        )
    }

    showDeleteConfirm?.let { item ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Permanently") },
            text = { Text("Are you sure you want to delete \"${item.originalName}\"? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteRecycleBinItemPermanently(item)
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }

    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            title = { Text("Empty Recycle Bin") },
            text = { Text("All items will be permanently deleted. Are you sure?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.emptyRecycleBinPermanently()
                        showEmptyConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Empty") }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Delete Permanently") },
            text = { Text("Delete ${selectedItems.size} selected item(s) permanently?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteRecycleBinItemsPermanently(selectedItems)
                        exitSelection()
                        showBulkDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun RecycledSortItem(
    label: String,
    sortBy: RoseViewModel.SortBy,
    currentSort: RoseViewModel.SortBy,
    currentOrder: RoseViewModel.SortOrder,
    onClick: (RoseViewModel.SortBy) -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = { onClick(sortBy) },
        trailingIcon = {
            if (currentSort == sortBy) {
                Icon(
                    if (currentOrder == RoseViewModel.SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    )
}

@Composable
internal fun RecycledItemView(
    item: RecycledItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit,
    onProperties: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    viewModel: RoseViewModel,
    modifier: Modifier = Modifier,
    index: Int = 0,
    hasAnimatedBefore: Boolean = true,
    onAnimationStart: () -> Unit = {}
) {
    // Material Files-style staggered entrance animation
    val animatedProgress = remember(item.id) {
        Animatable(if (hasAnimatedBefore) 1f else 0f)
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(item.id) {
        if (!hasAnimatedBefore) {
            onAnimationStart()
            val delay = (index % 8 * 12).toLong()
            kotlinx.coroutines.delay(delay)
            animatedProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing)
            )
        }
    }

    val context = LocalContext.current
    val recycledFile = File(context.getExternalFilesDir(null), ".rose_recycle_bin/${item.id}")
    var showMenu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = modifier
            .graphicsLayer {
                alpha = animatedProgress.value
                translationY = with(density) { (1f - animatedProgress.value) * 40.dp.toPx() }
            }
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                FileIcon(
                    fileItem = FileItem(
                        file = recycledFile,
                        isDirectory = item.isDirectory,
                        name = item.originalName,
                        size = if (recycledFile.exists()) recycledFile.length() else item.size,
                        lastModified = item.deletionTime,
                        extension = item.originalName.substringAfterLast('.', "").lowercase()
                    ),
                    iconSize = 48.dp,
                    viewModel = viewModel
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.originalName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatFileSize(if (recycledFile.exists()) recycledFile.length() else item.size)} | Deleted: ${formatItemDate(item.deletionTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "From: ${item.originalPath.substringBeforeLast("/")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!isSelectionMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, null)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Restore") },
                            onClick = { showMenu = false; onRestore() },
                            leadingIcon = { Icon(Icons.Default.Restore, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Permanently") },
                            onClick = { showMenu = false; onDeletePermanently() },
                            leadingIcon = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) }
                        )
                        DropdownMenuItem(
                            text = { Text("Properties") },
                            onClick = { showMenu = false; onProperties() },
                            leadingIcon = { Icon(Icons.Default.Info, null) }
                        )
                    }
                }
            } else {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
            }
        }
    }
}
