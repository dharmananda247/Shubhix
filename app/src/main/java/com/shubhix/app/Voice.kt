package com.shubhix.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Speech-to-text and text-to-speech. Each persona can have its own voice and pitch. */
class Voice(private val ctx: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx) { ready = it == TextToSpeech.SUCCESS }
    private var sr: SpeechRecognizer? = null

    /** Voices installed on this phone for English and Hindi: (internal name, readable label). */
    fun voices(): List<Pair<String, String>> = try {
        if (!ready) emptyList() else tts.voices.orEmpty()
            .filter { it.locale.language == "en" || it.locale.language == "hi" }
            .sortedBy { it.locale.toString() + it.name }
            .map { it.name to (it.locale.displayName + " · " + it.name) }
    } catch (e: Exception) {
        emptyList()
    }

    fun speak(text: String, persona: String, speed: Float, voiceName: String, pitch: Float) {
        if (!ready) return
        try {
            val clean = text.replace(Regex("```[\\s\\S]*?```"), " code is written above ")
            val v = if (voiceName.isNotBlank()) tts.voices?.firstOrNull { it.name == voiceName } else null
            if (v != null) tts.setVoice(v) else tts.setLanguage(Locale("en", "IN"))
            tts.setPitch(pitch)
            tts.setSpeechRate(speed)
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "shubhix")
        } catch (e: Exception) {
            // a TTS engine problem should never crash the app
        }
    }

    fun stopSpeaking() { tts.stop() }

    fun listen(lang: String, onText: (String) -> Unit, onEnd: () -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { onEnd(); return }
        stopSpeaking()
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(ctx).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (t.isNullOrBlank()) onEnd() else onText(t)
                }
                override fun onError(error: Int) { onEnd() }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            )
        }
    }

    fun release() { sr?.destroy(); tts.shutdown() }
}
