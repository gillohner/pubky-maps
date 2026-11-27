package to.pubky.maps.android

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.PersistableBundle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/** Waiting writes keep a job that Android runs when the phone has a network, also with the app closed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OutboxJobTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val jobs = context.getSystemService(JobScheduler::class.java)
    private val waiting = MutableStateFlow<Boolean?>(null)

    @Test
    fun theJobIsKeptWhileWritesOrNotesWaitAndOnlyEndsWhenBothAreKnownToWaitNot() {
        assertEquals(true, anyWaiting(writes = true, notes = false))
        assertEquals(true, anyWaiting(writes = null, notes = true))
        assertEquals(true, anyWaiting(writes = false, notes = true))
        assertNull(anyWaiting(writes = false, notes = null))
        assertNull(anyWaiting(writes = null, notes = false))
        assertEquals(false, anyWaiting(writes = false, notes = false))
    }

    @Test
    fun waitingWritesKeepAJobThatNeedsANetworkAndOutlastsARestart() =
        runTest(UnconfinedTestDispatcher()) {
            followOutbox(context, waiting, backgroundScope)
            assertNull("nothing is known before the outbox is read", jobs.getPendingJob(OUTBOX_JOB_ID))

            waiting.value = true

            val job = jobs.getPendingJob(OUTBOX_JOB_ID)
            assertNotNull(job)
            assertEquals(ComponentName(context, OutboxJobService::class.java), job!!.service)
            assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
            assertTrue("kept over a restart of the phone", job.isPersisted)
            assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, job.backoffPolicy)
            assertTrue("never held back to batch it with other apps' jobs", job.isExpedited)

            waiting.value = false
            assertNull("sent or signed out: nothing to wake the app for", jobs.getPendingJob(OUTBOX_JOB_ID))
        }

    @Test
    fun withTheExpeditedQuotaUsedUpTheJobIsARegularOne() {
        shadowOf(jobs).failExpeditedJob(true)

        scheduleOutboxJob(context)

        val job = jobs.getPendingJob(OUTBOX_JOB_ID)
        assertNotNull("a regular job instead of none", job)
        assertFalse(job!!.isExpedited)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertTrue(job.isPersisted)
    }

    @Test
    fun aJobThatIsAlreadyThereIsNotScheduledAgain() =
        runTest(UnconfinedTestDispatcher()) {
            // The run that started this process: scheduling the job again would stop it.
            val running =
                JobInfo
                    .Builder(OUTBOX_JOB_ID, ComponentName(context, OutboxJobService::class.java))
                    .setExtras(PersistableBundle().apply { putString("run", "the one running") })
                    .build()
            jobs.schedule(running)
            followOutbox(context, waiting, backgroundScope)

            waiting.value = true

            assertEquals("the one running", jobs.getPendingJob(OUTBOX_JOB_ID)!!.extras.getString("run"))
        }

    @Test
    fun aRunStoppedByTheNetworkGoingLeavesAFreshJobWithoutBackoff() {
        assertFalse("a new job instead of a backoff", rescheduleAfterStop(context, waits = true, connectivityLost = true))
        assertNotNull(jobs.getPendingJob(OUTBOX_JOB_ID))
    }

    @Test
    fun aRunStoppedForOtherReasonsRunsAgainAfterItsBackoff() {
        assertTrue(rescheduleAfterStop(context, waits = true, connectivityLost = false))
        assertNull("Android reschedules the stopped job itself", jobs.getPendingJob(OUTBOX_JOB_ID))
    }

    @Test
    fun aStoppedRunWithNothingWaitingEnds() {
        assertFalse(rescheduleAfterStop(context, waits = false, connectivityLost = true))
        assertNull(jobs.getPendingJob(OUTBOX_JOB_ID))
    }

    @Test
    fun aWriteMadeAsARunEndsGetsAJobAgain() {
        afterRun(context, sent = false, waitsNow = true)
        assertNull("a run that did not send is rescheduled by Android", jobs.getPendingJob(OUTBOX_JOB_ID))
        afterRun(context, sent = true, waitsNow = false)
        assertNull(jobs.getPendingJob(OUTBOX_JOB_ID))

        afterRun(context, sent = true, waitsNow = true)
        assertNotNull(jobs.getPendingJob(OUTBOX_JOB_ID))
    }

    @Test
    fun aRunThatSentEverythingEndsWithoutAnotherRun() =
        runTest {
            waiting.value = true
            val finished = mutableListOf<Boolean>()
            val run = async { runOutbox(context, waiting, sendNow = { waiting.value = false }, finish = { finished += it }) }
            runCurrent()

            assertTrue(run.isCompleted)
            assertEquals("jobFinished(reschedule = false)", listOf(false), finished)
            assertNull(jobs.getPendingJob(OUTBOX_JOB_ID))
        }

    @Test
    fun aRunThatCouldNotSendAsksAndroidToRunItAgain() =
        runTest {
            waiting.value = true
            val finished = mutableListOf<Boolean>()
            launch { runOutbox(context, waiting, sendNow = {}, finish = { finished += it }) }
            advanceTimeBy(SEND_WINDOW + 1.seconds)

            assertEquals("jobFinished(reschedule = true)", listOf(true), finished)
            assertNull("no new job: Android reschedules this one after its backoff", jobs.getPendingJob(OUTBOX_JOB_ID))
        }

    @Test
    fun aWriteMadeAsTheRunEndsGetsAJobAfterTheRun() =
        runTest {
            waiting.value = true
            val finished = mutableListOf<Boolean>()
            launch {
                runOutbox(context, waiting, sendNow = { waiting.value = false }, finish = {
                    finished += it
                    waiting.value = true
                })
            }
            runCurrent()

            assertEquals(listOf(false), finished)
            assertNotNull("the finished job is gone, so a new one is scheduled", jobs.getPendingJob(OUTBOX_JOB_ID))
        }

    @Test
    fun aRunKeepsAGuardJobUntilItEnds() =
        runTest {
            waiting.value = true
            launch { runOutbox(context, waiting, sendNow = {}, finish = {}) }
            runCurrent()

            val guard = jobs.getPendingJob(OUTBOX_GUARD_JOB_ID)
            assertNotNull("due only if the process dies during the run", guard)
            assertEquals(GUARD_DELAY.inWholeMilliseconds, guard!!.minLatencyMillis)
            assertTrue("after the run's window", GUARD_DELAY > SEND_WINDOW)
            assertEquals("the timer alone runs it", JobInfo.NETWORK_TYPE_NONE, guard.networkType)

            advanceTimeBy(SEND_WINDOW + 1.seconds)
            assertNull("the run ended", jobs.getPendingJob(OUTBOX_GUARD_JOB_ID))
        }

    @Test
    fun aStoppedRunCancelsItsGuardJob() =
        runTest {
            waiting.value = true
            val run = launch { runOutbox(context, waiting, sendNow = {}, finish = {}) }
            runCurrent()
            assertNotNull(jobs.getPendingJob(OUTBOX_GUARD_JOB_ID))

            run.cancel()
            runCurrent()

            assertNull(jobs.getPendingJob(OUTBOX_GUARD_JOB_ID))
        }

    @Test
    fun aDueGuardSchedulesTheOutboxJobAnewWhenItsRunDied() {
        val leftByTheDeadRun =
            JobInfo
                .Builder(OUTBOX_JOB_ID, ComponentName(context, OutboxJobService::class.java))
                .setExtras(PersistableBundle().apply { putString("run", "registered again after the process died") })
                .build()
        jobs.schedule(leftByTheDeadRun)

        guardRun(context, runGoing = false)

        val job = jobs.getPendingJob(OUTBOX_JOB_ID)
        assertNull("a new job, which Android checks at once", job!!.extras.getString("run"))
        assertTrue(job.isExpedited)
    }

    @Test
    fun aDueGuardLeavesARunOfThisProcessAndAMissingJobAlone() {
        guardRun(context, runGoing = false)
        assertNull("sent or signed out: no job to start", jobs.getPendingJob(OUTBOX_JOB_ID))

        val running =
            JobInfo
                .Builder(OUTBOX_JOB_ID, ComponentName(context, OutboxJobService::class.java))
                .setExtras(PersistableBundle().apply { putString("run", "the one running") })
                .build()
        jobs.schedule(running)
        guardRun(context, runGoing = true)
        assertEquals("scheduling it again would stop it", "the one running", jobs.getPendingJob(OUTBOX_JOB_ID)!!.extras.getString("run"))
    }

    @Test
    fun theGuardJobEndsAtOnceAndTheOutboxJobStartsARun() {
        var runs = 0
        jobs.schedule(outboxJob(context, expedited = false))

        assertFalse("done in onStartJob", startJob(context, OUTBOX_GUARD_JOB_ID, runGoing = false) { runs++ })
        assertEquals("the guard sends nothing itself", 0, runs)
        assertTrue("the outbox job, scheduled anew", jobs.getPendingJob(OUTBOX_JOB_ID)!!.isExpedited)

        assertTrue("goes on until jobFinished", startJob(context, OUTBOX_JOB_ID, runGoing = false) { runs++ })
        assertEquals(1, runs)
    }

    @Test
    fun nothingWaitingCancelsTheGuardJobToo() =
        runTest(UnconfinedTestDispatcher()) {
            jobs.schedule(guardJob(context))
            followOutbox(context, waiting, backgroundScope)

            waiting.value = false

            assertNull(jobs.getPendingJob(OUTBOX_GUARD_JOB_ID))
        }

    @Test
    fun onlyAStopForTheNetworkOrAnUntoldOneCountsAsTheNetworkGoing() {
        assertTrue("before Android 12", connectivityLost(null))
        assertTrue(connectivityLost(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY))
        assertFalse(connectivityLost(JobParameters.STOP_REASON_DEVICE_STATE))
        assertFalse(connectivityLost(JobParameters.STOP_REASON_QUOTA))
        assertFalse(connectivityLost(JobParameters.STOP_REASON_TIMEOUT))
    }

    @Test
    fun aRunAsksUntilNothingWaits() =
        runTest {
            var asked = 0
            val run = async { sendWaiting(waiting) { asked++ } }
            runCurrent()
            assertEquals(1, asked)

            waiting.value = true
            advanceTimeBy(SEND_AGAIN + 1.seconds)
            assertEquals("asked again, as the foreground timer would", 2, asked)

            waiting.value = false
            runCurrent()
            assertTrue(run.isCompleted)
            assertTrue("everything was sent", run.await())
        }

    @Test
    fun aRunGivesUpAfterItsWindowSoAndroidTriesLater() =
        runTest {
            waiting.value = true
            var asked = 0
            val run = async { sendWaiting(waiting) { asked++ } }

            advanceTimeBy(SEND_WINDOW + 1.seconds)

            assertTrue(run.isCompleted)
            assertFalse("still waiting: the job is run again after its backoff", run.await())
            assertTrue("asked every ${SEND_AGAIN}: $asked", asked >= (SEND_WINDOW / SEND_AGAIN).toInt())
        }

    @Test
    fun aRunWithNothingWaitingEndsAtOnce() =
        runTest {
            waiting.value = false
            assertTrue(sendWaiting(waiting) {})
        }
}
