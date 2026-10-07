package pl.nawyki.app

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private enum class HabitType { GOOD, BAD }
private enum class DailyStatus { SUCCESS, FAILURE }
private enum class AppScreen { HABITS, STATS, CALENDAR, SETTINGS }

private data class Habit(
    val id: Long,
    val name: String,
    val type: HabitType,
    val createdAt: String,
    val weeklyTarget: Int = 7
)

private data class StatusKey(val habitId: Long, val date: String)

private data class BackupData(
    val habits: List<Habit>,
    val statuses: Map<StatusKey, DailyStatus>,
    val reminder: ReminderSettings
)

data class ReminderSettings(
    val enabled: Boolean = false,
    val hour: Int = 20,
    val minute: Int = 0
)

private val AppBackground = Color(0xFF0B0B0F)
private val AppSurface = Color(0xFF17181F)
private val GoodColor = Color(0xFF29D17D)
private val BadColor = Color(0xFFFF6B6B)
private val AccentColor = Color(0xFF7C5CFF)
private val NeutralColor = Color(0xFF404552)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = darkColorScheme(
                primary = AccentColor,
                secondary = GoodColor,
                tertiary = BadColor,
                background = AppBackground,
                surface = AppSurface,
                surfaceVariant = Color(0xFF20222B)
            )
            MaterialTheme(colorScheme = colors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    HabitApp(applicationContext)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitApp(context: Context) {
    val repository = remember { HabitRepository(context) }
    val habits: SnapshotStateList<Habit> = remember {
        mutableStateListOf<Habit>().apply { addAll(repository.loadHabits()) }
    }
    val statuses: SnapshotStateMap<StatusKey, DailyStatus> = remember {
        mutableStateMapOf<StatusKey, DailyStatus>().apply { putAll(repository.loadStatuses()) }
    }

    var reminderSettings by remember { mutableStateOf(repository.loadReminderSettings()) }
    var screen by remember { mutableStateOf(AppScreen.HABITS) }
    var filter by remember { mutableStateOf<HabitType?>(null) }
    var showHabitDialog by remember { mutableStateOf(false) }
    var editingHabit by remember { mutableStateOf<Habit?>(null) }
    var pendingBackup by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf<String?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { }
    )

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val saved = runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(pendingBackup)
                }
            }.isSuccess
            backupMessage = if (saved) "Backup zapisany." else "Nie udało się zapisać backupu."
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Pusty plik")
                repository.parseBackup(raw) ?: error("Nieprawidłowy backup")
            }.getOrNull()

            if (result == null) {
                backupMessage = "Nie udało się wczytać backupu."
            } else {
                habits.clear()
                habits.addAll(result.habits)
                statuses.clear()
                statuses.putAll(result.statuses)
                reminderSettings = result.reminder
                repository.saveHabits(habits)
                repository.saveStatuses(statuses)
                repository.saveReminderSettings(reminderSettings)
                backupMessage = "Backup wczytany."
            }
        }
    }

    val today = LocalDate.now()
    val todayKey = today.toString()
    val todayMarked = habits.count { statuses[StatusKey(it.id, todayKey)] != null }
    val todaySuccess = habits.count { statuses[StatusKey(it.id, todayKey)] == DailyStatus.SUCCESS }

    fun persistHabitsAndStatuses() {
        repository.saveHabits(habits)
        repository.saveStatuses(statuses)
    }

    fun saveReminder(newValue: ReminderSettings) {
        reminderSettings = newValue
        repository.saveReminderSettings(newValue)
    }

    fun setStatusForDate(habit: Habit, date: LocalDate, status: DailyStatus?) {
        val key = StatusKey(habit.id, date.toString())
        if (status == null) statuses.remove(key) else statuses[key] = status
        repository.saveStatuses(statuses)
    }

    LaunchedEffect(reminderSettings) {
        ReminderScheduler.update(context, reminderSettings)
    }

    val topTitle = when (screen) {
        AppScreen.HABITS -> "Nawyki 3.0"
        AppScreen.STATS -> "Statystyki"
        AppScreen.CALENDAR -> "Kalendarz"
        AppScreen.SETTINGS -> "Ustawienia"
    }

    val topSubtitle = when (screen) {
        AppScreen.HABITS -> "Dzisiaj: $todaySuccess/$todayMarked sukcesów"
        AppScreen.STATS -> "Skuteczność i trend 30 dni"
        AppScreen.CALENDAR -> "Kliknij dzień, aby poprawić wpis"
        AppScreen.SETTINGS -> "Przypomnienia i backup"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(topTitle, fontWeight = FontWeight.Bold)
                        Text(topSubtitle, style = MaterialTheme.typography.bodySmall)
                    }
                }
            )
        },
        floatingActionButton = {
            if (screen == AppScreen.HABITS) {
                FloatingActionButton(onClick = {
                    editingHabit = null
                    showHabitDialog = true
                }) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                AppScreen.entries.forEach { item ->
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = {
                            Text(
                                when (item) {
                                    AppScreen.HABITS -> "🏠"
                                    AppScreen.STATS -> "📊"
                                    AppScreen.CALENDAR -> "📅"
                                    AppScreen.SETTINGS -> "⚙️"
                                }
                            )
                        },
                        label = {
                            Text(
                                when (item) {
                                    AppScreen.HABITS -> "Nawyki"
                                    AppScreen.STATS -> "Staty"
                                    AppScreen.CALENDAR -> "Kalendarz"
                                    AppScreen.SETTINGS -> "Opcje"
                                }
                            )
                        }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (screen) {
                AppScreen.HABITS -> HabitsScreen(
                    habits = habits,
                    statuses = statuses,
                    today = today,
                    filter = filter,
                    onFilterChange = { filter = it },
                    onStatus = { habit, status ->
                        val key = StatusKey(habit.id, todayKey)
                        if (statuses[key] == status) statuses.remove(key) else statuses[key] = status
                        persistHabitsAndStatuses()
                    },
                    onDelete = { habit ->
                        habits.removeAll { it.id == habit.id }
                        statuses.keys.filter { it.habitId == habit.id }.toList().forEach { statuses.remove(it) }
                        persistHabitsAndStatuses()
                    },
                    onEdit = { habit ->
                        editingHabit = habit
                        showHabitDialog = true
                    }
                )

                AppScreen.STATS -> StatsScreen(habits = habits, statuses = statuses, today = today)

                AppScreen.CALENDAR -> CalendarScreen(
                    habits = habits,
                    statuses = statuses,
                    today = today,
                    onSetStatus = { habit, date, status -> setStatusForDate(habit, date, status) }
                )

                AppScreen.SETTINGS -> SettingsScreen(
                    reminderSettings = reminderSettings,
                    backupMessage = backupMessage,
                    onReminderChange = { saveReminder(it) },
                    onPickTime = {
                        val current = reminderSettings
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> saveReminder(current.copy(hour = hour, minute = minute)) },
                            current.hour,
                            current.minute,
                            true
                        ).show()
                    },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onExport = {
                        pendingBackup = repository.createBackup(habits, statuses, reminderSettings)
                        backupMessage = null
                        exportLauncher.launch("nawyki-backup-${LocalDate.now()}.json")
                    },
                    onImport = {
                        backupMessage = null
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                )
            }
        }
    }

    if (showHabitDialog) {
        AddOrEditHabitDialog(
            initialHabit = editingHabit,
            onDismiss = {
                showHabitDialog = false
                editingHabit = null
            },
            onSave = { name, type, weeklyTarget ->
                val trimmed = name.trim()
                if (editingHabit == null) {
                    val nextId = (habits.maxOfOrNull { it.id } ?: 0L) + 1L
                    habits.add(
                        Habit(
                            id = nextId,
                            name = trimmed,
                            type = type,
                            createdAt = todayKey,
                            weeklyTarget = weeklyTarget
                        )
                    )
                } else {
                    val editId = editingHabit?.id
                    val index = habits.indexOfFirst { it.id == editId }
                    if (index >= 0) {
                        habits[index] = habits[index].copy(
                            name = trimmed,
                            type = type,
                            weeklyTarget = weeklyTarget
                        )
                    }
                }
                persistHabitsAndStatuses()
                editingHabit = null
                showHabitDialog = false
            }
        )
    }
}

