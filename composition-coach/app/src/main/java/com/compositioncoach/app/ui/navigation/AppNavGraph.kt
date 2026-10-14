package com.compositioncoach.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

    // The gallery thumbnail's source: CameraUiState/CameraViewModel don't carry a `lastPhotoUri` field
    // (that hook doesn't exist yet — see app/README.md and the final report), so this is wired here
    // instead, straight off `AppContainer.reviewStore`'s last entry. `reviewStore.current` itself goes
    // back to null once Keep/Retake clears it (see below), so the *last non-null* value seen is kept
    // separately — that's the "optimistic" part: it stays correct across every capture without needing a
    // ViewModel change, at the cost of not knowing about a photo taken in a previous process (cold start).
    var lastPhotoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    LaunchedEffect(container) {
        container.reviewStore.current.collect { entry -> if (entry != null) lastPhotoUri = entry.photoUri }
    }

    NavHost(navController = navController, startDestination = Routes.CAMERA) {
        composable(Routes.CAMERA) {
            val cameraViewModel: CameraViewModel = viewModel(factory = CameraViewModel.factory(container))
            CameraPermissionGate {
                CameraScreen(
                    viewModel = cameraViewModel,
                    lastPhotoUri = lastPhotoUri,
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
