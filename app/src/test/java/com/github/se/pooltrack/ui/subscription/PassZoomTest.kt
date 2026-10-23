package com.github.se.pooltrack.ui.subscription

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.github.se.pooltrack.model.subscription.SavedPassZoom
import com.github.se.pooltrack.utils.FakePassZoomRepository
import com.github.se.pooltrack.utils.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PassZoomTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

  private val viewport = Size(1000f, 2000f)

  @Test
  fun zoomAroundCenter_keepsOffsetZero() {
    val t = PassTransform.IDENTITY.transformedBy(2f, Offset.Zero, Offset.Zero, viewport)
    assertEquals(2f, t.scale, 0f)
    assertEquals(Offset.Zero, t.offset)
  }

  @Test
  fun zoomAwayFromCenter_keepsPointUnderFingersInPlace() {
    val centroid = Offset(400f, -800f)
    val t = PassTransform.IDENTITY.transformedBy(2f, centroid, Offset.Zero, viewport)
    assertEquals(Offset(-400f, 800f), t.offset)
  }

  @Test
  fun scale_isClampedToBounds() {
    val big = PassTransform.IDENTITY.transformedBy(100f, Offset.Zero, Offset.Zero, viewport)
    assertEquals(MAX_ZOOM_SCALE, big.scale, 0f)
    val small = big.transformedBy(0.001f, Offset.Zero, Offset.Zero, viewport)
    assertEquals(MIN_ZOOM_SCALE, small.scale, 0f)
    assertEquals(Offset.Zero, small.offset)
  }

  @Test
  fun pan_isClampedToContentEdges() {
    val t =
        PassTransform(2f, Offset.Zero)
            .transformedBy(1f, Offset.Zero, Offset(9999f, -9999f), viewport)
    assertEquals(Offset(500f, -1000f), t.offset)
  }

  @Test
  fun pan_atMinScale_isIgnored() {
    val t = PassTransform.IDENTITY.transformedBy(1f, Offset.Zero, Offset(50f, 50f), viewport)
    assertEquals(Offset.Zero, t.offset)
  }

  @Test
  fun viewModel_keepsTransform_untilDifferentPassShown() {
    val vm = PassZoomViewModel(FakePassZoomRepository())
    vm.onPassShown("a")
    vm.onGesture(3f, Offset(100f, 100f), Offset.Zero, viewport)
    val zoomed = vm.transform.value
    vm.onPassShown("a")
    assertEquals(zoomed, vm.transform.value)
    vm.onPassShown("b")
    assertEquals(PassTransform.IDENTITY, vm.transform.value)
  }

  @Test
  fun viewModel_savesZoom_onceGestureSettles() = runTest {
    val repository = FakePassZoomRepository()
    val vm = PassZoomViewModel(repository)
    vm.onPassShown("a")

    vm.onGesture(2f, Offset(100f, 200f), Offset.Zero, viewport)
    advanceTimeBy(PassZoomViewModel.SAVE_DELAY_MS - 1)
    runCurrent()
    assertEquals(emptyList<Any>(), repository.saves)
    advanceTimeBy(1)
    runCurrent()

    val offset = vm.transform.value.offset
    assertEquals(
        listOf("a" to SavedPassZoom(2f, offset.x / viewport.width, offset.y / viewport.height)),
        repository.saves,
    )
  }

  @Test
  fun viewModel_savesOnlyTheLastStep_ofAFastGesture() = runTest {
    val repository = FakePassZoomRepository()
    val vm = PassZoomViewModel(repository)
    vm.onPassShown("a")

    repeat(5) {
      vm.onGesture(1.2f, Offset.Zero, Offset.Zero, viewport)
      advanceTimeBy(PassZoomViewModel.SAVE_DELAY_MS / 2)
    }
    advanceTimeBy(PassZoomViewModel.SAVE_DELAY_MS)
    runCurrent()

    assertEquals(1, repository.saves.size)
    assertEquals(vm.transform.value.scale, repository.saves.single().second.scale, 1e-6f)
  }

  @Test
  fun viewModel_persistNow_savesWithoutWaiting() = runTest {
    val repository = FakePassZoomRepository()
    val vm = PassZoomViewModel(repository)
    vm.onPassShown("a")
    vm.onGesture(2f, Offset.Zero, Offset.Zero, viewport)

    vm.persistNow()
    runCurrent()

    assertEquals(1, repository.saves.size)
    // The cancelled delay must not write a second time.
    advanceTimeBy(PassZoomViewModel.SAVE_DELAY_MS * 2)
    runCurrent()
    assertEquals(1, repository.saves.size)
  }

  @Test
  fun viewModel_persistNow_doesNothing_whenNothingChanged() = runTest {
    val repository = FakePassZoomRepository()
    val vm = PassZoomViewModel(repository)
    vm.onPassShown("a")

    vm.persistNow()
    runCurrent()

    assertEquals(emptyList<Any>(), repository.saves)
  }

  @Test
  fun viewModel_restoresSavedZoom_afterRestart() = runTest {
    val repository = FakePassZoomRepository()
    val first = PassZoomViewModel(repository)
    first.onPassShown("a")
    first.onGesture(3f, Offset(100f, 100f), Offset.Zero, viewport)
    first.persistNow()
    runCurrent()

    val restarted = PassZoomViewModel(repository)
    restarted.onPassShown("a")
    restarted.onViewportChanged(viewport)
    runCurrent()

    assertEquals(first.transform.value, restarted.transform.value)
  }

  @Test
  fun viewModel_restoresSavedZoom_whenViewportIsMeasuredAfterTheLoad() = runTest {
    val vm =
        PassZoomViewModel(FakePassZoomRepository(mapOf("a" to SavedPassZoom(2f, 0.25f, -0.5f))))

    vm.onPassShown("a")
    runCurrent()
    assertEquals(PassTransform.IDENTITY, vm.transform.value)
    vm.onViewportChanged(viewport)

    assertEquals(PassTransform(2f, Offset(250f, -1000f)), vm.transform.value)
  }

  @Test
  fun viewModel_restoresSavedZoom_whenViewportIsMeasuredBeforeTheLoad() = runTest {
    val vm = PassZoomViewModel(FakePassZoomRepository(mapOf("a" to SavedPassZoom(2f, 0.25f, 0f))))

    vm.onViewportChanged(viewport)
    vm.onPassShown("a")
    runCurrent()

    assertEquals(PassTransform(2f, Offset(250f, 0f)), vm.transform.value)
  }

  @Test
  fun viewModel_scalesSavedOffset_toTheNewViewport() = runTest {
    val vm = PassZoomViewModel(FakePassZoomRepository(mapOf("a" to SavedPassZoom(3f, 0.5f, 0f))))

    vm.onViewportChanged(Size(400f, 800f))
    vm.onPassShown("a")
    runCurrent()

    assertEquals(Offset(200f, 0f), vm.transform.value.offset)
  }

  @Test
  fun viewModel_clampsSavedZoom_toTheViewport() = runTest {
    val vm = PassZoomViewModel(FakePassZoomRepository(mapOf("a" to SavedPassZoom(99f, 50f, -50f))))

    vm.onViewportChanged(viewport)
    vm.onPassShown("a")
    runCurrent()

    val t = vm.transform.value
    assertEquals(MAX_ZOOM_SCALE, t.scale, 0f)
    assertEquals(Offset((MAX_ZOOM_SCALE - 1f) * 500f, -(MAX_ZOOM_SCALE - 1f) * 1000f), t.offset)
  }

  @Test
  fun viewModel_showsUnzoomedPass_whenNothingWasSavedForIt() = runTest {
    val vm = PassZoomViewModel(FakePassZoomRepository(mapOf("a" to SavedPassZoom(2f, 0f, 0f))))

    vm.onViewportChanged(viewport)
    vm.onPassShown("b")
    runCurrent()

    assertEquals(PassTransform.IDENTITY, vm.transform.value)
  }

  @Test
  fun viewModel_keepsUsersGesture_overASlowLoad() = runTest {
    val vm = PassZoomViewModel(FakePassZoomRepository(mapOf("a" to SavedPassZoom(5f, 0f, 0f))))
    vm.onViewportChanged(viewport)

    vm.onPassShown("a")
    vm.onGesture(2f, Offset.Zero, Offset.Zero, viewport)
    runCurrent()

    assertEquals(2f, vm.transform.value.scale, 0f)
  }

  @Test
  fun viewModel_savesOldPassZoom_whenSwitchingPasses() = runTest {
    val repository = FakePassZoomRepository()
    val vm = PassZoomViewModel(repository)
    vm.onPassShown("a")
    vm.onGesture(2f, Offset.Zero, Offset.Zero, viewport)

    vm.onPassShown("b")
    advanceTimeBy(PassZoomViewModel.SAVE_DELAY_MS)
    runCurrent()

    assertEquals(listOf("a"), repository.saves.map { it.first })
    assertEquals(PassTransform.IDENTITY, vm.transform.value)
  }

  @Test
  fun viewModel_doesNotSave_beforeViewportIsKnown() = runTest {
    val repository = FakePassZoomRepository()
    val vm = PassZoomViewModel(repository)
    vm.onPassShown("a")

    vm.onGesture(2f, Offset.Zero, Offset.Zero, Size.Zero)
    vm.persistNow()
    runCurrent()

    assertEquals(emptyList<Any>(), repository.saves)
  }

  @Test
  fun viewModel_doesNotSave_whenNoPassIsShown() = runTest {
    val repository = FakePassZoomRepository()
    val vm = PassZoomViewModel(repository)

    vm.onGesture(2f, Offset.Zero, Offset.Zero, viewport)
    vm.persistNow()
    runCurrent()

    assertEquals(emptyList<Any>(), repository.saves)
  }

  @Test
  fun viewModel_doesNotSave_whenTheViewportHasNoHeight() = runTest {
    val repository = FakePassZoomRepository()
    val vm = PassZoomViewModel(repository)
    vm.onPassShown("a")

    vm.onGesture(2f, Offset.Zero, Offset.Zero, Size(1000f, 0f))
    vm.persistNow()
    runCurrent()

    assertEquals(emptyList<Any>(), repository.saves)
  }

  @Test
  fun viewModel_waitsForARealViewport_beforeRestoringTheSavedZoom() = runTest {
    val vm = PassZoomViewModel(FakePassZoomRepository(mapOf("a" to SavedPassZoom(2f, 0.25f, 0f))))
    vm.onPassShown("a")
    runCurrent()

    vm.onViewportChanged(Size(1000f, 0f))
    assertEquals(PassTransform.IDENTITY, vm.transform.value)
    vm.onViewportChanged(Size(0f, 2000f))
    assertEquals(PassTransform.IDENTITY, vm.transform.value)
    vm.onViewportChanged(viewport)

    assertEquals(PassTransform(2f, Offset(250f, 0f)), vm.transform.value)
  }

  @Test
  fun viewModel_showsUnzoomedPass_whenLoadFails() = runTest {
    val vm = PassZoomViewModel(FakePassZoomRepository(failing = true))

    vm.onViewportChanged(viewport)
    vm.onPassShown("a")
    runCurrent()

    assertEquals(PassTransform.IDENTITY, vm.transform.value)
  }

  @Test
  fun viewModel_keepsZoom_whenSaveFails() = runTest {
    val vm = PassZoomViewModel(FakePassZoomRepository(failing = true))
    vm.onPassShown("a")

    vm.onGesture(2f, Offset.Zero, Offset.Zero, viewport)
    vm.persistNow()
    runCurrent()

    assertEquals(2f, vm.transform.value.scale, 0f)
  }
}
