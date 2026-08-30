package com.arokya.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.data.Meal
import com.arokya.app.data.Store
import com.arokya.app.engine.MealSlot
import com.arokya.app.engine.PlanLever
import com.arokya.app.ui.theme.Ar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "YOUR DAY SO FAR" — today's meals plotted on a clock axis.
 *
 * This is the only place in the app that shows WHEN the user eats, which is
 * exactly the dimension the rule engine reasons about (R1 evening, R2
 * afternoon) but never surfaces. Three deterministic facts, zero model calls:
 *
 *  1. the eating rhythm — meal dots sized by calories, gaps made visible;
 *  2. where the day stands — a "now" marker inside the upcoming meal window;
 *  3. THE LEVER — [PlanLever]'s exact distance to the nearest rule flipping,
 *     e.g. "1,300 more steps and tonight's plan changes". Computable only
 *     because the engine is thresholds, not vibes — the on-Home proof of
 *     "the engine decides".
 *
 * Reads [Store.stepsToday] live, so the demo slider moves the lever text in
 * real time — that interaction IS the demo of rule transparency.
 */
@Composable
fun DayTimelineCard() {
    val meals = Store.meals
    @Suppress("UNUSED_EXPRESSION")
    Store.stepsToday.intValue // read so slider changes recompose the lever

    val now = Calendar.getInstance()
    val nowFrac = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f
    val slot = MealSlot.forHour(now.get(Calendar.HOUR_OF_DAY))
    val points = meals.mapNotNull { m -> mealHourOfDay(m)?.let { it to m.kcal } }
    val lever = PlanLever.forContext(Store.buildContext())
    val kcalToday = meals.sumOf { it.kcal }

    val textMeasurer = rememberTextMeasurer()
    val tickStyle = TextStyle(fontSize = 9.sp, color = Ar.Muted)

    ArCard {
        // ---- header ----
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "YOUR DAY SO FAR",
                fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = Ar.Teal, letterSpacing = 1.2.sp,
                modifier = Modifier
                    .background(Ar.TealChipBg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.weight(1f))
            Text("%,d kcal eaten".format(kcalToday), fontSize = 11.5.sp, color = Ar.Muted)
        }

        Spacer(Modifier.height(14.dp))

        // ---- the clock axis ----
        // Starts at 5 AM normally, but stretches back for early loggers (a
        // 2:40 AM snack must not fall off the chart) and always contains "now".
        val axisStart = floor(min(5f, min(nowFrac, points.minOfOrNull { it.first } ?: 24f)))
        val axisEnd = 23.5f
        fun frac(hour: Float) = ((hour - axisStart) / (axisEnd - axisStart)).coerceIn(0f, 1f)

        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val w = size.width
            val trackY = size.height * 0.40f

            // Upcoming meal window, so "now" sits visibly inside its context.
            slotHourRange(slot)?.let { (s, e) ->
                val x1 = frac(s) * w
                val x2 = frac(e) * w
                drawRoundRect(
                    color = Ar.Teal.copy(alpha = 0.10f),
                    topLeft = Offset(x1, trackY - 15.dp.toPx()),
                    size = Size(x2 - x1, 30.dp.toPx()),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
            }

            // Base track.
            drawLine(
                Ar.Navy.copy(alpha = 0.10f),
                Offset(0f, trackY), Offset(w, trackY),
                3.dp.toPx(), StrokeCap.Round,
            )

            // Hour ticks. Sparse on purpose — this is a rhythm, not a chart.
            val ticks = listOf(axisStart, 12f, 18f, 23f)
                .filter { it in axisStart..axisEnd }.distinct()
            ticks.forEach { h ->
                val x = frac(h) * w
                drawLine(
                    Ar.Navy.copy(alpha = 0.15f),
                    Offset(x, trackY + 6.dp.toPx()), Offset(x, trackY + 10.dp.toPx()),
                    1.5.dp.toPx(), StrokeCap.Round,
                )
                val layout = textMeasurer.measure(AnnotatedString(hourLabel(h.roundToInt())), tickStyle)
                drawText(
                    layout,
                    topLeft = Offset(
                        (x - layout.size.width / 2f).coerceIn(0f, w - layout.size.width),
                        trackY + 12.dp.toPx(),
                    ),
                )
            }

            // Meal dots, radius scaled by calories — a heavy lunch reads
            // heavier at a glance.
            points.forEach { (hour, kcal) ->
                val r = 4.dp.toPx() + (kcal / 800f).coerceIn(0f, 1f) * 5.dp.toPx()
                drawCircle(Ar.Teal, radius = r, center = Offset(frac(hour) * w, trackY))
            }

            // "Now" marker.
            val nowX = frac(nowFrac) * w
            drawLine(
                Ar.Navy,
                Offset(nowX, trackY - 16.dp.toPx()), Offset(nowX, trackY + 8.dp.toPx()),
                2.dp.toPx(), StrokeCap.Round,
            )
            drawCircle(Ar.Navy, radius = 3.dp.toPx(), center = Offset(nowX, trackY - 16.dp.toPx()))
        }

        Spacer(Modifier.height(8.dp))

        // ---- gap + next window ----
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Schedule, null, tint = Ar.Muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                gapText(points.maxOfOrNull { it.first }, nowFrac),
                fontSize = 12.sp, color = Ar.Slate,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Next: ${slot.label}",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ar.Teal,
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- THE LEVER: what would change the engine's mind ----
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (lever.emphasis) Ar.OrangeChipBg else Ar.TealChipBg,
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bolt, null,
                tint = if (lever.emphasis) Ar.Orange else Ar.Teal,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(lever.text, fontSize = 12.sp, color = Ar.Navy, lineHeight = 17.sp)
        }

        // ---- active lab constraint, when one exists ----
        Store.profile.labFindings.firstOrNull { it.flag != "Normal" }?.let { f ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Science, null, tint = Ar.Purple,
                    modifier = Modifier.padding(top = 2.dp).size(13.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "${f.marker} ${f.flag.lowercase()} — ${f.dietaryNote}",
                    fontSize = 11.5.sp, color = Ar.Muted, lineHeight = 16.sp, maxLines = 2,
                )
            }
        }
    }
}

