package com.f4hbw.pocsagsdr

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class POCSAGDecoder(private val onMessageReceived: (PocsagMessage) -> Unit) {

    companion object {
        private const val TAG = "POCSAGDecoder"

        // Constantes POCSAG
        private const val POCSAG_SYNC_WORD = 0x7CD215D8.toInt()
        private const val POCSAG_IDLE_WORD = 0x7A89C197.toInt()
        private const val PREAMBLE_LENGTH = 576
        private const val BATCH_SIZE = 16 // 16 codewords par batch
        private const val CODEWORD_SIZE = 32 // 32 bits par codeword

        // BCH Code Generator Polynomial pour POCSAG
        private const val BCH_POLY = 0xED200000.toInt()
        private const val BCH_N = 31
        private const val BCH_K = 21
    }

    private var sampleRate = 24000 // Après démodulation
    private var baudRate = 1200
    private var samplesPerBit = sampleRate / baudRate

    // État du décodeur
    private enum class DecoderState {
        SEARCHING_PREAMBLE,
        SEARCHING_SYNC,
        RECEIVING_BATCH
    }

    private var state = DecoderState.SEARCHING_PREAMBLE
    private var bitBuffer = mutableListOf<Int>()
    private var sampleBuffer = mutableListOf<Float>()
    private var bitCount = 0
    private var currentBatch = mutableListOf<Int>()
    private var batchPosition = 0

    // Seuil de détection binaire
    private var threshold = 0.0f
    private var runningAverage = 0.0f
    private val averageAlpha = 0.01f

    fun processAudio(audioData: FloatArray) {
        for (sample in audioData) {
            processSample(sample)
        }
    }

    private fun processSample(sample: Float) {
        sampleBuffer.add(sample)

        // Mettre à jour la moyenne mobile pour le seuil adaptatif
        runningAverage = runningAverage * (1 - averageAlpha) + abs(sample) * averageAlpha
        threshold = runningAverage * 0.5f

        // Échantillonnage des bits
        if (sampleBuffer.size >= samplesPerBit) {
            val bit = detectBit()
            bitBuffer.add(bit)
            sampleBuffer.clear()

            when (state) {
                DecoderState.SEARCHING_PREAMBLE -> searchPreamble()
                DecoderState.SEARCHING_SYNC -> searchSync()
                DecoderState.RECEIVING_BATCH -> receiveBatch()
            }
        }
    }

    private fun detectBit(): Int {
        // Intégration sur la période du bit pour détecter 0 ou 1
        val average = sampleBuffer.average().toFloat()
        return if (average > threshold) 1 else 0
    }

    private fun searchPreamble() {
        // Chercher le préambule 101010... de 576 bits
        if (bitBuffer.size >= PREAMBLE_LENGTH) {
            var alternatingCount = 0
            var lastBit = bitBuffer[bitBuffer.size - PREAMBLE_LENGTH]

            for (i in (bitBuffer.size - PREAMBLE_LENGTH + 1) until bitBuffer.size) {
                val currentBit = bitBuffer[i]
                if (currentBit != lastBit) {
                    alternatingCount++
                }
                lastBit = currentBit
            }

            // Si on trouve suffisamment d'alternances (>90% du préambule)
            if (alternatingCount > PREAMBLE_LENGTH * 0.9) {
                Log.d(TAG, "Préambule détecté!")
                state = DecoderState.SEARCHING_SYNC
                bitBuffer.clear()
            } else {
                // Garder seulement les derniers bits pour la recherche continue
                if (bitBuffer.size > PREAMBLE_LENGTH * 2) {
                    bitBuffer = bitBuffer.takeLast(PREAMBLE_LENGTH).toMutableList()
                }
            }
        }
    }

    private fun searchSync() {
        if (bitBuffer.size >= CODEWORD_SIZE) {
            val syncWord = bitsToInt(bitBuffer.takeLast(CODEWORD_SIZE))

            if (syncWord == POCSAG_SYNC_WORD) {
                Log.d(TAG, "Mot de synchronisation trouvé!")
                state = DecoderState.RECEIVING_BATCH
                bitBuffer.clear()
                currentBatch.clear()
                batchPosition = 0
            } else {
                // Décaler d'un bit et continuer la recherche
                bitBuffer.removeAt(0)
            }
        }
    }

    private fun receiveBatch() {
        if (bitBuffer.size >= CODEWORD_SIZE) {
            val codeword = bitsToInt(bitBuffer.take(CODEWORD_SIZE))
            bitBuffer = bitBuffer.drop(CODEWORD_SIZE).toMutableList()

            currentBatch.add(codeword)
            batchPosition++

            if (batchPosition >= BATCH_SIZE) {
                // Batch complet, traiter les messages
                processBatch(currentBatch)

                // Retourner à la recherche de synchronisation
                state = DecoderState.SEARCHING_SYNC
                currentBatch.clear()
                batchPosition = 0
            }
        }
    }

    private fun bitsToInt(bits: List<Int>): Int {
        var result = 0
        for (i in bits.indices) {
            if (bits[i] == 1) {
                result = result or (1 shl (bits.size - 1 - i))
            }
        }
        return result
    }

    private fun processBatch(batch: List<Int>) {
        Log.d(TAG, "Traitement du batch: ${batch.size} codewords")

        var i = 0
        while (i < batch.size) {
            val codeword = batch[i]

            // Vérifier si c'est un codeword d'adresse ou de données
            if (isAddressCodeword(codeword)) {
                val correctedCodeword = correctBCH(codeword)
                if (correctedCodeword != null) {
                    val address = extractAddress(correctedCodeword)
                    val functionCode = extractFunction(correctedCodeword)

                    // Collecter les codewords de message suivants
                    val messageData = mutableListOf<Int>()
                    var j = i + 1

                    while (j < batch.size && !isAddressCodeword(batch[j])) {
                        val msgCodeword = correctBCH(batch[j])
                        if (msgCodeword != null) {
                            messageData.add(msgCodeword)
                        }
                        j++
                    }

                    if (messageData.isNotEmpty()) {
                        val message = decodeMessage(messageData, functionCode)
                        if (message.isNotEmpty()) {
                            deliverMessage(address, message, functionCode)
                        }
                    }

                    i = j
                } else {
                    i++
                }
            } else {
                i++
            }
        }
    }

    private fun isAddressCodeword(codeword: Int): Boolean {
        // Un codeword d'adresse a le bit 0 à 0
        return (codeword and 0x80000000.toInt()) == 0
    }

    private fun correctBCH(codeword: Int): Int? {
        // Implémentation simplifiée de la correction BCH
        // Dans une vraie implémentation, on ferait la correction d'erreurs

        // Calculer le syndrome
        val syndrome = calculateSyndrome(codeword)

        // Si syndrome = 0, pas d'erreur
        if (syndrome == 0) {
            return codeword
        }

        // Tentative de correction d'erreur simple (1 bit)
        for (i in 0 until 32) {
            val testCodeword = codeword xor (1 shl i)
            if (calculateSyndrome(testCodeword) == 0) {
                Log.d(TAG, "Erreur corrigée en position $i")
                return testCodeword
            }
        }

        Log.w(TAG, "Codeword non corrigeable: ${codeword.toString(16)}")
        return null
    }

    private fun calculateSyndrome(codeword: Int): Int {
        // Calcul simplifié du syndrome BCH
        var syndrome = 0
        var data = codeword

        for (i in 0 until BCH_K) {
            if ((data and 0x80000000.toInt()) != 0) {
                data = data xor BCH_POLY
            }
            data = data shl 1
        }

        return syndrome
    }

    private fun extractAddress(codeword: Int): Int {
        // L'adresse POCSAG est sur 18 bits (bits 31-14)
        return (codeword ushr 10) and 0x1FFFF8
    }

    private fun extractFunction(codeword: Int): Int {
        // La fonction est sur 2 bits (bits 11-10)
        return (codeword ushr 11) and 0x3
    }

    private fun decodeMessage(messageData: List<Int>, functionCode: Int): String {
        when (functionCode) {
            0 -> return decodeNumericMessage(messageData)
            1, 2, 3 -> return decodeAlphanumericMessage(messageData)
            else -> return "Message fonction inconnue"
        }
    }

    private fun decodeNumericMessage(messageData: List<Int>): String {
        val message = StringBuilder()

        for (codeword in messageData) {
            // Extraire les données (20 bits de données utiles)
            val data = codeword and 0xFFFFF000.toInt()

            // Décoder 5 digits de 4 bits chacun
            for (i in 0 until 5) {
                val digit = (data ushr (16 - i * 4)) and 0xF
                when (digit) {
                    0xA -> message.append("*")
                    0xB -> message.append("U") // Urgence
                    0xC -> message.append(" ")
                    0xD -> message.append("-")
                    0xE -> message.append(")")
                    0xF -> message.append("(")
                    else -> if (digit < 10) message.append(digit)
                }
            }
        }

        return message.toString().trim()
    }

    private fun decodeAlphanumericMessage(messageData: List<Int>): String {
        val message = StringBuilder()
        var bitAccumulator = 0L
        var bitCount = 0

        for (codeword in messageData) {
            // Ajouter 20 bits de données
            val data = (codeword ushr 11) and 0xFFFFF
            bitAccumulator = (bitAccumulator shl 20) or data.toLong()
            bitCount += 20

            // Décoder par groupes de 7 bits (ASCII)
            while (bitCount >= 7) {
                val char = ((bitAccumulator ushr (bitCount - 7)) and 0x7F).toInt()
                bitCount -= 7

                if (char >= 32 && char <= 126) { // ASCII imprimable
                    message.append(char.toChar())
                } else if (char == 0) {
                    break // Fin de message
                }
            }
        }

        return message.toString().trim()
    }

    private fun deliverMessage(address: Int, message: String, functionCode: Int) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val addressStr = String.format("%07d", address and 0x1FFFFF)
        val typeStr = when (functionCode) {
            0 -> "NUM"
            1 -> "TXT1"
            2 -> "TXT2"
            3 -> "TXT3"
            else -> "UNK"
        }

        val pocsagMessage = PocsagMessage(
            timestamp = timestamp,
            address = addressStr,
            message = message,
            type = typeStr
        )

        Log.i(TAG, "Message POCSAG: $addressStr - $message")
        onMessageReceived(pocsagMessage)
    }
}