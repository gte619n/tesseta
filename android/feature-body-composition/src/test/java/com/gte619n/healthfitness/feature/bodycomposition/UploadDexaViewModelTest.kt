package com.gte619n.healthfitness.feature.bodycomposition

import com.gte619n.healthfitness.domain.bodycomposition.DexaUploadEvent
import com.gte619n.healthfitness.feature.bodycomposition.upload.UploadDexaViewModel
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadDexaViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @Test
    fun `file above 25 MB short-circuits to Failed without network`() =
        runTest(mainRule.dispatcher) {
            val repo = FakeDexaScanRepository()
            val snackbar = mockk<SnackbarController>(relaxed = true)
            val vm = UploadDexaViewModel(repo, snackbar)

            val tooBig = ByteArray((UploadDexaViewModel.MAX_PDF_BYTES + 1).toInt())
            vm.upload("big.pdf", tooBig)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is UploadDexaViewModel.UiState.Failed)
            assertTrue((state as UploadDexaViewModel.UiState.Failed).error.contains("25 MB"))
            verify { snackbar.showError(match<String> { it.contains("25 MB") }) }
        }

    @Test
    fun `phase events produce matching InProgress states then Complete`() =
        runTest(mainRule.dispatcher) {
            val repo = FakeDexaScanRepository(
                uploadEvents = listOf(
                    DexaUploadEvent.Phase("uploading", "Uploading"),
                    DexaUploadEvent.Phase("extracting", "Extracting"),
                    DexaUploadEvent.Phase("saving", "Saving"),
                    DexaUploadEvent.Complete(sampleScan("new-scan")),
                ),
            )
            val vm = UploadDexaViewModel(repo, mockk(relaxed = true))

            vm.upload("ok.pdf", ByteArray(10))
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is UploadDexaViewModel.UiState.Complete)
            assertEquals("new-scan", (state as UploadDexaViewModel.UiState.Complete).scanId)
        }

    @Test
    fun `failed event transitions to Failed`() = runTest(mainRule.dispatcher) {
        val repo = FakeDexaScanRepository(
            uploadEvents = listOf(
                DexaUploadEvent.Phase("uploading", null),
                DexaUploadEvent.Failed("extraction blew up"),
            ),
        )
        val vm = UploadDexaViewModel(repo, mockk(relaxed = true))

        vm.upload("bad.pdf", ByteArray(10))
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is UploadDexaViewModel.UiState.Failed)
        assertEquals("extraction blew up", (state as UploadDexaViewModel.UiState.Failed).error)
    }
}
