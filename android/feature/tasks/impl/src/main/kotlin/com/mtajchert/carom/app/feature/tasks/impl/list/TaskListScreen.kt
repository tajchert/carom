package com.mtajchert.carom.app.feature.tasks.impl.list

import com.mtajchert.carom.app.feature.tasks.impl.R
import androidx.compose.ui.res.stringResource
import com.mtajchert.carom.app.feature.tasks.impl.TaskUiError
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.mtajchert.carom.app.feature.tasks.impl.preferences.PreferencesDialog
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.mtajchert.carom.app.core.designsystem.component.CaromEmptyState
import com.mtajchert.carom.app.core.designsystem.component.CaromErrorState
import com.mtajchert.carom.app.core.designsystem.component.CaromLoadingIndicator
import com.mtajchert.carom.app.core.designsystem.component.CaromTopAppBar
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.Task
import com.mtajchert.carom.app.core.model.TaskPriority
import com.mtajchert.carom.app.core.ui.TaskCard
import kotlin.time.Instant

// =============================================================================
// STATE HOISTING PATTERN
// =============================================================================
// This file demonstrates the state hoisting pattern:
//
// ## Two Composables Per Screen
//
// 1. **Stateful wrapper** (TaskListScreen):
//    - Connects to ViewModel via hiltViewModel()
//    - Collects state with collectAsStateWithLifecycle()
//    - Passes state and callbacks to stateless content
//
// 2. **Stateless content** (TaskListContent):
//    - Receives all data as parameters
//    - Has no side effects
//    - Easy to preview and test
//
// ## Why This Pattern?
//
// - **Testability**: Content can be tested without ViewModel
// - **Previews**: Content can be previewed with sample data
// - **Reusability**: Content could be used in different contexts
// - **Separation**: Business logic stays in ViewModel, UI stays in composables
// =============================================================================

/**
 * Stateful task list screen.
 *
 * This composable:
 * - Connects to the ViewModel
 * - Collects UI state lifecycle-aware
 * - Delegates rendering to [TaskListContent]
 *
 * @param onTaskClick Called when a task is clicked (navigate to detail)
 * @param onCreateClick Called when create FAB is clicked
 * @param viewModel The ViewModel (provided by Hilt)
 */
@Composable
fun TaskListScreen(
    onTaskClick: (taskId: String) -> Unit,
    onCreateClick: () -> Unit,
    viewModel: TaskListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showPreferences by rememberSaveable { mutableStateOf(false) }
    if (showPreferences) PreferencesDialog(onDismiss = { showPreferences = false })

    TaskListContent(
        uiState = uiState,
        onTaskClick = onTaskClick,
        onTaskCheckedChange = viewModel::onTaskCheckedChange,
        onDeleteTask = viewModel::onDeleteTask,
        onRetry = viewModel::onRetry,
        onCreateClick = onCreateClick,
        onPreferencesClick = { showPreferences = true },
        onImportDemoTasks = viewModel::onImportDemoTasks,
    )
}

/**
 * Stateless task list content.
 *
 * This composable renders the UI based on the provided state.
 * It has no knowledge of ViewModels or data fetching.
 *
 * @param uiState The current UI state
 * @param onTaskClick Called when a task is clicked
 * @param onTaskCheckedChange Called when task checkbox is toggled
 * @param onDeleteTask Called when task delete button is clicked
 * @param onCreateClick Called when create FAB is clicked
 * @param modifier Modifier for the screen
 */
@Composable
internal fun TaskListContent(
    uiState: TaskListUiState,
    onTaskClick: (taskId: String) -> Unit,
    onTaskCheckedChange: (taskId: String, isCompleted: Boolean) -> Unit,
    onDeleteTask: (taskId: String) -> Unit,
    onRetry: () -> Unit = {},
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPreferencesClick: () -> Unit = {},
    onImportDemoTasks: () -> Unit = {},
) {
    var confirmImport by rememberSaveable { mutableStateOf(false) }
    if (confirmImport) AlertDialog(
        onDismissRequest = { confirmImport = false },
        title = { Text(stringResource(R.string.import_title)) },
        text = { Text(stringResource(R.string.import_message)) },
        dismissButton = { TextButton(onClick = { confirmImport = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = { confirmImport = false; onImportDemoTasks() }) {
            Text(stringResource(R.string.import_confirm))
        } },
    )
    Scaffold(
        modifier = modifier,
        topBar = {
            CaromTopAppBar(title = stringResource(R.string.tasks), actions = {
                IconButton(onClick = { confirmImport = true }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.import_demo))
                }
                IconButton(onClick = onPreferencesClick) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.preferences))
                }
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateClick) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.create_task_description),
                )
            }
        },
    ) { paddingValues ->
        when (uiState) {
            is TaskListUiState.Loading -> {
                CaromLoadingIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                )
            }

            is TaskListUiState.Empty -> {
                CaromEmptyState(
                    icon = Icons.Outlined.CheckCircle,
                    title = stringResource(R.string.empty_title),
                    description = stringResource(R.string.empty_description),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                )
            }

            is TaskListUiState.Success -> {
                TaskList(
                    tasks = uiState.tasks,
                    onTaskClick = onTaskClick,
                    onTaskCheckedChange = onTaskCheckedChange,
                    onDeleteTask = onDeleteTask,
                    contentPadding = paddingValues,
                    errorMessage = uiState.errorMessage,
                )
            }

            is TaskListUiState.Error -> {
                CaromErrorState(
                    message = uiState.message.message(),
                    onRetry = onRetry,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                )
            }
        }
    }
}

