package com.oforomar.watchbabymonitor.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WatchScreen()
            }
        }
    }
}

@Composable
private fun WatchScreen() {
    val context = LocalContext.current
    val state by MonitorState.state.collectAsStateWithLifecycle()
    var sensitivity by remember { mutableStateOf(Settings.sensitivity(context)) }

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            MonitorService.start(context)
        } else {
            MonitorState.update { it.copy(error = "Microphone permission is required") }
        }
    }

    fun toggle() {
        if (state.running) {
            MonitorService.stop(context)
        } else {
            val micGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (micGranted) MonitorService.start(context) else launcher.launch(permissions)
        }
    }

    val listState = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item {
                Text(
                    text = when {
                        state.crying -> "Crying detected"
                        state.running -> "Listening"
                        else -> "Not monitoring"
                    },
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Button(
                    onClick = ::toggle,
                    modifier = Modifier.size(ButtonDefaults.LargeButtonSize),
                    colors = if (state.running) {
                        ButtonDefaults.secondaryButtonColors()
                    } else {
                        ButtonDefaults.primaryButtonColors()
                    },
                ) {
                    Text(if (state.running) "Stop" else "Start")
                }
            }
            if (state.running) {
                item {
                    Text(
                        text = "Level %.0f dB · cry %d%%".format(state.levelDb, (state.score * 100).toInt()),
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                Chip(
                    onClick = {
                        sensitivity = sensitivity.next()
                        Settings.setSensitivity(context, sensitivity)
                    },
                    label = { Text("Sensitivity") },
                    secondaryLabel = { Text(sensitivity.label) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = when (state.phoneLinked) {
                        true -> "Phone linked"
                        false -> "Phone NOT reachable"
                        null -> "Phone: waiting"
                    },
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                )
            }
            if (state.lastCryMs > 0) {
                item {
                    Text(
                        text = "Last cry ${time(state.lastCryMs)}" +
                            if (state.lastAckMs >= state.lastCryMs) " · seen" else "",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            state.error?.let { error ->
                item {
                    Text(
                        text = error,
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
