package com.mtajchert.carom.app.feature.tasks.impl.list

import app.cash.turbine.test
import com.mtajchert.carom.app.core.data.repository.TaskRepository
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.domain.GetSortedTasksUseCase
import com.mtajchert.carom.app.core.model.TaskPriority
import com.mtajchert.carom.app.core.testing.repository.FakeTaskRepository
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.testTask
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// =============================================================================
// VIEWMODEL UNIT TESTS
// =============================================================================
// Demonstrates proper ViewModel testing patterns:
//
// ## Key Patterns
//
// 1. **MainDispatcherRule**: Replaces Main dispatcher for testing
// 2. **Turbine**: Tests StateFlow emissions over time
// 3. **Fake Repositories**: Simple, predictable test doubles
// 4. **No Mocking**: Fakes are preferred over Mockito/Mockk
//
// ## Test Structure
//
// - @Before: Create fresh fakes and ViewModel
// - Each test: Set up fakes → trigger action → assert state
//
// ## Turbine Usage
//
// Turbine provides `test {}` block for testing Flows:
// - `awaitItem()`: Get next emission
// - `cancelAndIgnoreRemainingEvents()`: Clean up
//
// ## StateFlow Conflation Gotcha
//
// With MainDispatcherRule's UnconfinedTestDispatcher, the `stateIn` sharing
// coroutine runs eagerly and synchronously the moment Turbine subscribes.
// By the time the first item is delivered, the intermediate Loading value
// has already been conflated away - the first `awaitItem()` returns the
// settled state (Success/Empty/Error), NOT Loading. Don't write
// `awaitItem() // skip Loading` - it will eat the state you wanted and the
// next `awaitItem()` will time out.
// =============================================================================

class TaskListViewModelTest {

    @Test
    fun `failed delete preserves the visible list`() = runTest {
        taskRepository.setTasks(listOf(testTask(id = "task")))
        viewModel = createViewModel()
        viewModel.uiState.test {
            assertTrue(awaitItem() is TaskListUiState.Success)
            taskRepository.setShouldThrowError(true)
            viewModel.onDeleteTask("task")
            val failed = awaitItem() as TaskListUiState.Success
            assertTrue(failed.errorMessage != null)
            assertEquals("task", failed.tasks.single().id)
            assertEquals("task", taskRepository.currentTasks.single().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recreating the list preserves local tasks instead of replacing them with a remote snapshot`() = runTest {
        taskRepository.setTasks(listOf(testTask(id = "saved", title = "Saved locally")))
        val destructiveRemote = object : TaskRepository by taskRepository {
            override suspend fun refreshTasks() { taskRepository.setTasks(emptyList()) }
        }
        repeat(2) {
            val recreated = TaskListViewModel(getSortedTasksUseCase, destructiveRemote)
            recreated.uiState.test {
                val state = awaitItem()
                assertTrue("Opening the list must retain saved tasks", state is TaskListUiState.Success)
                assertEquals("saved", (state as TaskListUiState.Success).tasks.single().id)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var taskRepository: FakeTaskRepository
    private lateinit var userPreferencesRepository: FakeUserPreferencesRepository
    private lateinit var getSortedTasksUseCase: GetSortedTasksUseCase
    private lateinit var viewModel: TaskListViewModel

    @Before
    fun setup() {
        taskRepository = FakeTaskRepository()
        userPreferencesRepository = FakeUserPreferencesRepository()
        getSortedTasksUseCase = GetSortedTasksUseCase(
            taskRepository = taskRepository,
            userPreferencesRepository = userPreferencesRepository,
        )
    }

    private fun createViewModel(): TaskListViewModel {
        return TaskListViewModel(
            getSortedTasksUseCase = getSortedTasksUseCase,
            taskRepository = taskRepository,
        )
    }

    // =========================================================================
    // INITIAL STATE TESTS
    // =========================================================================

    @Test
    fun `initial state is Loading`() = runTest {
        viewModel = createViewModel()

        assertEquals(TaskListUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun `state is Empty when no tasks exist`() = runTest {
        // Given: Empty repository
        taskRepository.setTasks(emptyList())

        // When: ViewModel is created
        viewModel = createViewModel()

        // Then: State settles to Empty
        // (Loading is conflated away - see "StateFlow Conflation Gotcha" above)
        viewModel.uiState.test {
            assertEquals(TaskListUiState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state is Success when tasks exist`() = runTest {
        // Given: Repository with tasks
        val tasks = listOf(
            testTask(id = "1", title = "Task 1"),
            testTask(id = "2", title = "Task 2"),
        )
        taskRepository.setTasks(tasks)

        // When: ViewModel is created
        viewModel = createViewModel()

        // Then: State settles to Success with tasks
        viewModel.uiState.test {
            val successState = awaitItem()
            assertTrue(successState is TaskListUiState.Success)
            assertEquals(2, (successState as TaskListUiState.Success).tasks.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // ACTION TESTS
    // =========================================================================

    @Test
    fun `onTaskCheckedChange updates task completion`() = runTest {
        // Given: Repository with incomplete task
        val task = testTask(id = "1", isCompleted = false)
        taskRepository.setTasks(listOf(task))
        viewModel = createViewModel()

        // Wait for initial state
        viewModel.uiState.test {
            awaitItem() // Settled Success state

            // When: Toggle completion
            viewModel.onTaskCheckedChange("1", true)

            // Then: Task is updated
            val updatedState = awaitItem() as TaskListUiState.Success
            assertTrue(updatedState.tasks.first().isCompleted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDeleteTask removes task from list`() = runTest {
        // Given: Repository with tasks
        val tasks = listOf(
            testTask(id = "1", title = "Task 1"),
            testTask(id = "2", title = "Task 2"),
        )
        taskRepository.setTasks(tasks)
        viewModel = createViewModel()

        // Wait for initial state
        viewModel.uiState.test {
            awaitItem() // Settled Success state with 2 tasks

            // When: Delete first task
            viewModel.onDeleteTask("1")

            // Then: Only one task remains
            val updatedState = awaitItem() as TaskListUiState.Success
            assertEquals(1, updatedState.tasks.size)
            assertEquals("2", updatedState.tasks.first().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting last task results in Empty state`() = runTest {
        // Given: Repository with single task
        val task = testTask(id = "1")
        taskRepository.setTasks(listOf(task))
        viewModel = createViewModel()

        viewModel.uiState.test {
            awaitItem() // Settled Success state

            // When: Delete the only task
            viewModel.onDeleteTask("1")

            // Then: State becomes Empty
            assertEquals(TaskListUiState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // ERROR HANDLING TESTS
    // =========================================================================

    @Test
    fun `state is Error when repository throws exception`() = runTest {
        // Given: Repository that will throw
        taskRepository.setShouldThrowError(true)

        // When: ViewModel is created
        viewModel = createViewModel()

        // Then: State settles to Error
        viewModel.uiState.test {
            val errorState = awaitItem()
            assertTrue(errorState is TaskListUiState.Error)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