@Composable
private fun HabitsScreen(
    habits: List<Habit>,
    statuses: Map<StatusKey, DailyStatus>,
    today: LocalDate,
    filter: HabitType?,
    onFilterChange: (HabitType?) -> Unit,
    onStatus: (Habit, DailyStatus) -> Unit,
    onDelete: (Habit) -> Unit,
    onEdit: (Habit) -> Unit
) {
    val visibleHabits = habits.filter { filter == null || it.type == filter }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { OverviewHeader(habits = habits, statuses = statuses, today = today) }
        item { FilterBar(filter = filter, onFilterChange = onFilterChange) }

        if (visibleHabits.isEmpty()) {
            item {
                EmptyState(
                    hasAnyHabits = habits.isNotEmpty(),
                    onAddHint = "Kliknij + aby dodać nowy nawyk"
                )
            }
        } else {
            items(
                count = visibleHabits.size,
                key = { index -> visibleHabits[index].id }
            ) { index ->
                val habit = visibleHabits[index]
                HabitCard(
                    habit = habit,
                    statuses = statuses,
                    today = today,
                    onStatus = { status -> onStatus(habit, status) },
                    onDelete = { onDelete(habit) },
                    onEdit = { onEdit(habit) }
                )
            }
        }
    }
}

@Composable
private fun OverviewHeader(
    habits: List<Habit>,
    statuses: Map<StatusKey, DailyStatus>,
    today: LocalDate
) {
    val goodCount = habits.count { it.type == HabitType.GOOD }
    val badCount = habits.count { it.type == HabitType.BAD }
    val bestStreak = habits.maxOfOrNull { currentStreak(it.id, statuses, today) } ?: 0

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoTile("Dobre", goodCount.toString(), GoodColor, Modifier.weight(1f))
        InfoTile("Złe", badCount.toString(), BadColor, Modifier.weight(1f))
        InfoTile("Najlepsza seria", "$bestStreak d", AccentColor, Modifier.weight(1f))
    }
}

