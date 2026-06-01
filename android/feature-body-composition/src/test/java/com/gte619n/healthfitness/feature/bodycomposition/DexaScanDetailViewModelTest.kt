package com.gte619n.healthfitness.feature.bodycomposition

import androidx.lifecycle.SavedStateHandle
import com.gte619n.healthfitness.feature.bodycomposition.detail.DexaScanDetailViewModel
import com.gte619n.healthfitness.feature.bodycomposition.nav.BodyCompositionRoutes
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DexaScanDetailViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private fun handle(scanId: String = "scan-1") =
        SavedStateHandle(mapOf(BodyCompositionRoutes.ARG_SCAN_ID to scanId))

    @Test
    fun `load populates the scan`() = runTest(mainRule.dispatcher) {
        val repo = FakeDexaScanRepository(scan = sampleScan())
        val vm = DexaScanDetailViewModel(repo, SnackbarController(), FakeUnitPreferencesRepository(), handle())
        advanceUntilIdle()

        val state = vm.state.value
        assertNotNull(state.scan)
        assertEquals("scan-1", state.scan!!.scanId)
        assertNull(state.error)
    }

    @Test
    fun `load failure sets error`() = runTest(mainRule.dispatcher) {
        val repo = FakeDexaScanRepository(failGet = true)
        val vm = DexaScanDetailViewModel(repo, SnackbarController(), FakeUnitPreferencesRepository(), handle())
        advanceUntilIdle()

        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.scan)
    }

    @Test
    fun `patchField applies optimistic update then settles to server result`() =
        runTest(mainRule.dispatcher) {
            val server = sampleScan().copy(totalMassLb = 200.0)
            val repo = FakeDexaScanRepository(scan = server)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            repo.patchGate = gate
            val vm = DexaScanDetailViewModel(repo, SnackbarController(), FakeUnitPreferencesRepository(), handle())
            advanceUntilIdle()
            // load() returned `server` so totalMassLb is 200.0.

            vm.patchField("totalMassLb", 123.4)
            // The repo is gated mid-patch, so the optimistic value is visible.
            assertEquals(123.4, vm.state.value.scan!!.totalMassLb!!, 0.001)

            // Release the patch; it settles to the server response (200.0).
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(200.0, vm.state.value.scan!!.totalMassLb!!, 0.001)
            assertEquals(Triple("scan-1", "totalMassLb", 123.4), repo.lastPatch)
        }

    @Test
    fun `patchField failure reverts and fires snackbar`() = runTest(mainRule.dispatcher) {
        val repo = FakeDexaScanRepository(scan = sampleScan(), failPatch = true)
        val snackbar = mockk<SnackbarController>(relaxed = true)
        val vm = DexaScanDetailViewModel(repo, snackbar, FakeUnitPreferencesRepository(), handle())
        advanceUntilIdle()
        val original = vm.state.value.scan!!.totalMassLb

        vm.patchField("totalMassLb", 999.0)
        advanceUntilIdle()

        assertEquals(original!!, vm.state.value.scan!!.totalMassLb!!, 0.001)
        verify { snackbar.show(match<String> { it.startsWith("Couldn't save") }) }
    }

    @Test
    fun `region patch updates nested field optimistically`() = runTest(mainRule.dispatcher) {
        val repo = FakeDexaScanRepository(scan = sampleScan())
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        repo.patchGate = gate
        val vm = DexaScanDetailViewModel(repo, mockk(relaxed = true), FakeUnitPreferencesRepository(), handle())
        advanceUntilIdle()

        vm.patchField("trunk.leanTissueLb", 50.0)
        // Gated mid-patch: the nested optimistic value is visible.
        assertEquals(50.0, vm.state.value.scan!!.trunk!!.leanTissueLb!!, 0.001)
        gate.complete(Unit)
    }

    @Test
    fun `delete success fires snackbar and calls onDone`() = runTest(mainRule.dispatcher) {
        val repo = FakeDexaScanRepository(scan = sampleScan())
        val snackbar = mockk<SnackbarController>(relaxed = true)
        val vm = DexaScanDetailViewModel(repo, snackbar, FakeUnitPreferencesRepository(), handle())
        advanceUntilIdle()

        var done = false
        vm.delete { done = true }
        advanceUntilIdle()

        assertTrue(done)
        assertEquals(1, repo.deleteCount)
        verify { snackbar.show("Scan deleted") }
    }

    @Test
    fun `delete failure fires error snackbar`() = runTest(mainRule.dispatcher) {
        val repo = FakeDexaScanRepository(scan = sampleScan(), failDelete = true)
        val snackbar = mockk<SnackbarController>(relaxed = true)
        val vm = DexaScanDetailViewModel(repo, snackbar, FakeUnitPreferencesRepository(), handle())
        advanceUntilIdle()

        var done = false
        vm.delete { done = true }
        advanceUntilIdle()

        assertTrue(!done)
        verify { snackbar.show(match<String> { it.startsWith("Couldn't delete") }) }
    }
}
