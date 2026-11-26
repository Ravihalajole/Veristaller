package dev.veristaller.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.veristaller.ui.screens.BundleScreen
import dev.veristaller.ui.screens.HistoryScreen
import dev.veristaller.ui.screens.HomeScreen
import dev.veristaller.ui.screens.PatchScreen
import dev.veristaller.ui.screens.ProgressScreen
import dev.veristaller.ui.screens.ResultScreen
import dev.veristaller.ui.screens.ReviewScreen
import dev.veristaller.ui.screens.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val BUNDLE = "bundle"
    const val PATCH = "patch"
    const val REVIEW = "review"
    const val PROGRESS = "progress"
    const val RESULT = "result"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
}

@Composable
fun VeristallerRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val job by vm.job.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Short finish pulse. Permissionless: a denied/unsupported vibrator is a no-op.
    val context = LocalContext.current
    val finished = job.outcome != null && !job.running
    LaunchedEffect(finished, job.jobId) {
        if (!finished || !settings.vibrate) return@LaunchedEffect
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(80)
        }
    }

    // Keep the screen awake only while privileged work is in flight.
    val view = LocalView.current
    DisposableEffect(job.running) {
        view.keepScreenOn = job.running
        onDispose { view.keepScreenOn = false }
    }

    // A job owns the UI once, not forever: leaving the progress screen to read
    // the log or open Home must not bounce the user straight back.
    var shownJob by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(job.running, job.jobId, job.outcome, route) {
        val runningId = job.jobId
        if (job.running && runningId != null && runningId != shownJob) {
            shownJob = runningId
            if (route != Routes.PROGRESS) {
                nav.navigate(Routes.PROGRESS) { launchSingleTop = true }
            }
        } else if (!job.running && job.outcome != null && route == Routes.PROGRESS) {
            nav.navigate(Routes.RESULT) {
                launchSingleTop = true
                popUpTo(Routes.PROGRESS) { inclusive = true }
            }
        }
    }

    fun goHome() {
        nav.navigate(Routes.HOME) {
            launchSingleTop = true
            popUpTo(Routes.HOME) { inclusive = false }
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onNewInstall = {
                    if (job.running) {
                        nav.navigate(Routes.PROGRESS) { launchSingleTop = true }
                    } else {
                        vm.clearSelection()
                        nav.navigate(Routes.BUNDLE)
                    }
                },
                onHistory = { nav.navigate(Routes.HISTORY) { launchSingleTop = true } },
                onSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenResult = {
                    if (job.outcome != null) {
                        nav.navigate(Routes.RESULT) { launchSingleTop = true }
                    } else {
                        nav.navigate(Routes.HISTORY) { launchSingleTop = true }
                    }
                },
            )
        }

        composable(Routes.BUNDLE) {
            BundleScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onNext = { nav.navigate(Routes.PATCH) },
            )
        }

        composable(Routes.PATCH) {
            PatchScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onNext = { nav.navigate(Routes.REVIEW) },
            )
        }

        composable(Routes.REVIEW) {
            ReviewScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onStart = {
                    vm.startJob()
                    nav.navigate(Routes.PROGRESS) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.PROGRESS) {
            ProgressScreen(vm = vm, onHome = ::goHome)
        }

        composable(Routes.RESULT) {
            ResultScreen(
                vm = vm,
                onHome = ::goHome,
                onTryAgain = {
                    if (vm.bundle.value != null && vm.patch.value != null) {
                        nav.navigate(Routes.REVIEW) {
                            launchSingleTop = true
                            popUpTo(Routes.HOME) { inclusive = false }
                        }
                    } else {
                        vm.clearSelection()
                        nav.navigate(Routes.BUNDLE) {
                            launchSingleTop = true
                            popUpTo(Routes.HOME) { inclusive = false }
                        }
                    }
                },
                onHistory = { nav.navigate(Routes.HISTORY) { launchSingleTop = true } },
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                vm = vm,
                onHome = ::goHome,
                onSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = vm,
                onHome = ::goHome,
                onHistory = { nav.navigate(Routes.HISTORY) { launchSingleTop = true } },
            )
        }
    }
}
