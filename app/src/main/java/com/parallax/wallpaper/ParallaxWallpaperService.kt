package com.parallax.wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.hardware.*
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.abs

class ParallaxWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ParallaxEngine()

    inner class ParallaxEngine : Engine(), SensorEventListener {

        private lateinit var sensorManager: SensorManager
        private var gyroscope: Sensor? = null
        private lateinit var prefs: SharedPreferences

        private var rotX = 0f
        private var rotY = 0f
        private var targetX = 0f
        private var targetY = 0f
        private var smoothX = 0f
        private var smoothY = 0f

        private var sensitivity = 5f
        private var layers = mutableListOf<ParallaxLayer>()
        private var screenW = 0
        private var screenH = 0

        private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var running = false

        private val drawRunnable = object : Runnable {
            override fun run() {
                if (running) {
                    drawFrame()
                    handler.postDelayed(this, 16) // ~60fps
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            loadSettings()
            loadImage()
        }

        private fun loadSettings() {
            sensitivity = prefs.getFloat(KEY_SENSITIVITY, 5f)
        }

        private fun loadImage() {
            layers.clear()
            val uriStr = prefs.getString(KEY_IMAGE_URI, null) ?: return
            try {
                val uri = Uri.parse(uriStr)
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                val original = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return

                // Create 3 parallax layers from the same image with different scales/offsets
                // Layer 0 (back) - slightly zoomed out, moves least
                // Layer 1 (mid) - normal size
                // Layer 2 (front) - slightly zoomed in, moves most

                val w = original.width
                val h = original.height

                // Back layer - full image, slight zoom
                val backBmp = Bitmap.createScaledBitmap(original,
                    (w * 1.15f).toInt(), (h * 1.15f).toInt(), true)

                // Mid layer - normal
                val midBmp = Bitmap.createScaledBitmap(original,
                    (w * 1.1f).toInt(), (h * 1.1f).toInt(), true)

                // Front layer - zoomed in, draw with low opacity to simulate depth
                val frontBmp = Bitmap.createScaledBitmap(original,
                    (w * 1.05f).toInt(), (h * 1.05f).toInt(), true)

                layers.add(ParallaxLayer(backBmp, depthFactor = 0.3f, alpha = 255))
                layers.add(ParallaxLayer(midBmp, depthFactor = 0.6f, alpha = 200))
                layers.add(ParallaxLayer(frontBmp, depthFactor = 1.0f, alpha = 140))

                original.recycle()
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
                gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                handler.post(drawRunnable)
            } else {
                sensorManager.unregisterListener(this)
                handler.removeCallbacks(drawRunnable)
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                val dt = 0.016f
                rotX += event.values[0] * dt * sensitivity * 10f
                rotY += event.values[1] * dt * sensitivity * 10f
                // Clamp
                rotX = rotX.coerceIn(-80f, 80f)
                rotY = rotY.coerceIn(-80f, 80f)
                // Drift back to center slowly
                rotX *= 0.98f
                rotY *= 0.98f
                targetX = rotY
                targetY = rotX
            } else if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val mat = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(mat, event.values)
                targetX = -mat[1] * sensitivity * 60f
                targetY = mat[3] * sensitivity * 60f
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                canvas?.let { c ->
                    // Smooth interpolation
                    smoothX += (targetX - smoothX) * 0.08f
                    smoothY += (targetY - smoothY) * 0.08f

                    c.drawColor(Color.BLACK)

                    if (layers.isEmpty()) {
                        // Draw placeholder
                        val p = Paint().apply { color = Color.DKGRAY; textSize = 40f; textAlign = Paint.Align.CENTER }
                        c.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), p)
                        p.color = Color.WHITE
                        c.drawText("Set image in app", screenW / 2f, screenH / 2f, p)
                        return
                    }

                    // Draw each layer with parallax offset
                    layers.forEachIndexed { _, layer ->
                        val offsetX = smoothX * layer.depthFactor
                        val offsetY = smoothY * layer.depthFactor

                        val bw = layer.bitmap.width.toFloat()
                        val bh = layer.bitmap.height.toFloat()

                        // Center + offset
                        val left = (screenW - bw) / 2f + offsetX
                        val top = (screenH - bh) / 2f + offsetY

                        drawPaint.alpha = layer.alpha
                        c.drawBitmap(layer.bitmap, left, top, drawPaint)
                    }
                    drawPaint.alpha = 255
                }
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            running = false
            sensorManager.unregisterListener(this)
            handler.removeCallbacks(drawRunnable)
            layers.forEach { it.bitmap.recycle() }
            layers.clear()
        }
    }

    data class ParallaxLayer(
        val bitmap: Bitmap,
        val depthFactor: Float,
        val alpha: Int
    )

    companion object {
        const val PREFS = "parallax_prefs"
        const val KEY_IMAGE_URI = "image_uri"
        const val KEY_SENSITIVITY = "sensitivity"
    }
}