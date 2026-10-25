
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
import android.os.Handler
import android.os.Looper
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
    private lateinit var previewFrame: FrameLayout
    private lateinit var reviewImage: ImageView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var gpsInfo: TextView
    private lateinit var noteInput: EditText
    private lateinit var captureButton: Button
    private lateinit var flipButton: Button
    private lateinit var flashButton: Button
    private lateinit var saveButton: Button
    private lateinit var recaptureButton: Button

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var locationExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private var frontCamera = false
    private var flashOn = false
    private var isReviewing = false
    private var isSaving = false

    private var capturedBitmap: Bitmap? = null
    private var reviewBitmap: Bitmap? = null
    private var capturedLocation: Location? = null
    private var capturedAddress = "Address unavailable"
    private var currentAddress = "Finding address..."
    private var capturedAt = System.currentTimeMillis()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastAddressLocation: Location? = null

    private val clockUpdater = object : Runnable {
        override fun run() {
            updateGpsInfo()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val permissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (hasCameraPermission()) {
                startCamera()
                fetchLocation()
            } else {
                gpsInfo.text = "Camera permission required"
            }

            if (!hasLocationPermission()) {
                currentAddress = "Location permission required"
                updateGpsInfo()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        locationExecutor = Executors.newSingleThreadExecutor()

        buildUi()
        updateGpsInfo()
        mainHandler.post(clockUpdater)

        val missing = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(
                this, it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startCamera()
            fetchLocation()
        } else {
            permissions.launch(missing.toTypedArray())
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // Upper 75%: live camera or captured photo
        previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            implementationMode =
                PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        reviewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        previewFrame.addView(
            previewView,
            FrameLayout.LayoutParams(-1, -1)
        )
        previewFrame.addView(
            reviewImage,
            FrameLayout.LayoutParams(-1, -1)
        )

        root.addView(
            previewFrame,
            LinearLayout.LayoutParams(-1, 0, 3f)
        )

        // Lower 25%: details, note and controls
        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.rgb(9, 17, 30))
        }

        val heading = TextView(this).apply {
            text = "SchoolCam  •  GPS Camera"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        bottomPanel.addView(heading)

        gpsInfo = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(205, 225, 250))
            gravity = Gravity.CENTER
            maxLines = 4
            setPadding(0, dp(3), 0, dp(3))
        }
        bottomPanel.addView(gpsInfo)

        noteInput = EditText(this).apply {
            hint = "Write note (optional)"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            minLines = 1
            maxLines = 2
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.rgb(28, 37, 53))
            visibility = View.GONE
        }
        bottomPanel.addView(
            noteInput,
            LinearLayout.LayoutParams(-1, dp(48))
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        flipButton = makeButton("↻ Flip") {
            frontCamera = !frontCamera
            flashOn = false
            startCamera()
        }

        captureButton = makeButton("● Capture") {
            capturePhoto()
        }

        flashButton = makeButton("⚡ Flash OFF") {
            toggleFlash()
        }

        recaptureButton = makeButton("↻ Retake") {
            returnToCamera()
        }

        saveButton = makeButton("Save Photo") {
            savePhoto()
        }

        buttons.addView(flipButton, buttonParams())
        buttons.addView(captureButton, buttonParams())
        buttons.addView(flashButton, buttonParams())
        buttons.addView(recaptureButton, buttonParams())
        buttons.addView(saveButton, buttonParams())

        bottomPanel.addView(buttons)

        root.addView(
            bottomPanel,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)

        noteInput.addTextChangedListener(object :
            android.text.TextWatcher {

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
                if (isReviewing) renderReview()
            }

            override fun afterTextChanged(
                s: android.text.Editable?
            ) = Unit
        })

        showCameraControls()
    }

    private fun buttonParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setPadding(dp(1), 0, dp(1), 0)
        backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                Color.rgb(31, 91, 158)
            )
        setOnClickListener { action() }
    }

    private fun showCameraControls() {
        captureButton.visibility = View.VISIBLE
        flipButton.visibility = View.VISIBLE
        flashButton.visibility = View.VISIBLE
        recaptureButton.visibility = View.GONE
        saveButton.visibility = View.GONE
        noteInput.visibility = View.GONE
    }

    private fun showReviewControls() {
        captureButton.visibility = View.GONE
        flipButton.visibility = View.GONE
        flashButton.visibility = View.GONE
        recaptureButton.visibility = View.VISIBLE
        saveButton.visibility = View.VISIBLE
        noteInput.visibility = View.VISIBLE
    }

    private fun startCamera() {
        if (!hasCameraPermission()) return

        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(
                        ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    )
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
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
                    capture
                )

                imageCapture = capture

                if (flashOn &&
                    camera?.cameraInfo?.hasFlashUnit() == true
                ) {
                    camera?.cameraControl?.enableTorch(true)
                }

                flashButton.isEnabled =
                    camera?.cameraInfo?.hasFlashUnit() == true

                flashButton.text =
                    if (flashOn) "⚡ Flash ON" else "⚡ Flash OFF"

                if (!isReviewing) {
                    previewView.visibility = View.VISIBLE
                    reviewImage.visibility = View.GONE
                }

                updateGpsInfo()

            } catch (e: Exception) {
                gpsInfo.text =
                    "Camera error: ${e.localizedMessage ?: "unknown"}"
            }
        }, ContextCompat.getMainExecutor(this))
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
                runOnUiThread {
                    flashOn = newState
                    flashButton.text =
                        if (flashOn) "⚡ Flash ON" else "⚡ Flash OFF"
                }
            }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return

        captureButton.isEnabled = false

        val tempFile = File(
            cacheDir,
            "schoolcam_${System.currentTimeMillis()}.jpg"
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
                        val bitmap = BitmapFactory.decodeFile(
                            tempFile.absolutePath
                        ) ?: throw IllegalStateException(
                            "Unable to read captured photo"
                        )

                        runOnUiThread {
                            capturedBitmap?.recycle()
                            capturedBitmap = bitmap
                            capturedAt = System.currentTimeMillis()
                            capturedLocation = currentBestLocation()
                            capturedAddress = currentAddress
                            isReviewing = true

                            previewView.visibility = View.GONE
                            reviewImage.visibility = View.VISIBLE

                            showReviewControls()
                            noteInput.setText("")
                            renderReview()

                            captureButton.isEnabled = true
                            updateGpsInfo()

                            fetchLocation()
                        }

                    } catch (e: Exception) {
                        runOnUiThread {
                            captureButton.isEnabled = true
                            Toast.makeText(
                                this@MainActivity,
                                "Capture failed: ${e.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } finally {
                        tempFile.delete()
                    }
                }

                override fun onError(
                    exception: ImageCaptureException
                ) {
                    tempFile.delete()

                    runOnUiThread {
                        captureButton.isEnabled = true
                        Toast.makeText(
                            this@MainActivity,
                            "Capture failed: ${exception.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }

    private fun currentBestLocation(): Location? =
        capturedLocation?.let {
            Location(it)
        }

    private fun fetchLocation() {
        if (!hasLocationPermission()) {
            currentAddress = "Location permission required"
            updateGpsInfo()
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

                if (location != null) {
                    capturedLocation = Location(location)
                    lastAddressLocation = Location(location)

                    currentAddress = "Finding address..."
                    updateGpsInfo()
                    lookupAddress(location)
                } else {
                    client.lastLocation.addOnSuccessListener { last ->
                        if (last != null) {
                            capturedLocation = Location(last)
                            lastAddressLocation = Location(last)
                            lookupAddress(last)
                        } else {
                            currentAddress = "GPS fix unavailable"
                            updateGpsInfo()
                        }
                    }
                }

            }.addOnFailureListener {
                currentAddress = "GPS location unavailable"
                updateGpsInfo()
            }

        } catch (_: SecurityException) {
            currentAddress = "Location permission required"
            updateGpsInfo()
        }
    }

    @Suppress("DEPRECATION")
    private fun lookupAddress(location: Location) {
        locationExecutor.execute {
            var addressText = "Address unavailable"

            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(
                        this,
                        Locale.getDefault()
                    )

                    val addresses = geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1
                    )

                    if (!addresses.isNullOrEmpty()) {
                        addressText =
                            addresses[0].getAddressLine(0)
                                ?: "Address unavailable"
                    }
                }
            } catch (_: Exception) {
                addressText = "Address unavailable"
            }

            val result = addressText

            mainHandler.post {
                currentAddress = result

                if (isReviewing) {
                    capturedAddress = result
                    renderReview()
                }

                updateGpsInfo()
            }
        }
    }

    private fun updateGpsInfo() {
        val now = SimpleDateFormat(
            "dd MMM yyyy  •  HH:mm:ss",
            Locale.getDefault()
        ).format(Date())

        val location = if (isReviewing) {
            capturedLocation
        } else {
            lastAddressLocation ?: capturedLocation
        }

        val address = if (isReviewing) {
            capturedAddress
        } else {
            currentAddress
        }

        val coordinates = if (location != null) {
            "Lat: %.6f  |  Long: %.6f".format(
                Locale.US,
                location.latitude,
                location.longitude
            )
        } else {
            "Latitude: --  |  Longitude: --"
        }

        val accuracy = location?.let {
            "GPS accuracy: %.0f m".format(Locale.US, it.accuracy)
        } ?: "Waiting for GPS fix..."

        gpsInfo.text = "$coordinates\n$
