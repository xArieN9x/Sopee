package com.example.cedokbooster

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.content.ContextCompat

class FloatingWidgetService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isActive = false

    companion object {
        private const val TAG = "FloatingWidgetService"
        const val ACTION_START_WIDGET = "com.example.cedokbooster.START_WIDGET"
        const val ACTION_STOP_WIDGET = "com.example.cedokbooster.STOP_WIDGET"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FloatingWidgetService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WIDGET -> {
                isActive = intent.getBooleanExtra("isActive", false)
                if (floatingView == null) {
                    showFloatingWidget()
                } else {
                    updateWidgetColor()
                }
            }
            ACTION_STOP_WIDGET -> {
                removeFloatingWidget()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun showFloatingWidget() {
        if (floatingView != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatingView = LayoutInflater.from(this).inflate(R.layout.widget_layout, null)

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
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = 100
        }

        windowManager?.addView(floatingView, params)
        updateWidgetColor()
        setupTouchListener(params)

        Log.d(TAG, "Floating widget shown")
    }

    private fun updateWidgetColor() {
        floatingView?.findViewById<TextView>(R.id.tvWidget)?.apply {
            if (isActive) {
                setBackgroundColor(Color.GREEN)
            } else {
                setBackgroundColor(Color.RED)
            }
        }
    }

    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    
                    // If it's a click (not a drag)
                    if (Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) {
                        openMainActivity()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        Log.d(TAG, "Main activity opened from widget")
    }

    private fun removeFloatingWidget() {
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
            Log.d(TAG, "Floating widget removed")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingWidget()
        Log.d(TAG, "FloatingWidgetService destroyed")
    }
}
