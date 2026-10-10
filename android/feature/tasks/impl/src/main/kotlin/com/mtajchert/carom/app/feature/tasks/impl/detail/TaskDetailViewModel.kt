package com.mtajchert.carom.app.feature.tasks.impl.detail

import com.mtajchert.carom.app.feature.tasks.impl.TaskUiError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.common.result.Result
import com.mtajchert.carom.app.core.common.result.asResult
import com.mtajchert.carom.app.core.data.repository.TaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TaskDetailActionState(
    val isRunning: Boolean = false,
    val isDeleted: Boolean = false,
    val errorMessage: TaskUiError? = null,
)

// =============================================================================
// ASSISTED INJECTION FOR NAVIGATION ARGUMENTS (Navigation 3)
// =============================================================================
// This ViewModel demonstrates how to receive type-safe navigation arguments.
//
// ## Why Assisted Injection?
//
// In Navigation 3 the app owns the back stack, and navigation arguments are
// plain properties of the NavKey - they are no longer delivered through
// SavedStateHandle. The recommended pattern is Hilt assisted injection:
//
// ```kotlin
// viewModel = hiltViewModel<TaskDetailViewModel, TaskDetailViewModel.Factory>(
//     creationCallback = { factory -> factory.create(route.taskId) },
// )
// ```
//
// This provides compile-time safety for navigation arguments - no parsing,
// no string keys, just a constructor parameter.
// =============================================================================

@HiltViewModel(assistedFactory = TaskDetailViewModel.Factory::class)
class TaskDetailViewModel @AssistedInject constructor(
    @Assisted private val taskId: String,
    private val taskRepository: TaskRepository,
) : ViewModel() {
    private val _actionState = MutableStateFlow(TaskDetailActionState())
    val actionState: StateFlow<TaskDetailActionState> = _actionState.asStateFlow()

    /**
     * Factory for creating the ViewModel with its navigation argument.
     */
    @AssistedFactory
    interface Factory {
        fun create(taskId: String): TaskDetailViewModel
    }

    /**
     * UI state for the task detail screen.
     */
    val uiState: StateFlow<TaskDetailUiState> = taskRepository
        .observeTask(taskId)
        .asResult()
        .map { result ->
            when (result) {
                is Result.Loading -> TaskDetailUiState.Loading
                is Result.Success -> {
                    val task = result.data
                    if (task != null) {
                        TaskDetailUiState.Success(task = task)
                    } else {
                        TaskDetailUiState.NotFound
                    }
                }
                is Result.Error -> TaskDetailUiState.Error(
                    message = TaskUiError.LoadTaskFailed,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TaskDetailUiState.Loading,
        )

    // =========================================================================
    // USER ACTIONS
    // =========================================================================

    /**
     * Toggle task completion status.
     */
    fun onToggleCompletion() {
        val task = (uiState.value as? TaskDetailUiState.Success)?.task ?: return
        runAction {
            taskRepository.updateTaskCompletion(task.id, !task.isCompleted)
        }
    }

    /**
     * Delete the task.
     *
     * The UI navigates once it observes successful deletion while resumed.
     */
    fun onDelete() {
        runAction {
            taskRepository.deleteTask(taskId)
            _actionState.update { it.copy(isDeleted = true) }
        }
    }

    private fun runAction(action: suspend () -> Unit) {
        if (_actionState.value.isRunning || _actionState.value.isDeleted) return
        _actionState.update { it.copy(isRunning = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionState.update {
                    it.copy(errorMessage = TaskUiError.SaveFailed)
                }
            } finally {
                _actionState.update { it.copy(isRunning = false) }
            }
        }
    }
}
