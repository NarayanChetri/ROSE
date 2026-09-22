package dev.narayan.rose.document

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import dev.narayan.rose.ArchiveManager
import dev.narayan.rose.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    source: DocumentSource,
    isMarkdownFile: Boolean,
    initialMode: DocumentMode = DocumentMode.VIEW,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var content by rememberSaveable { mutableStateOf("") }
    var originalContent by rememberSaveable { mutableStateOf("") }
    var charsetName by rememberSaveable { mutableStateOf("UTF-8") }
    var lineEnding by rememberSaveable { mutableStateOf(LineEnding.LF) }
    var byteSize by rememberSaveable { mutableStateOf(0L) }
    var isTruncated by rememberSaveable { mutableStateOf(false) }

    var currentMode by rememberSaveable { mutableStateOf(initialMode) }
    var wordWrap by rememberSaveable { mutableStateOf(true) }
    var showLineNumbers by rememberSaveable { mutableStateOf(true) }
    var useMonospace by rememberSaveable { mutableStateOf(true) }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var currentMatchIndex by rememberSaveable { mutableStateOf(0) }

    val searchMatches = remember(content, searchQuery, isSearching) {
        if (!isSearching || searchQuery.isBlank()) emptyList()
        else {
            val matches = mutableListOf<Int>()
            var idx = 0
            while (idx < content.length) {
                val found = content.indexOf(searchQuery, idx, ignoreCase = true)
                if (found == -1) break
                matches.add(found)
                idx = found + searchQuery.length.coerceAtLeast(1)
            }
            matches
        }
    }

    // Auto-focus search text field and show keyboard when search opens
    LaunchedEffect(isSearching) {
        if (isSearching) {
            delay(120)
            try {
                searchFocusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {}
        } else {
            keyboardController?.hide()
        }
    }

    var showInfoDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val isDirty by remember { derivedStateOf { content != originalContent } }

    // Load file content on background thread
    LaunchedEffect(source) {
        isLoading = true
        errorMessage = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                when (source) {
                    is DocumentSource.FileSource -> {
                        source.file.inputStream().use { stream ->
                            TextEncodingHelper.readText(stream)
                        }
                    }
                    is DocumentSource.UriSource -> {
                        val stream = context.contentResolver.openInputStream(source.uri)
                            ?: throw Exception("Cannot open stream for ${source.displayName}")
                        stream.use {
                            TextEncodingHelper.readText(it)
                        }
                    }
                    is DocumentSource.VirtualZipSource -> {
                        val baos = ByteArrayOutputStream()
                        ArchiveManager.extractEntry(source.archiveFile, source.entryPath, baos, null)
                        baos.toByteArray().inputStream().use { stream ->
                            TextEncodingHelper.readText(stream)
                        }
                    }
                }
            }

            content = loaded.content
            originalContent = loaded.content
            charsetName = loaded.charsetName
            lineEnding = loaded.lineEnding
            byteSize = loaded.byteSize
            isTruncated = loaded.isTruncated
            isLoading = false
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to open document"
            isLoading = false
        }
    }

    fun handleBack() {
        when {
            isSearching -> {
                isSearching = false
                searchQuery = ""
                keyboardController?.hide()
            }
            currentMode == DocumentMode.EDIT -> {
                if (isDirty && !source.isReadOnly) {
                    showUnsavedDialog = true
                } else {
                    keyboardController?.hide()
                    currentMode = DocumentMode.VIEW
                }
            }
            else -> {
                onBack()
            }
        }
    }

    // Protect unsaved changes on back / return to view mode first
    BackHandler {
        handleBack()
    }

    fun saveDocument(onComplete: () -> Unit = {}) {
        if (source.isReadOnly) {
            Toast.makeText(context, R.string.doc_read_only_warning, Toast.LENGTH_SHORT).show()
            return
        }

        isSaving = true
        scope.launch(Dispatchers.IO) {
            val result = when (source) {
                is DocumentSource.FileSource -> {
                    TextEncodingHelper.saveFile(
                        context = context,
                        targetFile = source.file,
                        content = content,
                        charsetName = charsetName,
                        lineEnding = lineEnding
                    )
                }
                is DocumentSource.UriSource -> {
                    TextEncodingHelper.saveUri(
                        context = context,
                        targetUri = source.uri,
                        content = content,
                        charsetName = charsetName,
                        lineEnding = lineEnding
                    )
                }
                is DocumentSource.VirtualZipSource -> {
                    Result.failure(Exception("Cannot save directly inside archive"))
                }
            }

            withContext(Dispatchers.Main) {
                isSaving = false
                result.fold(
                    onSuccess = {
                        originalContent = content
                        Toast.makeText(context, R.string.doc_saved_success, Toast.LENGTH_SHORT).show()
                        onComplete()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.doc_save_failed, error.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    // Modern Unsaved Changes Confirmation Dialog
    if (showUnsavedDialog) {
        Dialog(
            onDismissRequest = { showUnsavedDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 380.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Icon Badge
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(18.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Title
                    Text(
                        text = stringResource(R.string.doc_unsaved_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // File Badge Card
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = source.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Description
                    Text(
                        text = stringResource(R.string.doc_unsaved_message, source.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Action Buttons - Clean Modern Vertical Stack
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Save (Primary filled button)
                        Button(
                            onClick = {
                                showUnsavedDialog = false
                                saveDocument(onComplete = {
                                    keyboardController?.hide()
                                    currentMode = DocumentMode.VIEW
                                })
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.doc_save),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        // 2. Discard (Outlined destructive button)
                        OutlinedButton(
                            onClick = {
                                showUnsavedDialog = false
                                content = originalContent
                                keyboardController?.hide()
                                currentMode = DocumentMode.VIEW
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.doc_discard),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        // 3. Keep Editing (Subtle text button)
                        TextButton(
                            onClick = { showUnsavedDialog = false },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.doc_keep_editing),
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }

    // Document Details / Stats Dialog
    if (showInfoDialog) {
        val stats = remember(content, byteSize, charsetName, lineEnding) {
            DocumentStats.compute(content, byteSize, charsetName, lineEnding)
        }

        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.doc_info), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatRow(stringResource(R.string.content_desc_file_name), source.title)
                    StatRow(stringResource(R.string.doc_stat_lines), stats.lineCount.toString())
                    StatRow(stringResource(R.string.doc_stat_words), stats.wordCount.toString())
                    StatRow(stringResource(R.string.doc_stat_chars), stats.charCount.toString())
                    StatRow(stringResource(R.string.doc_stat_encoding), stats.encoding)
                    StatRow(stringResource(R.string.doc_stat_line_ending), stats.lineEnding.label)
                    if (source.isReadOnly) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.doc_read_only_warning),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(R.string.action_done))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = source.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isDirty) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            }
                            if (source.isReadOnly) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.doc_read_only),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = when {
                                isMarkdownFile && currentMode == DocumentMode.VIEW -> stringResource(R.string.doc_markdown_reader)
                                currentMode == DocumentMode.VIEW -> "Text Viewer"
                                else -> stringResource(R.string.doc_text_editor)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        handleBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back)
                        )
                    }
                },
                actions = {
                    // Search toggle
                    IconButton(onClick = {
                        isSearching = !isSearching
                        if (!isSearching) {
                            searchQuery = ""
                            keyboardController?.hide()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.action_search),
                            tint = if (isSearching) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Edit button (only visible in VIEW mode)
                    if (currentMode == DocumentMode.VIEW) {
                        IconButton(onClick = {
                            currentMode = DocumentMode.EDIT
                        }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.doc_edit),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Save button
                    if (!source.isReadOnly && currentMode == DocumentMode.EDIT) {
                        IconButton(
                            onClick = { saveDocument() },
                            enabled = isDirty && !isSaving
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = stringResource(R.string.doc_save),
                                    tint = if (isDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    // Overflow Menu
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.content_desc_more_options))
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.doc_info)) },
                                onClick = {
                                    showOverflowMenu = false
                                    showInfoDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                            )
                            if (currentMode == DocumentMode.EDIT) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (wordWrap) "${stringResource(R.string.doc_word_wrap)}: ON" else "${stringResource(R.string.doc_word_wrap)}: OFF")
                                    },
                                    onClick = {
                                        wordWrap = !wordWrap
                                        showOverflowMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.WrapText, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(if (showLineNumbers) "${stringResource(R.string.doc_line_numbers)}: ON" else "${stringResource(R.string.doc_line_numbers)}: OFF")
                                    },
                                    onClick = {
                                        showLineNumbers = !showLineNumbers
                                        showOverflowMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.FormatListNumbered, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(if (useMonospace) "${stringResource(R.string.doc_monospace)}: ON" else "${stringResource(R.string.doc_monospace)}: OFF")
                                    },
                                    onClick = {
                                        useMonospace = !useMonospace
                                        showOverflowMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_share)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onShare()
                                },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.doc_open_external)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onOpenExternal()
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            // Status bar
            if (!isLoading && errorMessage == null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
                        Text(
                            text = "$lines lines  •  ${content.length} chars",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$charsetName  •  ${lineEnding.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            // In-Document Search Bar
            AnimatedVisibility(visible = isSearching) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                currentMatchIndex = 0
                            },
                            placeholder = { Text(stringResource(R.string.doc_search_hint), style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (searchMatches.isNotEmpty()) {
                                        currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(searchFocusRequester)
                        )

                        if (searchQuery.isNotBlank()) {
                            Text(
                                text = if (searchMatches.isEmpty()) {
                                    stringResource(R.string.doc_no_matches)
                                } else {
                                    stringResource(R.string.doc_matches_count, currentMatchIndex + 1, searchMatches.size)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )

                            IconButton(
                                onClick = {
                                    if (searchMatches.isNotEmpty()) {
                                        currentMatchIndex = if (currentMatchIndex > 0) currentMatchIndex - 1 else searchMatches.size - 1
                                    }
                                },
                                enabled = searchMatches.isNotEmpty(),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match")
                            }

                            IconButton(
                                onClick = {
                                    if (searchMatches.isNotEmpty()) {
                                        currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size
                                    }
                                },
                                enabled = searchMatches.isNotEmpty(),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match")
                            }
                        }

                        IconButton(
                            onClick = {
                                isSearching = false
                                searchQuery = ""
                                keyboardController?.hide()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                }
            }

            // Truncation / Large file warning banner
            if (isTruncated) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.doc_large_file_warning,
                                "${byteSize / (1024 * 1024)} MB",
                                50000
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Content Area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.doc_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    errorMessage != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.doc_failed_to_load, errorMessage ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    isMarkdownFile && currentMode == DocumentMode.VIEW -> {
                        // Markdown Reader Mode
                        val baseDir = (source as? DocumentSource.FileSource)?.file?.parentFile
                        val viewScrollState = rememberScrollState()
                        val activeMatchCharOffset = if (isSearching) searchMatches.getOrNull(currentMatchIndex) else null
                        LaunchedEffect(activeMatchCharOffset) {
                            if (activeMatchCharOffset != null && content.isNotEmpty() && viewScrollState.maxValue > 0) {
                                val fraction = (activeMatchCharOffset.toFloat() / content.length.toFloat()).coerceIn(0f, 1f)
                                val targetY = (fraction * viewScrollState.maxValue).toInt()
                                viewScrollState.animateScrollTo(targetY)
                            }
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            MarkdownDocument(
                                markdown = content,
                                baseDir = baseDir,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(viewScrollState)
                            )
                            VerticalFastScrollbar(
                                scrollState = viewScrollState,
                                modifier = Modifier.align(Alignment.CenterEnd)
                            )
                        }
                    }
                    else -> {
                        // Text Editor Mode
                        TextEditorContent(
                            content = content,
                            onContentChange = { if (!source.isReadOnly) content = it },
                            isReadOnly = source.isReadOnly || currentMode == DocumentMode.VIEW,
                            wordWrap = wordWrap,
                            showLineNumbers = showLineNumbers,
                            useMonospace = useMonospace,
                            searchQuery = if (isSearching) searchQuery else "",
                            searchMatches = if (isSearching) searchMatches else emptyList(),
                            currentMatchIndex = currentMatchIndex
                        )
                    }
                }
            }
        }
    }
}

/**
 * Interactive vertical scroll thumb shown on the right side of long documents.
 * Allows quick position awareness, tap-to-scroll, and vertical drag-to-scroll.
 */
@Composable
fun VerticalFastScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier
) {
    if (scrollState.maxValue <= 0) return

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    var trackHeightPx by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val isScrolling = scrollState.isScrollInProgress || isDragging
    val alpha by animateFloatAsState(
        targetValue = if (isScrolling) 0.85f else 0.35f,
        animationSpec = tween(durationMillis = 200),
        label = "scrollbarAlpha"
    )
    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 7.dp else 4.dp,
        animationSpec = tween(durationMillis = 150),
        label = "thumbWidth"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .onGloballyPositioned { coordinates ->
                trackHeightPx = coordinates.size.height.toFloat()
            }
            .pointerInput(scrollState, trackHeightPx) {
                detectTapGestures { offset ->
                    if (trackHeightPx > 0f && scrollState.maxValue > 0) {
                        val fraction = (offset.y / trackHeightPx).coerceIn(0f, 1f)
                        coroutineScope.launch {
                            scrollState.scrollTo((fraction * scrollState.maxValue).toInt())
                        }
                    }
                }
            }
            .pointerInput(scrollState, trackHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (trackHeightPx > 0f && scrollState.maxValue > 0) {
                            val minThumbHeightPx = with(density) { 36.dp.toPx() }
                            val totalContentHeight = scrollState.maxValue + trackHeightPx
                            val proportion = (trackHeightPx / totalContentHeight).coerceIn(0.05f, 1f)
                            val thumbHeightPx = (trackHeightPx * proportion).coerceIn(minThumbHeightPx, trackHeightPx)
                            val maxThumbOffset = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)

                            val scrollDelta = (dragAmount / maxThumbOffset) * scrollState.maxValue
                            coroutineScope.launch {
                                scrollState.scrollBy(scrollDelta)
                            }
                        }
                    }
                )
            }
    ) {
        if (trackHeightPx > 0f && scrollState.maxValue > 0) {
            val minThumbHeightPx = with(density) { 36.dp.toPx() }
            val totalContentHeight = scrollState.maxValue + trackHeightPx
            val proportion = (trackHeightPx / totalContentHeight).coerceIn(0.05f, 1f)
            val thumbHeightPx = (trackHeightPx * proportion).coerceIn(minThumbHeightPx, trackHeightPx)
            val maxThumbOffset = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)

            val currentOffsetPx = if (scrollState.maxValue > 0) {
                (scrollState.value.toFloat() / scrollState.maxValue) * maxThumbOffset
            } else 0f

            val thumbHeightDp = with(density) { thumbHeightPx.toDp() }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 3.dp)
                    .offset { IntOffset(0, currentOffsetPx.roundToInt()) }
                    .width(thumbWidth)
                    .height(thumbHeightDp)
                    .clip(CircleShape)
                    .background(
                        (if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            .copy(alpha = alpha)
                    )
            )
        }
    }
}

