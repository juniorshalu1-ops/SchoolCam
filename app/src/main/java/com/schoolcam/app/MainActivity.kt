package com.schoolcam.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val PERMISSION_REQUEST = 101
    }

    private lateinit var root: LinearLayout
    private lateinit var previewView: PreviewView
    private lateinit var reviewImage: ImageView
    private lateinit var cameraPanel: LinearLayout
    private lateinit var reviewPanel: LinearLayout
    private lateinit var controlPanel: LinearLayout
    private lateinit var noteInput: EditText
    private lateinit var status: TextView
    private lateinit var locationText: TextView
    private lateinit var captureButton: Button
    private lateinit var saveButton: Button
    private lateinit var flashButton: Button
    private lateinit var switchButton: Button
    private lateinit var reviewButton: Button

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var locationExecutor: ExecutorService
    private lateinit var locationManager: LocationManager

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false
    private var capturedLocation: Location? = null
    private var capturedAddress = "Address unavailable"
    private var capturedAt = System.currentTimeMillis()

    private var capturedBitmap: Bitmap? = null
    private var reviewBitmap: Bitmap? = null
    private var isReviewMode = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            capturedLocation = location
            runOnUiThread {
                updateLocationText(location)
            }
            lookupAddress(location)
        }

        @Deprecated("Deprecated by Android")
        override fun onStatusChanged(
            provider: String?,
            statusValue: Int,
            extras: Bundle?
        ) = Unit

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        locationExecutor = Executors.newSingleThreadExecutor()
        locationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager

        buildInterface()

        if (hasRequiredPermissions()) {
            startCamera()
            startLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                PERMISSION_REQUEST
            )
        }
    }

    private fun buildInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 20, 32))
            setPadding(dp(10), dp(5), dp(10), dp(8))
        }

        val header = TextView(this).apply {
            text = "SchoolCam"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(5), 0, dp(5))
        }

        status = TextView(this).apply {
            text = "Starting camera..."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(180, 220, 255))
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }

        locationText = TextView(this).apply {
            text = "GPS: Waiting for location..."
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val locationScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(locationText)
        }

        cameraPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        cameraPanel.addView(
            previewView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        reviewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }

        reviewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }

        reviewPanel.addView(
            reviewImage,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val cameraFrame = FrameLayout(this).apply {
            addView(
                cameraPanel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            addView(
                reviewPanel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(5), dp(2), dp(2))
        }

        noteInput = EditText(this).apply {
            hint = "Photo note (optional)"
            textSize = 14f
            setSingleLine(false)
            minLines = 1
            maxLines = 2
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = roundedBackground(
                Color.rgb(35, 45, 60),
                10
            )
        }

        controlPanel.addView(
            noteInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                bottomMargin = dp(4)
            }
        )

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        captureButton = makeButton("CAPTURE") {
            capturePhoto()
        }

        switchButton = makeButton("SWITCH") {
            switchCamera()
        }

        flashButton = makeButton("FLASH OFF") {
            toggleFlash()
        }

        firstRow.addView(captureButton, weightParams())
        firstRow.addView(switchButton, weightParams())
        firstRow.addView(flashButton, weightParams())

        controlPanel.addView(firstRow)

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        saveButton = makeButton("SAVE PHOTO") {
            savePhoto()
        }

        reviewButton = makeButton("RECAPTURE") {
            returnToCamera()
        }

        secondRow.addView(saveButton, weightParams())
        secondRow.addView(reviewButton, weightParams())

        controlPanel.addView(secondRow)

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            )
        )

        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(30)
            )
        )

        root.addView(
            locationScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        root.addView(
            cameraFrame,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                3f
            )
        )

        root.addView(
            controlPanel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        updateReviewControls(false)
    }

    private fun hasRequiredPermissions(): Boolean {
        val cameraPermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

        val locationPermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return cameraPermission && locationPermission
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == PERMISSION_REQUEST) {
            if (hasRequiredPermissions()) {
                startCamera()
                startLocationUpdates()
            } else {
                status.text = "Camera and location permissions are required."

                Toast.makeText(
                    this,
                    "Please allow camera and location permissions.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startCamera() {
        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                status.text = "Camera error: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()

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

            camera = provider.bindToLifecycle(
                this,
                selector,
                preview,
                imageCapture
            )

            status.text = "Ready to capture"
            flashButton.text =
                if (flashEnabled) "FLASH ON" else "FLASH OFF"

        } catch (e: Exception) {
            status.text =
                "Cannot start camera: ${e.localizedMessage}"
        }
    }

    private fun switchCamera() {
        if (isReviewMode) return

        lensFacing =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

        flashEnabled = false
        bindCameraUseCases()
    }

    private fun toggleFlash() {
        if (isReviewMode) return

        val currentCamera = camera

        if (currentCamera == null) {
            status.text = "Camera is not ready."
            return
        }

        val hasFlashUnit = currentCamera.cameraInfo.hasFlashUnit()

        if (!hasFlashUnit) {
            Toast.makeText(
                this,
                "Flash is not available on this camera.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        flashEnabled = !flashEnabled

        try {
            currentCamera.cameraControl.enableTorch(flashEnabled)

            imageCapture?.flashMode =
                if (flashEnabled) {
                    ImageCapture.FLASH_MODE_ON
                } else {
                    ImageCapture.FLASH_MODE_OFF
                }

            flashButton.text =
                if (flashEnabled) "FLASH ON" else "FLASH OFF"

        } catch (e: Exception) {
            status.text = "Flash error: ${e.localizedMessage}"
        }
    }

    private fun capturePhoto() {
        if (isReviewMode) return

        val capture = imageCapture

        if (capture == null) {
            status.text = "Camera is not ready."
            return
        }

        captureButton.isEnabled = false
        status.text = "Capturing photo..."

        capturedAt = System.currentTimeMillis()

        val tempFile = File(
            cacheDir,
            "schoolcam_${capturedAt}.jpg"
        )

        val outputOptions =
            ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    try {
                        var bitmap =
                            BitmapFactory.decodeFile(tempFile.absolutePath)
                                ?: throw IllegalStateException(
                                    "Could not read captured photo."
                                )

                        bitmap = correctImageOrientation(
                            bitmap,
                            tempFile
                        )

                        tempFile.delete()

                        runOnUiThread {
                            capturedBitmap?.recycle()
                            capturedBitmap = bitmap
                            showReview(bitmap)
                            captureButton.isEnabled = true
                            status.text =
                                "Photo captured. Add a note and save."
                        }

                    } catch (e: Exception) {
                        tempFile.delete()

                        runOnUiThread {
                            captureButton.isEnabled = true
                            status.text =
                                "Capture failed: ${e.localizedMessage}"
                        }
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

    private fun correctImageOrientation(
        original: Bitmap,
        file: File
    ): Bitmap {
        try {
            val exif = ExifInterface(file.absolutePath)

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()

            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 ->
                    matrix.postRotate(90f)

                ExifInterface.ORIENTATION_ROTATE_180 ->
                    matrix.postRotate(180f)

                ExifInterface.ORIENTATION_ROTATE_270 ->
                    matrix.postRotate(270f)

                ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                    matrix.preScale(-1f, 1f)

                ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                    matrix.preScale(1f, -1f)

                else -> return original
            }

            val corrected = Bitmap.createBitmap(
                original,
                0,
                0,
                original.width,
                original.height,
                matrix,
                true
            )

            if (corrected !== original) {
                original.recycle()
            }

            return corrected

        } catch (_: Exception) {
            return original
        }
    }

    private fun showReview(bitmap: Bitmap) {
        reviewBitmap?.recycle()
        reviewBitmap = bitmap

        reviewImage.setImageBitmap(bitmap)

        isReviewMode = true
        cameraPanel.visibility = View.GONE
        reviewPanel.visibility = View.VISIBLE

        updateReviewControls(true)
    }

    private fun updateReviewControls(review: Boolean) {
        captureButton.visibility =
            if (review) View.GONE else View.VISIBLE

        switchButton.visibility =
            if (review) View.GONE else View.VISIBLE

        flashButton.visibility =
            if (review) View.GONE else View.VISIBLE

        saveButton.visibility =
            if (review) View.VISIBLE else View.GONE

        reviewButton.visibility =
            if (review) View.VISIBLE else View.GONE

        saveButton.isEnabled = true
        reviewButton.text = if (review) "RECAPTURE" else "RECAPTURE"
    }

    
private fun returnToCamera() {
    reviewImage.setImageDrawable(null)

    capturedBitmap?.recycle()
    capturedBitmap = null
    reviewBitmap = null

    isReviewMode = false
    cameraPanel.visibility = View.VISIBLE
    reviewPanel.visibility = View.GONE

    noteInput.setText("")
    status.text = "Ready to capture"
    updateReviewControls(false)
}

private fun startLocationUpdates() {
    if (
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        locationText.text = "GPS permission not granted"
        return
    }

    try {
        val providers = locationManager.getProviders(true)

        for (provider in providers) {
            val last = locationManager.getLastKnownLocation(provider)
            if (last != null) {
                capturedLocation = last
                updateLocationText(last)
                lookupAddress(last)
                break
            }
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            2000L,
            1f,
            locationListener,
            Looper.getMainLooper()
        )
    } catch (e: SecurityException) {
        locationText.text = "GPS permission unavailable"
    } catch (e: Exception) {
        locationText.text = "Waiting for GPS location"
    }
}

private fun updateLocationText(location: Location) {
    locationText.text = String.format(
        Locale.US,
        "Lat: %.6f | Long: %.6f | Accuracy: %.0f m",
        location.latitude,
        location.longitude,
        location.accuracy
    )
}

private fun lookupAddress(location: Location) {
    locationExecutor.execute {
        try {
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
                val address = addresses[0]
                val text = (0..address.maxAddressLineIndex)
                    .mapNotNull { address.getAddressLine(it) }
                    .joinToString(", ")

                if (text.isNotBlank()) {
                    capturedAddress = text
                }
            }
        } catch (_: Exception) {
            capturedAddress = "Address unavailable"
        }
    }
}

private fun savePhoto() {
    val source = capturedBitmap ?: run {
        status.text = "No photo to save"
        return
    }

    saveButton.isEnabled = false
    status.text = "Saving photo..."

    val note = noteInput.text.toString().trim()
    val location = capturedLocation
    val address = capturedAddress
    val time = capturedAt

    cameraExecutor.execute {
        var watermarked: Bitmap? = null
        var uri: Uri? = null

        try {
            watermarked = addWatermark(
                source,
                note,
                location,
                address,
                time
            )

            val filename = SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(Date(time))

            val values = ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "SchoolCam_$filename.jpg"
                )
                put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
                )
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/SchoolCam"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Unable to create image file")

            val stream = contentResolver.openOutputStream(uri!!)
                ?: error("Unable to open image file")

            stream.use {
                if (!watermarked.compress(
                        Bitmap.CompressFormat.JPEG,
                        95,
                        it
                    )
                ) {
                    error("Unable to encode image")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri!!, done, null, null)
            }

            runOnUiThread {
                status.text = "Saved to Pictures/SchoolCam"
                Toast.makeText(
                    this,
                    "Photo saved successfully",
                    Toast.LENGTH_LONG
                ).show()
                saveButton.isEnabled = true
                returnToCamera()
            }
        } catch (e: Exception) {
            uri?.let {
                try {
                    contentResolver.delete(it, null, null)
                } catch (_: Exception) {
                }
            }

            runOnUiThread {
                status.text = "Save failed: ${e.localizedMessage}"
                saveButton.isEnabled = true
            }
        } finally {
            watermarked?.recycle()
        }
    }
}

