package com.signal.helper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val reqCapture = 1
    private lateinit var stake: EditText
    private lateinit var steps: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
            setBackgroundColor(0xFF1B1830.toInt())
        }
        fun label(t: String, size: Float = 15f) = TextView(this).apply {
            text = t; textSize = size; setTextColor(0xFFF1EFFB.toInt()); setPadding(0, 16, 0, 8)
        }
        root.addView(label("Signal Helper", 26f))
        root.addView(label("Pehle compounding likho, phir Shuru dabao. Phir apni trading app kholo, gol bubble screen par rahega."))
        root.addView(label("Shuru stake ($)"))
        stake = EditText(this).apply {
            setText("1"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(stake)
        root.addView(label("Compounding steps"))
        steps = EditText(this).apply {
            setText("5"); inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(steps)
        val go = Button(this).apply { text = "Shuru karo"; gravity = Gravity.CENTER }
        root.addView(go)
        root.addView(label("Yeh sirf andaza deta hai, jeet ki guarantee nahi. Pehle demo account par aazmao.", 13f))
        setContentView(root)

        go.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Pehle 'Display over other apps' allow karo", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, SignalService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
                .putExtra("stake", stake.text.toString().toDoubleOrNull() ?: 1.0)
                .putExtra("steps", steps.text.toString().toIntOrNull() ?: 5)
            startForegroundService(i)
            moveTaskToBack(true)
        } else if (requestCode == reqCapture) {
            Toast.makeText(this, "Screen share ki permission nahi mili", Toast.LENGTH_LONG).show()
        }
    }
}
