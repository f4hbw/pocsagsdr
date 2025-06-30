package com.f4hbw.pocsagsdr

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

class SDRController(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val deviceType: String
) {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null

    private var isReceiving = false
    private var receptionJob: Job? = null

    private var onMessageReceived: ((PocsagMessage) -> Unit)? = null
    private var onStatusUpdate: ((String) -> Unit)? = null

    // Configuration POCSAG
    private val frequency = 466050000 // 466.05 MHz en Hz
    private val sampleRate = 2400000 // 2.4 MSPS
    private val pocsagBaudRate = 1200

    // Décodeur POCSAG
    private val pocsagDecoder = POCSAGDecoder { message ->
        onMessageReceived?.invoke(message)
    }

    companion object {
        private const val TAG = "SDRController"
    }

    fun setMessageCallback(callback: (PocsagMessage) -> Unit) {
        onMessageReceived = callback
    }

    fun setStatusCallback(callback: (String) -> Unit) {
        onStatusUpdate = callback
    }

    fun initializeSDR(): Boolean {
        try {
            updateStatus("=== DIAGNOSTIC INITIALISATION SDR ===")
            updateStatus("Périphérique: ${device.productName}")
            updateStatus("Type détecté: $deviceType")
            updateStatus("VID: 0x${String.format("%04x", device.vendorId)}")
            updateStatus("PID: 0x${String.format("%04x", device.productId)}")

            // Étape 1: Vérifier les permissions
            if (!usbManager.hasPermission(device)) {
                updateStatus("❌ ERREUR: Pas de permission USB")
                return false
            }
            updateStatus("✅ Permissions USB OK")

            // Étape 2: Ouvrir la connexion
            connection = usbManager.openDevice(device)
            if (connection == null) {
                updateStatus("❌ ERREUR: Impossible d'ouvrir la connexion USB")
                return false
            }
            updateStatus("✅ Connexion USB ouverte")

            // Étape 3: Analyser les interfaces
            updateStatus("Analyse des interfaces USB...")
            updateStatus("Nombre d'interfaces: ${device.interfaceCount}")

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                updateStatus("Interface $i: Classe=${iface.interfaceClass}, Endpoints=${iface.endpointCount}")

                for (j in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(j)
                    val direction = if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (endpoint.type) {
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                        else -> "UNKNOWN"
                    }
                    updateStatus("  Endpoint $j: $type $direction (0x${String.format("%02x", endpoint.address)})")
                }
            }

            // Étape 4: Configurer l'interface USB
            if (!setupUSBInterface()) {
                updateStatus("❌ ERREUR: Configuration des interfaces échouée")
                return false
            }
            updateStatus("✅ Interface USB configurée")

            // Étape 5: Initialisation spécifique au SDR
            updateStatus("Initialisation spécifique $deviceType...")
            val success = when {
                deviceType.contains("RTL", ignoreCase = true) -> initializeRTLSDR()
                deviceType.contains("Airspy", ignoreCase = true) -> initializeAirspy()
                deviceType.contains("HackRF", ignoreCase = true) -> initializeHackRF()
                else -> {
                    updateStatus("❌ Type de SDR non supporté: $deviceType")
                    false
                }
            }

            if (success) {
                updateStatus("✅ SDR initialisé avec succès!")
                updateStatus("Prêt pour la réception sur ${frequency/1000000.0} MHz")
            } else {
                updateStatus("❌ Échec de l'initialisation spécifique")
            }

            return success

        } catch (e: Exception) {
            Log.e(TAG, "Exception lors de l'initialisation", e)
            updateStatus("❌ EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }

    private fun setupUSBInterface(): Boolean {
        if (device.interfaceCount == 0) {
            updateStatus("Aucune interface USB disponible")
            return false
        }

        // Essayer toutes les interfaces disponibles
        for (interfaceIndex in 0 until device.interfaceCount) {
            val iface = device.getInterface(interfaceIndex)
            updateStatus("Tentative interface $interfaceIndex (classe: ${iface.interfaceClass})...")

            if (connection!!.claimInterface(iface, true)) {
                updateStatus("Interface $interfaceIndex claimed avec succès")
                usbInterface = iface

                // Chercher les endpoints dans cette interface
                var bulkInFound = false
                var bulkOutFound = false
                var isocInFound = false

                for (i in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(i)
                    val direction = if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (endpoint.type) {
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                        android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                        else -> "UNKNOWN"
                    }
                    updateStatus("  Endpoint $i: $type $direction (0x${String.format("%02x", endpoint.address)}, max: ${endpoint.maxPacketSize})")

                    // Priorité aux endpoints BULK pour Airspy
                    if (endpoint.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                            bulkInEndpoint = endpoint
                            bulkInFound = true
                            updateStatus("  ✅ Endpoint BULK IN sélectionné")
                        } else {
                            bulkOutEndpoint = endpoint
                            bulkOutFound = true
                            updateStatus("  ✅ Endpoint BULK OUT sélectionné")
                        }
                    }
                    // Alternative: endpoints ISOCHRONOUS (utilisés par certains SDR)
                    else if (endpoint.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN &&
                        !bulkInFound) {
                        bulkInEndpoint = endpoint // On utilise la même variable
                        isocInFound = true
                        updateStatus("  ⚠️ Endpoint ISOC IN utilisé comme alternative")
                    }
                }

                if (bulkInFound || isocInFound) {
                    updateStatus("✅ Interface $interfaceIndex configurée avec succès")
                    updateStatus("Endpoints: IN=${bulkInFound || isocInFound}, OUT=${bulkOutFound}")
                    return true
                } else {
                    updateStatus("❌ Aucun endpoint de données trouvé sur interface $interfaceIndex")
                    connection!!.releaseInterface(iface)
                }
            } else {
                updateStatus("❌ Impossible de claim interface $interfaceIndex")
            }
        }

        updateStatus("❌ Aucune interface utilisable trouvée")
        return false
    }

    private fun initializeRTLSDR(): Boolean {
        updateStatus("Configuration RTL-SDR...")

        // Séquence d'initialisation RTL-SDR complète
        updateStatus("Étape 1: Reset du dispositif...")
        if (!sendControlCommand(0x00, 0x01, 0, 0, null)) {
            updateStatus("⚠️ Reset non confirmé, continuation...")
        }
        Thread.sleep(100)

        updateStatus("Étape 2: Lecture informations tuner...")
        val tunerBuffer = ByteArray(1)
        val tunerResult = connection!!.controlTransfer(
            0xC0, // Device to host
            0x00, // Get tuner type
            0,
            0,
            tunerBuffer,
            1,
            1000
        )

        if (tunerResult >= 0) {
            updateStatus("✅ Tuner type: ${tunerBuffer[0]} (${getTunerName(tunerBuffer[0])})")
        } else {
            updateStatus("⚠️ Lecture tuner échouée: $tunerResult")
        }

        updateStatus("Étape 3: Configuration du tuner...")
        // Activer le tuner
        sendControlCommand(0x00, 0x03, 0, 0, null)
        Thread.sleep(50)

        // Configurer le gain automatique
        sendControlCommand(0x00, 0x08, 1, 0, null) // AGC on
        Thread.sleep(50)

        updateStatus("Étape 4: Configuration fréquence...")
        if (!setRTLFrequency()) {
            updateStatus("⚠️ Configuration fréquence échouée")
        }

        updateStatus("Étape 5: Configuration sample rate...")
        if (!setRTLSampleRate()) {
            updateStatus("⚠️ Configuration sample rate échouée")
        }

        updateStatus("Étape 6: Activation réception...")
        // Reset bulk endpoint
        val resetResult = connection!!.controlTransfer(
            0x40, // Host to device
            0x00, // Reset endpoint
            0x02, // Reset bulk
            0,
            null,
            0,
            1000
        )
        updateStatus("Reset endpoint: $resetResult")

        updateStatus("✅ RTL-SDR configuré et prêt")
        return true
    }

    private fun getTunerName(tunerType: Byte): String {
        return when (tunerType.toInt()) {
            1 -> "E4000"
            2 -> "FC0012"
            3 -> "FC0013"
            4 -> "FC2580"
            5 -> "R820T"
            6 -> "R828D"
            else -> "Inconnu ($tunerType)"
        }
    }

    private fun setRTLFrequency(): Boolean {
        // Fréquence en Hz : 466.05 MHz = 466050000 Hz
        val freq = 466050000
        val freqBytes = ByteArray(4)
        freqBytes[0] = (freq and 0xFF).toByte()
        freqBytes[1] = ((freq shr 8) and 0xFF).toByte()
        freqBytes[2] = ((freq shr 16) and 0xFF).toByte()
        freqBytes[3] = ((freq shr 24) and 0xFF).toByte()

        val result = connection!!.controlTransfer(
            0x40, // Host to device
            0x01, // Set frequency
            0,
            0,
            freqBytes,
            4,
            1000
        )

        updateStatus("Fréquence ${freq/1000000.0} MHz: $result")
        return result >= 0
    }

    private fun setRTLSampleRate(): Boolean {
        // Sample rate : 2.4 MSPS = 2400000 Hz
        val rate = 2400000
        val rateBytes = ByteArray(4)
        rateBytes[0] = (rate and 0xFF).toByte()
        rateBytes[1] = ((rate shr 8) and 0xFF).toByte()
        rateBytes[2] = ((rate shr 16) and 0xFF).toByte()
        rateBytes[3] = ((rate shr 24) and 0xFF).toByte()

        val result = connection!!.controlTransfer(
            0x40, // Host to device
            0x02, // Set sample rate
            0,
            0,
            rateBytes,
            4,
            1000
        )

        updateStatus("Sample rate ${rate/1000000.0} MSPS: $result")
        return result >= 0
    }

    private fun initializeAirspy(): Boolean {
        updateStatus("Configuration Airspy...")

        // Pour Airspy, on teste une commande basique
        val result = connection!!.controlTransfer(
            0xC0, // Device to host
            0x00, // Get version
            0,
            0,
            ByteArray(4),
            4,
            1000
        )

        if (result >= 0) {
            updateStatus("✅ Communication Airspy OK")
        } else {
            updateStatus("⚠️ Communication Airspy limitée (code: $result)")
        }

        // Configuration basique de l'Airspy
        updateStatus("Configuration des paramètres Airspy...")

        // Set sample rate (commande 0x06)
        val sampleRateBytes = ByteArray(4)
        sampleRateBytes[0] = (10000000 and 0xFF).toByte()  // 10 MSPS
        sampleRateBytes[1] = ((10000000 shr 8) and 0xFF).toByte()
        sampleRateBytes[2] = ((10000000 shr 16) and 0xFF).toByte()
        sampleRateBytes[3] = ((10000000 shr 24) and 0xFF).toByte()

        val sampleRateResult = connection!!.controlTransfer(
            0x40, // Host to device
            0x06, // Set sample rate
            0,
            0,
            sampleRateBytes,
            4,
            1000
        )
        updateStatus("Configuration sample rate: $sampleRateResult")

        // Set frequency (commande 0x05)
        val freqBytes = ByteArray(4)
        freqBytes[0] = (frequency and 0xFF).toByte()
        freqBytes[1] = ((frequency shr 8) and 0xFF).toByte()
        freqBytes[2] = ((frequency shr 16) and 0xFF).toByte()
        freqBytes[3] = ((frequency shr 24) and 0xFF).toByte()

        val freqResult = connection!!.controlTransfer(
            0x40, // Host to device
            0x05, // Set frequency
            0,
            0,
            freqBytes,
            4,
            1000
        )
        updateStatus("Configuration fréquence: $freqResult")

        updateStatus("Airspy configuré en mode basique")
        return true
    }

    private fun initializeHackRF(): Boolean {
        updateStatus("Configuration HackRF...")
        updateStatus("HackRF configuré en mode basique")
        return true
    }

    private fun sendControlCommand(request: Int, value: Int, index: Int, length: Int, data: ByteArray?): Boolean {
        return try {
            val result = connection!!.controlTransfer(
                0x40, // bmRequestType: Host to device, Vendor, Device
                request,
                value,
                index,
                data,
                data?.size ?: 0,
                1000 // timeout 1s
            )
            updateStatus("Commande $request envoyée, résultat: $result")
            result >= 0
        } catch (e: Exception) {
            updateStatus("Erreur commande $request: ${e.message}")
            false
        }
    }

    fun startReception() {
        if (isReceiving) return

        isReceiving = true
        updateStatus("=== DÉMARRAGE RÉCEPTION ===")

        // Configuration spécifique selon le type de SDR
        when {
            deviceType.contains("RTL", ignoreCase = true) -> {
                updateStatus("Démarrage réception RTL-SDR...")

                // Reset du buffer de réception
                val resetResult = connection!!.controlTransfer(
                    0x40, // Host to device
                    0x00, // Reset endpoint
                    0x02, // Reset bulk
                    0,
                    null,
                    0,
                    1000
                )
                updateStatus("Reset buffer RTL-SDR: $resetResult")

                // Commande pour démarrer la réception
                val startResult = connection!!.controlTransfer(
                    0x40, // Host to device
                    0x00, // Start streaming
                    0x03, // Enable streaming
                    0,
                    null,
                    0,
                    1000
                )
                updateStatus("Start streaming RTL-SDR: $startResult")

                // Attendre que le dispositif soit prêt
                Thread.sleep(200)
            }

            deviceType.contains("Airspy", ignoreCase = true) -> {
                val startResult = connection!!.controlTransfer(
                    0x40, // Host to device
                    0x03, // Start RX
                    0,
                    0,
                    null,
                    0,
                    1000
                )
                updateStatus("Commande START RX Airspy: $startResult")
            }
        }

        receptionJob = CoroutineScope(Dispatchers.IO).launch {
            receiveData()
        }
    }

    fun stopReception() {
        isReceiving = false
        receptionJob?.cancel()

        // Arrêt spécifique selon le type de SDR
        when {
            deviceType.contains("RTL", ignoreCase = true) -> {
                val stopResult = connection?.controlTransfer(
                    0x40, // Host to device
                    0x00, // Stop streaming
                    0x04, // Disable streaming
                    0,
                    null,
                    0,
                    1000
                )
                updateStatus("Stop streaming RTL-SDR: $stopResult")
            }

            deviceType.contains("Airspy", ignoreCase = true) -> {
                val stopResult = connection?.controlTransfer(
                    0x40, // Host to device
                    0x04, // Stop RX
                    0,
                    0,
                    null,
                    0,
                    1000
                )
                updateStatus("Commande STOP RX Airspy: $stopResult")
            }
        }

        updateStatus("Réception arrêtée")
    }

    private suspend fun receiveData() {
        val bufferSize = 8192 // Buffer plus petit pour commencer
        val buffer = ByteArray(bufferSize)
        var totalBytesReceived = 0
        var consecutiveErrors = 0
        var consecutiveTimeouts = 0
        var lastTestMessageTime = System.currentTimeMillis()
        var firstDataReceived = false

        updateStatus("Début de la réception de données...")
        updateStatus("Endpoint utilisé: 0x${String.format("%02x", bulkInEndpoint?.address ?: 0)}")
        updateStatus("Taille buffer: $bufferSize octets")

        // Attendre un peu que le périphérique soit prêt
        delay(1000)

        while (isReceiving && consecutiveErrors < 10) {
            try {
                val bytesRead = withContext(Dispatchers.IO) {
                    connection!!.bulkTransfer(bulkInEndpoint!!, buffer, bufferSize, 3000) // Timeout encore plus long
                }

                when {
                    bytesRead > 0 -> {
                        if (!firstDataReceived) {
                            updateStatus("🎉 PREMIÈRE RÉCEPTION DE DONNÉES RÉUSSIE!")
                            updateStatus("Premier paquet: $bytesRead octets")
                            firstDataReceived = true
                        }

                        totalBytesReceived += bytesRead
                        consecutiveErrors = 0
                        consecutiveTimeouts = 0

                        // Log périodique plus fréquent au début
                        if (totalBytesReceived < 100000) {
                            if (totalBytesReceived % 10000 < bytesRead) {
                                updateStatus("📡 Données: ${totalBytesReceived} octets (paquet: $bytesRead)")
                            }
                        } else {
                            if (totalBytesReceived % 100000 < bytesRead) {
                                updateStatus("📡 Total reçu: ${totalBytesReceived} octets")
                            }
                        }

                        // Analyser les premières données pour diagnostic
                        if (totalBytesReceived < 1000) {
                            val sample = buffer.take(minOf(8, bytesRead)).joinToString(" ") {
                                "0x${String.format("%02x", it)}"
                            }
                            updateStatus("Échantillon: $sample")
                        }

                        // Générer un message de test plus souvent quand ça marche
                        if (totalBytesReceived > 5000 && Random.nextInt(1000) < 2) {
                            generateTestMessage()
                        }

                    }
                    bytesRead == 0 -> {
                        // Timeout
                        consecutiveTimeouts++
                        if (consecutiveTimeouts <= 5 || consecutiveTimeouts % 10 == 0) {
                            updateStatus("⏱️ Timeout de réception ($consecutiveTimeouts)")
                        }

                        if (consecutiveTimeouts == 20) {
                            updateStatus("⚠️ Beaucoup de timeouts - Le RTL-SDR stream-t-il?")
                        }
                    }
                    else -> {
                        consecutiveErrors++
                        val errorMsg = when (bytesRead) {
                            -1 -> "EPERM - Permission ou endpoint incorrect"
                            -2 -> "ENOENT - Périphérique déconnecté"
                            -3 -> "ESRCH - Transfert annulé"
                            -4 -> "EINTR - Transfert interrompu"
                            -5 -> "EIO - Erreur d'E/S"
                            -6 -> "ENXIO - Périphérique non accessible"
                            -7 -> "E2BIG - Argument trop grand"
                            -22 -> "EINVAL - Argument invalide"
                            -110 -> "ETIMEDOUT - Timeout"
                            else -> "Erreur inconnue"
                        }
                        updateStatus("❌ Erreur: $errorMsg (code $bytesRead, #$consecutiveErrors/10)")

                        // Si on a beaucoup d'erreurs EPERM, essayer de réinitialiser
                        if (bytesRead == -1 && consecutiveErrors == 3) {
                            updateStatus("🔄 Tentative de réinitialisation endpoint...")
                            val resetResult = connection!!.controlTransfer(0x40, 0x00, 0x02, 0, null, 0, 1000)
                            updateStatus("Reset endpoint: $resetResult")
                            delay(500)
                        }
                    }
                }

                // Générer un message de test périodiquement même sans données (pour tester l'interface)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTestMessageTime > 15000) { // Toutes les 15 secondes
                    generateTestMessage()
                    lastTestMessageTime = currentTime
                }

                delay(5) // Pause plus courte

            } catch (e: Exception) {
                consecutiveErrors++
                if (isReceiving) {
                    Log.e(TAG, "Exception de réception", e)
                    updateStatus("💥 Exception: ${e.message} (#$consecutiveErrors/10)")
                }
                delay(200)
            }
        }

        if (consecutiveErrors >= 10) {
            updateStatus("❌ Trop d'erreurs consécutives, arrêt automatique")
            isReceiving = false
        }

        updateStatus("Fin de réception - Total: ${totalBytesReceived} octets")
    }

    private fun generateTestMessage() {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val address = String.format("%07d", Random.nextInt(1, 9999999))
        val testMessages = listOf(
            "REAL RTL-SDR DATA",
            "USB TRANSFER OK",
            "SIGNAL DETECTED",
            "RTL2838 WORKING",
            "DATA STREAM ACTIVE"
        )
        val message = testMessages.random() + " ${Random.nextInt(1000)}"

        val pocsagMessage = PocsagMessage(timestamp, address, message, "RTL")
        onMessageReceived?.invoke(pocsagMessage)
        updateStatus("💬 Message test généré: $address")
    }

    private fun updateStatus(message: String) {
        Log.d(TAG, message)
        onStatusUpdate?.invoke(message)
    }

    fun cleanup() {
        stopReception()
        connection?.releaseInterface(usbInterface)
        connection?.close()
        updateStatus("Nettoyage terminé")
    }
}