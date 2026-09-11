package dev.narayan.rose

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveAsScreen(
    viewModel: RoseViewModel,
    uris: List<Uri>,
    isZip: Boolean,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
    onArchiveView: (Uri) -> Unit = {}
) {
    val rootDir = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(rootDir) }
    val context = LocalContext.current
    
    // Initial state: show options menu if it's a single ZIP file
    var showOptions by remember { mutableStateOf(isZip && uris.size == 1) }

    val items = remember(currentDir) {
        currentDir.listFiles { f -> !f.name.startsWith(".") && f.isDirectory }
            ?.map { FileItem(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    BackHandler {
        if (showOptions) {
            onDismiss()
        } else if (currentDir != rootDir) {
            currentDir = currentDir.parentFile ?: rootDir
        } else if (isZip && uris.size == 1) {
            showOptions = true
        } else {
            onDismiss()
        }
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                if (isZip) stringResource(R.string.save_as_archive_title) else stringResource(R.string.save_as_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                            }
                        }
                    )
                    Breadcrumbs(path = currentDir.absolutePath, onNavigate = { currentDir = it })
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            if (!showOptions) {
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = Color(0xFF1C1B1F),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp)
                        .fillMaxWidth()
                ) {
                    SaveAsOption(
                        icon = Icons.Default.Save,
                        title = stringResource(R.string.save_as_save_here),
                        subtitle = null,
                        onClick = {
                            viewModel.saveSharedFiles(uris, currentDir.absolutePath) { success ->
                                if (success) {
                                    onSaved(currentDir.absolutePath)
                                } else {
                                    android.widget.Toast.makeText(context, context.getString(R.string.save_as_failed_to_save), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Folder List for Navigation
            Column(modifier = Modifier.fillMaxSize()) {
                if (items.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.save_as_no_folders_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 88.dp, top = 8.dp)
                    ) {
                        itemsIndexed(items, key = { _, item -> item.file.absolutePath }) { index, fileItem ->
                            FileListItem(
                                fileItem = fileItem,
                                isSelected = false,
                                showDetails = viewModel.showDetails,
                                isVirtual = false,
                                onClick = {
                                    if (fileItem.isDirectory) {
                                        currentDir = fileItem.file
                                    }
                                },
                                onLongClick = { },
                                onDelete = { },
                                onRename = { _ -> },
                                onShare = { },
                                onCopy = { },
                                onCut = { },
                                onProperties = { },
                                viewModel = viewModel,
                                index = index,
                                scrollResetKey = currentDir,
                                hasAnimatedBefore = true,
                                isDividerVisible = viewModel.showListDividers && index != items.lastIndex
                            )
                        }
                    }
                }
            }

            // Choice Menu Overlay
            AnimatedVisibility(
                visible = showOptions,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(enabled = false) {}
                ) {
                    Surface(
                        shape = RoundedCornerShape(32.dp),
                        color = Color(0xFF1C1B1F),
                        modifier = Modifier
                            .padding(24.dp)
                            .align(Alignment.BottomCenter)
                            .widthIn(max = 400.dp)
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(top = 12.dp, bottom = 24.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Handle
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.2f))
                            )
                            Spacer(modifier = Modifier.height(20.dp))

                            SaveAsOption(
                                icon = Icons.Default.Search,
                                title = stringResource(R.string.save_as_archive_viewer),
                                subtitle = stringResource(R.string.save_as_archive_viewer_subtitle),
                                onClick = { onArchiveView(uris[0]) }
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                color = Color.White.copy(alpha = 0.05f),
                                thickness = 1.dp
                            )

                            SaveAsOption(
                                icon = Icons.Default.FileDownload,
                                title = stringResource(R.string.save_as_save_as),
                                subtitle = stringResource(R.string.save_as_save_as_subtitle),
                                onClick = { showOptions = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveAsOption(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF2B2D31),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color(0xFF4C8DFF).copy(alpha = 0.3f),
                    modifier = Modifier.size(32.dp)
                )
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color(0xFF4C8DFF),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color.Gray.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}
