package com.mtajchert.carom.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.Task
import com.mtajchert.carom.app.core.model.TaskPriority
import kotlin.time.Instant

private val screenshotInstant = Instant.parse("2026-01-15T10:30:00Z")

@PreviewTest
@Preview(name = "Priority badges", showBackground = true, widthDp = 360)
@Composable
fun PriorityBadgesScreenshotTest() {
    CaromTheme(dynamicColor = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TaskPriority.entries.forEach { priority ->
                PriorityBadge(priority = priority)
            }
        }
    }
}
@PreviewTest
@Preview(name = "Task cards", showBackground = true, widthDp = 412)
@Composable
fun TaskCardsScreenshotTest() {
    CaromTheme(dynamicColor = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TaskCard(
                task = Task(
                    id = "open-task",
                    title = "Review visual regression report",
                    description = "Compare the intended layout with the generated reference.",
                    isCompleted = false,
                    createdAt = screenshotInstant,
                    priority = TaskPriority.HIGH,
                ),
                onCheckedChange = {},
                onClick = {},
                onDeleteClick = {},
            )
            TaskCard(
                task = Task(
                    id = "completed-task",
                    title = "Create reference images",
                    description = "Reviewed and approved",
                    isCompleted = true,
                    createdAt = screenshotInstant,
                    priority = TaskPriority.MEDIUM,
                ),
                onCheckedChange = {},
                onClick = {},
                onDeleteClick = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Long task light", widthDp = 412, fontScale = 1.5f, apiLevel = 36, showBackground = true)
@Preview(name = "Long task dark", widthDp = 412, fontScale = 1.5f, apiLevel = 36,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun LongTaskCardLargeFontScreenshotTest() {
    CaromTheme(dynamicColor = false) {
        TaskCard(
            task = Task("long-task", "Review the complete Android refresh and migration verification report",
                "A long description protects wrapping and truncation when larger text is enabled.",
                false, screenshotInstant, TaskPriority.HIGH),
            onCheckedChange = {}, onClick = {}, onDeleteClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