/**
 * VisualTransformation that highlights all search occurrences in the text,
 * with a distinct emphasis on the actively selected match index.
 */
class SearchHighlightTransformation(
    private val searchQuery: String,
    private val activeMatchCharOffset: Int?,
    private val highlightColor: Color,
    private val activeHighlightColor: Color,
    private val onHighlightTextColor: Color,
    private val onActiveHighlightTextColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (searchQuery.isBlank()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val rawText = text.text
        val queryLength = searchQuery.length
        val builder = AnnotatedString.Builder(text)
        var idx = 0
        while (idx < rawText.length) {
            val found = rawText.indexOf(searchQuery, idx, ignoreCase = true)
            if (found == -1) break
            val isActive = (activeMatchCharOffset != null && found == activeMatchCharOffset)
            val bg = if (isActive) activeHighlightColor else highlightColor
            val fg = if (isActive) onActiveHighlightTextColor else onHighlightTextColor

            builder.addStyle(
                SpanStyle(
                    background = bg,
                    color = fg,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                ),
                found,
                found + queryLength
            )
            idx = found + queryLength.coerceAtLeast(1)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextEditorContent(
    content: String,
    onContentChange: (String) -> Unit,
    isReadOnly: Boolean,
    wordWrap: Boolean,
    showLineNumbers: Boolean,
    useMonospace: Boolean,
    searchQuery: String = "",
    searchMatches: List<Int> = emptyList(),
    currentMatchIndex: Int = 0
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = content,
                selection = TextRange.Zero
            )
        )
    }

    // Keep textFieldValue in sync if external content changes
    LaunchedEffect(content) {
        if (textFieldValue.text != content) {
            textFieldValue = textFieldValue.copy(text = content)
        }
    }

    val lines = remember(textFieldValue.text) { textFieldValue.text.split('\n') }
    val lineCount = lines.size

    val textStyle = TextStyle(
        fontFamily = if (useMonospace) FontFamily.Monospace else FontFamily.Default,
        fontSize = 13.5.sp,
        lineHeight = 22.sp,
        color = MaterialTheme.colorScheme.onSurface
    )

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeightPx by remember { mutableStateOf(0) }

    val activeMatchCharOffset = remember(searchMatches, currentMatchIndex) {
        searchMatches.getOrNull(currentMatchIndex)
    }

    // Visual transformation for search highlighting
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
    val activeHighlightColor = MaterialTheme.colorScheme.primary
    val onHighlightTextColor = MaterialTheme.colorScheme.onTertiaryContainer
    val onActiveHighlightTextColor = MaterialTheme.colorScheme.onPrimary

    val visualTransformation = remember(
        searchQuery,
        activeMatchCharOffset,
        highlightColor,
        activeHighlightColor,
        onHighlightTextColor,
        onActiveHighlightTextColor
    ) {
        if (searchQuery.isNotBlank()) {
            SearchHighlightTransformation(
                searchQuery = searchQuery,
                activeMatchCharOffset = activeMatchCharOffset,
                highlightColor = highlightColor,
                activeHighlightColor = activeHighlightColor,
                onHighlightTextColor = onHighlightTextColor,
                onActiveHighlightTextColor = onActiveHighlightTextColor
            )
        } else {
            VisualTransformation.None
        }
    }

    // Auto-scroll to active search match
    LaunchedEffect(activeMatchCharOffset, textLayoutResult) {
        if (activeMatchCharOffset != null) {
            val layout = textLayoutResult
            val vHeight = if (verticalScrollState.viewportSize > 0) {
                verticalScrollState.viewportSize.toFloat()
            } else {
                viewportHeightPx.toFloat()
            }
            val paddingTopPx = with(density) { 12.dp.toPx() }

            if (layout != null && activeMatchCharOffset in 0..layout.layoutInput.text.length) {
                try {
                    val safeOffset = activeMatchCharOffset.coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
                    val box = layout.getBoundingBox(safeOffset)
                    val targetY = (box.top + paddingTopPx - vHeight / 3f)
                        .coerceIn(0f, verticalScrollState.maxValue.toFloat())
                    verticalScrollState.animateScrollTo(targetY.toInt())

                    if (!wordWrap) {
                        val targetX = (box.left - 40f).coerceIn(0f, horizontalScrollState.maxValue.toFloat())
                        horizontalScrollState.animateScrollTo(targetX.toInt())
                    }
                } catch (_: Exception) {}
            } else {
                val lineIndex = content.take(activeMatchCharOffset).count { it == '\n' }
                val lineHeightPx = with(density) { 22.sp.toPx() }
                val estimatedY = (lineIndex * lineHeightPx + paddingTopPx - vHeight / 3f)
                    .coerceIn(0f, verticalScrollState.maxValue.toFloat())
                verticalScrollState.animateScrollTo(estimatedY.toInt())
            }
        }
    }

    // BringIntoViewResponder: Intercepts and handles cursor/focus visibility requests.
    // By returning a rect at current scroll offset from calculateRectForParent, we completely
    // prevent Compose's verticalScroll from making unexpected whole-document jumps to line 0 on focus gain.
    val bringIntoViewResponder = remember(verticalScrollState) {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect {
                val currentY = verticalScrollState.value.toFloat()
                return Rect(0f, currentY, localRect.width, currentY + 1f)
            }

            override suspend fun bringChildIntoView(localRect: () -> Rect?) {
                val rect = localRect() ?: return
                val viewportHeight = if (verticalScrollState.viewportSize > 0) {
                    verticalScrollState.viewportSize.toFloat()
                } else {
                    viewportHeightPx.toFloat()
                }
                if (viewportHeight <= 0f) return

                // Suppress whole-component bring-into-view requests (triggered on FocusModifier gain)
                if (rect.height > viewportHeight * 0.5f) {
                    return
                }

                val paddingTopPx = with(density) { 12.dp.toPx() }

                // Suppress line-0 bring-into-view requests triggered on initial focus gain when scrolled down
                if (rect.top <= paddingTopPx && verticalScrollState.value > 40) {
                    return
                }

                val actualTop = rect.top + paddingTopPx
                val actualBottom = rect.bottom + paddingTopPx
                val currentScroll = verticalScrollState.value.toFloat()
                val marginPx = with(density) { 48.dp.toPx() }

                val isOffScreen = actualTop < currentScroll + marginPx || actualBottom > currentScroll + viewportHeight - marginPx
                if (isOffScreen) {
                    val targetScroll = (actualTop - viewportHeight / 3f).coerceIn(0f, verticalScrollState.maxValue.toFloat())
                    verticalScrollState.animateScrollTo(targetScroll.toInt())
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                viewportHeightPx = coordinates.size.height
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Line numbers column
            if (showLineNumbers) {
                Column(
                    modifier = Modifier
                        .verticalScroll(verticalScrollState)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    for (i in 1..lineCount) {
                        Text(
                            text = i.toString(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        )
                    }
                }
                // Thin gutter divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                )
            }

            // Editable text area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(verticalScrollState)
                    .then(
                        if (!wordWrap) Modifier.horizontalScroll(horizontalScrollState) else Modifier
                    )
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            val textChanged = newValue.text != textFieldValue.text
                            textFieldValue = newValue
                            if (textChanged) {
                                onContentChange(newValue.text)
                                val cursorOffset = newValue.selection.end.coerceIn(0, newValue.text.length)
                                val paddingTopPx = with(density) { 12.dp.toPx() }
                                val lineHeightPx = with(density) { 22.sp.toPx() }

                                val layout = textLayoutResult
                                val cursorRect = if (layout != null && layout.layoutInput.text.isNotEmpty()) {
                                    val safeOffset = cursorOffset.coerceIn(0, layout.layoutInput.text.length)
                                    try {
                                        layout.getCursorRect(safeOffset)
                                    } catch (e: Exception) {
                                        null
                                    }
                                } else null

                                val rawTop = cursorRect?.top ?: (newValue.text.take(cursorOffset).count { it == '\n' } * lineHeightPx)
                                val rawBottom = cursorRect?.bottom ?: (rawTop + lineHeightPx)
                                val cursorTop = rawTop + paddingTopPx
                                val cursorBottom = rawBottom + paddingTopPx

                                val vHeight = if (verticalScrollState.viewportSize > 0) {
                                    verticalScrollState.viewportSize.toFloat()
                                } else {
                                    viewportHeightPx.toFloat()
                                }

                                if (vHeight > 0f) {
                                    val currentScroll = verticalScrollState.value.toFloat()
                                    val marginPx = with(density) { 48.dp.toPx() }
                                    val isOffScreen = cursorTop < currentScroll + marginPx || cursorBottom > currentScroll + vHeight - marginPx
                                    if (isOffScreen) {
                                        val targetScroll = (cursorTop - vHeight / 3f).coerceIn(0f, verticalScrollState.maxValue.toFloat())
                                        if (scrollJob?.isActive != true) {
                                            scrollJob = scope.launch {
                                                verticalScrollState.animateScrollTo(targetScroll.toInt())
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        readOnly = isReadOnly,
                        textStyle = textStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = visualTransformation,
                        onTextLayout = { layoutResult ->
                            textLayoutResult = layoutResult
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .bringIntoViewResponder(bringIntoViewResponder)
                    )

                    // Blank space tap target below text to place cursor at the end
                    if (!isReadOnly) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    focusRequester.requestFocus()
                                    textFieldValue = textFieldValue.copy(
                                        selection = TextRange(textFieldValue.text.length)
                                    )
                                }
                        )
                    }
                }
            }
        }

        // Fast scrollbar on right side
        VerticalFastScrollbar(
            scrollState = verticalScrollState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
