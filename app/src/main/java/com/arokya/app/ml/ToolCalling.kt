package com.arokya.app.ml

import android.content.Context
import android.util.Log
import com.arokya.app.data.HealthContext
import com.arokya.app.reminder.Reminders
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ============================================================
 *  ON-DEVICE TOOL CALLING (prompt-driven, model-agnostic)
 * ============================================================
 *
 * The published litertlm-android artifact this project pins doesn't expose a
 * native function-calling API (its Message type is behind the sample — see
 * MlEndpoints.kt). And native function-calling on a 2B model is unreliable
 * anyway. So tool calling here is PROMPT-DRIVEN: the model is taught a tiny
 * grammar, emits a tool line, Kotlin actually runs the tool, and the result is
 * fed back for the model to finish. This works on LiteRT-LM, the llama.cpp
 * server, or any future model unchanged.
 *
 * The loop is genuinely agentic (bounded to [MAX_STEPS]): the model can chain
 * get_datetime -> set_reminder before answering. Every step is REAL — the
 * "thinking / calling tool / result" trace the UI shows corresponds to actual
 * work, never cosmetic pacing, which is the honesty rule this app holds
 * everywhere.
 *
 * TOOLS:
 *   get_datetime               — the real system clock (no args)
 *   set_reminder(title, when)  — schedules a real AlarmManager reminder
 *
 * Only date/time and reminder intents need tools; ordinary nutrition questions
 * are answered directly (the model emits FINAL immediately), so there's no
 * latency tax on normal chat.
 */

/** One entry in the reasoning trace shown to the user. */
data class TraceStep(val kind: Kind, val text: String) {
    enum class Kind { Thinking, ToolCall, ToolResult, Reminder }
}

/**
 * The result of a tool-calling turn.
 *
 * [awaitingReply] is true when the model asked the user something (e.g. "what
 * time tomorrow?") instead of finishing — the UI keeps the next message on the
 * tool path so the follow-up answer completes the reminder.
 */
data class ToolTurn(
    val answer: String,
    val trace: List<TraceStep>,
    val awaitingReply: Boolean = false,
)

private const val TAG = "ArokyaTools"
private const val MAX_STEPS = 4

/** Detects whether a message even needs tools — keeps normal chat on the fast path. */
fun mightNeedTools(text: String): Boolean {
    val t = text.lowercase()
    val keywords = listOf(
        // date / reminder intents
        "remind", "reminder", "remember to", "tomorrow", "today", "tonight",
        "date", "day", "time", "morning", "evening", "next week",
        "o'clock", "am ", "pm ", "schedule", "alarm", "wake me", "later",
        // health-data intents — so "how many steps", "calories left", "how far
        // did I walk" fetch the real numbers via get_health_data instead of
        // being guessed.
        "step", "walk", "calorie", "kcal", "protein", "carb", "macro",
        "burn", "distance", "how far", "how much have i", "eaten", "logged",
        "budget", "left today", "my weight", "bmi", "streak", "activity",
        // meal-logging intents
        "i ate", "i had", "just ate", "just had", "for breakfast", "for lunch",
        "for dinner", "had a", "log this", "log my", "ate a", "having",
    )
    return keywords.any { t.contains(it) }
}

private fun toolSystemPrompt(context: HealthContext): String =
    "You are Arokya, a friendly on-device health coach. You can use tools when a " +
            "question involves the current date/time, the user's health data, or " +
            "setting a reminder.\n\n" +
            "TOOLS:\n" +
            "- get_health_data : returns the user's REAL numbers right now — steps, " +
            "distance, calories burned, calories eaten and remaining, protein, meals " +
            "logged, goal, usual steps. Call this WHENEVER the user asks anything " +
            "about their steps, activity, calories, macros, weight or progress. " +
            "Never guess these numbers.\n" +
            "- log_meal : records something the user says they ate. Argument: their " +
            "description of the food. Call this whenever the user tells you what " +
            "they had (e.g. 'I had two idlis and sambar', 'just ate a banana'). " +
            "Arokya estimates the calories and logs it.\n" +
            "- get_datetime : returns the current date, time and weekday. Use this " +
            "BEFORE any reminder, or when the user asks what day/time it is.\n" +
            "- set_reminder : schedules a reminder. Arguments: title, and datetime " +
            "in EXACTLY 'yyyy-MM-dd HH:mm' (24-hour). You MUST call get_datetime " +
            "first so you compute the real target time.\n\n" +
            "Reply with EXACTLY ONE line, one of:\n" +
            "THINK: <one short sentence of your reasoning>\n" +
            "CALL: get_health_data\n" +
            "CALL: log_meal | <what the user ate, in their words>\n" +
            "CALL: get_datetime\n" +
            "CALL: set_reminder | <title> | <yyyy-MM-dd HH:mm>\n" +
            "ASK: <a question back to the user>\n" +
            "FINAL: <your natural reply to the user>\n\n" +
            "CRITICAL RULE ABOUT TIME: only call set_reminder when the user gave a " +
            "specific enough time. A vague phrase you can map confidently is fine " +
            "('tomorrow morning' = 07:00, 'tonight' = 20:00, 'afternoon' = 15:00, " +
            "'evening' = 19:00). But if the user gave NO time at all (e.g. just " +
            "'remind me for gym tomorrow'), do NOT guess — emit ASK to request the " +
            "exact time, e.g. 'ASK: What time tomorrow should I remind you?'. Never " +
            "invent a time the user did not imply.\n\n" +
            "Other rules: emit THINK before a CALL to explain why. After a tool " +
            "result is given back, continue. When done, emit FINAL — short and " +
            "conversational, confirming a reminder with its day and time. " +
            "The user's goal is ${context.goal}." +
            languageDirective()

