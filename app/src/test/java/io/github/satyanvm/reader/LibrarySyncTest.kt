package io.github.satyanvm.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.satyanvm.reader.data.DocumentRepository
import io.github.satyanvm.reader.data.ReadingPositionRepository
import io.github.satyanvm.reader.data.RoomPositionStore
import io.github.satyanvm.reader.data.db.AppDatabase
import io.github.satyanvm.reader.model.HybridClock
import io.github.satyanvm.reader.model.ReadingPosition
import io.github.satyanvm.reader.model.Stamp
import io.github.satyanvm.reader.sync.HttpSyncApi
import io.github.satyanvm.reader.sync.LibraryApi
import io.github.satyanvm.reader.sync.LibrarySync
import io.github.satyanvm.reader.sync.RemoteDocument
import io.github.satyanvm.reader.sync.SyncApi
import io.github.satyanvm.reader.sync.SyncUnavailableException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibrarySyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = AppDatabase.open(context, name = null)
    private val documents = DocumentRepository(context, db.documents())
    private val positions = ReadingPositionRepository(RoomPositionStore(db), HybridClock(), "phone")
    private val server = FakeLibrary()
    private val sync = LibrarySync(server, server, SignedIn, documents, positions)

    @After
    fun close() {
        db.close()
        File(context.filesDir, "documents").deleteRecursively()
    }

    @Test
    fun `a book only on the server is downloaded, with its position`() = runBlocking {
        val bytes = epub("Meditations")
        val id = sha256(bytes)
        server.books[id] = RemoteDocument(id, "Meditations (Hays)", "EPUB", bytes.size.toLong(), 2, hasFile = true, addedAt = 1234L) to bytes
        server.positions[id] = ReadingPosition(id, 1, 0.5f, 0f, 1f, 2, Stamp(5_000, "mac"))

        assertTrue(sync.sync())

        val doc = documents.get(id)!!
        assertEquals("Meditations (Hays)", doc.title)
        assertEquals("EPUB", doc.format)
        assertEquals(2, doc.pageCount)
        assertArrayEquals(bytes, File(doc.localPath).readBytes())
        assertEquals(1, positions.load(id)!!.pageIndex)
        assertTrue("nothing to upload back", server.uploads.isEmpty())
    }

    @Test
    fun `a book only on this device is uploaded once`() = runBlocking {
        val bytes = epub("Letters")
        val id = sha256(bytes)
        val tmp = documents.tempFile().apply { writeBytes(bytes) }
        documents.adoptDownload(id, "Letters", 1L, tmp)!!

        assertTrue(sync.sync())
        assertEquals(listOf(id), server.uploads)
        assertArrayEquals(bytes, server.books[id]!!.second)

        assertTrue(sync.sync())
        assertEquals("already on the server", 1, server.uploads.size)
    }

    @Test
    fun `a download that doesn't match its hash is thrown away`() = runBlocking {
        val bytes = epub("Real")
        val id = sha256(bytes)
        server.books[id] = RemoteDocument(id, "Real", "EPUB", bytes.size.toLong(), 2, hasFile = true, addedAt = 1L) to epub("Tampered")

        assertTrue(sync.sync())
        assertNull(documents.get(id))
        assertEquals("no stray files", emptyList<String>(), File(context.filesDir, "documents").list()!!.toList())
    }

    @Test
    fun `books removed from the account or without a file yet are not downloaded`() = runBlocking {
        val removed = epub("Removed")
        val pending = epub("Pending")
        server.books[sha256(removed)] =
            RemoteDocument(sha256(removed), "Removed", "EPUB", removed.size.toLong(), 2, hasFile = false, addedAt = 1L, removedAt = 2L) to removed
        server.books[sha256(pending)] =
            RemoteDocument(sha256(pending), "Pending", "EPUB", pending.size.toLong(), 2, hasFile = false, addedAt = 1L) to pending

        assertTrue(sync.sync())
        assertTrue(documents.all().isEmpty())
    }

    @Test
    fun `an unreachable server is retried later`() = runBlocking {
        server.offline = true
        assertFalse(sync.sync())
    }

    @Test
    fun `the HTTP client speaks the server's library routes`() = runBlocking<Unit> {
        val web = MockWebServer()
        val bytes = epub("Wire")
        web.enqueue(
            MockResponse().setBody(
                """{"books":[{"documentId":"abc","title":"Wire","format":"EPUB","sizeBytes":3,"pageCount":2,
                   "hasFile":true,"addedAt":7,"removedAt":null},
                   {"documentId":"def","title":"Gone","format":"PDF","sizeBytes":5,"pageCount":1,
                   "hasFile":false,"addedAt":8,"removedAt":9}]}"""
            )
        )
        web.enqueue(MockResponse().setBody("{}"))
        web.enqueue(MockResponse().setBody("{}"))
        web.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        web.start()
        try {
            val api = HttpSyncApi(web.url("/").toString(), { "tok" })
            assertEquals(
                listOf(
                    RemoteDocument("abc", "Wire", "EPUB", 3, 2, hasFile = true, addedAt = 7),
                    RemoteDocument("def", "Gone", "PDF", 5, 1, hasFile = false, addedAt = 8, removedAt = 9),
                ),
                api.library(),
            )
            assertEquals("/v1/library", web.takeRequest().path)

            val file = File.createTempFile("upload", ".epub").apply { writeBytes(bytes) }
            api.add("abc", "Für Elise", "EPUB", file.length(), 2)
            val add = web.takeRequest()
            assertEquals("PUT", add.method)
            assertEquals("/v1/library/abc", add.path)
            assertEquals("Bearer tok", add.getHeader("Authorization"))
            val body = org.json.JSONObject(add.body.readUtf8())
            assertEquals("Für Elise", body.getString("title"))
            assertEquals(file.length(), body.getLong("sizeBytes"))
            assertEquals(2, body.getInt("pageCount"))

            api.upload("abc", file)
            val put = web.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("/v1/library/abc/file", put.path)
            assertArrayEquals(bytes, put.body.readByteArray())

            val dest = File.createTempFile("download", ".epub")
            api.download("abc", dest)
            assertEquals("/v1/library/abc/file", web.takeRequest().path)
            assertArrayEquals(bytes, dest.readBytes())
        } finally {
            web.shutdown()
        }
    }

    private class FakeLibrary : LibraryApi, SyncApi {
        val books = linkedMapOf<String, Pair<RemoteDocument, ByteArray>>()
        val positions = mutableMapOf<String, ReadingPosition>()
        val uploads = mutableListOf<String>()
        var offline = false

        override suspend fun library(): List<RemoteDocument> {
            if (offline) throw SyncUnavailableException("offline")
            return books.values.map { it.first }
        }

        override suspend fun add(documentId: String, title: String, format: String, sizeBytes: Long, pageCount: Int) {
            books[documentId] = RemoteDocument(documentId, title, format, sizeBytes, pageCount, hasFile = false, addedAt = 0) to ByteArray(0)
        }

        override suspend fun upload(documentId: String, file: File) {
            uploads += documentId
            val (book, _) = books.getValue(documentId)
            books[documentId] = book.copy(hasFile = true) to file.readBytes()
        }

        override suspend fun download(documentId: String, dest: File) {
            dest.writeBytes(books.getValue(documentId).second)
        }

        override suspend fun get(documentId: String): ReadingPosition? = positions[documentId]

        override suspend fun put(position: ReadingPosition): ReadingPosition = position.also { positions[it.documentId] = it }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A two-chapter EPUB called [title]. */
    private fun epub(title: String): ByteArray {
        val files = mapOf(
            "META-INF/container.xml" to """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "content.opf" to """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title></metadata>
                <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "a.xhtml" to "<html><body><p>One</p></body></html>",
            "b.xhtml" to "<html><body><p>Two</p></body></html>",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            for ((path, body) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
            }
        }
        return out.toByteArray()
    }
}
