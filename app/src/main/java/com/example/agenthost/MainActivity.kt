package com.example.agenthost

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.agenthost.overlay.OverlayActions
import com.example.agenthost.ui.theme.AgentHostTheme

class MainActivity : ComponentActivity() {
    private var overlayPermissionGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshOverlayPermissionState()
        enableEdgeToEdge()
        setContent {
            AgentHostTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LauncherScreen(
                        overlayPermissionGranted = overlayPermissionGranted,
                        onGrantOverlayPermission = ::openOverlayPermissionSettings,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayPermissionState()
    }

    private fun refreshOverlayPermissionState() {
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        if (overlayPermissionGranted) {
            OverlayActions.startPresenceService(
                context = this,
                startReason = OverlayActions.START_REASON_MANUAL_ENTRY
            )
        }
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}

@Composable
fun LauncherScreen(
    overlayPermissionGranted: Boolean,
    onGrantOverlayPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("AgentHost")
        Text(
            if (overlayPermissionGranted) {
                "Overlay permission granted. The floating console should now be available."
            } else {
                "Overlay permission is required before the floating console can appear on the main display."
            }
        )
        if (!overlayPermissionGranted) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Current state: overlay permission is not enabled.")
                    Text("Grant it, then return to this screen. AgentHost will re-attach the overlay automatically.")
                    Button(onClick = onGrantOverlayPermission) {
                        Text("Grant overlay permission")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AgentHostTheme {
        LauncherScreen(
            overlayPermissionGranted = false,
            onGrantOverlayPermission = {}
        )
    }
}
