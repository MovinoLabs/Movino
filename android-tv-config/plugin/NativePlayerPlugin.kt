package app.movino.tv.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Capacitor plugin that bridges JS → Native ExoPlayer/Media3 for Android TV.
 *
 * Usage from JS:
 *   import { NativePlayer } from './native-player-bridge';
 *   NativePlayer.open({ payload: JSON.stringify({...}) });
 *
 * The plugin launches NativePlayerActivity in full-screen with ExoPlayer.
 * It also relays progress events from the native player back to JS.
 */
@CapacitorPlugin(name = "NativePlayer")
class NativePlayerPlugin : Plugin() {

    private var progressReceiver: BroadcastReceiver? = null
    private var closeReceiver: BroadcastReceiver? = null
    private var isPlayerOpen: Boolean = false
    private var lastOpenAtMs: Long = 0L

    override fun load() {
        // Listen for progress broadcasts from NativePlayerActivity
        progressReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val data = JSObject().apply {
                    put("mediaId", intent.getStringExtra("mediaId") ?: "")
                    put("type", intent.getStringExtra("type") ?: "movie")
                    put("currentTime", intent.getDoubleExtra("currentTime", 0.0))
                    put("duration", intent.getDoubleExtra("duration", 0.0))
                    put("completed", intent.getBooleanExtra("completed", false))
                }
                notifyListeners("playerProgress", data)
            }
        }
        context.registerReceiver(
            progressReceiver,
            IntentFilter(NativePlayerActivity.ACTION_PROGRESS),
            Context.RECEIVER_NOT_EXPORTED
        )

        closeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                isPlayerOpen = false
                val data = JSObject().apply {
                    put("mediaId", intent?.getStringExtra("mediaId") ?: "")
                    put("type", intent?.getStringExtra("type") ?: "movie")
                }
                notifyListeners("playerClosed", data)
            }
        }
        context.registerReceiver(
            closeReceiver,
            IntentFilter(NativePlayerActivity.ACTION_CLOSED),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun handleOnDestroy() {
        try {
            progressReceiver?.let { context.unregisterReceiver(it) }
            closeReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        progressReceiver = null
        closeReceiver = null
    }

    @PluginMethod
    fun open(call: PluginCall) {
        val payload = call.getString("payload")
        if (payload.isNullOrEmpty()) {
            call.reject("Missing payload")
            return
        }

        val now = System.currentTimeMillis()
        val openedVeryRecently = now - lastOpenAtMs < 1500
        if (isPlayerOpen && openedVeryRecently) {
            call.resolve()
            return
        }

        if (isPlayerOpen) {
            call.resolve()
            return
        }

        val intent = Intent(activity, NativePlayerActivity::class.java).apply {
            putExtra(NativePlayerActivity.EXTRA_PAYLOAD, payload)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        isPlayerOpen = true
        lastOpenAtMs = now
        activity.startActivity(intent)
        call.resolve()
    }

    @PluginMethod
    fun close(call: PluginCall) {
        val intent = Intent(NativePlayerActivity.ACTION_CLOSE)
        context.sendBroadcast(intent)
        call.resolve()
    }
}
