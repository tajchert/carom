package com.mtajchert.carom.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.navigation.CaromNavHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Single activity; every screen is a Compose destination. The splash screen stays until
 * preferences are loaded so the theme never flashes. Incoming carom:// URIs (pasted or scanned)
 * are handled inside onboarding/pairing, not as intent filters: only the launcher is exported.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainActivityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        var uiState: MainActivityUiState by mutableStateOf(MainActivityUiState.Loading)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState = it }
            }
        }
        splashScreen.setKeepOnScreenCondition { uiState is MainActivityUiState.Loading }

        enableEdgeToEdge()

        setContent {
            val darkTheme = uiState.shouldUseDarkTheme(isSystemInDarkTheme())
            CaromTheme(darkTheme = darkTheme, dynamicColor = uiState.shouldUseDynamicColor()) {
                // The splash screen covers Loading; the start destination is chosen once loaded.
                (uiState as? MainActivityUiState.Success)?.let { CaromNavHost(needsOnboarding = it.needsOnboarding) }
            }
        }
    }
}