/**
 * Task list using an adaptive grid.
 *
 * ## Why a grid rather than a LazyColumn
 *
 * `GridCells.Adaptive` fits as many columns of at least [TASK_CARD_MIN_WIDTH]
 * as the window allows: one on a phone or a folded cover display, two or more
 * on an unfolded foldable, a tablet or a desktop window. The layout follows the
 * window it is given instead of branching on a device type, and a single-column
 * grid renders identically to the list it replaces.
 *
 * The minimum width is a content decision: below it a task card's title and
 * description stop being comfortably readable.
 *
 * ## Performance Notes
 *
 * - Uses `key` parameter for stable item identity
 * - Each item recomposes independently
 * - No state reads during scroll (TaskCard is stateless)
 *
 * ## Edge-to-edge Notes
 *
 * Scaffold insets are passed to `contentPadding`, never to `Modifier.padding()`
 * on the list itself. Padding the list would clip it at the system bars instead
 * of letting the first and last items scroll behind them. `consumeWindowInsets`
 * then marks those insets as handled so nested content cannot apply them again.
 */
@Composable
private fun TaskList(
    tasks: List<Task>,
    onTaskClick: (taskId: String) -> Unit,
    onTaskCheckedChange: (taskId: String, isCompleted: Boolean) -> Unit,
    onDeleteTask: (taskId: String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    errorMessage: TaskUiError? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TASK_CARD_MIN_WIDTH),
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(contentPadding),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 88.dp, // Space for FAB
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        errorMessage?.let { message ->
            // The error belongs to the whole list, so it spans every column
            // instead of sitting in the first cell.
            item(key = "operation-error", span = { GridItemSpan(maxLineSpan) }) {
                Text(text = message.message(), color = MaterialTheme.colorScheme.error)
            }
        }
        items(
            items = tasks,
            key = { task -> task.id }, // Stable key for recomposition optimization
        ) { task ->
            TaskCard(
                task = task,
                onCheckedChange = { isCompleted ->
                    onTaskCheckedChange(task.id, isCompleted)
                },
                // Guarded at the click site: a second tap once this entry has
                // begun leaving the composition is dropped rather than pushing
                // a second detail destination.
                onClick = dropUnlessResumed { onTaskClick(task.id) },
                onDeleteClick = { onDeleteTask(task.id) },
            )
        }
    }
}

/**
 * Narrowest width at which a task card stays readable, and therefore the point
 * at which the grid adds another column.
 */
private val TASK_CARD_MIN_WIDTH = 360.dp

/**
 * Shown in the detail pane of a list-detail layout before anything is selected.
 *
 * A wide window always renders both panes, so without a placeholder the detail
 * half would simply be blank on first open.
 */
@Composable
fun TaskListPlaceholder(modifier: Modifier = Modifier) {
    CaromEmptyState(
        icon = Icons.Outlined.Checklist,
        title = stringResource(R.string.placeholder_title),
        description = stringResource(R.string.placeholder_description),
        modifier = modifier.fillMaxSize(),
    )
}

// =============================================================================
// PREVIEWS
// =============================================================================
// Multi-preview setup to test different states and configurations.
//
// ## PreviewParameterProvider
//
// We use PreviewParameterProvider to generate multiple previews from a single
// @Preview annotation. This reduces code duplication while ensuring we preview
// all important states.
// =============================================================================

private class TaskListUiStatePreviewProvider : PreviewParameterProvider<TaskListUiState> {
    override val values: Sequence<TaskListUiState> = sequenceOf(
        TaskListUiState.Loading,
        TaskListUiState.Empty,
        TaskListUiState.Success(
            tasks = listOf(
                Task(
                    id = "1",
                    title = "Complete project documentation",
                    description = "Write comprehensive docs for the architecture patterns",
                    isCompleted = false,
                    createdAt = Instant.parse("2026-01-15T10:30:00Z"),
                    priority = TaskPriority.HIGH,
                ),
                Task(
                    id = "2",
                    title = "Setup CI/CD pipeline",
                    description = "Configure GitHub Actions for automated builds",
                    isCompleted = true,
                    createdAt = Instant.parse("2026-01-15T10:30:00Z"),
                    priority = TaskPriority.MEDIUM,
                ),
                Task(
                    id = "3",
                    title = "Review pull requests",
                    description = "",
                    isCompleted = false,
                    createdAt = Instant.parse("2026-01-15T10:30:00Z"),
                    priority = TaskPriority.LOW,
                ),
            ),
        ),
        TaskListUiState.Error(TaskUiError.LoadTasksFailed),
    )
}

@Preview(showBackground = true, name = "Task List States")
@Composable
private fun TaskListContentPreview(
    @PreviewParameter(TaskListUiStatePreviewProvider::class) uiState: TaskListUiState,
) {
    CaromTheme {
        TaskListContent(
            uiState = uiState,
            onTaskClick = {},
            onTaskCheckedChange = { _, _ -> },
            onDeleteTask = {},
            onRetry = {},
            onCreateClick = {},
        )
    }
}

@Preview(showBackground = true, name = "Task List - Dark Theme")
@Composable
private fun TaskListContentDarkPreview() {
    CaromTheme(darkTheme = true) {
        TaskListContent(
            uiState = TaskListUiState.Success(
                tasks = listOf(
                    Task(
                        id = "1",
                        title = "Dark theme task",
                        description = "Testing dark theme appearance",
                        isCompleted = false,
                        createdAt = Instant.parse("2026-01-15T10:30:00Z"),
                        priority = TaskPriority.HIGH,
                    ),
                ),
            ),
            onTaskClick = {},
            onTaskCheckedChange = { _, _ -> },
            onDeleteTask = {},
            onRetry = {},
            onCreateClick = {},
        )
    }
}
