package com.arokya.app.ml

import com.arokya.app.data.Languages
import com.arokya.app.data.Store

/**
 * ============================================================
 *  THE ONE LANGUAGE DIRECTIVE
 * ============================================================
 * There is deliberately NO per-language prompt anywhere in this app. Every
 * AI call site — chat, meal analysis, fridge vision, lab reading, recipe
 * suggestions, proactive nudges, meal planning — appends the SAME clause
 * below, with the user's saved preference substituted in.
 *
 * Adding a language is therefore a one-line change in [Languages], not a new
 * prompt to write and keep in sync.
 */

/** The user's current choice, read fresh so a profile edit takes effect immediately. */
fun currentLanguageName(): String = Languages.byCode(Store.profile.languageCode).aiName

/**
 * Appended to every free-text prompt. Empty for English, because telling a
 * model to "respond in English" wastes tokens a 2B model needs for the actual
 * question — and small models degrade noticeably when the prompt front-loads
 * instructions before the task.
 */
fun languageDirective(language: String = currentLanguageName()): String {
    if (language.equals("English", ignoreCase = true)) return ""
    return "\n\nThe user's preferred language is $language. Always respond to the " +
            "user naturally and conversationally in $language. All health guidance, " +
            "food recommendations, fitness suggestions, reminders, explanations, " +
            "proactive messages, and assistant conversations must be written in " +
            "$language. Keep commonly understood technical terms, numbers, calories, " +
            "units, food names, and exercise names unchanged when translating them " +
            "would reduce clarity. If the user explicitly asks you to switch " +
            "languages during a conversation, follow their request."
}

/**
 * The language directive for prompts whose answer is PARSED by field label
 * (`NAME:`, `CALORIES:`, `TITLE:` …) or pipe position.
 *
 * Without the extra sentence a model asked to reply in Telugu will helpfully
 * translate the labels too — `NAME:` becomes `పేరు:` — and every parser in
 * this app silently returns an empty result. The values get translated; the
 * labels must not.
 */
fun structuredLanguageDirective(language: String = currentLanguageName()): String {
    val base = languageDirective(language)
    if (base.isEmpty()) return ""
    return base + " CRITICAL: keep the field labels and the response format " +
            "EXACTLY as written in English (for example `NAME:`, `CALORIES:`, " +
            "`TITLE:`, `BODY:`) — translate only the values that follow them."
}
