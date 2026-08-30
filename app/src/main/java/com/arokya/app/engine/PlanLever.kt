package com.arokya.app.engine

import com.arokya.app.data.HealthContext

/**
 * THE ENGINE'S THRESHOLDS, INVERTED.
 *
 * [RuleEngine] answers "what should the user do, given today?" — this answers
 * the question a verdict never does: "what would change the engine's mind?"
 * Because every rule is a plain threshold, the distance to the nearest one is
 * computable exactly. An LLM-only coach cannot make this promise; a rule
 * engine can, and this is where the user actually sees that difference.
 *
 * Every number here MUST mirror [RuleEngine]'s constants — that's why the
 * thresholds live as named constants on RuleEngine, not literals in two files.
 */
object PlanLever {

    /**
     * One actionable sentence. [emphasis] is true when the user is close
     * enough to a threshold that acting on it today is realistic.
     */
    data class Lever(val text: String, val emphasis: Boolean)

    fun forContext(c: HealthContext): Lever {
        val stepGap = c.stepBaseline - c.stepsToday
        val proteinGap = RuleEngine.PROTEIN_TARGET_G - c.proteinToday

        return when {
            // Mirrors R3: past baseline, nothing to earn back.
            stepGap <= 0 -> Lever(
                "You're past your usual steps — tonight stays a normal balanced plate.",
                emphasis = false,
            )

            // Mirrors R2's window: an afternoon protein hole the user can
            // close right now, before the nudge fires.
            c.hourOfDay in RuleEngine.AFTERNOON_HOURS && proteinGap > RuleEngine.PROTEIN_GAP_SNACK -> Lever(
                "Log ${proteinGap - RuleEngine.PROTEIN_GAP_SNACK} g more protein " +
                        "and this afternoon's protein-first nudge stands down.",
                emphasis = true,
            )

            // Mirrors R1's threshold: the exact step count that flips tonight's
            // suggestion from a lighter dinner back to a normal plate.
            stepGap > RuleEngine.STEP_GAP_LIGHT_DINNER -> Lever(
                "%,d more steps and tonight's plan changes from a lighter dinner to a normal plate."
                    .format(stepGap - RuleEngine.STEP_GAP_LIGHT_DINNER),
                emphasis = true,
            )

            else -> Lever(
                "Within %,d steps of your usual — tonight's plan holds as-is.".format(stepGap),
                emphasis = false,
            )
        }
    }
}
