package com.mtajchert.carom.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mtajchert.carom.app.core.model.TaskPriority

@Composable
fun TaskPriority.localizedLabel(): String = stringResource(when (this) {
    TaskPriority.HIGH -> R.string.priority_high
    TaskPriority.MEDIUM -> R.string.priority_medium
    TaskPriority.LOW -> R.string.priority_low
})
