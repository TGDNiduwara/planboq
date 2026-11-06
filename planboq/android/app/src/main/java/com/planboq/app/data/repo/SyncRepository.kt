package com.planboq.app.data.repo

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.planboq.app.boq.BoqRepository
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.net.CloudSyncClient
import com.planboq.app.net.SyncProjectPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Device-to-device project backup through the user's own processing server.
 * The sync code is a user-held secret; projects are matched by id and the
 * newer updatedAt wins on restore.
 */
class SyncRepository(
    private val context: Context,
    private val store: PlanDocumentStore,
    private val boqRepository: BoqRepository,
    private val dao: ProjectDao,
    private val client: CloudSyncClient,
) {

    data class Result(val ok: Boolean, val message: String)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun backup(serverUrl: String, apiKey: String, syncId: String): Result =
        withContext(Dispatchers.IO) {
            try {
                if (serverUrl.isBlank() || apiKey.isBlank()) {
                    return@withContext Result(false, "Set the server URL and API key first")
                }
                if (syncId.length < 12) {
                    return@withContext Result(false, "Sync code must be at least 12 characters")
                }
                val projects = dao.all()
                if (projects.isEmpty()) {
                    return@withContext Result(false, "No projects to back up")
                }
                val payloads = projects.mapNotNull { entity ->
                    val doc = store.load(entity.id) ?: return@mapNotNull null
                    val boqText = boqRepository.loadRaw(entity.id) ?: ""
                    val background = readBackground(entity.id)
                    SyncProjectPayload(
                        id = doc.id,
                        name = doc.name,
                        updatedAt = max(entity.updatedAt, doc.updatedAt),
                        planJson = json.encodeToString(PlanDocument.serializer(), doc),
                        boqJson = boqText,
                        backgroundBase64 = background,
                    )
                }
                val count = client.push(serverUrl, apiKey, syncId, payloads)
                Result(true, "Backed up $count project(s)")
            } catch (t: Throwable) {
                Result(false, "Backup failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }

    suspend fun restore(serverUrl: String, apiKey: String, syncId: String): Result =
        withContext(Dispatchers.IO) {
            try {
                if (serverUrl.isBlank() || apiKey.isBlank()) {
                    return@withContext Result(false, "Set the server URL and API key first")
                }
                if (syncId.length < 12) {
                    return@withContext Result(false, "Sync code must be at least 12 characters")
                }
                val remote = client.list(serverUrl, apiKey, syncId)
                var restored = 0
                var skipped = 0
                for (meta in remote) {
                    val local = store.load(meta.id)
                    if (local != null && local.updatedAt >= meta.updatedAt) {
                        skipped++
                        continue
                    }
                    val payload = client.pull(serverUrl, apiKey, syncId, meta.id)
                    if (payload.planJson.isBlank()) continue
                    val doc = json.decodeFromString(PlanDocument.serializer(), payload.planJson)
                    var saved = doc
                    payload.backgroundBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
                        val bytes = Base64.decode(b64, Base64.NO_WRAP)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            val path = store.saveBackground(doc.id, bitmap)
                            saved = doc.copy(backgroundImagePath = path)
                        }
                    }
                    store.save(saved)
                    if (payload.boqJson.isNotBlank()) {
                        boqRepository.saveRaw(saved.id, payload.boqJson)
                    }
                    dao.upsert(
                        ProjectEntity(
                            id = saved.id,
                            name = saved.name,
                            sourceType = saved.sourceType.name,
                            createdAt = saved.createdAt,
                            updatedAt = saved.updatedAt,
                        ),
                    )
                    restored++
                }
                Result(true, "Restored $restored project(s), $skipped already up to date")
            } catch (t: Throwable) {
                Result(false, "Restore failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }

    private fun readBackground(projectId: String): String? {
        val file = store.backgroundFile(projectId)
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
