package com.sfbstudio

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.media.effect.Enhancement
import com.google.android.gms.media.effect.EnhancementCallback
import com.google.android.gms.media.effect.EnhancementClient
import com.google.android.gms.media.effect.EnhancementMode
import com.google.android.gms.media.effect.EnhancementOptions
import com.google.android.gms.media.effect.EnhancementSession
import com.google.android.gms.media.effect.EnhancementSessionCallback
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


data class StudioState(
    val original: Bitmap? = null,
    val result: Bitmap? = null,
    val busy: Boolean = false,
    val status: String = "Bir fotoğraf seçin.",
    val aiSupported: Boolean = false,
    val aiModuleReady: Boolean = false
)

class StudioViewModel(
    private val applicationContext: android.content.Context
) : ViewModel() {

    private val _state = MutableStateFlow(StudioState())
    val state = _state.asStateFlow()

    private val client: EnhancementClient = Enhancement.getClient(applicationContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var session: EnhancementSession? = null

    fun loadBitmap(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bitmap = applicationContext.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
                if (bitmap == null) {
                    _state.value = _state.value.copy(status = "Fotoğraf okunamadı.")
                    return@launch
                }

                val safe = downsampleForMemory(bitmap)
                _state.value = StudioState(original = safe, status = "Fotoğraf hazır.")
                checkAi()
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = "Fotoğraf okunurken hata oluştu: ${e.message}")
            }
        }
    }

    private suspend fun checkAi() {
        try {
            val supported = client.isDeviceSupportedAsync()
            if (!supported) {
                _state.value = _state.value.copy(
                    aiSupported = false,
                    status = "Bu cihaz AI hızlandırmayı desteklemiyor. Yerel fallback kullanılabilir."
                )
                return
            }

            _state.value = _state.value.copy(
                aiSupported = true,
                status = "AI motoru kontrol ediliyor…"
            )

            val installed = client.isModuleInstalledAsync()
            if (!installed) {
                _state.value = _state.value.copy(status = "AI modeli cihaza indiriliyor…")
                client.installModule().awaitTask()
            }

            _state.value = _state.value.copy(
                aiSupported = true,
                aiModuleReady = true,
                status = "AI motoru hazır."
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                aiSupported = false,
                aiModuleReady = false,
                status = "AI motoru kullanılamadı. Fallback hazır."
            )
        }
    }

    fun enhance() {
        val input = _state.value.original ?: return
        if (_state.value.busy) return

        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(busy = true, status = "Görüntü iyileştiriliyor…")
            try {
                val result = if (_state.value.aiSupported && _state.value.aiModuleReady) {
                    enhanceWithAi(input)
                } else {
                    fallbackEnhance(input)
                }
                _state.value = _state.value.copy(
                    result = result,
                    busy = false,
                    status = if (_state.value.aiSupported) "AI super-resolution tamamlandı." else "Yerel fallback tamamlandı."
                )
            } catch (e: Exception) {
                session?.release()
                session = null
                _state.value = _state.value.copy(
                    busy = false,
                    status = "AI başarısız oldu; yerel fallback uygulanıyor…"
                )
                try {
                    val fallback = fallbackEnhance(input)
                    _state.value = _state.value.copy(result = fallback, status = "Fallback tamamlandı.")
                } catch (fallbackError: Exception) {
                    _state.value = _state.value.copy(status = "İşlem başarısız: ${fallbackError.message}")
                }
            }
        }
    }

    private suspend fun enhanceWithAi(input: Bitmap): Bitmap {
        if (session == null) {
            val options = EnhancementOptions(
                input.width,
                input.height,
                EnhancementMode.BITMAP,
                enableTonemap = true,
                enableDeblurDenoise = true,
                enableDenoiseOnly = false,
                enableUpscale = true,
                enableFaceDetection = false
            )
            session = client.createSessionAsync(options, executor)
        }
        return session!!.processBitmapAsync(input, session!!.defaultOptions)
    }

    private fun fallbackEnhance(input: Bitmap): Bitmap {
        val outWidth = (input.width * 2).coerceAtMost(4096)
        val outHeight = (input.height * 2).coerceAtMost(4096)
        val scaled = Bitmap.createScaledBitmap(input, outWidth, outHeight, true)
        val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val matrix = ColorMatrix().apply {
            setSaturation(1.05f)
            val c = 1.08f
            val t = -8f
            set(floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (scaled !== input) scaled.recycle()
        return result
    }

    fun saveResult() {
        val bitmap = _state.value.result ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val name = "SFBStudio_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SFB Studio")
            }
            val uri = applicationContext.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )
            if (uri != null) {
                applicationContext.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                _state.value = _state.value.copy(status = "Galeriye kaydedildi.")
            } else {
                _state.value = _state.value.copy(status = "Galeriye kaydedilemedi.")
            }
        }
    }

    fun clearResult() {
        _state.value = _state.value.copy(result = null, status = "Yeni fotoğraf seçebilirsiniz.")
    }

    private fun downsampleForMemory(bitmap: Bitmap): Bitmap {
        val maxSide = 3072
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxSide) return bitmap
        val scale = maxSide.toFloat() / max.toFloat()
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (resized !== bitmap) bitmap.recycle()
        return resized
    }

    override fun onCleared() {
        session?.release()
        session = null
        executor.shutdown()
        super.onCleared()
    }
}

