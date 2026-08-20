package com.example.personalmemoryai.diagnostics

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent local JSONL diagnostics for every intelligence pipeline. */
class DiagnosticsManager private constructor(private val context: Context) {
    companion object {
        private const val DIR = "diagnostics"
        private const val FILE = "events.jsonl"
        private const val MAX_EVENTS = 5000
        private const val MAX_MESSAGE = 4000
        @Volatile private var instance: DiagnosticsManager? = null
        fun get(context: Context): DiagnosticsManager = instance ?: synchronized(this) {
            instance ?: DiagnosticsManager(context.applicationContext).also { instance = it }
        }
    }

    private val file: File get() = File(context.filesDir, "$DIR/$FILE")
    private val installed = AtomicBoolean(false)

    fun installGlobalCrashHandler() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record("APP_CRASH", "UNCAUGHT_EXCEPTION", Severity.CRITICAL, throwable.message ?: throwable.javaClass.simpleName, throwable, mapOf("thread" to thread.name))
            previous?.uncaughtException(thread, throwable)
        }
        record("SYSTEM", "STARTUP", Severity.INFO, "Diagnostic monitoring online", metadata = mapOf("android" to Build.VERSION.SDK_INT.toString(), "device" to Build.MODEL))
    }

    fun begin(operation: String, metadata: Map<String, String> = emptyMap()): Run {
        val id = UUID.randomUUID().toString().substring(0, 8)
        record(operation, "START", Severity.INFO, "Operation started", metadata = metadata + ("runId" to id))
        return Run(this, operation, id)
    }

    fun record(operation: String, stage: String, severity: Severity, message: String, exception: Throwable? = null, metadata: Map<String, String> = emptyMap()) {
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            put("operation", operation)
            put("stage", stage)
            put("severity", severity.name)
            put("message", message.take(MAX_MESSAGE))
            if (exception != null) {
                put("exception", exception.javaClass.name)
                put("cause", exception.message?.take(MAX_MESSAGE) ?: "")
                put("stackTrace", exception.stackTraceToString().take(MAX_MESSAGE))
            }
            val meta = JSONObject()
            metadata.forEach { (key, value) -> meta.put(key, value.take(1000)) }
            put("metadata", meta)
        }
        synchronized(file.absolutePath.intern()) { file.parentFile?.mkdirs(); file.appendText(json.toString() + "\n", Charsets.UTF_8); trimIfNeeded() }
    }

    fun readLatest(limit: Int = 250): List<String> = if (!file.exists()) emptyList() else file.readLines(Charsets.UTF_8).takeLast(limit.coerceIn(1, MAX_EVENTS))
    fun clear() { synchronized(file.absolutePath.intern()) { if (file.exists()) file.writeText("") }; record("SYSTEM", "CLEAR_LOG", Severity.INFO, "Diagnostic journal cleared") }
    fun sizeBytes(): Long = if (file.exists()) file.length() else 0L
    private fun trimIfNeeded() { if (!file.exists()) return; val lines = file.readLines(Charsets.UTF_8); if (lines.size > MAX_EVENTS) file.writeText(lines.takeLast(MAX_EVENTS).joinToString("\n") + "\n", Charsets.UTF_8) }

    enum class Severity { DEBUG, INFO, WARNING, ERROR, CRITICAL }

    class Run internal constructor(private val manager: DiagnosticsManager, private val operation: String, val id: String) {
        private val startedAt = System.currentTimeMillis()
        private val terminal = AtomicBoolean(false)
        fun stage(stage: String, message: String, metadata: Map<String, String> = emptyMap()) = manager.record(operation, stage, Severity.INFO, message, metadata = metadata + ("runId" to id))
        fun success(message: String = "Operation completed", metadata: Map<String, String> = emptyMap()) { if (!terminal.compareAndSet(false, true)) return; manager.record(operation, "SUCCESS", Severity.INFO, message, metadata = metadata + ("runId" to id) + ("durationMs" to (System.currentTimeMillis() - startedAt).toString())) }
        fun warning(message: String, metadata: Map<String, String> = emptyMap()) = manager.record(operation, "WARNING", Severity.WARNING, message, metadata = metadata + ("runId" to id))
        fun warning(stage: String, message: String, metadata: Map<String, String> = emptyMap()) = manager.record(operation, stage, Severity.WARNING, message, metadata = metadata + ("runId" to id))
        fun failure(stage: String, throwable: Throwable, metadata: Map<String, String> = emptyMap()) { if (!terminal.compareAndSet(false, true)) return; manager.record(operation, stage, Severity.ERROR, throwable.message ?: "Operation failed", throwable, metadata + ("runId" to id) + ("durationMs" to (System.currentTimeMillis() - startedAt).toString())) }
    }
}
