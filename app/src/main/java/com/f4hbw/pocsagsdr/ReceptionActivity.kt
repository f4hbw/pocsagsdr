package com.f4hbw.pocsagsdr

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class PocsagMessage(
    val timestamp: String,
    val address: String,
    val message: String,
    val type: String
)

class ReceptionActivity : AppCompatActivity() {

    private lateinit var deviceInfoText: TextView
    private lateinit var statusText: TextView
    private lateinit var debugText: TextView
    private lateinit var startStopButton: Button
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private val messages = mutableListOf<PocsagMessage>()
    private var isReceiving = false

    private lateinit var sdrController: SDRController

    companion object {
        private const val TAG = "ReceptionActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reception)

        initializeViews()
        setupRecyclerView()

        // Instanciation du contrôleur SDR
        sdrController = SDRController(this)

        // Callback de statut
        sdrController.setStatusCallback { status ->
            runOnUiThread {
                statusText.text = "Status: $status"
                // On cumule dans le debug log
                val current = debugText.text.toString()
                val combined = "$status\n$current"
                debugText.text = combined.lines().take(20).joinToString("\n")
            }
        }

        // Callback de réception de données brutes
        sdrController.setMessageCallback { data, length ->
            val text = String(data, 0, length).trim()
            val msg = PocsagMessage(
                timestamp = System.currentTimeMillis().toString(),
                address = "",         // À remplir après décodage POCSAG
                message = text,
                type = ""             // À remplir après décodage POCSAG
            )
            runOnUiThread { addMessage(msg) }
        }

        // Préparation du bouton
        statusText.text = "Status: Prêt"
        startStopButton.isEnabled = true
        startStopButton.setOnClickListener {
            if (isReceiving) stopReception() else startReception()
        }
    }

    private fun initializeViews() {
        deviceInfoText    = findViewById(R.id.deviceInfoText)
        statusText        = findViewById(R.id.statusText)
        debugText         = findViewById(R.id.debugText)
        startStopButton   = findViewById(R.id.startStopButton)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(messages)
        messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ReceptionActivity)
            adapter = messageAdapter
        }
    }

    private fun startReception() {
        isReceiving = true
        startStopButton.text = "Arrêter la réception"
        startStopButton.backgroundTintList =
            ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        try {
            sdrController.startReception()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur de démarrage de la réception", e)
            statusText.text = "Status: Erreur de démarrage - ${e.message}"
            stopReception()
        }
    }

    private fun stopReception() {
        isReceiving = false
        startStopButton.text = "Lancer la réception"
        startStopButton.backgroundTintList =
            ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
        try {
            sdrController.stopReception()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur d'arrêt de la réception", e)
            statusText.text = "Status: Erreur d'arrêt - ${e.message}"
        }
    }

    private fun addMessage(message: PocsagMessage) {
        // Ajout en tête (plus récent d'abord)
        messages.add(0, message)
        if (messages.size > 100) messages.removeAt(100)
        messageAdapter.notifyItemInserted(0)
        messagesRecyclerView.scrollToPosition(0)
        Log.i(TAG, "Nouveau message POCSAG: ${message.message}")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiving) stopReception()
        sdrController.cleanup()
    }

    override fun onBackPressed() {
        if (isReceiving) stopReception()
        super.onBackPressed()
    }
}
