package com.mtajchert.carom.app.feature.tasks.impl.create

import com.mtajchert.carom.app.feature.tasks.impl.R
import androidx.compose.ui.res.stringResource
import com.mtajchert.carom.app.feature.tasks.impl.TaskUiError
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import com.mtajchert.carom.app.core.ui.localizedLabel
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.mtajchert.carom.app.core.designsystem.component.CaromButton
import com.mtajchert.carom.app.core.designsystem.component.CaromTopAppBar
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.TaskPriority

/**
 * Stateful create task screen.
 *
 * @param onBackClick Called when back navigation is requested
 * @param onTaskCreated Called after task is successfully created
 * @param viewModel The ViewModel (provided by Hilt)
 */
@Composable
fun CreateTaskScreen(
    onBackClick: () -> Unit,
    onTaskCreated: () -> Unit,
    viewModel: CreateTaskViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnTaskCreated by rememberUpdatedState(onTaskCreated)
    LaunchedEffect(uiState.isCreated, lifecycle) {
        if (uiState.isCreated) lifecycle.withResumed { currentOnTaskCreated() }
    }

    CreateTaskContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onTitleChange = viewModel::onTitleChange,
        onDescriptionChange = viewModel::onDescriptionChange,
        onPriorityChange = viewModel::onPriorityChange,
        onCreateClick = viewModel::onCreateTask,
    )
}

/**
 * Stateless create task content.
 */
@Composable
internal fun CreateTaskContent(
    uiState: CreateTaskUiState,
    onBackClick: () -> Unit,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPriorityChange: (TaskPriority) -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CaromTopAppBar(
                title = stringResource(R.string.create_task),
                onNavigationClick = onBackClick,
            )
        },
    ) { paddingValues ->
        // =====================================================================
        // EDGE-TO-EDGE AND IME INSETS
        // =====================================================================
        // Modifier order matters and is not interchangeable:
        //
        // 1. padding(paddingValues)        - space for the top app bar and system bars
        // 2. consumeWindowInsets(...)      - tells descendants those insets are handled,
        //                                    so nested components cannot apply them twice
        // 3. imePadding()                  - lifts the content above the soft keyboard.
        //                                    Scaffold's default contentWindowInsets does
        //                                    NOT include the IME, so without this the
        //                                    keyboard covers the focused text field.
        // 4. verticalScroll(...)           - must come after imePadding(), otherwise the
        //                                    keyboard inset scrolls away with the content
        //
        // Do not also set contentWindowInsets = WindowInsets.safeDrawing on the Scaffold.
        // That would deliver IME insets through paddingValues as well and pad twice.
        //
        // This only works because the hosting Activity declares
        // android:windowSoftInputMode="adjustResize" in the manifest.
        // =====================================================================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Title field
            OutlinedTextField(
                value = uiState.title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.title)) },
                placeholder = { Text(stringResource(R.string.title_hint)) },
                isError = uiState.titleError != null,
                supportingText = uiState.titleError?.let { error ->
                    { Text(error.message(), color = MaterialTheme.colorScheme.error) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Description field
            OutlinedTextField(
                value = uiState.description,
                onValueChange = onDescriptionChange,
                label = { Text(stringResource(R.string.description)) },
                placeholder = { Text(stringResource(R.string.description_hint)) },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )

            // Priority selection
            Text(
                text = stringResource(R.string.priority),
                style = MaterialTheme.typography.labelLarge,
            )

            PrioritySelector(
                selectedPriority = uiState.priority,
                onPrioritySelected = onPriorityChange,
            )

            uiState.submitError?.let { error ->
                Text(
                    text = error.message(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Create button
            CaromButton(
                text = stringResource(R.string.create_task),
                onClick = onCreateClick,
                enabled = uiState.isValid && !uiState.isLoading && !uiState.isCreated,
                isLoading = uiState.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Priority radio button group.
 *
 * ## Accessibility
 *
 * - Uses selectableGroup() for proper grouping
 * - Each radio button has contentDescription
 * - Row arrangement for horizontal layout
 */
@Composable
private fun PrioritySelector(
    selectedPriority: TaskPriority,
    onPrioritySelected: (TaskPriority) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().selectableGroup()) {
        TaskPriority.entries.forEach { priority ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = priority == selectedPriority,
                    role = Role.RadioButton,
                    onClick = { onPrioritySelected(priority) },
                ).padding(vertical = 4.dp),
            ) {
                RadioButton(selected = priority == selectedPriority, onClick = null)
                Text(text = priority.localizedLabel(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// =============================================================================
// PREVIEWS
// =============================================================================

@Preview(showBackground = true)
@Composable
private fun CreateTaskEmptyPreview() {
    CaromTheme {
        CreateTaskContent(
            uiState = CreateTaskUiState(),
            onBackClick = {},
            onTitleChange = {},
            onDescriptionChange = {},
            onPriorityChange = {},
            onCreateClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CreateTaskFilledPreview() {
    CaromTheme {
        CreateTaskContent(
            uiState = CreateTaskUiState(
                title = "Complete documentation",
                description = "Write comprehensive docs for all the architecture patterns",
                priority = TaskPriority.HIGH,
            ),
            onBackClick = {},
            onTitleChange = {},
            onDescriptionChange = {},
            onPriorityChange = {},
            onCreateClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CreateTaskErrorPreview() {
    CaromTheme {
        CreateTaskContent(
            uiState = CreateTaskUiState(
                title = "",
                titleError = TaskUiError.TitleRequired,
            ),
            onBackClick = {},
            onTitleChange = {},
            onDescriptionChange = {},
            onPriorityChange = {},
            onCreateClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CreateTaskLoadingPreview() {
    CaromTheme {
        CreateTaskContent(
            uiState = CreateTaskUiState(
                title = "My Task",
                isLoading = true,
            ),
            onBackClick = {},
            onTitleChange = {},
            onDescriptionChange = {},
            onPriorityChange = {},
            onCreateClick = {},
        )
    }
}
