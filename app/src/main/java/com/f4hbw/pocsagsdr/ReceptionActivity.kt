package com.f4hbw.pocsagsdr

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.Context
import android.util.Log

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

    private lateinit var usbManager: UsbManager
    private var currentDevice: UsbDevice? = null
    private var sdrController: SDRController? = null

    private val frequency = "466.05 MHz"
    private val baudRate = "1200 bauds"

    companion object {
        private const val TAG = "ReceptionActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reception)

        initializeViews()
        setupRecyclerView()
        setupListeners()

        // Initialiser le gestionnaire USB
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Récupérer les informations du périphérique
        val deviceName = intent.getStringExtra("DEVICE_NAME") ?: "SDR Inconnu"
        val deviceType = intent.getStringExtra("DEVICE_TYPE") ?: "SDR Inconnu"

        deviceInfoText.text = "Périphérique: $deviceType\nNom: $deviceName\nFréquence: $frequency\nDébit: $baudRate"
        statusText.text = "Status: Recherche du périphérique SDR..."

        // Trouver et initialiser le périphérique SDR
        findAndInitializeSDR(deviceType)
    }

    private fun initializeViews() {
        deviceInfoText = findViewById(R.id.deviceInfoText)
        statusText = findViewById(R.id.statusText)
        debugText = findViewById(R.id.debugText)
        startStopButton = findViewById(R.id.startStopButton)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(messages)
        messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@ReceptionActivity)
            adapter = messageAdapter
        }
    }

    private fun setupListeners() {
        startStopButton.setOnClickListener {
            if (isReceiving) {
                stopReception()
            } else {
                startReception()
            }
        }
    }

    private fun findAndInitializeSDR(deviceType: String) {
        val deviceList = usbManager.deviceList

        for ((_, device) in deviceList) {
            if (isSDRDevice(device)) {
                currentDevice = device
                Log.d(TAG, "Périphérique SDR trouvé: ${device.productName}")

                if (usbManager.hasPermission(device)) {
                    initializeSDRController(device, deviceType)
                } else {
                    statusText.text = "Status: Permission USB requise"
                    startStopButton.isEnabled = false
                }
                return
            }
        }

        statusText.text = "Status: Aucun périphérique SDR trouvé"
        startStopButton.isEnabled = false
    }

    private fun isSDRDevice(device: UsbDevice): Boolean {
        val sdrDevices = mapOf(
            Pair(0x0bda, 0x2838) to "RTL2838 DVB-T",
            Pair(0x0bda, 0x2832) to "RTL2832U DVB-T",
            Pair(0x1d50, 0x60a1) to "Airspy Mini",
            Pair(0x1d50, 0x604b) to "HackRF One"
        )

        return sdrDevices.containsKey(Pair(device.vendorId, device.productId))
    }

    private fun initializeSDRController(device: UsbDevice, deviceType: String) {
        try {
            sdrController = SDRController(usbManager, device, deviceType)

            // Configurer les callbacks
            sdrController?.setMessageCallback { message ->
                runOnUiThread {
                    addMessage(message)
                }
            }

            sdrController?.setStatusCallback { status ->
                runOnUiThread {
                    statusText.text = "Status: $status"

                    // Ajouter au debug log
                    val currentDebug = debugText.text.toString()
                    val newDebug = "$status\n$currentDebug"

                    // Garder seulement les 20 dernières lignes
                    val lines = newDebug.split("\n")
                    debugText.text = lines.take(20).joinToString("\n")
                }
            }

            // Initialiser le SDR
            if (sdrController?.initializeSDR() == true) {
                statusText.text = "Status: SDR initialisé - Prêt à recevoir"
                startStopButton.isEnabled = true
            } else {
                statusText.text = "Status: Erreur d'initialisation du SDR"
                startStopButton.isEnabled = false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Erreur d'initialisation du SDR", e)
            statusText.text = "Status: Erreur - ${e.message}"

            // Ajouter l'exception aux logs de debug
            val currentDebug = debugText.text.toString()
            val errorMsg = "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
            debugText.text = "$errorMsg\n$currentDebug"

            startStopButton.isEnabled = false
        }
    }

    private fun startReception() {
        if (sdrController == null) {
            statusText.text = "Status: Erreur - SDR non initialisé"
            return
        }

        isReceiving = true
        startStopButton.text = "🛑 Arrêter la réception"
        startStopButton.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)

        try {
            sdrController?.startReception()
            statusText.text = "Status: 📡 Réception POCSAG en cours sur $frequency"
        } catch (e: Exception) {
            Log.e(TAG, "Erreur de démarrage de la réception", e)
            statusText.text = "Status: Erreur de démarrage - ${e.message}"
            stopReception()
        }
    }

    private fun stopReception() {
        isReceiving = false
        startStopButton.text = "🚀 Lancer la réception"
        startStopButton.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)

        try {
            sdrController?.stopReception()
            statusText.text = "Status: Réception arrêtée"
        } catch (e: Exception) {
            Log.e(TAG, "Erreur d'arrêt de la réception", e)
            statusText.text = "Status: Erreur d'arrêt - ${e.message}"
        }
    }

    private fun addMessage(message: PocsagMessage) {
        // Ajouter au début de la liste (plus récent en premier)
        messages.add(0, message)

        // Limiter à 100 messages pour les performances
        if (messages.size > 100) {
            messages.removeAt(100)
        }

        messageAdapter.notifyItemInserted(0)
        messagesRecyclerView.scrollToPosition(0)

        Log.i(TAG, "Nouveau message POCSAG: ${message.address} - ${message.message}")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiving) {
            stopReception()
        }
        sdrController?.cleanup()
    }

    override fun onBackPressed() {
        if (isReceiving) {
            stopReception()
        }
        super.onBackPressed()
    }
}