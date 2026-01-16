package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepCounterFromMachineTest {

    private lateinit var repCounter: RepCounterFromMachine
    private val capturedEvents = mutableListOf<RepEvent>()

    @BeforeTest
    fun setup() {
        repCounter = RepCounterFromMachine()
        capturedEvents.clear()
        repCounter.onRepEvent = { capturedEvents.add(it) }
    }

    // ========== Configuration Tests ==========

    @Test
    fun `configure sets warmup and working targets`() {
        repCounter.configure(
            warmupTarget = 3,
            workingTarget = 10,
            isJustLift = false,
            stopAtTop = false
        )

        // Initially no reps counted
        val count = repCounter.getRepCount()
        assertEquals(0, count.warmupReps)
        assertEquals(0, count.workingReps)
        assertFalse(count.isWarmupComplete)
    }

    @Test
    fun `reset clears all state`() {
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Process some reps
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 0)

        repCounter.reset()

        val count = repCounter.getRepCount()
        assertEquals(0, count.warmupReps)
        assertEquals(0, count.workingReps)
        assertFalse(repCounter.shouldStopWorkout())
    }

    // ========== Modern Mode Rep Counting Tests ==========

    @Test
    fun `warmup reps are counted from repsRomCount`() {
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 0)

        val count = repCounter.getRepCount()
        assertEquals(1, count.warmupReps)
        assertEquals(0, count.workingReps)
        assertFalse(count.isWarmupComplete)
    }

    @Test
    fun `warmup complete event fires when first working rep completes`() {
        // With new formula: workingReps = down - repsRomCount
        // WARMUP_COMPLETE fires when transitioning from 0 to 1 working rep
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Complete 3 warmup reps (repsRomCount = 3), then first working rep (down = 4)
        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 4, down = 4)

        val count = repCounter.getRepCount()
        assertEquals(3, count.warmupReps)
        assertEquals(1, count.workingReps) // down(4) - repsRomCount(3) = 1
        assertTrue(count.isWarmupComplete)

        // Should have warmup complete event
        assertTrue(capturedEvents.any { it.type == RepType.WARMUP_COMPLETE })
    }

    @Test
    fun `working reps are counted from down minus repsRomCount`() {
        // New formula: workingReps = down - repsRomCount (matches official app)
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Complete warmup first (repsRomCount = 3, down = 3 means workingReps = 0)
        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 3, down = 3)

        // Now working reps: down = 4, repsRomCount = 3, so workingReps = 1
        repCounter.process(repsRomCount = 3, repsSetCount = 1, up = 4, down = 4)

        val count = repCounter.getRepCount()
        assertEquals(3, count.warmupReps)
        assertEquals(1, count.workingReps) // down(4) - repsRomCount(3) = 1
        assertEquals(1, count.totalReps) // totalReps = workingReps only
    }

    @Test
    fun `working reps use down counter minus repsRomCount even when repsSetCount is zero`() {
        // New formula: workingReps = down - repsRomCount
        // This works regardless of repsSetCount value
        repCounter.configure(
            warmupTarget = 1,
            workingTarget = 5,
            isJustLift = false,
            stopAtTop = false
        )

        // Warmup complete (repsRomCount = 1, down = 1 means workingReps = 0)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        // First working rep: down = 2, repsRomCount = 1, so workingReps = 1
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 2, down = 2)

        val count = repCounter.getRepCount()
        assertEquals(1, count.workingReps) // down(2) - repsRomCount(1) = 1
    }

    @Test
    fun `fallback counting can trigger workout completion`() {
        repCounter.configure(
            warmupTarget = 0,
            workingTarget = 2,
            isJustLift = false,
            stopAtTop = false
        )

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Two reps via counters, repsSetCount remains zero
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 2, down = 2)

        assertTrue(repCounter.shouldStopWorkout())
        assertTrue(capturedEvents.any { it.type == RepType.WORKOUT_COMPLETE })
    }

    @Test
    fun `shouldStopWorkout returns true when working target reached`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 5, isJustLift = false, stopAtTop = false)

        // Simulate 5 working reps
        repCounter.process(repsRomCount = 0, repsSetCount = 5, up = 5, down = 5)

        assertTrue(repCounter.shouldStopWorkout())
        assertTrue(capturedEvents.any { it.type == RepType.WORKOUT_COMPLETE })
    }

    @Test
    fun `shouldStopWorkout returns false for AMRAP mode`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 5, isJustLift = false, stopAtTop = false, isAMRAP = true)

        // Simulate 5 working reps
        repCounter.process(repsRomCount = 0, repsSetCount = 5, up = 5, down = 5)

        assertFalse(repCounter.shouldStopWorkout())
    }

    @Test
    fun `shouldStopWorkout returns false for Just Lift mode`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 5, isJustLift = true, stopAtTop = false)

        repCounter.process(repsRomCount = 0, repsSetCount = 5, up = 5, down = 5)

        assertFalse(repCounter.shouldStopWorkout())
    }

    // ========== stopAtTop Mode Tests ==========

    @Test
    fun `stopAtTop true counts working reps at TOP using up counter`() {
        // With stopAtTop=true, working reps are confirmed at TOP (up counter)
        // Formula: workingReps = up - warmupTarget
        repCounter.configure(warmupTarget = 3, workingTarget = 10, isJustLift = false, stopAtTop = true)

        // Complete 3 warmup reps first (repsRomCount = 3)
        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 3, down = 3)

        // First working rep at TOP (up = 4, warmupTarget = 3, so workingReps = 1)
        // Note: down counter doesn't matter for stopAtTop=true
        repCounter.process(repsRomCount = 3, repsSetCount = 0, up = 4, down = 3)

        val count = repCounter.getRepCount()
        assertEquals(3, count.warmupReps)
        assertEquals(1, count.workingReps) // up(4) - warmupTarget(3) = 1
    }

    @Test
    fun `stopAtTop true completes workout when target reached at TOP`() {
        // Simulates real scenario: user lifts to TOP on rep 10, machine releases weight at TOP
        // Without this fix, user never reaches BOTTOM so down counter stays at 9
        repCounter.configure(warmupTarget = 0, workingTarget = 5, isJustLift = false, stopAtTop = true)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Simulate 5 reps - each rep is counted at TOP (up counter)
        // User lifts to TOP, rep counted, then lowers to BOTTOM
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0)  // Rep 1 at TOP
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1)  // User lowers

        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 2, down = 1)  // Rep 2 at TOP
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 2, down = 2)  // User lowers

        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 3, down = 2)  // Rep 3 at TOP
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 3, down = 3)  // User lowers

        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 4, down = 3)  // Rep 4 at TOP
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 4, down = 4)  // User lowers

        // Rep 5 - machine releases weight AT TOP, user never reaches BOTTOM
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 5, down = 4)  // Rep 5 at TOP - WORKOUT COMPLETE

        val count = repCounter.getRepCount()
        assertEquals(5, count.workingReps)
        assertTrue(repCounter.shouldStopWorkout())
        assertTrue(capturedEvents.any { it.type == RepType.WORKOUT_COMPLETE })
    }

    @Test
    fun `stopAtTop false still counts reps at BOTTOM`() {
        // Verify stopAtTop=false still uses down - repsRomCount formula
        repCounter.configure(warmupTarget = 0, workingTarget = 3, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0)

        // Simulate reps - reps only count when user reaches BOTTOM (down increments)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0)  // TOP - no rep yet

        val countAfterTop = repCounter.getRepCount()
        assertEquals(0, countAfterTop.workingReps) // Not counted until BOTTOM

        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1)  // BOTTOM - rep 1 confirmed

        val countAfterBottom = repCounter.getRepCount()
        assertEquals(1, countAfterBottom.workingReps)
    }

    @Test
    fun `stopAtTop true does not show pending state`() {
        // With stopAtTop=true, reps are confirmed immediately at TOP (no pending state)
        repCounter.configure(warmupTarget = 1, workingTarget = 10, isJustLift = false, stopAtTop = true)

        // Complete warmup
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        // Go up - for stopAtTop=true, this immediately confirms the rep (no pending)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 2, down = 1)

        val count = repCounter.getRepCount()
        assertFalse(count.hasPendingRep, "stopAtTop=true should not use pending state")
        assertEquals(1, count.workingReps, "Rep should be immediately confirmed at TOP")
    }

    // ========== Legacy Mode Rep Counting Tests ==========

    @Test
    fun `legacy mode counts reps from topCounter increments`() {
        repCounter.configure(warmupTarget = 2, workingTarget = 3, isJustLift = false, stopAtTop = false)

        // First call establishes baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, isLegacyFormat = true)

        // Increment top counter = rep
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, isLegacyFormat = true)

        val count = repCounter.getRepCount()
        assertEquals(1, count.warmupReps)
    }

    @Test
    fun `legacy mode transitions from warmup to working reps`() {
        repCounter.configure(warmupTarget = 2, workingTarget = 3, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, isLegacyFormat = true)

        // 2 warmup reps
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, isLegacyFormat = true)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 2, down = 1, isLegacyFormat = true)

        // 1 working rep
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 3, down = 2, isLegacyFormat = true)

        val count = repCounter.getRepCount()
        assertEquals(2, count.warmupReps)
        assertEquals(1, count.workingReps)
    }

    // ========== Pending Rep Tests ==========

    @Test
    fun `pending rep is set on up movement after warmup complete`() {
        // Pending rep only shows when repsRomCount > 0 (warmup complete)
        repCounter.configure(warmupTarget = 1, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Complete warmup first (repsRomCount = 1)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        // Up movement should trigger pending (repsRomCount > 0 required)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 2, down = 1)

        val count = repCounter.getRepCount()
        assertTrue(count.hasPendingRep)
    }

    @Test
    fun `pending rep persists until working rep confirmed`() {
        // Pending rep is cleared when down - repsRomCount increases
        repCounter.configure(warmupTarget = 1, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Complete warmup (repsRomCount = 1, down = 1 means workingReps = 0)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        // Go up - triggers pending
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 2, down = 1)

        val countBeforeConfirm = repCounter.getRepCount()
        assertTrue(countBeforeConfirm.hasPendingRep, "Pending rep should be set on up movement")
        assertEquals(0, countBeforeConfirm.workingReps) // down(1) - repsRomCount(1) = 0
    }

    @Test
    fun `pending rep is cleared when down counter confirms rep`() {
        // With new formula, pending clears when workingReps = down - repsRomCount increases
        repCounter.configure(warmupTarget = 1, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Complete warmup (repsRomCount = 1, down = 1)
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 1, down = 1)

        // Go up - triggers pending
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 2, down = 1)

        // Down counter increments (down = 2), confirming the working rep
        // workingReps = down(2) - repsRomCount(1) = 1
        repCounter.process(repsRomCount = 1, repsSetCount = 0, up = 2, down = 2)

        val countAfterConfirm = repCounter.getRepCount()
        assertFalse(countAfterConfirm.hasPendingRep, "Pending rep should be cleared when down counter confirms")
        assertEquals(1, countAfterConfirm.workingReps)
    }

    // ========== Position Range Tests ==========

    @Test
    fun `position ranges are tracked from rep positions`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Establish baseline
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, posA = 100f, posB = 100f)

        // Go up (records top position)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, posA = 800f, posB = 800f)

        // Go down (records bottom position)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1, posA = 100f, posB = 100f)

        val ranges = repCounter.getRepRanges()
        assertEquals(800f, ranges.maxPosA)
        assertEquals(100f, ranges.minPosA)
    }

    @Test
    fun `hasMeaningfulRange returns true when range exceeds threshold`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Set up ranges with >50mm difference
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, posA = 100f, posB = 100f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, posA = 200f, posB = 200f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1, posA = 100f, posB = 100f)

        assertTrue(repCounter.hasMeaningfulRange(minRangeThreshold = 50f))
    }

    @Test
    fun `hasMeaningfulRange returns false when range is too small`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Set up ranges with <50mm difference
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, posA = 100f, posB = 100f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, posA = 130f, posB = 130f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1, posA = 100f, posB = 100f)

        assertFalse(repCounter.hasMeaningfulRange(minRangeThreshold = 50f))
    }

    @Test
    fun `isInDangerZone detects when position is near minimum`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Set up ranges: min=100, max=1000 (range=900)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, posA = 100f, posB = 100f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, posA = 1000f, posB = 1000f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1, posA = 100f, posB = 100f)

        // Position at 105 is within 5% of min (threshold = 100 + 900*0.05 = 145)
        assertTrue(repCounter.isInDangerZone(posA = 105f, posB = 105f))

        // Position at 200 is above danger zone
        assertFalse(repCounter.isInDangerZone(posA = 200f, posB = 200f))
    }

    // ========== resetCountsOnly Tests ==========

    @Test
    fun `resetCountsOnly preserves position ranges`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = false, stopAtTop = false)

        // Build up position ranges
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 0, down = 0, posA = 100f, posB = 100f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 0, posA = 800f, posB = 800f)
        repCounter.process(repsRomCount = 0, repsSetCount = 0, up = 1, down = 1, posA = 100f, posB = 100f)

        // Reset counts only
        repCounter.resetCountsOnly()

        // Counts should be reset
        val count = repCounter.getRepCount()
        assertEquals(0, count.warmupReps)
        assertEquals(0, count.workingReps)

        // But ranges should be preserved
        assertTrue(repCounter.hasMeaningfulRange())
    }

    // ========== Continuous Position Tracking Tests ==========

    @Test
    fun `updatePositionRangesContinuously tracks min and max`() {
        repCounter.configure(warmupTarget = 0, workingTarget = 10, isJustLift = true, stopAtTop = false)

        // Simulate continuous position updates during Just Lift
        repCounter.updatePositionRangesContinuously(100f, 100f)
        repCounter.updatePositionRangesContinuously(500f, 500f)
        repCounter.updatePositionRangesContinuously(200f, 200f)

        val ranges = repCounter.getRepRanges()
        assertEquals(100f, ranges.minPosA)
        assertEquals(500f, ranges.maxPosA)
    }
}

