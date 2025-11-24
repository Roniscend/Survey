package com.example.survey.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.survey.data.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.camera.core.Camera as CameraXCamera
import androidx.exifinterface.media.ExifInterface
import android.view.Surface
import android.view.WindowManager
import android.media.MediaActionSound
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import android.content.res.Configuration

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: CameraXCamera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var currentRecording: Recording? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var mediaActionSound: MediaActionSound? = null

    companion object {
        private const val APP_FOLDER_NAME = "SurveyApp"
        private const val TAG = "CameraController"
    }

    init {
        try {
            mediaActionSound = MediaActionSound()
        } catch (e: Exception) {
            Log.w(TAG, "MediaActionSound init failed", e)
        }
    }

    private fun playShutterSound() {
        try {
            mediaActionSound?.play(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            Log.w(TAG, "Shutter sound failed", e)
        }
    }

    @SuppressLint("RestrictedApi")
    fun initialize(surfaceProvider: Preview.SurfaceProvider) {
        this.surfaceProvider = surfaceProvider
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(surfaceProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing camera", e)
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(surfaceProvider: Preview.SurfaceProvider) {
        val cameraProvider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(getDisplayRotation())
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Error binding camera use cases", exc)
            exc.printStackTrace()
        }
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        surfaceProvider?.let { provider ->
            bindCameraUseCases(provider)
        }
    }

    private fun createSessionFolder(sessionName: String): File {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val sessionDir = File(picturesDir, "$APP_FOLDER_NAME${File.separator}$sessionName")

        try {
            if (!sessionDir.exists()) {
                val created = sessionDir.mkdirs()
                Log.d(TAG, "Session folder created: $created at ${sessionDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating session folder", e)
        }

        return sessionDir
    }

    fun capturePhoto(
        sessionName: String,
        timestamp: String,
        location: String,
        onSuccess: (MediaItem) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val imageCapture = imageCapture ?: run {
            onError(Exception("ImageCapture not initialized"))
            return
        }

        playShutterSound()

        val deviceOrientation = context.resources.configuration.orientation
        val isDeviceInLandscape = (deviceOrientation == Configuration.ORIENTATION_LANDSCAPE)

        Log.d(TAG, "Capturing photo - Device configuration.orientation: ${if (isDeviceInLandscape) "LANDSCAPE" else "PORTRAIT"}")

        imageCapture.targetRotation = getDisplayRotation()

        val sessionDir = createSessionFolder(sessionName)
        val photoFile = File(sessionDir, "IMG_${System.currentTimeMillis()}.jpg")

        Log.d(TAG, "Capturing photo to: ${photoFile.absolutePath}")

        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputFileOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo file written: ${photoFile.absolutePath}")

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val locationResult = withTimeoutOrNull(2000L) {
                                val locationHelper = LocationHelper(context)
                                locationHelper.getCurrentLocationAndAddress()
                            }

                            val (coordinates, detailedAddress) = locationResult ?: (Pair(0.0, 0.0) to location)

                            if (coordinates.first != 0.0 && coordinates.second != 0.0) {
                                saveLocationToExif(photoFile, coordinates.first, coordinates.second)
                            }

                            val overlayFile = addOverlayToImage(
                                photoFile,
                                sessionName,
                                timestamp,
                                detailedAddress,
                                isDeviceInLandscape
                            )

                            triggerEnhancedMediaScan(overlayFile)

                            val mediaItem = MediaItem(
                                uri = Uri.fromFile(overlayFile),
                                file = overlayFile,
                                isVideo = false,
                                timestamp = timestamp,
                                location = detailedAddress,
                                sessionName = sessionName
                            )

                            withContext(Dispatchers.Main) {
                                Log.d(TAG, "MediaItem ready: ${overlayFile.name}")
                                onSuccess(mediaItem)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing saved image", e)

                            try {
                                val overlayFile = addOverlayToImage(
                                    photoFile,
                                    sessionName,
                                    timestamp,
                                    location,
                                    isDeviceInLandscape
                                )
                                triggerEnhancedMediaScan(overlayFile)

                                val mediaItem = MediaItem(
                                    uri = Uri.fromFile(overlayFile),
                                    file = overlayFile,
                                    isVideo = false,
                                    timestamp = timestamp,
                                    location = location,
                                    sessionName = sessionName
                                )

                                withContext(Dispatchers.Main) {
                                    onSuccess(mediaItem)
                                }
                            } catch (fallbackError: Exception) {
                                Log.e(TAG, "Fallback processing failed", fallbackError)
                                withContext(Dispatchers.Main) {
                                    onError(fallbackError)
                                }
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    CoroutineScope(Dispatchers.Main).launch {
                        onError(exception)
                    }
                }
            }
        )
    }

    private fun saveLocationToExif(photoFile: File, latitude: Double, longitude: Double) {
        try {
            if (photoFile.exists() && latitude != 0.0 && longitude != 0.0) {
                val exif = ExifInterface(photoFile.absolutePath)

                val existingLatLng = exif.latLong
                if (existingLatLng == null || (existingLatLng[0] == 0.0 && existingLatLng[1] == 0.0)) {
                    exif.setLatLong(latitude, longitude)
                    exif.saveAttributes()
                    Log.d(TAG, "EXIF location saved: $latitude, $longitude")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving EXIF location", e)
            e.printStackTrace()
        }
    }

    private fun triggerEnhancedMediaScan(file: File) {
        try {
            Log.d(TAG, "Triggering media scan for: ${file.absolutePath}")

            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg", "video/mp4")
            ) { path, uri ->
                Log.d(TAG, "Media scanned successfully: $path -> $uri")
            }

            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(file)
            }
            context.sendBroadcast(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Error during media scan", e)
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    fun startVideoRecording(
        sessionName: String,
        timestamp: String,
        location: String,
        onSuccess: (MediaItem) -> Unit,
        onError: (Exception) -> Unit
    ): Boolean {
        val videoCapture = videoCapture ?: return false

        if (currentRecording != null) {
            currentRecording?.stop()
            currentRecording = null
            return false
        }

        val sessionDir = createSessionFolder(sessionName)
        val videoFile = File(
            sessionDir,
            "VID_${System.currentTimeMillis()}.mp4"
        )

        Log.d(TAG, "Starting video recording to: ${videoFile.absolutePath}")

        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        currentRecording = videoCapture.output
            .prepareRecording(context, outputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "Video recording started")
                    }
                    is VideoRecordEvent.Finalize -> {
                        currentRecording = null
                        if (!recordEvent.hasError()) {
                            Log.d(TAG, "Video recording completed: ${videoFile.name}")
                            triggerEnhancedMediaScan(videoFile)

                            val mediaItem = MediaItem(
                                uri = Uri.fromFile(videoFile),
                                file = videoFile,
                                isVideo = true,
                                timestamp = timestamp,
                                location = location,
                                sessionName = sessionName
                            )
                            onSuccess(mediaItem)
                        } else {
                            Log.e(TAG, "Video recording failed: ${recordEvent.error}")
                            onError(Exception("Video recording failed: ${recordEvent.error}"))
                            try {
                                videoFile.delete()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error deleting failed video file", e)
                            }
                        }
                    }
                }
            }
        return true
    }

    fun stopVideoRecording() {
        currentRecording?.stop()
        currentRecording = null
    }

    fun isRecording(): Boolean {
        return currentRecording != null
    }

    private fun addOverlayToImage(
        originalFile: File,
        sessionName: String,
        timestamp: String,
        location: String,
        isDeviceInLandscape: Boolean
    ): File {
        return try {
            val rawBitmap = BitmapFactory.decodeFile(originalFile.absolutePath)
                ?: throw Exception("Failed to decode image file")

            val isBitmapLandscape = rawBitmap.width > rawBitmap.height
            val shouldUseLandscapeLayout = isDeviceInLandscape && isBitmapLandscape

            Log.d(TAG, "==========================================")
            Log.d(TAG, "WATERMARK ORIENTATION DEBUG: ${originalFile.name}")
            Log.d(TAG, "Device orientation (config): ${if (isDeviceInLandscape) "LANDSCAPE" else "PORTRAIT"}")
            Log.d(TAG, "Bitmap dimensions: ${rawBitmap.width} x ${rawBitmap.height}")
            Log.d(TAG, "Bitmap shape: ${if (isBitmapLandscape) "LANDSCAPE" else "PORTRAIT"}")
            Log.d(TAG, "Final watermark layout: ${if (shouldUseLandscapeLayout) "LANDSCAPE" else "PORTRAIT"}")
            Log.d(TAG, "==========================================")

            val mutableBitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)

            val paint = Paint().apply {
                color = Color.WHITE
                textSize = 36f
                isAntiAlias = true
                isDither = true
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = 1f
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }

            if (shouldUseLandscapeLayout) {
                Log.d(TAG, "Applying LANDSCAPE watermark")

                val sideMargin = 40f
                val centerY = mutableBitmap.height / 2f

                canvas.save()
                canvas.translate(sideMargin, centerY)
                canvas.rotate(-90f)
                val timestampBounds = Rect()
                paint.getTextBounds(timestamp, 0, timestamp.length, timestampBounds)
                canvas.drawText(timestamp, -timestampBounds.width() / 2f, 0f, paint)
                canvas.restore()

                canvas.save()
                canvas.translate(mutableBitmap.width - sideMargin, centerY)
                canvas.rotate(90f)
                val sessionBounds = Rect()
                paint.getTextBounds(sessionName, 0, sessionName.length, sessionBounds)
                canvas.drawText(sessionName, -sessionBounds.width() / 2f, 0f, paint)
                canvas.restore()

                val maxLineWidth = mutableBitmap.height - 160f
                val locationLines = wrapText(location, paint, maxLineWidth)

                canvas.save()
                canvas.translate(mutableBitmap.width - sideMargin - 20f, mutableBitmap.height - 80f)
                canvas.rotate(90f)
                var currentY = 0f
                locationLines.reversed().forEach { line ->
                    canvas.drawText(line, 0f, currentY, paint)
                    currentY -= (paint.textSize + 6f)
                }
                canvas.restore()

            } else {
                Log.d(TAG, "Applying PORTRAIT watermark")
                val maxLineWidth = mutableBitmap.width - 60f
                val locationLines = wrapText(location, paint, maxLineWidth)

                canvas.drawText(timestamp, 20f, 50f, paint)

                val sessionNameBounds = Rect()
                paint.getTextBounds(sessionName, 0, sessionName.length, sessionNameBounds)
                val sessionNameX = mutableBitmap.width - sessionNameBounds.width() - 20f
                canvas.drawText(sessionName, sessionNameX, 50f, paint)

                val lineSpacing = 6f
                val totalLocationHeight = locationLines.size * (paint.textSize + lineSpacing)
                var currentY = mutableBitmap.height - totalLocationHeight - 15f

                locationLines.forEach { line ->
                    val lineBounds = Rect()
                    paint.getTextBounds(line, 0, line.length, lineBounds)
                    val lineX = (mutableBitmap.width - lineBounds.width()) / 2f
                    canvas.drawText(line, lineX, currentY, paint)
                    currentY += paint.textSize + lineSpacing
                }
            }

            val overlayFile = File(
                originalFile.parent,
                "IMG_${System.currentTimeMillis()}_watermarked.jpg"
            )

            FileOutputStream(overlayFile).use { out ->
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            try {
                val srcExif = ExifInterface(originalFile.absolutePath)
                val dstExif = ExifInterface(overlayFile.absolutePath)

                srcExif.latLong?.let { ll ->
                    dstExif.setLatLong(ll[0], ll[1])
                }

                val dateTags = arrayOf(
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED
                )
                dateTags.forEach { tag ->
                    srcExif.getAttribute(tag)?.let { dstExif.setAttribute(tag, it) }
                }

                dstExif.saveAttributes()
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to copy EXIF", ex)
            }

            if (originalFile != overlayFile && originalFile.exists()) {
                originalFile.delete()
            }

            Log.d(TAG, "Overlay added: ${overlayFile.name}")
            overlayFile
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay", e)
            originalFile
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val testWidth = paint.measureText(testLine)

            if (testWidth <= maxWidth) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                    currentLine = word
                } else {
                    lines.add(word)
                }
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        return lines.ifEmpty { listOf(text) }
    }

    fun getSessionFolderPath(sessionName: String): File {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        return File(picturesDir, "$APP_FOLDER_NAME${File.separator}$sessionName")
    }

    fun getSessionMediaFiles(sessionName: String): List<File> {
        return try {
            val sessionDir = getSessionFolderPath(sessionName)
            if (sessionDir.exists() && sessionDir.isDirectory) {
                val files = sessionDir.listFiles { file ->
                    file.extension.lowercase() in listOf("jpg", "jpeg", "png", "mp4", "avi", "mov")
                }?.sortedByDescending { it.lastModified() } ?: emptyList()

                Log.d(TAG, "Found ${files.size} media files in session: $sessionName")
                files
            } else {
                Log.w(TAG, "Session directory doesn't exist: ${sessionDir.absolutePath}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting session media files", e)
            emptyList()
        }
    }

    private fun getDisplayRotation(): Int {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                context.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                    ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } catch (e: Exception) {
            Surface.ROTATION_0
        }
    }

    fun shutdown() {
        try {
            mediaActionSound?.release()
            mediaActionSound = null
            cameraExecutor.shutdown()
            Log.d(TAG, "CameraController shutdown")
        } catch (e: Exception) {
            Log.w(TAG, "Shutdown error", e)
        }
    }
}
