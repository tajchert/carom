package com.mtajchert.carom.app.feature.tasks.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Errors cross the state boundary as types; only the UI chooses localized text. */
enum class TaskUiError {
    TitleRequired, CreateFailed, LoadTasksFailed, LoadTaskFailed, SaveFailed, ImportFailed, PreferencesSaveFailed, PreferencesLoadFailed;

    @Composable
    fun message(): String = stringResource(when (this) {
        TitleRequired -> R.string.error_titlerequired
        CreateFailed -> R.string.error_createfailed
        LoadTasksFailed -> R.string.error_loadtasksfailed
        LoadTaskFailed -> R.string.error_loadtaskfailed
        SaveFailed -> R.string.error_savefailed
        ImportFailed -> R.string.error_importfailed
        PreferencesSaveFailed -> R.string.error_preferencessavefailed
        PreferencesLoadFailed -> R.string.error_preferencesloadfailed
    })
}
