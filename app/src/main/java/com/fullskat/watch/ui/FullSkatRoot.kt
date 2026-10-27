package com.fullskat.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.fullskat.watch.AppContainer
import com.fullskat.watch.LaunchRequest
import com.fullskat.watch.ui.chat.ChatScreen
import com.fullskat.watch.ui.dialogs.DialogsScreen
import com.fullskat.watch.ui.pair.PairScreen
import com.fullskat.watch.ui.settings.EffortScreen
import com.fullskat.watch.ui.settings.ModelScreen
import com.fullskat.watch.ui.settings.SettingsScreen
import com.fullskat.watch.ui.theme.FullSkatTheme
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val DIALOGS = "dialogs"
    const val PAIR = "pair"
    const val SETTINGS = "settings"
    const val MODEL = "model"
    const val EFFORT = "effort"
    const val CHAT = "chat/{dialogId}?listen={listen}"
    fun chat(dialogId: String, listen: Boolean) = "chat/$dialogId?listen=$listen"
}

@Composable
fun FullSkatRoot(
    container: AppContainer,
    launchRequests: StateFlow<LaunchRequest?>,
    onLaunchRequestConsumed: () -> Unit,
) {
    val vm: AppViewModel = viewModel(factory = AppViewModel.Factory(container))
    val settings by vm.settings.collectAsStateWithLifecycle()
    val request by launchRequests.collectAsStateWithLifecycle()
    val cfg = settings ?: return // settings load in a few ms; showing nothing avoids a flash of the wrong screen

    FullSkatTheme {
        val nav = rememberSwipeDismissableNavController()

        LaunchedEffect(request, cfg.paired) {
            val r = request ?: return@LaunchedEffect
            if (r == LaunchRequest.NEW_DIALOG_AND_LISTEN) {
                if (cfg.paired) {
                    val id = vm.newDialog()
                    nav.navigate(Routes.chat(id, listen = true)) { popUpTo(Routes.DIALOGS) }
                } else {
                    nav.navigate(Routes.PAIR)
                }
            }
            onLaunchRequestConsumed()
        }

        AppScaffold {
            SwipeDismissableNavHost(
                navController = nav,
                startDestination = if (cfg.paired) Routes.DIALOGS else Routes.PAIR,
            ) {
                composable(Routes.DIALOGS) {
                    DialogsScreen(
                        vm = vm,
                        onOpen = { id -> nav.navigate(Routes.chat(id, listen = false)) },
                        onNew = { id -> nav.navigate(Routes.chat(id, listen = true)) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(
                    Routes.CHAT,
                    arguments = listOf(
                        navArgument("dialogId") { type = NavType.StringType },
                        navArgument("listen") { type = NavType.BoolType; defaultValue = false },
                    ),
                ) { entry ->
                    val id = entry.arguments?.getString("dialogId") ?: return@composable
                    val listen = entry.arguments?.getBoolean("listen") ?: false
                    ChatScreen(vm = vm, dialogId = id, autoListen = listen)
                }
                composable(Routes.PAIR) {
                    PairScreen(
                        vm = vm,
                        onPaired = {
                            nav.navigate(Routes.DIALOGS) { popUpTo(0) { inclusive = true } }
                        },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        vm = vm,
                        onModel = { nav.navigate(Routes.MODEL) },
                        onEffort = { nav.navigate(Routes.EFFORT) },
                        onRepair = { nav.navigate(Routes.PAIR) },
                        onUnpaired = { nav.navigate(Routes.PAIR) { popUpTo(0) { inclusive = true } } },
                    )
                }
                composable(Routes.MODEL) {
                    ModelScreen(vm = vm, onDone = { nav.popBackStack() })
                }
                composable(Routes.EFFORT) {
                    EffortScreen(vm = vm, onDone = { nav.popBackStack() })
                }
            }
        }
    }
}
