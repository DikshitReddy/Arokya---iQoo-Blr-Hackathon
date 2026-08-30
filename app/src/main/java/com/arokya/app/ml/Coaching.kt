package com.arokya.app.ml

import com.arokya.app.data.HealthContext
import com.arokya.app.engine.MealSlot
import com.arokya.app.engine.RemainingBudget
import java.io.IOException

/**
 * ============================================================
 *  PROACTIVE NUDGE COPY + NEXT-MEAL PLAN
 * ============================================================
 * Both ride the existing [Llm] endpoint rather than adding a backend, so they
 * work unchanged on LiteRT-LM or the llama.cpp server — see MlEndpoints.kt.
 *
 * Same split of responsibility as everywhere else in this app: the ENGINE
 * decides whether there is anything to say and owns every number
 * (RuleEngine, DayBudget); the model only chooses words and names a dish.
 */

/**
 * Pulls the first real number out of a model's field: "about 420 kcal" -> 420,
 * "12.5 g" -> 12, "350-400" -> 350.
 *
 * NOTE: the older parsers (`parseMealAnalysis` in MlEndpoints.kt and
 * `suggestRecipe` in Scan.kt) use `filter { it.isDigit() }`, which turns
 * "350-400" into 350400. New code uses this instead; those two call sites are
 * still worth fixing separately.
 */
internal fun firstNumber(raw: String): Int =
    Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: 0

/** "NAME: x" lines -> {"NAME": "x"}. Shared by both parsers below. */
private fun labeledFields(reply: String): Map<String, String> =
    reply.lines().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null
        else line.substring(0, idx).trim().uppercase() to line.substring(idx + 1).trim()
    }.toMap()

// ---------------------------------------------------------------------------
//  Notification copy
// ---------------------------------------------------------------------------

/** The two lines a proactive notification needs. */
data class NudgeCopy(val title: String, val body: String)

/**
 * Rotated per fire so a user who gets several nudges in a row doesn't read the
 * same sentence shape every time. Deterministic input, varied output.
 */
private val NUDGE_TONES = listOf(
    "warm and encouraging",
    "curious, like a friend checking in",
    "practical and matter-of-fact",
    "lightly playful, but never gimmicky",
)

private fun nudgeCopyPrompt(
    context: HealthContext,
    slot: MealSlot,
    remaining: RemainingBudget,
    tone: String,
): String =
    "Write a phone notification for a nutrition app. It is ${slot.partOfDay}, and " +
            "the user's next meal is ${slot.label.lowercase()}.\n\n" +
            "About them: goal ${context.goal}; diet " +
            "${context.dietTags.joinToString().ifBlank { "unspecified" }}; " +
            "${context.stepsToday} steps today against a usual ${context.stepBaseline}; " +
            "${context.mealsLogged.size} meals logged so far" +
            (if (context.mealsLogged.isNotEmpty())
                " (${context.mealsLogged.joinToString { it.name }})" else "") +
            "; ${remaining.summary()}." +
            labFlagsClause(context.labFindings) + "\n\n" +
            "Tone: $tone. Do NOT greet them by name. Do NOT give the actual meal " +
            "advice here — the only goal is to earn a tap so the app can show them a " +
            "full plan. End the body with a short question offering to plan their " +
            "${slot.label.lowercase()}.\n\n" +
            "Respond in EXACTLY this format, two lines, no extra commentary:\n" +
            "TITLE: <at most 6 words>\n" +
            "BODY: <one sentence, at most 22 words>" + structuredLanguageDirective()

/**
 * Notification copy for right now. Throws on any model failure — the caller
 * ([com.arokya.app.nudge.NudgeWorker]) then falls back to the rule engine's own
 * message. That fallback is the ENGINE speaking, not canned text pretending to
 * be a model reply, so the no-mock rule still holds.
 */
suspend fun generateNudgeCopy(
    context: HealthContext,
    slot: MealSlot,
    remaining: RemainingBudget,
    rotation: Int,
): NudgeCopy {
    val tone = NUDGE_TONES[Math.floorMod(rotation, NUDGE_TONES.size)]
    val reply = Ml.llm.respond(
        listOf(ChatTurn(fromUser = true, text = nudgeCopyPrompt(context, slot, remaining, tone))),
        context,
    )
    val fields = labeledFields(reply)

    // A small model sometimes drops the labels and just writes a sentence.
    // Rather than fail the whole nudge, treat a bare reply as the body.
    val body = fields["BODY"].orEmpty().trim().ifBlank {
        reply.lines().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }
    if (body.isBlank()) throw IOException("Model returned no usable notification text.")

    val title = fields["TITLE"].orEmpty().trim()
        .ifBlank { "Arokya · ${slot.label}" }

    return NudgeCopy(title = title, body = body)
}

// ---------------------------------------------------------------------------
//  Next-meal plan
// ---------------------------------------------------------------------------

/** One concrete meal the model proposes for the slot the user is heading into. */
data class NextMealPlan(
    val mealName: String,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    /** Why this dish, given their day so far. */
    val why: String,
    /** How to actually make it. */
    val prep: String,
    /** Pantry items the dish leans on, echoed back by the model. */
    val usesPantry: List<String>,
)

