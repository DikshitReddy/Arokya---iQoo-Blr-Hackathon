package com.arokya.app.data

import java.util.Locale

/**
 * A language Arokya can talk to the user in.
 *
 * [bcp47] drives Android's speech recognition and text-to-speech; [aiName] is
 * what gets injected into the AI system prompt. There is deliberately ONE
 * prompt in this app with this name substituted in — never a per-language
 * prompt (see ml/Prompts.kt).
 */
data class AppLanguage(
    /** Stable id persisted in the profile. Never localise this. */
    val code: String,
    /** Shown in the picker's second line, and used in prompts. */
    val englishName: String,
    /** Shown in the picker's first line, in its own script. */
    val nativeName: String,
    val bcp47: String,
) {
    val aiName: String get() = englishName
    fun locale(): Locale = Locale.forLanguageTag(bcp47)
    /** e.g. "हिन्दी · Hindi" */
    fun label(): String =
        if (nativeName == englishName) englishName else "$nativeName · $englishName"
}

/**
 * The languages offered during setup. Covers the major Indian languages plus
 * the rest of the 22 scheduled languages, so the picker doesn't quietly
 * exclude someone's mother tongue.
 *
 * ORDER: English first (the app's own UI language), then by rough speaker
 * count, so the common choices need no scrolling.
 */
object Languages {
    val ALL: List<AppLanguage> = listOf(
        AppLanguage("en", "English", "English", "en-IN"),
        AppLanguage("hi", "Hindi", "हिन्दी", "hi-IN"),
        AppLanguage("bn", "Bengali", "বাংলা", "bn-IN"),
        AppLanguage("te", "Telugu", "తెలుగు", "te-IN"),
        AppLanguage("mr", "Marathi", "मराठी", "mr-IN"),
        AppLanguage("ta", "Tamil", "தமிழ்", "ta-IN"),
        AppLanguage("gu", "Gujarati", "ગુજરાતી", "gu-IN"),
        AppLanguage("kn", "Kannada", "ಕನ್ನಡ", "kn-IN"),
        AppLanguage("ml", "Malayalam", "മലയാളം", "ml-IN"),
        AppLanguage("pa", "Punjabi", "ਪੰਜਾਬੀ", "pa-IN"),
        AppLanguage("or", "Odia", "ଓଡ଼ିଆ", "or-IN"),
        AppLanguage("as", "Assamese", "অসমীয়া", "as-IN"),
        AppLanguage("ur", "Urdu", "اردو", "ur-IN"),
        AppLanguage("kok", "Konkani", "कोंकणी", "kok-IN"),
        AppLanguage("mai", "Maithili", "मैथिली", "mai-IN"),
        AppLanguage("sd", "Sindhi", "سنڌي", "sd-IN"),
        AppLanguage("ne", "Nepali", "नेपाली", "ne-NP"),
        AppLanguage("sa", "Sanskrit", "संस्कृतम्", "sa-IN"),
        AppLanguage("mni", "Manipuri", "ꯃꯤꯇꯩꯂꯣꯟ", "mni-IN"),
        AppLanguage("brx", "Bodo", "बर'", "brx-IN"),
        AppLanguage("sat", "Santali", "ᱥᱟᱱᱛᱟᱲᱤ", "sat-IN"),
        AppLanguage("doi", "Dogri", "डोगरी", "doi-IN"),
        AppLanguage("ks", "Kashmiri", "کٲشُر", "ks-IN"),
    )

    val DEFAULT: AppLanguage = ALL.first()

    /** Unknown/blank codes fall back to English rather than crashing an old profile. */
    fun byCode(code: String?): AppLanguage =
        ALL.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DEFAULT
}
