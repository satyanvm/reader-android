package io.github.satyanvm.reader.text

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads text from a page image, for scanned PDFs. */
fun interface Ocr {
    suspend fun lines(page: Bitmap): List<Line>
}

/**
 * On-device recognition with ML Kit (via Google Play services; the model is fetched at install
 * time, see the manifest). Output lines go through the same [Reflow] as PDF text, with pixel
 * coordinates and the line height standing in for font size.
 */
class MlKitOcr : Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun lines(page: Bitmap): List<Line> {
        val result = suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(page, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                Line(
                    spans = listOf(Span(line.text)),
                    x = box.left.toFloat(),
                    endX = box.right.toFloat(),
                    y = box.bottom.toFloat(),
                    fontSize = box.height().toFloat(),
                )
            }
    }
}
