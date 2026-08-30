package com.arokya.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.arokya.app.data.Languages
import com.arokya.app.data.Store
import java.util.Locale

/**
 * Text-to-speech with real pause/resume.
 *
 * Android's TextToSpeech has NO pause: [TextToSpeech.stop] cancels outright and
 * there's no API to report or seek to a position mid-utterance. So instead of
 * speaking one long blob, this splits the reply into sentences and queues them
 * as separate utterances tagged with their index. Pausing stops the queue and
 * remembers which sentence was playing; resuming re-speaks from the START of
 * that sentence — which is what a listener actually wants, rather than
 * resuming mid-word even if that were possible.
 */
class Speaker(context: Context) {
    companion object { private const val TAG = "ArokyaSpeaker" }

    private var ready = false

    /** Sentences of the current reply, in order. */
    private var chunks: List<String> = emptyList()
    /** Index of the sentence currently being spoken. */
    private var currentIndex = 0
    /** True between pause() and resume(), so stop callbacks don't look like completion. */
    private var paused = false
    /** True while a deliberate stop is in flight, for the same reason. */
    private var stopping = false

    /** Called from a background thread. */
    var onStart: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            // The engine only accepts a language once initialised, so the call
            // in init{} below is too early to stick on its own.
            applyLanguage()
            Log.i(TAG, "TTS ready")
        } else {
            Log.w(TAG, "TTS init failed (status=$status) — replies will be text only")
        }
    }

    /** Follows the profile's preferred language, falling back when the voice is missing. */
    fun applyLanguage() {
        val wanted = Languages.byCode(Store.profile.languageCode).locale()
        val result = tts.setLanguage(wanted)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // No voice pack for this language on this phone. Indian English is
            // the least-wrong fallback; the text itself is still translated.
            Log.w(TAG, "No voice for ${wanted.toLanguageTag()} — falling back to en-IN")
            tts.language = Locale("en", "IN")
        } else {
            Log.i(TAG, "Voice set to ${wanted.toLanguageTag()}")
        }
    }

    init {
        applyLanguage()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                indexOf(utteranceId)?.let { currentIndex = it }
                if (!paused && !stopping) this@Speaker.onStart?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                val index = indexOf(utteranceId) ?: return
                // Only the LAST sentence finishing means the reply is over.
                // A pause/stop also fires this for the cancelled utterance,
                // which must not be mistaken for finishing.
                if (!paused && !stopping && index >= chunks.lastIndex) {
                    Log.i(TAG, "Finished all ${chunks.size} sentence(s)")
                    this@Speaker.onDone?.invoke()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = failed()
            override fun onError(utteranceId: String?, errorCode: Int) = failed()

            private fun failed() {
                if (!paused && !stopping) this@Speaker.onDone?.invoke()
            }
        })
    }

    private fun indexOf(utteranceId: String?): Int? =
        utteranceId?.substringAfterLast('-')?.toIntOrNull()

    /**
     * Splits on sentence enders, keeping the punctuation. Chunks that are still
     * huge (a model writing one long run-on) are left alone — better a coarse
     * pause point than mangling the text.
     */
    private fun splitSentences(text: String): List<String> =
        Regex("(?<=[.!?۔।])\\s+")
            .split(text)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(text) }

    /** Speaks [text] from the beginning. Returns false if TTS never became ready. */
    fun say(text: String): Boolean {
        if (!ready) {
            Log.w(TAG, "say() called before TTS was ready")
            return false
        }
        chunks = splitSentences(text)
        Log.i(TAG, "Speaking ${chunks.size} sentence(s)")
        return speakFrom(0)
    }

    private fun speakFrom(index: Int): Boolean {
        if (!ready || index > chunks.lastIndex) return false
        paused = false
        stopping = false
        currentIndex = index
        chunks.drop(index).forEachIndexed { offset, sentence ->
            tts.speak(
                sentence,
                if (offset == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "arokya-${index + offset}",
            )
        }
        return true
    }

    /** Halts speech, keeping the place so [resume] can carry on. */
    fun pause() {
        if (!ready) return
        paused = true
        tts.stop()
        Log.i(TAG, "Paused at sentence ${currentIndex + 1}/${chunks.size}")
    }

    /** Continues from the start of the sentence that was interrupted. */
    fun resume(): Boolean {
        if (!ready || chunks.isEmpty()) return false
        Log.i(TAG, "Resuming at sentence ${currentIndex + 1}/${chunks.size}")
        return speakFrom(currentIndex)
    }

    /** Abandons the rest of the reply entirely. */
    fun stop() {
        if (!ready) return
        stopping = true
        paused = false
        tts.stop()
        chunks = emptyList()
        currentIndex = 0
        Log.i(TAG, "Stopped")
        onDone?.invoke()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
