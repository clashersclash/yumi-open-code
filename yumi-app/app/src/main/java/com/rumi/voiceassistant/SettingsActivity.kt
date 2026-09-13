package com.rumi.voiceassistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var settings: SettingsStore
    private lateinit var provider: Spinner
    private lateinit var geminiKey: EditText
    private lateinit var geminiModel: EditText
    private lateinit var openAiKey: EditText
    private lateinit var openAiModel: EditText
    private lateinit var ollamaUrl: EditText
    private lateinit var ollamaModel: EditText
    private lateinit var trustedContacts: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = SettingsStore(this)
        provider = findViewById(R.id.providerSpinner)
        geminiKey = findViewById(R.id.geminiKeyInput); geminiModel = findViewById(R.id.geminiModelInput)
        openAiKey = findViewById(R.id.openAiKeyInput); openAiModel = findViewById(R.id.openAiModelInput)
        ollamaUrl = findViewById(R.id.ollamaUrlInput); ollamaModel = findViewById(R.id.ollamaModelInput)
        trustedContacts = findViewById(R.id.trustedContactsInput)
        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("gemini", "openai", "ollama"))
        provider.setSelection(listOf("gemini", "openai", "ollama").indexOf(settings.provider).coerceAtLeast(0))
        geminiKey.setText(settings.geminiKey); geminiModel.setText(settings.geminiModel)
        openAiKey.setText(settings.openAiKey); openAiModel.setText(settings.openAiModel)
        ollamaUrl.setText(settings.ollamaUrl); ollamaModel.setText(settings.ollamaModel)
        trustedContacts.setText(settings.trustedContacts.joinToString(", "))
        provider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = refreshVisibility()
        }
        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener { save() }
        findViewById<Button>(R.id.geminiGuideButton).setOnClickListener { openGuide("https://aistudio.google.com/app/apikey") }
        findViewById<Button>(R.id.openAiGuideButton).setOnClickListener { openGuide("https://platform.openai.com/api-keys") }
        refreshVisibility()
    }
    private fun refreshVisibility() {
        val p = provider.selectedItem?.toString() ?: "gemini"
        findViewById<View>(R.id.geminiFields).visibility = if (p == "gemini") View.VISIBLE else View.GONE
        findViewById<View>(R.id.openAiFields).visibility = if (p == "openai") View.VISIBLE else View.GONE
        findViewById<View>(R.id.ollamaFields).visibility = if (p == "ollama") View.VISIBLE else View.GONE
    }
    private fun save() {
        settings.provider = provider.selectedItem.toString(); settings.geminiKey = geminiKey.text.toString(); settings.geminiModel = geminiModel.text.toString()
        settings.openAiKey = openAiKey.text.toString(); settings.openAiModel = openAiModel.text.toString()
        settings.ollamaUrl = ollamaUrl.text.toString(); settings.ollamaModel = ollamaModel.text.toString()
        settings.trustedContacts = trustedContacts.text.toString().split(',').map { it.trim() }
        Toast.makeText(this, "Settings saved on this device.", Toast.LENGTH_SHORT).show(); finish()
    }
    private fun openGuide(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
