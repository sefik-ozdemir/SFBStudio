package com.sfbstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class RealEsrganUpscaler(private val context: Context) {
    companion object {
        private const val MODEL_NAME = "Real-ESRGAN-x4plus.tflite"
        private const val MODEL_MIN_BYTES = 50_000_000L
        private const val MODEL_URL_PRIMARY =
            "https://huggingface.co/qualcomm/Real-ESRGAN-x4plus/resolve/main/Real-ESRGAN-x4plus.tflite?download=true"
        private const val MODEL_URL_PINNED =
            "https://huggingface.co/qualcomm/Real-ESRGAN-x4plus/resolve/cf687e89fe86b1d1949594476f2155765d9e057c/Real-ESRGAN-x4plus.tflite?download=true"
        private const val MODEL_INPUT_SIZE = 128
        private const val SCALE = 4
    }

    private var interpreter: Interpreter? = null

    suspend fun ensureModel(): String = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, MODEL_NAME)
        if (file.exists() && file.length() >= MODEL_MIN_BYTES) {
            return@withContext "AI modeli cihazda hazır"
        }

        val temp = File(context.filesDir, "$MODEL_NAME.tmp")
        if (temp.exists()) temp.delete()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        var lastError: Throwable? = null
        for (url in listOf(MODEL_URL_PRIMARY, MODEL_URL_PINNED)) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "SFBStudio/0.4 Android")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Model sunucusu HTTP ${response.code}")
                    }
                    val body = response.body ?: error("Model yanıtı boş")
                    FileOutputStream(temp).use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }

                check(temp.length() >= MODEL_MIN_BYTES) {
                    "İndirilen model eksik (${temp.length()} byte)"
                }

                if (file.exists()) file.delete()
                check(temp.renameTo(file)) { "Model dosyası kaydedilemedi" }
                return@withContext "AI modeli indirildi ve hazır"
            } catch (t: Throwable) {
                lastError = t
                temp.delete()
            }
        }

        throw IllegalStateException(
            "AI modeli indirilemedi. İnternet bağlantısını kontrol edip tekrar deneyin. " +
                "Son hata: ${lastError?.message ?: "bilinmeyen hata"}"
        )
    }

    suspend fun upscale(uri: Uri): Bitmap = withContext(Dispatchers.Default) {
        val input = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("Fotoğraf okunamadı")
        upscaleBitmap(input)
    }

    private fun loadInterpreter(): Interpreter {
        interpreter?.let { return it }
        val file = File(context.filesDir, MODEL_NAME)
        check(file.exists() && file.length() >= MODEL_MIN_BYTES) {
            "AI modeli hazır değil. Önce modeli indirip tekrar deneyin."
        }

        val bytes = file.readBytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()

        return Interpreter(
            buffer,
            Interpreter.Options().apply { setNumThreads(4) }
        ).also { interpreter = it }
    }

    private fun upscaleBitmap(source: Bitmap): Bitmap {
        val model = loadInterpreter()
        require(model.getInputTensor(0).dataType() == DataType.FLOAT32) {
            "Desteklenmeyen model giriş tipi: ${model.getInputTensor(0).dataType()}"
        }
        require(model.getOutputTensor(0).dataType() == DataType.FLOAT32) {
            "Desteklenmeyen model çıkış tipi: ${model.getOutputTensor(0).dataType()}"
        }

        val src = if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val out = Bitmap.createBitmap(
            src.width * SCALE,
            src.height * SCALE,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)

        var y = 0
        while (y < src.height) {
            var x = 0
            while (x < src.width) {
                val w = minOf(MODEL_INPUT_SIZE, src.width - x)
                val h = minOf(MODEL_INPUT_SIZE, src.height - y)
                val tile = Bitmap.createBitmap(src, x, y, w, h)
                val result = runTile(model, tile, w, h)
                canvas.drawBitmap(result, x * SCALE.toFloat(), y * SCALE.toFloat(), null)
                tile.recycle()
                result.recycle()
                x += w
            }
            y += MODEL_INPUT_SIZE
        }

        if (src !== source) src.recycle()
        return out
    }

    private fun runTile(
        model: Interpreter,
        bitmap: Bitmap,
        width: Int,
        height: Int
    ): Bitmap {
        val inputShape = model.getInputTensor(0).shape()
        val inputH = if (inputShape[1] > 0) inputShape[1] else MODEL_INPUT_SIZE
        val inputW = if (inputShape[2] > 0) inputShape[2] else MODEL_INPUT_SIZE

        // The Qualcomm mobile model is trained/exported for 128x128 input.
        // Edge tiles are padded with their nearest pixel and cropped back after inference.
        val inputBitmap = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(inputBitmap)
        val scaleX = inputW.toFloat() / width
        val scaleY = inputH.toFloat() / height
        val scale = minOf(scaleX, scaleY)
        val drawW = (width * scale).toInt().coerceAtLeast(1)
        val drawH = (height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, drawW, drawH, true)
        canvas.drawBitmap(scaled, 0f, 0f, null)
        if (scaled !== bitmap) scaled.recycle()

        val input = ByteBuffer.allocateDirect(inputW * inputH * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputW * inputH)
        inputBitmap.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        for (pixel in pixels) {
            input.putFloat(((pixel shr 16) and 0xFF) / 255f)
            input.putFloat(((pixel shr 8) and 0xFF) / 255f)
            input.putFloat((pixel and 0xFF) / 255f)
        }
        input.rewind()
        inputBitmap.recycle()

        val outputShape = model.getOutputTensor(0).shape()
        val outputH = outputShape[1]
        val outputW = outputShape[2]
        val output = ByteBuffer.allocateDirect(outputW * outputH * 3 * 4)
            .order(ByteOrder.nativeOrder())

        model.run(input, output)
        output.rewind()

        val fullResult = Bitmap.createBitmap(outputW, outputH, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(outputW * outputH)
        for (i in outPixels.indices) {
            val r = (output.float.coerceIn(0f, 1f) * 255f).toInt()
            val g = (output.float.coerceIn(0f, 1f) * 255f).toInt()
            val b = (output.float.coerceIn(0f, 1f) * 255f).toInt()
            outPixels[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        fullResult.setPixels(outPixels, 0, outputW, 0, 0, outputW, outputH)

        val resultW = (width * SCALE).coerceAtMost(outputW)
        val resultH = (height * SCALE).coerceAtMost(outputH)
        val result = Bitmap.createBitmap(fullResult, 0, 0, resultW, resultH)
        fullResult.recycle()
        return result
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
