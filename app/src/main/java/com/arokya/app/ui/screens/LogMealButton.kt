package com.arokya.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.data.Meal
import com.arokya.app.data.Store
import com.arokya.app.ui.theme.Ar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogState { Idle, Saving, Saved, Duplicate, Failed }

/**
 * The one way a meal gets logged anywhere in this app.
 *
 * It exists because the previous inline `scope.launch { Store.addMeal(...) }`
 * blocks gave the user NOTHING to look at: the button stayed enabled and
 * unchanged while the insert ran, so a tap that had actually worked looked
 * like a tap that hadn't — and people tap again. That produced 19 identical
 * rows in one minute in real testing.
 *
 * So: the button disables the instant it's pressed, shows the save happening,
 * confirms success, and only then navigates. [Store.addMeal] independently
 * rejects same-dish-same-minute repeats, so even two screens racing can't
 * double-log.
 */
@Composable
fun LogMealButton(
    mealName: String,
    kcal: Int,
    proteinG: Int,
    carbsG: Int,
    fatG: Int,
    label: String = "Log this meal",
    onLogged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var logState by remember(mealName, kcal) { mutableStateOf(LogState.Idle) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Let the confirmation actually be seen before the screen changes under
    // the user — navigating instantly reads as "nothing happened".
    LaunchedEffect(logState) {
        if (logState == LogState.Saved || logState == LogState.Duplicate) {
            delay(700)
            onLogged()
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                if (logState != LogState.Idle && logState != LogState.Failed) return@Button
                logState = LogState.Saving
                errorText = null
                scope.launch {
                    val now = System.currentTimeMillis()
                    val result = Store.addMeal(
                        Meal(
                            name = mealName,
                            kcal = kcal,
                            proteinG = proteinG,
                            carbsG = carbsG,
                            fatG = fatG,
                            timeLabel = SimpleDateFormat("h:mm a", Locale.getDefault())
                                .format(Date(now)),
                            epochMillis = now,
                        )
                    )
                    logState = when (result) {
                        is Store.LogResult.Saved -> LogState.Saved
                        is Store.LogResult.Duplicate -> LogState.Duplicate
                        is Store.LogResult.Failed -> {
                            errorText = result.message
                            LogState.Failed
                        }
                    }
                }
            },
            // Disabled the moment it's pressed — this is the duplicate guard
            // the user actually sees.
            enabled = logState == LogState.Idle || logState == LogState.Failed,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when (logState) {
                    LogState.Saved, LogState.Duplicate -> Ar.Teal
                    LogState.Failed -> Ar.Orange
                    else -> Ar.Teal
                },
                disabledContainerColor = when (logState) {
                    LogState.Saved, LogState.Duplicate -> Ar.Teal
                    else -> Ar.Teal.copy(alpha = 0.6f)
                },
                disabledContentColor = Color.White,
            ),
        ) {
            when (logState) {
                LogState.Saving -> {
                    CircularProgressIndicator(
                        color = Color.White, strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Saving…", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                LogState.Saved -> {
                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Logged", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                LogState.Duplicate -> {
                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Already logged", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                LogState.Failed -> Text("Try logging again", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                LogState.Idle -> Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (logState == LogState.Duplicate) {
            Spacer(Modifier.height(8.dp))
            Text(
                "You already logged this a moment ago — not adding it twice.",
                fontSize = 12.sp, color = Ar.Muted, lineHeight = 17.sp,
            )
        }
        errorText?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PriorityHigh, null, tint = Ar.Orange,
                    modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Couldn't save: $it",
                    fontSize = 12.sp, color = Ar.Orange, lineHeight = 17.sp,
                )
            }
        }
    }
}
