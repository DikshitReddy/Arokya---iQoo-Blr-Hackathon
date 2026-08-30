package com.arokya.app.data.db

/** One row of the `profile` table (there's only ever one — id 0). */
data class ProfileRow(
    val name: String,
    val age: String,
    val sex: String,
    val weightKg: String,
    val heightCm: String,
    val goal: String,
    val dietTags: List<String>,
    val stepBaseline: Int,
    val labReportName: String?,
    val languageCode: String,
)

data class LabFindingRow(
    val marker: String,
    val value: String,
    val flag: String,
    val dietaryNote: String,
)

data class MealRow(
    val name: String,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val timeLabel: String,
    val fatG: Int,
    val epochMillis: Long,
)

data class PantryItemRow(
    val name: String,
    val quantity: String,
    val proteinG: Int,
    val kcal: Int,
)
