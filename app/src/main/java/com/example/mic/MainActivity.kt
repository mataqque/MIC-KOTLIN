package com.example.mic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.mic.ui.theme.MICTheme
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter

class MainActivity : ComponentActivity() {
    private lateinit var socket: Socket

    companion object {
        private const val REQUEST_CODE = 1001
        private const val TAG = "VirtualMicApp"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkAndRequestPermissions()
        
        enableEdgeToEdge()
        setContent {
            MICTheme {
                AudioStreamScreen(
                    onStartService = { startVirtualMicService() },
                    onStopService = { stopVirtualMicService() }
                )
            }
        }
        
        initSocketIO()
    }

    private fun initSocketIO() {
        try {
            val options = IO.Options().apply {
                transports = arrayOf("websocket")
                reconnection = true
                reconnectionAttempts = 5
                reconnectionDelay = 1000
            }

            socket = IO.socket("http://192.168.100.5:8080", options)

            socket.on(Socket.EVENT_CONNECT, Emitter.Listener {
                runOnUiThread {
                    Toast.makeText(this, "Conectado al servidor", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Socket.IO conectado")
                }
            }).on("audio-chunk", Emitter.Listener { args ->
                try {
                    val audioData = args[0] as ByteArray
                    Log.d(TAG, "Audio Data recibido: ${audioData.size} bytes")

                    if (VirtualMicService.isRunning.get()) {
                        VirtualMicService.audioBuffer.offer(audioData)

                        // Usar las funciones del companion object
                        val pcmData = VirtualMicService.decodeWebmToPcm(audioData)
                        pcmData?.let {
                            VirtualMicService.getAudioTrack()?.write(it, 0, it.size)
                        }
                    } else {
                        Log.w(TAG, "Servicio no activo, no se puede procesar audio")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando audio-chunk: ${e.message}")
                }
            }).on(Socket.EVENT_DISCONNECT, Emitter.Listener {
                runOnUiThread {
                    Toast.makeText(this, "Desconectado del servidor", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Socket.IO desconectado")
                }
            })

            socket.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Error al conectar Socket.IO: ${e.message}")
        }
    }

    private fun startVirtualMicService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService()
        } else {
            startService(Intent(this, VirtualMicService::class.java))
        }
        Toast.makeText(this, "Micrófono virtual iniciado", Toast.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundService() {
        val serviceIntent = Intent(this, VirtualMicService::class.java)
        startForegroundService(serviceIntent)
    }

    private fun stopVirtualMicService() {
        val serviceIntent = Intent(this, VirtualMicService::class.java)
        stopService(serviceIntent)
        Toast.makeText(this, "Micrófono virtual detenido", Toast.LENGTH_SHORT).show()
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE
        )
        
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_CODE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        socket.disconnect()
    }
}

@Composable
fun AudioStreamScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Button(
            onClick = {
                onStartService()
                Toast.makeText(context, "Iniciando micrófono virtual...", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(innerPadding)
        ) {
            Text("Iniciar Micrófono Virtual")
        }
        
        Button(
            onClick = {
                onStopService()
                Toast.makeText(context, "Deteniendo micrófono virtual...", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(innerPadding)
        ) {
            Text("Detener Micrófono Virtual")
        }
    }
}