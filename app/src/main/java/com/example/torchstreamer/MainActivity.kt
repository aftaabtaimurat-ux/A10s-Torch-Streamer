package com.example.torchstreamer

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    
    // ⚠️ CHANGE THIS VALUE TO MATCH YOUR CURRENT WINDOWS PC IP ADDRESS
    private val serverUrl = "ws://192.168.1.43:3000" 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        connectWebSocket()
        startFullPowerCameraStreaming()
        Toast.makeText(this, "Streaming at 100% Full Capacity...", Toast.LENGTH_LONG).show()
    }

    private fun connectWebSocket() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {})
    }

    private fun startFullPowerCameraStreaming() {
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, null)
        } catch (e: SecurityException) { e.printStackTrace() }
    }

    private fun createCaptureSession() {
        val imageReader = android.media.ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            
            // SYNTAX FIX: Target explicit camera array indices directly
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            
            val bos = ByteArrayOutputStream()
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 40, bos)
            
            val payload = ByteString.of(*bos.toByteArray())
            webSocket?.send(payload)
            image.close()
        }, null)

        val surface = imageReader.surface
        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder?.addTarget(surface)
        builder?.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)

        cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                builder?.let { session.setRepeatingRequest(it.build(), null, null) }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, null)
    }
}
