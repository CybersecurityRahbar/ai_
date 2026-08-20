package com.example.personalmemoryai.diagnostics

import android.content.Context

/**
 * Runtime telemetry adapter for every ML engine.
 * Readiness must be proven by successful load + inference, not by file existence.
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

    fun loadFailure(model: String, throwable: Throwable, details: Map<String, String> = emptyMap()) {
        diagnostics.record(
            "MODEL_HEALTH", "LOAD_FAILURE", DiagnosticsManager.Severity.ERROR,
            "$model failed to load", exception = throwable,
            metadata = details + mapOf("component" to model)
        )
    }

    fun tensorContractFailure(model: String, reason: String, details: Map<String, String> = emptyMap()) {
        diagnostics.record(
            "MODEL_HEALTH", "TENSOR_CONTRACT_FAILURE", DiagnosticsManager.Severity.ERROR,
            "$model tensor contract invalid: $reason",
            metadata = details + mapOf("component" to model, "reason" to reason)
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
            "$model inference failed", exception = throwable,
            metadata = details + mapOf("component" to model)
        )
    }

    fun outputInvalid(model: String, reason: String, details: Map<String, String> = emptyMap()) {
        diagnostics.record(
            "MODEL_HEALTH", "OUTPUT_INVALID", DiagnosticsManager.Severity.ERROR,
            "$model produced invalid output: $reason",
            metadata = details + mapOf("component" to model, "reason" to reason)
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
