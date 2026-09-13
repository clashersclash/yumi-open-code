package com.rumi.voiceassistant

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class LlmAndMemory(private val context: Context) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val settings = SettingsStore(context)
    
    private val chatHistory = mutableListOf<JSONObject>()
    private val memoryFile = File(context.filesDir, "yumi_memory.json")

    private fun loadMemories(): List<String> {
        if (!memoryFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(memoryFile.readText())
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun saveMemory(info: String) {
        val memories = loadMemories().toMutableList()
        memories.add(info)
        memoryFile.writeText(JSONArray(memories).toString())
        Log.d("LlmManager", "Saved memory: $info")
    }

    private fun getSystemPrompt(): String {
        val memories = loadMemories()
        val memText = if (memories.isNotEmpty()) memories.joinToString("\n- ") else "None."
        val frequentContacts = SettingsStore(context).trustedContacts.joinToString().ifBlank { "None configured." }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val date = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault()).format(Date())

        return """You are Yumi, a natural and warm voice assistant on a phone.
Answer directly in one or two concise conversational sentences.
CRITICAL: Never output your internal thinking, reasoning steps, or thought process.
Do not use markdown, emojis, asterisks, bullet points, or numbered lists.
For calculations, quantities, dates, and times, always use numeric digits (for example, 1, 2, 3, 5 and 14:30), never spelled-out numbers.

CURRENT CONTEXT:
- Exact Time: $time
- Date: $date

PERMANENT MEMORIES ABOUT THE USER:
- $memText

FREQUENT CALL CONTACTS (user-entered names only; never claim you queried Contacts):
- $frequentContacts

You control the device hardware and state. If asked to perform an action, include EXACTLY the matching tag anywhere in your response:
- Save/note info permanently: [SAVE_MEMORY: exact information to save]
- Search the internet (Use ONLY for factual knowledge, NOT for live weather/time): [SEARCH: search query]
- Enable Open Mode (allow anyone to use you for 15 mins): [MODE: OPEN]
- Disable Open Mode: [MODE: END_OPEN]
- Start Voice Call mode (continuous conversation): [MODE: VC]
- End Voice Call mode: [MODE: END_VC]
- Play music/song/artist: [PLAY_MUSIC: exact song title by exact artist]. Preserve any artist the user names in this query. For an artist-only request, use the artist name exactly.
- Pause or Resume music: [toggle]
- Next track: [next]
- Previous track: [prev]
- Volume up: [up]
- Volume down: [down]
- Set volume to exact percentage: [VOL: SET_50] 
- Call someone: [CALL: contact name]
- Ask what song is playing: [GET_MEDIA]
For hardware/mode actions, just acknowledge briefly (e.g., 'Done.', 'Open mode enabled.', 'Playing.')."""
    }

    fun quickWebSearch(query: String): String {
        Log.d("LlmManager", "Searching Wiki for: $query")
        return try {
            val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${query.replace(" ", "%20")}&utf8=&format=json"
            val request = Request.Builder().url(url).header("User-Agent", "YumiVoiceAssistant/1.0").build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")
            val results = json.getJSONObject("query").getJSONArray("search")
            if (results.length() > 0) {
                results.getJSONObject(0).getString("snippet").replace(Regex("<[^>]+>"), "")
            } else "No results found."
        } catch (e: Exception) { "Error searching." }
    }

    fun queryLlm(text: String): String {
        if (!settings.isConfigured()) return "Please add an AI provider in Settings before sending a message."
        chatHistory.add(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        if (chatHistory.size > 30) chatHistory.removeAt(0)

        return try {
            val answer = when (settings.provider) {
                "gemini" -> requestGemini()
                "openai" -> requestOpenAi("https://api.openai.com/v1/chat/completions", settings.openAiKey, settings.openAiModel)
                "ollama" -> requestOllama()
                else -> error("Unknown provider")
            }

            chatHistory.add(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", answer))))
            cleanResponse(answer)
        } catch (e: Exception) {
            Log.e("LlmManager", "Provider request failed", e)
            chatHistory.removeAt(chatHistory.size - 1) // Revert on fail
            "I could not reach the selected AI provider. Please check Settings and your connection."
        }
    }

    private fun requestGemini(): String {
        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", getSystemPrompt()))))
            put("contents", JSONArray(chatHistory))
            put("generationConfig", JSONObject().put("maxOutputTokens", 180).put("temperature", 0.7))
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${settings.geminiModel}:generateContent?key=${settings.geminiKey}"
        val body = client.newCall(Request.Builder().url(url).post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            check(it.isSuccessful) { "Gemini HTTP ${it.code}" }; it.body?.string() ?: error("Empty Gemini response")
        }
        return JSONObject(body).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private fun openAiMessages(): JSONArray = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", getSystemPrompt()))
        chatHistory.forEach { item ->
            val role = if (item.getString("role") == "model") "assistant" else "user"
            put(JSONObject().put("role", role).put("content", item.getJSONArray("parts").getJSONObject(0).getString("text")))
        }
    }

    private fun requestOpenAi(url: String, key: String, model: String): String {
        val payload = JSONObject().put("model", model).put("messages", openAiMessages()).put("max_tokens", 180).put("temperature", 0.7)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val body = client.newCall(request).execute().use { check(it.isSuccessful) { "Provider HTTP ${it.code}" }; it.body?.string() ?: error("Empty provider response") }
        return JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun requestOllama(): String {
        val payload = JSONObject().put("model", settings.ollamaModel).put("messages", openAiMessages()).put("stream", false)
        val url = "${settings.ollamaUrl}/api/chat"
        val request = Request.Builder().url(url).post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val body = client.newCall(request).execute().use { check(it.isSuccessful) { "Ollama HTTP ${it.code}" }; it.body?.string() ?: error("Empty Ollama response") }
        return JSONObject(body).getJSONObject("message").getString("content")
    }

    fun cleanResponse(text: String): String {
        var clean = text.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("[*#`~]"), "")
        return clean.replace(Regex("\\s+"), " ").trim()
    }
}
