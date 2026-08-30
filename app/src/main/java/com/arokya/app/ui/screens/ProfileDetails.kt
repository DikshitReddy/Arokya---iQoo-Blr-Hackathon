package com.arokya.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.data.AppLanguage
import com.arokya.app.data.LabFinding
import com.arokya.app.data.Languages
import com.arokya.app.data.Store
import com.arokya.app.ml.InferenceStat
import com.arokya.app.ml.InferenceStats
import com.arokya.app.ml.Ml
import com.arokya.app.ui.theme.Ar
import kotlinx.coroutines.launch

/** Name + age/weight/height/sex are mandatory — this shapes every suggestion the app makes. */
private fun isProfileValid(
    name: String, age: String, weight: String, height: String,
    language: AppLanguage?,
): Boolean {
    val a = age.toIntOrNull()
    val w = weight.toDoubleOrNull()
    val h = height.toDoubleOrNull()
    // Language is required: every AI reply is generated in it, so there is no
    // sensible default to fall back on silently.
    return language != null &&
            name.isNotBlank() && a != null && a in 1..119 && w != null && w > 0 && h != null && h > 0
}

@Composable
fun ProfileDetailsScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(Store.profile.name) }
    var age by remember { mutableStateOf(Store.profile.age) }
    var weight by remember { mutableStateOf(Store.profile.weightKg) }
    var height by remember { mutableStateOf(Store.profile.heightCm) }
    var sex by remember { mutableStateOf(Store.profile.sex) }
    var language by remember { mutableStateOf<AppLanguage?>(null) }
    var showErrors by remember { mutableStateOf(false) }

    val (labState, pickLabReport) = rememberLabUpload(Store.profile.labReportName, Store.profile.labFindings)

    Column(
        Modifier.fillMaxSize().background(Ar.Cream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(32.dp))
        Text("A bit about you", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ar.Navy)
        Spacer(Modifier.height(8.dp))
        Text(
            "This shapes every suggestion Arokya makes.",
            fontSize = 14.sp, color = Ar.Slate
        )
        Spacer(Modifier.height(22.dp))

        PersonalDetailsFields(
            name = name, onNameChange = { name = it },
            age = age, onAgeChange = { age = it },
            weight = weight, onWeightChange = { weight = it },
            height = height, onHeightChange = { height = it },
            sex = sex, onSexChange = { sex = it },
            showErrors = showErrors,
        )

        Spacer(Modifier.height(22.dp))
        LanguageField(language, showErrors) { language = it }

        LabReportSection(labState, onPick = pickLabReport)

        Spacer(Modifier.height(26.dp))
        ArPrimaryButton("Continue") {
            if (isProfileValid(name, age, weight, height, language)) {
                Store.profile.name = name
                Store.profile.age = age
                Store.profile.sex = sex
                Store.profile.weightKg = weight
                Store.profile.heightCm = height
                Store.profile.languageCode = language!!.code
                scope.launch {
                    Store.saveProfile()
                    // Profile is the final onboarding step now — mark done so a
                    // re-launch goes straight to Home (the goal screen that used
                    // to do this was removed).
                    Store.onboarded.value = true
                    onDone()
                }
            } else {
                showErrors = true
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Lock, null, tint = Ar.Muted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text("Your report is processed on this device.", fontSize = 11.5.sp, color = Ar.Muted)
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Reachable any time from Home's avatar — the same personal-detail, goal/diet,
 * and lab-report sections as onboarding, editable afterwards in one place.
 */
@Composable
fun EditProfileScreen(onBack: () -> Unit, onWhereThisRan: () -> Unit) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(Store.profile.name) }
    var age by remember { mutableStateOf(Store.profile.age) }
    var weight by remember { mutableStateOf(Store.profile.weightKg) }
    var height by remember { mutableStateOf(Store.profile.heightCm) }
    var sex by remember { mutableStateOf(Store.profile.sex) }
    var selectedGoal by remember { mutableStateOf(Store.profile.goal) }
    var language by remember { mutableStateOf<AppLanguage?>(Languages.byCode(Store.profile.languageCode)) }
    var showErrors by remember { mutableStateOf(false) }

    val (labState, pickLabReport) = rememberLabUpload(Store.profile.labReportName, Store.profile.labFindings)

    Column(
        Modifier.fillMaxSize().background(Ar.Cream)
            .verticalScroll(rememberScrollState())
    ) {
        ArHeader("Edit profile", onBack = onBack)
        Column(Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            PersonalDetailsFields(
                name = name, onNameChange = { name = it },
                age = age, onAgeChange = { age = it },
                weight = weight, onWeightChange = { weight = it },
                height = height, onHeightChange = { height = it },
                sex = sex, onSexChange = { sex = it },
                showErrors = showErrors,
            )

            Spacer(Modifier.height(22.dp))
            Text("Goal", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
            Spacer(Modifier.height(10.dp))
            GoalAndDietSection(selectedGoal, { selectedGoal = it })

            Spacer(Modifier.height(22.dp))
            LanguageField(language, showErrors) { language = it }

            LabReportSection(labState, onPick = pickLabReport)

            Spacer(Modifier.height(22.dp))
            ArPrimaryButton("Save changes") {
                if (isProfileValid(name, age, weight, height, language)) {
                    Store.profile.name = name
                    Store.profile.age = age
                    Store.profile.sex = sex
                    Store.profile.weightKg = weight
                    Store.profile.heightCm = height
                    Store.profile.goal = selectedGoal
                    Store.profile.languageCode = language!!.code
                    scope.launch {
                        Store.saveProfile()
                        onBack()
                    }
                } else {
                    showErrors = true
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Where this ran", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ar.Muted,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable { onWhereThisRan() }
                    .padding(8.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Name/age/weight/height/sex fields + the live BMI card — shared by onboarding and Edit Profile. */
@Composable
private fun PersonalDetailsFields(
    name: String, onNameChange: (String) -> Unit,
    age: String, onAgeChange: (String) -> Unit,
    weight: String, onWeightChange: (String) -> Unit,
    height: String, onHeightChange: (String) -> Unit,
    sex: String, onSexChange: (String) -> Unit,
    showErrors: Boolean,
) {
    ArField("Name", name, onNameChange)
    if (showErrors && name.isBlank()) {
        Spacer(Modifier.height(4.dp))
        Text("Name is required.", fontSize = 12.sp, color = Ar.Orange)
    }
    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            ArField("Age", age, { onAgeChange(it.filter(Char::isDigit).take(3)) }, number = true)
        }
        Box(Modifier.weight(1f)) {
            ArField("Weight (kg)", weight, {
                onWeightChange(it.filter { c -> c.isDigit() || c == '.' }.take(5))
            }, number = true)
        }
    }
    if (showErrors && (age.toIntOrNull() ?: 0) !in 1..119) {
        Spacer(Modifier.height(4.dp))
        Text("Enter a valid age.", fontSize = 12.sp, color = Ar.Orange)
    }
    Spacer(Modifier.height(12.dp))
    ArField("Height (cm)", height, {
        onHeightChange(it.filter { c -> c.isDigit() || c == '.' }.take(5))
    }, number = true)
    if (showErrors && ((weight.toDoubleOrNull() ?: 0.0) <= 0 || (height.toDoubleOrNull() ?: 0.0) <= 0)) {
        Spacer(Modifier.height(4.dp))
        Text("Enter your weight and height.", fontSize = 12.sp, color = Ar.Orange)
    }

    Spacer(Modifier.height(14.dp))
    Text("Sex", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Female", "Male", "Other").forEach { option ->
            SelectChip(option, sex == option) { onSexChange(option) }
        }
    }

    // ---- live BMI ----
    val bmi = remember(weight, height) {
        val w = weight.toDoubleOrNull()
        val h = height.toDoubleOrNull()
        if (w == null || h == null || w <= 0 || h <= 0) null
        else w / ((h / 100.0) * (h / 100.0))
    }
    if (bmi != null) {
        Spacer(Modifier.height(16.dp))
        ArCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("BMI", fontSize = 12.sp, color = Ar.Muted)
                    Text(
                        String.format("%.1f", bmi),
                        fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ar.Navy
                    )
                }
                ArChip(bmiBand(bmi), Ar.Blue, Ar.BlueChipBg)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "BMI is one rough signal among several. Arokya uses it " +
                        "alongside your activity and meals, never on its own.",
                fontSize = 11.5.sp, color = Ar.Muted, lineHeight = 16.sp
            )
        }
    }
}

/** Holds one lab-report upload's in-flight state — shared by onboarding and Edit Profile. */
private class LabUploadState(initialFileName: String?, initialFindings: List<LabFinding>) {
    var fileName by mutableStateOf(initialFileName)
    var analysing by mutableStateOf(false)
    var findings by mutableStateOf(initialFindings)
    var error by mutableStateOf<String?>(null)
    /** Measured cost of the extraction run — null for findings restored from the DB. */
    var stat by mutableStateOf<InferenceStat?>(null)
}

/** Registers the file picker + the analyze-and-persist coroutine once; returns state + a launch trigger. */
@Composable
private fun rememberLabUpload(
    initialFileName: String?,
    initialFindings: List<LabFinding>,
): Pair<LabUploadState, () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { LabUploadState(initialFileName, initialFindings) }

    // Storage Access Framework — no storage permission needed.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        state.fileName = queryDisplayName(context, uri) ?: "Lab report"
        state.error = null
        state.analysing = true
        scope.launch {
            try {
                val result = Ml.lab.analyze(context, uri)
                state.findings = result
                state.stat = InferenceStats.last
                Store.profile.labReportName = state.fileName
                Store.profile.labFindings = result
                Store.saveProfile()
            } catch (e: Exception) {
                state.error = "Could not read that file. Try a PDF or a clear photo."
                state.findings = emptyList()
            } finally {
                state.analysing = false
            }
        }
    }

    return state to { picker.launch("*/*") } // PDFs and images; change to "application/pdf" to restrict
}

