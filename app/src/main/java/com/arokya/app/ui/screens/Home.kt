package com.arokya.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.data.Store
import com.arokya.app.engine.RuleEngine
import com.arokya.app.ui.theme.Ar
import java.util.Calendar

@Composable
fun HomeScreen(
    onTalk: () -> Unit,
    onScan: () -> Unit,
    onPantry: () -> Unit,
    onActivity: () -> Unit,
    onMeals: () -> Unit,
    onWhyThis: () -> Unit,
    onProfile: () -> Unit,
    onReminders: () -> Unit,
    onDiary: () -> Unit,
) {
    val steps by remember { Store.stepsToday }
    val streak by remember { Store.streakDays }

    val pick = RuleEngine.recommend(Store.buildContext())

    Column(
        Modifier.fillMaxSize().background(Ar.Cream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        // ---- Greeting + avatar ----
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    greeting(),
                    fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = Ar.Navy, lineHeight = 30.sp
                )
                val name = Store.profile.name.trim()
                if (name.isNotEmpty()) {
                    Text(
                        name,
                        fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        color = Ar.Teal, lineHeight = 30.sp
                    )
                }
            }
            Box(
                Modifier
                    .size(42.dp)
                    .background(Ar.PurpleChipBg, CircleShape)
                    .clickable { onProfile() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Person, contentDescription = "Profile",
                    tint = Ar.Purple, modifier = Modifier.size(22.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Stats row: steps · meals · streak ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                value = "%,d".format(steps),
                label = "steps",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                value = "${Store.meals.size}",
                label = "meals logged",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                value = "$streak",
                label = "day streak",
                modifier = Modifier.weight(1f),
                flame = true
            )
        }

        Spacer(Modifier.height(18.dp))

        // ---- HERO 1: Talk to Arokya ----
        Row(
            Modifier
                .fillMaxWidth()
                .background(Ar.Teal, RoundedCornerShape(16.dp))
                .clickable { onTalk() }
                .padding(vertical = 17.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Text("Talk to Arokya", fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, color = Color.White)
        }

        Spacer(Modifier.height(11.dp))

        // ---- HERO 2: Analyze your food ----
        Row(
            Modifier
                .fillMaxWidth()
                .background(Ar.CardBg, RoundedCornerShape(16.dp))
                .border(1.5.dp, Ar.Teal, RoundedCornerShape(16.dp))
                .clickable { onScan() }
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null,
                tint = Ar.Teal, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
            Text("Analyze your food", fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, color = Ar.Teal)
        }

        Spacer(Modifier.height(18.dp))

        // ---- Today's top pick ----
        ArCard {
            Text(
                "TODAY'S TOP PICK",
                fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = Ar.Orange, letterSpacing = 1.2.sp,
                modifier = Modifier
                    .background(Ar.OrangeChipBg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(pick.title, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, color = Ar.Navy, lineHeight = 23.sp)
            Spacer(Modifier.height(5.dp))
            Text(pick.message, fontSize = 13.sp, color = Ar.Slate, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "See the meal \u2192",
                    fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ar.Teal,
                    modifier = Modifier.clickable {
                        Store.lastRecommendation.value = pick
                        onScan()
                    }
                )
                Text(
                    "Why this?",
                    fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = Ar.Purple,
                    modifier = Modifier.clickable {
                        Store.lastRecommendation.value = pick
                        onWhyThis()
                    }
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---- Your day so far: eating rhythm + the engine's lever ----
        // The reflective element of the page. Everything above it launches
        // something; this is the one card that ANALYZES — meals on a clock,
        // and the exact distance to tonight's plan changing (PlanLever).
        DayTimelineCard()

        Spacer(Modifier.height(18.dp))

        // ---- Quick links, one tidy row ----
        Text("QUICK LINKS", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            color = Ar.Muted, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(9.dp))
        // Scrollable: meal names come from the model and can be long, which
        // used to squeeze the last chip into a one-letter-per-line column.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Store.meals.lastOrNull()?.let { RecentChip(it.name) { onMeals() } }
            RecentChip("Fridge scan") { onScan() }
            RecentChip("Check-in") {
                Store.lastRecommendation.value = pick
                onWhyThis()
            }
            RecentChip("Pantry") { onPantry() }
            RecentChip("Activity") { onActivity() }
            RecentChip("Reminders") { onReminders() }
            RecentChip("Meal diary") { onDiary() }
        }

        // Demo controls (step slider + manual checkpoint) moved to the Activity
        // tab, where steps already live — Home stays a clean, real product page.
        Spacer(Modifier.height(28.dp))
    }
}

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 12 -> "Good morning,"
        h < 17 -> "Good afternoon,"
        else -> "Good evening,"
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier, flame: Boolean = false) {
    Column(
        modifier
            .background(Ar.CardBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (flame) {
                Icon(Icons.Filled.LocalFireDepartment, contentDescription = null,
                    tint = Ar.Orange, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ar.Navy)
        }
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = Ar.Muted)
    }
}

@Composable
private fun RecentChip(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .background(Ar.CardBg, RoundedCornerShape(999.dp))
            .border(1.dp, Ar.Navy.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = Ar.Navy,
            // A long model-written dish name stays on one line and truncates
            // rather than reflowing the chip.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 190.dp),
        )
    }
}