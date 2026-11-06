package com.planboq.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.planboq.app.ui.BoqScreen
import com.planboq.app.ui.BoqSetupScreen
import com.planboq.app.ui.CombinedBoqScreen
import com.planboq.app.ui.ExportScreen
import com.planboq.app.ui.ImportScreen
import com.planboq.app.ui.LocalApp
import com.planboq.app.ui.RateLibraryScreen
import com.planboq.app.ui.CameraScreen
import com.planboq.app.ui.CornerTapScreen
import com.planboq.app.ui.EditorScreen
import com.planboq.app.ui.PaywallScreen
import com.planboq.app.ui.ProjectsScreen
import com.planboq.app.ui.SettingsScreen
import com.planboq.app.ui.theme.PlanBoqTheme

object Routes {
    const val PROJECTS = "projects"
    const val IMPORT = "import"
    const val CAMERA = "camera"
    const val CORNER_TAP = "cornertap"
    const val EDITOR = "editor/{projectId}"
    const val EXPORT = "export/{projectId}"
    const val BOQ = "boq/{projectId}"
    const val RATES = "rates"
    const val COMBINED = "combinedBoq"
    const val SETUP = "setup/{projectId}"
    const val PAYWALL = "paywall"
    const val SETTINGS = "settings"

    fun editor(projectId: String) = "editor/$projectId"
    fun export(projectId: String) = "export/$projectId"
    fun boq(projectId: String) = "boq/$projectId"
    fun setup(projectId: String) = "setup/$projectId"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PlanBoqApp
        setContent {
            PlanBoqTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalApp provides app) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        val nav = rememberNavController()
                        NavHost(navController = nav, startDestination = Routes.PROJECTS) {
                            composable(Routes.PROJECTS) { ProjectsScreen(nav) }
                            composable(Routes.IMPORT) { ImportScreen(nav) }
                            composable(Routes.CAMERA) { CameraScreen(nav) }
                            composable(Routes.CORNER_TAP) { CornerTapScreen(nav) }
                            composable(Routes.EDITOR) { entry ->
                                EditorScreen(nav, entry.arguments?.getString("projectId").orEmpty())
                            }
                            composable(Routes.EXPORT) { entry ->
                                ExportScreen(nav, entry.arguments?.getString("projectId").orEmpty())
                            }
                            composable(Routes.BOQ) { entry ->
                                BoqScreen(nav, entry.arguments?.getString("projectId").orEmpty())
                            }
                            composable(Routes.RATES) { RateLibraryScreen(nav) }
                            composable(Routes.COMBINED) { CombinedBoqScreen(nav) }
                            composable(Routes.SETUP) { entry ->
                                BoqSetupScreen(nav, entry.arguments?.getString("projectId").orEmpty())
                            }
                            composable(Routes.PAYWALL) { PaywallScreen(nav) }
                            composable(Routes.SETTINGS) { SettingsScreen(nav) }
                        }
                    }
                }
            }
        }
    }
}
