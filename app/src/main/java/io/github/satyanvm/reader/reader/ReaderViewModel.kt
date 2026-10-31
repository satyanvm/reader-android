package io.github.satyanvm.reader.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.satyanvm.reader.AppGraph
import io.github.satyanvm.reader.text.EpubBook
import io.github.satyanvm.reader.text.MlKitOcr
import io.github.satyanvm.reader.text.TextDocument
import io.github.satyanvm.reader.text.TextFlow
import io.github.satyanvm.reader.text.TextTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ReaderViewModel(private val documentId: String, private val graph: AppGraph) : ViewModel() {
    private val _text = MutableStateFlow<TextDocument?>(null)
    /** Flowing-text view of a PDF; created the first time the reader switches to text mode. */
    val text: StateFlow<TextDocument?> = _text.asStateFlow()

    val theme: StateFlow<TextTheme> = graph.readingPrefs.theme

    private val _flow = MutableStateFlow(TextFlow.PAGED)
    /** Every book opens as pages that turn; scrolling is a choice for this sitting only. */
    val flow: StateFlow<TextFlow> = _flow.asStateFlow()

    private var pdfFile: File? = null

    val controller = ReaderController(
        documentId = documentId,
        loadDocument = {
            val doc = graph.documents.get(documentId) ?: error("Document not found")
            graph.documents.touch(documentId)
            val file = File(doc.localPath)
            if (doc.format == "EPUB") {
                // EPUBs are text already: always flowing text.
                LoadedDocument(doc.title, EpubBook.open(file), ReadingMode.TEXT, graph.readingPrefs.textScale)
            } else {
                pdfFile = file
                val pdf = PdfDocument.open(file)
                // Flowing text, a page at a time, unless this book was switched to its original pages.
                val mode = runCatching { ReadingMode.valueOf(doc.readingMode) }.getOrDefault(ReadingMode.TEXT)
                if (mode == ReadingMode.TEXT) {
                    // Build around the saved page first, so the restore target is ready soonest.
                    ensureText(file, pdf, from = graph.positions.load(documentId)?.pageIndex ?: 0)
                }
                LoadedDocument(doc.title, pdf, mode, graph.readingPrefs.textScale)
            }
        },
        positions = graph.positions,
        sync = graph.syncEngine,
        telemetry = graph.telemetry,
        scope = viewModelScope,
        saveScope = graph.appScope,
        now = System::currentTimeMillis,
    ).also { it.start() }

    fun setMode(mode: ReadingMode) {
        val file = pdfFile ?: return // EPUBs have no original pages
        val state = controller.state.value
        val pdf = state.document as? PagedDocument ?: return
        if (mode == ReadingMode.TEXT) ensureText(file, pdf, state.pageIndex)
        controller.setMode(mode)
        graph.appScope.launch { graph.documents.setReadingMode(documentId, mode.name) }
    }

    fun setTextScale(scale: Float) {
        controller.setTextScale(scale)
        graph.readingPrefs.textScale = controller.state.value.textScale
    }

    fun setTheme(theme: TextTheme) = graph.readingPrefs.setTheme(theme)

    fun setFlow(flow: TextFlow) {
        _flow.value = flow
    }

    private fun ensureText(file: File, pdf: PagedDocument, from: Int) {
        if (_text.value != null) return
        val doc = TextDocument(
            pdf = file,
            cacheDir = TextDocument.cacheDirFor(graph.filesDir, documentId),
            pageCount = pdf.pageCount,
            renderPage = pdf::render,
            ocr = MlKitOcr(),
        )
        _text.value = doc
        doc.prefetch(viewModelScope, from = from)
    }

    override fun onCleared() {
        controller.flush()
        controller.state.value.document?.close()
        _text.value?.close()
    }
}
