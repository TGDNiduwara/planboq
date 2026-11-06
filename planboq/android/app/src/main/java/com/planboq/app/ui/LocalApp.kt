package com.planboq.app.ui

import androidx.compose.runtime.compositionLocalOf
import com.planboq.app.PlanBoqApp

/** Access the application singletons from composables. */
val LocalApp = compositionLocalOf<PlanBoqApp> { error("PlanBoqApp not provided") }
