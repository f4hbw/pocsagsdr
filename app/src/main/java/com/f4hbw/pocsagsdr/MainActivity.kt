package com.f4hbw.pocsagsdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var statusText: TextView
    private lateinit var deviceInfoText: TextView
    private lateinit var scanButton: Button
    private lateinit var receptionButton: Button
    private lateinit var preferences: SharedPreferences

    private var currentDevice: UsbDevice? = null

    private val ACTION_USB_PERMISSION = "com.f4hbw.pocsagsdr.USB_PERMISSION"
    private val PREFS_NAME = "SDR_PERMISSIONS"
    private val PREF_GRANTED_DEVICES = "granted_devices"

    // VID/PID connus pour SDR (RTL-SDR, Airspy, HackRF)
    private val sdrDevices = mapOf(
        Pair(0x0bda, 0x2838) to "RTL2838 DVB-T",
        Pair(0x0bda, 0x2832) to "RTL2832U DVB-T",
        Pair(0x0bda, 0x2834) to "RTL2834 DVB-T",
        Pair(0x0bda, 0x2837) to "RTL2837 DVB-T",
        Pair(0x1d50, 0x604b) to "HackRF One",
        Pair(0x1d50, 0x6089) to "Great Scott Gadgets HackRF One",
        Pair(0x1d50, 0x60a1) to "Airspy Mini",
        Pair(0x03eb, 0x800c) to "Airspy R2",
        Pair(0x03eb, 0x800d) to "Airspy HF+"
    )

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    updateStatus("Périphérique USB connecté")
                    scanForSdr()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    updateStatus("Périphérique USB déconnecté")
                    deviceInfoText.text = "Aucun périphérique SDR détecté"
                    currentDevice = null
                    receptionButton.isEnabled = false
                }
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                saveDevicePermission(it)
                                currentDevice = it
                                updateStatus("Permission accordée pour ${it.productName}")
                                displayDeviceInfo(it)
                                receptionButton.isEnabled = true
                            }
                        } else {
                            updateStatus("Permission refusée pour le périphérique USB")
                            currentDevice = null
                            receptionButton.isEnabled = false
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        initializeUsb()
        setupListeners()

        // Scan initial
        scanForSdr()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        deviceInfoText = findViewById(R.id.deviceInfoText)
        scanButton = findViewById(R.id.scanButton)
        receptionButton = findViewById(R.id.receptionButton)
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        updateStatus("Application démarrée")
        deviceInfoText.text = "Aucun périphérique SDR détecté"

        // Bouton réception désactivé par défaut
        receptionButton.isEnabled = false
    }

    private fun initializeUsb() {
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }

        registerReceiver(usbReceiver, filter)
    }

    private fun setupListeners() {
        scanButton.setOnClickListener {
            updateStatus("Scan des périphériques USB...")
            scanForSdr()
        }

        receptionButton.setOnClickListener {
            currentDevice?.let { device ->
                val intent = Intent(this, ReceptionActivity::class.java)
                intent.putExtra("DEVICE_NAME", device.productName ?: "SDR Inconnu")
                intent.putExtra("DEVICE_TYPE", sdrDevices[Pair(device.vendorId, device.productId)] ?: "SDR Inconnu")
                intent.putExtra("VENDOR_ID", device.vendorId)
                intent.putExtra("PRODUCT_ID", device.productId)
                intent.putExtra("DEVICE_ID", device.deviceId)
                startActivity(intent)
            }
        }

        // Long clic pour effacer les permissions sauvegardées
        scanButton.setOnLongClickListener {
            clearStoredPermissions()
            true
        }
    }

    private fun scanForSdr() {
        val deviceList = usbManager.deviceList
        var sdrFound = false

        updateStatus("Scan de ${deviceList.size} périphérique(s) USB")

        for ((_, device) in deviceList) {
            if (isSdrDevice(device)) {
                sdrFound = true
                val deviceType = sdrDevices[Pair(device.vendorId, device.productId)] ?: "SDR Inconnu"
                updateStatus("SDR détecté: $deviceType")

                if (usbManager.hasPermission(device)) {
                    currentDevice = device
                    displayDeviceInfo(device)
                    receptionButton.isEnabled = true
                } else if (hasStoredPermission(device)) {
                    updateStatus("Permission précédemment accordée pour ${deviceType}")
                    currentDevice = device
                    displayDeviceInfo(device)
                    receptionButton.isEnabled = true
                } else {
                    requestPermission(device)
                }
                break
            }
        }

        if (!sdrFound) {
            deviceInfoText.text = "Aucun périphérique SDR détecté\n\n" +
                    "Périphériques USB trouvés:\n" +
                    deviceList.values.joinToString("\n") { device ->
                        "- ${device.productName ?: "Inconnu"} " +
                                "(VID: 0x${String.format("%04x", device.vendorId)}, " +
                                "PID: 0x${String.format("%04x", device.productId)})"
                    }
            updateStatus("Aucun SDR trouvé parmi ${deviceList.size} périphérique(s)")
            currentDevice = null
            receptionButton.isEnabled = false
        }
    }

    private fun isSdrDevice(device: UsbDevice): Boolean {
        val vendorId = device.vendorId
        val productId = device.productId
        return sdrDevices.containsKey(Pair(vendorId, productId))
    }

    private fun requestPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        updateStatus("Demande de permission pour ${device.productName}")
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun displayDeviceInfo(device: UsbDevice) {
        val deviceType = sdrDevices[Pair(device.vendorId, device.productId)] ?: "SDR Inconnu"

        val info = StringBuilder()
        info.append("=== PÉRIPHÉRIQUE SDR DÉTECTÉ ===\n\n")
        info.append("Type: $deviceType\n")
        info.append("Nom du produit: ${device.productName ?: "Non spécifié"}\n")
        info.append("Nom du fabricant: ${device.manufacturerName ?: "Non spécifié"}\n")
        info.append("Numéro de série: ${device.serialNumber ?: "Non spécifié"}\n")
        info.append("VendorID: 0x${String.format("%04x", device.vendorId)}\n")
        info.append("ProductID: 0x${String.format("%04x", device.productId)}\n")
        info.append("Classe: ${device.deviceClass}\n")
        info.append("Sous-classe: ${device.deviceSubclass}\n")
        info.append("Protocole: ${device.deviceProtocol}\n")
        info.append("Version: ${device.version}\n")
        info.append("Nom du périphérique: ${device.deviceName}\n")

        info.append("\n=== INTERFACES ===\n")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            info.append("Interface $i:\n")
            info.append("  Classe: ${iface.interfaceClass}\n")
            info.append("  Sous-classe: ${iface.interfaceSubclass}\n")
            info.append("  Protocole: ${iface.interfaceProtocol}\n")
            info.append("  Endpoints: ${iface.endpointCount}\n")

            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                info.append("    Endpoint $j: 0x${String.format("%02x", endpoint.address)} " +
                        "(${if (endpoint.direction == 0) "OUT" else "IN"})\n")
            }
        }

        deviceInfoText.text = info.toString()
        updateStatus("Informations du SDR affichées")
    }

    private fun updateStatus(message: String) {
        statusText.text = "Status: $message"
    }

    private fun saveDevicePermission(device: UsbDevice) {
        val deviceKey = "${device.vendorId}:${device.productId}"
        val grantedDevices = preferences.getStringSet(PREF_GRANTED_DEVICES, mutableSetOf()) ?: mutableSetOf()
        val newGrantedDevices = grantedDevices.toMutableSet()
        newGrantedDevices.add(deviceKey)

        preferences.edit()
            .putStringSet(PREF_GRANTED_DEVICES, newGrantedDevices)
            .apply()

        updateStatus("Permission sauvegardée pour ${device.productName}")
    }

    private fun hasStoredPermission(device: UsbDevice): Boolean {
        val deviceKey = "${device.vendorId}:${device.productId}"
        val grantedDevices = preferences.getStringSet(PREF_GRANTED_DEVICES, mutableSetOf()) ?: mutableSetOf()
        return grantedDevices.contains(deviceKey)
    }

    private fun clearStoredPermissions() {
        preferences.edit()
            .remove(PREF_GRANTED_DEVICES)
            .apply()
        updateStatus("Permissions sauvegardées effacées")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}