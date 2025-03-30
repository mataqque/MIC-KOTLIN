package com.example.mic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class VirtualMicService : Service() {
    private lateinit var audioRecord: AudioRecord
    private lateinit var audioTrack: AudioTrack
    private lateinit var mediaCodec: MediaCodec
    private val isRunning = AtomicBoolean(false)
    private lateinit var notification: Notification
    private var virtualMicParcelFileDescriptor: ParcelFileDescriptor? = null

    companion object {
        val audioBuffer = ConcurrentLinkedQueue<ByteArray>()
        val isRunning = AtomicBoolean(false)
        private const val TAG = "VirtualMicService"
        private const val CHANNEL_ID = "VirtualMicChannel"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 44100
        private const val VIRTUAL_MIC_PATH = "/dev/virtual_mic"

        @Volatile
        private var instance: VirtualMicService? = null

        // Función pública para decodificar
        fun decodeWebmToPcm(webmData: ByteArray): ByteArray? {
            return instance?.decodeWebmToPcmInternal(webmData)
        }

        // Función para acceder al audioTrack
        fun getAudioTrack(): AudioTrack? {
            return instance?.audioTrack
        }
    }

    // Función interna de decodificación
    private fun decodeWebmToPcmInternal(webmData: ByteArray): ByteArray? {
        try {
            val inputBufferId = mediaCodec.dequeueInputBuffer(10000)
            if (inputBufferId >= 0) {
                val inputBuffer = mediaCodec.getInputBuffer(inputBufferId)
                inputBuffer?.put(webmData)
                mediaCodec.queueInputBuffer(inputBufferId, 0, webmData.size, 0, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferId >= 0) {
                val outputBuffer = mediaCodec.getOutputBuffer(outputBufferId)
                val pcmData = ByteArray(bufferInfo.size)
                outputBuffer?.get(pcmData)
                mediaCodec.releaseOutputBuffer(outputBufferId, false)
                return pcmData
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decodificando WebM: ${e.message}")
        }
        return null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        if (checkRootAccess()) {
            setupVirtualMicrophone()
            initAudioComponents()
            startAudioProcessing()
        } else {
            Log.e(TAG, "Se requiere acceso root")
            stopSelf()
        }
    }

    private fun checkRootAccess(): Boolean {
        return try {
            Runtime.getRuntime().exec("su").exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun setupVirtualMicrophone() {
        try {
            Runtime.getRuntime().exec("su -c mknod $VIRTUAL_MIC_PATH c 1 9").waitFor()
            Runtime.getRuntime().exec("su -c chmod 666 $VIRTUAL_MIC_PATH").waitFor()
            
            virtualMicParcelFileDescriptor = ParcelFileDescriptor.open(
                File(VIRTUAL_MIC_PATH),
                ParcelFileDescriptor.MODE_READ_WRITE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creando micrófono virtual: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun initAudioComponents() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_OPUS)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            }
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    private fun startAudioProcessing() {
        isRunning.set(true)
        audioRecord.startRecording()
        audioTrack.play()

        Thread {
            val buffer = ByteArray(4096)
            while (isRunning.get()) {
                try {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val encodedData = encodeToOpus(buffer.copyOf(bytesRead))
                        
                        virtualMicParcelFileDescriptor?.fileDescriptor?.let { fd ->
                            FileOutputStream(fd).use { fos ->
                                fos.write(encodedData)
                            }
                        }
                        
                        audioTrack.write(buffer, 0, bytesRead)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error en procesamiento de audio: ${e.message}")
                }
            }
            releaseResources()
        }.start()
    }

    private fun encodeToOpus(pcmData: ByteArray): ByteArray {
        val inputBufferId = mediaCodec.dequeueInputBuffer(10000)
        if (inputBufferId >= 0) {
            mediaCodec.getInputBuffer(inputBufferId)?.put(pcmData)
            mediaCodec.queueInputBuffer(inputBufferId, 0, pcmData.size, 0, 0)
        }

        val bufferInfo = MediaCodec.BufferInfo()
        val outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000)
        if (outputBufferId >= 0) {
            val outputBuffer = mediaCodec.getOutputBuffer(outputBufferId)
            val encodedData = ByteArray(bufferInfo.size)
            outputBuffer?.get(encodedData)
            mediaCodec.releaseOutputBuffer(outputBufferId, false)
            return encodedData
        }
        return byteArrayOf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Virtual Mic Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Servicio de micrófono virtual"
                }
            )
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Micrófono Virtual")
            .setContentText("Transmitiendo audio...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun releaseResources() {
        try {
            audioRecord.stop()
            audioRecord.release()
            audioTrack.stop()
            audioTrack.release()
            mediaCodec.stop()
            mediaCodec.release()
            virtualMicParcelFileDescriptor?.close()
            Runtime.getRuntime().exec("su -c rm $VIRTUAL_MIC_PATH").waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando recursos: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning.set(false)
        instance = null
        releaseResources()
        super.onDestroy()
    }
}