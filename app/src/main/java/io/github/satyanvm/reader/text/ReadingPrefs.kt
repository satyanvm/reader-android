package io.github.satyanvm.reader.text

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TextTheme { LIGHT, SEPIA, DARK }

/** Flowing-text appearance; applies to every book on this device. */
class ReadingPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("reading_prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { TextTheme.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(TextTheme.LIGHT)
    )
    val theme: StateFlow<TextTheme> = _theme.asStateFlow()

    var textScale: Float
        get() = prefs.getFloat("text_scale", 1f)
        set(value) = prefs.edit().putFloat("text_scale", value).apply()

    fun setTheme(theme: TextTheme) {
        _theme.value = theme
        prefs.edit().putString("theme", theme.name).apply()
    }
}
