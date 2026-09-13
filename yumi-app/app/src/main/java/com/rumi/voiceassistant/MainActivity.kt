package com.rumi.voiceassistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var messageInput: EditText
    private lateinit var conversationContainer: LinearLayout
    private lateinit var conversationScroll: ScrollView
    private lateinit var sendButton: Button

    private val modelUrl =
        "https://github.com/clashersclash/yumi-android/releases/download/v1.0/yumi-models.zip"

    private val assistantUpdates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            statusText.text = intent.getStringExtra(AssistantService.EXTRA_STATUS)
                ?: "Yumi is active"
            intent.getStringExtra(AssistantService.EXTRA_CONVERSATION)?.let(::addActivityLine)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        conversationContainer = findViewById(R.id.conversationContainer)
        conversationScroll = findViewById(R.id.conversationScroll)

        messageInput.isEnabled = false
        sendButton.isEnabled = false
        sendButton.setOnClickListener { sendTypedMessage() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedMessage()
                true
            } else false
        }

        addActivityLine("Yumi: Open this screen anytime to see what I am doing.")
        checkPermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            assistantUpdates,
            IntentFilter(AssistantService.ACTION_ASSISTANT_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(assistantUpdates)
        super.onStop()
    }

    private fun checkPermissionsAndStart() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            checkModelsAndLaunch()
        } else {
            ActivityCompat.requestPermissions(this, permissions, 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkModelsAndLaunch()
        } else {
            statusText.text = "Microphone permission is required"
            Toast.makeText(this, "Yumi needs microphone access for voice mode.", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkModelsAndLaunch() {
        val modelDir = File(filesDir, "models")
        if (modelDir.exists() && (modelDir.listFiles()?.isNotEmpty() == true)) {
            launchAssistant()
            return
        }

        statusText.text = "Downloading Yumi's voice models"
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        downloadAndExtractModels(modelDir)
    }

    private fun downloadAndExtractModels(targetDir: File) {
        thread {
            try {
                OkHttpClient().newCall(Request.Builder().url(modelUrl).build()).execute().use { response ->
                    val body = response.body ?: error("The model download had no data.")
                    check(response.isSuccessful) { "Failed to fetch models: ${response.code}" }
                    val contentLength = body.contentLength()
                    val zipFile = File(cacheDir, "temp_models.zip")

                    body.byteStream().use { input ->
                        FileOutputStream(zipFile).use { output ->
                            val buffer = ByteArray(8192)
                            var totalRead = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                totalRead += read
                                if (contentLength > 0) {
                                    val progress = ((totalRead * 100) / contentLength).toInt()
                                    runOnUiThread {
                                        progressBar.progress = progress
                                        progressText.text = "$progress%"
                                    }
                                }
                            }
                        }
                    }
                    runOnUiThread {
                        statusText.text = "Extracting voice models"
                        progressBar.isIndeterminate = true
                        progressText.text = "Please wait"
                    }
                    unzip(zipFile, targetDir)
                    zipFile.delete()
                }
                runOnUiThread(::launchAssistant)
            } catch (error: Exception) {
                runOnUiThread {
                    statusText.text = "Model download failed: ${error.message}"
                }
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val targetPath = targetDir.canonicalPath + File.separator
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                require(file.canonicalPath.startsWith(targetPath)) { "Invalid archive path" }
                if (entry.isDirectory) file.mkdirs() else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use(zis::copyTo)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun launchAssistant() {
        ContextCompat.startForegroundService(this, Intent(this, AssistantService::class.java))
        val configured = SettingsStore(this).isConfigured()
        statusText.text = if (configured) "Yumi is listening in the background" else "Add an AI provider in Settings to chat"
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        messageInput.isEnabled = configured
        sendButton.isEnabled = configured
        if (!getPreferences(MODE_PRIVATE).getBoolean("key_tutorial_prompted", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("key_tutorial_prompted", true).apply()
            AlertDialog.Builder(this)
                .setTitle("Set up Yumi's AI")
                .setMessage("Would you like Yumi to open the Gemini API-key guide now? You can also choose OpenAI or Ollama in Settings.")
                .setPositiveButton("Open guide") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/app/apikey")))
                }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun sendTypedMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        messageInput.text.clear()
        addActivityLine("You: $text")
        ContextCompat.startForegroundService(
            this,
            Intent(this, AssistantService::class.java)
                .setAction(AssistantService.ACTION_SEND_TEXT)
                .putExtra(AssistantService.EXTRA_TEXT, text),
        )
    }

    private fun addActivityLine(line: String) {
        val item = TextView(this).apply {
            text = line
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.yumi_primary_text))
            textSize = 15f
            setPadding(0, 0, 0, 12)
        }
        conversationContainer.addView(item)
        conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
