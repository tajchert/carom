package com.mtajchert.carom.app.feature.tasks.impl

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.data.repository.TaskRepository
import com.mtajchert.carom.app.core.model.TaskPriority
import com.mtajchert.carom.app.core.testing.repository.FakeTaskRepository
import com.mtajchert.carom.app.core.testing.testTask
import com.mtajchert.carom.app.feature.tasks.api.TaskDetailRoute
import com.mtajchert.carom.app.feature.tasks.impl.create.CreateTaskScreen
import com.mtajchert.carom.app.feature.tasks.impl.create.CreateTaskViewModel
import com.mtajchert.carom.app.feature.tasks.impl.detail.TaskDetailScreen
import com.mtajchert.carom.app.feature.tasks.impl.detail.TaskDetailViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CompletionNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun createCompletionWhileStopped_navigatesOnceAfterResume() {
        val writes = FakeTaskRepository()
        val releaseWrite = CompletableDeferred<Unit>()
        val repository = object : TaskRepository by writes {
            override suspend fun createTask(title: String, description: String, priority: TaskPriority) = run {
                releaseWrite.await()
                writes.createTask(title, description, priority)
            }
        }
        val viewModel = CreateTaskViewModel(repository, SavedStateHandle())
        val navigations = AtomicInteger()
        compose.setContent {
            CreateTaskScreen({}, { navigations.incrementAndGet() }, viewModel)
        }
        compose.runOnIdle {
            viewModel.onTitleChange("Background completion")
            viewModel.onCreateTask()
        }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        releaseWrite.complete(Unit)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(0, navigations.get())

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil { navigations.get() == 1 }
        compose.waitForIdle()
        assertEquals(1, navigations.get())
        compose.runOnIdle { viewModel.viewModelScope.cancel() }
    }

    @Test
    fun deleteCompletionWhileStopped_navigatesOnceAfterResume() {
        val writes = FakeTaskRepository().apply { setTasks(listOf(testTask(id = "task"))) }
        val releaseWrite = CompletableDeferred<Unit>()
        val repository = object : TaskRepository by writes {
            override suspend fun deleteTask(taskId: String) {
                releaseWrite.await()
                writes.deleteTask(taskId)
            }
        }
        val viewModel = TaskDetailViewModel("task", repository)
        val navigations = AtomicInteger()
        compose.setContent {
            // Named arguments: a positional ViewModel argument silently binds to
            // whichever optional parameter happens to sit in that slot.
            TaskDetailScreen(
                route = TaskDetailRoute("task"),
                onBackClick = { navigations.incrementAndGet() },
                viewModel = viewModel,
            )
        }
        compose.runOnIdle { viewModel.onDelete() }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        releaseWrite.complete(Unit)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(0, navigations.get())

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil { navigations.get() == 1 }
        compose.waitForIdle()
        assertEquals(1, navigations.get())
        compose.runOnIdle { viewModel.viewModelScope.cancel() }
    }

    @Test
    fun removedCreateRoute_doesNotNavigateWhenWriteFinishes() {
        val writes = FakeTaskRepository()
        val releaseWrite = CompletableDeferred<Unit>()
        val repository = object : TaskRepository by writes {
            override suspend fun createTask(title: String, description: String, priority: TaskPriority) = run {
                releaseWrite.await()
                writes.createTask(title, description, priority)
            }
        }
        val viewModel = CreateTaskViewModel(repository, SavedStateHandle())
        val navigations = AtomicInteger()
        val showRoute = mutableStateOf(true)
        compose.setContent {
            if (showRoute.value) CreateTaskScreen({}, { navigations.incrementAndGet() }, viewModel)
        }
        compose.runOnIdle {
            viewModel.onTitleChange("Removed route")
            viewModel.onCreateTask()
            showRoute.value = false
        }
        compose.waitForIdle()
        releaseWrite.complete(Unit)
        compose.waitUntil { viewModel.uiState.value.isCreated }
        compose.waitForIdle()
        assertEquals(0, navigations.get())
        compose.runOnIdle { viewModel.viewModelScope.cancel() }
    }
}
