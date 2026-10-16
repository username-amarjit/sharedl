package com.example.sharedl

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.sharedl.notify.Notifications
import com.example.sharedl.ui.MainScreen
import com.example.sharedl.ui.theme.ShareDLTheme

class MainActivity : ComponentActivity() {

    // Downloads work without this; you just won't see progress outside the app.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Notifications.canPost(this)
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ShareDLTheme {
                MainScreen()
            }
        }
    }
}
