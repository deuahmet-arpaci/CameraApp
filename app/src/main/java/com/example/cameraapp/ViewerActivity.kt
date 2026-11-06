package com.example.cameraapp

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import java.util.Locale

class ViewerActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var note = ""
    private lateinit var speakButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)

        val uri = intent.data ?: run { finish(); return }
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        val photo = findViewById<ImageView>(R.id.photoView)
        val noteView = findViewById<TextView>(R.id.noteView)
        speakButton = findViewById(R.id.speakButton)

        Glide.with(this).load(uri).into(photo)
        speakButton.setOnClickListener { if (speaking) stopSpeaking() else speak() }

        // Metin, doğrudan fotoğrafın EXIF verisinden okunur.
        Thread {
            val n = ExifNotes.read(this, uri).orEmpty()
            runOnUiThread {
                note = n
                noteView.text = if (n.isEmpty()) "Bu fotoğrafta kayıtlı bir açıklama yok." else n
                if (n.isNotEmpty()) initTts()
            }
        }.start()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                toast("Sesli okuma başlatılamadı")
                return@TextToSpeech
            }
            val r = engine.setLanguage(Locale("tr", "TR"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                toast("Türkçe ses verisi yok: Ayarlar > Metin okuma bölümünden Türkçe'yi yükleyin")
                return@TextToSpeech
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { runOnUiThread { setSpeaking(false) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { runOnUiThread { setSpeaking(false) } }
            })
            ttsReady = true
            speak() // fotoğraf açılınca otomatik oku
        }
    }

    private fun speak() {
        if (!ttsReady || note.isEmpty()) return
        tts?.speak(note, TextToSpeech.QUEUE_FLUSH, null, "note")
        setSpeaking(true)
    }

    private fun stopSpeaking() {
        tts?.stop()
        setSpeaking(false)
    }

    private fun setSpeaking(value: Boolean) {
        speaking = value
        speakButton.setImageResource(if (value) R.drawable.ic_stop else R.drawable.ic_volume)
    }

    override fun onPause() {
        super.onPause()
        if (speaking) stopSpeaking()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
