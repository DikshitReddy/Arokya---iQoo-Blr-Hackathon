package com.arokya.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.data.Meal
import com.arokya.app.data.Store
import com.arokya.app.engine.NutritionTargets
import com.arokya.app.engine.RuleEngine
import com.arokya.app.ui.theme.Ar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Meals tab — everything food-related in one place. Whatever gets logged
 * from a meal scan or a fridge-to-meal recipe lands here.
 *
 * Every number on this screen is deterministic: totals are summed from the
 * meals actually logged, and targets come from [NutritionTargets] (a published
 * equation, not a model). Nothing here is estimated by AI at display time.
 */
@Composable
fun MealPlanScreen(
    onScanMeal: () -> Unit,
    onVideoPicked: (Uri) -> Unit,
    onFridgeToMeal: () -> Unit,
    onWhyThis: () -> Unit,
) {
    val meals = Store.meals
    val targets = remember(
        Store.profile.weightKg, Store.profile.heightCm, Store.profile.age,
        Store.profile.sex, Store.profile.goal, Store.profile.stepBaseline,
    ) { NutritionTargets.forProfile(Store.profile) }

    val kcal = meals.sumOf { it.kcal }
    val protein = meals.sumOf { it.proteinG }
    val carbs = meals.sumOf { it.carbsG }
    val fat = meals.sumOf { it.fatG }

    val pick = RuleEngine.recommend(Store.buildContext())

    Column(
        Modifier.fillMaxSize().background(Ar.Cream)
            .verticalScroll(rememberScrollState())
    ) {
        // ---- Title ----
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Your meals", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ar.Navy)
            Text(
                remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()) },
                fontSize = 13.sp, color = Ar.Muted,
            )
        }

        Column(Modifier.padding(horizontal = 20.dp)) {

            // ---- HERO: today's energy + macro budget ----
            ArCard {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CalorieRing(consumed = kcal, target = targets.kcal)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        MacroBar("Protein", protein, targets.proteinG, Ar.Teal)
                        Spacer(Modifier.height(12.dp))
                        MacroBar("Carbs", carbs, targets.carbsG, Ar.Amber)
                        Spacer(Modifier.height(12.dp))
                        MacroBar("Fat", fat, targets.fatG, Ar.Orange)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    targets.basis,
                    fontSize = 11.sp, color = Ar.Muted, lineHeight = 16.sp,
                )
                if (targets.personalized) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A general-population estimate, not medical advice.",
                        fontSize = 11.sp, color = Ar.Muted,
                    )
                }
            }

            // ---- Today's pick, straight from the rule engine ----
            Spacer(Modifier.height(12.dp))
            ArCard {
                Text(
                    "TODAY'S PICK",
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = Ar.Orange, letterSpacing = 1.2.sp,
                    modifier = Modifier
                        .background(Ar.OrangeChipBg, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(pick.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = Ar.Navy, lineHeight = 23.sp)
                Spacer(Modifier.height(5.dp))
                Text(pick.message, fontSize = 13.sp, color = Ar.Slate, lineHeight = 19.sp)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Build it from my fridge →",
                        fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ar.Teal,
                        modifier = Modifier.clickable {
                            Store.lastRecommendation.value = pick
                            onFridgeToMeal()
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

            // ---- Quick add ----
            // The reel-to-recipe pipeline still works via the system share
            // sheet (share a video/Instagram link to Arokya — see the manifest
            // SEND intent-filters and MainActivity), so no in-app button here.
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(
                    icon = Icons.Filled.CameraAlt,
                    label = "Scan a meal",
                    sub = "Get its macros",
                    modifier = Modifier.weight(1f),
                    onClick = onScanMeal,
                )
                QuickAction(
                    icon = Icons.Filled.Kitchen,
                    label = "From my fridge",
                    sub = "Cook something",
                    modifier = Modifier.weight(1f),
                    onClick = onFridgeToMeal,
                )
            }

            // ---- Logged meals ----
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("LOGGED TODAY", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                    color = Ar.Muted, letterSpacing = 1.2.sp)
                Text(
                    if (meals.isEmpty()) "—" else "${meals.size} meal${if (meals.size == 1) "" else "s"}",
                    fontSize = 11.5.sp, color = Ar.Muted,
                )
            }
            Spacer(Modifier.height(10.dp))

            if (meals.isEmpty()) {
                ArCard {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(52.dp).background(Ar.TealChipBg, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.RestaurantMenu, null, tint = Ar.Teal,
                                modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Nothing logged yet today", fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, color = Ar.Navy)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Scan a plate to log what you ate, or build a recipe from what's in your fridge.",
                            fontSize = 12.5.sp, color = Ar.Slate, lineHeight = 18.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                meals.forEachIndexed { index, meal ->
                    MealRowCard(meal, isLast = index == meals.lastIndex)
                }
                Spacer(Modifier.height(10.dp))
                ArCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Day total", fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, color = Ar.Navy)
                        Text(
                            "$kcal kcal · ${protein}P / ${carbs}C / ${fat}F",
                            fontSize = 13.sp, color = Ar.Slate,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Big energy ring — consumed vs the day's budget, with what's left in the middle. */
@Composable
private fun CalorieRing(consumed: Int, target: Int) {
    val fraction = if (target > 0) consumed.toFloat() / target else 0f
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(700),
        label = "calorieRing",
    )
    val over = consumed > target
    val ringColor = if (over) Ar.Orange else Ar.Teal
    val remaining = target - consumed

    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 13.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Ar.Cream,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (animated > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f, sweepAngle = 360f * animated, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (over) "+${-remaining}" else "$remaining",
                fontSize = 27.sp, fontWeight = FontWeight.Bold,
                color = if (over) Ar.Orange else Ar.Navy,
            )
            Text(
                if (over) "kcal over" else "kcal left",
                fontSize = 11.sp, color = Ar.Muted,
            )
            Spacer(Modifier.height(3.dp))
            Text("$consumed / $target", fontSize = 10.5.sp, color = Ar.Muted)
        }
    }
}

/** One macro's progress toward its share of the day. */
@Composable
private fun MacroBar(label: String, consumed: Int, target: Int, color: Color) {
    val fraction = if (target > 0) (consumed.toFloat() / target) else 0f
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(700),
        label = "macro$label",
    )
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ar.Navy)
            Text("$consumed / $target g", fontSize = 11.sp, color = Ar.Muted)
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .background(Ar.Cream, RoundedCornerShape(999.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .height(7.dp)
                    .background(color, RoundedCornerShape(999.dp))
            )
        }
    }
}

@Composable
private fun MealRowCard(meal: Meal, isLast: Boolean) {
    ArCard(Modifier.padding(bottom = if (isLast) 0.dp else 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(meal.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
                Spacer(Modifier.height(2.dp))
                Text(meal.timeLabel, fontSize = 11.5.sp, color = Ar.Muted)
            }
            Text(
                "${meal.kcal} kcal",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ar.Blue,
                modifier = Modifier
                    .background(Ar.BlueChipBg, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MacroPill("${meal.proteinG}g protein", Ar.Teal)
            MacroPill("${meal.carbsG}g carbs", Ar.Amber)
            MacroPill("${meal.fatG}g fat", Ar.Orange)
        }
    }
}

@Composable
private fun MacroPill(text: String, color: Color) {
    Text(
        text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sub: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .background(Ar.CardBg, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Box(
            Modifier.size(34.dp).background(Ar.TealChipBg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Ar.Teal, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
        Text(sub, fontSize = 11.sp, color = Ar.Muted)
    }
}
