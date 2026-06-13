package com.parallax.wallpaper

import android.Manifest
import android.app.WallpaperManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var previewImage: ImageView
    private lateinit var sensitivitySlider: SeekBar
    private lateinit var sensitivityLabel: TextView

    companion object {
        const val REQ_IMAGE = 201
        const val REQ_PERM = 202
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(ParallaxWallpaperService.PREFS, Context.MODE_PRIVATE)

        previewImage = findViewById(R.id.preview_image)
        sensitivitySlider = findViewById(R.id.sensitivity_slider)
        sensitivityLabel = findViewById(R.id.sensitivity_label)

        val savedSens = prefs.getFloat(ParallaxWallpaperService.KEY_SENSITIVITY, 5f)
        sensitivitySlider.progress = (savedSens * 10).toInt()
        updateSensLabel(savedSens)

        // Load saved preview
        val savedPath = prefs.getString(ParallaxWallpaperService.KEY_IMAGE_PATH, null)
        if (savedPath != null && File(savedPath).exists()) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bm = BitmapFactory.decodeFile(savedPath, opts)
                previewImage.setImageBitmap(bm)
            } catch (e: Exception) {}
        }

        sensitivitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val sens = progress / 10f
                updateSensLabel(sens)
                prefs.edit().putFloat(ParallaxWallpaperService.KEY_SENSITIVITY, sens).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_pick_image).setOnClickListener {
            checkPermAndPickImage()
        }

        findViewById<Button>(R.id.btn_set_wallpaper).setOnClickListener {
            setAsWallpaper()
        }
    }

    private fun updateSensLabel(sens: Float) {
        sensitivityLabel.text = "Sensitivity: ${String.format("%.1f", sens)}"
    }

    private fun checkPermAndPickImage() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(perm), REQ_PERM)
        } else {
            pickImage()
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, REQ_IMAGE)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == REQ_PERM && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            pickImage()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            copyImageToInternal(uri)
        }
    }

    private fun copyImageToInternal(uri: Uri) {
        try {
            // Copy gambar ke internal storage — hindari URI permission issues
            val inStream = contentResolver.openInputStream(uri) ?: run {
                Toast.makeText(this, "Gagal buka gambar", Toast.LENGTH_SHORT).show()
                return
            }

            // Decode dengan downsample biar ga OOM
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inStream, null, opts)
            inStream.close()

            val maxSize = 2048
            var sample = 1
            while (opts.outWidth / sample > maxSize || opts.outHeight / sample > maxSize) {
                sample *= 2
            }

            val inStream2 = contentResolver.openInputStream(uri) ?: return
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeStream(inStream2, null, decodeOpts)
            inStream2.close()

            if (bitmap == null) {
                Toast.makeText(this, "Gagal decode gambar", Toast.LENGTH_SHORT).show()
                return
            }

            // Save ke internal storage
            val file = File(filesDir, "wallpaper_source.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()

            val path = file.absolutePath
            prefs.edit().putString(ParallaxWallpaperService.KEY_IMAGE_PATH, path).apply()

            // Show preview
            val previewOpts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val previewBm = BitmapFactory.decodeFile(path, previewOpts)
            previewImage.setImageBitmap(previewBm)

            Toast.makeText(this, "Gambar dipilih!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setAsWallpaper() {
        if (prefs.getString(ParallaxWallpaperService.KEY_IMAGE_PATH, null) == null) {
            Toast.makeText(this, "Pilih gambar dulu!", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, ParallaxWallpaperService::class.java))
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}