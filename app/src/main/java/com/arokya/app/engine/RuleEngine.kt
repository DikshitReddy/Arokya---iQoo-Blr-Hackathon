package com.arokya.app.engine

import com.arokya.app.data.HealthContext
import com.arokya.app.data.Recommendation

/**
 * THE ENGINE DECIDES. THE AI EXPLAINS.
 *
 * This is deliberately plain Kotlin with zero ML. Every recommendation is
 * traceable to a named rule, which is exactly what the "Why this?" card
 * shows and what you defend in front of judges.
 *
 * Rules are evaluated top-down; the first match wins.
 */
object RuleEngine {

    // Named thresholds, shared with [PlanLever] (which computes the distance
    // to each rule flipping). Change a number here and both stay honest.
    const val STEP_GAP_LIGHT_DINNER = 2500
    const val PROTEIN_TARGET_G = 60 // simple demo target (g/day)
    const val PROTEIN_GAP_SNACK = 30
    const val EVENING_HOUR = 17
    val AFTERNOON_HOURS = 12..16

    fun recommend(c: HealthContext): Recommendation {
        val stepGap = c.stepBaseline - c.stepsToday
        val proteinTarget = PROTEIN_TARGET_G
        val proteinGap = proteinTarget - c.proteinToday
        val pantryNames = c.pantry.map { it.name }

        // R1: Evening + big step shortfall -> lighter, protein-forward dinner
        if (c.hourOfDay >= EVENING_HOUR && stepGap > STEP_GAP_LIGHT_DINNER) {
            val use = pantryNames.filter { it in listOf("Spinach", "Paneer", "Eggs") }
            return Recommendation(
                title = "Lighter, protein-forward dinner",
                message = "You're about ${stepGap} steps below your usual today, " +
                        "so dinner should be lighter and lean on protein. " +
                        "A spinach-paneer egg bowl uses what you already have.",
                reasons = listOf(
                    "Steps today: ${c.stepsToday} vs your baseline ${c.stepBaseline}",
                    "Calories so far: ${c.kcalToday} kcal across ${c.mealsLogged.size} meals",
                    "Protein gap: ${proteinGap.coerceAtLeast(0)} g remaining of $proteinTarget g",
                    "Pantry has: ${use.joinToString()}"
                ),
                ruleId = "R1_EVENING_STEP_SHORTFALL",
                usesPantry = use,
                kcal = 410, proteinG = 32, carbsG = 21,
            )
        }

        // R2: Protein badly behind by afternoon -> protein-first suggestion
        if (c.hourOfDay in AFTERNOON_HOURS && proteinGap > PROTEIN_GAP_SNACK) {
            val use = pantryNames.filter { it in listOf("Curd", "Paneer", "Eggs") }
            return Recommendation(
                title = "Protein-first snack",
                message = "You're ${proteinGap} g behind on protein for the day. " +
                        "Curd with a small portion of paneer closes most of that gap.",
                reasons = listOf(
                    "Protein so far: ${c.proteinToday} g of $proteinTarget g target",
                    "Time of day: afternoon — still room to adjust",
                    "Pantry has: ${use.joinToString()}"
                ),
                ruleId = "R2_PROTEIN_GAP_AFTERNOON",
                usesPantry = use,
                kcal = 220, proteinG = 21, carbsG = 9,
            )
        }

        // R3: Active day -> normal balanced meal, positive reinforcement
        if (stepGap <= 0) {
            return Recommendation(
                title = "Balanced meal — you've earned it",
                message = "You're past your step baseline today, so a normal " +
                        "balanced plate is fine. No restriction needed.",
                reasons = listOf(
                    "Steps today: ${c.stepsToday}, above baseline ${c.stepBaseline}",
                    "Calories so far: ${c.kcalToday} kcal — within a normal day",
                ),
                ruleId = "R3_ACTIVE_DAY_BALANCED",
                kcal = 550, proteinG = 25, carbsG = 60,
            )
        }

        // R0: Default — gentle, goal-aligned suggestion
        return Recommendation(
            title = "Steady as planned",
            message = "You're roughly on track. Keep the next meal close to " +
                    "your usual portion and favour protein where you can.",
            reasons = listOf(
                "Steps today: ${c.stepsToday} vs baseline ${c.stepBaseline}",
                "Calories so far: ${c.kcalToday} kcal",
                "Goal: ${c.goal}"
            ),
            ruleId = "R0_DEFAULT_ON_TRACK",
            kcal = 500, proteinG = 22, carbsG = 55,
        )
    }

    /**
     * The ambient trigger's question: is there anything WORTH saying right now?
     * Returns a recommendation only when a non-default rule fires — this is
     * what makes the nudge feel intelligent instead of spammy.
     */
    fun proactiveCheck(c: HealthContext): Recommendation? {
        val r = recommend(c)
        return if (r.ruleId == "R0_DEFAULT_ON_TRACK") null else r
    }
}
