package com.arokya.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.arokya.app.data.AppLanguage
import com.arokya.app.data.Languages
import com.arokya.app.ui.theme.Ar

/**
 * Required "Preferred language" field.
 *
 * A plain Material dropdown would render 23 items in a menu that overflows the
 * screen, so this opens a scrollable sheet instead. Each row shows the native
 * script first — someone looking for their own language scans for the script
 * they read, not the English name.
 */
@Composable
fun LanguageField(
    selected: AppLanguage?,
    showError: Boolean,
    onSelect: (AppLanguage) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Text("Preferred language", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
    Spacer(Modifier.height(4.dp))
    Text(
        "Arokya will talk to you in this language — voice, meal plans and reminders.",
        fontSize = 12.sp, color = Ar.Slate, lineHeight = 17.sp,
    )
    Spacer(Modifier.height(8.dp))

    Row(
        Modifier
            .fillMaxWidth()
            .background(Ar.CardBg, RoundedCornerShape(14.dp))
            .border(
                1.4.dp,
                if (showError && selected == null) Ar.Orange else Ar.Navy.copy(alpha = 0.12f),
                RoundedCornerShape(14.dp),
            )
            .clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Language, null, tint = Ar.Teal, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            selected?.label() ?: "Choose a language",
            fontSize = 15.sp,
            color = if (selected == null) Ar.Muted else Ar.Navy,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Filled.ExpandMore, null, tint = Ar.Muted, modifier = Modifier.size(20.dp))
    }
    if (showError && selected == null) {
        Spacer(Modifier.height(4.dp))
        Text("Please choose a language.", fontSize = 12.sp, color = Ar.Orange)
    }

    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Surface(color = Ar.Cream, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(vertical = 18.dp).heightIn(max = 520.dp)) {
                    Text(
                        "Preferred language",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ar.Navy,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Languages.ALL.forEach { language ->
                            val isSelected = language.code == selected?.code
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(language); open = false }
                                    .padding(horizontal = 20.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        language.nativeName,
                                        fontSize = 16.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Ar.Teal else Ar.Navy,
                                    )
                                    if (language.nativeName != language.englishName) {
                                        Text(language.englishName, fontSize = 12.sp, color = Ar.Muted)
                                    }
                                }
                                if (isSelected) {
                                    Icon(Icons.Filled.Check, null, tint = Ar.Teal,
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
