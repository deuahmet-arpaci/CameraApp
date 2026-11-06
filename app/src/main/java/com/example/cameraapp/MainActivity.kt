package com.example.cameraapp

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // --- Kamera arayüzü ---
    private lateinit var previewView: PreviewView
    private lateinit var gridOverlay: GridOverlayView
    private lateinit var focusRing: View
    private lateinit var flashOverlay: View
    private lateinit var flashButton: ImageButton
    private lateinit var hdrButton: TextView
    private lateinit var zoomSlider: SeekBar
    private lateinit var thumb: ImageView
    private lateinit var micButton: ImageButton

    // --- Bilgi paneli ---
    private lateinit var infoPanel: View
    private lateinit var tabMeta: TextView
    private lateinit var tabNote: TextView
    private lateinit var metaText: TextView
    private lateinit var noteGroup: View
    private lateinit var recRow: View
    private lateinit var recDot: View
    private lateinit var noteText: TextView

    // --- Durum ---
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var extensionsManager: ExtensionsManager? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var hdrOn = false
    private var sliding = false

    private var lastPhotoUri: Uri? = null
    private var currentNote = ""
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val hideMicRunnable = Runnable {
        if (!listening && infoPanel.visibility != View.VISIBLE) micButton.visibility = View.GONE
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                toast("Kamera izni gerekli")
                finish()
            }
        }

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else toast("Sesli not için mikrofon izni gerekli")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupControls()
        setupGestures()

        io.execute {
            val latest = PhotoStore.allUris(this).firstOrNull()
            runOnUiThread { latest?.let { loadThumb(it) } }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        gridOverlay = findViewById(R.id.gridOverlay)
        focusRing = findViewById(R.id.focusRing)
        flashOverlay = findViewById(R.id.flashOverlay)
        flashButton = findViewById(R.id.flashButton)
        hdrButton = findViewById(R.id.hdrButton)
        zoomSlider = findViewById(R.id.zoomSlider)
        thumb = findViewById(R.id.thumb)
        micButton = findViewById(R.id.micButton)
        infoPanel = findViewById(R.id.infoPanel)
        tabMeta = findViewById(R.id.tabMeta)
        tabNote = findViewById(R.id.tabNote)
        metaText = findViewById(R.id.metaText)
        noteGroup = findViewById(R.id.noteGroup)
        recRow = findViewById(R.id.recRow)
        recDot = findViewById(R.id.recDot)
        noteText = findViewById(R.id.noteText)
    }

    private fun setupControls() {
        findViewById<View>(R.id.shutterButton).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.switchButton).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        val openGallery = View.OnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        findViewById<View>(R.id.galleryButton).setOnClickListener(openGallery)
        thumb.setOnClickListener(openGallery)

        findViewById<View>(R.id.gridButton).setOnClickListener {
            gridOverlay.visibility =
                if (gridOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        flashButton.setOnClickListener { cycleFlash() }
        hdrButton.setOnClickListener {
            hdrOn = !hdrOn
            startCamera()
        }

        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) camera?.cameraControl?.setLinearZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sliding = true }
            override fun onStopTrackingTouch(sb: SeekBar?) { sliding = false }
        })

        micButton.setOnClickListener { onMicClicked() }
        findViewById<View>(R.id.panelClose).setOnClickListener { hidePanel() }
        tabMeta.setOnClickListener { selectTab(0) }
        tabNote.setOnClickListener { selectTab(1) }
        selectTab(0)
    }

    // ------------------------------------------------------------------ Kamera

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val extFuture = ExtensionsManager.getInstanceAsync(this, provider)
            extFuture.addListener({
                extensionsManager = try { extFuture.get() } catch (e: Exception) { null }
                bindCamera(provider)
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()

        var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val em = extensionsManager
        val hdrAvailable = try {
            em != null && em.isExtensionAvailable(selector, ExtensionMode.HDR)
        } catch (e: Exception) { false }
        hdrButton.visibility = if (hdrAvailable) View.VISIBLE else View.GONE
        if (!hdrAvailable) hdrOn = false
        hdrButton.setTextColor(if (hdrOn) Color.parseColor("#FFD54F") else Color.WHITE)
        if (hdrOn && em != null) selector = em.getExtensionEnabledCameraSelector(selector, ExtensionMode.HDR)

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview, imageCapture)
            camera?.cameraInfo?.zoomState?.observe(this) { state ->
                if (!sliding) zoomSlider.progress = (state.linearZoom * 100).toInt()
            }
        } catch (e: Exception) {
            toast("Kamera başlatılamadı: ${e.message}")
        }
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> {
                flashButton.setImageResource(R.drawable.ic_flash_on)
                flashButton.setColorFilter(Color.parseColor("#FFD54F"))
                toast("Flaş: Açık")
            }
            ImageCapture.FLASH_MODE_AUTO -> {
                flashButton.setImageResource(R.drawable.ic_flash_on)
                flashButton.setColorFilter(Color.parseColor("#80D8FF"))
                toast("Flaş: Otomatik")
            }
            else -> {
                flashButton.setImageResource(R.drawable.ic_flash_off)
                flashButton.setColorFilter(Color.WHITE)
                toast("Flaş: Kapalı")
            }
        }
    }

    private fun setupGestures() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return true
                    val z = cam.cameraInfo.zoomState.value ?: return true
                    val ratio = (z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(ratio)
                    return true
                }
            })
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        previewView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val point = previewView.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f
        focusRing.animate().cancel()
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(700).setDuration(300).start()
        }.start()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CameraApp")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        flashOverlay.alpha = 0.8f
        flashOverlay.animate().alpha(0f).setDuration(200).start()

        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let { onPhotoSaved(it) }
                }
                override fun onError(exc: ImageCaptureException) {
                    toast("Hata: ${exc.message}")
                }
            })
    }

    private fun onPhotoSaved(uri: Uri) {
        stopRecognizer()
        lastPhotoUri = uri
        currentNote = ""
        noteText.text = ""
        loadThumb(uri)
        hidePanel()
        showMicButton()
        io.execute {
            val meta = ExifNotes.describe(this, uri)
            runOnUiThread { metaText.text = meta }
        }
    }

    private fun loadThumb(uri: Uri) {
        Glide.with(this).load(uri).centerCrop().into(thumb)
    }

    // ------------------------------------------------------------------ Mikrofon

    private fun showMicButton() {
        micButton.setImageResource(R.drawable.ic_mic)
        micButton.visibility = View.VISIBLE
        micButton.scaleX = 0f
        micButton.scaleY = 0f
        micButton.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
        handler.removeCallbacks(hideMicRunnable)
        handler.postDelayed(hideMicRunnable, 20_000)
    }

    private fun onMicClicked() {
        handler.removeCallbacks(hideMicRunnable)
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        val target = lastPhotoUri ?: return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Bu cihazda konuşma tanıma servisi bulunamadı")
            return
        }
        showPanel(1)
        stopRecognizer()

        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partial: Bundle?) {
                val p = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (p.isNotBlank()) noteText.text = join(currentNote, p)
            }

            override fun onResults(results: Bundle?) {
                setListening(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) {
                    noteText.text = currentNote
                    toast("Ses algılanamadı, tekrar deneyin")
                    return
                }
                currentNote = join(currentNote, text)
                noteText.text = currentNote
                saveNote(target, currentNote)
            }

            override fun onError(error: Int) {
                setListening(false)
                noteText.text = currentNote
                when (error) {
                    SpeechRecognizer.ERROR_CLIENT -> {}
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        toast("Ses algılanamadı, tekrar deneyin")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        toast("Mikrofon izni gerekli")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        toast("Ağ hatası: konuşma tanıma için internet gerekebilir")
                    12, 13 -> toast("Türkçe konuşma tanıma bu cihazda kullanılamıyor")
                    else -> toast("Konuşma tanıma hatası ($error)")
                }
            }
        })
        recognizer = sr

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        sr.startListening(intent)
        setListening(true)
    }

    private fun setListening(value: Boolean) {
        listening = value
        recRow.visibility = if (value) View.VISIBLE else View.GONE
        micButton.setImageResource(if (value) R.drawable.ic_stop else R.drawable.ic_mic)
        if (value) {
            recDot.startAnimation(AlphaAnimation(1f, 0.15f).apply {
                duration = 600
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            })
        } else {
            recDot.clearAnimation()
        }
    }

    private fun stopRecognizer() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        if (listening) setListening(false)
    }

    private fun join(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")

    private fun saveNote(uri: Uri, note: String) {
        io.execute {
            val ok = ExifNotes.write(this, uri, note)
            val meta = ExifNotes.describe(this, uri)
            runOnUiThread {
                toast(if (ok) "Açıklama fotoğrafın EXIF verisine eklendi" else "EXIF'e yazılamadı")
                metaText.text = meta
            }
        }
    }

    // ------------------------------------------------------------------ Bilgi paneli

    private fun dp(v: Int) = v * resources.displayMetrics.density

    private fun showPanel(tab: Int) {
        selectTab(tab)
        if (infoPanel.visibility != View.VISIBLE) {
            infoPanel.translationX = dp(260)
            infoPanel.visibility = View.VISIBLE
            infoPanel.animate().translationX(0f).setDuration(200).start()
        }
    }

    private fun hidePanel() {
        if (infoPanel.visibility != View.VISIBLE) return
        infoPanel.animate().translationX(dp(260)).setDuration(200).withEndAction {
            infoPanel.visibility = View.GONE
        }.start()
    }

    private fun selectTab(index: Int) {
        val on = Color.parseColor("#E3F2FD")
        val off = Color.parseColor("#F1F1F1")
        tabMeta.setBackgroundColor(if (index == 0) on else off)
        tabNote.setBackgroundColor(if (index == 1) on else off)
        tabMeta.setTextColor(if (index == 0) Color.parseColor("#1565C0") else Color.GRAY)
        tabNote.setTextColor(if (index == 1) Color.parseColor("#1565C0") else Color.GRAY)
        metaText.visibility = if (index == 0) View.VISIBLE else View.GONE
        noteGroup.visibility = if (index == 1) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ Yaşam döngüsü

    override fun onStop() {
        super.onStop()
        stopRecognizer()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopRecognizer()
        io.shutdown()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
