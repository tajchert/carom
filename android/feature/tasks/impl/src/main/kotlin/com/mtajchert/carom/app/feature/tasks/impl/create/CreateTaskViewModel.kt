package com.mtajchert.carom.app.feature.tasks.impl.create

import com.mtajchert.carom.app.feature.tasks.impl.TaskUiError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.repository.TaskRepository
import com.mtajchert.carom.app.core.model.TaskPriority
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

// =============================================================================
// FORM STATE MANAGEMENT
// =============================================================================
// This ViewModel demonstrates form state management patterns.
//
// ## MutableStateFlow for Form State
//
// Unlike read-only data that comes from repositories, form state is:
// - Modified directly by user input
// - Not persisted until explicitly saved
// - Needs immediate UI updates on each keystroke
//
// For this, we use MutableStateFlow with direct updates, not a repository pattern.
//
// ## Single State Object
//
// We combine all form fields into a single [CreateTaskUiState] object rather
// than having separate StateFlows for each field. This:
// - Reduces the number of collectors
// - Makes state easier to reason about
// - Simplifies validation that depends on multiple fields
// =============================================================================

/**
 * UI state for the create task screen.
 */
data class CreateTaskUiState(
    val title: String = "",
    val description: String = "",
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val isLoading: Boolean = false,
    val titleError: TaskUiError? = null,
    val submitError: TaskUiError? = null,
    val isCreated: Boolean = false,
) {
    /**
     * Whether the form is valid and can be submitted.
     */
    val isValid: Boolean = title.isNotBlank() && titleError == null
}

@HiltViewModel
class CreateTaskViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Persist only the small draft. In-flight work is not replayed after process death.
    private val _uiState = MutableStateFlow(CreateTaskUiState(
        title = savedStateHandle["title"] ?: "",
        description = savedStateHandle["description"] ?: "",
        priority = TaskPriority.entries.firstOrNull {
            it.name == savedStateHandle.get<String>("priority")
        } ?: TaskPriority.MEDIUM,
    ))
    val uiState: StateFlow<CreateTaskUiState> = _uiState.asStateFlow()

    // =========================================================================
    // FORM FIELD UPDATES
    // =========================================================================
    // Each field update is a separate function for clarity.
    // We use `update {}` for thread-safe state modifications.
    // =========================================================================

    /**
     * Update the task title.
     */
    fun onTitleChange(title: String) {
        savedStateHandle["title"] = title
        _uiState.update { currentState ->
            currentState.copy(
                title = title,
                titleError = if (title.isBlank()) TaskUiError.TitleRequired else null,
                submitError = null,
            )
        }
    }

    /**
     * Update the task description.
     */
    fun onDescriptionChange(description: String) {
        savedStateHandle["description"] = description
        _uiState.update { it.copy(description = description) }
    }

    /**
     * Update the task priority.
     */
    fun onPriorityChange(priority: TaskPriority) {
        savedStateHandle["priority"] = priority.name
        _uiState.update { it.copy(priority = priority) }
    }

    // =========================================================================
    // FORM SUBMISSION
    // =========================================================================

    /**
     * Create the task.
     *
     * Completion is state; the UI owns navigation and its lifecycle.
     */
    fun onCreateTask() {
        val currentState = _uiState.value

        if (currentState.isLoading || currentState.isCreated) return

        // Validate before submitting
        if (currentState.title.isBlank()) {
            _uiState.update { it.copy(titleError = TaskUiError.TitleRequired) }
            return
        }

        // Claim the operation before dispatching so rapid taps cannot queue duplicates.
        _uiState.update { it.copy(isLoading = true, submitError = null) }

        viewModelScope.launch {
            try {
                taskRepository.createTask(
                    title = currentState.title,
                    description = currentState.description,
                    priority = currentState.priority,
                )
                _uiState.update { it.copy(isCreated = true) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        submitError = TaskUiError.CreateFailed,
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
