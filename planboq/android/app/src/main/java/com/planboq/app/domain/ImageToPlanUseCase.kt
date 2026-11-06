package com.planboq.app.domain

import android.graphics.Bitmap
import com.planboq.app.data.geometry.PlanDocument
import com.planboq.app.data.geometry.Segment
import com.planboq.app.data.geometry.SourceType
import com.planboq.app.data.geometry.Vec2
import com.planboq.app.imaging.PlanImageProcessor
import com.planboq.app.net.PlanBoqServer
import java.io.ByteArrayOutputStream

/**
 * Runs the photo pipeline: perspective-correct + clean on-device, then detect
 * lines on the server when one is configured, falling back to on-device
 * detection (and to server on device failure - whichever path works).
 */
class ImageToPlanUseCase(
    private val processor: PlanImageProcessor,
    private val server: PlanBoqServer,
) {

    sealed class Outcome {
        data class Success(
            val document: PlanDocument,
            val correctedBitmap: Bitmap,
            val detector: String,
            val viaServer: Boolean,
            /** Set when a server was configured but failed; null on clean paths. */
            val serverError: String? = null,
        ) : Outcome()

        data class Failed(val message: String) : Outcome()
    }

    suspend fun run(
        bitmap: Bitmap,
        corners: List<Vec2>,
        fileName: String,
        serverUrl: String?,
        apiKey: String?,
    ): Outcome {
        if (!processor.ensureOpenCv()) return Outcome.Failed("OpenCV could not be initialised")

        // Build the document FIRST so segments reference the same layer IDs the
        // document owns (two defaultLayers() calls mint different UUIDs).
        val baseDoc = PlanDocument(
            name = fileName,
            sourceType = SourceType.PHOTO,
            tracedFromImage = true,
        )
        val defaultLayer = PlanDocument.defaultLayerId(baseDoc.layers)
        val deviceResult = try {
            processor.process(bitmap, corners, defaultLayer)
        } catch (t: Throwable) {
            return Outcome.Failed("Processing failed: ${t.message ?: t.javaClass.simpleName}")
        }
        val withSegments = baseDoc.copyWithTouch(segments = deviceResult.segments)

        // Try the server when configured; keep device result as fallback.
        var serverError: String? = null
        if (!serverUrl.isNullOrBlank() && !apiKey.isNullOrBlank()) {
            try {
                val png = encodePng(deviceResult.correctedBitmap)
                val remote = server.detectLines(serverUrl, apiKey, png)
                val segs = remote.map {
                    Segment(
                        a = Vec2(it.x1, it.y1),
                        b = Vec2(it.x2, it.y2),
                        layerId = defaultLayer,
                    )
                }
                if (segs.isNotEmpty()) {
                    return Outcome.Success(
                        document = withSegments.copyWithTouch(segments = segs),
                        correctedBitmap = deviceResult.correctedBitmap,
                        detector = remote.firstOrNull()?.let { "server" } ?: deviceResult.detector,
                        viaServer = true,
                    )
                }
            } catch (t: Throwable) {
                // keep the reason so the UI can say processing fell back to the device
                serverError = t.message ?: t.javaClass.simpleName
            }
        }

        return if (deviceResult.segments.isEmpty()) {
            Outcome.Failed("No lines detected. Try better lighting, or hold the camera square to the plan.")
        } else {
            Outcome.Success(
                document = withSegments,
                correctedBitmap = deviceResult.correctedBitmap,
                detector = deviceResult.detector,
                viaServer = false,
                serverError = serverError,
            )
        }
    }

    private fun encodePng(bmp: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }
}
