package com.rumi.voiceassistant

import android.content.Context
import org.json.JSONArray

/** Local, user-entered provider settings. Keys are never bundled in the APK. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("yumi_settings", Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString("provider", "gemini") ?: "gemini"
        set(value) = prefs.edit().putString("provider", value).apply()
    var geminiKey: String
        get() = prefs.getString("gemini_key", "") ?: ""
        set(value) = prefs.edit().putString("gemini_key", value.trim()).apply()
    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(value) = prefs.edit().putString("gemini_model", value.trim()).apply()
    var openAiKey: String
        get() = prefs.getString("openai_key", "") ?: ""
        set(value) = prefs.edit().putString("openai_key", value.trim()).apply()
    var openAiModel: String
        get() = prefs.getString("openai_model", "gpt-4.1-mini") ?: "gpt-4.1-mini"
        set(value) = prefs.edit().putString("openai_model", value.trim()).apply()
    var ollamaUrl: String
        get() = prefs.getString("ollama_url", "http://10.0.2.2:11434") ?: "http://10.0.2.2:11434"
        set(value) = prefs.edit().putString("ollama_url", value.trim().trimEnd('/')).apply()
    var ollamaModel: String
        get() = prefs.getString("ollama_model", "llama3.2") ?: "llama3.2"
        set(value) = prefs.edit().putString("ollama_model", value.trim()).apply()

    /** Names the user explicitly wants Yumi to recognise as frequent call contacts. */
    var trustedContacts: List<String>
        get() = try {
            val values = JSONArray(prefs.getString("trusted_contacts", "[]"))
            List(values.length()) { values.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
        set(value) = prefs.edit().putString(
            "trusted_contacts",
            JSONArray(value.map { it.trim() }.filter { it.isNotBlank() }).toString(),
        ).apply()

    fun isConfigured(): Boolean = when (provider) {
        "gemini" -> geminiKey.isNotBlank()
        "openai" -> openAiKey.isNotBlank()
        "ollama" -> ollamaUrl.isNotBlank() && ollamaModel.isNotBlank()
        else -> false
    }
}
