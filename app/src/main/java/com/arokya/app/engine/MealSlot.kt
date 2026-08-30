package com.arokya.app.engine

import com.arokya.app.data.Meal
import java.util.Calendar

/**
 * Which meal the user is heading into right now. Plain clock arithmetic, no
 * model — the nudge copy and the plan screen both key off this, so the wording
 * ("dinner", "breakfast") is decided here once rather than guessed twice.
 */
enum class MealSlot(val label: String, val partOfDay: String) {
    Breakfast("Breakfast", "morning"),
    Lunch("Lunch", "midday"),
    Snack("Snack", "afternoon"),
    Dinner("Dinner", "evening"),
    LateNight("Late bite", "late night");

    companion object {
        fun forHour(hour: Int): MealSlot = when (hour) {
            in 5..10 -> Breakfast
            in 11..15 -> Lunch
            in 16..18 -> Snack
            in 19..23 -> Dinner
            else -> LateNight
        }

        fun now(): MealSlot =
            forHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    }

    /** The clock hours this slot spans, or null for the wrap-around late slot. */
    val hourRange: IntRange?
        get() = when (this) {
            Breakfast -> 5..10
            Lunch -> 11..15
            Snack -> 16..18
            Dinner -> 19..23
            LateNight -> null
        }

    /** True when [meals] already contains one logged during this slot's hours today. */
    fun isLoggedIn(meals: List<Meal>): Boolean {
        val range = hourRange ?: return true // don't prompt for the late slot
        return meals.any { m ->
            if (m.epochMillis <= 0) return@any false
            val cal = Calendar.getInstance().apply { timeInMillis = m.epochMillis }
            cal.get(Calendar.HOUR_OF_DAY) in range
        }
    }

    /** Main meals worth proactively asking about (Snack/LateNight aren't). */
    val isMainMeal: Boolean get() = this == Breakfast || this == Lunch || this == Dinner
}

/**
 * What's left of the day's budget after everything logged so far. Negative
 * values are real and meaningful — they mean the user is already over.
 */
data class RemainingBudget(
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val targets: DailyTargets,
) {
    val overBudget: Boolean get() = kcal < 0

    /** Reads as "820 kcal, 34 g protein left" — used verbatim in prompts. */
    fun summary(): String =
        if (overBudget) "${-kcal} kcal OVER budget, ${proteinG.coerceAtLeast(0)} g protein still short"
        else "$kcal kcal and ${proteinG.coerceAtLeast(0)} g protein left"
}

/** THE ENGINE DECIDES — subtraction, not estimation. */
object DayBudget {
    fun remaining(targets: DailyTargets, meals: List<Meal>): RemainingBudget =
        RemainingBudget(
            kcal = targets.kcal - meals.sumOf { it.kcal },
            proteinG = targets.proteinG - meals.sumOf { it.proteinG },
            carbsG = targets.carbsG - meals.sumOf { it.carbsG },
            fatG = targets.fatG - meals.sumOf { it.fatG },
            targets = targets,
        )
}
