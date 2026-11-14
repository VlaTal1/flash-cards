package com.vlatal.flashcards

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlatal.flashcards.data.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.lifecycleScope
import com.vlatal.flashcards.data.AutoBackup
import com.vlatal.flashcards.data.Repository
import kotlinx.coroutines.launch
import com.vlatal.flashcards.data.Speaker
import com.vlatal.flashcards.ui.*

sealed interface Screen {
    data object Home : Screen
    data class DeckDetails(val deckId: Long) : Screen
    data class Cards(val deckId: Long) : Screen
    data class Game(val config: GameConfig) : Screen
    data object Stats : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        speaker = Speaker(this)
        val repo = Repository.get(this)
        Reminders.schedule(this)
        lifecycleScope.launch { AutoBackup.runIfDue(this@MainActivity) }
        val settings = Settings.get(this)
        setContent {
            val values by settings.values.collectAsStateWithLifecycle()
            val dark = isDark(values.theme)
            // keep status/navigation bar icons readable when the app theme differs from the system one
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
            }
            AppTheme(values) {
                val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
                // game run counter, so that "Another round" recreates the session
                var gameRun by rememberSaveable { mutableIntStateOf(0) }
                val push: (Screen) -> Unit = { stack.add(it) }
                val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

                BackHandler(enabled = stack.size > 1) { pop() }

                when (val s = stack.last()) {
                    Screen.Home -> HomeScreen(
                        repo, onOpenDeck = { push(Screen.DeckDetails(it)) },
                        onStats = { push(Screen.Stats) }, onSettings = { push(Screen.Settings) },
                    )
                    Screen.Stats -> StatsScreen(repo, onBack = pop)
                    Screen.Settings -> SettingsScreen(onBack = pop)
                    is Screen.DeckDetails -> DeckScreen(
                        repo, s.deckId, onBack = pop,
                        onCards = { push(Screen.Cards(s.deckId)) },
                        onPlay = { push(Screen.Game(it)) },
                    )
                    is Screen.Cards -> CardsScreen(repo, s.deckId, speaker, onBack = pop)
                    is Screen.Game -> key(s, gameRun) {
                        GameScreen(
                            repo, s.config, speaker, onExit = pop,
                            onRestart = { cfg ->
                                stack[stack.lastIndex] = Screen.Game(cfg); gameRun++
                            },
                        )
                    }
                }
            }
        }
    }

    private companion object {
        // the same scrims enableEdgeToEdge() uses by default
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    override fun onResume() {
        super.onResume()
        Repository.get(this).refresh() // a new day may have started — refresh the counters
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }
}
