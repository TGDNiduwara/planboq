package com.planboq.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class SyncProjectPayload(
    val id: String,
    val name: String = "",
    val updatedAt: Long = 0,
    val planJson: String = "",
    val boqJson: String = "",
    val backgroundBase64: String? = null,
)

@Serializable
data class SyncPushRequest(val syncId: String, val projects: List<SyncProjectPayload>)

@Serializable
data class SyncProjectMeta(val id: String, val name: String = "", val updatedAt: Long = 0)

/** Client for the server's device-to-device sync endpoints. */
class CloudSyncClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private fun base(url: String) = url.trim().trimEnd('/')

    fun push(serverUrl: String, apiKey: String, syncId: String, projects: List<SyncProjectPayload>): Int {
        val body = json.encodeToString(SyncPushRequest.serializer(), SyncPushRequest(syncId, projects))
            .toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url("${base(serverUrl)}/api/v1/sync/push")
            .header("X-API-Key", apiKey)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Sync push failed (${response.code})")
            val text = response.body?.string().orEmpty()
            return try {
                val node = json.parseToJsonElement(text) as JsonObject
                (node["written"] as? JsonPrimitive)?.content?.toIntOrNull() ?: projects.size
            } catch (t: Throwable) {
                projects.size
            }
        }
    }

    fun list(serverUrl: String, apiKey: String, syncId: String): List<SyncProjectMeta> {
        val request = Request.Builder()
            .url("${base(serverUrl)}/api/v1/sync/list?sync_id=${java.net.URLEncoder.encode(syncId, "UTF-8")}")
            .header("X-API-Key", apiKey)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Sync list failed (${response.code})")
            val text = response.body?.string() ?: return emptyList()
            return json.decodeFromString<List<SyncProjectMeta>>(text)
        }
    }

    fun pull(serverUrl: String, apiKey: String, syncId: String, projectId: String): SyncProjectPayload {
        val request = Request.Builder()
            .url(
                "${base(serverUrl)}/api/v1/sync/project/${java.net.URLEncoder.encode(projectId, "UTF-8")}" +
                    "?sync_id=${java.net.URLEncoder.encode(syncId, "UTF-8")}",
            )
            .header("X-API-Key", apiKey)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Sync pull failed (${response.code})")
            val text = response.body?.string() ?: throw IOException("Empty sync response")
            return json.decodeFromString<SyncProjectPayload>(text)
        }
    }
}
