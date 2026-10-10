package com.mtajchert.carom.app.feature.tasks.impl

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.preview.FormFactorPreviews
import com.mtajchert.carom.app.core.designsystem.preview.LargeFontPreview
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

// =============================================================================
// ADAPTIVE SCREEN SCREENSHOTS
// =============================================================================
// These tests answer a different question than the ones in
// TasksScreenScreenshotTests, which cover every state at phone width. Here a
// small number of representative, content-rich states are rendered across every
// supported window size and at a 1.5 font scale.
//
// What to look for when reviewing these references:
//
// - Line length. Body text stretched across a 1280dp window is unreadable; a
//   real product constrains content width or moves to a multi-pane layout.
// - Wasted space. A single-column list on a tablet is the signal to adopt an
//   adaptive grid and a list-detail layout.
// - Clipping and overlap at font scale 1.5.
//
// These references are deliberately a "before" picture for the adaptive work.
// They are not an assertion that the current layouts are correct on large
// windows - they exist so that regressions and improvements are both visible.
// =============================================================================

private val screenshotInstant = Instant.parse("2026-01-15T10:30:00Z")

@Composable
private fun PopulatedTaskList() {
    CaromTheme {
        TaskListContent(
            uiState = TaskListUiState.Success(
                tasks = listOf(
                    task(
                        id = "adaptive-list",
                        title = "Check the list on every window size",
                        description = "A single column stretched to tablet width wastes space.",
                        priority = TaskPriority.HIGH,
                    ),
                    task(
                        id = "adaptive-detail",
                        title = "Plan the list-detail layout",
                        description = "Wide windows should show list and detail together.",
                        isCompleted = true,
                        priority = TaskPriority.MEDIUM,
                    ),
                    task(
                        id = "adaptive-fold",
                        title = "Verify the folded cover display",
                        description = "",
                        priority = TaskPriority.LOW,
                    ),
                ),
            ),
            onTaskClick = {},
            onTaskCheckedChange = { _, _ -> },
            onDeleteTask = {},
            onRetry = {},
            onCreateClick = {},
        )
    }
}

@PreviewTest
@FormFactorPreviews
@Composable
fun TaskListPopulatedFormFactorScreenshotTest() = PopulatedTaskList()

@PreviewTest
@LargeFontPreview
@Composable
fun TaskListPopulatedLargeFontScreenshotTest() = PopulatedTaskList()

@Composable
private fun FilledCreateTask() {
    CaromTheme {
        CreateTaskContent(
            uiState = CreateTaskUiState(
                title = "Support foldables",
                description = "Text fields must stay visible when the keyboard opens, " +
                    "on the cover display and on the unfolded inner display.",
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

@PreviewTest
@FormFactorPreviews
@Composable
fun CreateTaskFilledFormFactorScreenshotTest() = FilledCreateTask()

@PreviewTest
@LargeFontPreview
@Composable
fun CreateTaskFilledLargeFontScreenshotTest() = FilledCreateTask()

@Composable
private fun OpenTaskDetail() {
    CaromTheme {
        TaskDetailContent(
            uiState = TaskDetailUiState.Success(
                task(
                    id = "adaptive-detail-open",
                    title = "Review detail layout on wide windows",
                    description = "Detail content that runs the full width of a desktop " +
                        "window is hard to read. Constrain the measure or pair it with " +
                        "the list in a two-pane layout.",
                    priority = TaskPriority.HIGH,
                ),
            ),
            onBackClick = {},
            onToggleCompletion = {},
            onDelete = {},
            zoneId = ZoneOffset.UTC,
        )
    }
}

@PreviewTest
@FormFactorPreviews
@Composable
fun TaskDetailSuccessFormFactorScreenshotTest() = OpenTaskDetail()

@PreviewTest
@LargeFontPreview
@Composable
fun TaskDetailSuccessLargeFontScreenshotTest() = OpenTaskDetail()

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
