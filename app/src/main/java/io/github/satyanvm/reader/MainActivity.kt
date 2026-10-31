package io.github.satyanvm.reader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import io.github.satyanvm.reader.account.PricingScreen
import io.github.satyanvm.reader.account.SyncAccount
import io.github.satyanvm.reader.data.UnsupportedFormatException
import io.github.satyanvm.reader.data.db.WishlistEntity
import io.github.satyanvm.reader.library.LibraryScreen
import io.github.satyanvm.reader.library.NameBookDialog
import io.github.satyanvm.reader.reader.ReaderScreen
import io.github.satyanvm.reader.reader.ReaderViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A PDF handed to us by another app (ACTION_VIEW), waiting to be imported and opened. */
    private val incoming = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always light, so the clock and status icons are always dark: following the
        // phone's dark mode would draw white icons on the white library.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        val graph = (application as ReaderApp).graph

        setContent {
            MaterialTheme {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                var importing by remember { mutableStateOf(false) }
                /** A PDF waiting for the reader to name it, with the name it starts with. */
                var naming by remember { mutableStateOf<Pair<Uri, String>?>(null) }

                fun importAndOpen(uri: Uri, title: String? = null) {
                    importing = true
                    scope.launch {
                        try {
                            val doc = graph.documents.import(uri, title)
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

                /** PDFs get a name from the reader first (the file's name to start); EPUBs name themselves. */
                fun chooseAndOpen(uri: Uri) {
                    if (!graph.documents.looksLikePdf(uri)) return importAndOpen(uri)
                    scope.launch {
                        naming = uri to (graph.documents.suggestedTitle(uri) ?: "")
                    }
                }

                val pending by incoming.collectAsStateWithLifecycle()
                LaunchedEffect(pending) {
                    pending?.let {
                        incoming.value = null
                        chooseAndOpen(it)
                    }
                }

                naming?.let { (uri, initial) ->
                    NameBookDialog(
                        initial = initial,
                        onConfirm = { title ->
                            naming = null
                            importAndOpen(uri, title)
                        },
                        onDismiss = { naming = null },
                    )
                }

                NavHost(nav, startDestination = "library") {
                    composable("library") {
                        val library = remember { graph.documents.library() }
                        val rows by library.collectAsStateWithLifecycle(emptyList())
                        val wishes = remember { graph.wishlist.all() }
                        val wishlist by wishes.collectAsStateWithLifecycle(emptyList())
                        val stage by graph.account.stage.collectAsStateWithLifecycle()
                        LibraryScreen(
                            rows = rows,
                            wishlist = wishlist,
                            importing = importing,
                            onOpen = { id -> nav.navigate("reader/$id") },
                            onImport = ::chooseAndOpen,
                            onCurrentlyReading = { id, reading ->
                                scope.launch { graph.documents.setCurrentlyReading(id, reading) }
                            },
                            onWish = { title, author ->
                                scope.launch {
                                    graph.wishlist.add(WishlistEntity(title = title, author = author, addedAt = System.currentTimeMillis()))
                                }
                            },
                            onUnwish = { id -> scope.launch { graph.wishlist.remove(id) } },
                            signedIn = stage is SyncAccount.Stage.SignedIn,
                            onAccount = { nav.navigate("account") { launchSingleTop = true } },
                            onPricing = { nav.navigate("pricing") { launchSingleTop = true } },
                        )
                    }
                    composable("pricing") {
                        PricingScreen(
                            account = graph.account,
                            onSignIn = { nav.navigate("account") { launchSingleTop = true } },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("account") {
                        AccountScreen(
                            account = graph.account,
                            google = graph.googleSignIn,
                            onBack = { nav.popBackStack() },
                            // Back to the library, even when sign-in started from Pricing.
                            onSignedIn = { nav.popBackStack("library", inclusive = false) },
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
