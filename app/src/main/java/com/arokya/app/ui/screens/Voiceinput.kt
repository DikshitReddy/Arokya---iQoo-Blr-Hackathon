package com.arokya.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * REAL ML ENDPOINT #1 — on-device speech recognition.
 *
 * Wraps Android's SpeechRecognizer with live partial results and
 * tap-to-stop. Prefers the on-device model (EXTRA_PREFER_OFFLINE) to
 * keep the privacy story true; if the offline model is missing for
 * this language, it retries once allowing the online recognizer.
 *
 * All methods must be called from the main thread (Compose callbacks are).
 */
class VoiceInput(private val context: Context) {

    interface Listener {
        /** Words as they are being spoken — update the live bubble. */
        fun onPartial(text: String)
        /** The final utterance — send this to the engine. */
        fun onFinal(text: String)
        /** Human-readable problem — show it, return to idle. */
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var triedOnlineFallback = false

    /** False on devices with no speech service at all (rare). */
    val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(l: Listener) {
        listener = l
        triedOnlineFallback = false
        startInternal(preferOffline = true)
    }

    /** Tap-to-stop: finalizes whatever was heard so far -> onFinal. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        listener = null
    }

    // ------------------------------------------------------------

    private fun startInternal(preferOffline: Boolean) {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) listener?.onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) listener?.onError("Didn't catch that \u2014 try again")
                else listener?.onFinal(text)
            }

            override fun onError(error: Int) {
                // The offline recognizer failed (model missing, service
                // disconnected, server error...)? Retry once with the online
                // recognizer before giving up. Don't retry for problems the
                // online path can't fix: no permission, busy mic, or the user
                // simply not speaking.
                val dontRetry = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (preferOffline && !triedOnlineFallback && !dontRetry) {
                    triedOnlineFallback = true
                    startInternal(preferOffline = false)
                    return
                }
                listener?.onError(errorText(error))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // Listen in the language the user CHOSE, not the phone's locale:
            // someone with an English phone who picked Telugu wants to speak
            // Telugu. Falls back to English via Languages.byCode.
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                com.arokya.app.data.Languages
                    .byCode(com.arokya.app.data.Store.profile.languageCode).bcp47
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        r.startListening(intent)
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "Didn't catch that \u2014 try again"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission needed"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "This language needs a network right now"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "One moment \u2014 mic is busy"
        10 /* TOO_MANY_REQUESTS */ ->
            "Speech service is overloaded \u2014 wait a moment"
        11 /* SERVER_DISCONNECTED */ ->
            "Speech service dropped \u2014 tap the mic again"
        12 /* LANGUAGE_NOT_SUPPORTED */,
        13 /* LANGUAGE_UNAVAILABLE */ ->
            "This language isn't available for speech on this device"
        else -> "Speech error ($code) \u2014 try again"
    }
}