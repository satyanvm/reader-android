package io.github.satyanvm.reader.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.satyanvm.reader.data.db.LibraryRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    rows: List<LibraryRow>,
    importing: Boolean,
    onOpen: (String) -> Unit,
    onImport: (Uri) -> Unit,
    signedIn: Boolean,
    onAccount: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
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
            ExtendedFloatingActionButton(
                text = { Text(if (importing) "Opening…" else "Open book") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { if (!importing) picker.launch(arrayOf("application/pdf", "application/epub+zip", "application/octet-stream")) },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (!signedIn) SignInPrompt(onAccount)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Open a PDF or EPUB to start reading")
                }
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                if (!signedIn) item(key = "sign-in") { SignInPrompt(onAccount) }
                items(rows, key = { it.id }) { row ->
                    ListItem(
                        headlineContent = { Text(row.title, maxLines = 2) },
                        supportingContent = { Text(progressLabel(row)) },
                        modifier = Modifier.clickable { onOpen(row.id) }.padding(horizontal = 4.dp),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Shown while signed out, so syncing with the Mac is one tap away. */
@Composable
private fun SignInPrompt(onSignIn: () -> Unit) {
    ListItem(
        headlineContent = { Text("Sync your reading") },
        supportingContent = { Text("Sign in to pick up where you left off on your other devices.") },
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
