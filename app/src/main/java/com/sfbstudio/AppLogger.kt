package com.sfbstudio

import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent diagnostic logger for field/device testing.
 * Logs are stored in app-private storage and are also visible in Logcat.
 */
object AppLogger {
    private const val TAG = "SFBStudio"
    private const val LOG_FILE_NAME = "sfbstudio.log"
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        i("LOGGER", "Logger başlatıldı | Android=${Build.VERSION.RELEASE} | SDK=${Build.VERSION.SDK_INT} | Device=${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun d(event: String, message: String) = write("DEBUG", event, message)
    fun i(event: String, message: String) = write("INFO", event, message)
    fun w(event: String, message: String) = write("WARN", event, message)
    fun e(event: String, message: String, throwable: Throwable? = null) {
        val details = buildString {
            append(message)
            throwable?.let {
                append(" | exception=")
                append(it.javaClass.name)
                append(" | cause=")
                append(it.message ?: "")
                append("\n")
                append(Log.getStackTraceString(it))
            }
        }
        write("ERROR", event, details)
    }

    fun deviceSnapshot(): String {
        val runtime = Runtime.getRuntime()
        val maxMb = runtime.maxMemory() / 1024 / 1024
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1024 / 1024
        return "heapUsed=${usedMb}MB heapMax=${maxMb}MB nativeHeap=${nativeMb}MB"
    }

    fun read(): String = synchronized(lock) {
        val file = logFile() ?: return "Logger henüz başlatılmadı."
        if (!file.exists()) return "Henüz log kaydı yok."
        runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { "Log okunamadı: ${it.message}" }
    }

    fun clear() = synchronized(lock) {
        logFile()?.delete()
    }

    private fun write(level: String, event: String, message: String) {
        val line = "${formatter.format(Date())} | $level | $event | $message"
        when (level) {
            "ERROR" -> Log.e(TAG, line)
            "WARN" -> Log.w(TAG, line)
            "DEBUG" -> Log.d(TAG, line)
            else -> Log.i(TAG, line)
        }

        synchronized(lock) {
            val file = logFile() ?: return
            runCatching {
                if (file.exists() && file.length() > MAX_LOG_BYTES) {
                    val old = file.readText(Charsets.UTF_8)
                    val keepFrom = old.length / 2
                    file.writeText(
                        "--- LOG ROTATED ---\n" + old.substring(keepFrom),
                        Charsets.UTF_8
                    )
                }
                file.appendText(line + "\n", Charsets.UTF_8)
            }.onFailure {
                Log.e(TAG, "Persistent log yazılamadı: ${it.message}")
            }
        }
    }

    private fun logFile(): File? = appContext?.let { File(it.filesDir, LOG_FILE_NAME) }
}
