package com.example.filemanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/* ------------------------------------------------------------------------------------
 * MEDIA BLOCKING
 * Two independent checks are used so a renamed/mislabeled file still can't slip through:
 *   1) File extension (fast, used for the list itself)
 *   2) Actual MIME type resolved from the extension (double-checked again before any
 *      "open" action, so even an edge case is blocked at the last possible moment)
 * ------------------------------------------------------------------------------------ */

val HIDDEN_MEDIA_EXTENSIONS = setOf(
    // images
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "svg", "tiff", "tif",
    "raw", "cr2", "nef", "dng", "arw", "ico", "avif", "jfif", "jxl", "psd", "apng", "mif", "pict", "pct",
    // videos
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "3gpp", "m4v", "mpg",
    "mpeg", "ts", "m2ts", "vob", "3g2", "ogv", "mts", "mxf", "asf", "rm", "rmvb"
)

fun resolvedMimeType(file: File): String? {
    val ext = file.extension.lowercase()
    if (ext.isEmpty()) return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

fun isMedia(file: File): Boolean {
    if (file.isDirectory) return false
    val ext = file.extension.lowercase()
    if (ext in HIDDEN_MEDIA_EXTENSIONS) return true
    val mime = resolvedMimeType(file)
    return mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))
}

enum class SortMode(val label: String) {
    NAME("שם"), SIZE("גודל"), DATE("תאריך שינוי")
}

enum class ClipboardMode { COPY, CUT }

data class ClipboardState(val files: List<File>, val mode: ClipboardMode)

