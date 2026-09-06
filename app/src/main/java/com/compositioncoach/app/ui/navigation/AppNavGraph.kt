package com.compositioncoach.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.ui.camera.CameraScreen
import com.compositioncoach.app.ui.camera.CameraViewModel
import com.compositioncoach.app.ui.permissions.CameraPermissionGate
import com.compositioncoach.app.ui.privacy.PrivacyScreen
import com.compositioncoach.app.ui.review.ReviewScreen
import com.compositioncoach.app.ui.settings.SettingsScreen
import com.compositioncoach.app.ui.settings.SettingsViewModel

/** The four screens the app has: camera (start), the post-capture review, settings, and the privacy policy. */
object Routes {
    const val CAMERA = "camera"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
}

@Composable
fun AppNavGraph(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.CAMERA) {
        composable(Routes.CAMERA) {
            val cameraViewModel: CameraViewModel = viewModel(factory = CameraViewModel.factory(container))
            CameraPermissionGate {
                CameraScreen(
                    viewModel = cameraViewModel,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onNavigateToReview = { navController.navigate(Routes.REVIEW) },
                )
            }
        }

        composable(Routes.REVIEW) {
            val entry by container.reviewStore.current.collectAsStateWithLifecycle()
            ReviewScreen(
                entry = entry,
                captureRepository = container.captureRepository,
                onKeep = {
                    container.reviewStore.clear()
                    navController.popBackStack()
                },
                onRetakeComplete = {
                    container.reviewStore.clear()
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenPrivacyPolicy = { navController.navigate(Routes.PRIVACY) },
            )
        }

        composable(Routes.PRIVACY) {
            PrivacyScreen(onBack = { navController.popBackStack() })
        }
    }
}
