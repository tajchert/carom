package com.mtajchert.carom.app.samples.tasks

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.carom.app.feature.tasks.impl.preferences.PreferencesViewModel
import com.mtajchert.carom.app.core.model.DarkThemeConfig
import com.mtajchert.carom.app.core.model.ThemeBrand
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.feature.tasks.api.TaskListRoute
import com.mtajchert.carom.app.feature.tasks.impl.navigation.tasksEntries
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TasksCaromActivity : ComponentActivity() {
    private val preferencesViewModel: PreferencesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by preferencesViewModel.uiState.collectAsStateWithLifecycle()
            val preferences = state.preferences
            val darkTheme = when (preferences.darkThemeConfig) {
                DarkThemeConfig.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                DarkThemeConfig.LIGHT -> false
                DarkThemeConfig.DARK -> true
            }
            CaromTheme(darkTheme = darkTheme, dynamicColor = preferences.themeBrand == ThemeBrand.DYNAMIC) {
                TasksFeatureHost()
            }
        }
    }
}

/**
 * Minimal navigation shell for Tasks. It deliberately composes the feature's
 * public navigation contribution instead of copying any screen implementation.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun TasksFeatureHost(
    modifier: Modifier = Modifier,
    backStack: NavBackStack<NavKey> = rememberNavBackStack(TaskListRoute),
) {
    // The host mirrors the app's adaptive configuration so that a layout
    // verified here behaves the same in the complete app. Any difference
    // between the two is a source of drift, which is the main cost of running
    // a feature host at all.
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive = remember(windowAdaptiveInfo) {
        calculatePaneScaffoldDirective(windowAdaptiveInfo)
            .copy(horizontalPartitionSpacerSize = 0.dp)
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        sceneStrategies = listOf(listDetailStrategy),
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            tasksEntries(
                onNavigate = { backStack.add(it) },
                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                showDetailBackButton = directive.maxHorizontalPartitions == 1,
            )
        },
    )
}