private suspend fun EnhancementClient.createSessionAsync(
    options: EnhancementOptions,
    executor: ExecutorService
): EnhancementSession = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val callback = object : EnhancementSessionCallback {
            override fun onSessionCreated(session: EnhancementSession) {
                if (continuation.isActive) continuation.resume(session)
            }
            override fun onSessionCreationFailed(status: Status) {
                if (continuation.isActive) continuation.resumeWithException(
                    IllegalStateException("Session creation failed: ${status.statusMessage}")
                )
            }
            override fun onSessionDestroyed() = Unit
            override fun onSessionDisconnected(status: Status) = Unit
        }
        createSession(options, callback).addOnFailureListener(executor) { e ->
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }
}

private suspend fun EnhancementSession.processBitmapAsync(
    bitmap: Bitmap,
    options: EnhancementOptions
): Bitmap = suspendCancellableCoroutine { continuation ->
    val callback = object : EnhancementCallback {
        override fun onBitmapProcessed(enhancedBitmap: Bitmap) {
            if (continuation.isActive) continuation.resume(enhancedBitmap)
        }
        override fun onError(statusCode: Int) {
            if (continuation.isActive) continuation.resumeWithException(
                IllegalStateException("Bitmap processing failed: $statusCode")
            )
        }
        override fun onSurfaceProcessed(timestamp: Long) = Unit
    }
    process(bitmap, options, callback)
}

private suspend fun com.google.android.gms.tasks.Task<Void>.awaitTask() =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
            .addOnFailureListener { e -> if (continuation.isActive) continuation.resumeWithException(e) }
    }

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<StudioViewModel> {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return StudioViewModel(applicationContext) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SfbStudioApp(viewModel) }
    }
}

@Composable
fun SfbStudioApp(vm: StudioViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.loadBitmap(uri)
    }
    var compare by remember { mutableFloatStateOf(1f) }

    MaterialTheme {
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("SFB Studio", style = MaterialTheme.typography.headlineMedium)
                Text("AI Image Enhance • v0.1 Test")
                Spacer(Modifier.height(16.dp))

                Button(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Fotoğraf Seç")
                }

                Spacer(Modifier.height(12.dp))
                Text(state.status)

                state.original?.let { original ->
                    Spacer(Modifier.height(16.dp))
                    Text("Orijinal • ${original.width} × ${original.height}")
                    Image(
                        bitmap = original.asImageBitmap(),
                        contentDescription = "Orijinal fotoğraf",
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                state.result?.let { result ->
                    Spacer(Modifier.height(16.dp))
                    Text("Sonuç • ${result.width} × ${result.height}")
                    Slider(value = compare, onValueChange = { compare = it }, valueRange = 0f..1f)
                    Image(
                        bitmap = result.asImageBitmap(),
                        contentDescription = "İşlenmiş fotoğraf",
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.enhance() }, enabled = state.original != null && !state.busy) {
                        if (state.busy) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        else Text("AI Upscale")
                    }
                    OutlinedButton(onClick = { vm.saveResult() }, enabled = state.result != null) {
                        Text("Kaydet")
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    if (state.aiSupported) "Cihaz: AI hızlandırma uygun" else "Cihaz: fallback modu",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Destekli cihazlarda Google Play services Media Enhancement, görüntüde eksik yüksek frekanslı ayrıntıları yeniden oluşturan super-resolution modeli kullanır.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
