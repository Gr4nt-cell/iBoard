package com.gr4nt.iboard.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Permission is asked only after a deliberate microphone tap, never during setup or typing. */
class MicrophonePermissionActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "Microphone allowed. Tap the keyboard mic to dictate offline."
            else "Microphone is off. Typing and predictions still work.", Toast.LENGTH_LONG).show()
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) finish()
        else if (savedInstanceState == null) {
            val asked = getSharedPreferences("mic_permission", MODE_PRIVATE)
            if (asked.getBoolean("asked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                android.app.AlertDialog.Builder(this).setTitle("Microphone access is off")
                    .setMessage("Allow microphone access in Android Settings to use offline dictation.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName")))
                        finish()
                    }.setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            } else {
                asked.edit().putBoolean("asked", true).apply()
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
