package io.github.satyanvm.reader.sync

import io.github.satyanvm.reader.model.ReadingPosition
import io.github.satyanvm.reader.model.Stamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * REST implementation of [SyncApi]:
 *
 *   GET {base}/v1/reading-positions/{documentId}  -> 200 position | 404
 *   PUT {base}/v1/reading-positions/{documentId}  body: position -> 200 winning position
 *
 * and of [LibraryApi]:
 *
 *   GET {base}/v1/library               -> 200 { books }  (removed books included)
 *   PUT {base}/v1/library/{sha256}      { title, format, sizeBytes, pageCount } -> 200 book
 *   PUT {base}/v1/library/{sha256}/file body: the file -> 200 book
 *   GET {base}/v1/library/{sha256}/file -> 200 the file | 404
 */
class HttpSyncApi(
    private val baseUrl: String,
    private val token: () -> String?,
    private val timeoutMs: Int = 10_000,
    /** Books are large; give their transfers longer before calling the connection dead. */
    private val fileTimeoutMs: Int = 120_000,
) : SyncApi, LibraryApi {

    override suspend fun get(documentId: String): ReadingPosition? =
        request("GET", documentId, body = null)?.let(::fromJson)

    override suspend fun put(position: ReadingPosition): ReadingPosition =
        request("PUT", position.documentId, toJson(position).toString())
            ?.let(::fromJson)
            ?: throw SyncUnavailableException("empty PUT response")

    override suspend fun library(): List<RemoteDocument> = withContext(Dispatchers.IO) {
        val conn = open("GET", "/v1/library", timeoutMs)
        try {
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                200 -> libraryFromJson(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
                401 -> throw UnauthorizedException()
                else -> throw SyncUnavailableException("HTTP $code on GET library")
            }
        } catch (e: IOException) {
            throw SyncUnavailableException("network error on GET library", e)
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun add(documentId: String, title: String, format: String, sizeBytes: Long, pageCount: Int) =
        withContext(Dispatchers.IO) {
            val conn = open("PUT", bookPath(documentId), timeoutMs)
            try {
                val body = JSONObject()
                    .put("title", title.ifBlank { "Untitled" })
                    .put("format", format)
                    .put("sizeBytes", maxOf(1L, sizeBytes))
                    .put("pageCount", maxOf(1, pageCount))
                    .toString().toByteArray()
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body) }
                checkBookResponse(conn.responseCode, "adding $documentId")
            } catch (e: IOException) {
                throw SyncUnavailableException("network error adding $documentId", e)
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun upload(documentId: String, file: File) = withContext(Dispatchers.IO) {
        val conn = open("PUT", bookPath(documentId) + "/file", fileTimeoutMs)
        try {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(file.length())
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
            checkBookResponse(conn.responseCode, "uploading $documentId")
        } catch (e: IOException) {
            throw SyncUnavailableException("network error uploading $documentId", e)
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun download(documentId: String, dest: File) = withContext(Dispatchers.IO) {
        val conn = open("GET", bookPath(documentId) + "/file", fileTimeoutMs)
        try {
            when (val code = conn.responseCode) {
                200 -> conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        input.copyTo(out, 64 * 1024)
                        out.fd.sync()
                    }
                }
                401 -> throw UnauthorizedException()
                503 -> throw BookStorageUnavailableException()
                else -> throw SyncUnavailableException("HTTP $code downloading $documentId")
            }
        } catch (e: IOException) {
            throw SyncUnavailableException("network error downloading $documentId", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun bookPath(documentId: String) = "/v1/library/" + URLEncoder.encode(documentId, "UTF-8")

    private fun checkBookResponse(code: Int, what: String) {
        when (code) {
            200 -> Unit
            401 -> throw UnauthorizedException()
            503 -> throw BookStorageUnavailableException()
            // Too large, not the size or hash it claims, or not in the library: retrying won't help.
            400, 409, 411, 413, 422 -> throw BookRejectedException("HTTP $code $what")
            else -> throw SyncUnavailableException("HTTP $code $what")
        }
    }

    private fun open(method: String, path: String, timeout: Int): HttpURLConnection {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeout
        token()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        return conn
    }

    private suspend fun request(method: String, documentId: String, body: String?): JSONObject? =
        withContext(Dispatchers.IO) {
            val conn = open(method, "/v1/reading-positions/" + URLEncoder.encode(documentId, "UTF-8"), timeoutMs)
            try {
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                when (val code = conn.responseCode) {
                    200 -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    404 -> if (method == "GET") null else throw SyncUnavailableException("404 on $method")
                    401 -> throw UnauthorizedException()
                    else -> throw SyncUnavailableException("HTTP $code on $method")
                }
            } catch (e: IOException) {
                throw SyncUnavailableException("network error on $method", e)
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        fun libraryFromJson(o: JSONObject): List<RemoteDocument> {
            val books = o.getJSONArray("books")
            return List(books.length()) { i ->
                val d = books.getJSONObject(i)
                RemoteDocument(
                    documentId = d.getString("documentId"),
                    title = d.getString("title"),
                    format = d.getString("format"),
                    sizeBytes = d.getLong("sizeBytes"),
                    pageCount = d.getInt("pageCount"),
                    hasFile = d.optBoolean("hasFile", false),
                    addedAt = d.optLong("addedAt", 0L),
                    removedAt = if (d.isNull("removedAt")) null else d.getLong("removedAt"),
                )
            }
        }

        fun toJson(p: ReadingPosition) = JSONObject()
            .put("documentId", p.documentId)
            .put("pageIndex", p.pageIndex)
            .put("pageOffset", p.pageOffset.toDouble())
            .put("horizontalOffset", p.horizontalOffset.toDouble())
            .put("zoom", p.zoom.toDouble())
            .put("pageCount", p.pageCount)
            .put("hlc", p.stamp.hlc)
            .put("deviceId", p.stamp.deviceId)
            .put("schemaVersion", p.schemaVersion)

        fun fromJson(o: JSONObject) = ReadingPosition(
            documentId = o.getString("documentId"),
            pageIndex = o.getInt("pageIndex"),
            pageOffset = o.getDouble("pageOffset").toFloat(),
            horizontalOffset = o.optDouble("horizontalOffset", 0.0).toFloat(),
            zoom = o.optDouble("zoom", 1.0).toFloat(),
            pageCount = o.getInt("pageCount"),
            stamp = Stamp(o.getLong("hlc"), o.getString("deviceId")),
            schemaVersion = o.optInt("schemaVersion", ReadingPosition.SCHEMA_VERSION),
        )
    }
}
