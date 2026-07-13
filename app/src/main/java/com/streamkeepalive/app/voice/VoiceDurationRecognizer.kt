package com.streamkeepalive.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

private val NUMBER_REGEX = Regex("\\d+")
private const val MIN_SECONDS = 10
private const val MAX_SECONDS = 240

private val ONES_AND_TEENS = mapOf(
    "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
    "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
    "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
    "eighteen" to 18, "nineteen" to 19
)
private val TENS = mapOf(
    "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
    "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
)

/**
 * "20", "twenty", and "twenty seconds" should all resolve to 20 — the speech engine
 * doesn't reliably convert spoken numbers to digits unless a unit word follows them,
 * so a plain digit regex alone misses bare number words like "twenty".
 */
private fun parseSpokenNumber(text: String): Int? {
    NUMBER_REGEX.find(text)?.value?.toIntOrNull()?.let { return it }

    var total = 0
    var current = 0
    var foundAny = false
    for (word in text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }) {
        when {
            word == "hundred" -> {
                current = (if (current == 0) 1 else current) * 100
                foundAny = true
            }
            ONES_AND_TEENS.containsKey(word) -> {
                current += ONES_AND_TEENS.getValue(word)
                foundAny = true
            }
            TENS.containsKey(word) -> {
                current += TENS.getValue(word)
                foundAny = true
            }
            else -> {} // ignore filler words like "and", "seconds"
        }
    }
    total += current
    return if (foundAny) total else null
}

/**
 * Listens for a single spoken number in [MIN_SECONDS, MAX_SECONDS] (commercial-break
 * length) and reports it as soon as recognized — acts on partial results so it doesn't
 * wait for silence/end-of-utterance once a valid number has already been heard.
 */
class VoiceDurationRecognizer(context: Context) {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null

    /** [onDone] always fires eventually (recognized, error, or gave up) so callers can reset UI state. */
    fun start(onRecognized: (Int) -> Unit, onDone: () -> Unit) {
        val r = recognizer
        if (r == null) {
            onDone()
            return
        }
        var handled = false

        fun tryHandle(bundle: Bundle) {
            if (handled) return
            val matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            for (text in matches) {
                val number = parseSpokenNumber(text)
                if (number != null && number in MIN_SECONDS..MAX_SECONDS) {
                    handled = true
                    r.stopListening()
                    onRecognized(number)
                    return
                }
            }
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) = tryHandle(partialResults)
            override fun onResults(results: Bundle) {
                tryHandle(results)
                if (!handled) onDone()
            }
            override fun onError(error: Int) {
                if (!handled) onDone()
            }
            override fun onEndOfSpeech() {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        r.startListening(intent)
    }

    fun cancel() {
        recognizer?.stopListening()
        recognizer?.cancel()
    }

    fun destroy() {
        recognizer?.destroy()
    }
}
