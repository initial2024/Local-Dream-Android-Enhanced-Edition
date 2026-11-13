package io.github.xororz.localdream.service

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Low-RAM, MNN-only hand-off: one BackendService serializes destruction of SD
 * and startup of SR on its single backend-control thread. Never launch a
 * second standalone process on port 8081.
 *
 * The service's Running + servingModelId is emitted only AFTER the previous
 * child was reaped (bounded destroy/waitFor) and the new child started.
 * Work and restore are scoped to the same UI operation, including failures.
 */
object MnnBackendHandoff {
    const val SR_MODEL_ID = "upscaler_standalone"
    private const val TAG = "MnnBackendHandoff"
    private const val START_TIMEOUT_MS = 60000L

    private fun requestStart(
        context: Context, modelId: String, backendType: String,
        width: Int, height: Int,
    ) {
        context.startForegroundService(
            Intent(context, BackendService::class.java).apply {
                putExtra("modelId", modelId)
                putExtra("backendType", backendType)
                putExtra("width", width)
                putExtra("height", height)
            },
        )
    }

    private suspend fun awaitServing(modelId: String) {
        try {
            withTimeout(START_TIMEOUT_MS) {
                combine(
                    BackendService.backendState,
                    BackendService.servingModelId,
                ) { state, serving -> state to serving }
                    .first { (state, serving) ->
                        // Do not count the old Running state as successful SR.
                        serving == modelId && state is BackendService.BackendState.Running
                    }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("MNN backend did not become ready for " + modelId, e)
        }
    }

    private fun requestStop(context: Context) {
        context.startForegroundService(
            Intent(context, BackendService::class.java).apply {
                action = BackendService.ACTION_STOP_NOW
                putExtra(BackendService.EXTRA_EXPECTED_MODEL_ID, SR_MODEL_ID)
            },
        )
    }

    /** Executes SR, then restores original generation even when SR fails. */
    suspend fun <T> withGenerationRestored(
        context: Context, modelId: String, backendType: String,
        width: Int, height: Int, operation: suspend () -> T,
    ): T {
        try {
            requestStart(context, SR_MODEL_ID, BackendService.BACKEND_TYPE_UPSCALER, 512, 512)
            awaitServing(SR_MODEL_ID)
            return operation()
        } finally {
            // A cancelled screen has no owner to receive the restored model.
            // Leave no native backend resident after navigation away.
            val ownerStillActive = currentCoroutineContext().isActive
            withContext(NonCancellable) {
                if (!ownerStillActive) {
                    requestStop(context)
                } else {
                    requestStart(context, modelId, backendType, width, height)
                    try {
                        awaitServing(modelId)
                    } catch (e: Exception) {
                        Log.e(TAG, "MNN generation restore failed", e)
                        throw e
                    }
                }
            }
        }
    }

    /** Standalone upscale page: start on demand and stop immediately on exit. */
    suspend fun <T> withStandalone(context: Context, operation: suspend () -> T): T {
        try {
            requestStart(context, SR_MODEL_ID, BackendService.BACKEND_TYPE_UPSCALER, 512, 512)
            awaitServing(SR_MODEL_ID)
            return operation()
        } finally {
            withContext(NonCancellable) { requestStop(context) }
        }
    }
}
