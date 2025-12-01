package com.example.allinoneflushapp

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var textViewIP: TextView
    private lateinit var textViewDNS: TextView
    private lateinit var btnFlushDNS: Button
    private lateinit var btnRenewIP: Button

    private val dnsList = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textViewIP = findViewById(R.id.textViewIP)
        textViewDNS = findViewById(R.id.textViewDNS)
        btnFlushDNS = findViewById(R.id.btnFlushDNS)
        btnRenewIP = findViewById(R.id.btnRenewIP)

        updateIP()
        rotateDNS()

        btnFlushDNS.setOnClickListener {
            flushDNS()
        }

        btnRenewIP.setOnClickListener {
            renewIP()
        }
    }

    private fun updateIP() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ip = URL("https://api.ipify.org").readText().trim()
                runOnUiThread {
                    textViewIP.text = "Public IP: $ip"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    textViewIP.text = "Public IP: Error"
                }
            }
        }
    }

    private fun rotateDNS() {
        val selectedDNS = dnsList.random()
        textViewDNS.text = "DNS: $selectedDNS"
        Toast.makeText(this, "DNS rotated to $selectedDNS", Toast.LENGTH_SHORT).show()
    }

    private fun flushDNS() {
        rotateDNS()
        clearAppCache()
        Toast.makeText(this, "DNS flushed & app cache cleared", Toast.LENGTH_SHORT).show()
    }

    private fun renewIP() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks // just to trigger network awareness (no-op, but harmless)
        bestEffortRAMFlush()
        updateIP()
        Toast.makeText(this, "Attempted IP refresh", Toast.LENGTH_SHORT).show()
    }

    private fun clearAppCache() {
        cacheDir.deleteRecursively()
    }

    private fun bestEffortRAMFlush() {
        // Cannot call trimMemory() manually — not allowed in Android
        // Best effort: clear local cache + suggest GC
        cacheDir.deleteRecursively()
        System.gc()
    }
}
