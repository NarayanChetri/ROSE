package dev.narayan.rose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import android.webkit.MimeTypeMap
import java.io.File
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.narayan.rose.filejob.JobManager
import dev.narayan.rose.ui.theme.RoseTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    internal val viewModel: RoseViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }

        if (storageGranted) {
            recreate()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is recommended for background tasks", Toast.LENGTH_SHORT).show()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                recreate()
            } else {
                Toast.makeText(this, "Manage Storage permission is required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = viewModel.pendingSafPath ?: viewModel.currentPath
        viewModel.clearPendingSaf() // Clear it immediately

        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    viewModel.onSafResult(true, path)
                } catch (e: Exception) {
                    viewModel.onSafResult(false, path)
                }
            } else {
                viewModel.onSafResult(false, path)
            }
        } else {
            viewModel.onSafResult(false, path)
        }
    }

    private var pendingStorageUri by mutableStateOf<Uri?>(null)

    private val addStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    pendingStorageUri = uri
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to add storage: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var sharedUris by mutableStateOf<List<Uri>?>(null)

    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            val path = viewModel.pendingShizukuPath ?: viewModel.currentPath
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            viewModel.onShizukuResult(granted, path)
            if (!granted) {
                // If Shizuku denied, fallback to SAF
                viewModel.retrySaf(path)
            }
        }
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        val currentPath = viewModel.currentPath
        if (SafManager.isRestrictedPath(currentPath)) {
            viewModel.loadFiles(currentPath, showLoading = false)
        }
    }

    private var pendingArchiveUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        checkPermissions()
        handleIntent(intent)

        viewModel.onRequestAddStorage = {
            addStorageLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra("android.provider.extra.SHOW_ADVANCED", true)
            })
        }

        setContent {
            RoseTheme(
                themeMode = viewModel.themeMode,
                amoled = viewModel.amoledMode,
                dynamicColor = viewModel.dynamicColorEnabled
            ) {
                // Asking for notification permission the instant the app opens - before the
                // user has even seen a file - reads as generic OS boilerplate and has nothing
                // to anchor the "why" to. Instead, ask contextually: the first time a
                // background job actually needs a notification. That's downloads only -
                // FileJobService already posts Delete/Copy/Move/Recycle/Restore jobs to a
                // silent channel with no visible notification (see isDownload there), so
                // asking for the permission for those jobs has nothing to show the user even
                // if granted. Gating on `is FileJobType.Download` here, not just
                // `activeJobs.isNotEmpty()`, is what keeps the primer from popping up on
                // every delete/copy/paste.
                val activeJobs by JobManager.activeJobs.collectAsState()
                val hasActiveDownload = activeJobs.values.any { it.type is dev.narayan.rose.filejob.FileJobType.Download }
                var showNotificationPrimer by remember { mutableStateOf(false) }

                // Delete, Recycle and Restore are near-instant (typically just a file move
                // or metadata update), so showing the full detail dialog or mini bar
                // (built for long transfers) just flashes them open and shut. Split them
                // out into a tiny, cosmetic center spinner instead.
                val fastJobs = remember(activeJobs) {
                    activeJobs.values.filter {
                        it.type is dev.narayan.rose.filejob.FileJobType.Delete ||
                                it.type is dev.narayan.rose.filejob.FileJobType.Recycle ||
                                it.type is dev.narayan.rose.filejob.FileJobType.Restore
                    }
                }
                val otherJobs = remember(activeJobs) {
                    activeJobs.values.filter {
                        it.type !is dev.narayan.rose.filejob.FileJobType.Delete &&
                                it.type !is dev.narayan.rose.filejob.FileJobType.Recycle &&
                                it.type !is dev.narayan.rose.filejob.FileJobType.Restore
                    }
                }

                // Lives at the Activity level (not inside FileExplorerScreen) so the mini
                // progress bar keeps showing no matter which screen is on top - Home,
                // Files, Recycle Bin, etc. - for as long as a job is running, instead of
                // vanishing the moment the user navigates away from All Files.
                var showJobDetailsDialog by remember { mutableStateOf(false) }
                LaunchedEffect(otherJobs.isNotEmpty()) {
                    if (otherJobs.isNotEmpty()) showJobDetailsDialog = true
                }

                LaunchedEffect(hasActiveDownload) {
                    if (hasActiveDownload &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !viewModel.notificationPrimerShown &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.markNotificationPrimerShown()
                        showNotificationPrimer = true
                    }
                }

                if (showNotificationPrimer) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val coroutineScope = rememberCoroutineScope()

                    fun dismissPrimer(onDismissed: () -> Unit = {}) {
                        coroutineScope.launch {
                            sheetState.hide()
                            showNotificationPrimer = false
                            onDismissed()
                        }
                    }

                    ModalBottomSheet(
                        onDismissRequest = { showNotificationPrimer = false },
                        sheetState = sheetState,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(bottom = 32.dp, top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        androidx.compose.foundation.shape.CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                "Stay updated on transfers",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Rose can notify you with live progress while a file downloads in the background - so you always know when it's saved offline.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    dismissPrimer {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) { Text("Allow notifications") }
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = { dismissPrimer() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Not now") }
                        }
                    }
                }

                // Handle SAF requests
                LaunchedEffect(viewModel.pendingSafPath) {
                    viewModel.pendingSafPath?.let { path ->
                        val intent = SafManager.requestPermission(this@MainActivity, path)
                        if (intent != null) {
                            safLauncher.launch(intent)
                        }
                    }
                }

                // Handle Shizuku requests
                LaunchedEffect(viewModel.pendingShizukuPath) {
                    viewModel.pendingShizukuPath?.let { path ->
                        if (ShizukuManager.isAvailable()) {
                            if (ShizukuManager.hasPermission()) {
                                viewModel.onShizukuResult(true, path)
                            } else {
                                try {
                                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                                } catch (e: Exception) {
                                    viewModel.onShizukuResult(false, path)
                                    viewModel.retrySaf(path)
                                }
                            }
                        } else {
                            viewModel.onShizukuResult(false, path)
                            viewModel.retrySaf(path)
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Hoisted scroll states to ensure they survive screen transitions (Home <-> Files)
                    // and Activity re-compositions. Moved outside the permission block to be absolutely stable.
                    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    val recycleBinListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    val explorerListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    val explorerGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

                    if (hasStoragePermission()) {
                        var screen by remember {
                            mutableStateOf<AppScreen>(
                                if (viewModel.startPage == StartPage.ALL_FILES) {
                                    AppScreen.Files(startPath = Environment.getExternalStorageDirectory().absolutePath, fromHome = true)
                                } else {
                                    AppScreen.Home
                                }
                            )
                        }

                        // Handle viewed archive
                        LaunchedEffect(pendingArchiveUri) {
                            pendingArchiveUri?.let { uri ->
                                val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
                                val isArchive = isArchiveUri(uri) || extension in archiveExtensions

                                if (isArchive) {
                                    viewModel.resetFiles()
                                    // For external URIs, we need to copy to a temp file because
                                    // java.util.zip.ZipFile requires a File object (path), not a Stream.
                                    try {
                                        val tempFile = File(cacheDir, "view_archive_${System.currentTimeMillis()}.${if (extension.isEmpty()) "zip" else extension}")
                                        contentResolver.openInputStream(uri)?.use { input ->
                                            tempFile.outputStream().use { output -> input.copyTo(output) }
                                        }
                                        screen = AppScreen.Files(startPath = tempFile.absolutePath, fromHome = true)
                                    } catch (e: Exception) {
                                        Toast.makeText(this@MainActivity, "Failed to open archive: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                pendingArchiveUri = null
                            }
                        }

                        // Handle shared files
                        LaunchedEffect(sharedUris) {
                            sharedUris?.let { uris ->
                                if (uris.isNotEmpty()) {
                                    // isZip is a legacy name kept to avoid touching the
                                    // SaveAsScreen/AppScreen.SaveAs signatures - it really
                                    // means "is a single archive we can preview" and now
                                    // covers rar/7z/tar/etc, not just zip. See isArchiveUri().
                                    val isZip = uris.size == 1 && isArchiveUri(uris[0])
                                    screen = AppScreen.SaveAs(uris, isZip)
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            SharedTransitionLayout {
                                AnimatedContent(
                                    targetState = screen,
                                    transitionSpec = {
                                        val target = targetState
                                        when (target) {
                                            is AppScreen.Files -> {
                                                if (target.isFromAllFiles) {
                                                    // Snappier transition for All Files
                                                    (fadeIn(tween(250, easing = FastOutSlowInEasing)) +
                                                            slideInVertically(tween(250, easing = FastOutSlowInEasing)) { it / 20 })
                                                        .togetherWith(fadeOut(tween(150, easing = FastOutSlowInEasing)))
                                                        .using(SizeTransform(clip = false))
                                                } else {
                                                    (fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                                                            scaleIn(initialScale = 0.96f, animationSpec = tween(200, easing = FastOutSlowInEasing)))
                                                        .togetherWith(fadeOut(tween(100, easing = FastOutSlowInEasing)))
                                                        .using(SizeTransform(clip = false))
                                                }
                                            }
                                            is AppScreen.Home, is AppScreen.RecycleBin -> {
                                                (fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                                                        scaleIn(initialScale = 0.97f, animationSpec = tween(160, easing = FastOutSlowInEasing)))
                                                    .togetherWith(fadeOut(tween(90, easing = FastOutSlowInEasing)))
                                                    .using(SizeTransform(clip = false))
                                            }
                                            is AppScreen.SaveAs -> {
                                                (slideInVertically(animationSpec = tween(280, easing = FastOutSlowInEasing)) { it } + fadeIn())
                                                    .togetherWith(slideOutVertically(animationSpec = tween(200)) { it } + fadeOut())
                                            }
                                            else -> fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                                        }
                                    },
                                    label = "ScreenTransition",
                                    modifier = Modifier.fillMaxSize()
                                )
                                { currentScreen ->
                                    // Naming dialog for new external storage
                                    pendingStorageUri?.let { uri ->
                                        val initialName = if (uri.authority?.contains("com.google.android.apps.docs") == true) {
                                            "Google Drive"
                                        } else {
                                            // Try to get a meaningful name from the URI if possible
                                            val path = uri.path ?: ""
                                            if (path.contains(":")) {
                                                path.substringAfterLast(":")
                                            } else {
                                                "External Storage"
                                            }
                                        }
                                        RenameDialog(
                                            initialName = initialName,
                                            onDismiss = { pendingStorageUri = null },
                                            onRename = { name ->
                                                viewModel.addExternalStorage(name, uri)
                                                pendingStorageUri = null
                                            }
                                        )
                                    }

                                    viewModel.passphrasePromptItem?.let { fileItem ->
                                        PasswordDialog(
                                            onDismiss = {
                                                viewModel.passphrasePromptItem = null
                                                viewModel.passphraseAction = null
                                            },
                                            onConfirm = { passphrase ->
                                                val action = viewModel.passphraseAction
                                                viewModel.passphrasePromptItem = null
                                                viewModel.passphraseAction = null
                                                if (action != null) {
                                                    action(passphrase)
                                                } else {
                                                    openFile(fileItem, passphrase)
                                                }
                                            }
                                        )
                                    }

                                    when (currentScreen) {
                                        is AppScreen.Home -> HomeScreen(
                                            viewModel = viewModel,
                                            listState = homeListState,
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this@AnimatedContent,
                                            onOpenPath = { path, fileToHighlight ->
                                                val isFromAllFiles = path == Environment.getExternalStorageDirectory().absolutePath && fileToHighlight == null
                                                // Always reset - this only clears category/zip metadata
                                                // (categoryTitle, categoryFilterType, currentZipFile,
                                                // categoryFiles), never `files`/rootCache, so it can't
                                                // cause a "blank flash" here. Skipping it for All Files
                                                // used to leave a stale categoryFilterType set (from a
                                                // previously-browsed category still hanging around this
                                                // session) which made setSortOrder() silently re-sort the
                                                // invisible categoryFiles list instead of the visible one
                                                // - "sort does nothing in All Files" until a manual
                                                // refresh, which reloads through a path that ignores
                                                // categoryFilterType entirely.
                                                viewModel.resetFiles()
                                                screen = AppScreen.Files(
                                                    startPath = path,
                                                    fromHome = true,
                                                    highlightFile = fileToHighlight,
                                                    isFromAllFiles = isFromAllFiles
                                                )
                                            },
                                            onOpenCategory = { type, title ->
                                                viewModel.resetFiles()
                                                screen = AppScreen.Files(category = type to title)
                                            },
                                            onOpenRecent = {
                                                viewModel.resetFiles()
                                                screen = AppScreen.Files(recent = true)
                                            },
                                            onFileClick = { fileItem ->
                                                if (fileItem.fileType == FileType.ZIP) {
                                                    viewModel.resetFiles()
                                                    screen = AppScreen.Files(
                                                        startPath = fileItem.file.absolutePath,
                                                        fromHome = true
                                                    )
                                                } else {
                                                    openFile(fileItem)
                                                }
                                            },
                                            onOpenWithClick = { openFileWith(it) },
                                            onShareClick = { fileItem -> shareFiles(listOf(fileItem)) },
                                            onSettingsClick = { screen = AppScreen.Settings },
                                            onAboutClick = { screen = AppScreen.About },
                                            onRecycleBinClick = { screen = AppScreen.RecycleBin }
                                        )
                                        is AppScreen.Files -> FileExplorerScreen(
                                            viewModel = viewModel,
                                            startPath = currentScreen.startPath,
                                            startCategory = currentScreen.category,
                                            startRecent = currentScreen.recent,
                                            fromHome = currentScreen.fromHome,
                                            highlightFile = currentScreen.highlightFile,
                                            isFromAllFiles = currentScreen.isFromAllFiles,
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this@AnimatedContent,
                                            onExitToHome = {
                                                if (sharedUris != null) {
                                                    screen = AppScreen.SaveAs(sharedUris!!, true)
                                                } else {
                                                    screen = AppScreen.Home
                                                }
                                            },
                                            onFileClick = { fileItem -> openFile(fileItem) },
                                            onOpenWithClick = { openFileWith(it) },
                                            onShareClick = { fileItems -> shareFiles(fileItems) },
                                            listState = explorerListState,
                                            gridState = explorerGridState
                                        )
                                        is AppScreen.RecycleBin -> RecycleBinScreen(
                                            onBack = { screen = AppScreen.Home },
                                            viewModel = viewModel,
                                            listState = recycleBinListState
                                        )
                                        is AppScreen.SaveAs -> SaveAsScreen(
                                            viewModel = viewModel,
                                            uris = currentScreen.uris,
                                            isZip = currentScreen.isZip,
                                            onDismiss = { screen = AppScreen.Home; sharedUris = null },
                                            onSaved = { destPath ->
                                                screen = AppScreen.Files(startPath = destPath, fromHome = true)
                                                sharedUris = null
                                            },
                                            onArchiveView = { uri ->
                                                viewModel.resetFiles()
                                                screen = AppScreen.Files(startPath = uri.toString(), fromHome = true)
                                                // Keep sharedUris so we can come back
                                            }
                                        )
                                        AppScreen.Settings -> SettingsScreen(
                                            viewModel = viewModel,
                                            onBack = { screen = AppScreen.Home }
                                        )
                                        AppScreen.About -> AboutScreen(
                                            viewModel = viewModel,
                                            onBack = { screen = AppScreen.Home }
                                        )
                                    }
                                }
                            }

                            // Global mini bar + detailed dialog for in-flight jobs - lives
                            // above the AnimatedContent so it survives Home <-> Files <->
                            // Recycle Bin navigation instead of resetting per screen.
                            // Delete jobs are handled separately below (see deleteJobs).
                            if (otherJobs.isNotEmpty() && !showJobDetailsDialog) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(16.dp)
                                ) {
                                    ActiveJobsCard(
                                        activeJobs = otherJobs,
                                        onClick = { showJobDetailsDialog = true }
                                    )
                                }
                            }
                            if (showJobDetailsDialog && otherJobs.isNotEmpty()) {
                                dev.narayan.rose.filejob.FileJobProgressDialog(
                                    activeJobs = otherJobs,
                                    onDismissRequest = { showJobDetailsDialog = false }
                                )
                            }

                            // Fast jobs (Delete/Recycle/Restore): no dialog, no mini bar - just
                            // a small centered spinner that fades in/out, since they are fast
                            // enough that the full progress UI would only ever flash.
                            androidx.compose.animation.AnimatedVisibility(
                                visible = fastJobs.isNotEmpty(),
                                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)),
                                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
                                modifier = Modifier.align(Alignment.Center)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shadowElevation = 8.dp
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.5.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        val jobText = when (fastJobs.firstOrNull()?.type) {
                                            is dev.narayan.rose.filejob.FileJobType.Recycle -> "Moving to bin…"
                                            is dev.narayan.rose.filejob.FileJobType.Restore -> "Restoring…"
                                            else -> "Deleting…"
                                        }
                                        Text(jobText, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    } else {
                        PermissionPlaceholder()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    pendingArchiveUri = uri
                }
            }
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { sharedUris = listOf(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris?.let { sharedUris = it }
            }
            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                // The user tapped ROSE in the Android USB app-chooser dialog.
                // Tell the ViewModel to automatically navigate to the first
                // USB storage device it sees once Android finishes mounting it.
                viewModel.handleUsbDeviceAttached()
            }
        }
    }

    private val archiveExtensions = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
    private val archiveMimeTypes = listOf(
        "application/zip", "application/x-zip-compressed",
        "application/x-7z-compressed", "application/x-rar-compressed", "application/vnd.rar",
        "application/x-tar", "application/gzip", "application/x-bzip2", "application/x-xz"
    )

    // Content Uris shared from other apps (WhatsApp, Gmail, etc.) almost never have a
    // real file extension in their path, so MimeTypeMap.getFileExtensionFromUrl(uri)
    // alone silently returns "" for them - this was why sharing a .rar/.7z (and often
    // even a .zip) here used to skip straight to "Save to..." instead of offering
    // Archive viewer/Save as. Cross-check the resolver's reported MIME type and the
    // provider's DISPLAY_NAME column, which reflect the real file name/type.
    private fun isArchiveUri(uri: Uri): Boolean {
        val mimeType = contentResolver.getType(uri)
        if (mimeType in archiveMimeTypes) return true

        val urlExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
        if (urlExtension in archiveExtensions) return true

        val displayName = try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
        val nameExtension = displayName?.substringAfterLast('.', "")?.lowercase()
        return nameExtension != null && nameExtension.isNotEmpty() && nameExtension in archiveExtensions
    }

    private fun shareFiles(fileItems: List<FileItem>, passphrase: String? = null) {
        if (fileItems.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val uris = ArrayList<Uri>()
            var folderFound = false

            for (item in fileItems) {
                if (item.virtualZipSource != null) {
                    if (item.isDirectory) {
                        folderFound = true
                        continue
                    }

                    val cached = viewModel.cachedZipPassword
                    val effectivePassphrase = passphrase ?: cached

                    if (item.isEncrypted && effectivePassphrase == null) {
                        withContext(Dispatchers.Main) {
                            viewModel.passphrasePromptItem = item
                            viewModel.passphraseAction = { pw -> shareFiles(fileItems, pw) }
                        }
                        return@launch
                    }

                    // Extract virtual file to cache before sharing
                    val cacheFile = File(cacheDir, "share_${System.currentTimeMillis()}_${item.name}")
                    try {
                        cacheFile.outputStream().use { output ->
                            ArchiveManager.extractEntry(item.virtualZipSource, item.zipEntryPath ?: item.name, output, effectivePassphrase)
                        }
                        if (effectivePassphrase != null) {
                            withContext(Dispatchers.Main) {
                                viewModel.cachedZipPassword = effectivePassphrase
                            }
                        }
                        uris.add(FileProvider.getUriForFile(this@MainActivity, "${packageName}.provider", cacheFile))
                    } catch (e: Exception) {
                        if (e.message?.contains("Password required") == true || e.message?.contains("Invalid password") == true) {
                            withContext(Dispatchers.Main) {
                                viewModel.cachedZipPassword = null
                                viewModel.passphrasePromptItem = item
                                viewModel.passphraseAction = { pw -> shareFiles(fileItems, pw) }
                            }
                            return@launch
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Failed to extract ${item.name}: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
else {
                    val path = item.file.absolutePath
                    if (SafManager.isRestrictedPath(path)) {
                        // Files here don't exist from java.io.File's point of view - only the
                        // SAF DocumentFile's own content:// Uri can be opened by another app.
                        SafManager.getContentUri(this@MainActivity, path)?.let { uris.add(it) } ?: run { folderFound = true }
                    } else if (item.file.isFile) {
                        uris.add(FileProvider.getUriForFile(
                            this@MainActivity,
                            "${applicationContext.packageName}.provider",
                            item.file
                        ))
                    } else {
                        folderFound = true
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (uris.isEmpty()) {
                    if (folderFound) {
                        Toast.makeText(this@MainActivity, "Folders cannot be shared directly", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }

                val intent = Intent().apply {
                    action = if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
                    if (uris.size > 1) {
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    } else {
                        putExtra(Intent.EXTRA_STREAM, uris[0])
                    }
                    type = if (uris.size > 1) "*/*" else {
                        val extension = MimeTypeMap.getFileExtensionFromUrl(uris[0].toString())
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share via"))
            }
        }
    }

    private fun openFileWith(fileItem: FileItem) {
        val intent = createViewIntent(fileItem) ?: return
        try {
            val chooser = Intent.createChooser(intent, "Open with")
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createViewIntent(fileItem: FileItem, passphrase: String? = null): Intent? {
        try {
            val file = fileItem.file
            val path = file.absolutePath
            val restricted = SafManager.isRestrictedPath(path)
            val isVirtual = fileItem.virtualZipSource != null

            if (!isVirtual) {
                if (restricted && !SafManager.exists(this, path)) {
                    Toast.makeText(this, "File no longer exists", Toast.LENGTH_SHORT).show()
                    return null
                }
                if (!restricted && !file.exists()) {
                    Toast.makeText(this, "File no longer exists", Toast.LENGTH_SHORT).show()
                    return null
                }
            }

            val uri = if (isVirtual) {
                val cached = viewModel.cachedZipPassword
                val effectivePassphrase = passphrase ?: cached

                if (fileItem.isEncrypted && effectivePassphrase == null) {
                    viewModel.passphrasePromptItem = fileItem
                    viewModel.passphraseAction = { pw -> openFile(fileItem, pw) }
                    return null
                }

                val cacheFile = File(cacheDir, "temp_open_${fileItem.name}")
                try {
                    cacheFile.outputStream().use { output ->
                        ArchiveManager.extractEntry(fileItem.virtualZipSource!!, fileItem.zipEntryPath ?: fileItem.name, output, effectivePassphrase)
                    }
                    if (effectivePassphrase != null) {
                        viewModel.cachedZipPassword = effectivePassphrase
                    }
                    FileProvider.getUriForFile(this, "${packageName}.provider", cacheFile)
                } catch (e: Exception) {
                    if (e.message?.contains("Password required") == true || e.message?.contains("Invalid password") == true) {
                        viewModel.cachedZipPassword = null
                        viewModel.passphrasePromptItem = fileItem
                        viewModel.passphraseAction = { pw -> openFile(fileItem, pw) }
                    } else {
                        Toast.makeText(this, "Failed to extract file: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    return null
                }
            }
else if (restricted) {
                SafManager.getContentUri(this, path) ?: run {
                    Toast.makeText(this, "Couldn't access file", Toast.LENGTH_SHORT).show()
                    return null
                }
            } else {
                FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.provider",
                    file
                )
            }

            if (fileItem.fileType == FileType.APK) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!packageManager.canRequestPackageInstalls()) {
                        val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(settingsIntent)
                        Toast.makeText(this, "Please allow installation from this source", Toast.LENGTH_LONG).show()
                        return null
                    }
                }

                return Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            val intent = Intent(Intent.ACTION_VIEW)
            var type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileItem.extension)

            if (type == null) {
                type = contentResolver.getType(uri)
            }

            if (type == null || type == "*/*") {
                type = java.net.URLConnection.guessContentTypeFromName(fileItem.name)
            }

            if (fileItem.fileType == FileType.VIDEO || fileItem.fileType == FileType.AUDIO || fileItem.fileType == FileType.IMAGE) {
                viewModel.getLocalOfflineFile(fileItem)?.let { localFile ->
                    val localUri = FileProvider.getUriForFile(
                        this,
                        "${applicationContext.packageName}.provider",
                        localFile
                    )

                    val offlineIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(localUri, type ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    // Try returning this if we have it, or let the caller decide
                    // For now, openFile handles it differently
                }
            }

            intent.setDataAndType(uri, type ?: "*/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            return intent
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't prepare intent: ${e.message}", Toast.LENGTH_SHORT).show()
            return null
        }
    }

    private fun openFile(fileItem: FileItem, passphrase: String? = null) {
        val intent = createViewIntent(fileItem, passphrase) ?: return
        try {
            // Check for offline file first (simplified from previous implementation)
            if (fileItem.fileType == FileType.VIDEO || fileItem.fileType == FileType.AUDIO || fileItem.fileType == FileType.IMAGE) {
                viewModel.getLocalOfflineFile(fileItem)?.let { localFile ->
                    val localUri = FileProvider.getUriForFile(this, "${packageName}.provider", localFile)
                    val type = contentResolver.getType(localUri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileItem.extension)
                    val offlineIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(localUri, type ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(offlineIntent)
                        return
                    } catch (e: Exception) {}
                }
            }

            try {
                startActivity(intent)
            } catch (e: Exception) {
                val chooser = Intent.createChooser(intent, "Open with")
                startActivity(chooser)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }


    internal fun openRecycledFile(item: RecycledItem) {
        val file = RecycleBinManager.getRecycledFile(this, item)
        if (!file.exists()) {
            Toast.makeText(this, "File not found in recycle bin", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)

        // Use the original name to get the correct MIME type
        val extension = item.originalName.substringAfterLast('.', "").lowercase()
        var type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

        if (type == null) {
            type = contentResolver.getType(uri)
        }

        if (type == null) {
            type = when (extension) {
                "mp4", "mkv", "avi", "mov" -> "video/*"
                "mp3", "wav", "ogg", "flac" -> "audio/*"
                "jpg", "jpeg", "png", "gif", "webp" -> "image/*"
                "pdf" -> "application/pdf"
                else -> "*/*"
            }
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            val chooser = Intent.createChooser(intent, "Open with")
            startActivity(chooser)
        }
    }

    // -------------------------------------------------------------------------
    // USB / SD hot-plug: listen for MEDIA_* broadcasts while the app is
    // in the foreground (onStart → onStop window). Registration is done
    // programmatically (not in the manifest) so we only receive events
    // while the Activity is visible — no wasted work in the background.
    // -------------------------------------------------------------------------
    private val storageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            intent ?: return
            // Extract the filesystem path from the intent's data URI
            // (e.g. "file:///storage/xxxx-xxxx" → "/storage/xxxx-xxxx")
            val path = intent.data?.path ?: ""
            when (intent.action) {
                Intent.ACTION_MEDIA_MOUNTED -> {
                    // New volume appeared (USB inserted or SD card mounted)
                    viewModel.loadStorageDevices()
                }
                Intent.ACTION_MEDIA_REMOVED,
                Intent.ACTION_MEDIA_UNMOUNTED,
                Intent.ACTION_MEDIA_EJECT,
                Intent.ACTION_MEDIA_BAD_REMOVAL -> {
                    // Volume gone — refresh list and navigate away if we are inside it
                    if (path.isNotEmpty()) {
                        viewModel.onStorageEjected(path)
                    } else {
                        viewModel.loadStorageDevices()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            // Required for all MEDIA_* intents — without this the receiver
            // never fires even when the action string matches.
            addDataScheme("file")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(storageReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(storageReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(storageReceiver) } catch (e: IllegalArgumentException) { /* already unregistered */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
    }


    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    manageStorageLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStorageLauncher.launch(intent)
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }
}

@Composable
fun PermissionPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Waiting for storage permissions...")
    }
}

private sealed class AppScreen {
    object Home : AppScreen()
    data class Files(
        val startPath: String? = null,
        val category: Pair<FileType, String>? = null,
        val recent: Boolean = false,
        val fromHome: Boolean = false,
        val highlightFile: FileItem? = null,
        val isFromAllFiles: Boolean = false
    ) : AppScreen()
    data class SaveAs(
        val uris: List<Uri>,
        val isZip: Boolean = false
    ) : AppScreen()
    object Settings : AppScreen()
    object About : AppScreen()
    object RecycleBin : AppScreen()
}