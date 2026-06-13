package com.parallax.wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    inner class ParallaxEngine : Engine(), SensorEventListener {

        private lateinit var sensorManager: SensorManager
        private var gyroscope: Sensor? = null
        private lateinit var prefs: SharedPreferences
        private val drawHandler = Handler(Looper.getMainLooper())

        private var targetX = 0f
        private var targetY = 0f
        private var smoothX = 0f
        private var smoothY = 0f
        private var rotX = 0f
        private var rotY = 0f

        private var sensitivity = 5f
        private val layers = mutableListOf<ParallaxLayer>()
        private var screenW = 0
        private var screenH = 0
        private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var running = false

        private val drawRunnable = object : Runnable {
            override fun run() {
                if (running) {
                    drawFrame()
                    drawHandler.postDelayed(this, 16)
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        }

        private fun loadSettings() {
            sensitivity = prefs.getFloat(KEY_SENSITIVITY, 5f)
        }

        private fun loadImage() {
            layers.forEach { it.bitmap.recycle() }
            layers.clear()

            val path = prefs.getString(KEY_IMAGE_PATH, null) ?: return
            val file = File(path)
            if (!file.exists()) return

            try {
                // Load dengan sample size biar aman
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)

                val maxSize = 1200
                var sample = 1
                while (opts.outWidth / sample > maxSize || opts.outHeight / sample > maxSize) {
                    sample *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                val original = BitmapFactory.decodeFile(path, decodeOpts) ?: return

                val w = original.width
                val h = original.height

                // 3 layers dengan scale berbeda
                layers.add(ParallaxLayer(
                    Bitmap.createScaledBitmap(original, (w * 1.2f).toInt(), (h * 1.2f).toInt(), true),
                    depthFactor = 0.25f, alpha = 255
                ))
                layers.add(ParallaxLayer(
                    Bitmap.createScaledBitmap(original, (w * 1.1f).toInt(), (h * 1.1f).toInt(), true),
                    depthFactor = 0.6f, alpha = 200
                ))
                layers.add(ParallaxLayer(
                    original,
                    depthFactor = 1.0f, alpha = 150
                ))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            screenW = width
            screenH = height
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            running = visible
            if (visible) {
                loadSettings()
                loadImage()
                gyroscope?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
                drawHandler.post(drawRunnable)
            } else {
                sensorManager.unregisterListener(this)
                drawHandler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            val dt = 0.016f
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    rotX += event.values[0] * dt * sensitivity * 10f
                    rotY += event.values[1] * dt * sensitivity * 10f
                    rotX = rotX.coerceIn(-80f, 80f)
                    rotY = rotY.coerceIn(-80f, 80f)
                    rotX *= 0.98f
                    rotY *= 0.98f
                    targetX = rotY
                    targetY = rotX
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val mat = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(mat, event.values)
                    targetX = -mat[1] * sensitivity * 60f
                    targetY = mat[3] * sensitivity * 60f
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        private fun drawFrame() {
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas() ?: return
                smoothX += (targetX - smoothX) * 0.08f
                smoothY += (targetY - smoothY) * 0.08f

                canvas.drawColor(Color.BLACK)

                if (layers.isEmpty()) {
                    val p = Paint().apply {
                        color = Color.parseColor("#444444")
                        textSize = 38f
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    canvas.drawText("Buka app untuk pilih gambar", screenW / 2f, screenH / 2f, p)
                    return
                }

                layers.forEach { layer ->
                    val ox = smoothX * layer.depthFactor
                    val oy = smoothY * layer.depthFactor
                    val left = (screenW - layer.bitmap.width) / 2f + ox
                    val top = (screenH - layer.bitmap.height) / 2f + oy
                    drawPaint.alpha = layer.alpha
                    canvas.drawBitmap(layer.bitmap, left, top, drawPaint)
                }
                drawPaint.alpha = 255
            } finally {
                canvas?.let { surfaceHolder.unlockCanvasAndPost(it) }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            running = false
            sensorManager.unregisterListener(this)
            drawHandler.removeCallbacks(drawRunnable)
            layers.forEach { it.bitmap.recycle() }
            layers.clear()
        }
    }

    data class ParallaxLayer(val bitmap: Bitmap, val depthFactor: Float, val alpha: Int)

    companion object {
        const val PREFS = "parallax_prefs"
        const val KEY_IMAGE_PATH = "image_path"
        const val KEY_SENSITIVITY = "sensitivity"
    }
}