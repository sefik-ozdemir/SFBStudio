package com.sfbstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SfbStudioApp()
        }
    }
}

private fun decodeBitmap(uri: Uri, contentResolver: android.content.ContentResolver): Bitmap? {
    return contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input)
    }
}

@androidx.compose.runtime.Composable
private fun SfbStudioApp() {
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resolutionText by remember { mutableStateOf("Henüz fotoğraf seçilmedi.") }
    var loading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        loading = true
        val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
        val resolver = activity?.contentResolver

        if (resolver == null) {
            loading = false
            resolutionText = "Fotoğraf açılamadı."
            return@rememberLauncherForActivityResult
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = decodeBitmap(uri, resolver)
            withContext(Dispatchers.Main) {
                selectedBitmap = bitmap
                loading = false
                resolutionText = if (bitmap != null) {
                    "Çözünürlük: ${bitmap.width} × ${bitmap.height} px"
                } else {
                    "Fotoğraf okunamadı."
                }
            }
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
                Text(
                    text = "SFB Studio",
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Fotoğraf geliştirme stüdyosu • v0.1 Test",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { picker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading
                ) {
                    Text(if (loading) "Fotoğraf yükleniyor…" else "📷 Galeriden Fotoğraf Seç")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(resolutionText)

                selectedBitmap?.let { bitmap ->
                    Spacer(modifier = Modifier.height(20.dp))
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Seçilen fotoğraf",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "V0.1: Galeriden fotoğraf seçme testi. AI Upscale bir sonraki sürümde eklenecek.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
