package com.mtajchert.carom.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
 * preferences are loaded so the theme never flashes. The activity is exported for the launcher and
 * for carom://setup and carom://pair links; a link is read once from the intent, then cleared, and
 * onboarding asks before it enrolls. Pasted or scanned codes are handled inside onboarding/pairing.
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

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.alreadyConnected.collect {
                    Toast.makeText(this@MainActivity, R.string.already_connected, Toast.LENGTH_LONG).show()
                }
            }
        }
        if (savedInstanceState == null) handleLink(intent)

        enableEdgeToEdge()

        setContent {
            val darkTheme = uiState.shouldUseDarkTheme(isSystemInDarkTheme())
            CaromTheme(darkTheme = darkTheme, dynamicColor = uiState.shouldUseDynamicColor()) {
                // The splash screen covers Loading; the start destination is chosen once loaded.
                (uiState as? MainActivityUiState.Success)?.let { CaromNavHost(needsOnboarding = it.needsOnboarding) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    /** Hands a carom:// link to the ViewModel once. Recents and recreation never replay it. */
    private fun handleLink(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val data = intent.data ?: return
        intent.data = null
        if (data.scheme == "carom") viewModel.onLink(data.toString())
    }
}
