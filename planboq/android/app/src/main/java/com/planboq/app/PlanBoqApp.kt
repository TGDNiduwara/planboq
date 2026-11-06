package com.planboq.app

import android.app.Application
import android.graphics.Bitmap
import com.planboq.app.boq.BoqExporter
import com.planboq.app.boq.BoqRepository
import com.planboq.app.boq.RateRepository
import com.planboq.app.data.billing.BillingManager
import com.planboq.app.data.repo.PlanDocumentStore
import com.planboq.app.data.repo.SettingsRepository
import com.planboq.app.data.repo.PlanBoqDatabase
import com.planboq.app.data.repo.SyncRepository
import com.planboq.app.net.CloudSyncClient
import com.planboq.app.domain.ExportPlanUseCase
import com.planboq.app.domain.ImageToPlanUseCase
import com.planboq.app.imaging.LinePostProcessor
import com.planboq.app.imaging.PageDetector
import com.planboq.app.imaging.PlanImageProcessor
import com.planboq.app.imaging.RoomDetector
import com.planboq.app.net.PlanBoqServer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PlanBoqApp : Application() {

    // Bitmap handed from the camera/photo picker to the corner-tap screen.
    // Photos are downscaled before being stored here to keep memory sane.
    var pendingPhotoBitmap: Bitmap? = null

    val database: PlanBoqDatabase by lazy { PlanBoqDatabase.get(this) }
    val documentStore: PlanDocumentStore by lazy { PlanDocumentStore(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val billingManager: BillingManager by lazy { BillingManager(this) }
    val serverClient: PlanBoqServer by lazy { PlanBoqServer() }
    val imageProcessor: PlanImageProcessor by lazy { PlanImageProcessor(LinePostProcessor()) }
    val imageToPlan: ImageToPlanUseCase by lazy { ImageToPlanUseCase(imageProcessor, serverClient) }
    val exporter: ExportPlanUseCase by lazy { ExportPlanUseCase(documentStore) }
    val pageDetector: PageDetector by lazy { PageDetector() }
    val roomDetector: RoomDetector by lazy { RoomDetector() }
    val boqRepository: BoqRepository by lazy { BoqRepository(this) }
    val rateRepository: RateRepository by lazy { RateRepository(this) }
    val boqExporter: BoqExporter by lazy { BoqExporter() }
    val syncRepository: SyncRepository by lazy {
        SyncRepository(this, documentStore, boqRepository, database.projectDao(), CloudSyncClient())
    }

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        billingManager.start()
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            com.planboq.app.imaging.RoomDetector.debugMaskPath =
                java.io.File(filesDir, "room_mask.png").absolutePath
        }
    }
}
