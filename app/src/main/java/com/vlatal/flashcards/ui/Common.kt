package com.vlatal.flashcards.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vlatal.flashcards.R
import com.vlatal.flashcards.data.Locales
import com.vlatal.flashcards.data.Settings
import com.vlatal.flashcards.data.Srs
import java.util.Locale

val SkBlue = Color(0xFF0B4EA2)
val SkRed = Color(0xFFEE1C25)
val Good = Color(0xFF2E9E5B)
val Bad = Color(0xFFD64545)

private val Light = lightColorScheme(
    primary = SkBlue, onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF), onPrimaryContainer = Color(0xFF001B3F),
    secondary = SkRed, onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3FF), onSecondaryContainer = Color(0xFF001B3F),
    background = Color(0xFFF8F9FF), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC), onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF6), surfaceContainerHigh = Color(0xFFE5E8F1),
    surfaceContainerHighest = Color(0xFFE0E2EA),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFABC7FF), onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF1B4A8C), onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFFF8A80), onSecondary = Color(0xFF5F0006),
    secondaryContainer = Color(0xFF1B4A8C), onSecondaryContainer = Color(0xFFD7E3FF),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E), onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474E),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

/** Whether the dark theme is active for the given theme setting (system / light / dark). */
@Composable
fun isDark(theme: String): Boolean = when (theme) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

/**
 * App theme plus in-app language: string resources inside [content] use the language chosen
 * in settings, and switching it applies immediately, without recreating the activity.
 */
@Composable
fun AppTheme(settings: Settings.Values, content: @Composable () -> Unit) {
    val dark = isDark(settings.theme)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val (ctx, conf) = remember(settings.language, configuration) {
        if (settings.language.isEmpty()) context to configuration
        else {
            val conf = Locales.withLocale(configuration, Locale.forLanguageTag(settings.language))
            LocalizedContext(context, context.createConfigurationContext(conf).resources) to conf
        }
    }
    CompositionLocalProvider(LocalContext provides ctx, LocalConfiguration provides conf) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}

/**
 * Wraps the activity context with localized resources. It stays a [ContextWrapper] around the
 * activity, so APIs that look up the activity through the context (activity result launchers,
 * permission requests) keep working.
 */
private class LocalizedContext(base: Context, private val res: Resources) : ContextWrapper(base) {
    override fun getResources(): Resources = res
}

/** The locale the UI is currently shown in (for date formatting). */
@Composable
fun uiLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
fun plural(@PluralsRes id: Int, count: Int): String = pluralStringResource(id, count, count)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        },
        actions = actions,
    )
}

/** Level color: from gray (not learned) through red to green. */
fun levelColor(level: Int): Color {
    val palette = listOf(
        Color(0xFFB0B6C3), Color(0xFFE57373), Color(0xFFFFA05A), Color(0xFFFFCA4F),
        Color(0xFFC5D86D), Color(0xFF7CC97A), Color(0xFF3FAE6A), Color(0xFF1E8A57),
    )
    return palette[level.coerceIn(0, Srs.MAX_LEVEL)]
}

/** Horizontal bar showing how cards are distributed across levels. */
@Composable
fun LevelBar(levels: IntArray, modifier: Modifier = Modifier) {
    val total = levels.sum()
    Row(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        if (total == 0) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {}
        } else levels.forEachIndexed { lvl, n ->
            if (n > 0) Surface(color = levelColor(lvl), modifier = Modifier.weight(n.toFloat()).fillMaxHeight()) {}
        }
    }
}

@Composable
fun LevelDot(level: Int, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(50), color = levelColor(level), modifier = modifier.size(10.dp)) {}
}
