package com.planboq.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the PlanBOQ processing server (Oracle ARM, default
 * https://api.corecivil.engineer). Used only for the photo pipeline; the device
 * pipeline always remains as fallback.
 */
class PlanBoqServer {

    @Serializable
    data class SegmentDto(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

    @Serializable
    data class ProcessResponse(
        val segments: List<SegmentDto> = emptyList(),
        val width: Int = 0,
        val height: Int = 0,
        val detector: String? = null,
        val error: String? = null,
    )

    @Serializable
    data class HealthResponse(
        val ok: Boolean = false,
        val service: String? = null,
        val auth: Boolean = false,
    )

    /** Result of the Settings -> "Test connection" probe. */
    data class ConnectionResult(
        val reachable: Boolean,
        /** true = key accepted, false = rejected, null = not tested/unknown */
        val keyOk: Boolean?,
        val detail: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Short-timeout client for user-facing probes (settings screen). */
    private val probeClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Uploads the CLEANED binary image (already perspective-corrected on the
     * device) so server and device results live in the same pixel space.
     * Returns detected segments in pixels, y-down, same as device LSD output.
     */
    fun detectLines(serverUrl: String, apiKey: String, pngBytes: ByteArray): List<SegmentDto> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "plan.png",
                pngBytes.toRequestBody("image/png".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("$serverUrl/api/v1/process/image")
            .header("X-API-Key", apiKey)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val code = response.code
                throw IOException(
                    if (code == 401 || code == 403) "API key rejected ($code)" else "Server error $code",
                )
            }
            val text = response.body?.string() ?: throw IOException("Empty server response")
            val dto = json.decodeFromString<ProcessResponse>(text)
            dto.error?.let { throw IOException("Server: $it") }
            return dto.segments
        }
    }

    /**
     * Two-step probe for the Settings screen:
     * 1. GET /api/v1/health - is the server reachable?
     * 2. POST /api/v1/export/dxf with an empty plan - does the API key pass auth?
     *    (uses an existing endpoint, so no server-side changes are needed)
     */
    fun checkConnection(serverUrl: String, apiKey: String): ConnectionResult {
        val base = serverUrl.trim().trimEnd('/')
        if (base.isBlank()) return ConnectionResult(false, null, "Server URL is empty")

        // --- 1. reachability ---
        val health: HealthResponse = try {
            val request = Request.Builder().url("$base/api/v1/health").get().build()
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ConnectionResult(false, null, "Health check failed: HTTP ${response.code}")
                }
                val text = response.body?.string().orEmpty()
                json.decodeFromString<HealthResponse>(text)
            }
        } catch (t: Throwable) {
            return ConnectionResult(false, null, "Unreachable: ${t.message ?: t.javaClass.simpleName}")
        }
        if (!health.ok) {
            return ConnectionResult(false, null, "Server responded, but it is not a PlanBOQ API")
        }

        if (apiKey.isBlank()) {
            return ConnectionResult(true, null, "Server reachable; no API key entered")
        }

        // --- 2. key check against an authenticated endpoint ---
        val keyOk: Boolean? = try {
            val payload = """{"name":"connection-test","segments":[],"polylines":[],"layers":[]}"""
            val request = Request.Builder()
                .url("$base/api/v1/export/dxf")
                .header("X-API-Key", apiKey)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            probeClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> true
                    response.code == 401 || response.code == 403 -> false
                    else -> null
                }
            }
        } catch (t: Throwable) {
            return ConnectionResult(true, null, "Reachable, key check failed: ${t.message}")
        }

        return ConnectionResult(
            reachable = true,
            keyOk = keyOk,
            detail = when (keyOk) {
                true -> "Server reachable; API key OK"
                false -> "Server reachable; API key rejected"
                null -> "Server reachable; unexpected key-check response"
            },
        )
    }
}
