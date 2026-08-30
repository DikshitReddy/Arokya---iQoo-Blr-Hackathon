package com.arokya.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.data.Store
import com.arokya.app.ui.theme.Ar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash: white -> soft mint gradient, chip-and-leaf mark blooming in,
 * then auto-advance. Matches the Figma splash frame.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val scale = remember { Animatable(0.6f) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { fade.animateTo(1f, tween(durationMillis = 650)) }
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
        delay(900)
        onDone()
    }

    Column(
        Modifier
            .fillMaxSize()
            // The wordmark art sits on white, so the splash is white too — a
            // seamless field for it, blooming in with the same scale+fade.
            .background(Color.White),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.arokya.app.R.drawable.arokya_wordmark),
            contentDescription = "Arokya",
            modifier = Modifier
                .fillMaxWidth(0.66f)
                .scale(scale.value)
                .alpha(fade.value),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "An on-device AI health coach.",
            fontSize = 14.sp,
            color = Ar.Slate,
            modifier = Modifier.alpha(fade.value)
        )
    }
}

/**
 * The Arokya mark drawn in code: a chip (blue) blooming into two
 * leaves (teal). No image assets needed, scales to any size.
 */
@Composable
fun ArokyaLogo(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val lineW = w * 0.055f
        val stroke = Stroke(width = lineW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cx = w / 2f

        // ---- chip (blue rounded square with pins) ----
        val chip = w * 0.30f
        val chipLeft = cx - chip / 2f
        val chipTop = h * 0.10f
        drawRoundRect(
            color = Ar.Blue,
            topLeft = Offset(chipLeft, chipTop),
            size = Size(chip, chip),
            cornerRadius = CornerRadius(w * 0.06f),
            style = stroke
        )
        val pin = w * 0.055f
        val pA = 0.33f
        val pB = 0.67f
        drawLine(Ar.Blue, Offset(chipLeft + chip * pA, chipTop), Offset(chipLeft + chip * pA, chipTop - pin), lineW, StrokeCap.Round)
        drawLine(Ar.Blue, Offset(chipLeft + chip * pB, chipTop), Offset(chipLeft + chip * pB, chipTop - pin), lineW, StrokeCap.Round)
        drawLine(Ar.Blue, Offset(chipLeft, chipTop + chip * pA), Offset(chipLeft - pin, chipTop + chip * pA), lineW, StrokeCap.Round)
        drawLine(Ar.Blue, Offset(chipLeft, chipTop + chip * pB), Offset(chipLeft - pin, chipTop + chip * pB), lineW, StrokeCap.Round)
        drawLine(Ar.Blue, Offset(chipLeft + chip, chipTop + chip * pA), Offset(chipLeft + chip + pin, chipTop + chip * pA), lineW, StrokeCap.Round)
        drawLine(Ar.Blue, Offset(chipLeft + chip, chipTop + chip * pB), Offset(chipLeft + chip + pin, chipTop + chip * pB), lineW, StrokeCap.Round)

        // ---- stem (teal) ----
        val stemTop = chipTop + chip + h * 0.03f
        val stemBottom = h * 0.90f
        drawLine(Ar.Teal, Offset(cx, stemTop), Offset(cx, stemBottom), lineW, StrokeCap.Round)

        // ---- leaves (teal, mirrored) ----
        val baseY = h * 0.86f
        val left = Path().apply {
            moveTo(cx, baseY)
            quadraticBezierTo(w * 0.16f, h * 0.80f, w * 0.15f, h * 0.56f)
            quadraticBezierTo(w * 0.36f, h * 0.60f, cx, baseY)
        }
        val right = Path().apply {
            moveTo(cx, baseY)
            quadraticBezierTo(w * 0.84f, h * 0.80f, w * 0.85f, h * 0.56f)
            quadraticBezierTo(w * 0.64f, h * 0.60f, cx, baseY)
        }
        drawPath(left, Ar.Teal, style = stroke)
        drawPath(right, Ar.Teal, style = stroke)
    }
}

// ---------------- 3-page swipeable onboarding ----------------

private data class OnbPage(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pages = listOf(
        OnbPage(
            Icons.Filled.Mic,
            "Talk to it like a coach.",
            "Ask out loud, in your own words. It already knows your week."
        ),
        OnbPage(
            Icons.Filled.Kitchen,
            "Scan your meals and your fridge.",
            "Point the camera. It recognises what you have and what you ate."
        ),
        OnbPage(
            Icons.Filled.Insights,
            "It learns your daily rhythm.",
            "Steps and meals shape what it suggests tomorrow morning."
        ),
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == pages.size - 1

    Column(Modifier.fillMaxSize().background(Ar.Cream)) {

        // Skip — top right
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                "Skip",
                fontSize = 13.sp,
                color = Ar.Muted,
                modifier = Modifier.clickable { onDone() }.padding(6.dp)
            )
        }

        // Swipeable pages
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { i ->
            val p = pages[i]
            Column(
                Modifier.fillMaxSize().padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier
                        .size(190.dp)
                        .background(Ar.BlueChipBg, RoundedCornerShape(46.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        p.icon,
                        contentDescription = null,
                        tint = Ar.Blue,
                        modifier = Modifier.size(72.dp)
                    )
                }
                Spacer(Modifier.height(34.dp))
                Text(
                    p.title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ar.Navy,
                    textAlign = TextAlign.Center,
                    lineHeight = 31.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    p.subtitle,
                    fontSize = 14.sp,
                    color = Ar.Slate,
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp
                )
            }
        }

        // Bottom bar: dots left, button right
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(pages.size) { i ->
                    val active = pagerState.currentPage == i
                    val width by animateDpAsState(
                        targetValue = if (active) 26.dp else 8.dp,
                        label = "dotWidth"
                    )
                    Box(
                        Modifier
                            .padding(end = 7.dp)
                            .height(8.dp)
                            .width(width)
                            .background(
                                if (active) Ar.Teal else Ar.Muted.copy(alpha = 0.35f),
                                CircleShape
                            )
                    )
                }
            }
            Button(
                onClick = {
                    if (isLast) onDone()
                    else scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ar.Teal),
                contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp)
            ) {
                Text(
                    if (isLast) "Get Started" else "Next",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ---------------- Goals & profile setup ----------------

/**
 * The goal list — the onboarding "What matters to you?" screen and the diet-tag
 * chips were both removed, so this is now just the goal picker used by Edit
 * Profile.
 */
@Composable
fun GoalAndDietSection(
    selectedGoal: String,
    onGoalChange: (String) -> Unit,
) {
    val goals = listOf("Eat healthier", "Build strength", "Manage a condition", "Just stay consistent")

    goals.forEach { g ->
        val selected = g == selectedGoal
        Box(
            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .background(if (selected) Ar.TealChipBg else Ar.CardBg, RoundedCornerShape(14.dp))
                .border(
                    1.4.dp, if (selected) Ar.Teal else Ar.Navy.copy(alpha = 0.12f),
                    RoundedCornerShape(14.dp)
                )
                .clickable { onGoalChange(g) }
                .padding(16.dp)
        ) {
            Text(g, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ar.Navy)
        }
    }
}