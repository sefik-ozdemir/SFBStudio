package com.sfbstudio

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SfbStudioApp() }
    }
}

@Composable
private fun SfbStudioApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val upscaler = remember { RealEsrganUpscaler(context.applicationContext) }

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resolutionText by remember { mutableStateOf("Henüz fotoğraf seçilmedi.") }
    var status by remember { mutableStateOf("Hazır") }
    var busy by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        resultBitmap = null
        status = "Fotoğraf yükleniyor…"
        scope.launch {
            runCatching {
                val bitmap = uriToBitmap(context, uri)
                selectedBitmap = bitmap
                resolutionText = "Giriş: ${bitmap.width} × ${bitmap.height} px"
                if (!modelReady) {
                    status = "AI modeli hazırlanıyor… İlk kullanımda yaklaşık 67 MB indirilecek."
                    upscaler.ensureModel()
                    modelReady = true
                }
                status = "AI Upscale çalışıyor…"
                resultBitmap = upscaler.upscale(uri)
                status = "Tamamlandı • 4× AI Upscale"
            }.onFailure {
                status = "Hata: ${it.message ?: "işlem başarısız"}"
            }
            busy = false
        }
    }

    MaterialTheme {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Text("SFB Studio", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(6.dp))
                Text("AI Photo Upscaler • V0.2", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = { picker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) {
                    Text(if (busy) "İşleniyor…" else "📷 Fotoğraf Seç ve AI ile 4× Büyüt")
                }

                Spacer(Modifier.height(16.dp))
                Text(status)
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                Text(resolutionText)

                selectedBitmap?.let {
                    Spacer(Modifier.height(18.dp))
                    Text("Önce", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Orijinal fotoğraf",
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                resultBitmap?.let {
                    Spacer(Modifier.height(18.dp))
                    Text("Sonra • AI Upscale", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "AI ile büyütülmüş fotoğraf",
                        modifier = Modifier.fillMaxWidth().height(360.dp),
                        contentScale = ContentScale.Fit
                    )
                    Text("Çıkış: ${it.width} × ${it.height} px")
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    "V0.2: cihaz üzerinde TensorFlow Lite + Real-ESRGAN x4plus. Model ilk kullanımda indirilir ve cihazda saklanır.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private suspend fun uriToBitmap(context: android.content.Context, uri: Uri): Bitmap =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input)
        } ?: error("Fotoğraf okunamadı")
    }
