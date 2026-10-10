package com.mtajchert.carom.app.feature.tasks.impl.list

import com.mtajchert.carom.app.feature.tasks.impl.TaskUiError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.common.result.Result
import com.mtajchert.carom.app.core.common.result.asResult
import com.mtajchert.carom.app.core.data.repository.TaskRepository
import com.mtajchert.carom.app.core.domain.GetSortedTasksUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

// =============================================================================
// VIEWMODEL PATTERN
// =============================================================================
// This ViewModel demonstrates the recommended patterns from Now in Android:
//
// ## StateFlow with stateIn
//
// We use `stateIn` to convert Flow to StateFlow because:
// 1. StateFlow replays the last value to new collectors
// 2. We can specify an initial value for immediate UI rendering
// 3. WhileSubscribed(5_000) keeps the flow alive briefly during config changes
//
// ## Why WhileSubscribed(5_000)?
//
// - The "5000" is 5 seconds in milliseconds
// - During rotation, the old collector stops and new one starts
// - 5 second buffer prevents restarting the upstream flow
// - Saves resources when user leaves the screen
//
// ## Result Wrapper
//
// We wrap the Flow in Result to handle:
// - Loading state (initial)
// - Success state (with data)
// - Error state (with exception)
//
// This eliminates try-catch in the ViewModel and moves error handling
// to the flow transformation.
// =============================================================================

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskListViewModel @Inject constructor(
    getSortedTasksUseCase: GetSortedTasksUseCase,
    private val taskRepository: TaskRepository,
) : ViewModel() {

    private val retryCount = MutableStateFlow(0)
    private val operationError = MutableStateFlow<TaskUiError?>(null)

    /**
     * UI state exposed to the screen.
     *
     * This is computed from the sorted tasks use case, wrapped in Result,
     * and transformed to UI state.
     *
     * ## Flow Pipeline
     *
     * ```
     * getSortedTasksUseCase()  // Flow<List<Task>>
     *   → asResult()           // Flow<Result<List<Task>>>
     *   → map { ... }          // Flow<TaskListUiState>
     *   → stateIn(...)         // StateFlow<TaskListUiState>
     * ```
     */
    val uiState: StateFlow<TaskListUiState> = combine(
        retryCount.flatMapLatest { getSortedTasksUseCase().asResult() },
        operationError,
    ) { result, operationErrorMessage ->
            when (result) {
                is Result.Loading -> TaskListUiState.Loading
                is Result.Success -> {
                    if (result.data.isEmpty() && operationErrorMessage != null) {
                        TaskListUiState.Error(operationErrorMessage)
                    } else if (result.data.isEmpty()) {
                        TaskListUiState.Empty
                    } else {
                        TaskListUiState.Success(tasks = result.data, errorMessage = operationErrorMessage)
                    }
                }
                is Result.Error -> TaskListUiState.Error(
                    message = TaskUiError.LoadTasksFailed,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TaskListUiState.Loading,
        )


    // =========================================================================
    // USER ACTIONS
    // =========================================================================
    // These methods handle user interactions from the UI.
    // They launch coroutines in viewModelScope for fire-and-forget operations.
    //
    // Note: We don't expose loading states for these actions because:
    // 1. They're typically fast (local DB operations)
    // 2. The main uiState will update when the data changes
    // 3. For slow operations, consider adding action-specific loading states
    // =========================================================================

    /**
     * Toggle task completion status.
     *
     * @param taskId The ID of the task to toggle
     * @param isCompleted The new completion status
     */
    fun onTaskCheckedChange(taskId: String, isCompleted: Boolean) {
        runWrite {
            taskRepository.updateTaskCompletion(taskId, isCompleted)
        }
    }

    /**
     * Delete a task.
     *
     * @param taskId The ID of the task to delete
     */
    fun onDeleteTask(taskId: String) {
        runWrite {
            taskRepository.deleteTask(taskId)
        }
    }

    private fun runWrite(action: suspend () -> Unit) {
        viewModelScope.launch {
            operationError.value = null
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                operationError.value = TaskUiError.SaveFailed
            }
        }
    }

    /** Imports the latest remote snapshot while Room remains the UI source of truth. */
    fun onRetry() {
        operationError.value = null
        retryCount.value += 1
    }

    /** Called only after the UI confirms replacement of local tasks. */
    fun onImportDemoTasks() { refreshTasks() }

    private fun refreshTasks() {
        viewModelScope.launch {
            operationError.value = null
            try {
                taskRepository.refreshTasks()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                operationError.value = TaskUiError.ImportFailed
            }
        }
    }
}
