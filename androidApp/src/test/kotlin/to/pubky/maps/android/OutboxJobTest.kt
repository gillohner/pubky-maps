package to.pubky.maps.android

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.PersistableBundle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
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

            waiting.value = false
            assertNull("sent or signed out: nothing to wake the app for", jobs.getPendingJob(OUTBOX_JOB_ID))
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
