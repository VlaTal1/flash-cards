package com.vlatal.flashcards.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlatal.flashcards.R
import com.vlatal.flashcards.Reminders
import com.vlatal.flashcards.data.AutoBackup
import com.vlatal.flashcards.data.Locales
import com.vlatal.flashcards.data.Settings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings.get(context) }
    val s by settings.values.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var timePicker by remember { mutableStateOf(false) }
    val locale = uiLocale()

    fun setReminder(enabled: Boolean) {
        settings.update { copy(reminderEnabled = enabled) }
        Reminders.schedule(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        setReminder(granted)
        if (!granted) scope.launch { snackbar.showSnackbar(context.getString(R.string.notif_permission_denied)) }
    }

    Scaffold(
        topBar = { AppBar(stringResource(R.string.settings), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- appearance ----------
            Section(stringResource(R.string.section_appearance))
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.language.isEmpty(), { settings.update { copy(language = "") } },
                    { Text(stringResource(R.string.lang_system)) })
                Locales.SUPPORTED.forEach { (tag, name) ->
                    FilterChip(s.language == tag, { settings.update { copy(language = tag) } }, { Text(name) })
                }
            }
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "system" to R.string.theme_system,
                    "light" to R.string.theme_light,
                    "dark" to R.string.theme_dark,
                ).forEach { (value, label) ->
                    FilterChip(s.theme == value, { settings.update { copy(theme = value) } }, { Text(stringResource(label)) },
                        leadingIcon = {
                            Icon(when (value) {
                                "light" -> Icons.Default.LightMode
                                "dark" -> Icons.Default.DarkMode
                                else -> Icons.Default.BrightnessAuto
                            }, null, Modifier.size(18.dp))
                        })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---------- study ----------
            Section(stringResource(R.string.section_study))
            Text(stringResource(R.string.new_per_day), style = MaterialTheme.typography.titleSmall)
            Hint(stringResource(R.string.new_per_day_hint))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 5, 10, 15, 20, 30).forEach { n ->
                    FilterChip(s.newPerDay == n, { settings.update { copy(newPerDay = n) } }, { Text("$n") })
                }
            }
            Text(stringResource(R.string.daily_goal), style = MaterialTheme.typography.titleSmall)
            Hint(stringResource(R.string.daily_goal_hint))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 20, 30, 50, 100).forEach { n ->
                    FilterChip(s.dailyGoal == n, { settings.update { copy(dailyGoal = n) } }, { Text("$n") })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---------- reminder ----------
            Section(stringResource(R.string.section_reminder))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.remind_daily), style = MaterialTheme.typography.titleSmall)
                    Hint(stringResource(R.string.remind_hint))
                }
                Switch(checked = s.reminderEnabled, onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(context)) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else setReminder(on)
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { timePicker = true }, enabled = s.reminderEnabled) {
                    Icon(Icons.Default.Schedule, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.reminder_time, "%02d:%02d".format(s.reminderMinutes / 60, s.reminderMinutes % 60)))
                }
                TextButton(onClick = { scope.launch { Reminders.notifyIfNeeded(context, force = true) } },
                    enabled = s.reminderEnabled) { Text(stringResource(R.string.test_notification)) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---------- auto-backup ----------
            Section(stringResource(R.string.section_backup))
            if (!AutoBackup.supported) {
                Text(stringResource(R.string.backup_unsupported), style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.backup_weekly), style = MaterialTheme.typography.titleSmall)
                        Hint(stringResource(R.string.backup_hint))
                    }
                    Switch(checked = s.autoBackup, onCheckedChange = { on -> settings.update { copy(autoBackup = on) } })
                }
                Text(
                    stringResource(R.string.backup_last,
                        if (s.lastAutoBackup == 0L) stringResource(R.string.backup_never)
                        else SimpleDateFormat("d MMMM yyyy, HH:mm", locale).format(Date(s.lastAutoBackup))),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = {
                    scope.launch {
                        val msg = try { context.getString(R.string.backup_saved, AutoBackup.runNow(context)) }
                        catch (e: Exception) { context.getString(R.string.error_msg, e.message) }
                        snackbar.showSnackbar(msg, withDismissAction = true)
                    }
                }) {
                    Icon(Icons.Default.Backup, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.backup_now))
                }
            }
            Spacer(Modifier.height(24.dp))
            Hint(stringResource(R.string.data_local))
        }
    }

    if (timePicker) {
        val state = rememberTimePickerState(s.reminderMinutes / 60, s.reminderMinutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    timePicker = false
                    settings.update { copy(reminderMinutes = state.hour * 60 + state.minute) }
                    Reminders.schedule(context)
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { timePicker = false }) { Text(stringResource(R.string.cancel)) } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
