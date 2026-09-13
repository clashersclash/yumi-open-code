package com.rumi.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import android.util.Log

class RumiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("RumiController", "Received action: $action")

        // Android's native audio manager for volume and media controls
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        when (action) {
            // --- 1. SPOTIFY SKILL ---
            "com.rumi.voiceassistant.PLAY_SPOTIFY" -> {
                val query = intent.getStringExtra("query") ?: return
                
                // Launch Spotify natively from the app context
                val playIntent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").apply {
                    putExtra("query", query)
                    putExtra("android.intent.extra.focus", "vnd.android.cursor.item/audio")
                    setPackage("com.spotify.music")
                    // 0x10008000 = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) 
                }
                try {
                    context.startActivity(playIntent)
                } catch (e: Exception) {
                    Log.e("RumiController", "Failed to launch Spotify", e)
                }
            }
            
            // --- 2. HARDWARE MEDIA CONTROLS ---
            "com.rumi.voiceassistant.MEDIA_CONTROL" -> {
                val command = intent.getStringExtra("command")
                val keyCode = when (command) {
                    "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> return
                }
                
                // Simulates pressing the physical media buttons on Bluetooth headphones
                val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                audioManager.dispatchMediaKeyEvent(eventDown)
                audioManager.dispatchMediaKeyEvent(eventUp)
            }
            
            // --- 3. VOLUME CONTROLS ---
            "com.rumi.voiceassistant.VOLUME_CONTROL" -> {
                val command = intent.getStringExtra("command")
                val direction = when (command) {
                    "up" -> AudioManager.ADJUST_RAISE
                    "down" -> AudioManager.ADJUST_LOWER
                    else -> return
                }
                
                // Adjusts volume and shows the UI slider on the screen (FLAG_SHOW_UI)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            }
        }
    }
}