@Composable
private fun InfoTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(modifier = Modifier.size(10.dp).background(accent, CircleShape))
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBar(filter: HabitType?, onFilterChange: (HabitType?) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(selected = filter == null, onClick = { onFilterChange(null) }, label = { Text("Wszystkie") })
        FilterChip(selected = filter == HabitType.GOOD, onClick = { onFilterChange(HabitType.GOOD) }, label = { Text("Dobre") })
        FilterChip(selected = filter == HabitType.BAD, onClick = { onFilterChange(HabitType.BAD) }, label = { Text("Złe") })
    }
}

@Composable
private fun EmptyState(hasAnyHabits: Boolean, onAddHint: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                if (hasAnyHabits) "Brak nawyków w tej kategorii." else "Dodaj pierwszy nawyk i zacznij go kontrolować.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(onAddHint, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
        }
    }
}

@Composable
private fun HabitCard(
    habit: Habit,
    statuses: Map<StatusKey, DailyStatus>,
    today: LocalDate,
    onStatus: (DailyStatus) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    val todayStatus = statuses[StatusKey(habit.id, today.toString())]
    val streak = currentStreak(habit.id, statuses, today)
    val last7 = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val marked7 = last7.count { statuses[StatusKey(habit.id, it.toString())] != null }
    val success7 = last7.count { statuses[StatusKey(habit.id, it.toString())] == DailyStatus.SUCCESS }
    val percent = successPercent(habit.id, statuses)
    val weekSuccess = weekSuccessCount(habit.id, statuses, today)
    val weekProgress = (weekSuccess.toFloat() / habit.weeklyTarget.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    val accent = if (habit.type == HabitType.GOOD) GoodColor else BadColor

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(10.dp).background(accent, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(habit.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (habit.type == HabitType.GOOD) "Dobry nawyk" else "Zły nawyk",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                }
                Row {
                    TextButton(onClick = onEdit) { Text("Edytuj") }
                    TextButton(onClick = onDelete) { Text("Usuń") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Seria", "$streak dni")
                Metric("Skuteczność", "$percent%")
                Metric("7 dni", "$success7/$marked7")
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Cel tygodniowy", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                    Text("$weekSuccess/${habit.weeklyTarget}", fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(NeutralColor, RoundedCornerShape(99.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(weekProgress)
                            .height(8.dp)
                            .background(if (weekProgress >= 1f) GoodColor else AccentColor, RoundedCornerShape(99.dp))
                    )
                }
            }

            SevenDayStrip(habit.id, statuses, last7)

            Text(
                text = if (habit.type == HabitType.GOOD)
                    "Czy wykonałeś ten nawyk dzisiaj?"
                else
                    "Czy udało Ci się uniknąć tego nawyku dzisiaj?",
                style = MaterialTheme.typography.bodyMedium
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusButton(
                    selected = todayStatus == DailyStatus.SUCCESS,
                    text = "✓ Sukces",
                    fillColor = GoodColor,
                    modifier = Modifier.weight(1f),
                    onClick = { onStatus(DailyStatus.SUCCESS) }
                )
                StatusButton(
                    selected = todayStatus == DailyStatus.FAILURE,
                    text = "✕ Porażka",
                    fillColor = BadColor,
                    modifier = Modifier.weight(1f),
                    onClick = { onStatus(DailyStatus.FAILURE) }
                )
            }
        }
    }
}

