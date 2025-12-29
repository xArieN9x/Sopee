package com.example.cedokbooster

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*

class FloatingWidgetService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var bubbleImage: ImageView? = null
    private var bubbleText: TextView? = null

    // Coroutine untuk monitoring status
    private var monitoringJob: Job? = null
    private var isMonitoring = false
    private var isViewAdded = false

    // Broadcast receiver untuk status updates dari CoreEngine
    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "CORE_ENGINE_STATUS_UPDATE" -> {
                    val isRunning = intent.getBooleanExtra("is_running", false)
                    updateWidgetColor(isRunning)
                    Log.d(TAG, "Received status update: isRunning=$isRunning")
                }
                "TEST_BROADCAST" -> {
                    Log.d(TAG, "Test broadcast received - service is alive")
                }
            }
        }
    }

    companion object {
        const val TAG = "FloatingWidgetService"
        private var instance: FloatingWidgetService? = null
        
        fun isRunning(): Boolean = instance != null
        
        fun updateBubbleColor(isActive: Boolean) {
            instance?.updateWidgetColor(isActive)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() called")
        instance = this
        
        // Setup broadcast receiver
        val filter = android.content.IntentFilter().apply {
            addAction("CORE_ENGINE_STATUS_UPDATE")
            addAction("TEST_BROADCAST")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter)
        
        // Setup floating widget
        setupFloatingWidget()
        
        // Start monitoring
        startStatusMonitor()
    }

    private fun setupFloatingWidget() {
        // Check overlay permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 
            !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "No overlay permission")
            return
        }

        try {
            // Inflate view
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.TOP or Gravity.START
            params.x = 50  // Margin from left
            params.y = 150 // Margin from top

            // Get window manager and add view
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            floatingView?.let { view ->
                windowManager?.addView(view, params)
                isViewAdded = true
                Log.d(TAG, "Floating widget added to window")
                
                // Setup touch listener (MUST be on main thread)
                setupTouchListener(view, params)
            }

            // Set initial color (red = not active)
            updateWidgetColor(false)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup floating widget: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setupTouchListener(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                // Handle touch events on MAIN THREAD
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
                        try {
                            windowManager?.updateViewLayout(floatingView, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating view layout: ${e.message}")
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)

                        // If it's a click (not drag), open main activity
                        if (diffX < 10 && diffY < 10) {
                            openMainActivity()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun updateWidgetColor(isActive: Boolean) {
        // Run on main thread to update UI
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val drawableRes = if (isActive) {
                    R.drawable.green_circle
                } else {
                    R.drawable.red_circle
                }
                bubbleImage?.setImageResource(drawableRes)
                Log.d(TAG, "Widget color updated: ${if (isActive) "GREEN" else "RED"}")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widget color: ${e.message}")
            }
        }
    }

    private fun startStatusMonitor() {
        isMonitoring = true
        
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isMonitoring) {
                try {
                    // Send heartbeat log every 30 seconds
                    Log.d(TAG, "FloatingWidgetService heartbeat")
                    
                    // Check if CoreEngine is running via broadcast
                    val isEngineRunning = checkCoreEngineStatus()
                    
                    withContext(Dispatchers.Main) {
                        if (isEngineRunning) {
                            updateWidgetColor(true)
                        }
                    }
                    
                    delay(30000) // Check every 30 seconds
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error in status monitor: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    private fun checkCoreEngineStatus(): Boolean {
        // This should be replaced with actual CoreEngine status check
        // For now, send a test broadcast and see if we get response
        try {
            val testIntent = Intent("CHECK_ENGINE_STATUS")
            LocalBroadcastManager.getInstance(this).sendBroadcast(testIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending status check: ${e.message}")
        }
        return false
    }

    private fun openMainActivity() {
        Log.d(TAG, "Main activity opened from widget")
        try {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening main activity: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received")
        // Return START_STICKY to keep service running
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called")
        
        // Stop monitoring
        isMonitoring = false
        monitoringJob?.cancel()
        
        // Unregister receiver
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
        
        // Remove view from window
        if (isViewAdded && floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
                Log.d(TAG, "Floating widget removed from window")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing view: ${e.message}")
            }
        }
        
        // Clear instance
        instance = null
        
        super.onDestroy()
    }
}
