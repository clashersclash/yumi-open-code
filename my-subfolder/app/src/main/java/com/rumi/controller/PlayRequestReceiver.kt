package com.rumi.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent

class PlayRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        when (action) {
            PlayProtocol.ACTION_PLAY_SPOTIFY -> {
                val query = intent.getStringExtra(PlayProtocol.EXTRA_QUERY)?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    // Termux has already opened Spotify. Just tell Accessibility to click it.
                    SpotifyAccessibilityService.requestPlayback(query)
                }
            }
            "com.rumi.voiceassistant.MEDIA_CONTROL" -> {
                val command = intent.getStringExtra("command")
                val keyCode = when (command) {
                    "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "dial" -> {
                        SpotifyAccessibilityService.requestDialCurrent()
                        return
                    }
                    else -> return
                }
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
            "com.rumi.voiceassistant.VOLUME_CONTROL" -> {
                val command = intent.getStringExtra("command")
                val stream = AudioManager.STREAM_MUSIC
                
                when (command) {
                    "up" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "down" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "auto_lower" -> {
                        val maxVol = audioManager.getStreamMaxVolume(stream)
                        val currentVol = audioManager.getStreamVolume(stream)
                        if (currentVol > maxVol * 0.1) {
                            val targetVol = maxOf(1, (maxVol * 0.1).toInt())
                            audioManager.setStreamVolume(stream, targetVol, AudioManager.FLAG_SHOW_UI)
                        }
                    }
                }
            }
        }
    }
}
