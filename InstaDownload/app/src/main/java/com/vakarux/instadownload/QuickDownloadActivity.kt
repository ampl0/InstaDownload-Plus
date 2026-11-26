package com.vakarux.instadownload

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

class QuickDownloadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val settings = AppSettings(this)

        if (sharedUrl.isNullOrBlank()) {
            finish()
            return
        }

        if (settings.easySaveEnabled) {
            // Easy save mode (default): start the background download immediately — no tap,
            // progress shown in the notification.
            val serviceIntent = Intent(this, QuickDownloadService::class.java).apply {
                action = QuickDownloadService.ACTION_DOWNLOAD
                putExtra(QuickDownloadService.EXTRA_URL, sharedUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            // Easy save off: hand the link to the app so the user can pick quality and confirm.
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_SHARED_URL, sharedUrl)
                }
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        } else {
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        finish()
    }
}
