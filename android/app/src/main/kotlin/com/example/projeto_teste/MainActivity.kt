package com.example.projeto_teste

import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import android.media.AudioManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import android.content.Context
import android.media.audiofx.AcousticEchoCanceler

class MainActivity : FlutterActivity() {
    private val CHANNEL = "audio_test"
    private var audioRecorder: AudioRecord? = null
    private var isRecording = false

    // Parâmetros para controle de silêncio – considere substituir por uma solução de VAD robusta (ex.: WebRTC VAD)
    private val silenceThreshold = 1500 // tempo em milissegundos
    private val amplitudeThreshold = 3000 // ajuste conforme necessário

    private lateinit var context: Context
    private lateinit var eventChannel: EventChannel
    private var events: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        context = this

        // Configura MethodChannel para comandos (iniciar/parar gravação)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startRecording" -> {
                    startRecording()
                    result.success(null)
                }
                "stopRecording" -> {
                    stopRecording()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        // Configura EventChannel para enviar eventos de resposta (ex.: resposta do ChatGPT)
        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, "audio_events")
        eventChannel.setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    this@MainActivity.events = events
                }
                override fun onCancel(arguments: Any?) {
                    this@MainActivity.events = null
                }
            }
        )
    }

    private fun startRecording() {
    Log.d("AudioService", "Iniciando gravação...")
    val sampleRate = 16000

    // Dobrar o tamanho mínimo do buffer para segurança
    val bufferSize = 32000

    Log.d("AudioService", "Buffer size calculado: $bufferSize bytes")

    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    audioManager.isSpeakerphoneOn = false

    // Cria o AudioRecord
    audioRecorder = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize
    )

    if (audioRecorder?.state != AudioRecord.STATE_INITIALIZED) {
        Log.e("AudioService", "Falha na inicialização do AudioRecord!")
        return
    }

    // Verifica se o dispositivo suporta o AcousticEchoCanceler
    if (AcousticEchoCanceler.isAvailable()) {
        val aec = AcousticEchoCanceler.create(audioRecorder!!.audioSessionId)
        aec?.enabled = true
        Log.d("AudioService", "AcousticEchoCanceler ativado.")
    } else {
        Log.d("AudioService", "AcousticEchoCanceler não disponível neste dispositivo.")
    }

    audioRecorder?.startRecording()
    isRecording = true

    Log.d("AudioService", "Gravação iniciada com sucesso")

        // Thread para captura contínua e segmentação do áudio
        Thread(Runnable {
            Log.d("AudioThread", "Thread de gravação iniciada")
            val buffer = ShortArray(bufferSize / 2)
            val activeChunks = mutableListOf<ShortArray>()
            var silenceStartTime = -1L

            while (isRecording) {
                val bytesRead = audioRecorder?.read(buffer, 0, buffer.size) ?: 0
                Log.v("AudioThread", "Bytes lidos: $bytesRead")

                if (bytesRead <= 0) {
                    Log.w("AudioThread", "Leitura de bytes inválida: $bytesRead")
                    continue
                }

                val currentChunk = buffer.copyOfRange(0, bytesRead)

                // Aqui, a função isSilent pode ser substituída por uma implementação de VAD mais robusta
                if (isSilent(currentChunk)) {
                    Log.d("AudioDetection", "Silêncio detectado")
                    if (silenceStartTime == -1L) {
                        silenceStartTime = System.currentTimeMillis()
                    }
                    if (System.currentTimeMillis() - silenceStartTime >= silenceThreshold) {
                        if (activeChunks.isNotEmpty()) {
                            // Processa o segmento de áudio de forma assíncrona (STT + ChatGPT)
                            processAudioSegment(activeChunks)
                            activeChunks.clear()
                        }
                        silenceStartTime = -1L
                    }
                } else {
                    Log.d("AudioDetection", "Áudio detectado")
                    activeChunks.add(currentChunk)
                    silenceStartTime = -1L
                }
            }

            // Processa qualquer áudio restante ao parar a gravação
            if (activeChunks.isNotEmpty()) processAudioSegment(activeChunks)
            Log.d("AudioThread", "Thread de gravação finalizada")
        }).start()
    }

    // Função para detectar silêncio – pode ser aprimorada com uma biblioteca de VAD
    private fun isSilent(buffer: ShortArray): Boolean {
        for (value in buffer) {
            if (abs(value.toInt()) > amplitudeThreshold) return false
        }
        return true
    }

    // Processa o segmento de áudio: combina os chunks, salva o arquivo, realiza STT e envia o texto ao ChatGPT
    private fun processAudioSegment(chunks: List<ShortArray>) {
        Log.d("AudioService", "Processando segmento de áudio...")
        val combined = combineShortArrays(chunks)
        val sampleRate = 16000
        val wavFile = File(context.getExternalFilesDir(null), "audio_${System.currentTimeMillis()}.wav")
    
        try {
            FileOutputStream(wavFile).use { fos ->
                val byteBuffer = ByteBuffer.allocate(combined.size * 2)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                byteBuffer.asShortBuffer().put(combined)
                val pcmBytes = byteBuffer.array()
     
                writeWavHeader(fos, pcmBytes.size, sampleRate, 1, 16)
                fos.write(pcmBytes)
            }
            Log.d("AudioService", "Arquivo WAV salvo: ${wavFile.absolutePath}")
     
            runOnUiThread {
                events?.success(wavFile.absolutePath)
            }
        } catch (e: Exception) {
            Log.e("AudioService", "Erro ao salvar WAV", e)
        }
    }

    // Combina múltiplos arrays de áudio em um único array
    private fun combineShortArrays(chunks: List<ShortArray>): ShortArray {
        val totalSize = chunks.sumBy { it.size }
        val combined = ShortArray(totalSize)
        var pos = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, combined, pos, chunk.size)
            pos += chunk.size
        }
        return combined
    }

    // Escreve o cabeçalho WAV para o arquivo de áudio
    private fun writeWavHeader(
        stream: FileOutputStream,
        pcmBytes: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val header = ByteArray(44)
        val dataSize = pcmBytes

        // RIFF header
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()

        // Tamanho do arquivo (dataSize + 36)
        header[4] = (dataSize + 36 and 0xFF).toByte()
        header[5] = (dataSize + 36 shr 8 and 0xFF).toByte()
        header[6] = (dataSize + 36 shr 16 and 0xFF).toByte()
        header[7] = (dataSize + 36 shr 24 and 0xFF).toByte()

        // WAVE format
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()

        // fmt subchunk
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()

        // Tamanho do fmt (16 bytes)
        header[16] = 16
        // Formato PCM (1)
        header[20] = 1
        // Número de canais
        header[22] = channels.toByte()
        // Sample rate
        header[24] = (sampleRate and 0xFF).toByte()
        header[25] = (sampleRate shr 8 and 0xFF).toByte()
        header[26] = (sampleRate shr 16 and 0xFF).toByte()
        header[27] = (sampleRate shr 24 and 0xFF).toByte()

        // Byte rate (sampleRate * channels * bitsPerSample/8)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        header[28] = (byteRate and 0xFF).toByte()
        header[29] = (byteRate shr 8 and 0xFF).toByte()
        header[30] = (byteRate shr 16 and 0xFF).toByte()
        header[31] = (byteRate shr 24 and 0xFF).toByte()

        // Block align (channels * bitsPerSample/8)
        header[32] = (channels * bitsPerSample / 8).toByte()
        // Bits per sample
        header[34] = bitsPerSample.toByte()

        // data subchunk
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()

        // Data size
        header[40] = (dataSize and 0xFF).toByte()
        header[41] = (dataSize shr 8 and 0xFF).toByte()
        header[42] = (dataSize shr 16 and 0xFF).toByte()
        header[43] = (dataSize shr 24 and 0xFF).toByte()

        stream.write(header)
    }

    private fun stopRecording() {
        Log.d("AudioService", "Parando gravação...")
        isRecording = false
        audioRecorder?.stop()
        audioRecorder?.release()
        audioRecorder = null
        Log.d("AudioService", "Gravação parada e recursos liberados")
    }
}
