package com.vlatal.flashcards.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * On-screen keyboard used by the Typing and Dictation modes. The system keyboard's
 * autocorrect and word suggestions would give the answer away or "fix" a misspelled word,
 * so these modes type through this keyboard instead.
 */
object KeyboardLayouts {
    /** Letter rows for a language, or null when there is no built-in layout for it. */
    fun forLanguage(langTag: String): List<String>? = when (langTag.substringBefore('-').lowercase()) {
        "sk" -> listOf("áäčďéíĺľň", "óôŕšťúýž", "qwertzuiop", "asdfghjkl", "yxcvbnm")
        "uk" -> listOf("йцукенгшщзхї", "фівапролджє", "ґячсмитьбю")
        "ru" -> listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё")
        "en" -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        else -> null
    }
}

@Composable
fun AppKeyboard(rows: List<String>, onKey: (String) -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    fun tap(action: () -> Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        action()
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 3.dp, vertical = 6.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val widest = rows.maxOf { it.length }
            rows.forEach { row ->
                // all keys have the same width; shorter rows are centered
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    val side = (widest - row.length) / 2f
                    if (side > 0) Spacer(Modifier.weight(side))
                    row.forEach { ch -> Key(ch.toString(), Modifier.weight(1f)) { tap { onKey(ch.toString()) } } }
                    if (side > 0) Spacer(Modifier.weight(side))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Key("'", Modifier.weight(1f)) { tap { onKey("'") } }
                Key("-", Modifier.weight(1f)) { tap { onKey("-") } }
                Key("", Modifier.weight(5f), secondary = true) { tap { onKey(" ") } }
                Key(",", Modifier.weight(1f)) { tap { onKey(",") } }
                KeyBox(Modifier.weight(2f), secondary = true, onClick = { tap(onBackspace) }) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, null, Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, secondary: Boolean = false, onClick: () -> Unit) {
    KeyBox(modifier, secondary, onClick) {
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun KeyBox(modifier: Modifier, secondary: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (secondary) cs.surfaceContainerHighest else cs.surfaceContainerLowest,
        contentColor = cs.onSurface,
        shadowElevation = 1.dp,
        modifier = modifier.padding(horizontal = 2.dp).height(46.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** Looks like an outlined text field with a blinking cursor; typing happens via [AppKeyboard]. */
@Composable
fun AnswerDisplay(text: String, label: String, active: Boolean, trailing: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "cursor",
    )
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (active) cs.primary else cs.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        Surface(
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(if (active) 2.dp else 1.dp, if (active) cs.primary else cs.outline),
            color = cs.surface,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                    if (active) Text("|", style = MaterialTheme.typography.bodyLarge, color = cs.primary,
                        modifier = Modifier.alpha(if (blink > 0.5f) 1f else 0f))
                }
                trailing()
            }
        }
    }
}
