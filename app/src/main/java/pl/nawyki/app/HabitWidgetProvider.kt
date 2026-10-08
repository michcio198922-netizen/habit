package pl.nawyki.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class HabitWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_SUCCESS) {
            val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L)
            if (habitId >= 0) {
                toggleTodaySuccess(context, habitId)
                refresh(context)
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_TOGGLE_SUCCESS = "pl.nawyki.app.ACTION_WIDGET_TOGGLE_SUCCESS"
        private const val EXTRA_HABIT_ID = "habit_id"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, HabitWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { id -> manager.updateAppWidget(id, buildViews(context)) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_habits)
            val prefs = context.getSharedPreferences("nawyki_data", Context.MODE_PRIVATE)
            val today = LocalDate.now()
            val todayKey = today.toString()
            val habits = loadHabitsForToday(prefs.getString("habits", "[]") ?: "[]", today.dayOfWeek.value)
            val statuses = prefs.getString("statuses", "[]") ?: "[]"

            val openAppIntent = Intent(context, MainActivity::class.java)
            val openPending = PendingIntent.getActivity(
                context,
                9000,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPending)

            val rows = listOf(
                Triple(R.id.widget_row_1, R.id.widget_name_1, R.id.widget_button_1),
                Triple(R.id.widget_row_2, R.id.widget_name_2, R.id.widget_button_2),
                Triple(R.id.widget_row_3, R.id.widget_name_3, R.id.widget_button_3)
            )
            val statusIds = listOf(R.id.widget_status_1, R.id.widget_status_2, R.id.widget_status_3)

            rows.forEachIndexed { index, (rowId, nameId, buttonId) ->
                val habit = habits.getOrNull(index)
                if (habit == null) {
                    views.setViewVisibility(rowId, View.GONE)
                } else {
                    views.setViewVisibility(rowId, View.VISIBLE)
                    views.setTextViewText(nameId, habit.name)
                    val success = statusFor(statuses, habit.id, todayKey) == "SUCCESS"
                    views.setTextViewText(statusIds[index], if (success) "Zaliczone" else "Do zrobienia")
                    views.setTextViewText(buttonId, if (success) "↶" else "✓")

                    val actionIntent = Intent(context, HabitWidgetProvider::class.java).apply {
                        action = ACTION_TOGGLE_SUCCESS
                        putExtra(EXTRA_HABIT_ID, habit.id)
                    }
                    val pending = PendingIntent.getBroadcast(
                        context,
                        (habit.id % Int.MAX_VALUE).toInt(),
                        actionIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(buttonId, pending)
                }
            }

            views.setTextViewText(
                R.id.widget_empty,
                if (habits.isEmpty()) "Brak zaplanowanych nawyków na dziś" else ""
            )
            views.setViewVisibility(R.id.widget_empty, if (habits.isEmpty()) View.VISIBLE else View.GONE)
            return views
        }

        private data class WidgetHabit(val id: Long, val name: String)

        private fun loadHabitsForToday(raw: String, weekday: Int): List<WidgetHabit> = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optBoolean("archived", false)) continue
                    val days = obj.optJSONArray("activeDays")
                    val scheduled = if (days == null) {
                        true
                    } else {
                        (0 until days.length()).any { days.optInt(it) == weekday }
                    }
                    if (scheduled) {
                        add(WidgetHabit(obj.getLong("id"), obj.getString("name")))
                    }
                }
            }.take(3)
        }.getOrDefault(emptyList())

        private fun statusFor(raw: String, habitId: Long, date: String): String? = runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optLong("habitId") == habitId && obj.optString("date") == date) {
                    return@runCatching obj.optString("status")
                }
            }
            null
        }.getOrNull()

        private fun toggleTodaySuccess(context: Context, habitId: Long) {
            val prefs = context.getSharedPreferences("nawyki_data", Context.MODE_PRIVATE)
            val today = LocalDate.now().toString()
            val array = runCatching {
                JSONArray(prefs.getString("statuses", "[]") ?: "[]")
            }.getOrElse { JSONArray() }

            var foundIndex = -1
            var foundStatus: String? = null
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optLong("habitId") == habitId && obj.optString("date") == today) {
                    foundIndex = i
                    foundStatus = obj.optString("status")
                    break
                }
            }

            if (foundIndex >= 0 && foundStatus == "SUCCESS") {
                array.remove(foundIndex)
            } else {
                val entry = JSONObject()
                    .put("habitId", habitId)
                    .put("date", today)
                    .put("status", "SUCCESS")
                if (foundIndex >= 0) array.put(foundIndex, entry) else array.put(entry)
            }

            prefs.edit().putString("statuses", array.toString()).apply()
        }
    }
}