/**
 * Where a meal sits on the clock. The displayed [Meal.timeLabel] is the source
 * of truth (it's what the user sees on the Meals tab); epochMillis is the
 * fallback for labels that don't parse — and is also all that migrated rows
 * have, since their labels predate the timestamp column.
 */
private fun mealHourOfDay(meal: Meal): Float? {
    for (locale in listOf(Locale.getDefault(), Locale.ENGLISH)) {
        try {
            val parsed = SimpleDateFormat("h:mm a", locale).parse(meal.timeLabel) ?: continue
            val cal = Calendar.getInstance().apply { time = parsed }
            return cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        } catch (_: Exception) { /* try the next locale */ }
    }
    if (meal.epochMillis <= 0L) return null
    val cal = Calendar.getInstance().apply { timeInMillis = meal.epochMillis }
    return cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
}

/** The hour band a slot occupies on the axis — mirrors [MealSlot.forHour]. */
private fun slotHourRange(slot: MealSlot): Pair<Float, Float>? = when (slot) {
    MealSlot.Breakfast -> 5f to 11f
    MealSlot.Lunch -> 11f to 16f
    MealSlot.Snack -> 16f to 19f
    MealSlot.Dinner -> 19f to 23.5f
    MealSlot.LateNight -> null // wraps midnight; a band would be misleading
}

private fun hourLabel(hour: Int): String = when {
    hour == 0 || hour == 24 -> "12 AM"
    hour < 12 -> "$hour AM"
    hour == 12 -> "12 PM"
    else -> "${hour - 12} PM"
}

private fun gapText(lastMealHour: Float?, nowFrac: Float): String {
    if (lastMealHour == null) return "Nothing logged yet — your first meal starts the timeline"
    val minutes = ((nowFrac - lastMealHour) * 60).roundToInt()
    return when {
        minutes < 1 -> "Last meal just now"
        minutes < 60 -> "Last meal $minutes min ago"
        else -> "Last meal ${minutes / 60}h ${minutes % 60}m ago"
    }
}
