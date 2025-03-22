package com.example.mic

import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioFormat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat 
import android.Manifest
import android.content.pm.PackageManager 

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.mic.ui.theme.MICTheme

class MainActivity : ComponentActivity() {
    companion object{
        private const val REQUEST_CODE = 1001
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MICTheme {
                Scaffold( modifier = Modifier.fillMaxSize() ) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        checkAndRequestPermissions()

    }
    private fun checkAndRequestPermissions() {
        // Verificar si el permiso ya está concedido
        if (  ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Solicitar el permiso
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
        } else {
            // El permiso ya está concedido, puedes iniciar la grabación
            startRecording()
        }
    }

     private fun startRecording() {
        // Aquí colocas el código para iniciar la grabación de audio
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Verificar si el código de solicitud coincide
        if (requestCode == REQUEST_CODE) {
            // Verificar si el permiso fue concedido
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                // Permiso concedido, iniciar la grabación
                startRecording()
            } else {
                // Permiso denegado, mostrar un mensaje al usuario
                // Por ejemplo, un Toast o un diálogo
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MICTheme {
        Greeting("Android")
    }
}