/** Upload row + extracted-markers list — shared by onboarding and Edit Profile. */
@Composable
private fun LabReportSection(state: LabUploadState, onPick: () -> Unit) {
    Spacer(Modifier.height(22.dp))
    Text("Lab reports", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
    Spacer(Modifier.height(4.dp))
    Text(
        "Optional. Upload a blood report and Arokya reads the values " +
                "to shape food suggestions.",
        fontSize = 12.5.sp, color = Ar.Slate, lineHeight = 18.sp
    )
    Spacer(Modifier.height(12.dp))

    Row(
        Modifier
            .fillMaxWidth()
            .background(Ar.CardBg, RoundedCornerShape(16.dp))
            .border(1.4.dp, Ar.Teal.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(enabled = !state.analysing) { onPick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).background(Ar.TealChipBg, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (state.analysing) {
                CircularProgressIndicator(
                    color = Ar.Teal, strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    if (state.fileName == null) Icons.Filled.UploadFile else Icons.Filled.Description,
                    contentDescription = null, tint = Ar.Teal,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    state.analysing -> "Reading your report…"
                    state.fileName != null -> state.fileName!!
                    else -> "Upload a lab report"
                },
                fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy
            )
            Text(
                if (state.fileName != null && !state.analysing) "Tap to replace"
                else "PDF or photo",
                fontSize = 12.sp, color = Ar.Muted
            )
        }
        if (state.fileName != null && !state.analysing) {
            Icon(Icons.Filled.Check, null, tint = Ar.Teal, modifier = Modifier.size(20.dp))
        }
    }

    state.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, fontSize = 12.5.sp, color = Ar.Orange)
    }

    if (state.findings.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text(
            "WHAT AROKYA READ", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            color = Ar.Purple, letterSpacing = 1.2.sp
        )
        if (state.stat != null) {
            Spacer(Modifier.height(8.dp))
            InferenceChip(state.stat)
        }
        Spacer(Modifier.height(8.dp))
        state.findings.forEach { f -> FindingRow(f) }
        Spacer(Modifier.height(10.dp))
        ArCard {
            Text(
                "These are values, not a diagnosis. Arokya uses them only to " +
                        "adjust food suggestions. Talk to your doctor about what " +
                        "any result means for your health.",
                fontSize = 11.5.sp, color = Ar.Slate, lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun FindingRow(f: LabFinding) {
    val (fg, bg) = when (f.flag) {
        "Low" -> Ar.Amber to Ar.AmberChipBg
        "High" -> Ar.Orange to Ar.OrangeChipBg
        else -> Ar.Teal to Ar.TealChipBg
    }
    ArCard(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(f.marker, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
                Text(f.value, fontSize = 12.5.sp, color = Ar.Slate)
            }
            ArChip(f.flag, fg, bg)
        }
        Spacer(Modifier.height(6.dp))
        Text(f.dietaryNote, fontSize = 12.sp, color = Ar.Muted, lineHeight = 17.sp)
    }
}

@Composable
private fun ArField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    number: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 13.sp) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Ar.CardBg,
            unfocusedContainerColor = Ar.CardBg,
            focusedBorderColor = Ar.Teal,
            unfocusedBorderColor = Ar.Navy.copy(alpha = 0.12f),
            focusedLabelColor = Ar.Teal,
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = if (selected) Ar.Teal else Ar.Muted,
        modifier = Modifier
            .background(if (selected) Ar.TealChipBg else Ar.CardBg, RoundedCornerShape(999.dp))
            .border(
                1.dp,
                if (selected) Ar.Teal.copy(alpha = 0.5f) else Ar.Navy.copy(alpha = 0.12f),
                RoundedCornerShape(999.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp)
    )
}

private fun bmiBand(bmi: Double): String = when {
    bmi < 18.5 -> "Under 18.5"
    bmi < 25.0 -> "18.5 – 24.9"
    bmi < 30.0 -> "25 – 29.9"
    else -> "30+"
}

/** Pulls the human-readable filename out of a content:// Uri. */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Exception) {
        null
    }
}
