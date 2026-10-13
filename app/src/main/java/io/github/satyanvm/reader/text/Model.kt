package io.github.satyanvm.reader.text

import org.json.JSONArray
import org.json.JSONObject

/** A run of text in one style. */
data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false)

/**
 * One visual line from a page, from PDF text or OCR. Coordinates are in any consistent unit
 * (PDF points or image pixels); only their ratios to [fontSize] matter. [y] grows downward.
 */
data class Line(
    val spans: List<Span>,
    val x: Float,
    val endX: Float,
    val y: Float,
    val fontSize: Float,
) {
    val text: String get() = spans.joinToString("") { it.text }
}

/** A unit of flowing text. */
data class Block(
    val kind: Kind,
    val spans: List<Span> = emptyList(),
    /** First line was indented in the source (only meaningful for paragraphs). */
    val indented: Boolean = false,
    /** Continues a paragraph from the previous page: render without a first-line indent. */
    val continuation: Boolean = false,
    /** For [Kind.IMAGE]: a path inside the book. */
    val image: String? = null,
) {
    enum class Kind { PARAGRAPH, HEADING, IMAGE }

    val text: String get() = spans.joinToString("") { it.text }
}

/** One source page (PDF) as flowing text. Empty means no text was found (e.g. a figure). */
data class PageText(val blocks: List<Block>, val recognized: Boolean = false) {
    val isEmpty: Boolean get() = blocks.isEmpty()

    fun toJson(): String = JSONObject()
        .put("recognized", recognized)
        .put("blocks", JSONArray(blocks.map { it.toJson() }))
        .toString()

    companion object {
        fun fromJson(s: String): PageText {
            val o = JSONObject(s)
            val arr = o.getJSONArray("blocks")
            return PageText(
                blocks = List(arr.length()) { blockFromJson(arr.getJSONObject(it)) },
                recognized = o.optBoolean("recognized"),
            )
        }
    }
}

private fun Block.toJson(): JSONObject = JSONObject()
    .put("k", kind.name)
    .put("i", indented)
    .put("c", continuation)
    .put("img", image ?: JSONObject.NULL)
    .put("s", JSONArray(spans.map { JSONObject().put("t", it.text).put("b", it.bold).put("it", it.italic) }))

private fun blockFromJson(o: JSONObject): Block {
    val s = o.getJSONArray("s")
    return Block(
        kind = Block.Kind.valueOf(o.getString("k")),
        spans = List(s.length()) {
            val span = s.getJSONObject(it)
            Span(span.getString("t"), span.optBoolean("b"), span.optBoolean("it"))
        },
        indented = o.optBoolean("i"),
        continuation = o.optBoolean("c"),
        image = if (o.isNull("img")) null else o.getString("img"),
    )
}

/** Merges adjacent spans with the same style. */
fun List<Span>.compact(): List<Span> {
    val out = ArrayList<Span>(size)
    for (s in this) {
        if (s.text.isEmpty()) continue
        val last = out.lastOrNull()
        if (last != null && last.bold == s.bold && last.italic == s.italic) {
            out[out.size - 1] = last.copy(text = last.text + s.text)
        } else {
            out += s
        }
    }
    return out
}
