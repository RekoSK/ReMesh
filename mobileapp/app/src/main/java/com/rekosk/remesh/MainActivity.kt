package com.rekosk.remesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.rekosk.remesh.notify.MeshConnectionService
import com.rekosk.remesh.ui.ReMeshApp
import com.rekosk.remesh.ui.theme.ReMeshTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // A denial only costs the user their notifications. The service still runs
            // and the BLE link still works, so there is nothing to recover from.
            startConnectionService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ReMeshTheme {
                ReMeshApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Also here, not just in onCreate: on a fresh install the Bluetooth permission
        // is granted from the connect screen, and only then can the service start.
        startConnectionService()
    }

    /**
     * A `connectedDevice` foreground service needs a Bluetooth permission at runtime.
     * Asking before the user has granted one would have the system refuse the start.
     */
    private fun startConnectionService() {
        if (granted(Manifest.permission.BLUETOOTH_CONNECT)) MeshConnectionService.start(this)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        // Only a real exit tears the link down. A rotation or a process-death restore
        // must leave the node connected, which is the whole point of the service.
        if (isFinishing) MeshConnectionService.stop(this)
        super.onDestroy()
    }
}
