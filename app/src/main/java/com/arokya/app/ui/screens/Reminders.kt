package com.arokya.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.data.Store
import com.arokya.app.data.db.ReminderDao
import com.arokya.app.reminder.Reminders
import com.arokya.app.ui.theme.Ar
import kotlinx.coroutines.launch

/**
 * The reminders the user has set through the coach — the visible, manageable
 * home for them.
 *
 * WHY THIS EXISTS: reminders fire via AlarmManager, and AlarmManager alarms are
 * app-private — they never appear in the phone's Clock app (only alarms created
 * through that app's own UI do). So "I set a reminder but can't see it anywhere"
 * is expected on Android. This screen is where they live and can be cancelled.
 */
@Composable
fun RemindersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reminders by remember { mutableStateOf<List<ReminderDao.Row>>(emptyList()) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) { reminders = Store.allReminders() }

    val now = System.currentTimeMillis()
    val upcoming = reminders.filter { !it.fired && it.triggerAtMillis > now }
        .sortedBy { it.triggerAtMillis }
    val past = reminders.filter { it.fired || it.triggerAtMillis <= now }
        .sortedByDescending { it.triggerAtMillis }

    Column(
        Modifier.fillMaxSize().background(Ar.Cream).verticalScroll(rememberScrollState())
    ) {
        ArHeader("Reminders", onBack = onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {

            if (reminders.isEmpty()) {
                ArCard {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(52.dp).background(Ar.TealChipBg, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Alarm, null, tint = Ar.Teal, modifier = Modifier.size(24.dp)) }
                        Spacer(Modifier.height(12.dp))
                        Text("No reminders yet", fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, color = Ar.Navy)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Ask the coach — “remind me for gym tomorrow at 7 am” — and it " +
                                    "shows up here.",
                            fontSize = 12.5.sp, color = Ar.Slate, lineHeight = 18.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            if (upcoming.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SectionLabel("UPCOMING")
                upcoming.forEach { r ->
                    ReminderRow(r, past = false) {
                        scope.launch { Reminders.cancel(context, r.id); reloadKey++ }
                    }
                }
            }

            if (past.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SectionLabel("EARLIER")
                past.forEach { r ->
                    ReminderRow(r, past = true) {
                        scope.launch { Store.deleteReminder(r.id); reloadKey++ }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            ArCard {
                Text(
                    "Reminders fire as a notification, on time, even with the app closed. " +
                            "They don't appear in your phone's Clock app — Android keeps app " +
                            "reminders separate — so this is where you manage them.",
                    fontSize = 11.5.sp, color = Ar.Muted, lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
        color = Ar.Muted, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun ReminderRow(r: ReminderDao.Row, past: Boolean, onRemove: () -> Unit) {
    ArCard(Modifier.padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp)
                    .background(if (past) Ar.Cream else Ar.TealChipBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (past) Icons.Filled.Check else Icons.Filled.Alarm,
                    null, tint = if (past) Ar.Muted else Ar.Teal, modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
                Text(
                    Reminders.format(r.triggerAtMillis) + if (r.fired) " · done" else "",
                    fontSize = 12.5.sp, color = if (past) Ar.Muted else Ar.Teal,
                )
            }
            Icon(
                Icons.Filled.Close,
                contentDescription = if (past) "Remove ${r.title}" else "Cancel ${r.title}",
                tint = Ar.Muted,
                modifier = Modifier.size(20.dp).clickable { onRemove() },
            )
        }
    }
}
