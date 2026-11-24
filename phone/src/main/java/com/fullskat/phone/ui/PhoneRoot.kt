package com.fullskat.phone.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fullskat.core.net.AttachmentRef
import com.fullskat.core.ui.rich.LocalFigureOpener
import com.fullskat.core.ui.theme.Ember
import com.fullskat.phone.PhoneContainer
import com.fullskat.phone.R
import com.fullskat.phone.ui.chat.ChatScreen
import com.fullskat.phone.ui.chat.ChatViewModel
import com.fullskat.phone.ui.chat.ImageViewer
import com.fullskat.phone.ui.dialogs.DialogsScreen
import com.fullskat.phone.ui.dialogs.DialogsViewModel
import com.fullskat.phone.ui.figure.FigureViewer
import com.fullskat.phone.ui.onboarding.OnboardingScreen
import com.fullskat.phone.ui.onboarding.OnboardingViewModel
import com.fullskat.phone.ui.settings.DevicesScreen
import com.fullskat.phone.ui.settings.DevicesViewModel
import com.fullskat.phone.ui.settings.LinkViewModel
import com.fullskat.phone.ui.settings.LinkWatchScreen
import com.fullskat.phone.ui.settings.ModelScreen
import com.fullskat.phone.ui.settings.SettingsScreen
import com.fullskat.phone.ui.settings.SettingsViewModel
import com.fullskat.phone.ui.theme.PhoneTheme
import kotlinx.coroutines.flow.filterNotNull

private object Routes {
    const val ONBOARDING = "onboarding"
    const val DIALOGS = "dialogs"
    const val CHAT = "chat/{id}"
    const val SETTINGS = "settings"
    const val MODEL = "settings/model"
    const val LINK = "settings/link"
    const val DEVICES = "settings/devices"
    const val FIGURE = "figure"
    const val IMAGE = "image/{id}?w={w}&h={h}"

    fun chat(id: String) = "chat/$id"
    fun image(ref: AttachmentRef) = "image/${ref.id}?w=${ref.width}&h=${ref.height}"
}

/** The app: theme and renderer locals, navigation, and the way back to onboarding when the account goes away. */
@Composable
fun PhoneRoot(app: PhoneContainer) {
    PhoneTheme(app.core.attachments) {
        val session by app.core.session.state.collectAsStateWithLifecycle(initialValue = null)
        val s = session
        if (s == null) {
            Box(Modifier.fillMaxSize().background(Ember.background)) // the session loads in a moment
            return@PhoneTheme
        }
        val start = rememberSaveable { if (s.signedIn) Routes.DIALOGS else Routes.ONBOARDING }
        val nav = rememberNavController()
        var figureJson by rememberSaveable { mutableStateOf<String?>(null) }

        // Signed out here, or unlinked from another device (sync.onSignedOut, or the session simply lost its token).
        LaunchedEffect(nav) { app.signedOut.collect { nav.toOnboarding() } }
        LaunchedEffect(s.signedIn) { if (!s.signedIn) nav.toOnboarding() }
        // Shared into the app (also on a cold start): open the dialog made for it once signed in.
        LaunchedEffect(nav, s.signedIn) {
            if (s.signedIn) {
                app.pendingShare.filterNotNull().collect { share ->
                    if (nav.currentBackStackEntry?.arguments?.getString("id") != share.dialogId) nav.navigate(Routes.chat(share.dialogId))
                }
            }
        }

        val openFigure: (String) -> Unit = { json ->
            figureJson = json
            nav.navigate(Routes.FIGURE)
        }
        CompositionLocalProvider(LocalFigureOpener provides openFigure) {
            NavHost(nav, startDestination = start, modifier = Modifier.fillMaxSize().background(Ember.background)) {
                composable(Routes.ONBOARDING) {
                    val context = LocalContext.current
                    OnboardingScreen(viewModel { OnboardingViewModel(app) }) { firstSetup ->
                        if (firstSetup) Toast.makeText(context, R.string.onboarding_first_done, Toast.LENGTH_LONG).show()
                        nav.navigate(Routes.DIALOGS) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                    }
                }
                composable(Routes.DIALOGS) {
                    DialogsScreen(
                        viewModel { DialogsViewModel(app) },
                        onOpen = { nav.navigate(Routes.chat(it)) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.CHAT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    ChatScreen(
                        viewModel { ChatViewModel(app, id) },
                        onBack = { nav.popBackStack() },
                        onImage = { nav.navigate(Routes.image(it)) },
                        onModel = { nav.navigate(Routes.MODEL) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        viewModel { SettingsViewModel(app) },
                        onBack = { nav.popBackStack() },
                        onModel = { nav.navigate(Routes.MODEL) },
                        onLinkWatch = { nav.navigate(Routes.LINK) },
                        onDevices = { nav.navigate(Routes.DEVICES) },
                    )
                }
                composable(Routes.MODEL) { ModelScreen(viewModel { SettingsViewModel(app) }, onBack = { nav.popBackStack() }) }
                composable(Routes.LINK) { LinkWatchScreen(viewModel { LinkViewModel(app) }, onBack = { nav.popBackStack() }) }
                composable(Routes.DEVICES) {
                    DevicesScreen(
                        viewModel { DevicesViewModel(app) },
                        onBack = { nav.popBackStack() },
                        onLinkWatch = { nav.navigate(Routes.LINK) },
                    )
                }
                composable(Routes.FIGURE) { FigureViewer(figureJson.orEmpty(), onClose = { nav.popBackStack() }) }
                composable(
                    Routes.IMAGE,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("w") { type = NavType.IntType; defaultValue = 0 },
                        navArgument("h") { type = NavType.IntType; defaultValue = 0 },
                    ),
                ) { entry ->
                    val args = entry.arguments
                    val ref = AttachmentRef(args?.getString("id").orEmpty(), width = args?.getInt("w") ?: 0, height = args?.getInt("h") ?: 0)
                    ImageViewer(ref, onClose = { nav.popBackStack() })
                }
            }
        }
    }
}

private fun NavHostController.toOnboarding() {
    if (currentDestination?.route == Routes.ONBOARDING) return
    navigate(Routes.ONBOARDING) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
