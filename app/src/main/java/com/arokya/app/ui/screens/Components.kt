package com.arokya.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.ml.InferenceStat
import com.arokya.app.ui.theme.Ar

/** Screen header: back arrow + title, in the prototype's style. */
@Composable
fun ArHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Ar.Navy
                )
            }
        }
        Text(
            title,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ar.Navy
        )
    }
}

/**
 * White rounded card, the base surface of almost every screen. A soft shadow
 * lifts it off the cream background — flat white on cream read as unfinished;
 * a little depth is what makes the surfaces feel like a real app.
 */
@Composable
fun ArCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Ar.CardBg,
        shadowElevation = 3.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** Small colored macro/status chip, e.g. "32 g protein". */
@Composable
fun ArChip(text: String, fg: Color, bg: Color) {
    Text(
        text,
        color = fg,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp)
    )
}

/** Full-width teal primary button used across the prototype. */
@Composable
fun ArPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ar.Teal)
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Full-width outlined button — the "go back / do something else instead" action. */
@Composable
fun ArSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ar.Teal),
        border = BorderStroke(1.5.dp, Ar.Teal)
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The "On-device" trust badge shown in the voice coach. */
@Composable
fun OnDeviceBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(7.dp).background(Ar.Teal, CircleShape)
        )
        Spacer(Modifier.width(5.dp))
        Text("On-device", color = Ar.Teal, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Receipt for a single AI result: which model produced it, on which backend,
 * how long it took and how many tokens it cost. Shown directly under every
 * answer so the on-device claim is evidenced per result, not asserted once
 * on a separate screen.
 *
 * Renders nothing when [stat] is null — never invents numbers.
 */
@Composable
fun InferenceChip(stat: InferenceStat?, modifier: Modifier = Modifier) {
    if (stat == null) return
    Row(
        modifier
            .background(Ar.CardBg, RoundedCornerShape(999.dp))
            .border(1.dp, Ar.Teal.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Bolt, null, tint = Ar.Teal, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            "on-device",
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ar.Teal,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stat.summary(),
            fontSize = 10.5.sp,
            color = Ar.Muted,
            maxLines = 1,
        )
    }
}