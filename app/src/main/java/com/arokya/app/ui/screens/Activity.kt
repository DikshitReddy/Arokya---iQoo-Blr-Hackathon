package com.arokya.app.ui.screens

import android.Manifest
import android.os.Build
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.data.Store
import com.arokya.app.engine.ActivityBurn
import com.arokya.app.engine.BurnSuggestion
import com.arokya.app.engine.EnergyVerdict
import com.arokya.app.engine.NutritionTargets
import com.arokya.app.sensor.StepSensor
import com.arokya.app.ui.theme.Ar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ACTIVITY TAB — movement as an input to the nutrition plan, not a step counter.
 *
 * Three questions, in the order a user actually asks them:
 *   1. "How much have I moved?"      -> live pedometer, distance, burn
 *   2. "Where does that leave me?"   -> energy balance against today's budget
 *   3. "What should I DO about it?"  -> [ActivityBurn]'s MET-based options
 *
 * (3) is the part that makes this more than a tracker. Every duration is
 * `MET × 3.5 × weight / 200` on the user's own body weight — traceable
 * arithmetic, same rule as the nutrition engine: no model invents a number.
 *
 * Steps come from [StepSensor] (real hardware). Nothing here is simulated, and
 * when the sensor can't run the UI says so rather than showing a placeholder.
 */
