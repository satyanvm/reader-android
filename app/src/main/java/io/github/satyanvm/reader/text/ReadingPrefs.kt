package io.github.satyanvm.reader.text

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TextTheme { LIGHT, SEPIA, DARK }

/** How flowing text moves: a screen at a time (like Play Books), or one continuous scroll. */
enum class TextFlow { PAGED, CONTINUOUS }

/** Flowing-text appearance; applies to every book on this device. */
class ReadingPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("reading_prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { TextTheme.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(TextTheme.LIGHT)
    )
    val theme: StateFlow<TextTheme> = _theme.asStateFlow()

    private val _flow = MutableStateFlow(
        runCatching { TextFlow.valueOf(prefs.getString("flow", null) ?: "") }.getOrDefault(TextFlow.PAGED)
    )
    val flow: StateFlow<TextFlow> = _flow.asStateFlow()

    var textScale: Float
        get() = prefs.getFloat("text_scale", 1f)
        set(value) = prefs.edit().putFloat("text_scale", value).apply()

    fun setTheme(theme: TextTheme) {
        _theme.value = theme
        prefs.edit().putString("theme", theme.name).apply()
    }

    fun setFlow(flow: TextFlow) {
        _flow.value = flow
        prefs.edit().putString("flow", flow.name).apply()
    }
}
