package com.f4hbw.pocsagsdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

class SDRController(private val context: Context) {

    companion object {
        private const val TAG = "SDRController"
        private const val ACTION_USB_PERMISSION = "com.f4hbw.pocsagsdr.USB_PERMISSION"
        private const val RTL_VENDOR_ID = 0x0bda
        init {
            System.loadLibrary("pocsagsdr-native")
        }
    }

    private lateinit var usbManager: UsbManager
    private var connection: UsbDeviceConnection? = null
    private var endpoint: UsbEndpoint? = null
    @Volatile private var isReceiving = false

    private var statusCallback: ((String) -> Unit)? = null
    private var messageCallback: ((ByteArray, Int) -> Unit)? = null

    fun setStatusCallback(cb: (String) -> Unit) {
        statusCallback = cb
    }

    fun setMessageCallback(cb: (ByteArray, Int) -> Unit) {
        messageCallback = cb
    }

    private val permissionIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION), 0
        )
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                @Suppress("DEPRECATION")
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    statusCallback?.invoke("Permission USB accordée")
                    openDevice(device)
                } else {
                    statusCallback?.invoke("Permission USB refusée pour $device")
                    Log.e(TAG, "Permission USB refusée pour $device")
                }
            }
        }
    }

    fun startReception() {
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        context.registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))

        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == RTL_VENDOR_ID }
        if (device == null) {
            statusCallback?.invoke("RTL-SDR non trouvé")
            Log.e(TAG, "RTL-SDR non trouvé dans deviceList")
            return
        }
        statusCallback?.invoke("Demande de permission USB pour $device")
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun openDevice(device: UsbDevice) {
        connection = usbManager.openDevice(device)
        if (connection == null) {
            statusCallback?.invoke("Impossible d'ouvrir le device USB")
            Log.e(TAG, "usbManager.openDevice() a renvoyé null")
            return
        }

        var chosenInterface: UsbInterface? = null
        // Trouve un endpoint IN bulk
        for (i in 0 until device.interfaceCount) {
            val intf: UsbInterface = device.getInterface(i)
            val ep = (0 until intf.endpointCount)
                .map { intf.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            if (ep != null && connection!!.claimInterface(intf, true)) {
                endpoint = ep
                chosenInterface = intf
                Log.d(TAG, "ClaimInterface ok sur interface #$i, endpoint=0x${ep.address.toString(16)}")
                break
            }
        }

        if (endpoint == null || chosenInterface == null) {
            statusCallback?.invoke("Aucun endpoint IN bulk trouvé")
            Log.e(TAG, "Pas de bulk IN endpoint sur aucune interface")
            return
        }

        // Lance la boucle de lecture en Kotlin
        isReceiving = true
        Thread {
            val buf = ByteArray(16_384)
            while (isReceiving) {
                val len = connection?.bulkTransfer(endpoint, buf, buf.size, 1000) ?: -1
                if (len > 0) {
                    nativeProcess(buf, len)
                }
            }
        }.start()
        statusCallback?.invoke("Streaming démarré en Kotlin")
    }

    fun stopReception() {
        isReceiving = false
        connection?.close()
        statusCallback?.invoke("Réception arrêtée")
    }

    fun cleanup() {
        try { context.unregisterReceiver(usbReceiver) } catch (_: IllegalArgumentException) {}
        statusCallback?.invoke("Cleanup effectué")
    }

    // JNI entrypoint for processing raw bytes
    private external fun nativeProcess(data: ByteArray, length: Int): Int

    /**
     * Appelée par JNI pour livrer des messages décodés.
     */
    @Suppress("unused")
    private fun onNativeMessage(msg: String) {
        statusCallback?.invoke("Message: $msg")
    }
}