package com.arokya.app.ml

/**
 * What one on-device inference actually cost. Every field is MEASURED, never
 * estimated: [elapsedMs] is wall-clock around the real call, [tokens] comes
 * from the engine's own counter, and [model]/[backend] describe the engine
 * that produced the answer.
 *
 * These are what the per-result chips show, so a sceptic can see that each
 * individual answer came from this phone rather than take it on faith.
 */
data class InferenceStat(
    val model: String,
    val backend: String,
    val elapsedMs: Long,
    /** -1 when the engine didn't report a count. */
    val tokens: Int = -1,
) {
    val elapsedLabel: String
        get() = if (elapsedMs < 1_000) "$elapsedMs ms" else "%.1fs".format(elapsedMs / 1_000.0)

    /** e.g. "Gemma 4 E2B · CPU · 4.2s · 312 tok" */
    fun summary(): String = buildString {
        append(model)
        append(" · ").append(backend)
        append(" · ").append(elapsedLabel)
        if (tokens >= 0) append(" · ").append(tokens).append(" tok")
    }
}

/**
 * Holds the most recent measurement. Callers read [last] immediately after a
 * suspend call returns and keep it with that specific result — the engine
 * serialises inference, so there's no interleaving to worry about.
 */
object InferenceStats {
    @Volatile
    var last: InferenceStat? = null
        private set

    fun record(model: String, backend: String, elapsedMs: Long, tokens: Int) {
        last = InferenceStat(model, backend, elapsedMs, tokens)
    }
}
