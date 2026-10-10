package com.mtajchert.carom.app.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.mtajchert.carom.app.feature.tasks.api.TaskListRoute
import com.mtajchert.carom.app.feature.tasks.impl.navigation.tasksEntries

// =============================================================================
// APP NAVIGATION DISPLAY (Navigation 3)
// =============================================================================
// Central navigation configuration for the entire app.
//
// ## Navigation 3 Concepts
//
// - **Back stack**: A snapshot-state list of NavKeys owned by the app.
//   `rememberNavBackStack` persists it across config changes & process death.
// - **NavDisplay**: Observes the back stack and renders the current scene.
// - **Entry provider**: Maps each NavKey to its composable content.
// - **Entry decorators**: Provide per-entry saved state and ViewModel scoping.
// - **Scene strategy**: Decides how many entries are on screen at once.
//
// ## Adaptive layout
//
// A single back stack drives every window size. On a phone the top entry fills
// the window; on an unfolded foldable, tablet or desktop window the same back
// stack renders the list and the detail side by side. Features do not branch on
// screen size - they declare which pane role each destination plays, and
// `ListDetailSceneStrategy` decides what fits.
//
// This is deliberately the Navigation 3 Scenes approach rather than
// `ListDetailPaneScaffold`. A scaffold would need its own selection state
// alongside the back stack, leaving two sources of truth to keep in sync.
//
// ## Feature Composition
//
// Each feature contributes its entries via an extension function:
// ```kotlin
// entryProvider = entryProvider {
//     tasksEntries(...)      // Tasks feature
//     settingsEntries(...)   // Settings feature (future)
// }
// ```
//
// ## Type-Safe Routes
//
// - Routes are @Serializable classes implementing NavKey
// - No string-based route matching
// - Arguments are type-checked at compile time
//
// ## Adding New Features
//
// To add a new feature:
// 1. Create feature:newfeature:api with NavKey routes
// 2. Create feature:newfeature:impl with screens
// 3. Add `newFeatureEntries(...)` to the entryProvider here
// =============================================================================

/**
 * Main navigation display for the app.
 *
 * @param modifier Modifier for the NavDisplay
 * @param initialBackStack Keys the app starts with. A deep link resolved during
 *   `onCreate` supplies a synthetic back stack here so Back stays inside the app.
 * @param pendingDeepLink A back stack requested by a deep link that arrived
 *   while the app was already running, or null when there is none.
 * @param onDeepLinkHandled Called once [pendingDeepLink] has been installed, so
 *   the host can clear it and the same link is not applied twice.
 * @param backStack The navigation back stack (created if not provided)
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun CaromNavHost(
    modifier: Modifier = Modifier,
    initialBackStack: List<NavKey> = listOf(TaskListRoute),
    pendingDeepLink: List<NavKey>? = null,
    onDeepLinkHandled: () -> Unit = {},
    backStack: NavBackStack<NavKey> = rememberNavBackStack(*initialBackStack.toTypedArray()),
) {
    // A warm-start deep link replaces the whole back stack rather than pushing
    // onto it. Appending would leave the user's previous history underneath an
    // unrelated destination, so Back would walk backwards through two separate
    // journeys.
    LaunchedEffect(pendingDeepLink) {
        val requested = pendingDeepLink ?: return@LaunchedEffect
        backStack.clear()
        backStack.addAll(requested)
        onDeepLinkHandled()
    }

    // The window's own characteristics decide the pane layout. The spacer
    // override removes the gap between panes (b/418201867); drop it once the
    // default is fixed upstream.
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val directive = remember(windowAdaptiveInfo) {
        calculatePaneScaffoldDirective(windowAdaptiveInfo)
            .copy(horizontalPartitionSpacerSize = 0.dp)
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)

    // When both panes are on screen the list is always visible, so a detail
    // screen must not draw a back arrow - there is nothing behind it to go back
    // to, and the arrow would suggest the list had been replaced.
    val isSinglePane = directive.maxHorizontalPartitions == 1

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        sceneStrategies = listOf(listDetailStrategy),
        // The ViewModelStoreNavEntryDecorator scopes ViewModels to their
        // back stack entry, so each destination gets its own ViewModel that
        // is cleared when the entry is popped. Specifying entryDecorators
        // replaces the defaults, so the saveable-state decorator must be
        // listed explicitly as well.
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            // Tasks feature
            tasksEntries(
                onNavigate = { backStack.add(it) },
                onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                showDetailBackButton = isSinglePane,
            )

            // Add more features here:
            // settingsEntries(...)
            // profileEntries(...)
        },
    )
}
