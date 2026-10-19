package com.compositioncoach.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.compositioncoach.app.BuildConfig
import com.compositioncoach.app.R
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.ui.about.FeedbackComposer
import com.compositioncoach.app.ui.about.FeedbackText
import com.compositioncoach.app.ui.about.LicencesScreen
import com.compositioncoach.app.ui.camera.CameraScreen
import com.compositioncoach.app.ui.camera.CameraViewModel
import com.compositioncoach.app.ui.camera.GalleryMenuActions
import com.compositioncoach.app.ui.camera.GuidanceFormatter
import com.compositioncoach.app.ui.camera.ResourceChipStrings
import com.compositioncoach.app.ui.camera.ghostRectFor
import com.compositioncoach.app.ui.camera.openGallery
import com.compositioncoach.app.ui.permissions.CameraPermissionGate
import com.compositioncoach.app.ui.privacy.PrivacyScreen
import com.compositioncoach.app.ui.review.ReviewActions
import com.compositioncoach.app.ui.review.ReviewScreen
import com.compositioncoach.app.ui.settings.ExperienceActions
import com.compositioncoach.app.ui.settings.SettingsScreen
import com.compositioncoach.app.ui.settings.SettingsViewModel
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.ui.youreye.AdviceLabels
import com.compositioncoach.app.ui.youreye.JournalYourEyeSource
import com.compositioncoach.app.ui.youreye.YourEyeScreen
import com.compositioncoach.app.ui.youreye.YourEyeViewModel

/** The screens: camera (start), review, settings, privacy, "Your eye" and the licences list. */
object Routes {
    const val CAMERA = "camera"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
    const val YOUR_EYE = "your_eye"
    const val LICENCES = "licences"
}

/**
 * The navigation graph. Every screen pushed over the camera shares the camera's [CameraViewModel] (its back-stack
 * entry is always there): review decisions, the Enhancement row, and both "reset coaching" buttons go through it.
 * Settings come from one place, `SettingsRepository` (`review_card_mode` is read here for the review).
 */
@Composable
fun AppNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // The live chip's words come from strings.xml from here on (GuidanceFormatter defaults to the English table).
    val resources = context.resources
    remember(resources) { ResourceChipStrings(resources).also { GuidanceFormatter.strings = it } }

    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = CoachSettings())

    // The gallery thumbnail's fallback source and the last card (for "Use as guide" and "Send feedback"): the last
    // non-null entry the review store held, since Keep/Retake clear the store itself.
    var lastPhotoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var lastReview by remember { mutableStateOf<ReviewEntry?>(null) }
    LaunchedEffect(container) {
        container.reviewStore.current.collect { entry ->
            if (entry != null) {
                lastPhotoUri = entry.photoUri
                lastReview = entry
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.CAMERA) {
        composable(Routes.CAMERA) {
            val cameraViewModel: CameraViewModel = viewModel(factory = CameraViewModel.factory(container))
            val guideRect = lastReview?.let(::ghostRectFor)
            CameraPermissionGate {
                CameraScreen(
                    viewModel = cameraViewModel,
                    lastPhotoUri = lastPhotoUri,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onNavigateToReview = { navController.navigate(Routes.REVIEW) },
                    galleryMenu = GalleryMenuActions(
                        onOpenYourEye = { navController.navigate(Routes.YOUR_EYE) },
                        onUseAsGuide = guideRect?.let { rect -> { cameraViewModel.onUseAsGhost(rect) } },
                    ),
                )
            }
        }

        composable(Routes.REVIEW) { reviewEntry ->
            val entry by container.reviewStore.current.collectAsStateWithLifecycle()
            val cameraViewModel = cameraViewModelFrom(navController, reviewEntry, container)
            // Clearing the entry is all leaving needs to do: CameraViewModel deletes the entry's pre-level original
            // (ReviewEntry.originalPhotoUri, a cache copy) as soon as it leaves the store.
            fun leave(kept: Boolean) {
                cameraViewModel.onReviewDecision(kept)
                container.reviewStore.clear()
                navController.popBackStack()
            }
            ReviewScreen(
                entry = entry,
                captureRepository = container.captureRepository,
                actions = ReviewActions(
                    onKeep = { leave(kept = true) },
                    onRetakeComplete = { leave(kept = false) },
                    onNotHelpful = cameraViewModel::onNotHelpful,
                    onSaveCrop = cameraViewModel::onCropChosen,
                    onSavePortrait = cameraViewModel::onSavePortraitCopy,
                    onShootAgain = { rect ->
                        cameraViewModel.onUseAsGhost(rect)
                        leave(kept = true)
                    },
                ),
                cardMode = settings.reviewCardMode,
            )
        }

        composable(Routes.SETTINGS) { settingsEntry ->
            val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
            // The Enhancement row shows the persisted choice against what the *bound* lens offers, which only the
            // camera's view model knows; Settings is always pushed over the camera, so its entry is there to share.
            val cameraViewModel = cameraViewModelFrom(navController, settingsEntry, container)
            val cameraState by cameraViewModel.uiState.collectAsStateWithLifecycle()
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenPrivacyPolicy = { navController.navigate(Routes.PRIVACY) },
                availableEnhancements = cameraState.availableEnhancements,
                onEnhancementChange = cameraViewModel::onEnhancementSelected,
                actions = ExperienceActions(
                    onOpenYourEye = { navController.navigate(Routes.YOUR_EYE) },
                    onResetCoaching = cameraViewModel::onResetCoachingPreferences,
                    onOpenLicences = { navController.navigate(Routes.LICENCES) },
                    onSendFeedback = {
                        val body = FeedbackText.build(
                            version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            entry = lastReview,
                            noCardLine = context.getString(R.string.feedback_no_card),
                        )
                        FeedbackComposer.open(context, body)
                    },
                ),
            )
        }

        composable(Routes.YOUR_EYE) { yourEyeEntry ->
            val cameraViewModel = cameraViewModelFrom(navController, yourEyeEntry, container)
            val yourEyeSource = remember(cameraViewModel, resources) {
                JournalYourEyeSource(cameraViewModel.yourEye, AdviceLabels.from(resources), cameraViewModel::onResetCoachingPreferences)
            }
            val yourEyeViewModel: YourEyeViewModel = viewModel(factory = YourEyeViewModel.factory(yourEyeSource))
            val data by yourEyeViewModel.data.collectAsStateWithLifecycle()
            YourEyeScreen(
                data = data,
                onBack = { navController.popBackStack() },
                onReset = yourEyeViewModel::reset,
                onOpenPhoto = { uri -> openGallery(context, uri) },
            )
        }

        composable(Routes.PRIVACY) {
            PrivacyScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.LICENCES) {
            LicencesScreen(onBack = { navController.popBackStack() })
        }
    }
}

/** The camera's own view model, shared from its back-stack entry (review and settings are pushed over it). */
@Composable
private fun cameraViewModelFrom(navController: NavHostController, current: NavBackStackEntry, container: AppContainer): CameraViewModel {
    val cameraEntry = remember(current) { navController.getBackStackEntry(Routes.CAMERA) }
    return viewModel(viewModelStoreOwner = cameraEntry, factory = CameraViewModel.factory(container))
}
