package com.rumi.voiceassistant

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.Normalizer
import kotlin.math.sqrt

class SherpaManager(private val context: Context, private val modelDir: File) {

    lateinit var recognizer: OfflineRecognizer
    lateinit var vad: Vad
    lateinit var speakerExtractor: SpeakerEmbeddingExtractor
    lateinit var speakerManager: SpeakerEmbeddingManager
    lateinit var tts: OfflineTts

    var ownerRegistered = false

    private val voiceprintFile = File(context.filesDir, "owner_8_tone_matrix.dat")

    fun initModels() {
        Log.d("SherpaManager", "Loading Whisper Tiny...")
        val asrConfig = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDir, "sherpa-onnx-whisper-tiny.en/tiny.en-encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "sherpa-onnx-whisper-tiny.en/tiny.en-decoder.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "sherpa-onnx-whisper-tiny.en/tiny.en-tokens.txt").absolutePath,
                numThreads = 4,
                debug = false
            )
        )
        recognizer = OfflineRecognizer(null, asrConfig)

        Log.d("SherpaManager", "Loading Silero VAD...")
        val vadConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = File(modelDir, "silero_vad.onnx").absolutePath,
                threshold = 0.70f,
                minSilenceDuration = 0.6f,
                minSpeechDuration = 0.25f,
                windowSize = 512
            ),
            sampleRate = 16000,
            numThreads = 1
        )
        vad = Vad(null, vadConfig)

        Log.d("SherpaManager", "Loading WeSpeaker...")
        val speakerConfig = SpeakerEmbeddingExtractorConfig(
            model = File(modelDir, "wespeaker_en_voxceleb_resnet34.onnx").absolutePath,
            numThreads = 1
        )
        speakerExtractor = SpeakerEmbeddingExtractor(null, speakerConfig)
        speakerManager = SpeakerEmbeddingManager(speakerExtractor.dim())
        
        loadBiometrics()

        Log.d("SherpaManager", "Loading Piper TTS...")
        val ttsConfig = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(modelDir, "vits-piper-en_US-lessac-high/en_US-lessac-high.onnx").absolutePath,
                    tokens = File(modelDir, "vits-piper-en_US-lessac-high/tokens.txt").absolutePath,
                    dataDir = File(modelDir, "vits-piper-en_US-lessac-high/espeak-ng-data").absolutePath
                ),
                numThreads = 4,
                debug = false
            )
        )
        tts = OfflineTts(null, ttsConfig)
    }

    private fun loadBiometrics() {
        if (voiceprintFile.exists()) {
            try {
                DataInputStream(FileInputStream(voiceprintFile)).use { dis ->
                    for (i in 0 until 8) {
                        val emb = FloatArray(speakerExtractor.dim())
                        for (j in 0 until speakerExtractor.dim()) emb[j] = dis.readFloat()
                        speakerManager.add("owner_tone_$i", emb)
                    }
                }
                ownerRegistered = true
                Log.d("SherpaManager", "8-Tone Matrix Loaded!")
            } catch (e: Exception) {
                Log.e("SherpaManager", "Corrupt voiceprint file.", e)
            }
        }
    }

    fun saveBiometrics(matrix: List<FloatArray>) {
        DataOutputStream(FileOutputStream(voiceprintFile)).use { dos ->
            for (tone in matrix) {
                var norm = 0f
                for (v in tone) norm += v * v
                norm = sqrt(norm)
                for (v in tone) dos.writeFloat(v / norm)
            }
        }
        matrix.forEachIndexed { i, tone -> speakerManager.add("owner_tone_$i", tone) }
        ownerRegistered = true
    }

    fun transcribe(audio: FloatArray): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(audio, 16000)
        recognizer.decode(stream)
        return recognizer.getResult(stream).text.trim()
    }

    /**
     * Wake only on a complete two-word greeting plus Yumi's name. This
     * intentionally rejects "hey", "yummy", and partial/longer phrases.
     */
    fun isApprovedWakePhrase(text: String): Boolean {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}"), "")
            .lowercase()
            .replace(Regex("[^a-z]+"), " ")
            .trim()

        return normalized.matches(
            Regex(
                "^(hey|hello|hi) " +
                    "(yumi|yummy|yumi|yu mi|you me|yoomi)$",
            ),
        )
    }
}
