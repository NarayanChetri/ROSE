package dev.narayan.rose

import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings, redesigned: grouped cards per section with a leading icon,
 * rather than one long scrolling list. Behaviour is unchanged from before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: RoseViewModel,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var showExclusionDialog by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }

    if (showExclusionDialog) {
        ExcludedFoldersDialog(
            excludedFolders = viewModel.excludedFolders,
            onDismiss = { showExclusionDialog = false },
            onAdd = { showFolderPicker = true },
            onRemove = { folder ->
                viewModel.setExcludedFolders(viewModel.excludedFolders - folder)
            }
        )
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            onDismiss = { showFolderPicker = false },
            onFolderSelected = { folder ->
                viewModel.setExcludedFolders(viewModel.excludedFolders + folder.absolutePath)
                showFolderPicker = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ---------- Appearance ----------
            SettingsSection(title = "Appearance", icon = Icons.Default.Palette) {
                SettingsGroup {
                    SettingsChoiceRow(
                        title = "Theme",
                        options = listOf("System" to ThemeMode.SYSTEM, "Light" to ThemeMode.LIGHT, "Dark" to ThemeMode.DARK),
                        selected = viewModel.themeMode,
                        onSelect = { viewModel.setThemeMode(it) }
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Default.Contrast,
                        title = "Black AMOLED mode",
                        subtitle = "Use true black backgrounds while in dark mode",
                        checked = viewModel.amoledMode,
                        onCheckedChange = { viewModel.setAmoledMode(it) }
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SettingsDivider()
                        SettingsSwitchRow(
                            icon = Icons.Default.ColorLens,
                            title = "Dynamic color",
                            subtitle = "Use colors generated from your wallpaper (Android 12+)",
                            checked = viewModel.dynamicColorEnabled,
                            onCheckedChange = { viewModel.setDynamicColor(it) }
                        )
                    }
                }
            }

            // ---------- Browsing ----------
            SettingsSection(title = "Browsing", icon = Icons.Default.Folder) {
                SettingsGroup {
                    SettingsChoiceRow(
                        title = "Recent files limit",
                        options = listOf("50" to 50, "100" to 100, "150" to 150, "200" to 200),
                        selected = viewModel.recentFilesLimit,
                        onSelect = { viewModel.setRecentFilesLimit(it) }
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Default.HorizontalRule,
                        title = "Show list dividers",
                        subtitle = "Thin lines between items in Recent files, Quick access, and folder lists",
                        checked = viewModel.showListDividers,
                        onCheckedChange = { viewModel.setShowListDividers(it) }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        icon = Icons.Default.VisibilityOff,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Excluded folders",
                        subtitle = "Select folders to hide from recents and categories",
                        onClick = { showExclusionDialog = true }
                    )
                }
            }

            // ---------- Navigation ----------
            SettingsSection(title = "Navigation", icon = Icons.Default.Explore) {
                SettingsGroup {
                    SettingsChoiceRow(
                        title = "Start page",
                        options = listOf("Landing page" to StartPage.HOME, "All files" to StartPage.ALL_FILES),
                        selected = viewModel.startPage,
                        onSelect = { viewModel.setStartPage(it) }
                    )
                }
            }

            // ---------- Safety ----------
            SettingsSection(title = "Safety", icon = Icons.Default.Shield) {
                SettingsGroup {
                    SettingsSwitchRow(
                        icon = Icons.Default.WarningAmber,
                        title = "Confirm before delete",
                        subtitle = "Ask for confirmation before deleting files or folders",
                        checked = viewModel.confirmBeforeDelete,
                        onCheckedChange = { viewModel.setConfirmBeforeDelete(it) }
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Default.RestoreFromTrash,
                        title = "Use Recycle Bin",
                        subtitle = "Deleted files are moved to Recycle Bin and kept for 30 days",
                        checked = viewModel.useRecycleBin,
                        onCheckedChange = { viewModel.setUseRecycleBin(it) }
                    )
                }
            }

            // ---------- Home Screen ----------
            SettingsSection(title = "Home Screen", icon = Icons.Default.Home) {
                SettingsGroup {
                    SettingsSwitchRow(
                        icon = Icons.Default.Bolt,
                        title = "Show Quick Access",
                        subtitle = "Show the Quick Access section on the landing page",
                        checked = viewModel.showQuickAccess,
                        onCheckedChange = { viewModel.setShowQuickAccess(it) }
                    )
                }
            }


            // ---------- Storage ----------
            SettingsSection(title = "Storage", icon = Icons.Default.Storage) {
                SettingsGroup {
                    SettingsActionRow(
                        icon = Icons.Default.CleaningServices,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Clear thumbnail cache",
                        subtitle = "Free up space used by cached image and video thumbnails",
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    context.imageLoader.memoryCache?.clear()
                                    context.imageLoader.diskCache?.clear()
                                }
                                Toast.makeText(context, "Thumbnail cache cleared", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            // ---------- Support ----------
            SettingsSection(title = "Support", icon = Icons.Default.Favorite) {
                SettingsGroup {
                    SettingsActionRow(
                        icon = Icons.Default.BugReport,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = "Report an issue",
                        subtitle = "Found a bug? Let us know on GitHub",
                        onClick = { uriHandler.openUri("https://github.com/NarayanChetri/ROSE/issues") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** Section label with a small leading icon, followed by its content. */
@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Column(content = content)
}

/** Rounded card container that groups related rows together. */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
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
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconTint.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Row of segmented choices (theme, grid size, start page, ...). */
@Composable
private fun <T> SettingsChoiceRow(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (label, value) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExcludedFoldersDialog(
    excludedFolders: Set<String>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(horizontal = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Excluded Folders",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Hidden from Recents and Categories",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            if (excludedFolders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.VisibilityOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No folders excluded yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(excludedFolders.sorted()) { folder ->
                        val rootPath = Environment.getExternalStorageDirectory().absolutePath
                        val displayPath = folder.replace(rootPath, "Internal Storage")
                        
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        folder.split("/").last(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        displayPath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onRemove(folder) },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Folder to Exclude", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerDialog(
    onDismiss: () -> Unit,
    onFolderSelected: (File) -> Unit
) {
    val rootDir = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(rootDir) }
    
    val vm = (LocalContext.current as? androidx.activity.ComponentActivity)?.let { (it as? MainActivity)?.viewModel }

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
                        Text(
                            if (currentDir == rootDir) "Internal storage" else currentDir.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (currentDir != rootDir) {
                                currentDir.parentFile?.let { currentDir = it }
                            } else {
                                onDismiss()
                            }
                        }) {
                            Icon(
                                if (currentDir != rootDir) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        if (currentDir != rootDir) {
                            IconButton(onClick = { currentDir = rootDir }) {
                                Icon(Icons.Default.Home, contentDescription = "Go to Root")
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
                            onClick = { onFolderSelected(currentDir) },
                            enabled = currentDir != rootDir,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Check, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Exclude Current Folder", style = MaterialTheme.typography.titleMedium)
                        }
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
                val animatedItemKeys = remember(currentDir) { mutableStateSetOf<String>() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    itemsIndexed(subDirs, key = { _, item -> item.file.absolutePath }) { index, item ->
                        FolderPickerRow(
                            item = item,
                            index = index,
                            scrollResetKey = currentDir,
                            hasAnimatedBefore = animatedItemKeys.contains(item.file.absolutePath),
                            onAnimationStart = { animatedItemKeys.add(item.file.absolutePath) },
                            viewModel = vm,
                            onClick = { currentDir = item.file }
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
            Text(
                item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            val count = item.itemCount ?: 0
            val dateStr = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(item.lastModified))
            Text("$count items | $dateStr")
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
