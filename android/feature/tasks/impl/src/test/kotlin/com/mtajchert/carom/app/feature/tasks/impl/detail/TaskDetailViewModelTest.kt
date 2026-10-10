package com.mtajchert.carom.app.feature.tasks.impl.detail

import com.mtajchert.carom.app.core.testing.repository.FakeTaskRepository
import com.mtajchert.carom.app.core.testing.testTask
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TaskDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `failed deletion keeps the task and does not navigate`() = runTest {
        val repository = FakeTaskRepository().apply {
            setTasks(listOf(testTask(id = "task")))
            setShouldThrowError(true)
        }
        val viewModel = TaskDetailViewModel("task", repository)
        viewModel.onDelete()

        assertFalse(viewModel.actionState.value.isDeleted)
        assertFalse(viewModel.actionState.value.isRunning)
        assertTrue(viewModel.actionState.value.errorMessage != null)
        assertTrue(repository.currentTasks.any { it.id == "task" })
        repository.setShouldThrowError(false)
        viewModel.onDelete()
        assertTrue(repository.currentTasks.isEmpty())
        assertTrue(viewModel.actionState.value.isDeleted)
        assertNull(viewModel.actionState.value.errorMessage)
    }

    @Test
    fun `cancelled deletion does not become an error or successful navigation`() = runTest {
        val repository = FakeTaskRepository().apply {
            setTasks(listOf(testTask(id = "task")))
            setShouldThrowError(true)
            errorToThrow = CancellationException("Screen removed")
        }
        val viewModel = TaskDetailViewModel("task", repository)

        viewModel.onDelete()

        assertFalse(viewModel.actionState.value.isDeleted)
        assertFalse(viewModel.actionState.value.isRunning)
        assertNull(viewModel.actionState.value.errorMessage)
        assertTrue(repository.currentTasks.any { it.id == "task" })
    }
}
