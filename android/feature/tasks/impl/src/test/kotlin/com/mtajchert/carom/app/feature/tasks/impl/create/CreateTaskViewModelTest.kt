package com.mtajchert.carom.app.feature.tasks.impl.create

import com.mtajchert.carom.app.feature.tasks.impl.TaskUiError
import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import com.mtajchert.carom.app.core.model.TaskPriority
import com.mtajchert.carom.app.core.testing.repository.FakeTaskRepository
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// =============================================================================
// CREATE TASK VIEWMODEL TESTS
// =============================================================================
// Tests for form state management and task creation.
//
// ## Form Testing Patterns
//
// Forms have different testing considerations than read-only screens:
// - Input validation must be tested
// - Error states must be checked
// - Completion must remain observable without a UI collector
// =============================================================================

class CreateTaskViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var taskRepository: FakeTaskRepository
    private lateinit var viewModel: CreateTaskViewModel

    @Before
    fun setup() {
        taskRepository = FakeTaskRepository()
        viewModel = CreateTaskViewModel(taskRepository, SavedStateHandle())
    }

    // =========================================================================
    // INITIAL STATE TESTS
    // =========================================================================

    @Test
    fun `initial state has empty fields`() = runTest {
        val state = viewModel.uiState.value

        assertEquals("", state.title)
        assertEquals("", state.description)
        assertEquals(TaskPriority.MEDIUM, state.priority)
        assertFalse(state.isLoading)
        assertNull(state.titleError)
    }

    @Test
    fun `initial state is not valid`() = runTest {
        assertFalse(viewModel.uiState.value.isValid)
    }

    // =========================================================================
    // INPUT UPDATE TESTS
    // =========================================================================

    @Test
    fun `onTitleChange updates title`() = runTest {
        viewModel.onTitleChange("New Task")

        assertEquals("New Task", viewModel.uiState.value.title)
    }

    @Test
    fun `onTitleChange with valid title makes form valid`() = runTest {
        viewModel.onTitleChange("New Task")

        assertTrue(viewModel.uiState.value.isValid)
    }

    @Test
    fun `onTitleChange with blank title shows error`() = runTest {
        // First enter a title, then clear it
        viewModel.onTitleChange("Task")
        viewModel.onTitleChange("")

        assertEquals(TaskUiError.TitleRequired, viewModel.uiState.value.titleError)
        assertFalse(viewModel.uiState.value.isValid)
    }

    @Test
    fun `onDescriptionChange updates description`() = runTest {
        viewModel.onDescriptionChange("Task description")

        assertEquals("Task description", viewModel.uiState.value.description)
    }

    @Test
    fun `onPriorityChange updates priority`() = runTest {
        viewModel.onPriorityChange(TaskPriority.HIGH)

        assertEquals(TaskPriority.HIGH, viewModel.uiState.value.priority)
    }

    // =========================================================================
    // TASK CREATION TESTS
    // =========================================================================

    @Test
    fun `onCreateTask with valid form creates task`() = runTest {
        // Given: Valid form data
        viewModel.onTitleChange("Test Task")
        viewModel.onDescriptionChange("Test Description")
        viewModel.onPriorityChange(TaskPriority.HIGH)

        // When: Create task
        viewModel.onCreateTask()

        // Then: Task is created and completion is observable
        assertTrue(viewModel.uiState.value.isCreated)

        // Verify task was added to repository
        taskRepository.tasks.test {
            val tasks = awaitItem()
            assertEquals(1, tasks.size)
            assertEquals("Test Task", tasks.first().title)
            assertEquals("Test Description", tasks.first().description)
            assertEquals(TaskPriority.HIGH, tasks.first().priority)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onCreateTask with blank title shows error`() = runTest {
        // Given: Invalid form (no title)

        // When: Try to create task
        viewModel.onCreateTask()

        // Then: Error is shown without completion
        assertFalse(viewModel.uiState.value.isCreated)
        assertEquals(TaskUiError.TitleRequired, viewModel.uiState.value.titleError)
    }

    @Test
    fun `completed creation remains observable and cannot be submitted twice`() = runTest {
        viewModel.onTitleChange("Task")
        viewModel.onCreateTask()
        viewModel.onCreateTask()

        assertTrue(viewModel.uiState.value.isCreated)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, taskRepository.currentTasks.size)
    }

    @Test
    fun `failed creation keeps draft and permits retry`() = runTest {
        taskRepository.setShouldThrowError(true)
        viewModel.onTitleChange("Keep this draft")
        viewModel.onCreateTask()

        assertFalse(viewModel.uiState.value.isCreated)
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.submitError != null)
        assertEquals("Keep this draft", viewModel.uiState.value.title)

        taskRepository.setShouldThrowError(false)
        viewModel.onCreateTask()
        assertTrue(viewModel.uiState.value.isCreated)
        assertNull(viewModel.uiState.value.submitError)
    }

    @Test
    fun `restored draft populates the form without replaying a submission`() = runTest {
        val savedState = SavedStateHandle(mapOf(
            "title" to "Restored task",
            "description" to "Draft details",
            "priority" to "HIGH",
        ))
        val restored = CreateTaskViewModel(taskRepository, savedState)

        assertEquals("Restored task", restored.uiState.value.title)
        assertEquals("Draft details", restored.uiState.value.description)
        assertEquals(TaskPriority.HIGH, restored.uiState.value.priority)
        assertFalse(restored.uiState.value.isLoading)
        assertFalse(restored.uiState.value.isCreated)
        restored.onTitleChange("Edited task")
        restored.onDescriptionChange("Edited details")
        restored.onPriorityChange(TaskPriority.LOW)
        assertEquals("Edited task", savedState.get<String>("title"))
        assertEquals("Edited details", savedState.get<String>("description"))
        assertEquals("LOW", savedState.get<String>("priority"))
    }

    @Test
    fun `cancellation does not become a submit error`() = runTest {
        taskRepository.setShouldThrowError(true)
        taskRepository.errorToThrow = CancellationException("Screen removed")
        viewModel.onTitleChange("Task")

        viewModel.onCreateTask()

        assertNull(viewModel.uiState.value.submitError)
        assertTrue(taskRepository.currentTasks.isEmpty())
    }
}
