package com.example.survey.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.example.survey.data.MediaItem
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import androidx.camera.core.Camera as CameraXCamera

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
            mediaActionSound?.load(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            Log.w(TAG, "MediaActionSound init failed", e)
        }
    }

    private fun playShutterSound() {
        try {
            mediaActionSound?.play(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK)
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Failed to play any shutter sound", fallbackEx)
            }
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

        Log.d(TAG, "Capturing photo - Config Orientation: ${if (isDeviceInLandscape) "LANDSCAPE" else "PORTRAIT"}")

        imageCapture.targetRotation = getDisplayRotation()

        val sessionDir = createSessionFolder(sessionName)
        val photoFile = File(sessionDir, "IMG_${System.currentTimeMillis()}.jpg")

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
                                withContext(Dispatchers.Main) { onSuccess(mediaItem) }
                            } catch (fallbackError: Exception) {
                                withContext(Dispatchers.Main) { onError(fallbackError) }
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    CoroutineScope(Dispatchers.Main).launch { onError(exception) }
                }
            }
        )
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

            val exif = ExifInterface(originalFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val rotatedBitmap = rotateBitmap(rawBitmap, orientation)

            val finalBitmap = if (isDeviceInLandscape && rotatedBitmap.width < rotatedBitmap.height) {
                rotateBitmap(rotatedBitmap, ExifInterface.ORIENTATION_ROTATE_90)
            } else if (!isDeviceInLandscape && rotatedBitmap.width > rotatedBitmap.height) {
                rotateBitmap(rotatedBitmap, ExifInterface.ORIENTATION_ROTATE_90)
            } else {
                rotatedBitmap
            }

            if (rotatedBitmap != finalBitmap && rotatedBitmap != rawBitmap) {
                rotatedBitmap.recycle()
            }
            if (rawBitmap != finalBitmap && rawBitmap != rotatedBitmap) {

            }

            val mutableBitmap = finalBitmap.copy(Bitmap.Config.ARGB_8888, true)
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

            if (isDeviceInLandscape) {
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
                val dstExif = ExifInterface(overlayFile.absolutePath)
                dstExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())

                val srcExif = ExifInterface(originalFile.absolutePath)
                val dateTags = arrayOf(
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL
                )
                dateTags.forEach { tag ->
                    srcExif.getAttribute(tag)?.let { dstExif.setAttribute(tag, it) }
                }
                srcExif.latLong?.let { ll ->
                    dstExif.setLatLong(ll[0], ll[1])
                }
                dstExif.saveAttributes()
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to copy EXIF", ex)
            }

            if (originalFile != overlayFile && originalFile.exists()) {
                originalFile.delete()
            }
            if (rawBitmap != finalBitmap) rawBitmap.recycle()
            if (rotatedBitmap != finalBitmap && rotatedBitmap != rawBitmap) rotatedBitmap.recycle()

            return overlayFile
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay", e)
            return originalFile
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM rotating bitmap", e)
            bitmap
        }
    }

    private fun saveLocationToExif(photoFile: File, latitude: Double, longitude: Double) {
        try {
            if (photoFile.exists() && latitude != 0.0 && longitude != 0.0) {
                val exif = ExifInterface(photoFile.absolutePath)
                exif.setLatLong(latitude, longitude)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving EXIF location", e)
        }
    }

    private fun triggerEnhancedMediaScan(file: File) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg", "video/mp4")
            ) { _, _ -> }
        } catch (e: Exception) {
            Log.e(TAG, "Error during media scan", e)
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
        } catch (e: Exception) {
            Log.w(TAG, "Shutdown error", e)
        }
    }
}