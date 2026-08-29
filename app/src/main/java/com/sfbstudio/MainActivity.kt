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
import androidx.compose.runtime.DisposableEffect
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

    DisposableEffect(Unit) {
        onDispose { upscaler.close() }
    }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resolutionText by remember { mutableStateOf("Henüz fotoğraf seçilmedi.") }
    var status by remember { mutableStateOf("Fotoğraf seçerek başlayın.") }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                busy = true
                operation = "Fotoğraf hazırlanıyor…"
                resultBitmap = null
                val bitmap = uriToBitmap(context, uri)
                selectedUri = uri
                selectedBitmap = bitmap
                resolutionText = "Giriş: ${bitmap.width} × ${bitmap.height} px"
                status = "Fotoğraf hazır. Şimdi 4× AI Upscale'a basın."
                operation = ""
            }.onFailure {
                status = "Hata: ${it.message ?: "fotoğraf okunamadı"}"
                operation = ""
            }
            busy = false
        }
    }

    fun startUpscale() {
        val uri = selectedUri ?: return
        scope.launch {
            busy = true
            operation = "AI modeli kontrol ediliyor…"
            status = "Hazırlanıyor…"
            runCatching {
                upscaler.ensureModel()
                operation = "AI Upscale çalışıyor…"
                status = "Fotoğraf 4× büyütülüyor. Bu işlem cihazın gücüne göre biraz sürebilir."
                resultBitmap = upscaler.upscale(uri)
                val result = resultBitmap ?: error("Upscale sonucu oluşturulamadı")
                status = "Tamamlandı • 4× AI Upscale"
                resolutionText = "Giriş: ${selectedBitmap?.width} × ${selectedBitmap?.height} px  →  Çıkış: ${result.width} × ${result.height} px"
            }.onFailure {
                resultBitmap = null
                status = "Upscale hatası: ${it.message ?: "işlem başarısız"}"
            }
            operation = ""
            busy = false
        }
    }

    fun saveResult() {
        val bitmap = resultBitmap ?: run {
            Toast.makeText(context, "Önce 4× AI Upscale işlemini tamamlayın", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            runCatching {
                saveBitmapToGallery(context, bitmap)
            }.onSuccess {
                Toast.makeText(context, "Sonuç Pictures/SFBStudio içine kaydedildi", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "Kaydetme hatası: ${it.message}", Toast.LENGTH_LONG).show()
            }
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
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SFB Studio", fontWeight = FontWeight.Bold)
                            Text("AI Photo Upscaler • V0.4", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(
                            onClick = { saveResult() },
                            enabled = resultBitmap != null && !busy
                        ) {
                            Text("Kaydet")
                        }
                    }
                }
            },
            bottomBar = {
                Surface(shadowElevation = 10.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .padding(WindowInsets.navigationBars.asPaddingValues()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { picker.launch("image/*") },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📷 Fotoğraf Seç")
                        }
                        Button(
                            onClick = { saveResult() },
                            enabled = resultBitmap != null && !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("✓ Kaydet")
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
                    .padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(14.dp))

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(status, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(resolutionText, style = MaterialTheme.typography.bodySmall)
                        if (busy) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text(operation, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                selectedBitmap?.let { bitmap ->
                    Spacer(Modifier.height(14.dp))
                    PreviewCard("Önce • Orijinal", bitmap, 300)
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { startUpscale() },
                    enabled = selectedUri != null && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "İşleniyor…" else "✨ 4× AI UPSCALE BAŞLAT")
                }

                Spacer(Modifier.height(14.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("AI Upscale", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Real-ESRGAN x4plus modeli cihazda çalışır. İlk kullanımda yaklaşık 67 MB model indirilir ve sonraki kullanımlarda tekrar indirilmez.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                resultBitmap?.let { bitmap ->
                    Spacer(Modifier.height(14.dp))
                    PreviewCard("Sonra • 4× AI Upscale", bitmap, 330)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Çıkış: ${bitmap.width} × ${bitmap.height} px",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { saveResult() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("✓ SONUCU KAYDET")
                    }
                }

                Spacer(Modifier.height(16.dp))
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

private suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Uri =
    withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SFBStudio_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SFBStudio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Galeri kaydı oluşturulamadı")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    "Görsel sıkıştırılamadı"
                }
            } ?: error("Çıkış dosyası açılamadı")

            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
