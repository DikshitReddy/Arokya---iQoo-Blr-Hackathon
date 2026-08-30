package com.arokya.app.data

/** One item in the pantry ledger. */
data class PantryItem(
    val name: String,
    val quantity: String,
    val proteinG: Int = 0,
    val kcal: Int = 0,
)

/** One item the camera scan detected, judged against the user's diet plan. */
data class ScannedItem(
    val name: String,
    val quantity: String,
    val fitsDiet: Boolean,
    val note: String,
)

/** A prepared, cooked meal identified from a photo — nutrition + how it fits the user's plan. */
data class MealAnalysis(
    val mealName: String,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fitsGoal: Boolean,
    val summary: String,        // overall nutritional summary
    val recommendation: String, // portion/alternative advice + how it fits remaining daily targets
)

/** A meal the user logged (via scan or voice). */
data class Meal(
    val name: String,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val timeLabel: String,
    val fatG: Int = 0,
    /** When it was logged. Drives the day rollover — see [Store.isToday]. */
    val epochMillis: Long = System.currentTimeMillis(),
)

/** The user's profile chosen in onboarding. */
data class UserProfile(
    var name: String = "",
    var age: String = "",
    var sex: String = "Prefer not to say",
    var weightKg: String = "",
    var heightCm: String = "",
    var goal: String = "Eat healthier",
    /** Code from [Languages]. Every AI reply is generated in this language. */
    var languageCode: String = Languages.DEFAULT.code,
    // Diet selection was removed from setup — default to none so no veg/non-veg
    // assumption is baked in; prompts read this as "unspecified".
    var dietTags: MutableList<String> = mutableListOf(),
    var stepBaseline: Int = 8000,
    /** Filename of the uploaded lab report, if any. */
    var labReportName: String? = null,
    /** Markers extracted from the report, used as safety constraints. */
    var labFindings: List<LabFinding> = emptyList(),
) {
    /** BMI, or null if height/weight are missing or invalid. */
    val bmi: Double?
        get() {
            val w = weightKg.toDoubleOrNull() ?: return null
            val h = heightCm.toDoubleOrNull() ?: return null
            if (w <= 0 || h <= 0) return null
            val m = h / 100.0
            return w / (m * m)
        }
}

/** One marker read out of a lab report. Never a diagnosis — just a value. */
data class LabFinding(
    val marker: String,      // e.g. "Haemoglobin"
    val value: String,       // e.g. "11.2 g/dL"
    val flag: String,        // "Low" | "Normal" | "High"
    val dietaryNote: String, // what it means for FOOD, not for health
)

/** Everything the rule engine needs to decide. Assembled from repositories. */
data class HealthContext(
    val stepsToday: Int,
    val stepBaseline: Int,
    val kcalToday: Int,
    val proteinToday: Int,
    val mealsLogged: List<Meal>,
    val pantry: List<PantryItem>,
    val goal: String,
    val dietTags: List<String>,
    val hourOfDay: Int,
    val labFindings: List<LabFinding> = emptyList(),
)

/**
 * What the deterministic engine outputs. The LLM may rephrase [message]
 * but must never change [title], [reasons] or the numbers.
 */
data class Recommendation(
    val title: String,
    val message: String,
    val reasons: List<String>,   // powers the "Why this?" sheet
    val ruleId: String,          // which rule fired (shown to judges!)
    val usesPantry: List<String> = emptyList(),
    val kcal: Int = 0,
    val proteinG: Int = 0,
    val carbsG: Int = 0,
)