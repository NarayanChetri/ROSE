package dev.narayan.rose

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    SettingsSwitchRow(
                        icon = Icons.Default.HorizontalRule,
                        title = "Show list dividers",
                        subtitle = "Thin lines between items in Recent files, Quick access, and folder lists",
                        checked = viewModel.showListDividers,
                        onCheckedChange = { viewModel.setShowListDividers(it) }
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