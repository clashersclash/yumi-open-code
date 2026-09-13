package com.rumi.voiceassistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.ArrayDeque

/**
 * Streaming OpenWakeWord pipeline matching the old Python application:
 * PCM -> melspectrogram -> speech embedding -> Yumi classifier.
 */
class OpenWakeWordManager(context: Context, classifier: File) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val workDir = File(context.filesDir, "openwakeword").apply { mkdirs() }
    private val melSession: OrtSession
    private val embeddingSession: OrtSession
    private val wakeSession: OrtSession
    private val mels = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()
    private var melFramesSinceEmbedding = 0

    init {
        val mel = copyAsset(context, "openwakeword/melspectrogram.onnx")
        val embedding = copyAsset(context, "openwakeword/embedding_model.onnx")
        require(classifier.isFile) { "Missing downloaded Yumi wake model: ${classifier.absolutePath}" }
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        melSession = environment.createSession(mel.absolutePath, options)
        embeddingSession = environment.createSession(embedding.absolutePath, options)
        wakeSession = environment.createSession(classifier.absolutePath, options)
        reset()
    }

    fun reset() {
        mels.clear(); embeddings.clear(); melFramesSinceEmbedding = 0
    }

    /** Returns the current Yumi score after consuming one 16 kHz mono PCM block. */
    fun score(pcm16: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        val raw = FloatArray(count) { pcm16[it].toFloat() }
        val newMels = runMel(raw)
        for (frame in newMels) {
            mels.addLast(frame)
            if (mels.size > 76) mels.removeFirst()
            melFramesSinceEmbedding++
        }
        // The reference OpenWakeWord pipeline advances embeddings every 8 mel frames.
        if (mels.size == 76 && melFramesSinceEmbedding >= 8) {
            melFramesSinceEmbedding = 0
            embeddings.addLast(runEmbedding())
            while (embeddings.size > 16) embeddings.removeFirst()
        }
        if (embeddings.size < 16) return 0f
        return runClassifier()
    }

    private fun runMel(raw: FloatArray): List<FloatArray> {
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(raw), longArrayOf(1, raw.size.toLong()))
        melSession.run(mapOf("input" to tensor)).use { result ->
            tensor.close()
            val values = result[0] as OnnxTensor
            val flat = values.floatBuffer.let { buffer -> FloatArray(buffer.remaining()).also(buffer::get) }
            // ONNX output is [time, 1, ?, 32]. Flattened rows are 32 mel bins.
            return flat.asList().chunked(32).map { row ->
                FloatArray(32) { index -> row[index] / 10f + 2f }
            }
        }
    }

    private fun runEmbedding(): FloatArray {
        val input = FloatArray(76 * 32)
        mels.forEachIndexed { frameIndex, frame -> frame.copyInto(input, frameIndex * 32) }
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 76, 32, 1))
        embeddingSession.run(mapOf("input_1" to tensor)).use { result ->
            tensor.close()
            val buffer = (result[0] as OnnxTensor).floatBuffer
            return FloatArray(buffer.remaining()).also(buffer::get)
        }
    }

    private fun runClassifier(): Float {
        val input = FloatArray(16 * 96)
        embeddings.forEachIndexed { index, embedding -> embedding.copyInto(input, index * 96, 0, 96) }
        val name = wakeSession.inputNames.first()
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 16, 96))
        wakeSession.run(mapOf(name to tensor)).use { result ->
            tensor.close()
            val buffer = (result[0] as OnnxTensor).floatBuffer
            return if (buffer.hasRemaining()) buffer.get() else 0f
        }
    }

    private fun copyAsset(context: Context, path: String): File {
        val target = File(workDir, path.substringAfterLast('/'))
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(path).use { input -> FileOutputStream(target).use(input::copyTo) }
        }
        return target
    }

    override fun close() {
        melSession.close(); embeddingSession.close(); wakeSession.close()
    }
}
