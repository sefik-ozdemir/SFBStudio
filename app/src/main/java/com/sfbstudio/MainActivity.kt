package com.sfbstudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Surface(shadowElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(WindowInsets.statusBars.asPaddingValues())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SFB Studio", fontWeight = FontWeight.Bold)
                            Text("AI Photo Upscaler • V0.3", style = MaterialTheme.typography.bodySmall)
                        }
                        if (resultBitmap != null && !busy) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    resultBitmap?.let { bitmap ->
                                        saveBitmapToGallery(context, bitmap)
                                        Toast.makeText(context, "Sonuç galeriye kaydedildi", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Text("Kaydet")
                            }
                        }
                    }
                }
            },
            bottomBar = {
                Surface(shadowElevation = 8.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .padding(WindowInsets.navigationBars.asPaddingValues())
                    ) {
                        Button(
                            onClick = { picker.launch("image/*") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy
                        ) {
                            Text(if (busy) "İşleniyor…" else "📷 Fotoğraf Seç ve 4× Büyüt")
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(status, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Text(resolutionText, style = MaterialTheme.typography.bodySmall)
                        if (busy) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                selectedBitmap?.let {
                    Spacer(Modifier.height(16.dp))
                    PreviewCard(title = "Önce • Orijinal", bitmap = it, height = 280)
                }

                resultBitmap?.let {
                    Spacer(Modifier.height(16.dp))
                    PreviewCard(title = "Sonra • AI Upscale", bitmap = it, height = 320)
                    Spacer(Modifier.height(8.dp))
                    Text("Çıkış: ${it.width} × ${it.height} px", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { saveBitmapToGallery(context, it) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("✓ DEĞİŞİKLİKLERİ KAYDET")
                    }
                }

                Spacer(Modifier.height(20.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("İşlem Bilgisi", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Cihaz üzerinde TensorFlow Lite + Real-ESRGAN x4plus kullanılır. Model ilk kullanımda indirilir ve cihazda saklanır.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun PreviewCard(title: String, bitmap: Bitmap, height: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

private suspend fun uriToBitmap(context: Context, uri: Uri): Bitmap =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input)
        } ?: error("Fotoğraf okunamadı")
    }

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "SFBStudio_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SFBStudio")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

    return runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "Görsel kaydedilemedi" }
        } ?: error("Dosya açılamadı")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }.getOrElse {
        resolver.delete(uri, null, null)
        throw it
    }
}
