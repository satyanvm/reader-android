package io.github.satyanvm.reader.sync

import io.github.satyanvm.reader.model.ReadingPosition
import io.github.satyanvm.reader.model.Stamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * REST implementation of [SyncApi]:
 *
 *   GET {base}/v1/reading-positions/{documentId}  -> 200 position | 404
 *   PUT {base}/v1/reading-positions/{documentId}  body: position -> 200 winning position
 */
class HttpSyncApi(
    private val baseUrl: String,
    private val token: () -> String?,
    private val timeoutMs: Int = 10_000,
) : SyncApi {

    override suspend fun get(documentId: String): ReadingPosition? =
        request("GET", documentId, body = null)?.let(::fromJson)

    override suspend fun put(position: ReadingPosition): ReadingPosition =
        request("PUT", position.documentId, toJson(position).toString())
            ?.let(::fromJson)
            ?: throw SyncUnavailableException("empty PUT response")

    private suspend fun request(method: String, documentId: String, body: String?): JSONObject? =
        withContext(Dispatchers.IO) {
            val url = URL(
                baseUrl.trimEnd('/') + "/v1/reading-positions/" +
                    URLEncoder.encode(documentId, "UTF-8")
            )
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Accept", "application/json")
                token()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                when (val code = conn.responseCode) {
                    200 -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    404 -> if (method == "GET") null else throw SyncUnavailableException("404 on $method")
                    else -> throw SyncUnavailableException("HTTP $code on $method")
                }
            } catch (e: IOException) {
                throw SyncUnavailableException("network error on $method", e)
            } finally {
                conn.disconnect()
            }
        }

    companion object {
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