@Composable
fun ActivityScreen() {
    val context = LocalContext.current

    val steps = Store.stepsToday.intValue
    val baseline = Store.profile.stepBaseline
    val profile = Store.profile
    val hasReading by StepSensor.hasReading
    val sensorAvailable by StepSensor.available

    var permissionGranted by remember { mutableStateOf(StepSensor.hasPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) StepSensor.start(context)
    }
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) StepSensor.start(context)
    }

    val targets = remember(
        profile.weightKg, profile.heightCm, profile.age,
        profile.sex, profile.goal, profile.stepBaseline,
    ) { NutritionTargets.forProfile(profile) }

    val eaten = Store.meals.sumOf { it.kcal }
    val burned = ActivityBurn.kcalFromSteps(steps, profile)
    val distance = ActivityBurn.distanceKm(steps, profile)
    val verdict = ActivityBurn.verdict(eaten, targets.kcal, steps, baseline, profile)

    Column(
        Modifier.fillMaxSize().background(Ar.Cream).verticalScroll(rememberScrollState())
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Activity", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ar.Navy)
            Text(
                remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()) },
                fontSize = 13.sp, color = Ar.Muted,
            )
        }

        Column(Modifier.padding(horizontal = 20.dp)) {

            // ---- 1. Live movement ----
            when {
                !permissionGranted -> PermissionPrompt {
                    if (Build.VERSION.SDK_INT >= 29) {
                        permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    } else {
                        permissionGranted = true
                    }
                }
                !sensorAvailable -> UnavailableCard()
                else -> MovementCard(
                    steps = steps,
                    baseline = baseline,
                    distanceKm = distance,
                    burnedKcal = burned,
                    live = hasReading,
                )
            }

            // ---- 2. Where that leaves the day ----
            Spacer(Modifier.height(12.dp))
            EnergyBalanceCard(eaten = eaten, target = targets.kcal, burned = burned)

            // ---- 3. What to do about it ----
            Spacer(Modifier.height(12.dp))
            BalanceItOutCard(verdict)

            // ---- Method, stated plainly ----
            Spacer(Modifier.height(12.dp))
            ArCard {
                Text(
                    "Durations use the MET equation (MET × 3.5 × your weight ÷ 200), " +
                            "so they're built from your ${profile.weightKg.ifBlank { "—" }} kg, not estimated by a model. " +
                            "General-population figures, not medical advice.",
                    fontSize = 11.5.sp, color = Ar.Muted, lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

// ---------------------------------------------------------------------------
//  1. Movement
// ---------------------------------------------------------------------------

@Composable
private fun MovementCard(
    steps: Int,
    baseline: Int,
    distanceKm: Double,
    burnedKcal: Int,
    live: Boolean,
) {
    val progress = if (baseline > 0) (steps.toFloat() / baseline).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(progress, tween(700), label = "stepRing")
    val onTrack = steps >= baseline

    ArCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Ring, so movement reads the same way the calorie budget does on Meals.
            Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = Ar.Cream, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    if (animated > 0f) {
                        drawArc(
                            color = if (onTrack) Ar.Teal else Ar.Amber,
                            startAngle = -90f, sweepAngle = 360f * animated, useCenter = false,
                            topLeft = Offset(inset, inset), size = arcSize,
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "%,d".format(steps), fontSize = 24.sp,
                        fontWeight = FontWeight.Bold, color = Ar.Navy,
                    )
                    Text("steps", fontSize = 11.sp, color = Ar.Muted)
                }
            }

            Spacer(Modifier.width(18.dp))

            Column(Modifier.weight(1f)) {
                // Live badge — only shown once a real reading has landed.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp)
                            .background(if (live) Ar.Teal else Ar.Muted, CircleShape)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (live) "Live from your phone" else "Waiting for sensor…",
                        fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        color = if (live) Ar.Teal else Ar.Muted,
                    )
                }
                Spacer(Modifier.height(10.dp))
                MetricRow("Distance", "%.2f km".format(distanceKm))
                Spacer(Modifier.height(6.dp))
                MetricRow("Burned", "$burnedKcal kcal")
                Spacer(Modifier.height(6.dp))
                MetricRow("Your usual", "%,d".format(baseline))
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            if (onTrack) "You've passed your usual ${"%,d".format(baseline)} steps."
            else "${"%,d".format(baseline - steps)} short of your usual ${"%,d".format(baseline)}.",
            fontSize = 12.5.sp, color = Ar.Muted,
        )
        // The sensor can only count from when Arokya first read it today.
        StepSensor.countingSince.value?.let { since ->
            Text(
                "Counting since ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(since))} " +
                        "— the phone's pedometer has no record of earlier today.",
                fontSize = 10.5.sp, color = Ar.Muted, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.5.sp, color = Ar.Muted)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    ArCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(Ar.TealChipBg, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.DirectionsWalk, null, tint = Ar.Teal, modifier = Modifier.size(21.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Turn on step tracking", fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, color = Ar.Navy)
                Text(
                    "Arokya reads your phone's built-in pedometer. Nothing leaves the device.",
                    fontSize = 12.sp, color = Ar.Muted, lineHeight = 17.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ArPrimaryButton("Allow activity access", onClick = onGrant)
    }
}

@Composable
private fun UnavailableCard() {
    ArCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Sensors, null, tint = Ar.Orange, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
            Text("No pedometer on this phone", fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, color = Ar.Navy)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "This device has no step-counter sensor, so Arokya can't measure your " +
                    "movement. Everything else on this screen still works from your meals.",
            fontSize = 12.5.sp, color = Ar.Slate, lineHeight = 18.sp,
        )
    }
}

// ---------------------------------------------------------------------------
//  2. Energy balance
// ---------------------------------------------------------------------------

@Composable
private fun EnergyBalanceCard(eaten: Int, target: Int, burned: Int) {
    ArCard {
        Text("TODAY'S ENERGY", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            color = Ar.Muted, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            EnergyStat("Eaten", "$eaten", Ar.Blue, Modifier.weight(1f))
            EnergyStat("Budget", "$target", Ar.Navy, Modifier.weight(1f))
            EnergyStat("Moved", "$burned", Ar.Teal, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        // Single bar: how much of the budget is spent.
        val fraction = if (target > 0) (eaten.toFloat() / target).coerceIn(0f, 1f) else 0f
        val over = eaten > target
        Box(
            Modifier.fillMaxWidth().height(8.dp)
                .background(Ar.Cream, RoundedCornerShape(999.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(fraction).height(8.dp)
                    .background(if (over) Ar.Orange else Ar.Teal, RoundedCornerShape(999.dp))
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (over) "${eaten - target} kcal past today's budget."
            else "${target - eaten} kcal of budget left.",
            fontSize = 12.5.sp, color = if (over) Ar.Orange else Ar.Muted,
        )
    }
}

@Composable
private fun EnergyStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 11.sp, color = Ar.Muted)
    }
}

// ---------------------------------------------------------------------------
//  3. What to actually do
// ---------------------------------------------------------------------------

@Composable
private fun BalanceItOutCard(verdict: EnergyVerdict) {
    ArCard {
        when (verdict) {
            is EnergyVerdict.Over -> {
                SectionHeading("BALANCE IT OUT", Ar.Orange, Ar.OrangeChipBg)
                Spacer(Modifier.height(10.dp))
                Text(
                    "You're ${verdict.surplusKcal} kcal over. Any one of these clears it:",
                    fontSize = 13.5.sp, color = Ar.Navy, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(12.dp))
                verdict.options.forEach { SuggestionRow(it) }
            }

            is EnergyVerdict.ShortOnMovement -> {
                SectionHeading("MOVE A LITTLE MORE", Ar.Amber, Ar.AmberChipBg)
                Spacer(Modifier.height(10.dp))
                Text(
                    "You're within budget but ${"%,d".format(verdict.stepsShort)} steps " +
                            "below your usual day. Any of these makes up the difference:",
                    fontSize = 13.5.sp, color = Ar.Navy, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(12.dp))
                verdict.options.forEach { SuggestionRow(it) }
            }

            is EnergyVerdict.OnTrack -> {
                SectionHeading("ON TRACK", Ar.Teal, Ar.TealChipBg)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, tint = Ar.Teal, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Movement and meals line up today — ${verdict.headroomKcal} kcal " +
                                "still in budget. Nothing to make up.",
                        fontSize = 13.5.sp, color = Ar.Navy, lineHeight = 19.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String, fg: Color, bg: Color) {
    Text(
        text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        color = fg, letterSpacing = 1.2.sp,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun SuggestionRow(s: BurnSuggestion) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(Ar.Cream, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.activity.emoji, fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.activity.name, fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold, color = Ar.Navy)
            Text(s.activity.note, fontSize = 11.5.sp, color = Ar.Muted, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("${s.minutes} min", fontSize = 15.sp,
                fontWeight = FontWeight.Bold, color = Ar.Teal)
            Text("${s.kcal} kcal", fontSize = 10.5.sp, color = Ar.Muted)
        }
    }
}
