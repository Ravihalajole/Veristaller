package dev.veristaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.veristaller.platform.VeristallerApp
import dev.veristaller.ui.AppViewModel
import dev.veristaller.ui.VeristallerRoot
import dev.veristaller.ui.theme.VeristallerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VeristallerApp).container
        setContent {
            val viewModel: AppViewModel = viewModel(factory = AppViewModel.factory(container))
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = settings.theme == "dark" ||
                (settings.theme != "light" && systemDark)

            // Re-probe whenever the app comes back to the foreground. The user
            // leaves for KernelSU/Magisk to grant root and returns, and the
            // Activity is not recreated, so without this the chip keeps showing
            // the pre-grant "no root" until it is tapped.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, viewModel) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(dark) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            VeristallerTheme(
                dynamicColor = settings.dynamicColor,
                theme = settings.theme,
            ) {
                VeristallerRoot(viewModel)
            }
        }
    }
}
