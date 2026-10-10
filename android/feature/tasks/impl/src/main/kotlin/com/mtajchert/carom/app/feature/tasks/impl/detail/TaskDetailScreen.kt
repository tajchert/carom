package com.mtajchert.carom.app.feature.tasks.impl.detail

import com.mtajchert.carom.app.feature.tasks.impl.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalConfiguration
import java.time.format.FormatStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.mtajchert.carom.app.core.designsystem.component.CaromErrorState
import com.mtajchert.carom.app.core.designsystem.component.CaromLoadingIndicator
import com.mtajchert.carom.app.core.designsystem.component.CaromTopAppBar
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.Task
import com.mtajchert.carom.app.core.model.TaskPriority
import com.mtajchert.carom.app.feature.tasks.api.TaskDetailRoute
import com.mtajchert.carom.app.core.ui.PriorityBadge
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

/**
 * Stateful task detail screen.
 *
 * The route's [taskId][com.mtajchert.carom.app.feature.tasks.api.TaskDetailRoute.taskId]
 * is passed to the ViewModel via Hilt assisted injection. The
 * ViewModelStoreNavEntryDecorator (installed in CaromNavHost) scopes each
 * ViewModel to its back stack entry, so every TaskDetailRoute instance gets
 * its own ViewModel.
 *
 * @param route The route containing the task ID to display
 * @param onBackClick Called when back navigation is requested
 * @param showBackButton Whether to draw a back arrow. False when the host shows
 *   this screen beside the list in a two-pane layout: the list has not been
 *   replaced, so there is nothing to go back to and an arrow would mislead.
 * @param viewModel The ViewModel (provided by Hilt)
 */
@Composable
fun TaskDetailScreen(
    route: TaskDetailRoute,
    onBackClick: () -> Unit,
    showBackButton: Boolean = true,
    viewModel: TaskDetailViewModel = hiltViewModel<TaskDetailViewModel, TaskDetailViewModel.Factory>(
        creationCallback = { factory -> factory.create(route.taskId) },
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val actionState by viewModel.actionState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnBackClick by rememberUpdatedState(onBackClick)
    LaunchedEffect(actionState.isDeleted, lifecycle) {
        if (actionState.isDeleted) lifecycle.withResumed { currentOnBackClick() }
    }

    TaskDetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onToggleCompletion = viewModel::onToggleCompletion,
        onDelete = viewModel::onDelete,
        actionState = actionState,
        showBackButton = showBackButton,
    )
}

/**
 * Stateless task detail content.
 */
@Composable
internal fun TaskDetailContent(
    uiState: TaskDetailUiState,
    onBackClick: () -> Unit,
    onToggleCompletion: () -> Unit,
    onDelete: () -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
    modifier: Modifier = Modifier,
    actionState: TaskDetailActionState = TaskDetailActionState(),
    showBackButton: Boolean = true,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CaromTopAppBar(
                title = stringResource(R.string.task_details),
                // The app bar draws the arrow only when a click handler is
                // supplied, so withholding it is how the two-pane layout hides
                // navigation that no longer applies.
                onNavigationClick = onBackClick.takeIf { showBackButton },
            )
        },
    ) { paddingValues ->
        when (uiState) {
            is TaskDetailUiState.Loading -> {
                CaromLoadingIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .consumeWindowInsets(paddingValues),
                )
            }

            is TaskDetailUiState.NotFound -> {
                CaromErrorState(
                    message = stringResource(R.string.task_not_found),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .consumeWindowInsets(paddingValues),
                )
            }

            is TaskDetailUiState.Success -> {
                TaskDetailBody(
                    task = uiState.task,
                    onToggleCompletion = onToggleCompletion,
                    onDelete = onDelete,
                    zoneId = zoneId,
                    actionState = actionState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .consumeWindowInsets(paddingValues)
                        .padding(16.dp),
                )
            }

            is TaskDetailUiState.Error -> {
                CaromErrorState(
                    message = uiState.message.message(),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .consumeWindowInsets(paddingValues),
                )
            }
        }
    }
}

@Composable
private fun TaskDetailBody(
    task: Task,
    onToggleCompletion: () -> Unit,
    onDelete: () -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
    modifier: Modifier = Modifier,
    actionState: TaskDetailActionState = TaskDetailActionState(),
) {
    val completionLabel = stringResource(com.mtajchert.carom.app.core.ui.R.string.task_completion, task.title)
    Column(modifier = modifier) {
        // Title with checkbox
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                modifier = Modifier.semantics { contentDescription = completionLabel },
                enabled = !actionState.isRunning && !actionState.isDeleted,
                checked = task.isCompleted,
                onCheckedChange = { onToggleCompletion() },
            )
            Text(
                text = task.title,
                style = MaterialTheme.typography.headlineSmall,
                textDecoration = if (task.isCompleted) {
                    TextDecoration.LineThrough
                } else {
                    TextDecoration.None
                },
                color = if (task.isCompleted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Priority badge
        PriorityBadge(priority = task.priority)

        Spacer(modifier = Modifier.height(16.dp))

        // Description
        if (task.description.isNotBlank()) {
            Text(
                text = stringResource(R.string.description),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = task.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Created date
        Text(
            text = stringResource(R.string.created),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        val javaInstant = java.time.Instant.ofEpochMilli(
            (task.createdAt - kotlin.time.Instant.fromEpochMilliseconds(0)).inWholeMilliseconds
        )
        val localDateTime = javaInstant.atZone(zoneId).toLocalDateTime()
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(LocalConfiguration.current.locales[0])
        Text(
            text = localDateTime.format(formatter),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.weight(1f))

        actionState.errorMessage?.let { message ->
            Text(text = message.message(), color = MaterialTheme.colorScheme.error)
        }

        // Delete button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(
                onClick = onDelete,
                enabled = !actionState.isRunning && !actionState.isDeleted,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.delete_task))
            }
        }
    }
}

// =============================================================================
// PREVIEWS
// =============================================================================

@Preview(showBackground = true)
@Composable
private fun TaskDetailSuccessPreview() {
    CaromTheme {
        TaskDetailContent(
            uiState = TaskDetailUiState.Success(
                task = Task(
                    id = "1",
                    title = "Complete project documentation",
                    description = "Write comprehensive docs for the architecture patterns used in this sample project. Include diagrams and code examples.",
                    isCompleted = false,
                    createdAt = Instant.parse("2026-01-15T10:30:00Z"),
                    priority = TaskPriority.HIGH,
                ),
            ),
            onBackClick = {},
            onToggleCompletion = {},
            onDelete = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TaskDetailCompletedPreview() {
    CaromTheme {
        TaskDetailContent(
            uiState = TaskDetailUiState.Success(
                task = Task(
                    id = "2",
                    title = "Setup Room database",
                    description = "Already completed",
                    isCompleted = true,
                    createdAt = Instant.parse("2026-01-15T10:30:00Z"),
                    priority = TaskPriority.MEDIUM,
                ),
            ),
            onBackClick = {},
            onToggleCompletion = {},
            onDelete = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TaskDetailLoadingPreview() {
    CaromTheme {
        TaskDetailContent(
            uiState = TaskDetailUiState.Loading,
            onBackClick = {},
            onToggleCompletion = {},
            onDelete = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TaskDetailNotFoundPreview() {
    CaromTheme {
        TaskDetailContent(
            uiState = TaskDetailUiState.NotFound,
            onBackClick = {},
            onToggleCompletion = {},
            onDelete = {},
        )
    }
}
