package com.shubhix.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Speech-to-text (hi-IN) and text-to-speech with a lower pitch for DJ and higher for SHUBHU. */
class Voice(private val ctx: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx) { ready = it == TextToSpeech.SUCCESS }
    private var sr: SpeechRecognizer? = null

    fun speak(text: String, persona: String, speed: Float) {
        if (!ready) return
        val clean = text.replace(Regex("```[\\s\\S]*?```"), " code upar likha hai ")
        tts.language = Locale("en", "IN")
        tts.setPitch(if (persona == "DJ") 0.75f else 1.2f)
        tts.setSpeechRate(speed)
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "shubhix")
    }

    fun stopSpeaking() { tts.stop() }

    fun listen(onText: (String) -> Unit, onEnd: () -> Unit) {
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
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            )
        }
    }

    fun release() { sr?.destroy(); tts.shutdown() }
}
