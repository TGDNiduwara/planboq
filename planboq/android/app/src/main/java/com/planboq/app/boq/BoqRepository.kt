package com.planboq.app.boq

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Persists BoqDocuments as JSON under files/boq/. */
class BoqRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun dir(): File = File(context.filesDir, "boq").apply { mkdirs() }

    private fun fileFor(projectId: String) = File(dir(), "$projectId.json")

    suspend fun load(projectId: String): BoqDocument? = withContext(Dispatchers.IO) {
        val f = fileFor(projectId)
        if (!f.exists()) null else try {
            json.decodeFromString(BoqDocument.serializer(), f.readText())
        } catch (t: Throwable) {
            null
        }
    }

    suspend fun save(doc: BoqDocument) = withContext(Dispatchers.IO) {
        fileFor(doc.projectId).writeText(json.encodeToString(BoqDocument.serializer(), doc))
    }

    /** Raw JSON access for cloud sync. */
    suspend fun loadRaw(projectId: String): String? = withContext(Dispatchers.IO) {
        val f = fileFor(projectId)
        if (f.exists()) f.readText() else null
    }

    suspend fun saveRaw(projectId: String, text: String) = withContext(Dispatchers.IO) {
        fileFor(projectId).writeText(text)
    }

    suspend fun delete(projectId: String) = withContext(Dispatchers.IO) {
        fileFor(projectId).delete()
    }
}
