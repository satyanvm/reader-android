package io.github.satyanvm.reader.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.satyanvm.reader.data.db.LibraryRow
import io.github.satyanvm.reader.data.db.WishlistEntity

private enum class Shelf(val label: String) { LIBRARY("Library"), READING("Reading"), WISHLIST("Wishlist") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    rows: List<LibraryRow>,
    wishlist: List<WishlistEntity>,
    importing: Boolean,
    onOpen: (String) -> Unit,
    onImport: (Uri) -> Unit,
    onCurrentlyReading: (id: String, reading: Boolean) -> Unit,
    onWish: (title: String, author: String) -> Unit,
    onUnwish: (Long) -> Unit,
    signedIn: Boolean,
    onAccount: () -> Unit,
    onPricing: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    var shelf by rememberSaveable { mutableStateOf(Shelf.LIBRARY) }
    var wishing by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    TextButton(onClick = onPricing) { Text("Pricing") }
                    if (signedIn) {
                        IconButton(onClick = onAccount) {
                            Icon(Icons.Filled.AccountCircle, contentDescription = "Account")
                        }
                    } else {
                        TextButton(onClick = onAccount) { Text("Sign in") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (shelf == Shelf.WISHLIST) {
                ExtendedFloatingActionButton(
                    text = { Text("Add to wishlist") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { wishing = true },
                )
            } else {
                ExtendedFloatingActionButton(
                    text = { Text(if (importing) "Opening…" else "Open book") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { if (!importing) picker.launch(arrayOf("application/pdf", "application/epub+zip", "application/octet-stream")) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = shelf.ordinal) {
                for (s in Shelf.entries) {
                    Tab(selected = s == shelf, onClick = { shelf = s }, text = { Text(s.label) })
                }
            }
            when (shelf) {
                Shelf.LIBRARY -> Books(
                    rows = rows,
                    empty = "Open a PDF or EPUB to start reading",
                    header = { if (!signedIn) SignInPrompt(onAccount) },
                    onOpen = onOpen,
                    onCurrentlyReading = onCurrentlyReading,
                )
                Shelf.READING -> Books(
                    rows = rows.filter { it.currentlyReading },
                    empty = "Nothing here yet. In the library, tap ⋮ beside a book and choose \"Add to Currently reading\".",
                    onOpen = onOpen,
                    onCurrentlyReading = onCurrentlyReading,
                )
                Shelf.WISHLIST -> Wishlist(wishlist, onUnwish)
            }
        }
    }

    if (wishing) {
        WishDialog(
            onAdd = { title, author ->
                onWish(title, author)
                wishing = false
            },
            onDismiss = { wishing = false },
        )
    }
}

@Composable
private fun Books(
    rows: List<LibraryRow>,
    empty: String,
    onOpen: (String) -> Unit,
    onCurrentlyReading: (String, Boolean) -> Unit,
    header: @Composable () -> Unit = {},
) {
    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            header()
            EmptyShelf(empty, Modifier.weight(1f))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") { header() }
        items(rows, key = { it.id }) { row ->
            ListItem(
                leadingContent = { HardcoverIcon() },
                headlineContent = { Text(row.title, maxLines = 2) },
                supportingContent = { Text(progressLabel(row)) },
                trailingContent = { BookMenu(row.currentlyReading) { onCurrentlyReading(row.id, it) } },
                modifier = Modifier.clickable { onOpen(row.id) }.padding(horizontal = 4.dp),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun BookMenu(reading: Boolean, onCurrentlyReading: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Book options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (reading) "Remove from Currently reading" else "Add to Currently reading") },
                onClick = {
                    open = false
                    onCurrentlyReading(!reading)
                },
            )
        }
    }
}

@Composable
private fun Wishlist(items: List<WishlistEntity>, onRemove: (Long) -> Unit) {
    if (items.isEmpty()) {
        EmptyShelf("Keep track of books you want to read. Tap \"Add to wishlist\" to add one.", Modifier.fillMaxSize())
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            ListItem(
                leadingContent = { HardcoverIcon() },
                headlineContent = { Text(item.title, maxLines = 2) },
                supportingContent = item.author.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                trailingContent = {
                    IconButton(onClick = { onRemove(item.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove from wishlist")
                    }
                },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun WishDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to wishlist") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(title.trim(), author.trim()) }, enabled = title.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks what to call a PDF before it joins the library; starts with the file's own name. */
@Composable
fun NameBookDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this book") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Book name") },
                supportingText = { Text("Shown in your library and on your other devices") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyShelf(message: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, textAlign = TextAlign.Center)
    }
}

private val CoverCloth = Color(0xFF7A2E2E)
private val CoverSpine = Color(0xFF5A1F1F)
private val CoverFoil = Color(0xFFD9B26A)
private val PageEdges = Color(0xFFF3EBD8)

/** The same plain hardcover for every book: cloth boards, a darker spine, gilt title bands. */
@Composable
private fun HardcoverIcon() {
    Canvas(Modifier.size(width = 34.dp, height = 46.dp)) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(2.dp.toPx())
        // Page block peeking out at the fore-edge.
        drawRoundRect(PageEdges, topLeft = Offset(w * 0.12f, h * 0.04f), size = Size(w * 0.86f, h * 0.92f), cornerRadius = r)
        drawRoundRect(CoverCloth, size = Size(w * 0.92f, h), cornerRadius = r)
        drawRoundRect(CoverSpine, size = Size(w * 0.16f, h), cornerRadius = r)
        val bandX = w * 0.28f
        val bandW = w * 0.52f
        drawRect(CoverFoil, topLeft = Offset(bandX, h * 0.22f), size = Size(bandW, 1.5.dp.toPx()))
        drawRect(CoverFoil, topLeft = Offset(bandX, h * 0.30f), size = Size(bandW, 1.dp.toPx()))
        drawRect(CoverFoil, topLeft = Offset(bandX, h * 0.72f), size = Size(bandW, 1.dp.toPx()))
    }
}

/** Shown while signed out, so syncing with the Mac is one tap away. */
@Composable
private fun SignInPrompt(onSignIn: () -> Unit) {
    ListItem(
        headlineContent = { Text("Sync your reading") },
        supportingContent = { Text("Sign in to get your books and pick up where you left off on your other devices.") },
        trailingContent = { Button(onClick = onSignIn) { Text("Sign in") } },
        modifier = Modifier.clickable(onClick = onSignIn).padding(horizontal = 4.dp),
    )
    HorizontalDivider()
}

private fun progressLabel(row: LibraryRow): String {
    val unit = if (row.format == "EPUB") "Chapter" else "Page"
    val page = row.pageIndex
        ?: return "Not started · ${row.pageCount} ${if (row.format == "EPUB") "chapters" else "pages"}"
    val read = (page + (row.pageOffset ?: 0f)) / row.pageCount.coerceAtLeast(1)
    return "$unit ${page + 1} of ${row.pageCount} · ${(read * 100).toInt()}%"
}
