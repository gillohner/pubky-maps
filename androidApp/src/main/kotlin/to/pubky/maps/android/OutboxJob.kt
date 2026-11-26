package to.pubky.maps.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
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
 * How long one run of the job tries to send: one minute, enough for a session restore and a few
 * writes on a slow network. A homeserver that stays unreachable is tried again by Android after the
 * backoff, not by a long run: a run holds a wakelock and uses the app's job quota, which Android
 * refills slowly (and a used-up quota would delay the run when the homeserver is back).
 */
internal val SEND_WINDOW: Duration = 1.minutes

/**
 * When a run asks once more: in the background the account tries an unreachable homeserver only
 * when asked (its own timer runs in the foreground), and a first try at the start of a run can come
 * before the network works.
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
                scheduleOutboxJob(context)
            }
        }
    }

/** Schedules the job anew: a new job starts with no failures, so its first backoff is the shortest again. */
internal fun scheduleOutboxJob(context: Context) {
    context.getSystemService(JobScheduler::class.java)?.schedule(outboxJob(context))
}

/**
 * After a run sent everything ([sent]) and Android removed the job: a write made in the moment
 * between ([waitsNow]) gets a job again, as [followOutbox] may not have seen the outbox empty.
 */
internal fun afterRun(
    context: Context,
    sent: Boolean,
    waitsNow: Boolean,
) {
    if (sent && waitsNow) scheduleOutboxJob(context)
}

/**
 * Whether Android should run a stopped run again after its backoff. Nothing waiting: no. The network
 * went ([connectivityLost]): a new job instead, which runs as soon as the phone has one again (a
 * patchy connection must not stretch the backoff to hours). Otherwise (Android needed the resources):
 * yes.
 */
internal fun rescheduleAfterStop(
    context: Context,
    waits: Boolean,
    connectivityLost: Boolean,
): Boolean {
    if (!waits) return false
    if (!connectivityLost) return true
    scheduleOutboxJob(context)
    return false
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
 * and builds the outbox ([PubkyMapsApplication.onCreate]); the run then waits until it is sent, and
 * Android runs it again later (after [BACKOFF_MS], doubling) when the homeserver could not be reached.
 * A run that starts while the app is open also asks the account to try now, which starts the
 * account's own wait between retries over (at most twice a run).
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
                afterRun(this@OutboxJobService, sent, waitsNow = app.writes.waiting.value == true)
            }
        return true
    }

    /** Android stopped the run (the network went, or it needed the resources). */
    override fun onStopJob(params: JobParameters): Boolean {
        run?.cancel()
        // Before Android 12 the reason is not told; most stops of a network job are the network going.
        val connectivityLost =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                params.stopReason == JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY
        return rescheduleAfterStop(
            this,
            waits = (application as PubkyMapsApplication).writes.waiting.value != false,
            connectivityLost = connectivityLost,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
