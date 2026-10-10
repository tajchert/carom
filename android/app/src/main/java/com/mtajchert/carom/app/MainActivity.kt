package com.mtajchert.carom.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.feature.tasks.api.TaskListRoute
import com.mtajchert.carom.app.feature.tasks.api.TasksDeepLinks
import com.mtajchert.carom.app.navigation.CaromNavHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// =============================================================================
// MAIN ACTIVITY
// =============================================================================
// Single Activity architecture - all screens are Compose destinations.
//
// ## Key Patterns Demonstrated
//
// 1. **Edge-to-Edge**: Content draws behind system bars
// 2. **Splash Screen**: Shows while preferences load
// 3. **Theme from Preferences**: Dark/light/system follows user choice
// 4. **Hilt Integration**: @AndroidEntryPoint for dependency injection
// 5. **Deep links**: incoming URIs are validated and turned into a back stack
//
// ## Splash Screen Condition
//
// We keep the splash screen visible until preferences are loaded.
// This prevents a flash of wrong theme on app start.
//
// ## Deep Link Handling
//
// A deep link is an Intent from an untrusted sender, so the same rules apply on
// every path into the Activity:
//
// 1. Only the URI is trusted as *input*, never as a command. Nothing here reads
//    an Intent extra to decide where to go, and nothing forwards a nested
//    Intent from the extras - that is the intent-redirection vulnerability.
// 2. The resolver owns validation. It returns a back stack only for URIs this
//    app explicitly claims; everything else returns null and the app opens
//    normally rather than guessing.
// 3. `onNewIntent` repeats the same validation. `launchMode="singleTop"` means a
//    link arriving while the app is running never passes through `onCreate`, so
//    validating only there would leave the warm path unchecked.
// 4. `setIntent(intent)` is called before processing so `getIntent()` and any
//    later recreation see the Intent that is actually being handled.
// =============================================================================

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainActivityViewModel by viewModels()

    /**
     * Back stack requested by a deep link that arrived while the app was
     * already running. Compose observes it and installs it, then clears it.
     */
    private var pendingDeepLink: List<NavKey>? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate()
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)

        // Cold start: resolve the launching Intent once. An unrecognised or
        // absent link falls back to the normal start destination.
        val initialBackStack = resolveDeepLink(intent) ?: listOf(TaskListRoute)

        // Track UI state for splash screen condition
        var uiState: MainActivityUiState by mutableStateOf(MainActivityUiState.Loading)

        // Observe UI state
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState
                    .onEach { uiState = it }
                    .collect {}
            }
        }

        // Keep splash screen while loading preferences
        splashScreen.setKeepOnScreenCondition {
            when (uiState) {
                MainActivityUiState.Loading -> true
                is MainActivityUiState.Success -> false
            }
        }

        // Enable edge-to-edge display
        enableEdgeToEdge()

        setContent {
            val darkTheme = shouldUseDarkTheme(uiState)

            CaromTheme(darkTheme = darkTheme, dynamicColor = uiState.shouldUseDynamicColor()) {
                CaromNavHost(
                    initialBackStack = initialBackStack,
                    pendingDeepLink = pendingDeepLink,
                    onDeepLinkHandled = { pendingDeepLink = null },
                )
            }
        }
    }
    /**
     * Warm start: a deep link delivered to the existing task instance.
     *
     * Applies exactly the same validation as the cold path. An unrecognised
     * link is logged and ignored, leaving the user where they already were,
     * because silently navigating somewhere on an unmatched URI is how an
     * attacker-supplied link reaches a screen it was never meant to reach.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Keep getIntent() consistent with what is being handled.
        setIntent(intent)

        val backStack = resolveDeepLink(intent)
        if (backStack != null) {
            pendingDeepLink = backStack
        } else {
            Log.w(TAG, "Ignoring an Intent that matched no known deep link")
        }
    }

    /**
     * Resolves an Intent to a back stack, or null when no feature claims it.
     *
     * Deep links from every feature are composed here. A second feature adds a
     * line, and the app module still needs no knowledge of any screen.
     */
    private fun resolveDeepLink(intent: Intent?): List<NavKey>? {
        val uri = intent?.data ?: return null
        return TasksDeepLinks.resolve(uri)
        // ?: SettingsDeepLinks.resolve(uri)
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

/**
 * Determine if dark theme should be used.
 *
 * @param uiState The current UI state from ViewModel
 * @return true if dark theme should be applied
 */
@Composable
private fun shouldUseDarkTheme(uiState: MainActivityUiState): Boolean {
    val isSystemDark = isSystemInDarkTheme()
    return uiState.shouldUseDarkTheme(isSystemDark)
}