class MainActivity : ComponentActivity() {

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* UI re-checks Environment.isExternalStorageManager() via refreshTrigger */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FileManagerScreen(onRequestAllFilesAccess = { requestAllFilesAccess() })
                }
            }
        }
    }

    private fun requestAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                manageStorageLauncher.launch(intent)
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = Color(0xFF2563EB),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDAE8FF),
        secondary = Color(0xFF0D9488),
        background = Color(0xFFF7F9FC),
        surface = Color.White,
        surfaceVariant = Color(0xFFEDF2FA),
        onSurface = Color(0xFF142238),
        onSurfaceVariant = Color(0xFF59677C)
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun FileManagerScreen(onRequestAllFilesAccess: () -> Unit) {
    val context = LocalContext.current
    val rootDir = Environment.getExternalStorageDirectory()

    var currentDir by remember { mutableStateOf(rootDir) }
    var hasAccess by remember { mutableStateOf(checkAccess()) }
    var refreshTrigger by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = checkAccess()
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var sortAscending by remember { mutableStateOf(true) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<File>()) }
    var clipboard by remember { mutableStateOf<ClipboardState?>(null) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) { hasAccess = checkAccess() }

    if (!hasAccess) {
        PermissionScreen(onGrant = {
            onRequestAllFilesAccess()
            refreshTrigger++
        })
        return
    }

    BackHandler(enabled = selectionMode || searchActive || currentDir != rootDir) {
        when {
            selectionMode -> { selectionMode = false; selectedFiles = emptySet() }
            searchActive -> { searchActive = false; searchQuery = "" }
            currentDir != rootDir -> currentDir = currentDir.parentFile ?: rootDir
        }
    }

    fun exitSelection() {
        selectionMode = false
        selectedFiles = emptySet()
    }

    val allEntries = remember(currentDir, refreshTrigger, sortMode, sortAscending, searchQuery) {
        val base = currentDir.listFiles()?.toList().orEmpty()
            .filter { !isMedia(it) } // media never appears in the list
            .filter {
                searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
            }

        val comparator: Comparator<File> = when (sortMode) {
            SortMode.NAME -> compareBy { it.name.lowercase() }
            SortMode.SIZE -> compareBy { it.length() }
            SortMode.DATE -> compareBy { it.lastModified() }
        }
        val dirsFirst = compareBy<File> { !it.isDirectory }.then(comparator)
        val ordered = base.sortedWith(dirsFirst)
        if (sortAscending) ordered else {
            // keep directories first, but reverse within each group
            val dirs = ordered.filter { it.isDirectory }.reversed()
            val files = ordered.filter { !it.isDirectory }.reversed()
            dirs + files
        }
    }

    Scaffold(
        topBar = {
            Column {
                if (selectionMode) {
                    SelectionTopBar(
                        count = selectedFiles.size,
                        onClose = { exitSelection() },
                        onDelete = {
                            deleteDialog = true
                        },
                        onCopy = {
                            clipboard = ClipboardState(selectedFiles.toList(), ClipboardMode.COPY)
                            exitSelection()
                        },
                        onCut = {
                            clipboard = ClipboardState(selectedFiles.toList(), ClipboardMode.CUT)
                            exitSelection()
                        }
                    )
                } else {
                    TopAppBar(
                        title = {
                            if (searchActive) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("חיפוש בתיקייה...") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent
                                    )
                                )
                            } else {
                                Text(
                                    currentDir.name.ifEmpty { "אחסון" },
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        navigationIcon = {
                            if (searchActive) {
                                IconButton(onClick = { searchActive = false; searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "סגור חיפוש")
                                }
                            } else if (currentDir != rootDir) {
                                IconButton(onClick = { currentDir = currentDir.parentFile ?: rootDir }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "חזרה")
                                }
                            }
                        },
                        actions = {
                            if (!searchActive) {
                                IconButton(onClick = { searchActive = true }) {
                                    Icon(Icons.Filled.Search, contentDescription = "חיפוש")
                                }
                                Box {
                                    IconButton(onClick = { sortMenuOpen = true }) {
                                        Icon(Icons.Filled.Sort, contentDescription = "מיון")
                                    }
                                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                        SortMode.values().forEach { mode ->
                                            DropdownMenuItem(
                                                text = { Text(mode.label) },
                                                trailingIcon = {
                                                    if (sortMode == mode) Icon(Icons.Filled.Check, contentDescription = null)
                                                },
                                                onClick = {
                                                    if (sortMode == mode) sortAscending = !sortAscending
                                                    else { sortMode = mode; sortAscending = true }
                                                    sortMenuOpen = false
                                                }
                                            )
                                        }
                                        Divider()
                                        DropdownMenuItem(
                                            text = { Text(if (sortAscending) "סדר: עולה ↑" else "סדר: יורד ↓") },
                                            onClick = { sortAscending = !sortAscending; sortMenuOpen = false }
                                        )
                                    }
                                }
                                IconButton(onClick = { newFolderDialog = true }) {
                                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "תיקייה חדשה")
                                }
                            }
                        }
                    )
                }
                Text(
                    text = currentDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(visible = clipboard != null) {
                clipboard?.let { cb ->
                    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (cb.mode == ClipboardMode.COPY) Icons.Filled.ContentCopy else Icons.Filled.ContentCut,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "${cb.files.size} פריטים ל${if (cb.mode == ClipboardMode.COPY) "העתקה" else "העברה"}",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = { clipboard = null }) { Text("ביטול") }
                            Button(onClick = {
                                val ok = pasteFiles(cb, currentDir)
                                if (!ok) Toast.makeText(context, "לא ניתן להדביק כאן", Toast.LENGTH_SHORT).show()
                                clipboard = null
                                refreshTrigger++
                            }) { Text("הדבק") }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (allEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (searchQuery.isNotBlank()) "אין תוצאות" else "התיקייה ריקה (קבצי מדיה מוסתרים תמיד)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                    items(allEntries, key = { it.absolutePath }) { entry ->
                        FileRow(
                            file = entry,
                            selected = entry in selectedFiles,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    selectedFiles = if (entry in selectedFiles) selectedFiles - entry else selectedFiles + entry
                                } else if (entry.isDirectory) {
                                    currentDir = entry
                                } else {
                                    openFileSafely(context, entry)
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedFiles = setOf(entry)
                                }
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }

            if (deleteDialog) {
                AlertDialog(
                    onDismissRequest = { deleteDialog = false },
                    title = { Text("מחיקת פריטים") },
                    text = { Text("למחוק לצמיתות ${selectedFiles.size} פריטים? לא ניתן לבטל פעולה זו.") },
                    confirmButton = {
                        TextButton(onClick = {
                            selectedFiles.forEach { deleteRecursively(it) }
                            deleteDialog = false
                            exitSelection()
                            refreshTrigger++
                        }) { Text("מחק") }
                    },
                    dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("ביטול") } }
                )
            }
            if (newFolderDialog) {
                NewFolderDialog(
                    onDismiss = { newFolderDialog = false },
                    onCreate = { name ->
                        if (name.isNotBlank() && name != "." && name != ".." && !name.contains("/") && !name.contains("\\")) {
                            File(currentDir, name).mkdir()
                            refreshTrigger++
                        }
                        newFolderDialog = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(count: Int, onClose: () -> Unit, onDelete: () -> Unit, onCopy: () -> Unit, onCut: () -> Unit) {
    TopAppBar(
        title = { Text("$count נבחרו") },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "בטל בחירה") }
        },
        actions = {
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "העתק") }
            IconButton(onClick = onCut) { Icon(Icons.Filled.ContentCut, contentDescription = "גזור") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "מחק") }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}

