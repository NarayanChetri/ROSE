package dev.narayan.rose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

enum class VaultSortBy {
    NAME, DATE, SIZE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateVaultScreen(
    viewModel: RoseViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isPinConfigured = remember { mutableStateOf(PrivateVaultManager.isPinSet(context)) }

    // Auto-authenticate with biometric if available and configured
    LaunchedEffect(isPinConfigured.value, viewModel.isVaultUnlocked) {
        if (isPinConfigured.value && !viewModel.isVaultUnlocked && viewModel.vaultBiometricEnabled) {
            val activity = context as? FragmentActivity
            if (activity != null && VaultSecurityManager.isBiometricAvailable(context)) {
                VaultSecurityManager.authenticate(
                    activity = activity,
                    title = context.getString(R.string.vault_biometric_prompt_title),
                    subtitle = context.getString(R.string.vault_biometric_prompt_subtitle),
                    onSuccess = {
                        viewModel.unlockVault()
                    },
                    onError = {
                        // Keep PIN keypad visible
                    }
                )
            }
        }
    }

    if (!viewModel.isVaultUnlocked) {
        VaultLockView(
            isPinConfigured = isPinConfigured.value,
            onPinVerified = {
                viewModel.unlockVault()
            },
            onPinSetupComplete = {
                isPinConfigured.value = true
                viewModel.unlockVault()
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
            isBiometricAvailable = VaultSecurityManager.isBiometricAvailable(context) && viewModel.vaultBiometricEnabled,
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
// PIN & Lock Keypad Screen
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultLockView(
    isPinConfigured: Boolean,
    onPinVerified: () -> Unit,
    onPinSetupComplete: () -> Unit,
    onBiometricRequested: () -> Unit,
    isBiometricAvailable: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    var pinSetupStep by remember { mutableIntStateOf(0) } // 0: enter pin, 1: confirm pin
    var setupPinInitial by remember { mutableStateOf("") }

    var enteredPin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    fun handlePinInput(digit: String) {
        if (enteredPin.length < 4) {
            val newPin = enteredPin + digit
            enteredPin = newPin
            isError = false
            errorMessage = ""

            if (newPin.length == 4) {
                if (!isPinConfigured) {
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
                                onPinSetupComplete()
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vault_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Title & Instruction
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                val headerTitle = when {
                    !isPinConfigured && pinSetupStep == 0 -> stringResource(R.string.vault_setup_pin_title)
                    !isPinConfigured && pinSetupStep == 1 -> stringResource(R.string.vault_confirm_pin_title)
                    else -> stringResource(R.string.vault_enter_pin_title)
                }
                val headerDesc = when {
                    !isPinConfigured && pinSetupStep == 0 -> stringResource(R.string.vault_setup_pin_desc)
                    !isPinConfigured && pinSetupStep == 1 -> stringResource(R.string.vault_confirm_pin_desc)
                    else -> stringResource(R.string.vault_enter_pin_desc)
                }

                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = headerDesc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                // PIN Dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { index ->
                        val isFilled = index < enteredPin.length
                        val dotColor by animateColorAsState(
                            targetValue = when {
                                isError -> MaterialTheme.colorScheme.error
                                isFilled -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            animationSpec = tween(durationMillis = 180),
                            label = "DotColor"
                        )
                        val dotScale by animateFloatAsState(
                            targetValue = if (isFilled) 1.25f else 1f,
                            animationSpec = tween(durationMillis = 180),
                            label = "DotScale"
                        )

                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer(scaleX = dotScale, scaleY = dotScale)
                                .background(dotColor, CircleShape)
                        )
                    }
                }

                // Error Message
                if (errorMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Keypad
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("BIO", "0", "DEL")
                )

                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { key ->
                            when (key) {
                                "BIO" -> {
                                    if (isBiometricAvailable && isPinConfigured) {
                                        KeypadButton(
                                            onClick = onBiometricRequested,
                                            content = {
                                                Icon(
                                                    Icons.Default.Fingerprint,
                                                    contentDescription = "Biometric",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.size(72.dp))
                                    }
                                }
                                "DEL" -> {
                                    KeypadButton(
                                        onClick = { handleDelete() },
                                        content = {
                                            Icon(
                                                Icons.Default.Backspace,
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    )
                                }
                                else -> {
                                    KeypadButton(
                                        onClick = { handlePinInput(key) },
                                        content = {
                                            Text(
                                                text = key,
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 26.sp
                                            )
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
}

@Composable
private fun KeypadButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(72.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

// -----------------------------------------------------------------------------
// Vault Content Screen (Unlocked Mode)
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
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

    // File Import Picker (Add files to safe)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                var addedCount = 0
                for (uri in uris) {
                    try {
                        val displayName = getFileNameFromUri(context, uri) ?: "file_${System.currentTimeMillis()}"
                        val tempFile = File(context.cacheDir, displayName)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            val ok = PrivateVaultManager.moveToVault(context, tempFile)
                            if (ok) addedCount++
                        }
                    } catch (e: Exception) {}
                }
                withContext(Dispatchers.Main) {
                    if (addedCount > 0) {
                        viewModel.loadVaultItems()
                        Toast.makeText(context, context.getString(R.string.toast_moved_to_safe), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val items = remember(viewModel.vaultItems, sortBy, sortAscending) {
        val sorted = when (sortBy) {
            VaultSortBy.NAME -> viewModel.vaultItems.sortedBy { it.originalName.lowercase() }
            VaultSortBy.DATE -> viewModel.vaultItems.sortedBy { it.dateAdded }
            VaultSortBy.SIZE -> viewModel.vaultItems.sortedBy { it.size }
        }
        if (sortAscending) sorted else sorted.reversed()
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
                        IconButton(onClick = {
                            if (selectedVaultItems.size == items.size) {
                                selectedVaultItems.clear()
                            } else {
                                selectedVaultItems.clear()
                                selectedVaultItems.addAll(items)
                            }
                        }) {
                            Icon(
                                if (selectedVaultItems.size == items.size) Icons.Default.Deselect else Icons.Default.SelectAll,
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
                            val totalSize = items.sumOf { it.size }
                            Text(
                                "${items.size} file${if (items.size != 1) "s" else ""} • ${formatFileSize(totalSize)}",
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
                                Icon(Icons.Default.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Date added") },
                                    onClick = {
                                        if (sortBy == VaultSortBy.DATE) sortAscending = !sortAscending
                                        else { sortBy = VaultSortBy.DATE; sortAscending = false }
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (sortBy == VaultSortBy.DATE) {
                                            Icon(if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Name") },
                                    onClick = {
                                        if (sortBy == VaultSortBy.NAME) sortAscending = !sortAscending
                                        else { sortBy = VaultSortBy.NAME; sortAscending = true }
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (sortBy == VaultSortBy.NAME) {
                                            Icon(if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Size") },
                                    onClick = {
                                        if (sortBy == VaultSortBy.SIZE) sortAscending = !sortAscending
                                        else { sortBy = VaultSortBy.SIZE; sortAscending = false }
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (sortBy == VaultSortBy.SIZE) {
                                            Icon(if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null)
                                        }
                                    }
                                )
                            }
                        }

                        // Lock action
                        IconButton(onClick = {
                            viewModel.lockVault()
                            Toast.makeText(context, context.getString(R.string.toast_vault_locked), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.vault_lock_now))
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
                        importLauncher.launch(arrayOf("*/*"))
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
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            if (items.isEmpty()) {
                // Empty Vault State
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
                        stringResource(R.string.vault_empty_title),
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
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.vault_add_files))
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }) { item ->
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
    val vaultFile = remember(item.id) { PrivateVaultManager.getVaultFile(context, item) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Media Content
                when (item.fileType) {
                    FileType.IMAGE -> {
                        ZoomableVaultImage(file = vaultFile, altName = item.originalName)
                    }
                    FileType.VIDEO -> {
                        VaultVideoPlayer(file = vaultFile)
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

                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .align(Alignment.TopCenter),
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
                }

                // Bottom Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onRestore) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Restore, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.action_restore), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    TextButton(onClick = onShare) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.action_share), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    TextButton(onClick = onDelete) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableVaultImage(file: File, altName: String) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(file.absolutePath) {
        withContext(Dispatchers.IO) {
            try {
                bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
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
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun VaultVideoPlayer(file: File) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val mediaController = MediaController(ctx)
                    mediaController.setAnchorView(this)
                    setMediaController(mediaController)
                    setVideoPath(file.absolutePath)
                    setOnPreparedListener { mp ->
                        mp.isLooping = false
                        start()
                        isPlaying = true
                    }
                    setOnCompletionListener {
                        isPlaying = false
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
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
    var step by remember { mutableIntStateOf(0) } // 0: current, 1: new, 2: confirm
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
