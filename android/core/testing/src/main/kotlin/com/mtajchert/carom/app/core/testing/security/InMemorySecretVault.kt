package com.mtajchert.carom.app.core.testing.security

import com.mtajchert.carom.app.core.security.SecretVault
import com.mtajchert.carom.app.core.security.VaultContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [SecretVault] in memory, for JVM tests. */
class InMemorySecretVault(initial: VaultContents = VaultContents()) : SecretVault {
    private val mutex = Mutex()
    private val state = MutableStateFlow<VaultContents?>(initial)
    override val contents: StateFlow<VaultContents?> = state

    var wiped = false
        private set

    override suspend fun load(): VaultContents = state.value ?: VaultContents()

    override suspend fun update(transform: (VaultContents) -> VaultContents): VaultContents = mutex.withLock {
        transform(load()).also { state.value = it }
    }

    override suspend fun wipe() {
        wiped = true
        state.value = VaultContents()
    }
}
