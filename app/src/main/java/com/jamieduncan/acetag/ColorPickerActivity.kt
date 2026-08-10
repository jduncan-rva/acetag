package com.jamieduncan.acetag

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.jamieduncan.acetag.databinding.ActivityColorPickerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ColorPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COLOR_HEX = "color_hex"
    }

    private lateinit var binding: ActivityColorPickerBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var capturedHex: String? = null

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to sample a color.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityColorPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.captureButton.setOnClickListener { capture() }
        binding.useColorButton.setOnClickListener {
            capturedHex?.let {
                setResult(RESULT_OK, intent.putExtra(EXTRA_COLOR_HEX, it))
                finish()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder().build()
            imageCapture = capture

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val capture = imageCapture ?: return
        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val hex = sampleCenterColor(image)
                    image.close()
                    runOnUiThread { onSampled(hex) }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(
                            this@ColorPickerActivity,
                            "Capture failed: ${exception.message}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    /** JPEG bytes decode fine on any API level, regardless of preview/sensor rotation quirks. */
    private fun sampleCenterColor(image: ImageProxy): String? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        val boxSize = (minOf(bitmap.width, bitmap.height) * 0.08).toInt().coerceAtLeast(4)
        val cx = bitmap.width / 2
        val cy = bitmap.height / 2
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0
        for (y in (cy - boxSize)..(cy + boxSize)) {
            for (x in (cx - boxSize)..(cx + boxSize)) {
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    val px = bitmap.getPixel(x, y)
                    rSum += Color.red(px)
                    gSum += Color.green(px)
                    bSum += Color.blue(px)
                    count++
                }
            }
        }
        bitmap.recycle()
        if (count == 0) return null
        val r = (rSum / count).toInt()
        val g = (gSum / count).toInt()
        val b = (bSum / count).toInt()
        return String.format("#%02x%02x%02x", r, g, b)
    }

    private fun onSampled(hex: String?) {
        if (hex == null) {
            Toast.makeText(this, "Could not read color, try again.", Toast.LENGTH_SHORT).show()
            return
        }
        capturedHex = hex
        val bg = (binding.previewSwatch.background as GradientDrawable).mutate() as GradientDrawable
        bg.setColor(Color.parseColor(hex))
        binding.previewSwatch.background = bg
        binding.useColorButton.isEnabled = true
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
