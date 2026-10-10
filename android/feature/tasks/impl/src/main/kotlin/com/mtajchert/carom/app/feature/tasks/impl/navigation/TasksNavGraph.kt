package com.mtajchert.carom.app.feature.tasks.impl.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.mtajchert.carom.app.feature.tasks.api.CreateTaskRoute
import com.mtajchert.carom.app.feature.tasks.api.TaskDetailRoute
import com.mtajchert.carom.app.feature.tasks.api.TaskListRoute
import com.mtajchert.carom.app.feature.tasks.impl.create.CreateTaskScreen
import com.mtajchert.carom.app.feature.tasks.impl.detail.TaskDetailScreen
import com.mtajchert.carom.app.feature.tasks.impl.list.TaskListScreen
import com.mtajchert.carom.app.feature.tasks.impl.list.TaskListPlaceholder

// =============================================================================
// FEATURE NAVIGATION CONTRIBUTION (Navigation 3)
// =============================================================================
// This file provides an extension function that adds all task feature entries
// to the app's entry provider. This pattern allows:
//
// 1. **Encapsulation**: The app module doesn't need to know about individual
//    screens, only how to add "the tasks feature" to navigation.
//
// 2. **Single Point of Entry**: All task-related navigation is defined here.
//
// 3. **Type Safety**: Uses the NavKey routes from feature:tasks:api.
//
// ## Navigation 3 Pattern
//
// In Navigation 3 the app owns the back stack (a simple list of NavKeys).
// Features don't manipulate the back stack directly - they receive callbacks:
// - `onNavigate` pushes a new key onto the back stack
// - `onBack` pops the current key off the back stack
//
// ## Usage in App Module
//
// ```kotlin
// NavDisplay(
//     backStack = backStack,
//     entryProvider = entryProvider {
//         tasksEntries(
//             onNavigate = { backStack.add(it) },
//             onBack = { backStack.removeLastOrNull() },
//         )
//         // Other features...
//     },
// )
// ```
//
// ## Adaptive pane roles
//
// Each entry declares the role it plays in a list-detail layout through
// `metadata`. This is a statement about the *relationship* between screens, not
// about any device: the list is the primary content, the detail is secondary.
// The host's scene strategy reads these roles and decides whether the window is
// wide enough to show both. Nothing in this file measures the screen.
//
// A feature with no such relationship simply omits the metadata and always
// renders as a single pane.
// =============================================================================

/**
 * Adds the tasks feature entries to the entry provider.
 *
 * This adds all task-related destinations:
 * - Task List (start destination for this feature, and the list pane)
 * - Task Detail (the detail pane)
 * - Create Task
 *
 * @param onNavigate Pushes the given route onto the back stack
 * @param onBack Pops the current route off the back stack
 * @param showDetailBackButton Whether the detail screen should draw a back
 *   arrow. False when the host is showing list and detail together, where the
 *   list is still on screen and an arrow would be misleading.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun EntryProviderScope<NavKey>.tasksEntries(
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
    showDetailBackButton: Boolean = true,
) {
    // Task List Screen - the list pane.
    //
    // detailPlaceholder fills the detail pane when both panes are visible but
    // nothing is selected yet. Without it a wide window opens with a large
    // empty area next to the list.
    entry<TaskListRoute>(
        metadata = ListDetailSceneStrategy.listPane(
            detailPlaceholder = { TaskListPlaceholder() },
        ),
    ) {
        TaskListScreen(
            onTaskClick = { taskId ->
                onNavigate(TaskDetailRoute(taskId = taskId))
            },
            // dropUnlessResumed ignores a click that arrives after this entry
            // has started leaving the composition, so a double tap cannot push
            // the same destination twice. Row clicks are guarded the same way
            // at their click site inside the list, because dropUnlessResumed
            // only wraps a zero-argument lambda.
            onCreateClick = dropUnlessResumed {
                onNavigate(CreateTaskRoute)
            },
        )
    }

    // Task Detail Screen - the detail pane.
    entry<TaskDetailRoute>(
        metadata = ListDetailSceneStrategy.detailPane(),
    ) { key ->
        TaskDetailScreen(
            route = key,
            onBackClick = onBack,
            showBackButton = showDetailBackButton,
        )
    }

    // Create Task Screen. No pane metadata: creating a task is a self-contained
    // flow rather than the detail of the list, so it takes the whole window.
    entry<CreateTaskRoute> {
        CreateTaskScreen(
            onBackClick = onBack,
            onTaskCreated = {
                // Navigate back to list after creating
                onBack()
            },
        )
    }
}
