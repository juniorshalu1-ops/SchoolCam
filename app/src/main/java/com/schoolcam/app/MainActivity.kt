package com.schoolcam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var captureButton: Button
    private lateinit var flipButton: Button
    private lateinit var flashButton: Button
    private lateinit var saveButton: Button
    private lateinit var recaptureButton: Button
    private lateinit var noteInput: EditText
    private lateinit var reviewImage: ImageView
    private lateinit var cameraLayout: LinearLayout
    private lateinit var reviewLayout: LinearLayout
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var frontCamera = false
    private var flashOn = false
    private var capturedBitmap: Bitmap? = null
    private var capturedLocation: Location? = null
    private var capturedAt = System.currentTimeMillis()

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) startCamera()
        else status.text = "Camera permission is required."
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] != true &&
            results[Manifest.permission.ACCESS_COARSE_LOCATION] != true) {
            status.text = "Camera ready • Location permission not granted"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startCamera() else permissions.launch(needed.toTypedArray())
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 13, 24))
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val title = TextView(this).apply {
            text = "SchoolCam"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }
        root.addView(title)
        status = TextView(this).apply {
            text = "Starting camera…"
            textSize = 12f
            setTextColor(Color.rgb(160, 190, 225))
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        root.addView(status)

        cameraLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        cameraLayout.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        flipButton = makeButton("↻ Camera") { frontCamera = !frontCamera; startCamera() }
        flashButton = makeButton("⚡ Flash off") {
            flashOn = !flashOn
            imageCapture?.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            flashButton.text = if (flashOn) "⚡ Flash on" else "⚡ Flash off"
        }
        captureButton = makeButton("● CAPTURE") { capturePhoto() }
        controls.addView(flipButton, weightParams())
        controls.addView(captureButton, weightParams())
        controls.addView(flashButton, weightParams())
        cameraLayout.addView(controls)
        root.addView(cameraLayout, LinearLayout.LayoutParams(-1, 0, 1f))

        reviewLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        reviewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        reviewLayout.addView(reviewImage, LinearLayout.LayoutParams(-1, 0, 1f))
        noteInput = EditText(this).apply {
            hint = "Add note (optional) — e.g. school / activity"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            setSingleLine(false)
            minLines = 2
            maxLines = 3
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(28, 37, 53))
        }
        noteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (reviewLayout.visibility == View.VISIBLE) renderReview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        reviewLayout.addView(noteInput)
        val reviewControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recaptureButton = makeButton("↻ Recapture") { returnToCamera() }
        saveButton = makeButton("Save Photo") { savePhoto() }
        reviewControls.addView(recaptureButton, weightParams())
        reviewControls.addView(saveButton, weightParams())
        reviewLayout.addView(reviewControls)
        root.addView(reviewLayout, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                    .build()
                val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture)
                status.text = if (frontCamera) "Front camera • GPS watermark enabled" else "Back camera • GPS watermark enabled"
                flashButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
            } catch (e: Exception) {
                status.text = "Unable to start camera: ${e.localizedMessage ?: "unknown error"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: run { status.text = "Camera is not ready."; return }
        captureButton.isEnabled = false
        status.text = "Capturing photo…"
        val tempFile = java.io.File(cacheDir, "schoolcam_preview_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(tempFile).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                try {
                    val raw = BitmapFactory.decodeFile(tempFile.absolutePath)
                    if (raw == null) throw IllegalStateException("Could not decode captured photo")
                    capturedBitmap?.recycle()
                    capturedBitmap = raw
                    capturedAt = System.currentTimeMillis()
                    fetchLocation()
                    runOnUiThread {
                        captureButton.isEnabled = true
                        cameraLayout.visibility = View.GONE
                        reviewLayout.visibility = View.VISIBLE
                        noteInput.setText("")
                        renderReview()
                        status.text = "Review photo • Add a note, then Save or Recapture"
                    }
                } catch (e: Exception) {
                    runOnUiThread { captureButton.isEnabled = true; status.text = "Capture failed: ${e.localizedMessage}" }
                } finally {
                    tempFile.delete()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                tempFile.delete()
                runOnUiThread {
                    captureButton.isEnabled = true
                    status.text = "Capture failed: ${exception.message}"
                }
            }
        })
    }

    private fun fetchLocation() {
        capturedLocation = null
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { runOnUiThread { renderReview() }; return }
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    capturedLocation = loc
                    runOnUiThread { if (reviewLayout.visibility == View.VISIBLE) renderReview() }
                }.addOnFailureListener { runOnUiThread { renderReview() } }
        } catch (_: SecurityException) { }
    }

    private fun renderReview() {
        val bmp = capturedBitmap ?: return
        reviewImage.setImageBitmap(addWatermark(bmp, noteInput.text.toString(), capturedLocation, capturedAt))
        noteInput.setOnFocusChangeListener { _, _ -> }
    }

    private fun addWatermark(source: Bitmap, note: String, location: Location?, time: Long): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val scale = out.width / 900f
        val pad = (out.width * 0.025f).coerceAtLeast(16f)
        val textSize = (out.width * 0.026f).coerceIn(20f, 44f)
        val lines = mutableListOf<String>()
        if (location != null) {
            lines.add("Latitude: %.6f   Longitude: %.6f".format(Locale.US, location.latitude, location.longitude))
            lines.add("GPS accuracy: %.0f m".format(Locale.US, location.accuracy))
        } else {
            lines.add("Location unavailable")
        }
        lines.add(SimpleDateFormat("dd MMM yyyy • hh:mm:ss a", Locale.getDefault()).format(Date(time)))
        if (note.isNotBlank()) lines.add("Note: " + note.trim().replace("\n", " "))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSize
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val lineHeight = textSize * 1.45f
        val panelHeight = pad * 2 + lineHeight * lines.size
        canvas.drawRect(0f, out.height - panelHeight, out.width.toFloat(), out.height.toFloat(),
            Paint().apply { color = Color.argb(190, 4, 10, 20) })
        lines.forEachIndexed { i, line ->
            canvas.drawText(line.take(150), pad, out.height - panelHeight + pad + textSize + i * lineHeight, paint)
        }
        return out
    }

    private fun savePhoto() {
        val bmp = capturedBitmap ?: return
        saveButton.isEnabled = false
        val finalBitmap = addWatermark(bmp, noteInput.text.toString(), capturedLocation, capturedAt)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(capturedAt))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SchoolCam_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SchoolCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        var uri: Uri? = null
        try {
            uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create image file")
            contentResolver.openOutputStream(uri)?.use { stream ->
                if (!finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw IllegalStateException("JPEG encoding failed")
            } ?: throw IllegalStateException("Could not open image file")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            status.text = "Saved to Pictures/SchoolCam"
            Toast.makeText(this, "Photo saved to Pictures/SchoolCam", Toast.LENGTH_LONG).show()
            returnToCamera()
        } catch (e: Exception) {
            uri?.let { contentResolver.delete(it, null, null) }
            status.text = "Save failed: ${e.localizedMessage ?: "unknown error"}"
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
        } finally {
            finalBitmap.recycle()
            saveButton.isEnabled = true
        }
    }

    private fun returnToCamera() {
        capturedBitmap?.recycle()
        capturedBitmap = null
        reviewImage.setImageDrawable(null)
        reviewLayout.visibility = View.GONE
        cameraLayout.visibility = View.VISIBLE
        status.text = "Ready to capture"
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(34, 91, 155)))
        setOnClickListener { action() }
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(54), 1f).apply {
        setMargins(dp(3), dp(6), dp(3), dp(6))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        capturedBitmap?.recycle()
    }
}
