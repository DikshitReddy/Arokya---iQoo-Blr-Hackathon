package com.arokya.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.data.Meal
import com.arokya.app.data.Store
import com.arokya.app.ui.theme.Ar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The meal diary — every logged meal grouped by day, newest first.
 *
 * This is the "daily calendar" the meals get stored into: whatever gets logged
 * (scan, reel, plan, or just describing it to the coach) lands here with its
 * day and time, and the coach reads these same rows to spot patterns and
 * suggest accordingly (see Store.recentEatingSummary).
 */
@Composable
fun MealDiaryScreen(onBack: () -> Unit) {
    var history by remember { mutableStateOf<List<Meal>>(emptyList()) }
    LaunchedEffect(Unit) { history = Store.mealHistory() }

    // Group by calendar day, newest day first.
    val byDay = history
        .filter { it.epochMillis > 0 }
        .groupBy { Store.calendarDaysAgo(it.epochMillis) }
        .toSortedMap()

    Column(
        Modifier.fillMaxSize().background(Ar.Cream).verticalScroll(rememberScrollState())
    ) {
        ArHeader("Meal diary", onBack = onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {

            if (byDay.isEmpty()) {
                ArCard {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.RestaurantMenu, null, tint = Ar.Teal,
                            modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("No meals logged yet", fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, color = Ar.Navy)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Scan a plate, plan a meal, or just tell the coach what you " +
                                    "ate — it all shows up here.",
                            fontSize = 12.5.sp, color = Ar.Slate, lineHeight = 18.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            byDay.forEach { (daysAgo, meals) ->
                Spacer(Modifier.height(6.dp))
                DayHeading(daysAgo, meals)
                meals.sortedBy { it.epochMillis }.forEach { DiaryRow(it) }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun DayHeading(daysAgo: Int, meals: List<Meal>) {
    val label = when (daysAgo) {
        0 -> "TODAY"
        1 -> "YESTERDAY"
        else -> meals.firstOrNull()?.epochMillis
            ?.let { SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date(it)).uppercase() }
            ?: "$daysAgo DAYS AGO"
    }
    val kcal = meals.sumOf { it.kcal }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            color = Ar.Muted, letterSpacing = 1.2.sp)
        Text("$kcal kcal · ${meals.size} meal${if (meals.size == 1) "" else "s"}",
            fontSize = 11.sp, color = Ar.Muted)
    }
}

@Composable
private fun DiaryRow(meal: Meal) {
    ArCard(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(meal.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
                Text(meal.timeLabel, fontSize = 11.5.sp, color = Ar.Muted)
            }
            Text(
                "${meal.kcal} kcal",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ar.Blue,
                modifier = Modifier
                    .background(Ar.BlueChipBg, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DiaryPill("${meal.proteinG}g protein", Ar.Teal)
            DiaryPill("${meal.carbsG}g carbs", Ar.Amber)
            DiaryPill("${meal.fatG}g fat", Ar.Orange)
        }
    }
}

@Composable
private fun DiaryPill(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