private fun addWatermark(
    original: Bitmap,
    note: String,
    location: Location?,
    address: String,
    time: Long
): Bitmap {
    val output = original.copy(Bitmap.Config.ARGB_8888, true)
        ?: error("Unable to prepare image")

    val canvas = Canvas(output)
    val scale = output.width / 1080f
    val padding = (22f * scale).coerceAtLeast(12f)
    val textSize = (29f * scale).coerceAtLeast(18f)
    val lineHeight = textSize * 1.4f
    val maxWidth = output.width - padding * 2

    val lines = mutableListOf<String>()
    lines.add("SchoolCam")

    lines.add(
        "Date/Time: " + SimpleDateFormat(
            "dd-MM-yyyy hh:mm:ss a",
            Locale.getDefault()
        ).format(Date(time))
    )

    if (location != null) {
        lines.add(
            String.format(
                Locale.US,
                "Latitude: %.6f",
                location.latitude
            )
        )
        lines.add(
            String.format(
                Locale.US,
                "Longitude: %.6f",
                location.longitude
            )
        )
        lines.add("Accuracy: %.0f m".format(location.accuracy))
    } else {
        lines.add("GPS location unavailable")
    }

    if (address.isNotBlank()) {
        lines.add("Address: $address")
    }

    if (note.isNotBlank()) {
        lines.add("Note: ${note.replace("\n", " ")}")
    }

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        this.textSize = textSize
        typeface = Typeface.create(
            "sans-serif-medium",
            Typeface.BOLD
        )
    }

    val wrapped = mutableListOf<String>()

    for (text in lines) {
        var current = ""
        for (word in text.split(" ")) {
            val candidate =
                if (current.isEmpty()) word else "$current $word"

            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotEmpty()) wrapped.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) wrapped.add(current)
    }

    val panelHeight = padding * 2 + lineHeight * wrapped.size
    val top = (output.height - panelHeight).coerceAtLeast(0f)

    val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 5, 12, 25)
    }

    canvas.drawRect(
        0f,
        top,
        output.width.toFloat(),
        output.height.toFloat(),
        panelPaint
    )

    wrapped.forEachIndexed { index, text ->
        val y = top + padding + textSize + index * lineHeight
        if (y < output.height) {
            canvas.drawText(text, padding, y, paint)
        }
    }

    return output
}

private fun makeButton(
    label: String,
    action: () -> Unit
): Button {
    return Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = roundedBackground(
            Color.rgb(34, 91, 155),
            10
        )
        setOnClickListener { action() }
    }
}

private fun roundedBackground(
    color: Int,
    radiusDp: Int
): GradientDrawable {
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }
}

private fun weightParams(): LinearLayout.LayoutParams {
    return LinearLayout.LayoutParams(
        0,
        dp(48),
        1f
    ).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }
}

private fun dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
}

override fun onDestroy() {
    try {
        locationManager.removeUpdates(locationListener)
    } catch (_: Exception) {
    }

    cameraExecutor.shutdown()
    locationExecutor.shutdown()

    reviewImage.setImageDrawable(null)
    capturedBitmap?.recycle()
    capturedBitmap = null
    reviewBitmap = null

    super.onDestroy()
}
}

