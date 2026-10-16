package io.github.satyanvm.reader

import android.app.Application
import android.content.Context
import io.github.satyanvm.reader.account.AccountApi
import io.github.satyanvm.reader.account.GoogleSignIn
import io.github.satyanvm.reader.account.SyncAccount
import io.github.satyanvm.reader.data.DocumentRepository
import io.github.satyanvm.reader.data.ReadingPositionRepository
import io.github.satyanvm.reader.data.RoomPositionStore
import io.github.satyanvm.reader.data.db.AppDatabase
import io.github.satyanvm.reader.model.HybridClock
import io.github.satyanvm.reader.sync.HttpSyncApi
import io.github.satyanvm.reader.sync.LibrarySync
import io.github.satyanvm.reader.sync.SyncEngine
import io.github.satyanvm.reader.sync.SyncScheduler
import io.github.satyanvm.reader.telemetry.LocalRestoreTelemetry
import io.github.satyanvm.reader.text.ReadingPrefs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

class ReaderApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        graph = AppGraph(this)
        graph.syncScheduler.schedulePeriodic()
        // Anything saved offline in an earlier run goes out as soon as there's a network.
        graph.syncScheduler.requestSync(delaySeconds = 0)
    }
}

/** Hand-wired dependencies; small enough not to need a DI framework. */
class AppGraph(context: Context) {
    /** Outlives every screen, so in-flight saves finish after the reader closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database = AppDatabase.open(context)
    val deviceId: String = deviceId(context)
    val telemetry = LocalRestoreTelemetry(context)
    val readingPrefs = ReadingPrefs(context)
    val filesDir: java.io.File = context.filesDir
    val syncScheduler = SyncScheduler(context)

    /** The sync server (the Cloudflare Worker), unless local.properties points elsewhere. */
    val syncBaseUrl: String = BuildConfig.SYNC_BASE_URL
    val account = SyncAccount(
        api = AccountApi(syncBaseUrl),
        prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE),
        fallbackToken = BuildConfig.SYNC_TOKEN,
        // Reading done on this device before signing in goes up now.
        onSignedIn = { syncScheduler.requestSync(delaySeconds = 0) },
    )
    val googleSignIn = GoogleSignIn(BuildConfig.GOOGLE_WEB_CLIENT_ID)

    val positions = ReadingPositionRepository(
        store = RoomPositionStore(database),
        clock = HybridClock(),
        deviceId = deviceId,
        onPendingChange = { syncScheduler.requestSync() },
    )
    val documents = DocumentRepository(context, database.documents())

    private val syncApi = syncBaseUrl.takeIf { it.isNotBlank() }?.let { HttpSyncApi(it, account::token) }

    val syncEngine = SyncEngine(
        repository = positions,
        api = syncApi,
        session = account,
        onUnauthorized = account::onUnauthorized,
    )

    /** Books and every position, both ways; runs with each background sync. */
    val librarySync = LibrarySync(
        api = syncApi,
        positionsApi = syncApi,
        session = account,
        documents = documents,
        positions = positions,
        onUnauthorized = account::onUnauthorized,
    )

    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        prefs.getString("id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("id", id).commit()
        return id
    }
}
