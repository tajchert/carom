package com.mtajchert.carom.app.feature.tasks.impl.create

import androidx.lifecycle.SavedStateHandle
import com.mtajchert.carom.app.core.data.repository.TaskRepository
import com.mtajchert.carom.app.core.model.TaskPriority
import com.mtajchert.carom.app.core.testing.repository.FakeTaskRepository
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateTaskSubmissionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun `rapid taps queue one submission and completion survives absent collectors`() = runTest {
        val storedTasks = FakeTaskRepository()
        val releaseWrite = CompletableDeferred<Unit>()
        val repository = object : TaskRepository by storedTasks {
            override suspend fun createTask(
                title: String,
                description: String,
                priority: TaskPriority,
            ) = run {
                releaseWrite.await()
                storedTasks.createTask(title, description, priority)
            }
        }
        val viewModel = CreateTaskViewModel(repository, SavedStateHandle())
        viewModel.onTitleChange("One task")

        // Neither launch has had a chance to run yet.
        viewModel.onCreateTask()
        viewModel.onCreateTask()
        assertTrue(viewModel.uiState.value.isLoading)
        runCurrent()
        assertTrue(storedTasks.currentTasks.isEmpty())
        assertFalse(viewModel.uiState.value.isCreated)

        releaseWrite.complete(Unit)
        runCurrent()

        assertEquals(1, storedTasks.currentTasks.size)
        assertEquals("One task", storedTasks.currentTasks.single().title)
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.isCreated)
    }
}
