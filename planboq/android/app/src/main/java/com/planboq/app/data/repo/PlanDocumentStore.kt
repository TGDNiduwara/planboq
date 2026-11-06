package com.planboq.app.data.repo

import android.content.Context
import com.planboq.app.data.geometry.PlanDocument
import kotlinx.serialization.json.Json
import java.io.File

/** Persists full PlanDocuments as pretty JSON under files/projects/. */
class PlanDocumentStore(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun dir(): File = File(context.filesDir, "projects").apply { mkdirs() }

    private fun fileFor(id: String) = File(dir(), "$id.json")

    suspend fun save(doc: PlanDocument) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val tmp = File(dir(), "${doc.id}.json.tmp")
            tmp.writeText(json.encodeToString(PlanDocument.serializer(), doc))
            if (!tmp.renameTo(fileFor(doc.id))) {
                // rename can fail across some FS states; fall back to direct write
                fileFor(doc.id).writeText(json.encodeToString(PlanDocument.serializer(), doc))
                tmp.delete()
            }
        }
    }

    suspend fun load(id: String): PlanDocument? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val f = fileFor(id)
            if (!f.exists()) null else try {
                json.decodeFromString(PlanDocument.serializer(), f.readText())
            } catch (t: Throwable) {
                null
            }
        }

    suspend fun delete(id: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fileFor(id).delete()
        }
    }

    /** Duplicates a project: new id, copied geometry and review underlay. */
    suspend fun copyProject(id: String, newName: String): com.planboq.app.data.geometry.PlanDocument? {
        val doc = load(id) ?: return null
        val copy = doc.copy(
            id = com.planboq.app.data.geometry.PlanDocument.newId(),
            name = newName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            backgroundImagePath = null,
        )
        save(copy)
        val oldBg = backgroundFile(id)
        if (oldBg.exists()) {
            val newBg = backgroundFile(copy.id)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                oldBg.copyTo(newBg, overwrite = true)
            }
            val withBg = copy.copy(backgroundImagePath = newBg.name)
            save(withBg)
            return withBg
        }
        return copy
    }

    /** Exports directory (PDF/DXF output) - shared through FileProvider. */
    fun exportsDir(): File = File(context.filesDir, "exports").apply { mkdirs() }

    /** Corrected capture image used as the review underlay in the editor. */
    fun backgroundFile(id: String): File = File(dir(), "${id}_bg.png")

    suspend fun saveBackground(id: String, bitmap: android.graphics.Bitmap): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = backgroundFile(id)
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
            }
            file.name
        }

    suspend fun loadBackground(id: String, path: String?): android.graphics.Bitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = if (path != null) File(dir(), path) else backgroundFile(id)
            if (!file.exists()) null else android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        }
}
