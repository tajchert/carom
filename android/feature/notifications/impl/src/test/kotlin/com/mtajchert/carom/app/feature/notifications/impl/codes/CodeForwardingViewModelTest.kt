package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CodeForwardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val access = FakeCodeForwardingAccess()
    private val associations = FakeAssociationApi()
    private val vm by lazy { CodeForwardingViewModel(associations, access) }
    private val sender: IntentSender = PendingIntent.getActivity(
        ApplicationProvider.getApplicationContext<Context>(),
        0,
        Intent(),
        PendingIntent.FLAG_IMMUTABLE,
    ).intentSender

    @Test
    fun `below Android 15 there is nothing to do`() {
        access.current = CodeForwardingState.UNSUPPORTED
        vm.open(stillRedacted = false)
        assertEquals(CodeForwardingUi.Unsupported, vm.ui.value)
    }

    @Test
    fun `already enabled opens on done unless codes are still hidden`() {
        access.current = CodeForwardingState.ENABLED
        vm.open(stillRedacted = false)
        assertEquals(CodeForwardingUi.Enabled, vm.ui.value)

        val again = CodeForwardingViewModel(associations, access)
        again.open(stillRedacted = true)
        assertEquals(CodeForwardingUi.UseAdb(access.adbCommand()), again.ui.value)
    }

    @Test
    fun `an association that did not unlock codes goes straight to the adb command`() {
        associations.associated = true
        vm.open(stillRedacted = true)
        assertEquals(CodeForwardingUi.UseAdb(access.adbCommand()), vm.ui.value)
    }

    @Test
    fun `hidden codes without an association open on the intro`() {
        vm.open(stillRedacted = true)
        assertEquals(CodeForwardingUi.Intro, vm.ui.value)
    }

    @Test
    fun `opening twice keeps the state the first open chose`() {
        vm.open(stillRedacted = false)
        vm.enable()
        vm.open(stillRedacted = false)
        assertEquals(CodeForwardingUi.Working, vm.ui.value)
    }

    @Test
    fun `enable creates the association and settles once Android flips the switch`() = runTest(dispatcher) {
        access.queued += CodeForwardingState.NOT_ENABLED
        access.current = CodeForwardingState.ENABLED

        vm.enable()
        assertEquals(CodeForwardingUi.Working, vm.ui.value)
        runCurrent()
        assertEquals(CodeForwardingUi.Working, vm.ui.value) // first check: not yet
        advanceTimeBy(CodeForwardingViewModel.SETTLE_MS)
        runCurrent()
        assertEquals(CodeForwardingUi.Enabled, vm.ui.value)
        assertEquals(1, associations.calls)
    }

    @Test
    fun `codes still hidden after the association shows the adb command`() = runTest(dispatcher) {
        access.current = CodeForwardingState.NOT_ENABLED
        vm.enable()
        runCurrent()
        advanceTimeBy(CodeForwardingViewModel.SETTLE_MS)
        runCurrent()
        assertEquals(CodeForwardingUi.UseAdb(access.adbCommand()), vm.ui.value)
    }

    @Test
    fun `a refused association shows the adb command`() = runTest(dispatcher) {
        associations.outcome = AssociationOutcome.Failed("refused")
        vm.enable()
        runCurrent()
        assertEquals(CodeForwardingUi.UseAdb(access.adbCommand()), vm.ui.value)
    }

    @Test
    fun `the consent dialog is launched once and its result is verified`() = runTest(dispatcher) {
        associations.outcome = AssociationOutcome.NeedsConsent(sender)
        vm.enable()
        runCurrent()
        assertEquals(CodeForwardingUi.Consent(sender), vm.ui.value)

        vm.consentLaunched()
        assertEquals(CodeForwardingUi.Working, vm.ui.value)

        access.current = CodeForwardingState.ENABLED
        vm.onConsentResult(ok = true)
        runCurrent()
        assertEquals(CodeForwardingUi.Enabled, vm.ui.value)
    }

    @Test
    fun `cancelling the consent dialog returns to the intro`() = runTest(dispatcher) {
        associations.outcome = AssociationOutcome.NeedsConsent(sender)
        vm.enable()
        runCurrent()
        vm.consentLaunched()
        vm.onConsentResult(ok = false)
        assertEquals(CodeForwardingUi.Intro, vm.ui.value)
    }

    @Test
    fun `a second tap while working does not start another association`() = runTest(dispatcher) {
        vm.enable()
        vm.enable()
        runCurrent()
        assertEquals(1, associations.calls)
    }
}
