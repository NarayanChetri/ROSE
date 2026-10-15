package dev.narayan.rose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.abs
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class VaultSortBy {
    NAME, DATE, SIZE
}

enum class VaultCategory {
    ALL, PHOTOS, VIDEOS, MUSIC, DOCUMENTS, OTHER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateVaultScreen(
    viewModel: RoseViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isPinConfigured by remember { mutableStateOf(PrivateVaultManager.isPinSet(context)) }

    LaunchedEffect(Unit) {
        isPinConfigured = PrivateVaultManager.isPinSet(context)
    }

    // Instant autolock when exiting private safe (without triggering biometric prompt)
    DisposableEffect(Unit) {
        onDispose {
            viewModel.lockVault()
        }
    }

    if (!viewModel.isVaultUnlocked) {
        VaultLockView(
            isPinConfigured = isPinConfigured && PrivateVaultManager.isPinSet(context),
            onPinVerified = {
                viewModel.unlockVault()
            },
            onPinSetupComplete = {
                isPinConfigured = true
                viewModel.setVaultBiometricEnabled(true)
                viewModel.unlockVault()
            },
            onPinSavedRequireUnlock = {
                isPinConfigured = true
            },
            onPinReset = {
                isPinConfigured = false
            },
            onBiometricRequested = {
                val activity = context as? FragmentActivity
                if (activity != null) {
                    VaultSecurityManager.authenticate(
                        activity = activity,
                        title = context.getString(R.string.vault_biometric_prompt_title),
                        subtitle = context.getString(R.string.vault_biometric_prompt_subtitle),
                        onSuccess = {
                            viewModel.unlockVault()
                        },
                        onError = { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            },
            isBiometricAvailable = VaultSecurityManager.isBiometricAvailable(context),
            onBack = onBack
        )
    } else {
        VaultContentView(
            viewModel = viewModel,
            onBack = onBack
        )
    }
}

// -----------------------------------------------------------------------------
// PIN & Lock Keypad Screen (Matches MX Player Dark Aesthetic Exactly)
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultLockView(
    isPinConfigured: Boolean,
    onPinVerified: () -> Unit,
    onPinSetupComplete: () -> Unit,
    onPinSavedRequireUnlock: () -> Unit,
    onPinReset: () -> Unit,
    onBiometricRequested: () -> Unit,
    isBiometricAvailable: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    val pinConfigured = isPinConfigured && PrivateVaultManager.isPinSet(context)
    var pinSetupStep by remember { mutableIntStateOf(0) } // 0: enter pin, 1: confirm pin
    var setupPinInitial by remember { mutableStateOf("") }

    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(isError) {
        if (isError) {
            shakeOffset.snapTo(0f)
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 400
                    0f at 0
                    (-16f) at 50
                    16f at 100
                    (-12f) at 150
                    12f at 200
                    (-6f) at 250
                    6f at 300
                    0f at 400
                }
            )
        }
    }

    fun handlePinInput(digit: String) {
        if (enteredPin.length < 4) {
            val newPin = enteredPin + digit
            enteredPin = newPin
            isError = false
            errorMessage = ""

            if (newPin.length == 4) {
                if (!pinConfigured) {
                    // PIN Setup Flow
                    if (pinSetupStep == 0) {
                        setupPinInitial = newPin
                        coroutineScope.launch {
                            delay(180)
                            enteredPin = ""
                            pinSetupStep = 1
                        }
                    } else {
                        // Confirm PIN
                        if (newPin == setupPinInitial) {
                            val ok = PrivateVaultManager.setupPin(context, newPin)
                            if (ok) {
                                val activity = context as? FragmentActivity
                                if (isBiometricAvailable && activity != null) {
                                    // Prompt biometric authentication right after PIN setup
                                    VaultSecurityManager.authenticate(
                                        activity = activity,
                                        title = context.getString(R.string.vault_biometric_prompt_title),
                                        subtitle = "Touch fingerprint sensor to authenticate and unlock",
                                        onSuccess = {
                                            onPinSetupComplete()
                                        },
                                        onError = {
                                            onPinSetupComplete()
                                        },
                                        onCancel = {
                                            onPinSetupComplete()
                                        }
                                    )
                                } else {
                                    onPinSetupComplete()
                                }
                            } else {
                                isError = true
                                errorMessage = "Failed to save PIN"
                            }
                        } else {
                            isError = true
                            errorMessage = context.getString(R.string.vault_pin_mismatch)
                            coroutineScope.launch {
                                delay(600)
                                enteredPin = ""
                                setupPinInitial = ""
                                pinSetupStep = 0
                            }
                        }
                    }
                } else {
                    // Unlock Flow
                    val ok = PrivateVaultManager.verifyPin(context, newPin)
                    if (ok) {
                        onPinVerified()
                    } else {
                        isError = true
                        errorMessage = context.getString(R.string.vault_wrong_pin)
                        coroutineScope.launch {
                            delay(500)
                            enteredPin = ""
                        }
                    }
                }
            }
        }
    }

    fun handleDelete() {
        if (enteredPin.isNotEmpty()) {
            enteredPin = enteredPin.dropLast(1)
            isError = false
            errorMessage = ""
        }
    }

    Scaffold(
        containerColor = Color(0xFF13151F),
        contentColor = Color.White
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Row: Circular Back Button (matches image exactly)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF25293A))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.5f))

            // Header Section: Rounded Lock Card, Title, Subtitle, Dots
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                // Centered Lock Icon in Rounded Card
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1E2333)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFFA5B4FC),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                val titleText = when {
                    !pinConfigured && pinSetupStep == 0 -> "Set PIN"
                    !pinConfigured && pinSetupStep == 1 -> "Confirm PIN"
                    else -> "Enter PIN"
                }
                val descText = when {
                    !pinConfigured && pinSetupStep == 0 -> "Set a 4-digit PIN to access private vault"
                    !pinConfigured && pinSetupStep == 1 -> "Re-enter your 4-digit PIN to confirm"
                    else -> "Enter your PIN to access hidden files"
                }

                Text(
                    text = titleText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = descText,
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 4 PIN Dots (matches image exactly)
                Row(
                    modifier = Modifier.offset(x = shakeOffset.value.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        val isFilled = index < enteredPin.length
                        val dotColor by animateColorAsState(
                            targetValue = when {
                                isError -> Color(0xFFEF4444)
                                isFilled -> Color(0xFF93C5FD)
                                else -> Color(0xFF262B3D)
                            },
                            animationSpec = tween(150),
                            label = "DotColor"
                        )
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                }

                // Error Message
                if (errorMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = errorMessage,
                        fontSize = 12.sp,
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Keypad (matches image layout: 3 columns, rounded rectangle tiles)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val numRows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9")
                )

                numRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        row.forEach { digit ->
                            VaultKeypadTile(
                                modifier = Modifier.weight(1f),
                                onClick = { handlePinInput(digit) }
                            ) {
                                Text(
                                    text = digit,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // Row 4: Empty space | 0 | Backspace
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Spacer(modifier = Modifier.weight(1f))

                    VaultKeypadTile(
                        modifier = Modifier.weight(1f),
                        onClick = { handlePinInput("0") }
                    ) {
                        Text(
                            text = "0",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }

                    VaultKeypadTile(
                        modifier = Modifier.weight(1f),
                        onClick = { handleDelete() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Backspace,
                            contentDescription = "Delete",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.7f))

            // Bottom Section: "Unlock with biometrics" & "Forgot PIN?"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (!pinConfigured) {
                                Toast.makeText(context, "Set PIN first", Toast.LENGTH_SHORT).show()
                            } else if (isBiometricAvailable) {
                                onBiometricRequested()
                            } else {
                                Toast.makeText(context, "Biometric authentication not available", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "Biometric",
                        tint = Color(0xFFA5B4FC),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Unlock with biometrics",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFA5B4FC)
                    )
                }

                Text(
                    text = "Forgot PIN?",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            if (!pinConfigured) {
                                Toast.makeText(context, "Set PIN first", Toast.LENGTH_SHORT).show()
                            } else {
                                val activity = context as? FragmentActivity
                                if (isBiometricAvailable && activity != null) {
                                    VaultSecurityManager.authenticate(
                                        activity = activity,
                                        title = "Reset Private Safe PIN",
                                        subtitle = "Verify fingerprint to reset PIN",
                                        onSuccess = {
                                            showResetConfirmDialog = true
                                        },
                                        onError = {
                                            Toast.makeText(context, "Biometric verification required to reset PIN", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } else {
                                    showResetConfirmDialog = true
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Reset PIN?") },
            text = { Text("Do you want to reset your vault PIN and configure a new one?") },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmDialog = false
                        PrivateVaultManager.resetPin(context)
                        enteredPin = ""
                        setupPinInitial = ""
                        pinSetupStep = 0
                        onPinReset()
                    }
                ) {
                    Text("Reset PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun VaultKeypadTile(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E2333))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// -----------------------------------------------------------------------------
// Vault Content Screen (Unlocked Mode)
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun VaultContentView(
    viewModel: RoseViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadVaultItems()
    }

    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedVaultItems = remember { mutableStateListOf<VaultItem>() }

    var isGridView by remember { mutableStateOf(true) }
    var sortBy by remember { mutableStateOf(VaultSortBy.DATE) }
    var sortAscending by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    // Dialogs
    var showRestoreConfirmDialog by remember { mutableStateOf<List<VaultItem>?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<List<VaultItem>?>(null) }
    var showChangePinDialog by remember { mutableStateOf(false) }

    // In-Vault Media Viewer
    var activeViewerItem by remember { mutableStateOf<VaultItem?>(null) }

    var showCustomFilePicker by remember { mutableStateOf(false) }

    val categories = remember {
        listOf(
            VaultCategory.ALL,
            VaultCategory.PHOTOS,
            VaultCategory.VIDEOS,
            VaultCategory.MUSIC,
            VaultCategory.DOCUMENTS,
            VaultCategory.OTHER
        )
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { categories.size })

    val categoryCounts = remember(viewModel.vaultItems) {
        val counts = mutableMapOf<VaultCategory, Int>()
        counts[VaultCategory.ALL] = viewModel.vaultItems.size
        counts[VaultCategory.PHOTOS] = viewModel.vaultItems.count { it.fileType == FileType.IMAGE }
        counts[VaultCategory.VIDEOS] = viewModel.vaultItems.count { it.fileType == FileType.VIDEO }
        counts[VaultCategory.MUSIC] = viewModel.vaultItems.count { it.fileType == FileType.AUDIO }
        counts[VaultCategory.DOCUMENTS] = viewModel.vaultItems.count { it.fileType == FileType.DOCUMENT || it.fileType == FileType.PDF }
        counts[VaultCategory.OTHER] = viewModel.vaultItems.count {
            it.fileType != FileType.IMAGE &&
            it.fileType != FileType.VIDEO &&
            it.fileType != FileType.AUDIO &&
            it.fileType != FileType.DOCUMENT &&
            it.fileType != FileType.PDF
        }
        counts
    }

    val gridStates = remember { categories.indices.map { LazyGridState() } }
    val listStates = remember { categories.indices.map { androidx.compose.foundation.lazy.LazyListState() } }

    fun scrollToTop() {
        coroutineScope.launch {
            val curr = pagerState.currentPage
            if (isGridView) {
                gridStates.getOrNull(curr)?.scrollToItem(0)
            } else {
                listStates.getOrNull(curr)?.scrollToItem(0)
            }
        }
    }

    LaunchedEffect(sortBy, sortAscending, isGridView) {
        scrollToTop()
    }

    BackHandler {
        if (activeViewerItem != null) {
            activeViewerItem = null
        } else if (isSelectionMode) {
            isSelectionMode = false
            selectedVaultItems.clear()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            Column {
                if (isSelectionMode) {
                    TopAppBar(
                        title = {
                            Text(
                                "${selectedVaultItems.size} selected",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                isSelectionMode = false
                                selectedVaultItems.clear()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Exit Selection")
                            }
                        },
                        actions = {
                            val activeCat = categories[pagerState.currentPage]
                            val activeCatItems = viewModel.vaultItems.filter { item ->
                                when (activeCat) {
                                    VaultCategory.ALL -> true
                                    VaultCategory.PHOTOS -> item.fileType == FileType.IMAGE
                                    VaultCategory.VIDEOS -> item.fileType == FileType.VIDEO
                                    VaultCategory.MUSIC -> item.fileType == FileType.AUDIO
                                    VaultCategory.DOCUMENTS -> item.fileType == FileType.DOCUMENT || item.fileType == FileType.PDF
                                    VaultCategory.OTHER -> item.fileType != FileType.IMAGE &&
                                            item.fileType != FileType.VIDEO &&
                                            item.fileType != FileType.AUDIO &&
                                            item.fileType != FileType.DOCUMENT &&
                                            item.fileType != FileType.PDF
                                }
                            }
                            val allInPageSelected = activeCatItems.isNotEmpty() && activeCatItems.all { selectedVaultItems.contains(it) }
                            IconButton(onClick = {
                                if (allInPageSelected) {
                                    selectedVaultItems.removeAll(activeCatItems)
                                    if (selectedVaultItems.isEmpty()) isSelectionMode = false
                                } else {
                                    activeCatItems.forEach {
                                        if (!selectedVaultItems.contains(it)) selectedVaultItems.add(it)
                                    }
                                }
                            }) {
                                Icon(
                                    if (allInPageSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                    contentDescription = "Select All"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text(stringResource(R.string.vault_title), fontWeight = FontWeight.SemiBold)
                                val currentCat = categories[pagerState.currentPage]
                                val totalCount = categoryCounts[currentCat] ?: 0
                                Text(
                                    "$totalCount file${if (totalCount != 1) "s" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        },
                        actions = {
                            // Sort action
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Date modified") },
                                        onClick = {
                                            if (sortBy == VaultSortBy.DATE) sortAscending = !sortAscending
                                            else { sortBy = VaultSortBy.DATE; sortAscending = false }
                                            showSortMenu = false
                                            scrollToTop()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Schedule, contentDescription = null)
                                        },
                                        trailingIcon = {
                                            if (sortBy == VaultSortBy.DATE) {
                                                Icon(
                                                    if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Name") },
                                        onClick = {
                                            if (sortBy == VaultSortBy.NAME) sortAscending = !sortAscending
                                            else { sortBy = VaultSortBy.NAME; sortAscending = true }
                                            showSortMenu = false
                                            scrollToTop()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.SortByAlpha, contentDescription = null)
                                        },
                                        trailingIcon = {
                                            if (sortBy == VaultSortBy.NAME) {
                                                Icon(
                                                    if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Size") },
                                        onClick = {
                                            if (sortBy == VaultSortBy.SIZE) sortAscending = !sortAscending
                                            else { sortBy = VaultSortBy.SIZE; sortAscending = false }
                                            showSortMenu = false
                                            scrollToTop()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.DataUsage, contentDescription = null)
                                        },
                                        trailingIcon = {
                                            if (sortBy == VaultSortBy.SIZE) {
                                                Icon(
                                                    if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    )

                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).alpha(0.3f))

                                    DropdownMenuItem(
                                        text = { Text(if (isGridView) stringResource(R.string.view_list) else stringResource(R.string.view_grid)) },
                                        onClick = {
                                            isGridView = !isGridView
                                            showSortMenu = false
                                            scrollToTop()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.ViewModule,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }
                            }

                            // More options
                            Box {
                                IconButton(onClick = { showOptionsMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(
                                    expanded = showOptionsMenu,
                                    onDismissRequest = { showOptionsMenu = false },
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.settings_vault_change_pin)) },
                                        onClick = {
                                            showOptionsMenu = false
                                            showChangePinDialog = true
                                        },
                                        leadingIcon = { Icon(Icons.Default.Pin, null) }
                                    )
                                    if (VaultSecurityManager.isBiometricAvailable(context)) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.settings_vault_use_biometrics)) },
                                            trailingIcon = {
                                                Switch(
                                                    checked = viewModel.vaultBiometricEnabled,
                                                    onCheckedChange = { viewModel.setVaultBiometricEnabled(it) }
                                                )
                                            },
                                            onClick = {
                                                viewModel.setVaultBiometricEnabled(!viewModel.vaultBiometricEnabled)
                                            },
                                            leadingIcon = { Icon(Icons.Default.Fingerprint, null) }
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                    )
                }

                // Category Filter Tabs Row
                VaultCategoryFilterRow(
                    selectedCategory = categories[pagerState.currentPage],
                    onCategorySelected = { cat ->
                        val index = categories.indexOf(cat)
                        if (index >= 0) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        }
                    },
                    counts = categoryCounts
                )
            }
        },
        bottomBar = {
            if (isSelectionMode && selectedVaultItems.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(vertical = 10.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Restore
                        TextButton(onClick = {
                            showRestoreConfirmDialog = selectedVaultItems.toList()
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.action_restore), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Share
                        TextButton(onClick = {
                            shareVaultItems(context, selectedVaultItems.toList())
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.action_share), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Delete
                        TextButton(onClick = {
                            showDeleteConfirmDialog = selectedVaultItems.toList()
                        }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(
                    onClick = {
                        showCustomFilePicker = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.vault_add_files))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) { pageIndex ->
            val pageCat = categories[pageIndex]
            val pageItems = remember(viewModel.vaultItems, pageCat, sortBy, sortAscending) {
                val filtered = when (pageCat) {
                    VaultCategory.ALL -> viewModel.vaultItems
                    VaultCategory.PHOTOS -> viewModel.vaultItems.filter { it.fileType == FileType.IMAGE }
                    VaultCategory.VIDEOS -> viewModel.vaultItems.filter { it.fileType == FileType.VIDEO }
                    VaultCategory.MUSIC -> viewModel.vaultItems.filter { it.fileType == FileType.AUDIO }
                    VaultCategory.DOCUMENTS -> viewModel.vaultItems.filter { it.fileType == FileType.DOCUMENT || it.fileType == FileType.PDF }
                    VaultCategory.OTHER -> viewModel.vaultItems.filter {
                        it.fileType != FileType.IMAGE &&
                        it.fileType != FileType.VIDEO &&
                        it.fileType != FileType.AUDIO &&
                        it.fileType != FileType.DOCUMENT &&
                        it.fileType != FileType.PDF
                    }
                }
                val sorted = when (sortBy) {
                    VaultSortBy.NAME -> filtered.sortedBy { it.originalName.lowercase() }
                    VaultSortBy.DATE -> filtered.sortedBy { if (it.originalLastModified > 0L) it.originalLastModified else it.dateAdded }
                    VaultSortBy.SIZE -> filtered.sortedBy { it.size }
                }
                if (sortAscending) sorted else sorted.reversed()
            }

            // Group by date made when sorted by date
            val groupedByDate = remember(pageItems, sortBy) {
                if (sortBy == VaultSortBy.DATE) {
                    val map = LinkedHashMap<String, MutableList<VaultItem>>()
                    pageItems.forEach { item ->
                        val ts = if (item.originalLastModified > 0L) item.originalLastModified else item.dateAdded
                        val header = formatVaultDateHeader(context, ts)
                        map.getOrPut(header) { mutableListOf() }.add(item)
                    }
                    map
                } else null
            }

            if (pageItems.isEmpty()) {
                // Empty Category State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        if (viewModel.vaultItems.isEmpty()) stringResource(R.string.vault_empty_title)
                        else "No ${pageCat.name.lowercase()} found in Safe",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.vault_empty_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { showCustomFilePicker = true },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.vault_add_files))
                    }
                }
            } else if (isGridView) {
                val gridState = gridStates[pageIndex]
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    state = gridState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (groupedByDate != null) {
                        groupedByDate.forEach { (header, itemsInDate) ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                DateSectionHeader(title = header, count = itemsInDate.size)
                            }
                            items(itemsInDate, key = { it.id }) { item ->
                                val isSelected = selectedVaultItems.contains(item)
                                VaultGridItem(
                                    item = item,
                                    isSelected = isSelected,
                                    isSelectionMode = isSelectionMode,
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (isSelected) selectedVaultItems.remove(item)
                                            else selectedVaultItems.add(item)
                                            if (selectedVaultItems.isEmpty()) isSelectionMode = false
                                        } else {
                                            activeViewerItem = item
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            selectedVaultItems.add(item)
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        items(pageItems, key = { it.id }) { item ->
                            val isSelected = selectedVaultItems.contains(item)
                            VaultGridItem(
                                item = item,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) {
                                        if (isSelected) selectedVaultItems.remove(item)
                                        else selectedVaultItems.add(item)
                                        if (selectedVaultItems.isEmpty()) isSelectionMode = false
                                    } else {
                                        activeViewerItem = item
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        selectedVaultItems.add(item)
                                    }
                                }
                            )
                        }
                    }
                }
            } else {
                // List View
                val listState = listStates[pageIndex]
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (groupedByDate != null) {
                        groupedByDate.forEach { (header, itemsInDate) ->
                            item {
                                DateSectionHeader(title = header, count = itemsInDate.size)
                            }
                            items(itemsInDate, key = { it.id }) { item ->
                                val isSelected = selectedVaultItems.contains(item)
                                VaultListItem(
                                    item = item,
                                    isSelected = isSelected,
                                    isSelectionMode = isSelectionMode,
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (isSelected) selectedVaultItems.remove(item)
                                            else selectedVaultItems.add(item)
                                            if (selectedVaultItems.isEmpty()) isSelectionMode = false
                                        } else {
                                            activeViewerItem = item
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            selectedVaultItems.add(item)
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        items(pageItems, key = { it.id }) { item ->
                            val isSelected = selectedVaultItems.contains(item)
                            VaultListItem(
                                item = item,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) {
                                        if (isSelected) selectedVaultItems.remove(item)
                                        else selectedVaultItems.add(item)
                                        if (selectedVaultItems.isEmpty()) isSelectionMode = false
                                    } else {
                                        activeViewerItem = item
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        selectedVaultItems.add(item)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Media Viewer Overlay
    // -------------------------------------------------------------------------
    activeViewerItem?.let { item ->
        VaultMediaViewerDialog(
            item = item,
            onDismiss = { activeViewerItem = null },
            onRestore = {
                showRestoreConfirmDialog = listOf(item)
            },
            onDelete = {
                showDeleteConfirmDialog = listOf(item)
            },
            onShare = {
                shareVaultItems(context, listOf(item))
            }
        )
    }

    // -------------------------------------------------------------------------
    // Restore Dialog
    // -------------------------------------------------------------------------
    showRestoreConfirmDialog?.let { itemsToRestore ->
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = null },
            icon = { Icon(Icons.Default.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.vault_restore_title)) },
            text = { Text(stringResource(R.string.vault_restore_message, itemsToRestore.size)) },
            confirmButton = {
                Button(
                    onClick = {
                        val toRestore = itemsToRestore.toList()
                        showRestoreConfirmDialog = null
                        if (activeViewerItem != null && toRestore.contains(activeViewerItem)) {
                            activeViewerItem = null
                        }
                        if (isSelectionMode) {
                            isSelectionMode = false
                            selectedVaultItems.clear()
                        }
                        viewModel.restoreVaultItems(toRestore) { count ->
                            Toast.makeText(context, context.getString(R.string.toast_restored_from_vault), Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    // -------------------------------------------------------------------------
    // Delete Confirmation Dialog
    // -------------------------------------------------------------------------
    showDeleteConfirmDialog?.let { itemsToDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.vault_delete_title)) },
            text = { Text(stringResource(R.string.vault_delete_message, itemsToDelete.size)) },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = itemsToDelete.toList()
                        showDeleteConfirmDialog = null
                        if (activeViewerItem != null && toDelete.contains(activeViewerItem)) {
                            activeViewerItem = null
                        }
                        if (isSelectionMode) {
                            isSelectionMode = false
                            selectedVaultItems.clear()
                        }
                        viewModel.deleteVaultItemsPermanently(toDelete)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    // -------------------------------------------------------------------------
    // Change PIN Dialog
    // -------------------------------------------------------------------------
    if (showChangePinDialog) {
        ChangePinDialog(
            onDismiss = { showChangePinDialog = false },
            onSuccess = {
                showChangePinDialog = false
                Toast.makeText(context, context.getString(R.string.vault_pin_changed_success), Toast.LENGTH_SHORT).show()
            }
        )
    }

    // -------------------------------------------------------------------------
    // Custom File Manager File Picker Dialog
    // -------------------------------------------------------------------------
    if (showCustomFilePicker) {
        VaultCustomFilePickerDialog(
            onDismiss = { showCustomFilePicker = false },
            onConfirmFiles = { filesToMove ->
                showCustomFilePicker = false
                if (filesToMove.isNotEmpty()) {
                    val fileItems = filesToMove.map { FileItem(it) }
                    viewModel.moveToVault(fileItems) { count ->
                        if (count > 0) {
                            Toast.makeText(context, context.getString(R.string.toast_moved_to_safe), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun VaultCategoryFilterRow(
    selectedCategory: VaultCategory,
    onCategorySelected: (VaultCategory) -> Unit,
    counts: Map<VaultCategory, Int>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(selectedCategory) {
        val allCats = VaultCategory.entries
        val selectedIndex = allCats.indexOf(selectedCategory)
        if (selectedIndex >= 0 && allCats.size > 1) {
            val maxScroll = scrollState.maxValue
            if (maxScroll > 0) {
                val targetScroll = (maxScroll * (selectedIndex.toFloat() / (allCats.size - 1))).toInt()
                scrollState.animateScrollTo(targetScroll)
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VaultCategory.entries.forEach { cat ->
            val isSelected = cat == selectedCategory
            val count = counts[cat] ?: 0

            val label = when (cat) {
                VaultCategory.ALL -> stringResource(R.string.vault_tab_all)
                VaultCategory.PHOTOS -> stringResource(R.string.vault_tab_photos)
                VaultCategory.VIDEOS -> stringResource(R.string.vault_tab_videos)
                VaultCategory.MUSIC -> stringResource(R.string.vault_tab_music)
                VaultCategory.DOCUMENTS -> stringResource(R.string.vault_tab_documents)
                VaultCategory.OTHER -> stringResource(R.string.vault_tab_other)
            }

            val icon = when (cat) {
                VaultCategory.ALL -> Icons.Default.AllInclusive
                VaultCategory.PHOTOS -> Icons.Default.Image
                VaultCategory.VIDEOS -> Icons.Default.Movie
                VaultCategory.MUSIC -> Icons.Default.MusicNote
                VaultCategory.DOCUMENTS -> Icons.Default.Description
                VaultCategory.OTHER -> Icons.Default.Category
            }

            val accentColor = when (cat) {
                VaultCategory.ALL -> MaterialTheme.colorScheme.primary
                VaultCategory.PHOTOS -> Color(0xFF38BDF8)
                VaultCategory.VIDEOS -> Color(0xFFA855F7)
                VaultCategory.MUSIC -> Color(0xFFF43F5E)
                VaultCategory.DOCUMENTS -> Color(0xFF10B981)
                VaultCategory.OTHER -> Color(0xFFF59E0B)
            }

            val animatedBg by animateColorAsState(
                targetValue = if (isSelected) accentColor.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                label = "VaultCatBg"
            )

            val animatedBorderColor by animateColorAsState(
                targetValue = if (isSelected) accentColor
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                label = "VaultCatBorder"
            )

            val animatedContentColor by animateColorAsState(
                targetValue = if (isSelected) accentColor
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(180, easing = FastOutSlowInEasing),
                label = "VaultCatContent"
            )

            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onCategorySelected(cat) }
                    .border(
                        BorderStroke(if (isSelected) 1.5.dp else 1.dp, animatedBorderColor),
                        shape = RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                color = animatedBg
            ) {
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = animatedContentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = animatedContentColor
                    )
                    if (count > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) accentColor else MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (count > 99) "99+" else "$count",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultCustomFilePickerDialog(
    onDismiss: () -> Unit,
    onConfirmFiles: (List<File>) -> Unit
) {
    val rootDir = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(rootDir) }
    val selectedFiles = remember { mutableStateListOf<File>() }

    // List of files and folders in current directory (ignoring hidden & vault folder)
    val directoryContents = remember(currentDir) {
        val all = currentDir.listFiles { f ->
            !f.name.startsWith(".") && f.name != ".rose_vault"
        }?.toList() ?: emptyList()

        val folders = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val regularFiles = all.filter { !it.isDirectory }.sortedBy { it.name.lowercase() }
        folders + regularFiles
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler {
            if (currentDir != rootDir) {
                currentDir.parentFile?.let { currentDir = it }
            } else {
                onDismiss()
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                stringResource(R.string.vault_picker_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (currentDir == rootDir) stringResource(R.string.breadcrumbs_internal_storage) else currentDir.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    },
                    actions = {
                        val selectableFilesInCurrentDir = directoryContents.filter { !it.isDirectory }
                        if (selectableFilesInCurrentDir.isNotEmpty()) {
                            val areAllSelected = selectableFilesInCurrentDir.all { selectedFiles.contains(it) }
                            IconButton(onClick = {
                                if (areAllSelected) {
                                    selectedFiles.removeAll(selectableFilesInCurrentDir)
                                } else {
                                    selectableFilesInCurrentDir.forEach { file ->
                                        if (!selectedFiles.contains(file)) {
                                            selectedFiles.add(file)
                                        }
                                    }
                                }
                            }) {
                                Icon(
                                    imageVector = if (areAllSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                    contentDescription = if (areAllSelected) "Deselect All" else "Select All"
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = {
                                onConfirmFiles(selectedFiles.toList())
                            },
                            enabled = selectedFiles.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.vault_picker_select_button, selectedFiles.size),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Interactive Breadcrumb Header
                Breadcrumbs(
                    path = currentDir.absolutePath,
                    storageDevices = emptyList(),
                    onNavigate = { currentDir = it }
                )

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                if (directoryContents.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.no_files_found),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        itemsIndexed(
                            items = directoryContents,
                            key = { _, file -> file.absolutePath }
                        ) { _, file ->
                            val isDir = file.isDirectory
                            val isSelected = selectedFiles.contains(file)
                            val fileItem = remember(file.absolutePath, file.lastModified()) { FileItem(file) }

                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = file.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isDir) FontWeight.SemiBold else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                supportingContent = {
                                    val dateStr = SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))
                                    if (isDir) {
                                        val count = file.list { _, name -> !name.startsWith(".") }?.size ?: 0
                                        Text(stringResource(R.string.item_count_and_date, count, dateStr))
                                    } else {
                                        Text(stringResource(R.string.item_size_and_date, formatFileSize(file.length()), dateStr))
                                    }
                                },
                                leadingContent = {
                                    FileIcon(
                                        fileItem = fileItem,
                                        iconSize = 40.dp
                                    )
                                },
                                trailingContent = {
                                    if (isDir) {
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    } else {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                if (checked) selectedFiles.add(file)
                                                else selectedFiles.remove(file)
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isDir) {
                                            currentDir = file
                                        } else {
                                            if (isSelected) selectedFiles.remove(file)
                                            else selectedFiles.add(file)
                                        }
                                    }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Vault Item Card with Asynchronous Thumbnail Generation
// -----------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultGridItem(
    item: VaultItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        val file = PrivateVaultManager.getVaultFile(context, item)
        if (item.fileType == FileType.IMAGE || item.fileType == FileType.VIDEO) {
            thumbnail = VaultThumbnailLoader.loadThumbnail(file, item.fileType)
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainer,
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = item.originalName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val icon = when (item.fileType) {
                        FileType.VIDEO -> Icons.Default.Videocam
                        FileType.IMAGE -> Icons.Default.Image
                        FileType.AUDIO -> Icons.Default.MusicNote
                        FileType.DOCUMENT -> Icons.Default.Description
                        FileType.APK -> Icons.Default.Android
                        FileType.ZIP -> Icons.Default.Archive
                        else -> Icons.Default.InsertDriveFile
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Video badge
                if (item.fileType == FileType.VIDEO) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Selection Checkbox
                if (isSelectionMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .background(
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Filename & size label
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = item.originalName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(item.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun DateSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "$count file${if (count != 1) "s" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultListItem(
    item: VaultItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        val file = PrivateVaultManager.getVaultFile(context, item)
        if (item.fileType == FileType.IMAGE || item.fileType == FileType.VIDEO) {
            thumbnail = VaultThumbnailLoader.loadThumbnail(file, item.fileType)
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail / Icon
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = item.originalName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val icon = when (item.fileType) {
                        FileType.VIDEO -> Icons.Default.Videocam
                        FileType.IMAGE -> Icons.Default.Image
                        FileType.AUDIO -> Icons.Default.MusicNote
                        FileType.DOCUMENT, FileType.PDF -> Icons.AutoMirrored.Filled.InsertDriveFile
                        FileType.APK -> Icons.Default.Android
                        FileType.ZIP -> Icons.Default.Archive
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (item.fileType == FileType.VIDEO) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(22.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // File Name and Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.originalName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                val dateStr = remember(item.originalLastModified) {
                    val ts = if (item.originalLastModified > 0L) item.originalLastModified else item.dateAdded
                    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ts))
                }
                Text(
                    text = "${formatFileSize(item.size)} • $dateStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Selection indicator
            if (isSelectionMode) {
                Spacer(modifier = Modifier.width(8.dp))
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
            }
        }
    }
}

private fun formatVaultDateHeader(context: Context, timestamp: Long): String {
    if (timestamp <= 0L) return "Unknown Date"
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
        actualDiffDays in 2..7 -> context.getString(R.string.date_days_ago, actualDiffDays)
        else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

// -----------------------------------------------------------------------------
// Screen Brightness Helpers for Vault Viewers (Supports Dialog & Activity Windows)
// -----------------------------------------------------------------------------

private fun getSystemScreenBrightness(context: Context): Float {
    return try {
        android.provider.Settings.System.getInt(
            context.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS
        ) / 255f
    } catch (_: Exception) {
        0.5f
    }
}

private fun setVaultScreenBrightness(view: View, context: Context, brightness: Float) {
    var parent = view.parent
    var dialogWin: Window? = null
    while (parent != null) {
        if (parent is DialogWindowProvider) {
            dialogWin = parent.window
            break
        }
        parent = (parent as? View)?.parent
    }
    val actWin = (context as? Activity)?.window

    dialogWin?.let { w ->
        val lp = w.attributes
        lp.screenBrightness = brightness
        w.attributes = lp
    }
    actWin?.let { w ->
        val lp = w.attributes
        lp.screenBrightness = brightness
        w.attributes = lp
    }
}

private fun restoreVaultBrightness(view: View, context: Context) {
    setVaultScreenBrightness(view, context, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
}

// -----------------------------------------------------------------------------
// In-Vault Media Viewer Dialog (Images, Videos, and Files)
// -----------------------------------------------------------------------------

@Composable
private fun VaultMediaViewerDialog(
    item: VaultItem,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val vaultFile = remember(item.id) { PrivateVaultManager.getVaultFile(context, item) }
    var isPlaying by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var showVideoExitOptions by remember { mutableStateOf(false) }

    // Auto-hide controls after 3.5 seconds if media is playing
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3500)
            showControls = false
        }
    }

    val handleBack: () -> Unit = {
        if (item.fileType == FileType.VIDEO && !showVideoExitOptions) {
            isPlaying = false
            showVideoExitOptions = true
        } else {
            onDismiss()
        }
    }

    BackHandler(onBack = handleBack)

    Dialog(
        onDismissRequest = handleBack,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val view = LocalView.current

        // Restore orientation and screen brightness when media viewer is disposed
        DisposableEffect(view, context) {
            onDispose {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                restoreVaultBrightness(view, context)
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // If video exit overview is requested
                if (item.fileType == FileType.VIDEO && showVideoExitOptions) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 90.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(96.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = item.originalName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "${formatFileSize(item.size)} • ${item.mimeType ?: "Video"}",
                            color = Color.White.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.bodySmall
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = {
                                showVideoExitOptions = false
                                isPlaying = true
                                showControls = true
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Resume Video",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    // Media Content
                    when (item.fileType) {
                        FileType.IMAGE -> {
                            ZoomableVaultImage(
                                file = vaultFile,
                                altName = item.originalName,
                                onToggleControls = { showControls = !showControls }
                            )
                        }
                        FileType.VIDEO -> {
                            VaultVideoPlayer(
                                file = vaultFile,
                                title = item.originalName,
                                isPlaying = isPlaying,
                                onPlayingChanged = { isPlaying = it },
                                showControls = showControls,
                                onToggleControls = { showControls = !showControls },
                                onBack = handleBack
                            )
                        }
                        FileType.AUDIO -> {
                            VaultAudioPlayer(
                                file = vaultFile,
                                altName = item.originalName,
                                isPlaying = isPlaying,
                                onPlayingChanged = { isPlaying = it }
                            )
                        }
                        else -> {
                            // Generic File info card
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    item.originalName,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "${formatFileSize(item.size)} • ${item.mimeType ?: "Unknown type"}",
                                    color = Color.White.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // Top Bar (Shown for non-videos or when video exit options are active)
                val showDialogTopBar = (item.fileType != FileType.VIDEO && showControls) ||
                        (item.fileType == FileType.VIDEO && showVideoExitOptions)

                AnimatedVisibility(
                    visible = showDialogTopBar,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it },
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            item.originalName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.fileType == FileType.IMAGE) {
                            IconButton(
                                onClick = {
                                    activity?.let { act ->
                                        act.requestedOrientation = if (isLandscape) {
                                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        } else {
                                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ScreenRotation,
                                    contentDescription = "Rotate",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Bottom Action Bar (Restore, Share, Delete)
                // FOR VIDEO: ONLY appears when user clicked back and exit options are active!
                // FOR OTHER TYPES: appears when showControls is true.
                val showBottomActions = if (item.fileType == FileType.VIDEO) {
                    showVideoExitOptions
                } else {
                    showControls
                }

                AnimatedVisibility(
                    visible = showBottomActions,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(22.dp)),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onRestore) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Restore, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.action_restore), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        TextButton(onClick = onShare) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.action_share), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        TextButton(onClick = onDelete) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableVaultImage(
    file: File,
    altName: String,
    onToggleControls: () -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(file.absolutePath) {
        isLoading = true
        bitmap = VaultThumbnailLoader.decodeFullImage(file)
        isLoading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        offset = Offset.Zero
                    },
                    onTap = {
                        onToggleControls()
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offset += pan
                    } else {
                        offset = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = altName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        } else if (isLoading) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            // Friendly error card when image cannot be decoded
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = altName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Unable to load preview. File may be corrupted or unsupported.",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

enum class VideoFitMode(val label: String, val scale: Float) {
    FIT("Fit", 1.0f),
    FILL("Fill", 1.35f),
    ZOOM("125%", 1.6f);

    fun next(): VideoFitMode = when (this) {
        FIT -> FILL
        FILL -> ZOOM
        ZOOM -> FIT
    }
}

private enum class VideoGestureType {
    BRIGHTNESS, VOLUME
}

@Composable
private fun QuickGestureIndicator(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    percent: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.8f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )

            // Vertical indicator track
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(percent.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }

            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun VaultVideoPlayer(
    file: File,
    title: String,
    isPlaying: Boolean,
    onPlayingChanged: (Boolean) -> Unit,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentPosMs by remember { mutableIntStateOf(0) }
    var isReady by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }

    var fitMode by remember { mutableStateOf(VideoFitMode.FIT) }
    var isMuted by remember { mutableStateOf(false) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var currentVolume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var volumeFraction by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
    }

    val initialBrightness = remember { getSystemScreenBrightness(context) }
    var currentBrightness by remember { mutableFloatStateOf(initialBrightness.coerceIn(0.01f, 1f)) }

    var activeGesture by remember { mutableStateOf<VideoGestureType?>(null) }
    var gestureTriggerTime by remember { mutableLongStateOf(0L) }

    // Auto-dismiss gesture popup after 1.2s
    LaunchedEffect(gestureTriggerTime) {
        if (gestureTriggerTime > 0L) {
            delay(1200)
            activeGesture = null
        }
    }

    // Restore orientation and screen brightness when video player is disposed
    DisposableEffect(view, context) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            restoreVaultBrightness(view, context)
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            videoViewRef?.let { vv ->
                if (vv.isPlaying) {
                    currentPosMs = vv.currentPosition
                }
            }
            delay(250)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Video View with scale/fit applied
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = fitMode.scale, scaleY = fitMode.scale),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(file.absolutePath)
                        setOnPreparedListener { mp ->
                            mediaPlayerRef = mp
                            mp.isLooping = false
                            if (isMuted) {
                                mp.setVolume(0f, 0f)
                            } else {
                                mp.setVolume(1f, 1f)
                            }
                            durationMs = duration
                            isReady = true
                            start()
                            onPlayingChanged(true)
                        }
                        setOnCompletionListener {
                            onPlayingChanged(false)
                            currentPosMs = durationMs
                        }
                        setOnErrorListener { _, _, _ ->
                            hasError = true
                            onPlayingChanged(false)
                            true
                        }
                        videoViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Gesture Detection Overlay (Taps toggle controls; vertical drag adjusts brightness on left, volume on right)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val isLeft = down.position.x < (size.width / 2f)
                        var hasDragged = false
                        val touchSlop = viewConfiguration.touchSlop
                        val initialY = down.position.y

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break

                            if (!change.pressed) {
                                if (!hasDragged) {
                                    onToggleControls()
                                } else {
                                    gestureTriggerTime = System.currentTimeMillis()
                                }
                                break
                            }

                            val totalDragY = change.position.y - initialY
                            if (!hasDragged && abs(totalDragY) > touchSlop) {
                                hasDragged = true
                                activeGesture = if (isLeft) VideoGestureType.BRIGHTNESS else VideoGestureType.VOLUME
                            }

                            if (hasDragged) {
                                val dragDelta = change.previousPosition.y - change.position.y
                                val heightFactor = size.height * 0.75f
                                val delta = dragDelta / heightFactor

                                if (isLeft) {
                                    currentBrightness = (currentBrightness + delta).coerceIn(0.01f, 1.0f)
                                    setVaultScreenBrightness(view, context, currentBrightness)
                                    activeGesture = VideoGestureType.BRIGHTNESS
                                } else {
                                    volumeFraction = (volumeFraction + delta).coerceIn(0f, 1.0f)
                                    val newVol = (volumeFraction * maxVolume).toInt().coerceIn(0, maxVolume)
                                    if (newVol != currentVolume) {
                                        currentVolume = newVol
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                    }
                                    activeGesture = VideoGestureType.VOLUME
                                }
                                gestureTriggerTime = System.currentTimeMillis()
                                change.consume()
                            }
                        }
                    }
                }
        )

        // Gesture Popups (Brightness on Left, Volume on Right)
        AnimatedVisibility(
            visible = activeGesture == VideoGestureType.BRIGHTNESS,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 28.dp)
        ) {
            QuickGestureIndicator(
                icon = Icons.Default.WbSunny,
                label = "${(currentBrightness * 100).toInt()}%",
                percent = currentBrightness
            )
        }

        AnimatedVisibility(
            visible = activeGesture == VideoGestureType.VOLUME,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 28.dp)
        ) {
            QuickGestureIndicator(
                icon = if (currentVolume == 0) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                label = "${(volumeFraction * 100).toInt()}%",
                percent = volumeFraction
            )
        }

        if (hasError) {
            Column(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Unable to play video directly",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "You can restore or share it to open with an external player.",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        } else if (!isReady) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            // Top Controls Bar (Back, Title, Rotate, Fit Mode, Mute)
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Rotate toggle button
                    IconButton(
                        onClick = {
                            activity?.let { act ->
                                act.requestedOrientation = if (isLandscape) {
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "Rotate",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Fit Mode button
                    Surface(
                        onClick = { fitMode = fitMode.next() },
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.15f),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = "Fit Mode",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = fitMode.label,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Mute toggle button
                    IconButton(
                        onClick = {
                            isMuted = !isMuted
                            mediaPlayerRef?.let { mp ->
                                if (isMuted) {
                                    mp.setVolume(0f, 0f)
                                } else {
                                    mp.setVolume(1f, 1f)
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = if (isMuted) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Center Play / Pause button
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    onClick = {
                        videoViewRef?.let { vv ->
                            if (vv.isPlaying) {
                                vv.pause()
                                onPlayingChanged(false)
                            } else {
                                if (currentPosMs >= durationMs && durationMs > 0) {
                                    vv.seekTo(0)
                                    currentPosMs = 0
                                }
                                vv.start()
                                onPlayingChanged(true)
                            }
                        }
                    },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.size(68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                currentPosMs >= durationMs && durationMs > 0 -> Icons.Default.Replay
                                isPlaying -> Icons.Default.Pause
                                else -> Icons.Default.PlayArrow
                            },
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            // Simple Bottom Progress Bar
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTimeMs(currentPosMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Slider(
                            value = currentPosMs.toFloat().coerceIn(0f, maxOf(durationMs.toFloat(), 1f)),
                            onValueChange = { currentPosMs = it.toInt() },
                            onValueChangeFinished = {
                                videoViewRef?.seekTo(currentPosMs)
                            },
                            valueRange = 0f..maxOf(durationMs.toFloat(), 1f),
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = formatTimeMs(durationMs),
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultAudioPlayer(
    file: File,
    altName: String,
    isPlaying: Boolean,
    onPlayingChanged: (Boolean) -> Unit
) {
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var durationMs by remember { mutableIntStateOf(0) }
    var currentPosMs by remember { mutableIntStateOf(0) }

    DisposableEffect(file.absolutePath) {
        val player = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepare()
                durationMs = duration
                setOnCompletionListener {
                    onPlayingChanged(false)
                    currentPosMs = duration
                }
            } catch (e: Exception) {}
        }
        mediaPlayer = player

        onDispose {
            try {
                player.stop()
                player.release()
            } catch (e: Exception) {}
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    currentPosMs = mp.currentPosition
                }
            }
            delay(250)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = altName,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Slider(
            value = currentPosMs.toFloat().coerceIn(0f, maxOf(durationMs.toFloat(), 1f)),
            onValueChange = { currentPosMs = it.toInt() },
            onValueChangeFinished = {
                mediaPlayer?.seekTo(currentPosMs)
            },
            valueRange = 0f..maxOf(durationMs.toFloat(), 1f),
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatTimeMs(currentPosMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            Text(text = formatTimeMs(durationMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Surface(
            onClick = {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        mp.pause()
                        onPlayingChanged(false)
                    } else {
                        if (currentPosMs >= durationMs && durationMs > 0) {
                            mp.seekTo(0)
                            currentPosMs = 0
                        }
                        mp.start()
                        onPlayingChanged(true)
                    }
                }
            },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

private fun formatTimeMs(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

// -----------------------------------------------------------------------------
// Change PIN Dialog
// -----------------------------------------------------------------------------

@Composable
private fun ChangePinDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val hasPin = remember { PrivateVaultManager.isPinSet(context) }
    var step by remember { mutableIntStateOf(if (hasPin) 0 else 1) } // 0: current, 1: new, 2: confirm
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (step) {
                    0 -> stringResource(R.string.vault_change_pin_current_title)
                    1 -> stringResource(R.string.vault_change_pin_new_title)
                    else -> stringResource(R.string.vault_confirm_pin_title)
                }
            )
        },
        text = {
            Column {
                val value = when (step) {
                    0 -> currentPin
                    1 -> newPin
                    else -> confirmPin
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        if (input.length <= 4 && input.all { it.isDigit() }) {
                            when (step) {
                                0 -> currentPin = input
                                1 -> newPin = input
                                else -> confirmPin = input
                            }
                            error = ""
                        }
                    },
                    label = { Text("4-digit PIN") },
                    singleLine = true,
                    isError = error.isNotEmpty(),
                    supportingText = if (error.isNotEmpty()) { { Text(error) } } else null
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (step) {
                        0 -> {
                            if (PrivateVaultManager.verifyPin(context, currentPin)) {
                                step = 1
                            } else {
                                error = context.getString(R.string.vault_wrong_pin)
                            }
                        }
                        1 -> {
                            if (newPin.length == 4) {
                                step = 2
                            } else {
                                error = "PIN must be 4 digits"
                            }
                        }
                        2 -> {
                            if (confirmPin == newPin) {
                                val ok = PrivateVaultManager.changePin(context, currentPin, newPin)
                                if (ok) {
                                    onSuccess()
                                } else {
                                    error = "Failed to update PIN"
                                }
                            } else {
                                error = context.getString(R.string.vault_pin_mismatch)
                            }
                        }
                    }
                }
            ) {
                Text(if (step == 2) stringResource(R.string.action_done) else "Next")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

// -----------------------------------------------------------------------------
// Share Helper: Export with original filename and MIME type
// -----------------------------------------------------------------------------

private fun shareVaultItems(context: Context, items: List<VaultItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList<Uri>()
    val tempDir = File(context.cacheDir, "vault_share")
    if (!tempDir.exists()) tempDir.mkdirs()

    items.forEach { item ->
        val vaultFile = PrivateVaultManager.getVaultFile(context, item)
        if (vaultFile.exists()) {
            val shareFile = File(tempDir, item.originalName)
            try {
                FileInputStream(vaultFile).use { input ->
                    FileOutputStream(shareFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    shareFile
                )
                uris.add(uri)
            } catch (e: Exception) {}
        }
    }

    if (uris.isNotEmpty()) {
        val intent = Intent().apply {
            action = if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
            if (uris.size > 1) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            } else {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
            type = if (uris.size > 1) "*/*" else (items.first().mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share_via)))
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
    }
    return uri.path?.substringAfterLast('/')
}
