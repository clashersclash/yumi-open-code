package com.rumi.voiceassistant

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.ArrayDeque
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

class AssistantService : Service() {

    private val TAG = "YumiService"
    private var isListening = false
    private var inVcMode = false
    private var openModeUntil = 0L

    private lateinit var audioManager: AudioManager
    private lateinit var sherpa: SherpaManager
    private lateinit var wakeWord: OpenWakeWordManager
    private lateinit var llm: LlmAndMemory
    @Volatile private var engineReady = false
    
    private val speechLock = Any()
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaVolumeBeforeSpeech: Int? = null
    private data class PendingCall(val name: String, val number: String)
    private var pendingCall: PendingCall? = null
    private var actionAnnouncement: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Starting Yumi...")
        startForegroundServiceNotification()
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        llm = LlmAndMemory(this)
        // Native model loading is expensive. Service.onCreate runs on the app's UI
        // thread, so it must never initialise models here or it can cause an ANR.
        thread(name = "yumi-model-init") {
            try {
                val downloadedModels = File(filesDir, "models")
                val nestedModels = File(downloadedModels, "yumi-models")
                val modelDir = if (nestedModels.isDirectory) nestedModels else downloadedModels
                sherpa = SherpaManager(this, modelDir)
                sherpa.initModels()
                wakeWord = OpenWakeWordManager(this, File(modelDir, "yumi.onnx"))
                engineReady = true
                publishStatus("Listening for Yumi")
                startMicrophoneLoop()
            } catch (error: Exception) {
                Log.e(TAG, "Unable to initialise voice engine", error)
                publishStatus("Voice engine could not start: ${error.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SEND_TEXT) {
            intent.getStringExtra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (engineReady) handleTypedMessage(it)
                else publishStatus("Yumi is still loading voice models")
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "yumi_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Yumi Background", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, buildNotification("Starting Yumi"))
    }

    private fun buildNotification(status: String): android.app.Notification =
        NotificationCompat.Builder(this, "yumi_service_channel")
            .setContentTitle("Yumi")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun publishStatus(status: String, conversation: String? = null) {
        Log.d(TAG, status)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
        sendBroadcast(Intent(ACTION_ASSISTANT_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            conversation?.let { putExtra(EXTRA_CONVERSATION, it) }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneLoop() {
        isListening = true
        thread(start = true, name = "yumi-microphone") {
            if (!sherpa.ownerRegistered) registerOwnerVoice()

            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (bufferSize <= 0) {
                publishStatus("Microphone is not available on this device")
                return@thread
            }
            // One logical mono stream. Android selects the physical microphone;
            // apps cannot reliably pin a particular phone mic.
            val audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                publishStatus("Yumi could not initialise the microphone")
                return@thread
            }

            audioRecord.startRecording()
            Log.d(TAG, "[Ready. Sleeping — say 'Yumi']")
            
            publishStatus("Listening for Yumi")
            var needsWakeWord = true
            val chunk = ShortArray(1280)
            val wakeAudio = ArrayDeque<FloatArray>()
            var wakeSamples = 0
            var wakeChunksSinceCheck = 0

            while (isListening) {
                if (inVcMode) needsWakeWord = false

                if (needsWakeWord) {
                    val read = audioRecord.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        if (wakeWord.score(chunk, read) >= 0.99f) {
                            Log.d(TAG, "[OpenWakeWord score >= 0.99]")
                            requestSpeechAudioFocus()
                            publishStatus("Listening — I heard you")
                            playListeningCue()
                            wakeWord.reset()
                            needsWakeWord = false
                            continue
                        }
                        val pcm = FloatArray(read) { chunk[it] / 32768f }
                        wakeAudio.addLast(pcm)
                        wakeSamples += pcm.size
                        wakeChunksSinceCheck++
                        while (wakeSamples > 32_000 && wakeAudio.isNotEmpty()) {
                            wakeSamples -= wakeAudio.removeFirst().size
                        }
                        // Earlier checks make the “listening to you” state feel immediate;
                        // the rolling window still grows long enough for the whole phrase.
                        // Do not decode Whisper every few milliseconds: it starves AudioRecord.
                        if (false && wakeSamples >= 16_000 && wakeChunksSinceCheck >= 12) {
                            wakeChunksSinceCheck = 0
                            val window = FloatArray(wakeSamples)
                            var offset = 0
                            for (part in wakeAudio) {
                                part.copyInto(window, destinationOffset = offset)
                                offset += part.size
                            }
                            val heard = sherpa.transcribe(window)
                            Log.d(TAG, "[Wake phrase heard: $heard]")
                            if (sherpa.isApprovedWakePhrase(heard)) {
                            Log.d(TAG, "[Wake word detected]")
                            requestSpeechAudioFocus()
                            publishStatus("Listening — I heard you")
                            playListeningCue()
                                wakeAudio.clear()
                                wakeSamples = 0
                                needsWakeWord = false
                            }
                        }
                    }
                    continue
                }

                val audioData = smartListen(audioRecord)
                if (audioData == null) {
                    needsWakeWord = !inVcMode
                    if (needsWakeWord) {
                        releaseSpeechAudioFocus()
                        publishStatus("Listening for Yumi")
                    }
                    continue
                }

                if (System.currentTimeMillis() > openModeUntil) {
                    val stream = sherpa.speakerExtractor.createStream()
                    stream.acceptWaveform(audioData, 16000)
                    val emb = sherpa.speakerExtractor.compute(stream)
                    if (!isRegisteredOwner(emb)) {
                        Log.d(TAG, "[Voice ignored: owner verification did not match]")
                        publishStatus("Voice did not match the registered owner")
                        needsWakeWord = !inVcMode
                        if (needsWakeWord) {
                            releaseSpeechAudioFocus()
                            publishStatus("Listening for Yumi")
                        }
                        continue
                    }
                }

                publishStatus("Understanding your voice")
                val stream = sherpa.recognizer.createStream()
                stream.acceptWaveform(audioData, 16000)
                sherpa.recognizer.decode(stream)
                val userText = sherpa.recognizer.getResult(stream).text.trim().lowercase()

                if (userText.isEmpty() || userText in listOf("thank you", "subscribe", "you")) {
                    needsWakeWord = !inVcMode
                    if (needsWakeWord) {
                        releaseSpeechAudioFocus()
                        publishStatus("Listening for Yumi")
                    }
                    continue
                }

                Log.d(TAG, "You: $userText")
                publishStatus("Thinking", "You: $userText")
                val response = handlePendingCall(userText) ?: llm.queryLlm(userText)
                
                processHardwareTags(response)
                
                val spokenResponse = actionAnnouncement ?: response.replace(Regex("\\[.*?\\]"), "").trim().ifEmpty { "Done." }
                publishStatus("Speaking", "Yumi: $spokenResponse")
                val interrupted = speak(spokenResponse, audioRecord)

                // An interruption already contained the wake phrase, so proceed directly
                // to the user's next spoken command instead of making them say it twice.
                // A pending call is a short, explicit confirmation state: accept
                // “yes” or “cancel” without requiring the wake phrase again.
                needsWakeWord = pendingCall == null && !interrupted && !inVcMode
                if (needsWakeWord) publishStatus("Listening for Yumi") else if (interrupted) publishStatus("Listening to you")
            }
            audioRecord.stop()
            audioRecord.release()
        }
    }

    private fun handleTypedMessage(text: String) {
        thread(start = true) {
            publishStatus("Thinking", "You: $text")
            val response = handlePendingCall(text) ?: llm.queryLlm(text)
            processHardwareTags(response)
            val spokenResponse = actionAnnouncement ?: response.replace(Regex("\\[.*?\\]"), "").trim()
                .ifEmpty { "Done." }
            publishStatus("Speaking", "Yumi: $spokenResponse")
            speak(spokenResponse, null)
            publishStatus("Listening for Yumi")
        }
    }

    private fun smartListen(mic: AudioRecord): FloatArray? {
        Log.d(TAG, "[Listening...]")
        val vad = sherpa.vad
        val buffer = ShortArray(512)
        var speechStarted = false
        val startTime = System.currentTimeMillis()

        while (true) {
            val read = mic.read(buffer, 0, buffer.size)
            if (read > 0) {
                val pcm = FloatArray(read) { buffer[it] / 32768f }
                vad.acceptWaveform(pcm)
                
                if (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    return segment.samples
                }
                if (vad.isSpeechDetected()) speechStarted = true

                val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                if (!speechStarted && elapsed >= 10f) return null
                if (elapsed >= 30f) {
                    vad.flush()
                    if (!vad.empty()) {
                        val segment = vad.front()
                        vad.pop()
                        return segment.samples
                    }
                    return null
                }
            }
        }
    }

    private fun processHardwareTags(response: String) {
        actionAnnouncement = null
        if (response.contains("[MODE: VC]", true)) {
            inVcMode = true
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            run { audioManager.isSpeakerphoneOn = true }
            publishStatus("Voice mode on — communication audio enabled")
        } else if (response.contains("[MODE: END_VC]", true)) {
            inVcMode = false
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            run { audioManager.isSpeakerphoneOn = false }
            publishStatus("Voice mode off")
        }

        if (response.contains("[MODE: OPEN]", true)) openModeUntil = System.currentTimeMillis() + (15 * 60 * 1000)
        else if (response.contains("[MODE: END_OPEN]", true)) openModeUntil = 0L

        Regex("\\[SAVE_MEMORY:\\s*(.*?)\\]", RegexOption.IGNORE_CASE).find(response)?.let {
            llm.saveMemory(it.groupValues[1].trim())
        }
        
        Regex("\\[SEARCH:\\s*(.*?)\\]", RegexOption.IGNORE_CASE).find(response)?.let {
            val query = it.groupValues[1].trim()
            val result = llm.quickWebSearch(query)
            llm.queryLlm("Web search result for '$query': $result. Based on this, answer my previous question briefly.")
        }

        if (response.contains("[up]", true)) changeVolume(direction = "up")
        if (response.contains("[down]", true)) changeVolume(direction = "down")
        if (response.contains("[toggle]", true)) sendControllerCommand("toggle")
        if (response.contains("[next]", true)) sendControllerCommand("next")
        if (response.contains("[prev]", true)) sendControllerCommand("prev")
        Regex("\\[VOL:\\s*SET_(\\d+)\\]", RegexOption.IGNORE_CASE).find(response)?.let {
            changeVolume(percent = it.groupValues[1].toInt())
        }

        Regex("\\[PLAY_MUSIC:\\s*(.*?)\\]", RegexOption.IGNORE_CASE).find(response)?.let {
            val query = it.groupValues[1].trim()
            val i = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH")
            i.putExtra("query", query)
            i.putExtra("android.intent.extra.focus", "vnd.android.cursor.item/audio")
            i.setPackage("com.spotify.music")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(i) } catch (e: Exception) {}
            // Rumi Controller's accessibility service needs Spotify's results on screen first.
            Handler(Looper.getMainLooper()).postDelayed({
                sendBroadcast(Intent("com.rumi.voiceassistant.PLAY_SPOTIFY").apply {
                    component = controllerReceiver()
                    putExtra("query", query)
                })
            }, 900)
        }
        Regex("\\[CALL:\\s*(.*?)\\]", RegexOption.IGNORE_CASE).find(response)?.let {
            beginCallConfirmation(it.groupValues[1].trim())
        }
    }

    private fun beginCallConfirmation(contactName: String) {
        if (contactName.isBlank()) return
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            actionAnnouncement = "I need Contacts permission before I can look someone up."
            return
        }
        // This is the only place contacts are queried: after an explicit call request.
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$contactName%"), "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                pendingCall = PendingCall(cursor.getString(0), cursor.getString(1))
                actionAnnouncement = "I found ${pendingCall!!.name}. Should I open the phone dialer now?"
            } else actionAnnouncement = "I could not find a contact named $contactName."
        } ?: run { actionAnnouncement = "I could not access your contacts." }
    }

    private fun handlePendingCall(text: String): String? {
        val call = pendingCall ?: return null
        val normalized = text.lowercase().replace(Regex("[^a-z ]"), " ").trim()
        return when {
            Regex("^(yes|yeah|yep|confirm|do it|call)$").matches(normalized) -> {
                pendingCall = null
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(call.number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    // The spoken confirmation authorises a single accessibility attempt
                    // to press the dialer's Call control. The system may still show its
                    // own SIM-selection UI when no default SIM is configured.
                    Handler(Looper.getMainLooper()).postDelayed({ sendControllerCommand("dial") }, 750)
                    "Opening the dialer for ${call.name}. Choose SIM 1 or SIM 2 there if your phone asks."
                } catch (_: Exception) { "I could not open the phone dialer." }
            }
            Regex("^(no|nope|cancel|stop)$").matches(normalized) -> { pendingCall = null; "Okay, I cancelled the call." }
            else -> "Please say yes to open the dialer for ${call.name}, or say cancel."
        }
    }

    private fun playListeningCue() {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45).startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
    }

    private fun sendControllerCommand(command: String) {
        sendBroadcast(Intent("com.rumi.voiceassistant.MEDIA_CONTROL").apply {
            component = controllerReceiver()
            putExtra("command", command)
        })
    }

    private fun controllerReceiver() = ComponentName(
        "com.rumi.controller",
        "com.rumi.controller.PlayRequestReceiver",
    )

    private fun isRegisteredOwner(embedding: FloatArray): Boolean {
        if (!sherpa.ownerRegistered) return false
        // Test every enrolled sample, rather than depending on a string result
        // from search(). This makes the biometric decision explicit and logged.
        val matched = (0 until 8).any { index ->
            sherpa.speakerManager.verify("owner_tone_$index", embedding, 0.50f)
        }
        Log.d(TAG, "[Owner biometric: ${if (matched) "matched" else "rejected"}]")
        return matched
    }

    private fun changeVolume(direction: String? = null, percent: Int? = null) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        var newVol = current
        if (percent != null) newVol = (max * (percent / 100f)).toInt()
        else if (direction == "up") newVol = minOf(max, current + maxOf(1, (max * 0.1).toInt()))
        else if (direction == "down") newVol = maxOf(0, current - maxOf(1, (max * 0.1).toInt()))
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        publishStatus("Media volume: ${(newVol * 100 / max)}%")
    }

    private fun requestSpeechAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(if (inVcMode) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
        // Leave playback running; only lower media while Yumi is speaking.
        if (mediaVolumeBeforeSpeech == null) {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            mediaVolumeBeforeSpeech = current
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(4, current), 0)
        }
    }

    private fun releaseSpeechAudioFocus() {
        mediaVolumeBeforeSpeech?.let { original ->
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
            mediaVolumeBeforeSpeech = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun speak(text: String, mic: AudioRecord?): Boolean = synchronized(speechLock) {
        if (text.isEmpty()) return false
        Log.d(TAG, "Yumi: $text")

        requestSpeechAudioFocus()
        val sampleRate = sherpa.tts.sampleRate()

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (inVcMode) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ),
            )
            .build()
        audioTrack.play()

        // Start playback while Piper is still synthesising, instead of waiting for a full utterance.
        var interrupted = false
        val interruptAudio = ArrayDeque<FloatArray>()
        var interruptSamples = 0
        var interruptChecks = 0
        val interruptBuffer = ShortArray(1600)
        sherpa.tts.generateWithCallback(text, sid = 0, speed = 1.08f) { samples ->
            val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
            audioTrack.write(pcm, 0, pcm.size)
            // While she speaks, keep listening for the exact wake phrase. Returning 0
            // stops native synthesis immediately when the user interrupts her.
            if (mic != null && mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = mic.read(interruptBuffer, 0, interruptBuffer.size, AudioRecord.READ_NON_BLOCKING)
                if (read > 0 && wakeWord.score(interruptBuffer, read) >= 0.99f) {
                    interrupted = true
                    wakeWord.reset()
                    publishStatus("Listening — I heard you")
                    playListeningCue()
                    0
                } else if (read > 0) {
                    val heard = FloatArray(read) { interruptBuffer[it] / 32768f }
                    interruptAudio.addLast(heard); interruptSamples += heard.size; interruptChecks++
                    while (interruptSamples > 32_000 && interruptAudio.isNotEmpty()) interruptSamples -= interruptAudio.removeFirst().size
                    if (false && interruptSamples >= 16_000 && interruptChecks >= 10) {
                        interruptChecks = 0
                        val window = FloatArray(interruptSamples)
                        var offset = 0
                        interruptAudio.forEach { part -> part.copyInto(window, offset).also { offset += part.size } }
                        if (sherpa.isApprovedWakePhrase(sherpa.transcribe(window))) {
                            interrupted = true
                            publishStatus("Listening — I heard you")
                            playListeningCue()
                            0
                        } else 1
                    } else 1
                } else 1
            } else 1
        }
        audioTrack.stop()
        audioTrack.release()
        releaseSpeechAudioFocus()
        return interrupted
    }
    
    private fun registerOwnerVoice() {
        Log.d(TAG, "--- VOICE REGISTRATION (8-TONE PROCESS) ---")
        val tones = listOf("normal speaking voice", "slightly louder voice", "quiet whispering voice", "tired or groggy voice", "fast-paced sentence", "slow deliberate sentence", "higher pitch happy tone", "normal voice one last time")
        
        val matrix = mutableListOf<FloatArray>()
        val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        mic.startRecording()
        
        for ((index, tone) in tones.withIndex()) {
            var captured = false
            while (!captured) {
                publishStatus(
                    "Owner setup: sample ${index + 1} of ${tones.size}",
                    "Yumi: Please speak using a $tone.",
                )
                speak("Please speak a sentence using a $tone.", mic)
                val audio = smartListen(mic)
                if (audio != null && audio.size > 16000) {
                    val stream = sherpa.speakerExtractor.createStream()
                    stream.acceptWaveform(audio, 16000)
                    matrix.add(sherpa.speakerExtractor.compute(stream))
                    captured = true
                }
            }
        }
        mic.release()
        sherpa.saveBiometrics(matrix)
        publishStatus("Owner voice setup complete", "Yumi: I will now recognize you in any mood.")
        speak("Voice registration complete. I will now recognize you in any mood.", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        releaseSpeechAudioFocus()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ASSISTANT_UPDATE = "com.rumi.voiceassistant.ASSISTANT_UPDATE"
        const val ACTION_SEND_TEXT = "com.rumi.voiceassistant.SEND_TEXT"
        const val EXTRA_STATUS = "status"
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_TEXT = "text"
        private const val NOTIFICATION_ID = 1
    }
}
