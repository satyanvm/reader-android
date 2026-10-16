package io.github.satyanvm.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.satyanvm.reader.account.AccountScreen
import io.github.satyanvm.reader.account.SyncAccount
import io.github.satyanvm.reader.data.UnsupportedFormatException
import io.github.satyanvm.reader.library.LibraryScreen
import io.github.satyanvm.reader.reader.ReaderScreen
import io.github.satyanvm.reader.reader.ReaderViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A PDF handed to us by another app (ACTION_VIEW), waiting to be imported and opened. */
    private val incoming = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        val graph = (application as ReaderApp).graph

        setContent {
            MaterialTheme {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                var importing by remember { mutableStateOf(false) }

                fun importAndOpen(uri: Uri) {
                    importing = true
                    scope.launch {
                        try {
                            val doc = graph.documents.import(uri)
                            // A new book goes up to the account in the background.
                            graph.syncScheduler.requestSync(delaySeconds = 0)
                            nav.navigate("reader/${doc.id}") { launchSingleTop = true }
                        } catch (e: UnsupportedFormatException) {
                            Toast.makeText(this@MainActivity, "Only PDF and EPUB books can be opened", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Couldn't open that book", Toast.LENGTH_LONG).show()
                        } finally {
                            importing = false
                        }
                    }
                }

                val pending by incoming.collectAsStateWithLifecycle()
                LaunchedEffect(pending) {
                    pending?.let {
                        incoming.value = null
                        importAndOpen(it)
                    }
                }

                NavHost(nav, startDestination = "library") {
                    composable("library") {
                        val library = remember { graph.documents.library() }
                        val rows by library.collectAsStateWithLifecycle(emptyList())
                        val stage by graph.account.stage.collectAsStateWithLifecycle()
                        LibraryScreen(
                            rows = rows,
                            importing = importing,
                            onOpen = { id -> nav.navigate("reader/$id") },
                            onImport = ::importAndOpen,
                            signedIn = stage is SyncAccount.Stage.SignedIn,
                            onAccount = { nav.navigate("account") { launchSingleTop = true } },
                        )
                    }
                    composable("account") {
                        AccountScreen(
                            account = graph.account,
                            google = graph.googleSignIn,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("reader/{id}") { entry ->
                        val id = entry.arguments?.getString("id") ?: return@composable
                        // Scoped to the back-stack entry: survives rotation, cleared on back.
                        val vm: ReaderViewModel = viewModel(
                            factory = viewModelFactory { initializer { ReaderViewModel(id, graph) } },
                        )
                        ReaderScreen(vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Each return to the app picks up books and reading from other devices.
        (application as ReaderApp).graph.syncScheduler.requestSync(delaySeconds = 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { incoming.value = it }
    }
}
