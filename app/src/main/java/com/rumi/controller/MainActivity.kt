package com.rumi.controller

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.provider.MediaStore
import android.app.SearchManager
import android.view.View
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Accessibility Settings
        findViewById<View>(R.id.open_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 2. Spotify Test (Matches your XML ID: btnTestSpotify)
        findViewById<View>(R.id.btnTestSpotify).setOnClickListener {
            runSpotifyTest()
        }

        // 3. Media Control Tests (Matches your XML IDs)
        findViewById<View>(R.id.btnTestPlayPause).setOnClickListener { sendMediaCommand("toggle") }
        findViewById<View>(R.id.btnTestNext).setOnClickListener { sendMediaCommand("next") }

        // 4. Volume Control Tests (Matches your XML IDs)
        findViewById<View>(R.id.btnTestVolUp).setOnClickListener { sendVolumeCommand("up") }
        findViewById<View>(R.id.btnTestVolDown).setOnClickListener { sendVolumeCommand("down") }
    }

    override fun onResume() {
        super.onResume()
        val status = if (isServiceEnabled(this)) {
            "Accessibility service: enabled"
        } else {
            "Accessibility service: disabled"
        }
        findViewById<TextView>(R.id.service_status).text = status
    }

    private fun isServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        val component = ComponentName(context, SpotifyAccessibilityService::class.java)

        return manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { info ->
            ComponentName.unflattenFromString(info.id) == component
        }
    }

    private fun runSpotifyTest() {
        val query = "Love Me Not"
        val spotifyIntent = Intent(
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
        ).apply {
            setPackage("com.spotify.music")
            putExtra(SearchManager.QUERY, query)
            putExtra(
                MediaStore.EXTRA_MEDIA_FOCUS,
                "vnd.android.cursor.item/audio"
            )
        }

        try {
            startActivity(spotifyIntent)
            sendBroadcast(
                Intent(PlayProtocol.ACTION_PLAY_SPOTIFY)
                    .setPackage(packageName)
                    .putExtra(PlayProtocol.EXTRA_QUERY, query)
            )
        } catch (error: Exception) {
            findViewById<TextView>(R.id.service_status).text =
                "Could not open Spotify: ${error.javaClass.simpleName}"
        }
    }

    private fun sendMediaCommand(command: String) {
        val intent = Intent("com.rumi.voiceassistant.MEDIA_CONTROL").apply {
            putExtra("command", command)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun sendVolumeCommand(command: String) {
        val intent = Intent("com.rumi.voiceassistant.VOLUME_CONTROL").apply {
            putExtra("command", command)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}