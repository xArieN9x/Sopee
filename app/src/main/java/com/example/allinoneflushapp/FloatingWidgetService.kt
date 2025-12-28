package com.example.allinoneflushapp

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.*

class FloatingWidgetService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var bubbleImage: ImageView? = null
    private var bubbleText: TextView? = null

    // Coroutine untuk monitoring status
    private var monitoringJob: Job? = null
    private var isMonitoring = true

    companion object {
        private var instance: FloatingWidgetService? = null
        fun isRunning() = instance != null
        
        // Fungsi untuk update warna dari luar (contoh: dari CoreEngine)
        fun updateBubbleColor(isActive: Boolean) {
            instance?.updateColor(isActive)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Inflate view dari layout
        floatingView = LayoutInflater.from(this).inflate(R.layout.widget_layout, null)
        bubbleImage = floatingView?.findViewById(R.id.floatingBubble)
        bubbleText = floatingView?.findViewById(R.id.bubbleText)

        // Setup window parameters
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        // Add view ke window manager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager?.addView(floatingView, params)

        // Setup drag and click listener
        setupTouchListener(params)

        // Start monitoring status
        startStatusMonitor()
    }

    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)

                        // Jika click (bukan drag), buka MainActivity
                        if (diffX < 10 && diffY < 10) openMainActivity()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun updateColor(isActive: Boolean) {
        // Update warna bubble di main thread
        CoroutineScope(Dispatchers.Main).launch {
            bubbleImage?.setImageResource(
                if (isActive) R.drawable.green_smooth else R.drawable.red_smooth
            )
        }
    }

    private fun startStatusMonitor() {
        // Gunakan coroutine untuk monitor status
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isMonitoring) {
                try {
                    // Check jika CoreEngine sedang running
                    val isEngineRunning = checkCoreEngineStatus()
                    
                    // Check jika app Panda sedang running (optional)
                    val isPandaRunning = isAppRunning("com.logistics.rider.foodpanda")
                    
                    // Update warna berdasarkan status (contoh: hijau jika kedua-dua true)
                    val shouldBeGreen = isEngineRunning && isPandaRunning
                    
                    withContext(Dispatchers.Main) {
                        bubbleImage?.setImageResource(
                            if (shouldBeGreen) R.drawable.green_smooth else R.drawable.red_smooth
                        )
                    }
                    
                    delay(1500) // Check setiap 1.5 saat
                } catch (e: Exception) {
                    // Log error jika perlu
                    e.printStackTrace()
                    delay(5000) // Delay lebih lama jika ada error
                }
            }
        }
    }

    // Fungsi untuk check status CoreEngine
    private fun checkCoreEngineStatus(): Boolean {
        // Cara 1: Guna LocalBroadcast untuk dapat status dari CoreEngine
        // Untuk sekarang, return false dulu - akan diupdate nanti
        return false
    }

    // Fungsi helper untuk check app running
    private fun isAppRunning(packageName: String): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.runningAppProcesses?.any { it.processName == packageName } == true
        } catch (e: Exception) {
            false
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
    }

    override fun onDestroy() {
        // Stop monitoring
        isMonitoring = false
        monitoringJob?.cancel()
        
        // Remove view dari window
        floatingView?.let { windowManager?.removeView(it) }
        
        // Clear instance
        instance = null
        
        super.onDestroy()
    }
}