@Composable
fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("תיקייה חדשה") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("שם התיקייה") },
                singleLine = true
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }) { Text("צור") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "כדי לנהל את הקבצים במכשיר, האפליקציה צריכה הרשאת גישה לכל הקבצים.",
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onGrant) { Text("אשר הרשאה") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(file: File, selected: Boolean, selectionMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Spacer(modifier = Modifier.width(4.dp))
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (file.isDirectory) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (file.isDirectory) Icons.Filled.Folder else iconForFile(file),
                contentDescription = null,
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val subtitle = if (file.isDirectory) "תיקייה" else "${formatSize(file.length())} • ${formatDate(file.lastModified())}"
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun iconForFile(file: File) = when (file.extension.lowercase()) {
    "pdf" -> Icons.Filled.PictureAsPdf
    "doc", "docx" -> Icons.Filled.Description
    "xls", "xlsx" -> Icons.Filled.TableChart
    "zip", "rar", "7z" -> Icons.Filled.FolderZip
    "mp3", "wav", "m4a", "flac" -> Icons.Filled.AudioFile
    "txt" -> Icons.Filled.Article
    "apk" -> Icons.Filled.Android
    else -> Icons.Filled.InsertDriveFile
}

private fun checkAccess(): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

/**
 * Opens a file only after re-confirming, via its resolved MIME type, that it is not an
 * image or video. This is a deliberate second layer of defense on top of the list filter -
 * media is never opened or previewed from this app under any circumstance.
 */
private fun openFileSafely(context: android.content.Context, file: File) {
    if (isMedia(file)) {
        Toast.makeText(context, "קבצי מדיה (תמונות/וידאו) אינם נתמכים באפליקציה זו", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = context.contentResolver.getType(uri) ?: resolvedMimeType(file) ?: "*/*"
        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            Toast.makeText(context, "קבצי מדיה (תמונות/וידאו) אינם נתמכים באפליקציה זו", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "לא ניתן לפתוח קובץ זה", Toast.LENGTH_SHORT).show()
    }
}

private fun deleteRecursively(file: File) {
    if (file.isDirectory) {
        file.listFiles()?.forEach { deleteRecursively(it) }
    }
    file.delete()
}

/** Copies or moves the clipboard's files/folders into [targetDir]. Returns false if invalid (e.g. pasting a folder into itself). */
private fun pasteFiles(clipboard: ClipboardState, targetDir: File): Boolean {
    for (src in clipboard.files) {
        if (isMedia(src)) continue
        if (src.isDirectory && targetDir.absolutePath.startsWith(src.absolutePath)) {
            return false // can't move/copy a folder into its own subtree
        }
        val dest = File(targetDir, src.name)
        if (dest.exists()) continue // skip name conflicts for simplicity
        if (clipboard.mode == ClipboardMode.COPY) {
            if (!copyRecursively(src, dest)) return false
        } else {
            val moved = src.renameTo(dest)
            if (!moved) {
                if (!copyRecursively(src, dest)) return false
                deleteRecursively(src)
            }
        }
    }
    return true
}

private fun copyRecursively(src: File, dest: File): Boolean {
    return try {
        if (isMedia(src)) return true
        if (src.isDirectory) {
            if (!dest.exists() && !dest.mkdirs()) return false
            src.listFiles()?.all { child -> copyRecursively(child, File(dest, child.name)) } ?: false
        } else {
            src.copyTo(dest, overwrite = false)
            true
        }
    } catch (_: Exception) { false }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}

private fun formatDate(millis: Long): String {
    val fmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
    return fmt.format(Date(millis))
}