private fun nextMealPrompt(
    context: HealthContext,
    slot: MealSlot,
    remaining: RemainingBudget,
    recentHistory: String,
): String =
    "Plan ONE ${slot.label.lowercase()} for a nutrition app user.\n\n" +
            "Their day so far: eaten ${context.kcalToday} kcal and " +
            "${context.proteinToday} g protein across ${context.mealsLogged.size} meals" +
            (if (context.mealsLogged.isNotEmpty())
                " (${context.mealsLogged.joinToString { "${it.name} ${it.kcal} kcal" }})" else "") +
            ". Budget still open for the day: ${remaining.summary()}. " +
            "Steps ${context.stepsToday} against a usual ${context.stepBaseline}. " +
            "Goal: ${context.goal}. Diet: " +
            "${context.dietTags.joinToString().ifBlank { "unspecified" }}. " +
            "In their kitchen: " +
            "${context.pantry.joinToString { it.name }.ifBlank { "nothing listed" }}." +
            (if (recentHistory.isNotBlank())
                " What they ate on recent days — $recentHistory. Vary from this so " +
                        "they don't eat the same thing repeatedly, and correct any " +
                        "pattern (e.g. consistently low protein or heavy dinners)." else "") +
            labFlagsClause(context.labFindings) + "\n\n" +
            "Pick a real, simple dish that fits INSIDE the remaining budget above, " +
            "respects their diet, and leans on what is already in their kitchen. " +
            "Do not repeat a dish they already ate today. Estimate nutrition for the " +
            "actual portion you are proposing — not round generic numbers.\n\n" +
            "Respond in EXACTLY this format, one field per line, no extra commentary:\n" +
            "NAME: <dish name>\n" +
            "CALORIES: <number> kcal\n" +
            "PROTEIN: <number> g\n" +
            "CARBS: <number> g\n" +
            "FAT: <number> g\n" +
            "USES: <comma-separated items from their kitchen, or NONE>\n" +
            "WHY: <one sentence: why this dish for them, right now>\n" +
            "PREP: <one or two sentences: how to make it>" + structuredLanguageDirective()

/** Plans the meal the user is heading into. Live only — failures surface as errors. */
suspend fun planNextMeal(
    context: HealthContext,
    slot: MealSlot,
    remaining: RemainingBudget,
): NextMealPlan {
    // Pull recent days so the plan reflects real eating patterns, not just today.
    val recentHistory = com.arokya.app.data.Store.recentEatingSummary(days = 3)
    val reply = Ml.llm.respond(
        listOf(ChatTurn(fromUser = true, text = nextMealPrompt(context, slot, remaining, recentHistory))),
        context,
    )
    val fields = labeledFields(reply)
    fun field(key: String) = fields[key].orEmpty().trim()

    val name = field("NAME")
    if (name.isBlank()) {
        throw IOException(
            "The model didn't name a dish. Tap retry, or check the on-device model is installed."
        )
    }
    val uses = field("USES")
        .takeUnless { it.isBlank() || it.equals("NONE", ignoreCase = true) }
        ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        .orEmpty()

    return NextMealPlan(
        mealName = name,
        kcal = firstNumber(field("CALORIES")),
        proteinG = firstNumber(field("PROTEIN")),
        carbsG = firstNumber(field("CARBS")),
        fatG = firstNumber(field("FAT")),
        why = field("WHY"),
        prep = field("PREP"),
        usesPantry = uses,
    )
}

// ---------------------------------------------------------------------------
//  Log a meal from a spoken/typed description
// ---------------------------------------------------------------------------

/**
 * What the model estimated from a free-text description like "two idlis and
 * sambar". The macros are an LLM ESTIMATE — flagged as such at the call site —
 * unlike the deterministic engine numbers elsewhere in the app.
 */
data class MealEstimate(
    val name: String,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
)

private fun mealEstimatePrompt(description: String, c: HealthContext): String =
    "The user is logging something they ate, described in their own words: " +
            "\"$description\".\n\n" +
            "Identify the dish and estimate the nutrition for the portion they " +
            "describe (assume one normal serving if no quantity is given). Diet: " +
            "${c.dietTags.joinToString().ifBlank { "unspecified" }}.\n\n" +
            "Respond in EXACTLY this format, one field per line, no extra commentary:\n" +
            "NAME: <short dish name>\n" +
            "CALORIES: <number> kcal\n" +
            "PROTEIN: <number> g\n" +
            "CARBS: <number> g\n" +
            "FAT: <number> g\n\n" +
            "If the text does not describe food, respond with exactly: NOT_FOOD" +
            structuredLanguageDirective()

/** Estimates macros for a described meal. Throws if the text isn't food. */
suspend fun estimateMeal(description: String, context: HealthContext): MealEstimate {
    val reply = Ml.llm.respond(
        listOf(ChatTurn(fromUser = true, text = mealEstimatePrompt(description, context))),
        context,
    )
    if (reply.trim().equals("NOT_FOOD", ignoreCase = true)) {
        throw IOException("That didn't sound like a meal.")
    }
    val fields = labeledFields(reply)
    val name = fields["NAME"].orEmpty().trim()
    if (name.isBlank()) throw IOException("Couldn't work out what dish that was.")
    return MealEstimate(
        name = name,
        kcal = firstNumber(fields["CALORIES"].orEmpty()),
        proteinG = firstNumber(fields["PROTEIN"].orEmpty()),
        carbsG = firstNumber(fields["CARBS"].orEmpty()),
        fatG = firstNumber(fields["FAT"].orEmpty()),
    )
}
