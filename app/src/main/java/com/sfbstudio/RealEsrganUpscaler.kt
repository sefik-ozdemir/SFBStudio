package com.sfbstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RealEsrganUpscaler(private val context: Context) {
    companion object {
        private const val MODEL_NAME = "Real-ESRGAN-x4plus.tflite"
        private const val MODEL_URL = "https://huggingface.co/qualcomm/Real-ESRGAN-x4plus/resolve/main/Real-ESRGAN-x4plus.tflite?download=true"
        private const val TILE = 128
    }

    private var interpreter: Interpreter? = null

    suspend fun upscale(uri: Uri): Bitmap = withContext(Dispatchers.Default) {
        val input = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("Fotoğraf okunamadı")
        upscaleBitmap(input)
    }

    suspend fun ensureModel() = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, MODEL_NAME)
        if (file.exists() && file.length() > 50_000_000L) return@withContext
        val temp = File(context.filesDir, "$MODEL_NAME.tmp")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            requestMethod = "GET"
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) error("Model indirilemedi: HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            if (temp.length() < 50_000_000L) error("İndirilen model eksik")
            if (file.exists()) file.delete()
            check(temp.renameTo(file)) { "Model dosyası kaydedilemedi" }
        } finally {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
            if (temp.exists()) temp.delete()
        }
    }

    private fun loadInterpreter(): Interpreter {
        interpreter?.let { return it }
        val file = File(context.filesDir, MODEL_NAME)
        check(file.exists()) { "AI modeli henüz indirilmedi" }
        val bytes = file.readBytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).rewind()
        return Interpreter(buffer, Interpreter.Options().apply { setNumThreads(4) }).also { interpreter = it }
    }

    private fun upscaleBitmap(source: Bitmap): Bitmap {
        val model = loadInterpreter()
        val src = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
        val out = Bitmap.createBitmap(src.width * 4, src.height * 4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        var y = 0
        while (y < src.height) {
            var x = 0
            while (x < src.width) {
                val w = minOf(TILE, src.width - x)
                val h = minOf(TILE, src.height - y)
                val tile = Bitmap.createBitmap(src, x, y, w, h)
                val result = runTile(model, tile, w, h)
                canvas.drawBitmap(result, x * 4f, y * 4f, null)
                tile.recycle()
                result.recycle()
                x += w
            }
            y += minOf(TILE, src.height - y)
        }
        return out
    }

    private fun runTile(model: Interpreter, bitmap: Bitmap, width: Int, height: Int): Bitmap {
        // Resize interpreter input to this tile size
        model.resizeInput(0, intArrayOf(1, height, width, 3))
        model.allocateTensors()

        // Prepare input buffer (floats: R,G,B normalized to [0,1])
        val input = ByteBuffer.allocateDirect(width * height * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (pixel in pixels) {
            input.putFloat(((pixel shr 16) and 0xFF) / 255f)
            input.putFloat(((pixel shr 8) and 0xFF) / 255f)
            input.putFloat((pixel and 0xFF) / 255f)
        }
        input.rewind()

        // Prepare output buffer based on model output shape
        val shape = model.getOutputTensor(0).shape() // expected [1, oh, ow, 3]
        val oh = shape[1]
        val ow = shape[2]
        val output = ByteBuffer.allocateDirect(oh * ow * 3 * 4).order(ByteOrder.nativeOrder())

        model.run(input, output)
        output.rewind()

        val result = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(ow * oh)
        for (i in outPixels.indices) {
            // read R,G,B floats in order and convert to 0..255
            val rf = try { output.getFloat() } catch (e: Exception) { 0f }
            val gf = try { output.getFloat() } catch (e: Exception) { 0f }
            val bf = try { output.getFloat() } catch (e: Exception) { 0f }
            val r = (rf.coerceIn(0f, 1f) * 255f).toInt()
            val g = (gf.coerceIn(0f, 1f) * 255f).toInt()
            val b = (bf.coerceIn(0f, 1f) * 255f).toInt()
            outPixels[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        result.setPixels(outPixels, 0, ow, 0, 0, ow, oh)
        return result
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
