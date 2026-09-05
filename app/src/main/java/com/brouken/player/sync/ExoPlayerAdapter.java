package com.brouken.player.sync;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;

import java.util.concurrent.FutureTask;

import io.github.syncnuke.player.VideoPlayer;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;

/**
 * Adapter that implements the VideoPlayer interface for ExoPlayer.
 * This class bridges ExoPlayer with the sync library.
 */
public class ExoPlayerAdapter implements VideoPlayer {
    private static final String TAG = "ExoPlayerAdapter";

    private final ExoPlayer player;
    private boolean scrubbing;
    private PlaybackState scrubPlaybackState;
    private double scrubPosition;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ExoPlayerAdapter(@NonNull ExoPlayer player) {
        this.player = player;
        Log.i(TAG, "ExoPlayerAdapter initialized");
    }

    public void beginScrubbing(long positionMs) {
        scrubPlaybackState = getPlaybackState();
        scrubPosition = positionMs / 1000.0;
        scrubbing = true;
    }

    public void updateScrubbingPosition(long positionMs) {
        if (scrubbing) {
            scrubPosition = positionMs / 1000.0;
        }
    }

    public void endScrubbing(long positionMs) {
        if (!scrubbing) {
            return;
        }

        updateScrubbingPosition(positionMs);
        boolean shouldPlay = scrubPlaybackState == PlaybackState.PLAYING;
        player.setPlayWhenReady(shouldPlay);
        scrubbing = false;
    }

    @Override
    public void play() {
        runOnMainThread(() -> {
            if (scrubbing) {
                scrubPlaybackState = PlaybackState.PLAYING;
            }
            player.play();
        });
    }

    @Override
    public void pause() {
        runOnMainThread(() -> {
            if (scrubbing) {
                scrubPlaybackState = PlaybackState.PAUSED;
            }
            player.pause();
        });
    }

    @Override
    public void seek(double position) {
        runOnMainThread(() -> {
            long positionMs = (long) (position * 1000);
            SeekParameters seekParameters = player.getSeekParameters();
            player.setSeekParameters(SeekParameters.EXACT);
            player.seekTo(positionMs);
            player.setSeekParameters(seekParameters);
        });
        Log.d(TAG, "Seek requested to position: " + position + " seconds");
    }

    @Override
    public void setPlaybackSpeed(double playbackSpeed) {
        runOnMainThread(() -> player.setPlaybackSpeed((float) playbackSpeed));
    }

    @Override
    public PlayerState getStatus() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return readStatus();
        }

        FutureTask<PlayerState> getStatus = new FutureTask<>(this::readStatus);
        mainHandler.post(getStatus);

        try {
            return getStatus.get();
        } catch (Exception e) {
            Log.e(TAG, "Error getting player status", e);
            return new PlayerState();
        }
    }

    private PlayerState readStatus() {
        PlayerState status = new PlayerState();
        status.setPlaybackState(scrubbing ? scrubPlaybackState : getPlaybackState());
        status.setPosition(scrubbing ? scrubPosition : player.getCurrentPosition() / 1000.0);
        status.setPlaybackSpeed(player.getPlaybackParameters().speed);
        status.setLastUpdateTime(System.currentTimeMillis());
        return status;
    }

    private PlaybackState getPlaybackState() {
        return player.getPlayWhenReady() ? PlaybackState.PLAYING : PlaybackState.PAUSED;
    }

    @Override
    public void load(String filePath) {
        Log.d(TAG, "Load request for: " + filePath + " (ignored, handled by PlayerActivity)");
    }

    @Override
    public void close() {
        if (!player.isReleased()) {
            player.release();
        }
    }

    private void runOnMainThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

}
