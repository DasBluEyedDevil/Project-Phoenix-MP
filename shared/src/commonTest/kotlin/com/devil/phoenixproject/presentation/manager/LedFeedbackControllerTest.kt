package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.LedFeedbackMode
import com.devil.phoenixproject.domain.model.RepPhase
import com.devil.phoenixproject.domain.model.VelocityZone
import com.devil.phoenixproject.domain.model.WorkoutMode
import com.devil.phoenixproject.testutil.FakeBleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for LedFeedbackController covering:
 * - VelocityZone boundary values
 * - Hysteresis (3-sample stability requirement)
 * - Throttling (500ms minimum interval)
 * - Mode-specific resolvers (tempo, echo)
 * - Enabled/disabled state
 * - Rest period handling
 * - Workout end color restoration
 * - PR celebration
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedFeedbackControllerTest {

    // ===== VelocityZone.fromVelocity boundary tests =====

    @Test
    fun `VelocityZone fromVelocity maps REST below 20`() {
        assertEquals(VelocityZone.REST, VelocityZone.fromVelocity(0.0))
        assertEquals(VelocityZone.REST, VelocityZone.fromVelocity(19.0))
        assertEquals(VelocityZone.REST, VelocityZone.fromVelocity(19.999))
    }

    @Test
    fun `VelocityZone fromVelocity maps CONTROLLED 20 to 149`() {
        assertEquals(VelocityZone.CONTROLLED, VelocityZone.fromVelocity(20.0))
        assertEquals(VelocityZone.CONTROLLED, VelocityZone.fromVelocity(149.0))
        assertEquals(VelocityZone.CONTROLLED, VelocityZone.fromVelocity(149.999))
    }

    @Test
    fun `VelocityZone fromVelocity maps MODERATE 150 to 299`() {
        assertEquals(VelocityZone.MODERATE, VelocityZone.fromVelocity(150.0))
        assertEquals(VelocityZone.MODERATE, VelocityZone.fromVelocity(299.0))
    }

    @Test
    fun `VelocityZone fromVelocity maps FAST 300 to 499`() {
        assertEquals(VelocityZone.FAST, VelocityZone.fromVelocity(300.0))
        assertEquals(VelocityZone.FAST, VelocityZone.fromVelocity(499.0))
    }

    @Test
    fun `VelocityZone fromVelocity maps VERY_FAST 500 to 699`() {
        assertEquals(VelocityZone.VERY_FAST, VelocityZone.fromVelocity(500.0))
        assertEquals(VelocityZone.VERY_FAST, VelocityZone.fromVelocity(699.0))
    }

    @Test
    fun `VelocityZone fromVelocity maps EXPLOSIVE at 700 and above`() {
        assertEquals(VelocityZone.EXPLOSIVE, VelocityZone.fromVelocity(700.0))
        assertEquals(VelocityZone.EXPLOSIVE, VelocityZone.fromVelocity(1000.0))
    }

    // ===== Hysteresis tests =====

    @Test
    fun `hysteresis requires 3 consecutive samples before zone switch`() = runTest {
        val fakeBle = FakeBleRepository()
        var fakeTime = 0L
        val controller = LedFeedbackController(fakeBle, this, timeProvider = { fakeTime })
        controller.setEnabled(true)

        // Start in REST zone -- need to establish initial zone first
        // Send 3 samples to establish CONTROLLED zone
        fakeTime = 0L
        repeat(3) {
            controller.updateMetrics(100.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()
        fakeBle.colorSchemeCommands.clear()

        // Now try to switch to FAST -- only 2 samples should NOT trigger
        fakeTime = 1000L // Well past throttle
        controller.updateMetrics(400.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        controller.updateMetrics(400.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        advanceUntilIdle()
        assertEquals(0, fakeBle.colorSchemeCommands.size,
            "2 samples in new zone should NOT trigger color change")

        // Third sample in new zone should trigger
        controller.updateMetrics(400.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        advanceUntilIdle()
        assertTrue(fakeBle.colorSchemeCommands.contains(VelocityZone.FAST.schemeIndex),
            "3rd sample should trigger zone switch to FAST (yellow)")
    }

    @Test
    fun `hysteresis resets counter when target matches current zone`() = runTest {
        val fakeBle = FakeBleRepository()
        var fakeTime = 0L
        val controller = LedFeedbackController(fakeBle, this, timeProvider = { fakeTime })
        controller.setEnabled(true)

        // Establish CONTROLLED zone
        repeat(3) {
            controller.updateMetrics(100.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()
        fakeBle.colorSchemeCommands.clear()

        // 2 samples toward FAST
        fakeTime = 1000L
        controller.updateMetrics(400.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        controller.updateMetrics(400.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)

        // Back to CONTROLLED -- resets stability counter
        controller.updateMetrics(100.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)

        // 2 more samples toward FAST (counter should have reset)
        fakeTime = 2000L
        controller.updateMetrics(400.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        controller.updateMetrics(400.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        advanceUntilIdle()

        assertEquals(0, fakeBle.colorSchemeCommands.size,
            "Stability counter should have reset; 2 samples not enough")
    }

    // ===== Throttling tests =====

    @Test
    fun `throttling prevents rapid zone changes within 500ms`() = runTest {
        val fakeBle = FakeBleRepository()
        var fakeTime = 0L
        val controller = LedFeedbackController(fakeBle, this, timeProvider = { fakeTime })
        controller.setEnabled(true)

        // Establish CONTROLLED zone at t=0
        fakeTime = 0L
        repeat(3) {
            controller.updateMetrics(100.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()
        fakeBle.colorSchemeCommands.clear()

        // Try to switch to FAST at t=100 (within 500ms throttle window)
        // Hysteresis will pass (3 samples) but throttle should block the BLE write
        fakeTime = 100L
        repeat(3) {
            controller.updateMetrics(400.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        assertEquals(0, fakeBle.colorSchemeCommands.size,
            "Color change within 500ms should be throttled")

        // Now switch to a different zone (EXPLOSIVE) past the throttle window
        // The controller's currentZone is FAST (hysteresis passed), so we need
        // a new zone to trigger another hysteresis pass + send
        fakeTime = 700L
        repeat(3) {
            controller.updateMetrics(800.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        assertTrue(fakeBle.colorSchemeCommands.isNotEmpty(),
            "Color change after 500ms throttle window should succeed")
    }

    // ===== Tempo guide resolver tests =====

    @Test
    fun `resolveTempoZone TUT returns green when in target range`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        // TUT target: 250-350 mm/s
        assertEquals(VelocityZone.CONTROLLED, controller.resolveTempoZone(300.0, WorkoutMode.TUT))
        assertEquals(VelocityZone.CONTROLLED, controller.resolveTempoZone(250.0, WorkoutMode.TUT))
        assertEquals(VelocityZone.CONTROLLED, controller.resolveTempoZone(350.0, WorkoutMode.TUT))
    }

    @Test
    fun `resolveTempoZone TUT returns yellow when slightly fast`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        // Slightly above target (350 < vel <= 350*1.5=525)
        assertEquals(VelocityZone.FAST, controller.resolveTempoZone(400.0, WorkoutMode.TUT))
        assertEquals(VelocityZone.FAST, controller.resolveTempoZone(525.0, WorkoutMode.TUT))
    }

    @Test
    fun `resolveTempoZone TUT returns red when too fast`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        // Well above target (> 525)
        assertEquals(VelocityZone.EXPLOSIVE, controller.resolveTempoZone(600.0, WorkoutMode.TUT))
    }

    @Test
    fun `resolveTempoZone TUT returns teal when too slow`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        // TUT tolerance = (350-250)*0.25 = 25. Below 250-25=225
        assertEquals(VelocityZone.MODERATE, controller.resolveTempoZone(200.0, WorkoutMode.TUT))
    }

    // ===== Echo zone resolver tests =====

    @Test
    fun `resolveEchoZone returns green when ratio near 1_0`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        assertEquals(VelocityZone.CONTROLLED, controller.resolveEchoZone(1.0f))
        assertEquals(VelocityZone.CONTROLLED, controller.resolveEchoZone(0.90f))
        assertEquals(VelocityZone.CONTROLLED, controller.resolveEchoZone(1.10f))
    }

    @Test
    fun `resolveEchoZone returns yellow when slightly off`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        assertEquals(VelocityZone.FAST, controller.resolveEchoZone(0.80f))
        assertEquals(VelocityZone.FAST, controller.resolveEchoZone(1.20f))
    }

    @Test
    fun `resolveEchoZone returns red when significant mismatch`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)

        assertEquals(VelocityZone.EXPLOSIVE, controller.resolveEchoZone(0.50f))
        assertEquals(VelocityZone.EXPLOSIVE, controller.resolveEchoZone(1.50f))
    }

    // ===== Disabled state =====

    @Test
    fun `updateMetrics does nothing when disabled`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        // Not enabled -- default is false

        repeat(10) {
            controller.updateMetrics(500.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        assertEquals(0, fakeBle.colorSchemeCommands.size,
            "No BLE commands should be sent when controller is disabled")
    }

    // ===== Rest period =====

    @Test
    fun `onRestPeriodStart sends blue color`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        controller.setEnabled(true)

        controller.onRestPeriodStart()
        advanceUntilIdle()

        assertTrue(fakeBle.colorSchemeCommands.contains(0),
            "Rest period should send blue (index 0)")
    }

    @Test
    fun `updateMetrics ignored during rest period`() = runTest {
        val fakeBle = FakeBleRepository()
        var fakeTime = 0L
        val controller = LedFeedbackController(fakeBle, this, timeProvider = { fakeTime })
        controller.setEnabled(true)

        controller.onRestPeriodStart()
        advanceUntilIdle()
        fakeBle.colorSchemeCommands.clear()

        // Velocity updates during rest should be ignored
        fakeTime = 1000L
        repeat(5) {
            controller.updateMetrics(500.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        assertEquals(0, fakeBle.colorSchemeCommands.size,
            "No velocity-driven updates during rest period")
    }

    // ===== Workout end =====

    @Test
    fun `onWorkoutEnd restores user color scheme`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        controller.setEnabled(true)
        controller.setUserColorScheme(3) // Yellow

        controller.onWorkoutEnd()
        advanceUntilIdle()

        assertTrue(fakeBle.colorSchemeCommands.contains(3),
            "Workout end should restore user's color scheme (index 3)")
    }

    // ===== Disco mode interaction =====

    @Test
    fun `updateMetrics ignored when disco mode active`() = runTest {
        val fakeBle = FakeBleRepository()
        var fakeTime = 0L
        val controller = LedFeedbackController(fakeBle, this, timeProvider = { fakeTime })
        controller.setEnabled(true)
        fakeBle.setDiscoModeActive(true)

        repeat(5) {
            fakeTime += 600L
            controller.updateMetrics(500.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        assertEquals(0, fakeBle.colorSchemeCommands.size,
            "No LED commands when disco mode is active")
    }

    // ===== Auto mode resolution =====

    @Test
    fun `AUTO mode resolves to TEMPO_GUIDE for TUT`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        controller.setMode(LedFeedbackMode.AUTO)

        assertEquals(LedFeedbackMode.TEMPO_GUIDE, controller.resolveEffectiveMode(WorkoutMode.TUT))
        assertEquals(LedFeedbackMode.TEMPO_GUIDE, controller.resolveEffectiveMode(WorkoutMode.TUTBeast))
    }

    @Test
    fun `AUTO mode resolves to VELOCITY_ZONE for other modes`() = runTest {
        val fakeBle = FakeBleRepository()
        val controller = LedFeedbackController(fakeBle, this)
        controller.setMode(LedFeedbackMode.AUTO)

        assertEquals(LedFeedbackMode.VELOCITY_ZONE, controller.resolveEffectiveMode(WorkoutMode.OldSchool))
        assertEquals(LedFeedbackMode.VELOCITY_ZONE, controller.resolveEffectiveMode(WorkoutMode.Pump))
    }

    // ===== Disconnect reset =====

    @Test
    fun `onDisconnect resets lastSentSchemeIndex for fresh send on reconnect`() = runTest {
        val fakeBle = FakeBleRepository()
        var fakeTime = 0L
        val controller = LedFeedbackController(fakeBle, this, timeProvider = { fakeTime })
        controller.setEnabled(true)

        // Establish CONTROLLED zone (index 1) at t=0
        fakeTime = 0L
        repeat(3) {
            controller.updateMetrics(100.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        // Switch to FAST zone (index 3) at t=600
        fakeTime = 600L
        repeat(3) {
            controller.updateMetrics(400.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        // Verify FAST was sent
        assertTrue(fakeBle.colorSchemeCommands.contains(VelocityZone.FAST.schemeIndex),
            "FAST zone should have been sent")
        fakeBle.colorSchemeCommands.clear()

        // Disconnect -- resets lastSentSchemeIndex to -1
        controller.onDisconnect()

        // After disconnect, switch back to CONTROLLED (which was previously sent).
        // Without disconnect, this would be deduped. With disconnect, it should re-send.
        fakeTime = 1200L
        repeat(3) {
            controller.updateMetrics(100.0, RepPhase.CONCENTRIC, WorkoutMode.OldSchool)
        }
        advanceUntilIdle()

        assertTrue(fakeBle.colorSchemeCommands.contains(VelocityZone.CONTROLLED.schemeIndex),
            "After disconnect, previously-sent zone should re-send color command")
    }
}
