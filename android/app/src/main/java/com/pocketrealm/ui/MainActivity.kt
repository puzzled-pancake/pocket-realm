package com.pocketrealm.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pocketrealm.bots.BotCustomPresets
import com.pocketrealm.log.AppLog
import com.pocketrealm.service.RealmService
import com.pocketrealm.ui.theme.PocketRealmTheme

class MainActivity : ComponentActivity() {

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("UI", "POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Saved bot presets must resolve in this process (UI selection).
        BotCustomPresets.install(java.io.File(filesDir, "bots"))
        // F5a: construct the addon repository once at app start so the
        // fresh-install seed (built-in Android Port) runs even if the
        // Add-ons tab is never opened; the projector reads registry.json at
        // client launch. Off the main thread — init does registry I/O.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { com.pocketrealm.addons.AddonRepository.get(this@MainActivity) }
        }
        // Foreground service + notification need POST_NOTIFICATIONS on 33+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            PocketRealmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PocketRealmApp()
                }
            }
        }
    }
}
