
package com.schoolcam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var previewView: PreviewView
    private lateinit var capturedImageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var locationText: TextView
    private lateinit var noteInput: EditText

    private lateinit var captureButton: Button
    private lateinit var saveButton: Button
    private lateinit var recaptureButton: Button
    private lateinit var cameraSwitchButton: Button
    private lateinit var flashButton: Button

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var useFrontCamera = false
    private var flashEnabled = false
    private var isReviewingPhoto = false

    private var currentLocation: Location? = null
    private var currentAddress = "Address unavailable"

    private var capturedFile: File? = null
    private var watermarkedFile: File? = null

    private lateinit var locationManager: LocationManager
    private val backgroundExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val cameraGranted =
                permissions[Manifest.permission.CAMERA] == true ||
                    hasPermission(Manifest.permission.CAMERA)

            val fineLocationGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

            val coarseLocationGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                    hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

            if (cameraGranted) {
                startCamera()
            } else {
                showToast("Camera permission is required.")
                statusText.text = "Camera permission is required."
            }

            if (fineLocationGranted || coarseLocationGranted) {
                startLocationUpdates()
            } else {
                statusText.text =
                    "Camera ready. Allow location permission for GPS watermark."
            }
        }

    private val locationListener = LocationListener { location ->
        currentLocation = location
        val lat = String.format(Locale.US, "%.6f", location.latitude)
        val lng = String.format(Locale.US, "%.6f", location.longitude)
        val acc = location.accuracy.toInt()
        locationText.text = "GPS: Lat $lat, Lng $lng (±${acc}m)"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationManager =
            getSystemService(LOCATION_SERVICE) as LocationManager

        createInterface()

        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        }

        if (
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            startLocationUpdates()
        }

        if (
            !hasPermission(Manifest.permission.CAMERA) ||
            (
                !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                )
        ) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun createInterface() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val heading = TextView(this).apply {
            text = "SchoolCam"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(8))
        }

        rootLayout.addView(
            heading,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val cameraContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.DKGRAY)
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        capturedImageView = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }

        cameraContainer.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        cameraContainer.addView(
            capturedImageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        rootLayout.addView(
            cameraContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                7f
            )
        )

        statusText = TextView(this).apply {
            text = "Starting camera..."
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(6), dp(4), dp(4))
        }

        rootLayout.addView(statusText)

        locationText = TextView(this).apply {
            text = "GPS: Waiting for location..."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(4), 0, dp(4), dp(6))
        }

        rootLayout.addView(locationText)

        noteInput = EditText(this).apply {
            hint = "Enter note (optional)"
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setSingleLine(true)
            setPadding(dp(10), 0, dp(10), 0)
            setBackgroundColor(Color.rgb(45, 45, 45))
        }

        rootLayout.addView(
            noteInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            )
        )

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        cameraSwitchButton = makeButton("Switch Camera") {
            if (!isReviewingPhoto) {
                useFrontCamera = !useFrontCamera
                startCamera()
            }
        }

        flashButton = makeButton("Flash: OFF") {
            flashEnabled = !flashEnabled
            updateFlashButton()
            applyFlashSetting()
        }

        firstRow.addView(
            cameraSwitchButton,
            weightedButtonParams()
        )

        firstRow.addView(
            flashButton,
            weightedButtonParams()
        )

        rootLayout.addView(firstRow)

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        captureButton = makeButton("CAPTURE") {
            takePhoto()
        }

        saveButton = makeButton("SAVE PHOTO") {
            savePhoto()
        }

        recaptureButton = makeButton("RECAPTURE") {
            recapturePhoto()
        }

        secondRow.addView(captureButton, weightedButtonParams())
        secondRow.addView(saveButton, weightedButtonParams())
        secondRow.addView(recaptureButton, weightedButtonParams())

        rootLayout.addView(secondRow)

        saveButton.visibility = View.GONE
        recaptureButton.visibility = View.GONE

        setContentView(rootLayout)
    }

    private fun startCamera() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            statusText.text = "Camera permission is required."
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider

                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(
                            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        )
                        .setFlashMode(
                            if (flashEnabled) {
                                ImageCapture.FLASH_MODE_ON
                            } else {
                                ImageCapture.FLASH_MODE_OFF
                            }
                        )
                        .build()

                    val selector =
                        if (useFrontCamera) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }

                    provider.unbindAll()

                    camera = provider.bindToLifecycle(
                        this,
                        selector,
                        preview,
                        imageCapture
                    )

                    previewView.visibility = View.VISIBLE
                    capturedImageView.visibility = View.GONE
                    statusText.text = "Camera ready."

                    updateFlashButton()
                } catch (exception: Exception) {
                    statusText.text =
                        "Unable to start camera: ${exception.localizedMessage}"
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun takePhoto() {
        if (isReviewingPhoto) return

        val capture = imageCapture

        if (capture == null) {
            showToast("Camera is not ready.")
            return
        }

        captureButton.isEnabled = false
        statusText.text = "Capturing photo..."

        val outputFile = File(
            cacheDir,
            "schoolcam_original_${System.currentTimeMillis()}.jpg"
        )

        val outputOptions =
            ImageCapture.OutputFileOptions.Builder(outputFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    capturedFile = outputFile
                    createWatermarkedPhoto(outputFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true
                    statusText.text =
                        "Capture failed: ${exception.localizedMessage}"
                    showToast("Could not capture photo.")
                }
            }
        )
    }

    private fun createWatermarkedPhoto(sourceFile: File) {
        backgroundExecutor.execute {
            var sourceBitmap: Bitmap? = null
            var resultBitmap: Bitmap? = null

            try {
                sourceBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
                    ?: throw IOException("Unable to read captured image.")

                resultBitmap = sourceBitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    true
                ) ?: throw IOException("Unable to process captured image.")

                val canvas = Canvas(resultBitmap)
                drawWatermark(canvas, resultBitmap.width, resultBitmap.height)

                val output = File(
                    cacheDir,
                    "schoolcam_watermarked_${System.currentTimeMillis()}.jpg"
                )

                FileOutputStream(output).use { stream ->
                    if (
                        !resultBitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            95,
                            stream
                        )
                    ) {
                        throw IOException("Unable to create JPEG.")
                    }
                }

                addExifGps(output)

                runOnUiThread {
                    watermarkedFile = output
                    capturedImageView.setImageBitmap(resultBitmap)
                    capturedImageView.visibility = View.VISIBLE
                    previewView.visibility = View.GONE

                    isReviewingPhoto = true
                    captureButton.visibility = View.GONE
                    saveButton.visibility = View.VISIBLE
                    recaptureButton.visibility = View.VISIBLE
                    cameraSwitchButton.isEnabled = false
                    flashButton.isEnabled = false
                    noteInput.isEnabled = false

                    statusText.text = "Review photo. Save or recapture."
                    captureButton.isEnabled = true

                    resultBitmap = null
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    captureButton.isEnabled = true
                    statusText.text =
                        "Photo processing failed: ${exception.localizedMessage}"
                    showToast("Could not add watermark.")
                }
            } finally {
                sourceBitmap?.recycle()
                resultBitmap?.recycle()
            }
        }
    }

    private fun drawWatermark(
        canvas: Canvas,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val location = currentLocation
        val dateText = SimpleDateFormat(
            "dd-MM-yyyy HH:mm:ss",
            Locale.getDefault()
        ).format(Date())

        val note = noteInput.text.toString().trim()

        val latitude = location?.latitude?.let {
            String.format(Locale.US, "%.6f", it)
        } ?: "Unavailable"

        val longitude = location?.longitude?.let {
            String.format(Locale.US, "%.6f", it)
        } ?: "Unavailable"

        val accuracy = location?.accuracy?.let {
            "Accuracy: ${it.toInt()} m"
        } ?: "Accuracy: unavailable"

        val lines = mutableListOf(
            "SchoolCam",
            "Date: $dateText",
            "Latitude: $latitude",
            "Longitude: $longitude",
            accuracy,
            "Address: $currentAddress"
        )

        if (note.isNotBlank()) {
            lines.add("Note: $note")
        }

        val margin = imageWidth * 0.025f
        val textSize = (imageWidth * 0.030f).coerceAtLeast(20f)
        val lineSpacing = textSize * 1.35f
        val padding = textSize * 0.55f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        val availableWidth = imageWidth - margin * 2 - padding * 2
        val wrappedLines = mutableListOf<String>()

        for (line in lines) {
            wrappedLines.addAll(wrapText(line, paint, availableWidth))
        }

        val boxHeight = wrappedLines.size * lineSpacing + padding * 2
        val top = imageHeight - boxHeight - margin

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(175, 0, 0, 0)
        }

        canvas.drawRoundRect(
            RectF(
                margin,
                top,
                imageWidth - margin,
                imageHeight - margin
            ),
            textSize * 0.3f,
            textSize * 0.3f,
            backgroundPaint
        )

        var baseline = top + padding + textSize

        for ((index, line) in wrappedLines.withIndex()) {
            if (index == 0) {
                paint.textSize = textSize * 1.15f
            } else {
                paint.textSize = textSize
            }

            canvas.drawText(
                line,
                margin + padding,
                baseline,
                paint
            )

            baseline += lineSpacing
        }
    }

    private fun wrapText(
        text: String,
        paint: Paint,
        maxWidth: Float
    ): List<String> {
        if (paint.measureText(text) <= maxWidth) {
            return listOf(text)
        }

        val result = mutableListOf<String>()
        var current = ""

        for (word in text.split(" ")) {
            val candidate =
                if (current.isEmpty()) word else "$current $word"

            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotEmpty()) {
                    result.add(current)
                }

                current = word
            }
        }

        if (current.isNotEmpty()) {
            result.add(current)
        }

        return result
    }

    private fun addExifGps(file: File) {
        val location = currentLocation ?: return

        try {
            val exif = ExifInterface(file.absolutePath)

            exif.setLatLong(
                location.latitude,
                location.longitude
            )

            exif.setAttribute(
                ExifInterface.TAG_DATETIME,
                SimpleDateFormat(
                    "yyyy:MM:dd HH:mm:ss",
                    Locale.US
                ).format(Date())
            )

            exif.saveAttributes()
        } catch (_: Exception) {
            // Visible watermark is still saved even if EXIF fails
        }
    }

    private fun startLocationUpdates() {
        try {
            if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        2000L,
                        1f,
                        locationListener
                    )
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        2000L,
                        1f,
                        locationListener
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun savePhoto() {
        val file = watermarkedFile ?: return

        try {
            val contentValues = ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "SchoolCam_${System.currentTimeMillis()}.jpg"
                )
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/SchoolCam"
                    )
                }
            }

            val resolver = contentResolver
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )

            uri?.let { destinationUri ->
                resolver.openOutputStream(destinationUri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                showToast("Photo saved to Gallery!")
                statusText.text = "Photo saved successfully."
            } ?: throw IOException("Failed to create MediaStore entry.")

            resetToCamera()
        } catch (exception: Exception) {
            showToast("Failed to save photo.")
            statusText.text = "Error saving: ${exception.localizedMessage}"
        }
    }

    private fun recapturePhoto() {
        resetToCamera()
        statusText.text = "Camera ready."
    }

    private fun resetToCamera() {
        isReviewingPhoto = false
        capturedImageView.visibility = View.GONE
        previewView.visibility = View.VISIBLE

        captureButton.visibility = View.VISIBLE
        saveButton.visibility = View.GONE
        recaptureButton.visibility = View.GONE
        cameraSwitchButton.isEnabled = true
        flashButton.isEnabled = true
        noteInput.isEnabled = true
        noteInput.setText("")

        capturedFile?.delete()
        watermarkedFile?.delete()
        capturedFile = null
        watermarkedFile = null
    }

    private fun applyFlashSetting() {
        imageCapture?.let { capture ->
            capture.flashMode = if (flashEnabled) {
                ImageCapture.FLASH_MODE_ON
            } else {
                ImageCapture.FLASH_MODE_OFF
            }
        }
    }

    private fun updateFlashButton() {
        flashButton.text = if (flashEnabled) "Flash: ON" else "Flash: OFF"
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun makeButton(title: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 14f
            setOnClickListener { onClick() }
        }
    }

    private fun weightedButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
    }
}

