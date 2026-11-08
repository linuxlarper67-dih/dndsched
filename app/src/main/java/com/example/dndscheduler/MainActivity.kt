@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.dndscheduler

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Root(tick.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
        Scheduler.sync(this, applyNow = false)
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun fmt(min: Int): String =
    LocalTime.of(min / 60, min % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun dayName(d: Int, style: TextStyle = TextStyle.SHORT): String =
    DayOfWeek.of(d).getDisplayName(style, Locale.getDefault())

private fun daysLabel(days: Set<Int>): String =
    if (days.size == 7) "Every day" else days.sorted().joinToString(", ") { dayName(it) }

@Composable
fun Root(tick: Int) {
    val ctx = LocalContext.current
    var schedules by remember { mutableStateOf(Store.load(ctx)) }
    var mode by remember { mutableIntStateOf(Store.mode(ctx)) }
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var isNew by remember { mutableStateOf(false) }

    val granted = remember(tick) {
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }
    val next = remember(tick, schedules) { Scheduler.nextTransition(schedules, ZonedDateTime.now()) }

    fun commit(list: List<Schedule>) {
        schedules = list
        Store.save(ctx, list)
        Scheduler.sync(ctx)
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("DND Scheduler") }, scrollBehavior = scroll) },
        floatingActionButton = {
            if (schedules.size < MAX_SCHEDULES) {
                ExtendedFloatingActionButton(
                    onClick = {
                        isNew = true
                        editing = Schedule(days = setOf(1), startMin = 13 * 60, endMin = 15 * 60)
                    },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add time") },
                )
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!granted) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Permission needed", style = MaterialTheme.typography.titleMedium)
                        Text("Allow “Do Not Disturb access” so the app can switch DND on and off for you.")
                        Button(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        }) { Text("Open settings") }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val text = if (next == null) "No active schedules" else {
                            val on = schedules.any { it.enabled && it.isActive(next) }
                            val whenText = dayName(next.dayOfWeek.value) + " " + fmt(next.hour * 60 + next.minute)
                            (if (on) "DND turns on " else "DND turns off ") + whenText
                        }
                        Text(text, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${schedules.size} / $MAX_SCHEDULES times",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DND type", style = MaterialTheme.typography.labelLarge)
                    val labels = listOf("Priority", "Alarms only", "Silence")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        labels.forEachIndexed { i, label ->
                            SegmentedButton(
                                selected = mode == i,
                                onClick = {
                                    mode = i
                                    Store.setMode(ctx, i)
                                    Scheduler.sync(ctx)
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                            ) { Text(label, maxLines = 1) }
                        }
                    }
                }
            }

            if (schedules.isEmpty()) item {
                Text(
                    "Nothing yet. Tap “Add time” to pick days and hours.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }

            items(
                schedules.sortedWith(compareBy<Schedule>({ it.days.minOrNull() ?: 0 }, { it.startMin })),
                key = { it.id },
            ) { s ->
                Card(onClick = { isNew = false; editing = s }) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${fmt(s.startMin)} – ${fmt(s.endMin)}", style = MaterialTheme.typography.titleLarge)
                            Text(
                                daysLabel(s.days),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = s.enabled, onCheckedChange = { on ->
                            commit(schedules.map { if (it.id == s.id) it.copy(enabled = on) else it })
                        })
                        IconButton(onClick = { commit(schedules.filter { it.id != s.id }) }) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    }
                }
            }
        }
    }

    editing?.let { current ->
        EditorSheet(
            initial = current,
            onDismiss = { editing = null },
            onSave = { saved ->
                commit(if (isNew) schedules + saved else schedules.map { if (it.id == saved.id) saved else it })
                editing = null
            },
        )
    }
}

@Composable
fun EditorSheet(initial: Schedule, onDismiss: () -> Unit, onSave: (Schedule) -> Unit) {
    var days by remember { mutableStateOf(initial.days) }
    var start by remember { mutableIntStateOf(initial.startMin) }
    var end by remember { mutableIntStateOf(initial.endMin) }
    var picking by remember { mutableIntStateOf(0) } // 0 none, 1 start, 2 end

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Do Not Disturb time", style = MaterialTheme.typography.headlineSmall)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (d in 1..7) {
                    val sel = d in days
                    Box(
                        Modifier.size(38.dp).clip(CircleShape)
                            .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { days = if (sel) days - d else days + d },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            dayName(d, TextStyle.NARROW),
                            color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { picking = 1 }, modifier = Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Start", style = MaterialTheme.typography.labelSmall)
                        Text(fmt(start), style = MaterialTheme.typography.titleLarge)
                    }
                }
                FilledTonalButton(onClick = { picking = 2 }, modifier = Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("End", style = MaterialTheme.typography.labelSmall)
                        Text(fmt(end), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            if (end < start) {
                Text(
                    "Ends the next day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = days.isNotEmpty() && start != end,
                    onClick = { onSave(initial.copy(days = days, startMin = start, endMin = end)) },
                ) { Text("Save") }
            }
        }
    }

    if (picking != 0) {
        TimeDialog(
            initialMin = if (picking == 1) start else end,
            onDismiss = { picking = 0 },
            onConfirm = { m ->
                if (picking == 1) start = m else end = m
                picking = 0
            },
        )
    }
}

@Composable
fun TimeDialog(initialMin: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMin / 60,
        initialMinute = initialMin % 60,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}
