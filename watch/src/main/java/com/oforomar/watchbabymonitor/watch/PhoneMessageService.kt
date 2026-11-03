package com.oforomar.watchbabymonitor.watch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.oforomar.watchbabymonitor.shared.Messenger
import com.oforomar.watchbabymonitor.shared.Paths
import com.oforomar.watchbabymonitor.shared.Payload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Messages from the phone: alarm acknowledged, or "run a test of the whole chain". */
class PhoneMessageService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Paths.ACK -> MonitorState.update { it.copy(lastAckMs = System.currentTimeMillis()) }

            Paths.TEST -> runBlocking(Dispatchers.IO) {
                // Answer exactly as a real cry would, flagged as a test.
                withTimeoutOrNull(10_000) {
                    Messenger.send(
                        this@PhoneMessageService,
                        Paths.CRY_START,
                        Payload(score = 1f, test = true),
                    )
                }
            }
        }
    }
}
