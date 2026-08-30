package com.arokya.app.engine

import com.arokya.app.data.UserProfile
import kotlin.math.roundToInt

/**
 * THE ENGINE DECIDES — how much movement balances what was eaten.
 *
 * Every number here comes from the MET (Metabolic Equivalent of Task)
 * equation used throughout exercise physiology:
 *
 *     kcal/min = MET × 3.5 × weightKg / 200
 *
 * so a suggestion like "38 min of brisk walking" is arithmetic on the user's
 * own body weight, not a model's guess. That's what lets the Activity tab
 * name exact durations and defend them.
 *
 * NOT medical advice, and deliberately conservative: these are
 * general-population estimates, and the UI says so.
 */
data class FitnessActivity(
    val name: String,
    val emoji: String,
    /** Compendium of Physical Activities value, moderate intensity. */
    val met: Double,
    /** Why someone might pick this one over the others. */
    val note: String,
)

/** One concrete thing the user could do, sized to the gap. */
data class BurnSuggestion(
    val activity: FitnessActivity,
    val minutes: Int,
    val kcal: Int,
)

/** What the Activity tab should be telling the user right now. */
sealed interface EnergyVerdict {
    /** Eaten more than the day's budget — movement can close the gap. */
    data class Over(val surplusKcal: Int, val options: List<BurnSuggestion>) : EnergyVerdict
    /** Within budget but short of the usual step count. */
    data class ShortOnMovement(val stepsShort: Int, val options: List<BurnSuggestion>) : EnergyVerdict
    /** Budget respected and moving well. */
    data class OnTrack(val headroomKcal: Int) : EnergyVerdict
}

object ActivityBurn {

    /**
     * Deliberately a small, ordinary list: things reachable from a flat, a
     * stairwell or a park, spanning gentle to intense so there's always a
     * realistic option whatever the gap.
     */
    val CATALOG = listOf(
        FitnessActivity("Walking", "🚶", 3.5, "Easiest to start; counts toward your steps too."),
        FitnessActivity("Brisk walking", "🥾", 4.3, "Same route, faster pace — noticeably more burn."),
        FitnessActivity("Climbing stairs", "🪜", 8.0, "No equipment, and quick — your building already has some."),
        FitnessActivity("Cycling", "🚴", 6.8, "Easy on the knees at a steady pace."),
        FitnessActivity("Jogging", "🏃", 7.0, "Fastest way to close a big gap."),
        FitnessActivity("Skipping rope", "🪢", 11.0, "Very high burn per minute; short bursts are enough."),
        FitnessActivity("Strength training", "🏋️", 5.0, "Builds muscle, which raises your resting burn."),
        FitnessActivity("Yoga", "🧘", 2.8, "Gentle option for a rest day or late evening."),
        FitnessActivity("Dancing", "💃", 5.5, "Counts as cardio, and doesn't feel like exercise."),
        FitnessActivity("Household chores", "🧹", 3.3, "Cleaning or cooking still moves the needle."),
    )

    /** Fallback so a profile with no weight still gets sensible durations. */
    private const val DEFAULT_WEIGHT_KG = 70.0

    private fun weightOf(profile: UserProfile): Double =
        profile.weightKg.toDoubleOrNull()?.takeIf { it > 0 } ?: DEFAULT_WEIGHT_KG

    fun kcalPerMinute(met: Double, weightKg: Double): Double = met * 3.5 * weightKg / 200.0

    /** Minutes of [activity] needed to burn [kcal], rounded to a usable number. */
    fun minutesFor(kcal: Int, activity: FitnessActivity, weightKg: Double): Int {
        val perMin = kcalPerMinute(activity.met, weightKg)
        if (perMin <= 0) return 0
        return (kcal / perMin).roundToInt().coerceAtLeast(1)
    }

    /**
     * Calories the day's steps already burned.
     *
     * ~0.0005 kcal per step per kg is the standard walking approximation —
     * 10,000 steps for a 70 kg adult lands near 350 kcal, which matches
     * published figures.
     */
    fun kcalFromSteps(steps: Int, profile: UserProfile): Int =
        (steps * 0.0005 * weightOf(profile)).roundToInt()

    /**
     * Distance walked. Stride is estimated from height (≈0.415 × height), which
     * is meaningfully better than a fixed 0.76 m for shorter or taller users.
     */
    fun distanceKm(steps: Int, profile: UserProfile): Double {
        val heightCm = profile.heightCm.toDoubleOrNull()?.takeIf { it > 0 } ?: 170.0
        val strideM = heightCm * 0.415 / 100.0
        return steps * strideM / 1000.0
    }

    /**
     * The three options offered for a given gap. Picks a gentle, a moderate and
     * an intense choice rather than the top three by burn rate — otherwise
     * every answer is "skip rope", which nobody does for 40 minutes.
     */
    private fun optionsFor(kcal: Int, weightKg: Double): List<BurnSuggestion> {
        val picks = listOf(
            CATALOG.first { it.name == "Walking" },
            CATALOG.first { it.name == "Cycling" },
            CATALOG.first { it.name == "Jogging" },
        )
        return picks.map { activity ->
            val minutes = minutesFor(kcal, activity, weightKg)
            BurnSuggestion(
                activity = activity,
                minutes = minutes,
                kcal = (kcalPerMinute(activity.met, weightKg) * minutes).roundToInt(),
            )
        }
    }

    /**
     * Reads the day and decides what to say.
     *
     * [targetKcal] already includes an activity factor for the user's usual
     * day (see [NutritionTargets]), so step burn is NOT subtracted from the
     * budget again — doing that would double-count the movement the target
     * already assumes. Steps are reported alongside, as context.
     */
    fun verdict(
        eatenKcal: Int,
        targetKcal: Int,
        steps: Int,
        stepBaseline: Int,
        profile: UserProfile,
    ): EnergyVerdict {
        val weight = weightOf(profile)
        val surplus = eatenKcal - targetKcal
        val stepsShort = stepBaseline - steps

        return when {
            surplus > 0 -> EnergyVerdict.Over(surplus, optionsFor(surplus, weight))
            stepsShort > 0 -> {
                // Frame the gap as the calories those missing steps represent,
                // so the durations stay in the same unit as the Over case.
                val kcalOfMissingSteps = (stepsShort * 0.0005 * weight).roundToInt()
                EnergyVerdict.ShortOnMovement(
                    stepsShort,
                    optionsFor(kcalOfMissingSteps.coerceAtLeast(30), weight),
                )
            }
            else -> EnergyVerdict.OnTrack(-surplus)
        }
    }
}
