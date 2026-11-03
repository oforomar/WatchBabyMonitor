package com.oforomar.watchbabymonitor.shared

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Sends a message to every connected node (on the watch that is the paired phone,
 * on the phone that is the watch). Returns how many nodes accepted it.
 */
object Messenger {
    private const val TAG = "Messenger"

    suspend fun send(context: Context, path: String, payload: Payload = Payload()): Int {
        val bytes = payload.toBytes()
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val client = Wearable.getMessageClient(context)
            var sent = 0
            for (node in nodes) {
                try {
                    client.sendMessage(node.id, path, bytes).await()
                    sent++
                } catch (e: Exception) {
                    Log.w(TAG, "send $path to ${node.displayName} failed", e)
                }
            }
            if (sent == 0) Log.w(TAG, "send $path: no connected node")
            sent
        } catch (e: Exception) {
            Log.w(TAG, "send $path failed", e)
            0
        }
    }
}
