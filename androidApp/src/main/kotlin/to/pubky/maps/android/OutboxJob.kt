package to.pubky.maps.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The one JobScheduler job of the app. */
internal const val OUTBOX_JOB_ID = 1

/**
 * How long one run of the job tries to send: three minutes, well inside the ten Android gives a job,
 * long enough for a session restore and a few writes on a slow network.
 */
internal val SEND_WINDOW: Duration = 3.minutes

/**
 * How often a run asks again: in the background the account tries an unreachable homeserver only
 * when asked (its timer runs in the foreground), so the job asks as the foreground timer would.
 */
internal val SEND_AGAIN: Duration = 30.seconds

/** The wait before a run that could not send everything is tried again; it doubles each time. */
private const val BACKOFF_MS = 30_000L

/**
 * While writes wait in the outbox ([waiting] true), a JobScheduler job is kept that Android runs as
 * soon as the phone has a network, also with the app closed and after a restart of the phone; it
 * starts the app's process, which restores the session and sends the outbox ([OutboxJobService]).
 * When nothing waits any more (sent, or signed out) the job is cancelled.
 *
 * JobScheduler, not WorkManager: one job needs no library, and WorkManager opens its own database
 * at every start of the app.
 */
internal fun followOutbox(
    context: Context,
    waiting: Flow<Boolean?>,
    scope: CoroutineScope,
): Job =
    scope.launch {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return@launch
        waiting.filterNotNull().distinctUntilChanged().collect { waits ->
            if (!waits) {
                jobs.cancel(OUTBOX_JOB_ID)
            } else if (jobs.getPendingJob(OUTBOX_JOB_ID) == null) {
                // Scheduling the job again would stop a run of it (the run that started this process).
                jobs.schedule(outboxJob(context))
            }
        }
    }

internal fun outboxJob(context: Context): JobInfo =
    JobInfo
        .Builder(OUTBOX_JOB_ID, ComponentName(context, OutboxJobService::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        // Writes made just before the phone was switched off go out after it starts again.
        .setPersisted(true)
        .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        .build()

/**
 * Sends what waits: asks the account and the outbox to try now ([sendNow]), again every [again],
 * until nothing waits; false when something still waited after [window].
 */
internal suspend fun sendWaiting(
    waiting: StateFlow<Boolean?>,
    window: Duration = SEND_WINDOW,
    again: Duration = SEND_AGAIN,
    sendNow: () -> Unit,
): Boolean =
    withTimeoutOrNull(window) {
        while (true) {
            sendNow()
            if (withTimeoutOrNull(again) { waiting.first { it == false } } != null) break
        }
        true
    } ?: false

/**
 * Runs when the phone has a network while writes wait. Starting the process restores the session
 * and builds the outbox ([PubkyMapsApplication.onCreate]); the job then waits until it is sent, and
 * Android runs it again later (after [BACKOFF_MS], doubling) when the homeserver could not be reached.
 */
class OutboxJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var run: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as PubkyMapsApplication
        run =
            scope.launch {
                val sent =
                    sendWaiting(app.writes.waiting) {
                        app.account.controller.onNetworkAvailable()
                        app.writes.flush()
                    }
                jobFinished(params, !sent)
            }
        return true
    }

    /** Android stopped the run (the network went, or its time ran out): it runs again unless nothing waits. */
    override fun onStopJob(params: JobParameters): Boolean {
        run?.cancel()
        return (application as PubkyMapsApplication).writes.waiting.value != false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
