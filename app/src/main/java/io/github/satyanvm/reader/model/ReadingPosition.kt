package io.github.satyanvm.reader.model

/**
 * Where a reader is in a document, in device-independent terms.
 *
 * Offsets are fractions of the page, not pixels, so the same position restores correctly on a
 * different screen size, orientation or zoom level.
 */
data class ReadingPosition(
    /** SHA-256 of the PDF bytes, so the same file has the same id on every device. */
    val documentId: String,
    /** 0-based page whose area is at the top edge of the viewport. */
    val pageIndex: Int,
    /** How far down [pageIndex] the viewport's top edge is: 0 = top of page, 1 = bottom. */
    val pageOffset: Float,
    /** Horizontal scroll as a fraction of the scrollable width (only meaningful when zoom > 1). */
    val horizontalOffset: Float = 0f,
    /** Zoom relative to fit-width. */
    val zoom: Float = 1f,
    /** Page count at save time; used to reject positions that don't fit the document. */
    val pageCount: Int,
    val stamp: Stamp,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    /** True if this position should win over [other] under the last-writer-wins policy. */
    fun isNewerThan(other: ReadingPosition?): Boolean = other == null || stamp > other.stamp

    /** Same place in the document, ignoring when/where it was recorded. */
    fun samePlaceAs(other: ReadingPosition?, tolerance: Float = 0.002f): Boolean =
        other != null &&
            pageIndex == other.pageIndex &&
            kotlin.math.abs(pageOffset - other.pageOffset) <= tolerance &&
            kotlin.math.abs(horizontalOffset - other.horizontalOffset) <= tolerance &&
            zoom == other.zoom

    /** Clamped to a document with [actualPageCount] pages, or null if it can't apply at all. */
    fun validFor(actualPageCount: Int): ReadingPosition? {
        if (actualPageCount <= 0) return null
        if (pageCount != actualPageCount) return null
        return copy(
            pageIndex = pageIndex.coerceIn(0, actualPageCount - 1),
            pageOffset = pageOffset.coerceIn(0f, 1f),
            horizontalOffset = horizontalOffset.coerceIn(0f, 1f),
            zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
        )
    }

    val isAtStart: Boolean get() = pageIndex == 0 && pageOffset < 0.001f

    companion object {
        const val SCHEMA_VERSION = 1
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
    }
}

/**
 * Ordering key for conflict resolution: hybrid-logical-clock time, then device id as a
 * deterministic tie-break. Larger wins.
 */
data class Stamp(val hlc: Long, val deviceId: String) : Comparable<Stamp> {
    override fun compareTo(other: Stamp): Int =
        compareValuesBy(this, other, Stamp::hlc, Stamp::deviceId)
}
