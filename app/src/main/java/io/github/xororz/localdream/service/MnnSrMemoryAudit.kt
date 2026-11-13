package io.github.xororz.localdream.service

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Metadata-only resident-memory diagnostics for MNN SD -> SR handoff.
 *
 * VmRSS is not anonymous PSS, and summing both process RSS values double-counts
 * shared pages; this is a comparative trace, NOT a GPU allocation measurement.
 * Android may deny /proc/<pid>/status; missing values stay null, never zero.
 * Does not store images, prompts, seeds or model credentials.
 */
class MnnSrMemoryAudit(context: Context) {
    private val directory = File(context.filesDir, "diagnostics")
    private val output = File(directory, "sr_memory.jsonl")
    private val peakAppRssKb = AtomicLong(0L)
    private val peakNativeRssKb = AtomicLong(0L)

    private fun readRssKb(pid: Long?): Long? {
        if (pid == null || pid < 1) return null
        return runCatching {
            File("/proc/$pid/status").useLines { seq ->
                seq.firstOrNull { it.startsWith("VmRSS:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.substringBefore(' ')
                    ?.trim()
                    ?.toLongOrNull()
            }
        }.getOrNull()
    }

    suspend fun sample(stage: String, persist: Boolean = false) {
        withContext(Dispatchers.IO) {
            val appRss = readRssKb(Process.myPid().toLong())
            val nativePid = BackendService.nativePid.value
            val childRss = readRssKb(nativePid)
            if (appRss != null) peakAppRssKb.updateAndGet { maxOf(it, appRss) }
            if (childRss != null) peakNativeRssKb.updateAndGet { maxOf(it, childRss) }
            if (!persist) return@withContext
            try {
                directory.mkdirs()
                val event = JSONObject().apply {
                    put("at_ms", System.currentTimeMillis())
                    put("stage", stage)
                    put("app_rss_kib", appRss ?: JSONObject.NULL)
                    put("native_rss_kib", childRss ?: JSONObject.NULL)
                    put("native_pid", nativePid ?: JSONObject.NULL)
                    put("observed_peak_app_rss_kib", peakAppRssKb.get())
                    put("observed_peak_native_rss_kib", peakNativeRssKb.get())
                    put("rss_is_not_pss_or_gpu_memory", true)
                }
                output.appendText(event.toString() + "\n")
                Log.i("MnnSrMemoryAudit", event.toString())
            } catch (e: Exception) {
                Log.w("MnnSrMemoryAudit", "Could not persist SR RSS metrics", e)
            }
        }
    }
}
