package com.pocketrealm.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AdmissionMonitorEpochTest {
    @Test
    fun retiredGenerationCannotPublishIntoReplacementRun() {
        val epoch = AdmissionMonitorEpoch()
        val first = epoch.begin()
        var published = false
        assertTrue(epoch.publishIfCurrent(first) { published = true })
        assertTrue(published)

        epoch.invalidate()
        published = false
        assertFalse(epoch.publishIfCurrent(first) { published = true })
        assertFalse(published)

        val replacement = epoch.begin()
        assertNotEquals(first, replacement)
        assertTrue(epoch.publishIfCurrent(replacement) { published = true })
        assertTrue(published)
        assertFalse(epoch.isCurrent(first))
    }

    @Test
    fun invalidationBeforeTargetGatePreventsNativeSideEffect() {
        val epoch = AdmissionMonitorEpoch()
        val generation = epoch.begin()
        val release = CountDownLatch(1)
        val invoked = AtomicBoolean(false)
        val worker = thread(start = true) {
            release.await()
            epoch.runIfCurrent(generation) { invoked.set(true) }
        }

        epoch.invalidate()
        release.countDown()
        worker.join(1_000)
        assertFalse(invoked.get())
        assertFalse(worker.isAlive)
    }

    @Test
    fun invalidateWaitsForAlreadyAuthorizedTargetCallThenClosesGeneration() {
        val epoch = AdmissionMonitorEpoch()
        val generation = epoch.begin()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        val target = thread(start = true) {
            epoch.runIfCurrent(generation) {
                entered.countDown()
                release.await()
                0
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val stop = thread(start = true) {
            epoch.invalidate()
            invalidated.countDown()
        }
        assertFalse(invalidated.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(invalidated.await(1, TimeUnit.SECONDS))
        target.join(1_000)
        stop.join(1_000)
        assertNull(epoch.runIfCurrent(generation) { 0 })
    }

    @Test
    fun compositeInvalidationRetiresBeforeReturning() {
        val epoch = AdmissionMonitorEpoch()
        val generation = epoch.begin()
        var retired = false
        epoch.invalidate {
            retired = true
            assertFalse(epoch.isCurrent(generation))
        }
        assertTrue(retired)
        assertFalse(epoch.isCurrent(generation))
        assertNull(epoch.runIfCurrent(generation) { 0 })
    }

    @Test
    fun transitionGatePreventsStopStartOverlap() {
        val gate = AdmissionTransitionGate()
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val startEntered = CountDownLatch(1)
        val stop = thread(start = true) {
            gate.run {
                stopEntered.countDown()
                releaseStop.await()
            }
        }
        assertTrue(stopEntered.await(1, TimeUnit.SECONDS))
        val start = thread(start = true) {
            gate.run { startEntered.countDown() }
        }
        assertFalse(startEntered.await(100, TimeUnit.MILLISECONDS))
        releaseStop.countDown()
        assertTrue(startEntered.await(1, TimeUnit.SECONDS))
        stop.join(1_000)
        start.join(1_000)
    }

    @Test
    fun targetOwnershipCheckAndCallCannotCrossProfileClaim() {
        val gate = AdmissionTransitionGate()
        val targetChecked = CountDownLatch(1)
        val releaseTarget = CountDownLatch(1)
        val profileClaimed = CountDownLatch(1)
        var admissionOwned = false
        var targetCalled = false
        val target = thread(start = true) {
            gate.run {
                assertFalse(admissionOwned)
                targetChecked.countDown()
                releaseTarget.await()
                targetCalled = true
            }
        }
        assertTrue(targetChecked.await(1, TimeUnit.SECONDS))
        val profile = thread(start = true) {
            gate.run {
                admissionOwned = true
                profileClaimed.countDown()
            }
        }
        assertFalse(profileClaimed.await(100, TimeUnit.MILLISECONDS))
        releaseTarget.countDown()
        assertTrue(profileClaimed.await(1, TimeUnit.SECONDS))
        target.join(1_000)
        profile.join(1_000)
        assertTrue(targetCalled)
        assertTrue(admissionOwned)
    }
}