/**
 * Runs the tool loop for [userMessage]. [history] is prior chat (for context),
 * [nowMillis] is passed in so this stays clock-free where it can and the UI
 * owns the timestamp. [onStep] streams the trace live so the UI can show
 * thinking/tool phases as they happen.
 */
suspend fun respondWithTools(
    androidContext: Context,
    history: List<ChatTurn>,
    userMessage: String,
    context: HealthContext,
    nowMillis: Long,
    onStep: (TraceStep) -> Unit,
): ToolTurn {
    val trace = mutableListOf<TraceStep>()
    fun record(step: TraceStep) { trace += step; onStep(step) }

    // The running transcript the model sees: system + prior turns + this
    // message, with tool results appended as we go.
    val convo = StringBuilder()
    history.takeLast(6).forEach { turn ->
        convo.append(if (turn.fromUser) "User: " else "Arokya: ").append(turn.text).append("\n")
    }
    convo.append("User: ").append(userMessage).append("\n")

    var reminderConfirmed: String? = null

    repeat(MAX_STEPS) { step ->
        val prompt = toolSystemPrompt(context) + "\n\nConversation so far:\n" + convo +
                "\nYour next single line:"
        val raw = Ml.llm.respond(
            listOf(ChatTurn(fromUser = true, text = prompt)), context,
        ).trim()
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: raw
        Log.i(TAG, "step $step: $line")

        when {
            line.startsWith("THINK:", true) -> {
                val thought = line.substringAfter(":").trim()
                if (thought.isNotBlank()) record(TraceStep(TraceStep.Kind.Thinking, thought))
                convo.append("Arokya: ").append(line).append("\n")
            }

            line.startsWith("CALL: get_health_data", true) -> {
                record(TraceStep(TraceStep.Kind.ToolCall, "get_health_data()"))
                val result = healthSnapshot(context)
                record(TraceStep(TraceStep.Kind.ToolResult, result))
                convo.append("TOOL_RESULT(get_health_data): ").append(result).append("\n")
            }

            line.startsWith("CALL: log_meal", true) -> {
                val desc = line.substringAfter("log_meal").trim().trimStart('|').trim()
                record(TraceStep(TraceStep.Kind.ToolCall, "log_meal(\"$desc\")"))
                val result = try {
                    val estimate = estimateMeal(desc, context)
                    val stamp = nowMillis
                    val meal = com.arokya.app.data.Meal(
                        name = estimate.name, kcal = estimate.kcal,
                        proteinG = estimate.proteinG, carbsG = estimate.carbsG,
                        fatG = estimate.fatG,
                        timeLabel = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(stamp)),
                        epochMillis = stamp,
                    )
                    when (com.arokya.app.data.Store.addMeal(meal)) {
                        is com.arokya.app.data.Store.LogResult.Duplicate ->
                            "Already logged \"${estimate.name}\" a moment ago — not adding twice."
                        is com.arokya.app.data.Store.LogResult.Failed ->
                            "Couldn't save that meal."
                        is com.arokya.app.data.Store.LogResult.Saved ->
                            "Logged \"${estimate.name}\" — about ${estimate.kcal} kcal, " +
                                    "${estimate.proteinG} g protein (estimated)."
                    }
                } catch (e: Exception) {
                    e.message ?: "Couldn't log that."
                }
                record(TraceStep(TraceStep.Kind.ToolResult, result))
                convo.append("TOOL_RESULT(log_meal): ").append(result).append("\n")
            }

            line.startsWith("CALL: get_datetime", true) -> {
                record(TraceStep(TraceStep.Kind.ToolCall, "get_datetime()"))
                val result = currentDateTime(nowMillis)
                record(TraceStep(TraceStep.Kind.ToolResult, result))
                convo.append("TOOL_RESULT(get_datetime): ").append(result).append("\n")
            }

            line.startsWith("CALL: set_reminder", true) -> {
                val parts = line.substringAfter("set_reminder").trim()
                    .trimStart('|').split("|").map { it.trim() }
                val title = parts.getOrNull(0).orEmpty().ifBlank { "Reminder" }
                val whenStr = parts.getOrNull(1).orEmpty()
                record(TraceStep(TraceStep.Kind.ToolCall, "set_reminder(\"$title\", \"$whenStr\")"))

                val triggerAt = parseDateTime(whenStr, nowMillis)
                if (triggerAt == null || triggerAt <= nowMillis) {
                    val err = "Couldn't set that — the time was unclear or already past."
                    record(TraceStep(TraceStep.Kind.ToolResult, err))
                    convo.append("TOOL_RESULT(set_reminder): ").append(err).append("\n")
                } else {
                    Reminders.schedule(androidContext, title, triggerAt, nowMillis)
                    val confirm = "Reminder set: \"$title\" — ${Reminders.format(triggerAt)}."
                    reminderConfirmed = confirm
                    record(TraceStep(TraceStep.Kind.Reminder, confirm))
                    convo.append("TOOL_RESULT(set_reminder): ").append(confirm).append("\n")
                }
            }

            line.startsWith("ASK:", true) -> {
                // The model needs more from the user (usually a specific time).
                // End the turn with the question; the UI keeps the next reply on
                // the tool path so the reminder completes.
                val question = line.substringAfter(":").trim()
                return ToolTurn(
                    question.ifBlank { "What time should I set that for?" },
                    trace, awaitingReply = true,
                )
            }

            line.startsWith("FINAL:", true) -> {
                val answer = line.substringAfter(":").trim()
                return ToolTurn(answer.ifBlank { reminderConfirmed ?: "Done." }, trace)
            }

            else -> {
                // Model didn't follow the grammar — treat the whole reply as the
                // answer rather than looping pointlessly.
                return ToolTurn(raw, trace)
            }
        }
    }

    // Hit the step cap. If a reminder was set, that's the useful outcome.
    return ToolTurn(reminderConfirmed ?: "Done.", trace)
}

