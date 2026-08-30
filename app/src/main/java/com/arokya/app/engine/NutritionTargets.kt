package com.arokya.app.engine

import com.arokya.app.data.UserProfile
import kotlin.math.roundToInt

/**
 * A day's nutrition budget. Plain numbers, no model involved — the Meals tab
 * measures what's been eaten against these.
 */
data class DailyTargets(
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    /** Human-readable explanation of where these numbers came from. */
    val basis: String,
    /** False when the profile was too incomplete to personalise — generic defaults. */
    val personalized: Boolean,
)

/**
 * THE ENGINE DECIDES — same rule as [RuleEngine]. These targets come from a
 * published equation (Mifflin-St Jeor BMR × an activity factor), never from a
 * model, so every number on the Meals tab is traceable and defensible.
 *
 * NOT medical advice: this is a general-population estimate, and the UI says so.
 */
object NutritionTargets {

    private val GENERIC = DailyTargets(
        kcal = 2000, proteinG = 75, carbsG = 250, fatG = 65,
        basis = "General adult estimate — add your age, weight and height in your profile for targets built around you.",
        personalized = false,
    )

    fun forProfile(profile: UserProfile): DailyTargets {
        val weight = profile.weightKg.toDoubleOrNull()
        val height = profile.heightCm.toDoubleOrNull()
        val age = profile.age.toIntOrNull()
        if (weight == null || height == null || age == null || weight <= 0 || height <= 0 || age <= 0) {
            return GENERIC
        }

        // Mifflin-St Jeor resting metabolic rate.
        val base = 10 * weight + 6.25 * height - 5 * age
        val bmr = when (profile.sex) {
            "Male" -> base + 5
            "Female" -> base - 161
            else -> base - 78 // midpoint when sex isn't specified
        }

        // Activity multiplier from their USUAL day, so the target is stable
        // rather than sliding around as today's step count changes.
        val activityFactor = when {
            profile.stepBaseline < 5_000 -> 1.40
            profile.stepBaseline < 7_500 -> 1.50
            profile.stepBaseline < 10_000 -> 1.60
            else -> 1.75
        }

        val goalFactor = if (profile.goal == "Build strength") 1.10 else 1.0
        val kcal = (bmr * activityFactor * goalFactor).roundTo(10)

        // Protein scales with body weight and goal; fat holds ~27% of energy;
        // carbohydrate takes whatever energy is left.
        val proteinPerKg = when (profile.goal) {
            "Build strength" -> 1.8
            "Just stay consistent" -> 1.2
            else -> 1.4
        }
        val proteinG = (weight * proteinPerKg).roundTo(5)
        val fatG = (kcal * 0.27 / 9).roundTo(5)
        val carbsG = ((kcal - proteinG * 4 - fatG * 9) / 4.0).roundTo(5).coerceAtLeast(0)

        return DailyTargets(
            kcal = kcal,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
            basis = "Built from your ${weight.trimZero()} kg, ${height.trimZero()} cm, " +
                    "age $age, and a ${"%,d".format(profile.stepBaseline)}-step usual day.",
            personalized = true,
        )
    }

    /**
     * ENGINE gate for the reel-to-recipe feature: is one serving heavy for
     * THIS user's day? More than 35% of the daily budget in a single dish is
     * the point where offering the lighter version stops being nagging.
     * Deterministic — the model proposes a lighter recipe every time, but
     * whether it's SHOWN is decided here.
     */
    fun isHighCalorie(kcal: Int, profile: UserProfile): Boolean =
        kcal > forProfile(profile).kcal * 0.35

    private fun Double.roundTo(step: Int): Int = (this / step).roundToInt() * step

    private fun Double.trimZero(): String =
        if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(this)
}
