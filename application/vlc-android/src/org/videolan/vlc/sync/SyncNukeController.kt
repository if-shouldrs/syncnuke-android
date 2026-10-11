package org.videolan.vlc.sync

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.syncnuke.client.SyncManager
import io.github.syncnuke.player.MediaPlayer
import io.github.syncnuke.player.data.PlaybackState
import io.github.syncnuke.player.data.PlayerState
import org.videolan.vlc.PlaybackService
import java.util.concurrent.Executors

internal fun defaultSyncUsername(context: Context): String =
    Settings.Global.getString(context.contentResolver, "device_name")
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

/** Keeps SyncNuke independent of the video activity and of VLC's player ownership. */
class SyncNukeController(
    private val service: PlaybackService,
    private val settings: SharedPreferences
) : SharedPreferences.OnSharedPreferenceChangeListener, AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var configuration: List<String>? = null
    private var player: VlcPlayerAdapter? = null
    // Accessed only by worker; closing a network client must not block the UI thread.
    private var manager: SyncManager? = null
    private var closed = false
    private val refresh = Runnable { update() }

    init {
        settings.registerOnSharedPreferenceChangeListener(this)
    }

    fun update() {
        if (closed) return
        if (!settings.getBoolean("syncEnabled", false) || !service.hasMedia() || service.videoTracksCount == 0) {
            stop()
            return
        }
        val next = listOf(
            settings.getString("syncProtocol", "datasaver") ?: "datasaver",
            settings.getString("syncServer", "master.syncnuke.com") ?: "master.syncnuke.com",
            settings.getString("syncPort", "65344") ?: "65344",
            settings.getString("syncUsername", null) ?: defaultSyncUsername(service),
            settings.getString("syncRoom", "Default") ?: "Default",
            settings.getString("syncPassword", "") ?: ""
        )
        if (configuration == next) return
        stop()
        val port = next[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: 65344
        val adapter = VlcPlayerAdapter(service)
        player = adapter
        configuration = next
        worker.execute {
            try {
                if (!adapter.active) return@execute
                manager = SyncManager.getInstance(adapter)
                manager?.start(next[0], next[1], port, next[3], next[4], next[5])
            } catch (error: Exception) {
                Log.e("SyncNuke", "Unable to start synchronization", error)
            }
        }
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        if (key?.startsWith("sync") == true) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 300)
        }
    }

    fun stop() {
        if (closed || player == null) return
        player?.close()
        player = null
        configuration = null
        worker.execute {
            manager?.close()
            manager = null
        }
    }

    override fun close() {
        if (closed) return
        settings.unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacks(refresh)
        stop()
        closed = true
        worker.shutdown()
    }
}

/** All VLC access stays on the main thread; SyncNuke polls immutable snapshots. */
private class VlcPlayerAdapter(private val service: PlaybackService) : MediaPlayer {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var active = true
        private set
    @Volatile private var status = PlayerState()
    private val sample = object : Runnable {
        override fun run() {
            if (!active) return
            capture()
            handler.postDelayed(this, 100)
        }
    }

    init {
        sample.run()
    }

    private fun capture() {
        status = PlayerState().apply {
            playbackState = if (service.isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
            // The service's UI progress cache advances only every ~950 ms.
            // Pair this sample's timestamp with the live player clock instead.
            position = service.playlistManager.player.mediaplayer.time.coerceAtLeast(0) / 1000.0
            playbackSpeed = service.rate.toDouble().takeIf { it.isFinite() && it > 0 } ?: 1.0
            lastUpdateTime = System.currentTimeMillis()
        }
    }

    private fun command(action: () -> Unit) {
        handler.post {
            if (active && service.hasMedia()) {
                action()
                capture()
            }
        }
    }

    override fun play() = command { service.play() }
    override fun pause() = command { service.pause() }
    override fun seek(position: Double) = command {
        if (position.isFinite() && position >= 0 && service.isSeekable) {
            // Bypass VLC's optional fast-seek preference for synchronization corrections.
            service.playlistManager.player.setTime((position * 1000).toLong(), false)
        }
    }
    override fun setPlaybackSpeed(playbackSpeed: Double) = command {
        if (playbackSpeed.isFinite() && playbackSpeed > 0) service.setRate(playbackSpeed.toFloat(), false)
    }
    override fun getStatus(): PlayerState = PlayerState(status)
    // Media selection remains local, as in the JustPlayer integration.
    override fun load(file: String) = Unit
    override fun close() {
        active = false
        handler.removeCallbacks(sample)
        // VLC, rather than SyncNuke, owns and releases the playback service/player.
    }
}