class RepRangesTest {

    @Test
    fun `isInDangerZone returns true when position is within 5 percent of min`() {
        val ranges = RepRanges(
            minPosA = 100f,
            maxPosA = 1000f,
            minPosB = 100f,
            maxPosB = 1000f,
            minRangeA = null,
            maxRangeA = null,
            minRangeB = null,
            maxRangeB = null
        )

        // 5% of range (900) = 45, threshold = 145
        assertTrue(ranges.isInDangerZone(posA = 110f, posB = 500f))
    }

    @Test
    fun `isInDangerZone returns false when position is safe`() {
        val ranges = RepRanges(
            minPosA = 100f,
            maxPosA = 1000f,
            minPosB = 100f,
            maxPosB = 1000f,
            minRangeA = null,
            maxRangeA = null,
            minRangeB = null,
            maxRangeB = null
        )

        assertFalse(ranges.isInDangerZone(posA = 500f, posB = 500f))
    }

    @Test
    fun `isInDangerZone returns false when range is too small`() {
        val ranges = RepRanges(
            minPosA = 100f,
            maxPosA = 120f, // Range = 20, less than threshold
            minPosB = 100f,
            maxPosB = 120f,
            minRangeA = null,
            maxRangeA = null,
            minRangeB = null,
            maxRangeB = null
        )

        // Even at min, not in danger zone because range is too small
        assertFalse(ranges.isInDangerZone(posA = 100f, posB = 100f, minRangeThreshold = 50f))
    }

    @Test
    fun `isInDangerZone triggers when position goes to zero after meaningful range`() {
        // This tests the Just Lift autostop scenario where handles are placed down
        // Position goes to ~0 after user has established a meaningful ROM range
        val ranges = RepRanges(
            minPosA = 30f,    // Typical overhead pulley rest position
            maxPosA = 230f,   // Extended position after reps
            minPosB = 30f,
            maxPosB = 230f,
            minRangeA = null,
            maxRangeA = null,
            minRangeB = null,
            maxRangeB = null
        )

        // Range = 200mm, threshold = 30 + (200 * 0.05) = 40mm
        // Position at 0 or very low should trigger danger zone for autostop
        assertTrue(ranges.isInDangerZone(posA = 0f, posB = 0f))
        assertTrue(ranges.isInDangerZone(posA = 5f, posB = 5f))
        assertTrue(ranges.isInDangerZone(posA = 35f, posB = 35f))  // Just above min but below threshold

        // Position at 50 should NOT trigger (above threshold of 40)
        assertFalse(ranges.isInDangerZone(posA = 50f, posB = 50f))
    }
}
