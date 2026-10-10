package com.mtajchert.carom.app.feature.clipboard.impl

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * "Send clipboard to" (docs/CLIENTS.md): the devices the user picked, or null for the default,
 * which is every device whose `receive` rule allows this phone.
 */
interface ClipboardSendTargets {
    val selected: Flow<Set<String>?>
    suspend fun current(): Set<String>? = selected.first()
    suspend fun set(ids: Set<String>?)
}

private val Context.clipboardDataStore by preferencesDataStore(name = "carom_clipboard")

class DataStoreClipboardSendTargets @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClipboardSendTargets {
    override val selected: Flow<Set<String>?> = context.clipboardDataStore.data.map { it[KEY] }

    override suspend fun set(ids: Set<String>?) {
        context.clipboardDataStore.edit { prefs -> if (ids == null) prefs.remove(KEY) else prefs[KEY] = ids }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("send_to")
    }
}
