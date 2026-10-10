package com.mtajchert.carom.app.feature.tasks.impl

import com.mtajchert.carom.app.feature.tasks.impl.TaskUiError
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.Task
import com.mtajchert.carom.app.core.model.TaskPriority
import com.mtajchert.carom.app.feature.tasks.impl.create.CreateTaskContent
import com.mtajchert.carom.app.feature.tasks.impl.create.CreateTaskUiState
import com.mtajchert.carom.app.feature.tasks.impl.detail.TaskDetailContent
import com.mtajchert.carom.app.feature.tasks.impl.detail.TaskDetailUiState
import com.mtajchert.carom.app.feature.tasks.impl.list.TaskListContent
import com.mtajchert.carom.app.feature.tasks.impl.list.TaskListUiState
import java.time.ZoneOffset
import kotlin.time.Instant

private val screenshotInstant = Instant.parse("2026-01-15T10:30:00Z")

@Preview(showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
private annotation class PhoneScreenPreview

@Composable
private fun TaskListScreenshotContent(uiState: TaskListUiState) {
    CaromTheme(dynamicColor = false) {
        TaskListContent(
            uiState = uiState,
            onTaskClick = {},
            onTaskCheckedChange = { _, _ -> },
            onDeleteTask = {},
            onRetry = {},
            onCreateClick = {},
        )
    }
}

@PreviewTest
@PhoneScreenPreview
@Composable
fun TaskListLoadingScreenshotTest() = TaskListScreenshotContent(TaskListUiState.Loading)

@PreviewTest
@PhoneScreenPreview
@Composable
fun TaskListEmptyScreenshotTest() = TaskListScreenshotContent(TaskListUiState.Empty)

@PreviewTest
@PhoneScreenPreview
@Composable
fun TaskListPopulatedScreenshotTest() = TaskListScreenshotContent(
    TaskListUiState.Success(
        tasks = listOf(
            task(
                id = "write-tests",
                title = "Write screenshot tests",
                description = "Cover reusable components and complete screen states.",
                priority = TaskPriority.HIGH,
            ),
            task(
                id = "review-references",
                title = "Review reference images",
                description = "Confirm the visual change is intentional.",
                isCompleted = true,
                priority = TaskPriority.MEDIUM,
            ),
        ),
    ),
)

@PreviewTest
@PhoneScreenPreview
@Composable
fun TaskListErrorScreenshotTest() = TaskListScreenshotContent(
    TaskListUiState.Error(TaskUiError.LoadTasksFailed),
)

@Composable
private fun CreateTaskScreenshotContent(uiState: CreateTaskUiState) {
    CaromTheme(dynamicColor = false) {
        CreateTaskContent(
            uiState = uiState,
            onBackClick = {},
            onTitleChange = {},
            onDescriptionChange = {},
            onPriorityChange = {},
            onCreateClick = {},
        )
    }
}

@PreviewTest
@PhoneScreenPreview
@Composable
fun CreateTaskEmptyScreenshotTest() = CreateTaskScreenshotContent(CreateTaskUiState())

@PreviewTest
@PhoneScreenPreview
@Composable
fun CreateTaskFilledScreenshotTest() = CreateTaskScreenshotContent(
    CreateTaskUiState(
        title = "Review screenshot references",
        description = "Inspect component and full-screen output before committing.",
        priority = TaskPriority.HIGH,
    ),
)

@PreviewTest
@PhoneScreenPreview
@Composable
fun CreateTaskValidationErrorScreenshotTest() = CreateTaskScreenshotContent(
    CreateTaskUiState(titleError = TaskUiError.TitleRequired),
)

@PreviewTest
@PhoneScreenPreview
@Composable
fun CreateTaskLoadingScreenshotTest() = CreateTaskScreenshotContent(
    CreateTaskUiState(title = "Creating task", isLoading = true),
)

@Composable
private fun TaskDetailScreenshotContent(uiState: TaskDetailUiState) {
    CaromTheme(dynamicColor = false) {
        TaskDetailContent(
            uiState = uiState,
            onBackClick = {},
            onToggleCompletion = {},
            onDelete = {},
            zoneId = ZoneOffset.UTC,
        )
    }
}

@PreviewTest
@PhoneScreenPreview
@Composable
fun TaskDetailLoadingScreenshotTest() = TaskDetailScreenshotContent(TaskDetailUiState.Loading)

@PreviewTest
@PhoneScreenPreview
@Composable
fun TaskDetailSuccessScreenshotTest() = TaskDetailScreenshotContent(
    TaskDetailUiState.Success(
        task(
            id = "detail-open",
            title = "Add visual regression coverage",
            description = "Protect the complete screen layout with a deterministic reference.",
            priority = TaskPriority.HIGH,
        ),
    ),
)

@PreviewTest
@PhoneScreenPreview
@Composable
fun TaskDetailCompletedScreenshotTest() = TaskDetailScreenshotContent(
    TaskDetailUiState.Success(
        task(
            id = "detail-complete",
            title = "Generate reference images",
            description = "The reviewed output is ready.",
            isCompleted = true,
            priority = TaskPriority.MEDIUM,
        ),
    ),
)

@PreviewTest
@PhoneScreenPreview
@Composable
fun TaskDetailNotFoundScreenshotTest() = TaskDetailScreenshotContent(TaskDetailUiState.NotFound)

@PreviewTest
@PhoneScreenPreview
@Composable
fun TaskDetailErrorScreenshotTest() = TaskDetailScreenshotContent(
    TaskDetailUiState.Error(TaskUiError.LoadTaskFailed),
)

private fun task(
    id: String,
    title: String,
    description: String,
    isCompleted: Boolean = false,
    priority: TaskPriority,
): Task = Task(
    id = id,
    title = title,
    description = description,
    isCompleted = isCompleted,
    createdAt = screenshotInstant,
    priority = priority,
)

@PreviewTest
@Preview(name = "Dark validation", widthDp = 412, heightDp = 892, apiLevel = 36,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun CreateTaskDarkValidationScreenshotTest() = CreateTaskScreenshotContent(
    CreateTaskUiState(description = "Keep this description while fixing the missing title.",
        titleError = TaskUiError.TitleRequired, priority = TaskPriority.HIGH),
)

@PreviewTest
@Preview(name = "Long detail large text", widthDp = 412, heightDp = 892, apiLevel = 36,
    fontScale = 1.5f, showBackground = true)
@Composable
fun TaskDetailLongContentLargeFontScreenshotTest() = TaskDetailScreenshotContent(
    TaskDetailUiState.Success(task(
        id = "long-detail", title = "Review the complete dependency and database migration report",
        description = "Verify existing tasks survive the Room migration. Review the startup profile, " +
            "benchmark measurements, and screenshots on both light and dark themes. " +
            "Check long content remains readable when the user increases their system font size.",
        priority = TaskPriority.HIGH,
    )),
)
