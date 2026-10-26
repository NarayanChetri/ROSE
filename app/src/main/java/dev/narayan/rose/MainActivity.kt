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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import dev.narayan.rose.document.DocumentMode
import dev.narayan.rose.document.DocumentScreen
import dev.narayan.rose.document.DocumentSource
import dev.narayan.rose.update.UpdateBottomSheet
import dev.narayan.rose.ftpserver.FtpServerScreen
import dev.narayan.rose.ftpserver.FtpServerNotification
import dev.narayan.rose.ui.TintedGlassNavigationBar

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
class MainActivity : androidx.fragment.app.FragmentActivity() {

    internal val viewModel: RoseViewModel by viewModels()

    private val tempOpenedFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()

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
            Toast.makeText(this, getString(R.string.toast_notification_permission_recommended), Toast.LENGTH_SHORT).show()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                recreate()
            } else {
                Toast.makeText(this, getString(R.string.toast_manage_storage_required), Toast.LENGTH_LONG).show()
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

    private var sharedUris by mutableStateOf<List<Uri>?>(null)

    private val SHIZUKU_PERMISSION_REQUEST_CODE = 1001

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            val path = viewModel.pendingShizukuPath ?: viewModel.currentPath
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            if (granted) {
                ShizukuManager.bindUserService(this@MainActivity)
            }
            viewModel.onShizukuResult(granted, path)
            if (!granted && !viewModel.isShizukuRestrictedPath(path)) {
                // If Shizuku denied, fallback to SAF only for non-Android/data paths
                viewModel.retrySaf(path)
            }
        }
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        if (ShizukuManager.hasPermission()) {
            ShizukuManager.bindUserService(this@MainActivity)
        }
        val currentPath = viewModel.currentPath
        if (SafManager.isRestrictedPath(currentPath)) {
            viewModel.loadFiles(currentPath, showLoading = false)
        }
        if (viewModel.isShizukuRestrictedPath(currentPath) && ShizukuManager.hasPermission()) {
            viewModel.loadFiles(currentPath, showLoading = true)
        }
    }

    private var pendingArchiveUri by mutableStateOf<Uri?>(null)
    private var pendingDocument by mutableStateOf<Pair<DocumentSource, Boolean>?>(null)
    private var pendingFtpNavigation by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ShizukuManager.init(this)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        checkPermissions()
        handleIntent(intent)

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
                val coroutineScope = rememberCoroutineScope()

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
                                stringResource(R.string.notif_primer_title),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.notif_primer_description),
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
                            ) { Text(stringResource(R.string.action_allow_notifications)) }
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = { dismissPrimer() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.action_not_now)) }
                        }
                    }
                }

                // Automatic & manual update bottom sheet
                val updateCheckResult = viewModel.updateCheckResult
                if (updateCheckResult is UpdateCheckResult.UpdateAvailable) {
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    val context = androidx.compose.ui.platform.LocalContext.current
                    UpdateBottomSheet(
                        info = updateCheckResult.info,
                        downloadState = viewModel.updateDownloadState,
                        autoCheckEnabled = viewModel.autoCheckUpdates,
                        onAutoCheckToggle = { viewModel.setAutoCheckUpdates(it) },
                        onDismiss = {
                            viewModel.dismissUpdateSheet()
                        },
                        onUpdate = {
                            if (updateCheckResult.info.apkUrl != null) {
                                viewModel.downloadAndInstallUpdate(updateCheckResult.info)
                            } else {
                                uriHandler.openUri(updateCheckResult.info.downloadUrl)
                                viewModel.dismissUpdateSheet()
                            }
                        },
                        onInstallClick = {
                            viewModel.installDownloadedUpdate()
                        },
                        onSkipVersion = {
                            viewModel.skipVersion(updateCheckResult.info.tagName)
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_skipped_version_info),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
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
                                    if (!viewModel.isShizukuRestrictedPath(path)) {
                                        viewModel.retrySaf(path)
                                    }
                                }
                            }
                        } else {
                            viewModel.onShizukuResult(false, path)
                            if (!viewModel.isShizukuRestrictedPath(path)) {
                                viewModel.retrySaf(path)
                            }
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
                    val homePagerState = androidx.compose.foundation.pager.rememberPagerState(
                        initialPage = 1,
                        pageCount = { 2 }
                    )
                    var isHomeSearching by remember { mutableStateOf(false) }
                    var isRecentSearching by remember { mutableStateOf(false) }

                    if (hasStoragePermission()) {
                        var screen by remember {
                            mutableStateOf<AppScreen>(
                                if (intent?.getBooleanExtra(FtpServerNotification.EXTRA_OPEN_FTP, false) == true) {
                                    AppScreen.FtpServer
                                } else if (viewModel.startPage == StartPage.ALL_FILES) {
                                    AppScreen.Files(startPath = Environment.getExternalStorageDirectory().absolutePath, fromHome = true)
                                } else {
                                    AppScreen.Home
                                }
                            )
                        }

                        // Handle FTP notification click
                        LaunchedEffect(pendingFtpNavigation) {
                            if (pendingFtpNavigation) {
                                screen = AppScreen.FtpServer
                                pendingFtpNavigation = false
                            }
                        }

                        // Handle auto-navigation (e.g. when USB is attached and user selects ROSE)
                        LaunchedEffect(viewModel.pendingNavigationPath) {
                            viewModel.pendingNavigationPath?.let { path ->
                                viewModel.resetFiles()
                                screen = AppScreen.Files(startPath = path, fromHome = true)
                                viewModel.clearPendingNavigation()
                            }
                        }

                        // Handle viewed archive
                        LaunchedEffect(pendingArchiveUri) {
                            pendingArchiveUri?.let { uri ->
                                val isArchive = isArchiveUri(uri)

                                if (isArchive) {
                                    viewModel.resetFiles()
                                    try {
                                        if (uri.scheme == "file" && uri.path != null && File(uri.path!!).exists()) {
                                            screen = AppScreen.Files(startPath = File(uri.path!!).absolutePath, fromHome = true)
                                        } else {
                                            val displayName = getUriDisplayName(uri) ?: "archive"
                                            val extFromName = displayName.substringAfterLast('.', "").lowercase()
                                            val mime = try { contentResolver.getType(uri) } catch (e: Exception) { null }
                                            val effectiveExt = when {
                                                extFromName in archiveExtensions -> extFromName
                                                mime == "application/vnd.android.package-archive" -> "apk"
                                                mime in listOf("application/zip", "application/x-zip", "application/x-zip-compressed") -> "zip"
                                                else -> "zip"
                                            }

                                            val baseName = if (displayName.contains(".")) displayName.substringBeforeLast('.') else displayName
                                            val sanitizedBase = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(50)
                                            val targetDir = File(cacheDir, "view_archives/${System.currentTimeMillis()}")
                                            targetDir.mkdirs()

                                            cleanupOldViewArchives()

                                            val tempFile = File(targetDir, "$sanitizedBase.$effectiveExt")
                                            contentResolver.openInputStream(uri)?.use { input ->
                                                tempFile.outputStream().use { output -> input.copyTo(output) }
                                            } ?: throw Exception("Could not read file data")

                                            screen = AppScreen.Files(startPath = tempFile.absolutePath, fromHome = true)
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(this@MainActivity, getString(R.string.toast_failed_to_open_archive, e.message ?: ""), Toast.LENGTH_SHORT).show()
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

                        // Handle viewed/edited document (.md or .txt)
                        LaunchedEffect(pendingDocument) {
                            pendingDocument?.let { (source, isMarkdown) ->
                                val backScreen = when (val s = screen) {
                                    is AppScreen.Files -> s.copy(resumePath = viewModel.currentPath)
                                    else -> s
                                }
                                screen = AppScreen.Document(
                                    source = source,
                                    isMarkdown = isMarkdown,
                                    initialMode = DocumentMode.VIEW,
                                    previousScreen = backScreen
                                )
                                pendingDocument = null
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            SharedTransitionLayout {
                                AnimatedContent(
                                    targetState = screen,
                                    modifier = Modifier.fillMaxSize(),
                                    transitionSpec = {
                                        val target = targetState
                                        val initial = initialState
                                        if (initial is AppScreen.Home && target is AppScreen.Files && target.recent) {
                                            (slideInHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(250)))
                                                .togetherWith(slideOutHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)))
                                                .using(SizeTransform(clip = false))
                                        } else if (initial is AppScreen.Files && initial.recent && target is AppScreen.Home) {
                                            (slideInHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(250)))
                                                .togetherWith(slideOutHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(200)))
                                                .using(SizeTransform(clip = false))
                                        } else if (initial is AppScreen.Home && target is AppScreen.Files && target.isFromAllFiles) {
                                            // Exact old All Files capsule animation
                                            (fadeIn(tween(250, easing = FastOutSlowInEasing)) +
                                                    slideInVertically(tween(250, easing = FastOutSlowInEasing)) { it / 20 })
                                                .togetherWith(fadeOut(tween(150, easing = FastOutSlowInEasing)))
                                                .using(SizeTransform(clip = false))
                                        } else if (initial is AppScreen.Files && initial.isFromAllFiles && target is AppScreen.Home) {
                                            // Exact old All Files capsule return animation
                                            (fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                                                    scaleIn(initialScale = 0.97f, animationSpec = tween(160, easing = FastOutSlowInEasing)))
                                                .togetherWith(fadeOut(tween(90, easing = FastOutSlowInEasing)))
                                                .using(SizeTransform(clip = false))
                                        } else if (initial is AppScreen.Home && (
                                                (target is AppScreen.Files && (target.category != null || target.favourites)) ||
                                                target is AppScreen.FtpServer
                                        )) {
                                            // Category container transform: Expands smoothly from the clicked card
                                            (fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)))
                                                .togetherWith(
                                                    fadeOut(animationSpec = tween(220, easing = FastOutSlowInEasing))
                                                )
                                                .using(SizeTransform(clip = false))
                                        } else if (target is AppScreen.Home && (
                                                (initial is AppScreen.Files && (initial.category != null || initial.favourites)) ||
                                                initial is AppScreen.FtpServer
                                        )) {
                                            // Return from Category to Home: Minimizes back into the category card
                                            (fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)))
                                                .togetherWith(
                                                    fadeOut(animationSpec = tween(280, easing = FastOutSlowInEasing))
                                                )
                                                .using(SizeTransform(clip = false))
                                        } else if (initial is AppScreen.Home && (
                                                (target is AppScreen.Files && target.category == null && !target.favourites && !target.isFromAllFiles) ||
                                                target is AppScreen.RecycleBin ||
                                                target is AppScreen.Settings ||
                                                target is AppScreen.About
                                        )) {
                                            // Smooth forward slide animation when opening Quick Access folders, Recycle Bin, etc.
                                            (slideInHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(220)))
                                                .togetherWith(slideOutHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(180)))
                                        } else if (target is AppScreen.Home && (
                                                (initial is AppScreen.Files && initial.category == null && !initial.recent && !initial.favourites && !initial.isFromAllFiles) ||
                                                initial is AppScreen.RecycleBin ||
                                                initial is AppScreen.Settings ||
                                                initial is AppScreen.About
                                        )) {
                                            // Smooth backward slide animation when returning to Home
                                            (slideInHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(220)))
                                                .togetherWith(slideOutHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(180)))
                                        } else {
                                            when (target) {
                                                is AppScreen.Files -> {
                                                    (fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                                                            scaleIn(initialScale = 0.96f, animationSpec = tween(200, easing = FastOutSlowInEasing)))
                                                        .togetherWith(fadeOut(tween(100, easing = FastOutSlowInEasing)))
                                                        .using(SizeTransform(clip = false))
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
                                                is AppScreen.Document -> {
                                                    (fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                                                            scaleIn(initialScale = 0.98f, animationSpec = tween(200, easing = FastOutSlowInEasing)))
                                                        .togetherWith(fadeOut(tween(120, easing = FastOutSlowInEasing)))
                                                        .using(SizeTransform(clip = false))
                                                }
                                                else -> fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                                            }
                                        }
                                    },
                                    label = "ScreenTransition"
                                )
                                { currentScreen ->
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
                                        is AppScreen.Home -> {
                                            val canSwipe = !isHomeSearching && !isRecentSearching && !viewModel.isSelectionMode

                                            LaunchedEffect(currentScreen) {
                                                if (homePagerState.currentPage != 1) {
                                                    homePagerState.scrollToPage(1)
                                                }
                                            }

                                            LaunchedEffect(homePagerState.currentPage) {
                                                if (homePagerState.currentPage == 0) {
                                                    viewModel.loadRecentFiles()
                                                }
                                            }

                                            LaunchedEffect(homePagerState) {
                                                var wasScrolling = false
                                                var dragStartPage = homePagerState.currentPage
                                                var maxOffset = 0f
                                                snapshotFlow {
                                                    Triple(
                                                        homePagerState.isScrollInProgress,
                                                        homePagerState.currentPage,
                                                        homePagerState.currentPageOffsetFraction
                                                    )
                                                }.collect { (isScrolling, page, offsetFraction) ->
                                                    if (isScrolling) {
                                                        if (!wasScrolling) {
                                                            wasScrolling = true
                                                            dragStartPage = page
                                                            maxOffset = kotlin.math.abs(offsetFraction)
                                                        } else {
                                                            maxOffset = maxOf(maxOffset, kotlin.math.abs(offsetFraction))
                                                        }
                                                    } else if (wasScrolling) {
                                                        wasScrolling = false
                                                        val pageChanged = page != dragStartPage
                                                        val significantSwipe = maxOffset > 0.08f
                                                        if (pageChanged || significantSwipe) {
                                                            viewModel.lastSwipeToHomeTimestamp = System.currentTimeMillis()
                                                        }
                                                        dragStartPage = page
                                                        maxOffset = 0f
                                                    }
                                                }
                                            }

                                            BackHandler(enabled = (homePagerState.currentPage == 0 && !isRecentSearching && !viewModel.isSelectionMode)) {
                                                coroutineScope.launch {
                                                    homePagerState.animateScrollToPage(
                                                        page = 1,
                                                        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
                                                    )
                                                }
                                            }

                                            val homePagerFlingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(state = homePagerState)

                                            Box(modifier = Modifier.fillMaxSize()) {
                                                androidx.compose.foundation.pager.HorizontalPager(
                                                    state = homePagerState,
                                                    userScrollEnabled = canSwipe,
                                                    flingBehavior = homePagerFlingBehavior,
                                                    modifier = Modifier.fillMaxSize()
                                                ) { pageIndex ->
                                                    if (pageIndex == 0) {
                                                        FileExplorerScreen(
                                                            viewModel = viewModel,
                                                            startPath = null,
                                                            startCategory = null,
                                                            startRecent = true,
                                                            fromHome = true,
                                                            highlightFile = null,
                                                            isFromAllFiles = false,
                                                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                                                            backHandlerEnabled = (homePagerState.currentPage == 0),
                                                            onSearchActiveChange = { isRecentSearching = it },
                                                            onOpenPath = { path, fileToHighlight ->
                                                                viewModel.lastNavigationTimestamp = System.currentTimeMillis()
                                                                viewModel.resetFiles()
                                                                screen = AppScreen.Files(
                                                                    startPath = path,
                                                                    fromHome = true,
                                                                    highlightFile = fileToHighlight
                                                                )
                                                            },
                                                            sharedTransitionScope = this@SharedTransitionLayout,
                                                            animatedVisibilityScope = this@AnimatedContent,
                                                            onExitToHome = {
                                                                viewModel.lastNavigationTimestamp = System.currentTimeMillis()
                                                                coroutineScope.launch {
                                                                    homePagerState.animateScrollToPage(
                                                                        page = 1,
                                                                        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
                                                                    )
                                                                }
                                                            },
                                                            onFileClick = { fileItem ->
                                                                val now = System.currentTimeMillis()
                                                                if (now - viewModel.lastSwipeToHomeTimestamp > 500L && now - viewModel.lastNavigationTimestamp > 500L) {
                                                                    if (fileItem.fileType == FileType.ZIP) {
                                                                        viewModel.resetFiles()
                                                                        screen = AppScreen.Files(
                                                                            startPath = fileItem.file.absolutePath,
                                                                            fromHome = true
                                                                        )
                                                                    } else {
                                                                        openFile(fileItem)
                                                                    }
                                                                }
                                                            },
                                                            onOpenWithClick = { openFileWith(it) },
                                                            onShareClick = { fileItems -> shareFiles(fileItems) },
                                                            listState = explorerListState,
                                                            gridState = explorerGridState
                                                        )
                                                    } else {
                                                        HomeScreen(
                                                            viewModel = viewModel,
                                                            listState = homeListState,
                                                            onSearchActiveChange = { isHomeSearching = it },
                                                            sharedTransitionScope = this@SharedTransitionLayout,
                                                            animatedVisibilityScope = this@AnimatedContent,
                                                            onOpenPath = { path, fileToHighlight ->
                                                                viewModel.lastNavigationTimestamp = System.currentTimeMillis()
                                                                val isFromAllFiles = path == Environment.getExternalStorageDirectory().absolutePath && fileToHighlight == null
                                                                viewModel.resetFiles()
                                                                viewModel.loadFiles(path, showLoading = false)
                                                                screen = AppScreen.Files(
                                                                    startPath = path,
                                                                    fromHome = true,
                                                                    highlightFile = fileToHighlight,
                                                                    isFromAllFiles = isFromAllFiles
                                                                )
                                                            },
                                                            onOpenQuickAccess = { path ->
                                                                viewModel.lastNavigationTimestamp = System.currentTimeMillis()
                                                                viewModel.resetFiles()
                                                                viewModel.loadFiles(path, showLoading = false)
                                                                screen = AppScreen.Files(
                                                                    startPath = path,
                                                                    fromHome = true,
                                                                    isFromQuickAccess = true
                                                                )
                                                            },
                                                            onOpenCategory = { type, title ->
                                                                viewModel.lastNavigationTimestamp = System.currentTimeMillis()
                                                                viewModel.resetFiles()
                                                                viewModel.browseCategory(type, title)
                                                                screen = AppScreen.Files(category = type to title, fromHome = true)
                                                            },
                                                            onOpenFavourites = {
                                                                viewModel.lastNavigationTimestamp = System.currentTimeMillis()
                                                                screen = AppScreen.Files(favourites = true, fromHome = true)
                                                            },
                                                            onOpenFtpServer = {
                                                                screen = AppScreen.FtpServer
                                                            },
                                                            onOpenRecent = {
                                                                if (homePagerState.currentPage != 0) {
                                                                    coroutineScope.launch {
                                                                        homePagerState.animateScrollToPage(
                                                                            page = 0,
                                                                            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
                                                                        )
                                                                    }
                                                                }
                                                            },
                                                            canSwipeToRecent = canSwipe,
                                                            onFileClick = { fileItem ->
                                                                val now = System.currentTimeMillis()
                                                                if (now - viewModel.lastSwipeToHomeTimestamp > 500L && now - viewModel.lastNavigationTimestamp > 500L) {
                                                                    if (fileItem.fileType == FileType.ZIP) {
                                                                        viewModel.resetFiles()
                                                                        screen = AppScreen.Files(
                                                                            startPath = fileItem.file.absolutePath,
                                                                            fromHome = true
                                                                        )
                                                                    } else {
                                                                        openFile(fileItem)
                                                                    }
                                                                }
                                                            },
                                                            onOpenWithClick = { openFileWith(it) },
                                                            onShareClick = { fileItem -> shareFiles(listOf(fileItem)) },
                                                            onSettingsClick = { screen = AppScreen.Settings },
                                                            onAboutClick = { screen = AppScreen.About },
                                                            onRecycleBinClick = { screen = AppScreen.RecycleBin }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        is AppScreen.Files -> FileExplorerScreen(
                                            viewModel = viewModel,
                                            startPath = currentScreen.startPath,
                                            startCategory = currentScreen.category,
                                            startRecent = currentScreen.recent,
                                            startFavourites = currentScreen.favourites,
                                            fromHome = currentScreen.fromHome,
                                            highlightFile = currentScreen.highlightFile,
                                            isFromAllFiles = currentScreen.isFromAllFiles,
                                            isFromQuickAccess = currentScreen.isFromQuickAccess,
                                            resumePath = currentScreen.resumePath,
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this@AnimatedContent,
                                            onExitToHome = {
                                                viewModel.lastNavigationTimestamp = System.currentTimeMillis()
                                                viewModel.resetFiles()
                                                viewModel.exitCategoryMode()
                                                coroutineScope.launch {
                                                    homePagerState.scrollToPage(1)
                                                }
                                                if (sharedUris != null) {
                                                    screen = AppScreen.SaveAs(sharedUris!!, true)
                                                } else {
                                                    screen = AppScreen.Home
                                                }
                                            },
                                            onOpenPath = { path, fileToHighlight ->
                                                viewModel.lastNavigationTimestamp = System.currentTimeMillis()
                                                val isFromAllFiles = path == Environment.getExternalStorageDirectory().absolutePath && fileToHighlight == null
                                                viewModel.resetFiles()
                                                screen = AppScreen.Files(
                                                    startPath = path,
                                                    fromHome = true,
                                                    highlightFile = fileToHighlight,
                                                    isFromAllFiles = isFromAllFiles
                                                )
                                            },
                                            onFileClick = { fileItem ->
                                                val now = System.currentTimeMillis()
                                                if (now - viewModel.lastSwipeToHomeTimestamp > 500L && now - viewModel.lastNavigationTimestamp > 500L) {
                                                    openFile(fileItem)
                                                }
                                            },
                                            onOpenWithClick = { openFileWith(it) },
                                            onShareClick = { fileItems -> shareFiles(fileItems) },
                                            listState = explorerListState,
                                            gridState = explorerGridState,
                                            contentWindowInsets = WindowInsets(0, 0, 0, 0)
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
                                        AppScreen.FtpServer -> FtpServerScreen(
                                            viewModel = viewModel,
                                            onBack = { screen = AppScreen.Home },
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            animatedVisibilityScope = this@AnimatedContent
                                        )
                                        AppScreen.About -> AboutScreen(
                                            viewModel = viewModel,
                                            onBack = { screen = AppScreen.Home }
                                        )
                                        is AppScreen.Document -> DocumentScreen(
                                            source = currentScreen.source,
                                            isMarkdownFile = currentScreen.isMarkdown,
                                            initialMode = currentScreen.initialMode,
                                            onBack = {
                                                screen = currentScreen.previousScreen ?: AppScreen.Home
                                            },
                                            onOpenExternal = {
                                                when (val src = currentScreen.source) {
                                                    is DocumentSource.FileSource -> {
                                                        openFileWith(FileItem(src.file))
                                                    }
                                                    is DocumentSource.UriSource -> {
                                                        try {
                                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                                setDataAndType(src.uri, if (currentScreen.isMarkdown) "text/markdown" else "text/plain")
                                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            }
                                                            startActivity(Intent.createChooser(intent, getString(R.string.action_open_with)))
                                                        } catch (e: Exception) {
                                                            Toast.makeText(this@MainActivity, getString(R.string.toast_couldnt_open_file, e.message ?: ""), Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                    is DocumentSource.VirtualZipSource -> {}
                                                }
                                            },
                                            onShare = {
                                                when (val src = currentScreen.source) {
                                                    is DocumentSource.FileSource -> {
                                                        shareFiles(listOf(FileItem(src.file)))
                                                    }
                                                    is DocumentSource.UriSource -> {
                                                        try {
                                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                                putExtra(Intent.EXTRA_STREAM, src.uri)
                                                                type = if (currentScreen.isMarkdown) "text/markdown" else "text/plain"
                                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            }
                                                            startActivity(Intent.createChooser(intent, getString(R.string.action_share_via)))
                                                        } catch (e: Exception) {
                                                            Toast.makeText(this@MainActivity, getString(R.string.toast_couldnt_open_file, e.message ?: ""), Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                    is DocumentSource.VirtualZipSource -> {}
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // Tinted glass navigation bar behind system navigation buttons
                            // only active when 3-button / 2-button navigation is used and not in selection mode
                            if (!viewModel.isSelectionMode) {
                                TintedGlassNavigationBar()
                            }

                            // Global mini bar + detailed dialog for in-flight jobs - lives
                            // above the AnimatedContent so it survives Home <-> Files <->
                            // Recycle Bin navigation instead of resetting per screen.
                            // Delete jobs are handled separately below (see deleteJobs).
                            if (otherJobs.isNotEmpty() && !showJobDetailsDialog) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .navigationBarsPadding()
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

                            viewModel.operationFailureReport?.let { report ->
                                dev.narayan.rose.filejob.FileOperationFailureDialog(
                                    report = report,
                                    onDismissRequest = { viewModel.clearOperationFailureReport() }
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
                                            is dev.narayan.rose.filejob.FileJobType.Recycle -> stringResource(R.string.job_moving_to_bin_fast)
                                            is dev.narayan.rose.filejob.FileJobType.Restore -> stringResource(R.string.job_restoring_fast)
                                            else -> stringResource(R.string.job_deleting_fast)
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

        if (intent.getBooleanExtra(FtpServerNotification.EXTRA_OPEN_FTP, false)) {
            pendingFtpNavigation = true
        }

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                uri?.let { pendingArchiveUri = it }
                uri?.let {
                    if (isMarkdownUri(it)) {
                        val name = getUriDisplayName(it) ?: "document.md"
                        val isWritable = it.scheme == "file" || (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                        pendingDocument = DocumentSource.UriSource(it, name, isReadOnly = !isWritable) to true
                    } else if (isTextUri(it)) {
                        val name = getUriDisplayName(it) ?: "document.txt"
                        val isWritable = it.scheme == "file" || (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                        pendingDocument = DocumentSource.UriSource(it, name, isReadOnly = !isWritable) to false
                    } else {
                        pendingArchiveUri = it
                    }
                }
            }
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                uri?.let { sharedUris = listOf(it) }
                uri?.let {
                    if (isMarkdownUri(it)) {
                        val name = getUriDisplayName(it) ?: "document.md"
                        pendingDocument = DocumentSource.UriSource(it, name, isReadOnly = true) to true
                    } else {
                        sharedUris = listOf(it)
                    }
                }
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

    private val archiveExtensions = listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "apk", "xapk", "apks")
    private val archiveMimeTypes = listOf(
        "application/zip", "application/x-zip", "application/x-zip-compressed",
        "application/x-7z-compressed", "application/x-rar-compressed", "application/rar", "application/vnd.rar",
        "application/x-tar", "application/gzip", "application/x-bzip2", "application/x-xz",
        "application/vnd.android.package-archive"
    )

    private fun getUriDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment
    }

    private fun cleanupOldViewArchives() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                CacheCleaner.cleanViewArchives(this@MainActivity, viewModel.currentZipFile?.absolutePath)
            } catch (e: Exception) {}
        }
    }

    // Content Uris shared from other apps (WhatsApp, Gmail, etc.) almost never have a
    // real file extension in their path, so MimeTypeMap.getFileExtensionFromUrl(uri)
    // alone silently returns "" for them - this was why sharing a .rar/.7z (and often
    // even a .zip or .apk) here used to skip straight to "Save to..." instead of offering
    // Archive viewer/Save as. Cross-check the resolver's reported MIME type and the
    // provider's DISPLAY_NAME column, which reflect the real file name/type.
    private fun isArchiveUri(uri: Uri): Boolean {
        val mimeType = try { contentResolver.getType(uri) } catch (e: Exception) { null }
        if (mimeType != null && mimeType in archiveMimeTypes) return true

        val urlExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
        if (urlExtension in archiveExtensions) return true

        val pathExtension = uri.path?.substringAfterLast('.', "")?.lowercase()
        if (pathExtension != null && pathExtension.isNotEmpty() && pathExtension in archiveExtensions) return true

        val displayName = getUriDisplayName(uri)
        val nameExtension = displayName?.substringAfterLast('.', "")?.lowercase()
        if (nameExtension != null && nameExtension.isNotEmpty() && nameExtension in archiveExtensions) return true

        if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            if (file.extension.lowercase() in archiveExtensions) return true
        }

        return false
    }

    private val markdownExtensions = setOf("md", "markdown", "mdown", "mkdn", "mkd")
    private val textExtensions = setOf(
        "txt", "text", "log", "ini", "conf", "properties",
        "xml", "yml", "yaml", "json", "html", "htm", "css", "js", "ts", "sh", "py", "csv", "ml"
    )

    private fun isMarkdownUri(uri: Uri): Boolean {
        val mimeType = try { contentResolver.getType(uri) } catch (e: Exception) { null }
        if (mimeType == "text/markdown" || mimeType == "text/x-markdown") return true

        val urlExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
        if (urlExtension in markdownExtensions) return true

        val pathExtension = uri.path?.substringAfterLast('.', "")?.lowercase()
        if (pathExtension != null && pathExtension.isNotEmpty() && pathExtension in markdownExtensions) return true

        val displayName = getUriDisplayName(uri)
        val nameExtension = displayName?.substringAfterLast('.', "")?.lowercase()
        if (nameExtension != null && nameExtension.isNotEmpty() && nameExtension in markdownExtensions) return true

        if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            if (file.extension.lowercase() in markdownExtensions) return true
        }

        return false
    }

    private fun isTextUri(uri: Uri): Boolean {
        val mimeType = try { contentResolver.getType(uri) } catch (e: Exception) { null }
        if (mimeType == "text/plain") return true

        val urlExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
        if (urlExtension in textExtensions) return true

        val pathExtension = uri.path?.substringAfterLast('.', "")?.lowercase()
        if (pathExtension != null && pathExtension.isNotEmpty() && pathExtension in textExtensions) return true

        val displayName = getUriDisplayName(uri)
        val nameExtension = displayName?.substringAfterLast('.', "")?.lowercase()
        if (nameExtension != null && nameExtension.isNotEmpty() && nameExtension in textExtensions) return true

        if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            if (file.extension.lowercase() in textExtensions) return true
        }

        return false
    }

    private fun shareFiles(fileItems: List<FileItem>, passphrase: String? = null) {
        if (fileItems.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            // Clean up stale temporary share files older than 15 minutes before starting new share
            CacheCleaner.cleanStaleShareFiles(this@MainActivity, maxAgeMs = 15 * 60 * 1000L)
            val newlyCreatedTempFiles = mutableListOf<File>()
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
                        newlyCreatedTempFiles.forEach { runCatching { it.delete() } }
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
                        newlyCreatedTempFiles.add(cacheFile)
                        uris.add(FileProvider.getUriForFile(this@MainActivity, "${packageName}.provider", cacheFile))
                    } catch (e: Exception) {
                        runCatching { cacheFile.delete() }
                        if (ArchiveManager.isEncryptionError(e)) {
                            newlyCreatedTempFiles.forEach { runCatching { it.delete() } }
                            withContext(Dispatchers.Main) {
                                viewModel.cachedZipPassword = null
                                if (effectivePassphrase != null) {
                                    Toast.makeText(this@MainActivity, R.string.wrong_password, Toast.LENGTH_SHORT).show()
                                }
                                viewModel.passphrasePromptItem = item
                                viewModel.passphraseAction = { pw -> shareFiles(fileItems, pw) }
                            }
                            return@launch
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, getString(R.string.toast_failed_to_extract, item.name, e.message ?: ""), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val path = item.file.absolutePath
                    if (SafManager.isRestrictedPath(path)) {
                        // Files here don't exist from java.io.File's point of view
                        if (item.file.exists() && item.file.canRead()) {
                            uris.add(FileProvider.getUriForFile(
                                this@MainActivity,
                                "${applicationContext.packageName}.provider",
                                item.file
                            ))
                        } else {
                            val safUri = if (SafManager.hasPermission(this@MainActivity, path)) SafManager.getContentUri(this@MainActivity, path) else null
                            if (safUri != null) {
                                uris.add(safUri)
                            } else if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                                uris.add(RestrictedFileProvider.getUriForFile(this@MainActivity, item.file))
                            } else {
                                folderFound = true
                            }
                        }
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
                    newlyCreatedTempFiles.forEach { runCatching { it.delete() } }
                    if (folderFound) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_folders_cannot_be_shared), Toast.LENGTH_SHORT).show()
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
                startActivity(Intent.createChooser(intent, getString(R.string.action_share_via)))
            }
        }
    }

    private var lastFileOpenTimestamp = 0L

    private fun openFileWith(fileItem: FileItem) {
        val now = System.currentTimeMillis()
        if (now - lastFileOpenTimestamp < 500L) return
        lastFileOpenTimestamp = now

        val intent = createViewIntent(fileItem) ?: return
        try {
            val chooser = Intent.createChooser(intent, getString(R.string.action_open_with))
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_couldnt_open_file, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun createViewIntent(fileItem: FileItem, passphrase: String? = null): Intent? {
        try {
            val file = fileItem.file
            val path = file.absolutePath
            val isSaf = SafManager.isSafUri(path) || SafManager.isSafUri(fileItem.file.path)
            val restricted = !isSaf && SafManager.isRestrictedPath(path)
            val isVirtual = fileItem.virtualZipSource != null

            if (!isVirtual && !isSaf && !viewModel.isRemotePath(path)) {
                val exists = if (restricted) {
                    (SafManager.hasPermission(this, path) && SafManager.exists(this, path)) ||
                            (ShizukuManager.isAvailable() && ShizukuManager.hasPermission() && ShizukuManager.exists(path)) ||
                            file.exists()
                } else {
                    file.exists()
                }
                if (!exists) {
                    Toast.makeText(this, getString(R.string.toast_file_no_longer_exists), Toast.LENGTH_SHORT).show()
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

                // Clean up previous temp_open_ files to prevent cache accumulation
                cacheDir.listFiles()?.filter { it.name.startsWith("temp_open_") }?.forEach {
                    runCatching { it.deleteRecursively() }
                }
                val cacheFile = File(cacheDir, "temp_open_${fileItem.name}")
                tempOpenedFiles.add(cacheFile)
                try {
                    cacheFile.outputStream().use { output ->
                        ArchiveManager.extractEntry(fileItem.virtualZipSource!!, fileItem.zipEntryPath ?: fileItem.name, output, effectivePassphrase)
                    }
                    if (effectivePassphrase != null) {
                        viewModel.cachedZipPassword = effectivePassphrase
                    }
                    FileProvider.getUriForFile(this, "${packageName}.provider", cacheFile)
                } catch (e: Exception) {
                    runCatching {
                        cacheFile.delete()
                        tempOpenedFiles.remove(cacheFile)
                    }
                    if (ArchiveManager.isEncryptionError(e)) {
                        viewModel.cachedZipPassword = null
                        if (effectivePassphrase != null) {
                            Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show()
                        }
                        viewModel.passphrasePromptItem = fileItem
                        viewModel.passphraseAction = { pw -> openFile(fileItem, pw) }
                    } else {
                        Toast.makeText(this, getString(R.string.toast_failed_to_extract_file, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                    return null
                }
            } else if (isSaf) {
                val rawSafPath = if (SafManager.isSafUri(fileItem.file.path)) fileItem.file.path else path
                Uri.parse(SafManager.normalizeSafPath(rawSafPath))
            } else if (restricted) {
                if (file.exists() && file.canRead()) {
                    FileProvider.getUriForFile(
                        this,
                        "${applicationContext.packageName}.provider",
                        file
                    )
                } else {
                    val safUri = if (SafManager.hasPermission(this, path)) SafManager.getContentUri(this, path) else null
                    if (safUri != null) {
                        safUri
                    } else if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                        RestrictedFileProvider.getUriForFile(this, file)
                    } else {
                        Toast.makeText(this, getString(R.string.toast_couldnt_access_file), Toast.LENGTH_SHORT).show()
                        return null
                    }
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
                        Toast.makeText(this, getString(R.string.toast_allow_install_source), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, getString(R.string.toast_couldnt_prepare_intent, e.message ?: ""), Toast.LENGTH_SHORT).show()
            return null
        }
    }

    private fun openFile(fileItem: FileItem, passphrase: String? = null) {
        val now = System.currentTimeMillis()
        if (now - lastFileOpenTimestamp < 500L) return
        lastFileOpenTimestamp = now

        if (viewModel.isRemotePath(fileItem.file.path)) {
            val localOffline = viewModel.getLocalOfflineFile(fileItem)
            if (localOffline != null && localOffline.exists()) {
                val localFileItem = fileItem.copy(file = localOffline)
                openFile(localFileItem, passphrase)
                return
            } else {
                Toast.makeText(this, "Downloading file to view...", Toast.LENGTH_SHORT).show()
                viewModel.toggleOfflineStatus(fileItem)
                return
            }
        }

        val ext = fileItem.extension.lowercase()
        val isMd = ext in markdownExtensions
        val isTxt = ext in textExtensions

        if (isMd || isTxt) {
            if (fileItem.virtualZipSource != null) {
                val entryPath = fileItem.zipEntryPath ?: fileItem.name
                val source = DocumentSource.VirtualZipSource(
                    archiveFile = fileItem.virtualZipSource,
                    entryPath = entryPath,
                    displayName = fileItem.name
                )
                pendingDocument = source to isMd
                return
            }

            val path = fileItem.file.absolutePath
            val isSaf = SafManager.isSafUri(path) || SafManager.isSafUri(fileItem.file.path)
            if (isSaf) {
                val rawSafPath = if (SafManager.isSafUri(fileItem.file.path)) fileItem.file.path else path
                val safUri = Uri.parse(SafManager.normalizeSafPath(rawSafPath))
                pendingDocument = DocumentSource.UriSource(safUri, fileItem.name, isReadOnly = false) to isMd
                return
            }

            val restricted = SafManager.isRestrictedPath(path)
            if (restricted) {
                val safUri = if (SafManager.hasPermission(this, path)) SafManager.getContentUri(this, path) else null
                if (safUri != null) {
                    pendingDocument = DocumentSource.UriSource(safUri, fileItem.name, isReadOnly = false) to isMd
                    return
                } else if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                    val restrictedUri = RestrictedFileProvider.getUriForFile(this, fileItem.file)
                    pendingDocument = DocumentSource.UriSource(restrictedUri, fileItem.name, isReadOnly = false) to isMd
                    return
                }
            }

            pendingDocument = DocumentSource.FileSource(fileItem.file) to isMd
            return
        }

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
                val chooser = Intent.createChooser(intent, getString(R.string.action_open_with))
                startActivity(chooser)
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_couldnt_open_file, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }


    internal fun openRecycledFile(item: RecycledItem) {
        val file = RecycleBinManager.getRecycledFile(this, item)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.toast_file_not_found_recycle_bin), Toast.LENGTH_SHORT).show()
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
            val chooser = Intent.createChooser(intent, getString(R.string.action_open_with))
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
            registerReceiver(storageReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(storageReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        val currentPath = viewModel.currentPath
        if (viewModel.isShizukuRestrictedPath(currentPath) && viewModel.accessDenied) {
            if (ShizukuManager.isAvailable() && ShizukuManager.hasPermission()) {
                viewModel.onShizukuResult(true, currentPath)
            }
        }
        cleanupOldViewArchives()
        lifecycleScope.launch(Dispatchers.IO) {
            CacheCleaner.cleanStaleShareFiles(this@MainActivity, maxAgeMs = 15 * 60 * 1000L)
            CacheCleaner.cleanStaleTemporaryOpenFiles(this@MainActivity, maxAgeMs = 15 * 60 * 1000L)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(storageReceiver) } catch (e: IllegalArgumentException) { /* already unregistered */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        CacheCleaner.cleanStaleTemporaryOpenFiles(this, maxAgeMs = 15 * 60 * 1000L)
        CacheCleaner.cleanStaleShareFiles(this, maxAgeMs = 15 * 60 * 1000L)
        CacheCleaner.cleanViewArchives(this)
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
        Text(stringResource(R.string.permission_placeholder_waiting))
    }
}

private sealed class AppScreen {
    object Home : AppScreen()
    data class Files(
        val startPath: String? = null,
        val category: Pair<FileType, String>? = null,
        val recent: Boolean = false,
        val favourites: Boolean = false,
        val fromHome: Boolean = false,
        val highlightFile: FileItem? = null,
        val isFromAllFiles: Boolean = false,
        val isFromQuickAccess: Boolean = false,
        val resumePath: String? = null
    ) : AppScreen()
    data class SaveAs(
        val uris: List<Uri>,
        val isZip: Boolean = false
    ) : AppScreen()
    object Settings : AppScreen()
    object About : AppScreen()
    object RecycleBin : AppScreen()
    object FtpServer : AppScreen()
    data class Document(
        val source: DocumentSource,
        val isMarkdown: Boolean,
        val initialMode: DocumentMode = DocumentMode.VIEW,
        val previousScreen: AppScreen? = null
    ) : AppScreen()
}