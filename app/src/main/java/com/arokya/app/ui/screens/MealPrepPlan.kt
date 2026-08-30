package com.arokya.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.data.Meal
import com.arokya.app.data.Store
import com.arokya.app.engine.DayBudget
import com.arokya.app.engine.MealSlot
import com.arokya.app.engine.NutritionTargets
import com.arokya.app.ml.InferenceStat
import com.arokya.app.ml.InferenceStats
import com.arokya.app.ml.NextMealPlan
import com.arokya.app.ml.planNextMeal
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.arokya.app.ui.theme.Ar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cosmetic pacing while the single real inference runs. */
private val PLANNING_STEPS = listOf(
    "Reading what you've eaten today…",
    "Working out what's left in your budget…",
    "Checking your kitchen…",
    "Matching it to your goal…",
    "Writing the plan…",
)

/**
 * Where the proactive nudge lands: ONE concrete meal for the slot the user is
 * heading into, sized against what's actually left of their day.
 *
 * Every number in the budget strip is deterministic ([NutritionTargets] minus
 * what's logged). Only the dish itself comes from the model, and a failure
 * shows a real error rather than a generic meal.
 */
@Composable
fun NextMealPlanScreen(
    onBack: () -> Unit,
    onLogged: () -> Unit,
    onScanInstead: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val slot = remember { MealSlot.now() }
    val remaining = remember(Store.meals.size) {
        DayBudget.remaining(NutritionTargets.forProfile(Store.profile), Store.meals.toList())
    }

    var plan by remember { mutableStateOf<NextMealPlan?>(null) }
    var stat by remember { mutableStateOf<InferenceStat?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var stepIndex by remember { mutableIntStateOf(0) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryKey) {
        plan = null; error = null; stepIndex = 0
        val cycle = launch {
            while (isActive) {
                delay(1500)
                if (stepIndex < PLANNING_STEPS.size - 1) stepIndex++
            }
        }
        try {
            plan = planNextMeal(Store.buildContext(), slot, remaining)
            stat = InferenceStats.last
        } catch (e: Exception) {
            error = e.message ?: "Couldn't plan a meal right now."
        } finally {
            cycle.cancel()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ar.Cream).verticalScroll(rememberScrollState())
    ) {
        ArHeader("Your ${slot.label.lowercase()} plan", onBack = onBack)

        Column(Modifier.padding(horizontal = 20.dp)) {

            // ---- What's left of the day. Deterministic, always shown. ----
            ArCard {
                Text(
                    if (remaining.overBudget) "ALREADY OVER FOR TODAY" else "STILL OPEN TODAY",
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = if (remaining.overBudget) Ar.Orange else Ar.Teal,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier
                        .background(
                            if (remaining.overBudget) Ar.OrangeChipBg else Ar.TealChipBg,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArChip(
                        if (remaining.overBudget) "${-remaining.kcal} kcal over"
                        else "${remaining.kcal} kcal left",
                        if (remaining.overBudget) Ar.Orange else Ar.Blue,
                        if (remaining.overBudget) Ar.OrangeChipBg else Ar.BlueChipBg,
                    )
                    ArChip("${remaining.proteinG.coerceAtLeast(0)} g protein left", Ar.Teal, Ar.TealChipBg)
                    ArChip("${remaining.carbsG.coerceAtLeast(0)} g carbs left", Ar.Amber, Ar.AmberChipBg)
                    ArChip("${remaining.fatG.coerceAtLeast(0)} g fat left", Ar.Orange, Ar.OrangeChipBg)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Against ${remaining.targets.kcal} kcal for the day · " +
                            "${Store.meals.size} meal${if (Store.meals.size == 1) "" else "s"} logged.",
                    fontSize = 11.5.sp, color = Ar.Muted, lineHeight = 16.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            when {
                error != null -> ArCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PriorityHigh, null, tint = Ar.Orange,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Couldn't plan that", fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, color = Ar.Navy)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, fontSize = 13.sp, color = Ar.Slate, lineHeight = 19.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Try again", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = Ar.Teal, modifier = Modifier.clickable { retryKey++ }
                    )
                }

                plan == null -> PlanningCard(stepIndex)

                else -> {
                    val p = plan!!
                    ArCard {
                        Text(slot.label.uppercase(), fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold, color = Ar.Purple,
                            letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(p.mealName, fontSize = 21.sp,
                            fontWeight = FontWeight.Bold, color = Ar.Navy, lineHeight = 27.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ArChip("${p.kcal} kcal", Ar.Blue, Ar.BlueChipBg)
                            ArChip("${p.proteinG} g protein", Ar.Teal, Ar.TealChipBg)
                            ArChip("${p.carbsG} g carbs", Ar.Amber, Ar.AmberChipBg)
                            ArChip("${p.fatG} g fat", Ar.Orange, Ar.OrangeChipBg)
                        }
                        if (p.why.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(p.why, fontSize = 14.sp, color = Ar.Slate, lineHeight = 20.sp)
                        }
                    }

                    // Does the proposed dish actually fit what's left? The ENGINE
                    // answers that by subtraction — the model isn't asked to grade
                    // its own homework.
                    Spacer(Modifier.height(10.dp))
                    val fits = !remaining.overBudget && p.kcal <= remaining.kcal
                    ArCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Fits what's left?", fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, color = Ar.Muted)
                            val (fg, bg) = if (fits) Ar.Teal to Ar.TealChipBg
                            else Ar.Orange to Ar.OrangeChipBg
                            Row(
                                Modifier.background(bg, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (fits) Icons.Filled.Check else Icons.Filled.PriorityHigh,
                                    null, tint = fg, modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (fits) "Yes" else "Over",
                                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (fits) "${p.kcal} kcal of the ${remaining.kcal} kcal you have left."
                            else "This would put you " +
                                    "${p.kcal - remaining.kcal.coerceAtLeast(0)} kcal past today's budget.",
                            fontSize = 12.sp, color = Ar.Muted, lineHeight = 17.sp,
                        )
                    }

                    if (p.usesPantry.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        ArCard {
                            Text("USES FROM YOUR KITCHEN", fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold, color = Ar.Muted,
                                letterSpacing = 1.2.sp)
                            Spacer(Modifier.height(8.dp))
                            p.usesPantry.forEach { item ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(item, fontSize = 14.sp, color = Ar.Navy)
                                    Text(
                                        if (Store.pantry.any { it.name.equals(item, true) })
                                            "in pantry" else "need to buy",
                                        fontSize = 11.sp,
                                        color = if (Store.pantry.any { it.name.equals(item, true) })
                                            Ar.Teal else Ar.Amber,
                                    )
                                }
                            }
                        }
                    }

                    if (p.prep.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        ArCard {
                            Text("HOW TO MAKE IT", fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold, color = Ar.Muted,
                                letterSpacing = 1.2.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(p.prep, fontSize = 14.sp, color = Ar.Slate, lineHeight = 21.sp)
                        }
                    }

                    if (stat != null) {
                        Spacer(Modifier.height(12.dp))
                        InferenceChip(stat)
                    }

                    Spacer(Modifier.height(20.dp))
                    LogMealButton(
                        mealName = p.mealName,
                        kcal = p.kcal,
                        proteinG = p.proteinG,
                        carbsG = p.carbsG,
                        fatG = p.fatG,
                        onLogged = onLogged,
                    )
                    Spacer(Modifier.height(10.dp))
                    ArSecondaryButton("Plan something else") { retryKey++ }
                    Spacer(Modifier.height(10.dp))
                    ArSecondaryButton("Scan what I'm eating instead", onClick = onScanInstead)
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PlanningCard(stepIndex: Int) {
    ArCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            val pulse = rememberInfiniteTransition(label = "planPulse")
            val scale by pulse.animateFloat(
                initialValue = 0.92f, targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    animation = tween(950, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "scale",
            )
            Box(
                Modifier.size(76.dp).scale(scale).background(Ar.TealChipBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("🍽️", fontSize = 32.sp) }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Planning your meal…",
            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ar.Navy,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PLANNING_STEPS.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        index < stepIndex -> Icon(Icons.Filled.Check, null, tint = Ar.Teal,
                            modifier = Modifier.size(16.dp))
                        index == stepIndex -> CircularProgressIndicator(
                            color = Ar.Teal, strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        else -> {}
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    step, fontSize = 13.sp,
                    fontWeight = if (index == stepIndex) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index <= stepIndex) Ar.Navy else Ar.Muted,
                )
            }
            if (index != PLANNING_STEPS.lastIndex) Spacer(Modifier.height(10.dp))
        }
    }
}