// ---- the actual tools ----

/**
 * The user's REAL numbers right now, pulled from live state — the same
 * [Store.stepsToday] the pedometer writes and the same targets the Meals and
 * Activity tabs show. Every value here is measured or deterministic, never a
 * model guess.
 */
private fun healthSnapshot(context: HealthContext): String {
    val profile = com.arokya.app.data.Store.profile
    val targets = com.arokya.app.engine.NutritionTargets.forProfile(profile)
    val burned = com.arokya.app.engine.ActivityBurn.kcalFromSteps(context.stepsToday, profile)
    val distanceKm = com.arokya.app.engine.ActivityBurn.distanceKm(context.stepsToday, profile)
    val kcalLeft = targets.kcal - context.kcalToday
    val proteinLeft = targets.proteinG - context.proteinToday

    return buildString {
        append("steps today ${context.stepsToday} (usual ${context.stepBaseline}); ")
        append("distance ${"%.2f".format(distanceKm)} km; ")
        append("calories burned by walking ~$burned kcal; ")
        append("eaten ${context.kcalToday} of ${targets.kcal} kcal ")
        append("(${kcalLeft.coerceAtLeast(0)} left" + (if (kcalLeft < 0) ", ${-kcalLeft} over" else "") + "); ")
        append("protein ${context.proteinToday} of ${targets.proteinG} g ")
        append("(${proteinLeft.coerceAtLeast(0)} g left); ")
        append("${context.mealsLogged.size} meals logged today; ")
        append("goal ${context.goal}")
        profile.bmi?.let { append("; BMI ${"%.1f".format(it)}") }
    }
}

private fun currentDateTime(nowMillis: Long): String {
    val fmt = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault())
    return fmt.format(Date(nowMillis))
}

/**
 * Parses the model's 'yyyy-MM-dd HH:mm'. Lenient about a missing time (defaults
 * to 9:00) and accepts a couple of nearby formats a small model tends to emit.
 */
private fun parseDateTime(raw: String, nowMillis: Long): Long? {
    val cleaned = raw.trim().replace("T", " ")
    val patterns = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd H:mm", "yyyy-MM-dd", "yyyy/MM/dd HH:mm")
    for (p in patterns) {
        try {
            val sdf = SimpleDateFormat(p, Locale.getDefault())
            sdf.isLenient = false
            val date = sdf.parse(cleaned) ?: continue
            val cal = Calendar.getInstance().apply {
                time = date
                if (!p.contains("H")) { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0) }
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        } catch (_: Exception) { /* try next */ }
    }
    return null
}
