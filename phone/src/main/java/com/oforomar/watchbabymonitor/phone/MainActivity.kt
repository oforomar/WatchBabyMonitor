package com.oforomar.watchbabymonitor.phone

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oforomar.watchbabymonitor.shared.Messenger
import com.oforomar.watchbabymonitor.shared.Paths
import com.oforomar.watchbabymonitor.shared.Timing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PhoneState.load(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { HomeScreen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PhoneState.load(this)
        // Creates the DND-capable channel once the user has granted DND access.
        AlarmController.ensureChannel(this)
    }
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by PhoneState.status.collectAsStateWithLifecycle()

    // Re-render every few seconds so "last heard" stays fresh, and re-check setup.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var setupTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            now = System.currentTimeMillis()
            PhoneState.load(context)
            setupTick++
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { setupTick++ }

    val heardAgo = if (status.lastHeartbeatMs > 0) (now - status.lastHeartbeatMs) / 1000 else -1
    val linkOk = status.monitoring && heardAgo in 0..(Timing.WATCHDOG_TIMEOUT_MS / 1000)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Baby Monitor", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = when {
                        status.alarmActive -> status.alarmTitle.ifBlank { "Alarm ringing" }
                        status.crying -> "Crying right now"
                        linkOk -> "Watch is listening"
                        status.monitoring -> "Watch not heard from"
                        else -> "Not monitoring"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (status.monitoring) {
                    Text("Last heard: ${if (heardAgo >= 0) "$heardAgo s ago" else "never"}")
                }
                if (status.batteryPct >= 0) {
                    Text("Watch battery: ${status.batteryPct}%" + if (status.charging) " (charging)" else "")
                }
                if (status.lastCryMs > 0) Text("Last cry: ${time(status.lastCryMs)}")
                if (status.lastFault.isNotBlank()) Text("Last problem: ${status.lastFault.replace('_', ' ')}")
                if (!status.monitoring) {
                    Text(
                        "Start monitoring on the watch next to the crib.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (status.alarmActive) {
            Button(
                onClick = { scope.launch { AlarmController.acknowledge(context) } },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("I'm on it") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                scope.launch {
                    val sent = Messenger.send(context, Paths.TEST)
                    if (sent == 0) {
                        Toast.makeText(context, "Watch not reachable", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("Test alert") }

            if (status.monitoring) {
                OutlinedButton(onClick = {
                    PhoneState.update(context) { it.copy(monitoring = false, crying = false) }
                    Watchdog.disarm(context)
                }) { Text("End session") }
            }
        }

        SetupChecklist(context, setupTick, onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        })
    }
}

@Composable
private fun SetupChecklist(context: Context, tick: Int, onRequestNotifications: () -> Unit) {
    // `tick` only forces recomposition so the checks are re-read.
    @Suppress("UNUSED_VARIABLE") val unused = tick

    val notificationsOk = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val nm = context.getSystemService(NotificationManager::class.java)
    val fullScreenOk = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
    val pm = context.getSystemService(PowerManager::class.java)
    val batteryOk = pm.isIgnoringBatteryOptimizations(context.packageName)
    val dndOk = nm.isNotificationPolicyAccessGranted

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))

            CheckRow("Notifications allowed", notificationsOk) {
                if (Build.VERSION.SDK_INT >= 33) {
                    onRequestNotifications()
                } else {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            }
            CheckRow("Full-screen alarm allowed", fullScreenOk) {
                if (Build.VERSION.SDK_INT >= 34) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            }
            CheckRow("Not battery-optimised", batteryOk) {
                @Suppress("BatteryLife")
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            CheckRow("Ring through Do Not Disturb (optional)", dndOk) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, fix: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text((if (ok) "✓  " else "✗  ") + label, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = fix) { Text("Fix") }
    }
}

private fun time(ms: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
