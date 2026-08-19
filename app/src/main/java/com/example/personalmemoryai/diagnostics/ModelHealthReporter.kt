package com.example.personalmemoryai.diagnostics

import android.content.Context

/**
 * Small adapter used by every ML engine to publish what actually happened at runtime.
 * It deliberately does not infer readiness from the mere existence of a model file.
 */
class ModelHealthReporter(context: Context) {
    private val diagnostics = DiagnosticsManager.get(context.applicationContext)

    fun loaded(model: String, input: String, output: String, details: Map<String, String> = emptyMap()) {
        diagnostics.record(
            "MODEL_HEALTH", "LOAD_SUCCESS", DiagnosticsManager.Severity.INFO,
            "$model loaded successfully",
            metadata = details + mapOf("component" to model, "input" to input, "output" to output)
        )
    }

    fun inferenceSuccess(model: String, latencyMs: Long, outputElements: Int, details: Map<String, String> = emptyMap()) {
        diagnostics.record(
            "MODEL_HEALTH", "INFERENCE_SUCCESS", DiagnosticsManager.Severity.INFO,
            "$model inference succeeded",
            metadata = details + mapOf(
                "component" to model,
                "latencyMs" to latencyMs.toString(),
                "outputElements" to outputElements.toString()
            )
        )
    }

    fun inferenceFailure(model: String, throwable: Throwable, details: Map<String, String> = emptyMap()) {
        diagnostics.record(
            "MODEL_HEALTH", "INFERENCE_FAILURE", DiagnosticsManager.Severity.ERROR,
            "$model inference failed",
            throwable = throwable,
            metadata = details + mapOf("component" to model)
        )
    }

    fun unavailable(model: String, reason: String, details: Map<String, String> = emptyMap()) {
        diagnostics.record(
            "MODEL_HEALTH", "UNAVAILABLE", DiagnosticsManager.Severity.WARNING,
            "$model unavailable: $reason",
            metadata = details + mapOf("component" to model, "reason" to reason)
        )
    }
}
