
package com.schoolcam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
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
import java.io.File
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
    private lateinit var noteInput: EditText
    private lateinit var reviewImage: ImageView
    private lateinit var cameraLayout: LinearLayout
    private lateinit var reviewLayout: LinearLayout

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var locationExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private var frontCamera = false
    private var flashOn = false

    private var capturedBitmap: Bitmap? = null
    private var reviewBitmap: Bitmap? = null
    private var capturedLocation: Location? = null
    private var capturedAddress: String = "Address unavailable"
    private var capturedAt: Long = System.currentTimeMillis()

    private val mainExecutor by lazy {
        ContextCompat.getMainExecutor(this)
    }

    private val permissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->

            val cameraGranted =
                results[Manifest.permission.CAMERA] == true ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

            if (cameraGranted) {
                startCamera()
            } else {
                status.text = "Camera permission is required."
            }

            val locationGranted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

            if (cameraGranted && !locationGranted) {
                status.text = "Camera ready • GPS permission not granted"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        locationExecutor = Executors.newSingleThreadExecutor()

        buildUi()

        val requiredPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startCamera()
        } else {
            permissions.launch(missingPermissions.toTypedArray())
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 13, 24))
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }

        val title = TextView(this).apply {
            text = "SchoolCam"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        root.addView(title)

        status = TextView(this).apply {
            text = "Starting camera..."
            textSize = 11f
            setTextColor(Color.rgb(160, 190, 225))
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
        root.addView(status)

        cameraLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        cameraLayout.addView(
            previewView,
            LinearLayout.LayoutParams(-1, 0, 3f)
        )

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }

        flipButton = makeButton("↻ Camera") {
            frontCamera = !frontCamera
            startCamera()
        }

        captureButton = makeButton("● CAPTURE") {
            capturePhoto()
        }

        flashButton = makeButton("⚡ Flash off") {
            toggleFlash()
        }

        controls.addView(flipButton, weightParams())
        controls.addView(captureButton, weightParams())
        controls.addView(flashButton, weightParams())

        cameraLayout.addView(
            controls,
            LinearLayout.LayoutParams(-1, dp(62))
        )

        root.addView(
            cameraLayout,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        reviewLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        reviewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.BLACK)
        }

        reviewLayout.addView(
            reviewImage,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        noteInput = EditText(this).apply {
            hint = "Add note (optional) — school / activity"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            setSingleLine(false)
            minLines = 2
            maxLines = 3
            textSize = 14f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.rgb(28, 37, 53))
        }

        noteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                if (reviewLayout.visibility == View.VISIBLE) {
                    renderReview()
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        reviewLayout.addView(noteInput)

        val reviewControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val recaptureButton = makeButton("↻ Recapture") {
            returnToCamera()
        }

        saveButton = makeButton("Save Photo") {
            savePhoto()
        }

        reviewControls.addView(recaptureButton, weightParams())
        reviewControls.addView(saveButton, weightParams())
        reviewLayout.addView(reviewControls)

        root.addView(
            reviewLayout,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)
    }

    private fun startCamera() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val newCapture = ImageCapture.Builder()
                    .setCaptureMode(
                        ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    )
                    .setFlashMode(
                        if (flashOn) {
                            ImageCapture.FLASH_MODE_ON
                        } else {
                            ImageCapture.FLASH_MODE_OFF
                        }
                    )
                    .build()

                val selector = if (frontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                provider.unbindAll()

                camera = provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    newCapture
                )

                imageCapture = newCapture

                status.text = if (frontCamera) {
                    "Front camera • GPS watermark enabled"
                } else {
                    "Back camera • GPS watermark enabled"
                }

                val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                flashButton.isEnabled = hasFlash

                if (!hasFlash) {
                    flashOn = false
                    flashButton.text = "⚡ No flash"
                } else {
                    flashButton.text =
                        if (flashOn) "⚡ Flash on" else "⚡ Flash off"

                    camera?.cameraControl?.enableTorch(flashOn)
                }

            } catch (e: Exception) {
                status.text =
                    "Unable to start camera: ${e.localizedMessage ?: "unknown error"}"
            }
        }, mainExecutor)
    }

    private fun toggleFlash() {
        val activeCamera = camera

        if (activeCamera == null ||
            activeCamera.cameraInfo.hasFlashUnit().not()
        ) {
            Toast.makeText(
                this,
                "Flash is not available on this camera",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val newState = !flashOn

        activeCamera.cameraControl.enableTorch(newState)
            .addListener({
                flashOn = newState

                runOnUiThread {
                    flashButton.text =
                        if (flashOn) "⚡ Flash on" else "⚡ Flash off"

                    imageCapture?.flashMode =
                        if (flashOn) {
                            ImageCapture.FLASH_MODE_ON
                        } else {
                            ImageCapture.FLASH_MODE_OFF
                        }
                }
            }, mainExecutor)
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: run {
            status.text = "Camera is not ready."
            return
        }

        captureButton.isEnabled = false
        status.text = "Capturing photo..."

        val tempFile = File(
            cacheDir,
            "schoolcam_preview_${System.currentTimeMillis()}.jpg"
        )

        val options =
            ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    output: ImageCapture.OutputFileResults
                ) {
                    try {
                        val raw = BitmapFactory.decodeFile(
                            tempFile.absolutePath
                        ) ?: throw IllegalStateException(
                            "Could not decode captured photo"
                        )

                        runOnUiThread {
                            reviewBitmap?.recycle()
                            reviewBitmap = null

                            capturedBitmap?.recycle()
                            capturedBitmap = raw

                            capturedAt = System.currentTimeMillis()
                            capturedLocation = null
                            capturedAddress = "Getting GPS address..."

                            captureButton.isEnabled = true
                            cameraLayout.visibility = View.GONE
                            reviewLayout.visibility = View.VISIBLE

                            noteInput.setText("")

                            renderReview()
                            status.text =
                                "Review photo • Add note, Save or Recapture"

                            fetchLocation()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            captureButton.isEnabled = true
                            status.text =
                                "Capture failed: ${e.localizedMessage ?: "unknown error"}"
                        }
                    } finally {
                        tempFile.delete()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    tempFile.delete()

                    runOnUiThread {
                        captureButton.isEnabled = true
                        status.text =
                            "Capture failed: ${exception.message}"
                    }
                }
            }
        )
    }

    private fun fetchLocation() {
        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            capturedAddress = "GPS permission not granted"
            renderReview()
            return
        }

        try {
            val client =
                LocationServices.getFusedLocationProviderClient(this)

            val token = CancellationTokenSource()

            client.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                token.token
            ).addOnSuccessListener { location ->

                if (location == null) {
                    capturedAddress = "GPS location unavailable"
                    renderReview()
                } else {
                    capturedLocation = location
                    capturedAddress = "Getting GPS address..."
                    renderReview()
                    lookupAddress(location)
                }

            }.addOnFailureListener {
                capturedAddress = "Could not get GPS location"
                renderReview()
            }

        } catch (_: SecurityException) {
            capturedLocation = null
            capturedAddress = "Location permission required"
            renderReview()
        }
    }

    private fun lookupAddress(location: Location) {
        locationExecutor.execute {
            var addressText = "Address unavailable"

            try {
                if (Geocoder.isPresent()) {
                    @Suppress("DEPRECATION")
                    val addresses = Geocoder(
                        this,
                        Locale.getDefault()
                    ).getFromLocation(
                        location.latitude,
                        location.longitude,
                        1
                    )

                    if (!addresses.isNullOrEmpty()) {
                        addressText =
                            addresses[0].getAddressLine(0)
                                ?: addressText
                    }
                }
            } catch (_: Exception) {
                addressText = "Address unavailable"
            }

            runOnUiThread {
                capturedAddress = addressText

                if (reviewLayout.visibility == View.VISIBLE) {
                    renderReview()
                }
            }
        }
    }

    private fun renderReview() {
        val original = capturedBitmap ?: return

        try {
            val newBitmap = addWatermark(
                original,
                noteInput.text.toString(),
                capturedLocation,
                capturedAddress,
                capturedAt
            )

            val oldBitmap = reviewBitmap
            reviewImage.setImageBitmap(newBitmap)
            reviewBitmap = newBitmap

            if (oldBitmap != null && oldBitmap !== original) {
                oldBitmap.recycle()
            }
        } catch (e: Exception) {
            status.text = "Preview error: ${e.localizedMessage}"
        }
    }

    private fun addWatermark(
        source: Bitmap,
        note: String,
        location: Location?,
        address: String,
        time: Long
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Could not prepare photo")

        val canvas = Canvas(output)

        val padding = (output.width * 0.025f).coerceAtLeast(16f)
        val textSize = (output.width * 0.030f).coerceIn(22f, 48f)
        val maxTextWidth = output.width - padding * 2

        val lines = mutableListOf<String>()

        if (location != null) {
            lines.add(
                "Latitude: %.6f".format(
                    Locale.US,
                    location.latitude
                )
            )
            lines.add(
                "Longitude: %.6f".format(
                    Locale.US,
                    location.longitude
                )
            )
            lines.add(
                "GPS accuracy: %.0f m".format(
                    Locale.US,
                    location.accuracy
                )
            )
        } else {
            lines.add("Latitude: unavailable")
            lines.add("Longitude: unavailable")
        }

        lines.add(
            SimpleDateFormat(
                "dd MMM yyyy • hh:mm:ss a",
                Locale.getDefault()
            ).format(Date(time))
        )

        if (address.isNotBlank()) {
            lines.add("Address: $address")
        }

        if (note.isNotBlank()) {
            lines.add("N
