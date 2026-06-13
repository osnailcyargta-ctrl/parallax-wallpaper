package com.parallax.wallpaper

import android.Manifest
import android.app.WallpaperManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var previewImage: ImageView
    private lateinit var sensitivitySlider: SeekBar
    private lateinit var sensitivityLabel: TextView
    private var selectedUri: Uri? = null

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

        // Load saved sensitivity
        val savedSens = prefs.getFloat(ParallaxWallpaperService.KEY_SENSITIVITY, 5f)
        sensitivitySlider.progress = (savedSens * 10).toInt()
        updateSensLabel(savedSens)

        // Load saved image
        val savedUri = prefs.getString(ParallaxWallpaperService.KEY_IMAGE_URI, null)
        if (savedUri != null) {
            try {
                previewImage.setImageURI(Uri.parse(savedUri))
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
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            // Persist URI permission
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedUri = uri
            prefs.edit().putString(ParallaxWallpaperService.KEY_IMAGE_URI, uri.toString()).apply()
            previewImage.setImageURI(uri)
            Toast.makeText(this, "Image selected!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setAsWallpaper() {
        if (prefs.getString(ParallaxWallpaperService.KEY_IMAGE_URI, null) == null) {
            Toast.makeText(this, "Pick an image first!", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, ParallaxWallpaperService::class.java))
        }
        startActivity(intent)
    }
}