package com.arokya.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.ui.theme.Ar

/**
 * The four destinations of the bottom bar. Scan is a first-class item now,
 * not a raised action — a flat, uniform bar reads as more finished than a
 * single button jutting above the others.
 */
enum class ArTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Meals("Meals", Icons.Filled.RestaurantMenu),
    Scan("Scan", Icons.Filled.CameraAlt),
    Activity("Activity", Icons.Filled.DirectionsWalk),
}

/**
 * A clean, flat bottom navigation bar — four equal items, a soft floating card,
 * and a pill that slides behind the selected item. The camera lives here as a
 * normal tab (Home · Meals · Scan · Activity) rather than a raised hero button.
 */
@Composable
fun ArBottomNav(
    selected: ArTab,
    onSelect: (ArTab) -> Unit,
    onCamera: () -> Unit,
) {
    Surface(
        color = Ar.CardBg,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 10.dp,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArTab.entries.forEach { tab ->
                NavItem(
                    tab = tab,
                    isSelected = tab == selected,
                    modifier = Modifier.weight(1f),
                    onClick = { if (tab == ArTab.Scan) onCamera() else onSelect(tab) },
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: ArTab,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(if (isSelected) Ar.Teal else Ar.Muted, label = "navTint")
    // A soft pill grows behind the selected item — the standard modern
    // "selected" affordance, gentler than a hard underline.
    val pillPad by animateDpAsState(if (isSelected) 10.dp else 0.dp, label = "navPill")

    Column(
        modifier
            .fillMaxHeight()
            .noRippleClickable(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .padding(horizontal = pillPad)
                .background(
                    if (isSelected) Ar.TealChipBg else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(999.dp),
                )
                .padding(horizontal = if (isSelected) 14.dp else 0.dp, vertical = 6.dp),
        ) {
            Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            tab.label,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = tint,
            maxLines = 1,
        )
    }
}

/** Ripple would spill outside the rounded bar; a plain click reads cleaner here. */
@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null) { onClick() }
}