@Composable
private fun StatusButton(
    selected: Boolean,
    text: String,
    fillColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = fillColor)) {
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SevenDayStrip(habitId: Long, statuses: Map<StatusKey, DailyStatus>, dates: List<LocalDate>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        dates.forEach { date ->
            val status = statuses[StatusKey(habitId, date.toString())]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("pl")).take(2).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.LightGray
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(
                            color = when (status) {
                                DailyStatus.SUCCESS -> GoodColor
                                DailyStatus.FAILURE -> BadColor
                                null -> NeutralColor
                            },
                            shape = CircleShape
                        )
                )
            }
        }
    }
}

@Composable
private fun StatsScreen(habits: List<Habit>, statuses: Map<StatusKey, DailyStatus>, today: LocalDate) {
    val last7 = (0..6).map { today.minusDays(it.toLong()) }
    val last30 = (29 downTo 0).map { today.minusDays(it.toLong()) }
    val totalSuccess7 = habits.sumOf { habit -> last7.count { statuses[StatusKey(habit.id, it.toString())] == DailyStatus.SUCCESS } }
    val totalMarked7 = habits.sumOf { habit -> last7.count { statuses[StatusKey(habit.id, it.toString())] != null } }
    val totalSuccess30 = habits.sumOf { habit -> last30.count { statuses[StatusKey(habit.id, it.toString())] == DailyStatus.SUCCESS } }
    val totalMarked30 = habits.sumOf { habit -> last30.count { statuses[StatusKey(habit.id, it.toString())] != null } }
    val overallPercent = if (statuses.isEmpty()) 0 else (statuses.values.count { it == DailyStatus.SUCCESS } * 100f / statuses.size).roundToInt()
    val topHabits = habits.sortedByDescending { successPercent(it.id, statuses) }.take(8)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                InfoTile("Ogółem", "$overallPercent%", AccentColor, Modifier.weight(1f))
                InfoTile("7 dni", "$totalSuccess7/$totalMarked7", GoodColor, Modifier.weight(1f))
                InfoTile("30 dni", "$totalSuccess30/$totalMarked30", BadColor, Modifier.weight(1f))
            }
        }

        item {
            ThirtyDayChart(habits = habits, statuses = statuses, dates = last30)
        }

        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Skuteczność nawyków", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (topHabits.isEmpty()) {
                        Text("Brak danych.")
                    } else {
                        topHabits.forEachIndexed { index, habit ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(habit.name)
                                    Text(
                                        "Cel: ${weekSuccessCount(habit.id, statuses, today)}/${habit.weeklyTarget} w tym tygodniu",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.LightGray
                                    )
                                }
                                Text("${successPercent(habit.id, statuses)}%", fontWeight = FontWeight.Bold)
                            }
                            if (index != topHabits.lastIndex) Divider(modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThirtyDayChart(
    habits: List<Habit>,
    statuses: Map<StatusKey, DailyStatus>,
    dates: List<LocalDate>
) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Trend 30 dni", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Wysokość słupka = procent sukcesów danego dnia", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)

            LazyRow(
                modifier = Modifier.fillMaxWidth().height(130.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                items(count = dates.size) { index ->
                    val date = dates[index]
                    val marked = habits.count { statuses[StatusKey(it.id, date.toString())] != null }
                    val success = habits.count { statuses[StatusKey(it.id, date.toString())] == DailyStatus.SUCCESS }
                    val rate = if (marked == 0) 0f else success.toFloat() / marked.toFloat()
                    val barHeight = max(5, (rate * 92f).roundToInt())

                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(
                            modifier = Modifier
                                .width(8.dp)
                                .height(barHeight.dp)
                                .background(
                                    when {
                                        marked == 0 -> NeutralColor
                                        rate >= 0.8f -> GoodColor
                                        rate >= 0.4f -> AccentColor
                                        else -> BadColor
                                    },
                                    RoundedCornerShape(99.dp)
                                )
                        )
                        Spacer(Modifier.height(6.dp))
                        if (index % 5 == 0 || index == dates.lastIndex) {
                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                        } else {
                            Spacer(Modifier.height(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarScreen(
    habits: List<Habit>,
    statuses: Map<StatusKey, DailyStatus>,
    today: LocalDate,
    onSetStatus: (Habit, LocalDate, DailyStatus?) -> Unit
) {
    var currentMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf(today) }

    val firstOfMonth = currentMonth.atDay(1)
    val shift = firstOfMonth.dayOfWeek.value - 1
    val daysInMonth = currentMonth.lengthOfMonth()
    val cells = buildList<LocalDate?> {
        repeat(shift) { add(null) }
        for (day in 1..daysInMonth) add(currentMonth.atDay(day))
        while (size % 7 != 0) add(null)
    }
    val rows = cells.chunked(7)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) { Text("←") }
                        Text(monthName(currentMonth), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) { Text("→") }
                    }

                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd").forEach { day ->
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text(day, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    rows.forEach { week ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            week.forEach { date ->
                                Box(modifier = Modifier.weight(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                                    if (date != null) {
                                        val marked = habits.count { statuses[StatusKey(it.id, date.toString())] != null }
                                        val success = habits.count { statuses[StatusKey(it.id, date.toString())] == DailyStatus.SUCCESS }
                                        val bg = when {
                                            marked == 0 -> MaterialTheme.colorScheme.surfaceVariant
                                            success == marked -> GoodColor.copy(alpha = 0.85f)
                                            success == 0 -> BadColor.copy(alpha = 0.85f)
                                            else -> AccentColor.copy(alpha = 0.85f)
                                        }
                                        val borderLike = selectedDate == date
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .background(if (borderLike) Color.White.copy(alpha = 0.23f) else bg, RoundedCornerShape(12.dp))
                                                .clickable { selectedDate = date },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(date.dayOfMonth.toString(), fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    } else {
                                        Spacer(Modifier.size(38.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("Edytuj: $selectedDate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (habits.isEmpty()) {
            item { EmptyState(false, "Dodaj nawyk w zakładce Nawyki") }
        } else {
            items(count = habits.size, key = { index -> habits[index].id }) { index ->
                val habit = habits[index]
                HistoricalStatusCard(
                    habit = habit,
                    date = selectedDate,
                    status = statuses[StatusKey(habit.id, selectedDate.toString())],
                    onSetStatus = { onSetStatus(habit, selectedDate, it) }
                )
            }
        }
    }
}

@Composable
private fun HistoricalStatusCard(
    habit: Habit,
    date: LocalDate,
    status: DailyStatus?,
    onSetStatus: (DailyStatus?) -> Unit
) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(habit.name, fontWeight = FontWeight.Bold)
            Text(
                if (habit.type == HabitType.GOOD) "Czy wykonano $date?" else "Czy udało się uniknąć $date?",
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusButton(
                    selected = status == DailyStatus.SUCCESS,
                    text = "✓",
                    fillColor = GoodColor,
                    modifier = Modifier.weight(1f),
                    onClick = { onSetStatus(if (status == DailyStatus.SUCCESS) null else DailyStatus.SUCCESS) }
                )
                StatusButton(
                    selected = status == DailyStatus.FAILURE,
                    text = "✕",
                    fillColor = BadColor,
                    modifier = Modifier.weight(1f),
                    onClick = { onSetStatus(if (status == DailyStatus.FAILURE) null else DailyStatus.FAILURE) }
                )
                OutlinedButton(onClick = { onSetStatus(null) }, modifier = Modifier.weight(1f)) {
                    Text("Wyczyść")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    reminderSettings: ReminderSettings,
    backupMessage: String?,
    onReminderChange: (ReminderSettings) -> Unit,
    onPickTime: () -> Unit,
    onRequestNotifications: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Codzienne przypomnienie", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Włącz przypomnienia")
                            Text("Aplikacja przypomni Ci o wpisaniu postępu.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                        }
                        Switch(
                            checked = reminderSettings.enabled,
                            onCheckedChange = { onReminderChange(reminderSettings.copy(enabled = it)) }
                        )
                    }
                    Divider()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Godzina przypomnienia")
                            Text(formatTime(reminderSettings.hour, reminderSettings.minute), color = AccentColor, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = onPickTime) { Text("Zmień") }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Divider()
                        Button(onClick = onRequestNotifications) { Text("Pozwól na powiadomienia") }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Backup danych", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Eksport zapisuje nawyki, historię, cele tygodniowe i ustawienia przypomnień do pliku JSON.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                    Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Eksportuj backup") }
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Wczytaj backup") }
                    if (backupMessage != null) {
                        Text(backupMessage, color = GoodColor)
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Nowe w wersji 3.0", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FeatureTag("Cele tygodniowe 1–7")
                    FeatureTag("Edycja wcześniejszych dni")
                    FeatureTag("Procent skuteczności")
                    FeatureTag("Wykres trendu 30 dni")
                    FeatureTag("Backup i przywracanie danych")
                }
            }
        }
    }
}

@Composable
private fun FeatureTag(text: String) {
    AssistChip(onClick = {}, label = { Text(text) })
}

@Composable
private fun AddOrEditHabitDialog(
    initialHabit: Habit?,
    onDismiss: () -> Unit,
    onSave: (String, HabitType, Int) -> Unit
) {
    var name by remember(initialHabit) { mutableStateOf(initialHabit?.name ?: "") }
    var type by remember(initialHabit) { mutableStateOf(initialHabit?.type ?: HabitType.GOOD) }
    var weeklyTarget by remember(initialHabit) { mutableStateOf(initialHabit?.weeklyTarget ?: 7) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialHabit == null) "Nowy nawyk" else "Edytuj nawyk") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nazwa, np. 20 min spaceru") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (name.isNotBlank()) onSave(name, type, weeklyTarget) }
                    )
                )

                Text("Rodzaj")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = type == HabitType.GOOD, onClick = { type = HabitType.GOOD })
                    Text("Dobry")
                    Spacer(Modifier.width(18.dp))
                    RadioButton(selected = type == HabitType.BAD, onClick = { type = HabitType.BAD })
                    Text("Zły")
                }

                Divider()
                Text("Cel tygodniowy", fontWeight = FontWeight.Bold)
                Text("Ile dni w tygodniu chcesz zaliczyć ten nawyk?", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedButton(onClick = { weeklyTarget = (weeklyTarget - 1).coerceAtLeast(1) }) { Text("−") }
                    Text("$weeklyTarget / 7", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { weeklyTarget = (weeklyTarget + 1).coerceAtMost(7) }) { Text("+") }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, type, weeklyTarget) }, enabled = name.isNotBlank()) {
                Text(if (initialHabit == null) "Dodaj" else "Zapisz")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

private fun currentStreak(habitId: Long, statuses: Map<StatusKey, DailyStatus>, today: LocalDate): Int {
    var date = today
    if (statuses[StatusKey(habitId, date.toString())] == null) date = date.minusDays(1)
    var streak = 0
    while (statuses[StatusKey(habitId, date.toString())] == DailyStatus.SUCCESS) {
        streak++
        date = date.minusDays(1)
    }
    return streak
}

private fun successPercent(habitId: Long, statuses: Map<StatusKey, DailyStatus>): Int {
    val entries = statuses.filterKeys { it.habitId == habitId }.values
    if (entries.isEmpty()) return 0
    val success = entries.count { it == DailyStatus.SUCCESS }
    return (success * 100f / entries.size).roundToInt()
}

private fun weekSuccessCount(habitId: Long, statuses: Map<StatusKey, DailyStatus>, today: LocalDate): Int {
    val monday = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    return (0..6).count { offset ->
        statuses[StatusKey(habitId, monday.plusDays(offset.toLong()).toString())] == DailyStatus.SUCCESS
    }
}

private fun monthName(month: YearMonth): String {
    val monthLabel = month.month.getDisplayName(TextStyle.FULL, Locale("pl")).replaceFirstChar { it.uppercase() }
    return "$monthLabel ${month.year}"
}

private fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

private class HabitRepository(context: Context) {
    private val prefs = context.getSharedPreferences("nawyki_data", Context.MODE_PRIVATE)

    fun loadHabits(): List<Habit> = runCatching {
        habitsFromArray(JSONArray(prefs.getString("habits", "[]") ?: "[]"))
    }.getOrDefault(emptyList())

    fun saveHabits(habits: List<Habit>) {
        prefs.edit().putString("habits", habitsToArray(habits).toString()).apply()
    }

    fun loadStatuses(): Map<StatusKey, DailyStatus> = runCatching {
        statusesFromArray(JSONArray(prefs.getString("statuses", "[]") ?: "[]"))
    }.getOrDefault(emptyMap())

    fun saveStatuses(statuses: Map<StatusKey, DailyStatus>) {
        prefs.edit().putString("statuses", statusesToArray(statuses).toString()).apply()
    }

    fun loadReminderSettings(): ReminderSettings = runCatching {
        val raw = prefs.getString("reminder", null) ?: return ReminderSettings()
        reminderFromObject(JSONObject(raw))
    }.getOrDefault(ReminderSettings())

    fun saveReminderSettings(settings: ReminderSettings) {
        prefs.edit().putString("reminder", reminderToObject(settings).toString()).apply()
    }

    fun createBackup(
        habits: List<Habit>,
        statuses: Map<StatusKey, DailyStatus>,
        reminder: ReminderSettings
    ): String {
        return JSONObject()
            .put("backupVersion", 3)
            .put("createdAt", LocalDate.now().toString())
            .put("habits", habitsToArray(habits))
            .put("statuses", statusesToArray(statuses))
            .put("reminder", reminderToObject(reminder))
            .toString(2)
    }

    fun parseBackup(raw: String): BackupData? = runCatching {
        val obj = JSONObject(raw)
        BackupData(
            habits = habitsFromArray(obj.optJSONArray("habits") ?: JSONArray()),
            statuses = statusesFromArray(obj.optJSONArray("statuses") ?: JSONArray()),
            reminder = obj.optJSONObject("reminder")?.let { reminderFromObject(it) } ?: ReminderSettings()
        )
    }.getOrNull()

    private fun habitsToArray(habits: List<Habit>): JSONArray {
        val array = JSONArray()
        habits.forEach { habit ->
            array.put(
                JSONObject()
                    .put("id", habit.id)
                    .put("name", habit.name)
                    .put("type", habit.type.name)
                    .put("createdAt", habit.createdAt)
                    .put("weeklyTarget", habit.weeklyTarget)
            )
        }
        return array
    }

    private fun habitsFromArray(array: JSONArray): List<Habit> = buildList {
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            add(
                Habit(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    type = HabitType.valueOf(obj.getString("type")),
                    createdAt = obj.optString("createdAt", LocalDate.now().toString()),
                    weeklyTarget = obj.optInt("weeklyTarget", 7).coerceIn(1, 7)
                )
            )
        }
    }

    private fun statusesToArray(statuses: Map<StatusKey, DailyStatus>): JSONArray {
        val array = JSONArray()
        statuses.forEach { (key, status) ->
            array.put(
                JSONObject()
                    .put("habitId", key.habitId)
                    .put("date", key.date)
                    .put("status", status.name)
            )
        }
        return array
    }

    private fun statusesFromArray(array: JSONArray): Map<StatusKey, DailyStatus> = buildMap {
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            put(
                StatusKey(
                    habitId = obj.getLong("habitId"),
                    date = obj.getString("date")
                ),
                DailyStatus.valueOf(obj.getString("status"))
            )
        }
    }

    private fun reminderToObject(settings: ReminderSettings): JSONObject = JSONObject()
        .put("enabled", settings.enabled)
        .put("hour", settings.hour)
        .put("minute", settings.minute)

    private fun reminderFromObject(obj: JSONObject): ReminderSettings = ReminderSettings(
        enabled = obj.optBoolean("enabled", false),
        hour = obj.optInt("hour", 20),
        minute = obj.optInt("minute", 0)
    )
}
