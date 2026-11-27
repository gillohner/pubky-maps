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

/** The job that sends the outbox. */
internal const val OUTBOX_JOB_ID = 1

/** The job that starts the outbox job again when the app's process died during a run of it ([guardRun]). */
internal const val OUTBOX_GUARD_JOB_ID = 2

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

/** When the guard job of a run is due: after the run's window, so only a run that never ended meets it. */
internal val GUARD_DELAY: Duration = SEND_WINDOW + 30.seconds

/**
 * Whether anything waits, from whether writes ([writes]) and notes ([notes]) wait: true when one does,
 * null while one of them is not known yet.
 */
internal fun anyWaiting(
    writes: Boolean?,
    notes: Boolean?,
): Boolean? =
    when {
        writes == true || notes == true -> true
        writes == null || notes == null -> null
        else -> false
    }

/**
 * While writes or notes to OpenStreetMap wait ([waiting] true, [anyWaiting]), a JobScheduler job is kept that Android runs as
 * soon as the phone has a network, also with the app closed and after a restart of the phone; it
 * starts the app's process, which restores the session and sends the outbox and the notes ([OutboxJobService]).
 * When nothing waits any more (sent, or signed out) the job is cancelled, and a run's guard job too.
 *
 * JobScheduler, not WorkManager: two jobs need no library, and WorkManager opens its own database
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
                jobs.cancel(OUTBOX_GUARD_JOB_ID)
            } else if (jobs.getPendingJob(OUTBOX_JOB_ID) == null) {
                // Scheduling the job again would stop a run of it (the run that started this process).
                scheduleOutboxJob(context)
            }
        }
    }

/**
 * Schedules the job anew: a new job starts with no failures, so its first backoff is the shortest again,
 * and Android checks at once whether it can run.
 *
 * Expedited from Android 12 on: Android does not hold an expedited job back for its flexible
 * constraints (charging, idle, battery not low) or to batch it with other apps' network jobs, which
 * can delay a regular job of a closed app by hours where they are on. When the app's expedited quota
 * is used up Android refuses one, so a regular job is scheduled instead (and stays regular until it
 * is scheduled anew); a run Android reschedules stays expedited while quota is left.
 */
internal fun scheduleOutboxJob(context: Context) {
    val jobs = context.getSystemService(JobScheduler::class.java) ?: return
    if (jobs.schedule(outboxJob(context, expedited = true)) != JobScheduler.RESULT_SUCCESS) {
        jobs.schedule(outboxJob(context, expedited = false))
    }
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

/**
 * A run's guard: due after [GUARD_DELAY] (latest one minute later), with no other constraint, so the
 * timer alone makes Android run it.
 */
internal fun guardJob(context: Context): JobInfo =
    JobInfo
        .Builder(OUTBOX_GUARD_JOB_ID, ComponentName(context, OutboxJobService::class.java))
        .setMinimumLatency(GUARD_DELAY.inWholeMilliseconds)
        .setOverrideDeadline((GUARD_DELAY + 1.minutes).inWholeMilliseconds)
        .build()

/**
 * The guard job is due, so a run of the outbox job started more than [GUARD_DELAY] ago never ended:
 * its process died ([runGoing] false: no run in this process). Android registers such a run again
 * with no backoff and every constraint met, but nothing makes it look at the job again (on the API 35
 * emulator it stayed ready and not started for more than 5 minutes), so the job is scheduled anew,
 * which Android checks at once. With no outbox job (sent, signed out) there is nothing to do.
 */
internal fun guardRun(
    context: Context,
    runGoing: Boolean,
) {
    // A new job starts with no failures: a process killed during every run is tried again about
    // every 2.5 minutes, where Android's own count would have stretched the wait.
    val jobs = context.getSystemService(JobScheduler::class.java) ?: return
    if (!runGoing && jobs.getPendingJob(OUTBOX_JOB_ID) != null) scheduleOutboxJob(context)
}

internal fun outboxJob(
    context: Context,
    expedited: Boolean,
): JobInfo {
    val job =
        JobInfo
            .Builder(OUTBOX_JOB_ID, ComponentName(context, OutboxJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            // Writes made just before the phone was switched off go out after it starts again.
            .setPersisted(true)
            .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
    if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) job.setExpedited(true)
    return job.build()
}

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
 * One run of the job: sends what waits ([sendWaiting]) while its guard job is scheduled (cancelled
 * when the run ends or is stopped; due only if the process dies first, [guardRun]), then tells
 * Android whether to run it again after its backoff ([finish] true: something still waits) and gives
 * a write made as the run ended a job again ([afterRun]).
 */
internal suspend fun runOutbox(
    context: Context,
    waiting: StateFlow<Boolean?>,
    sendNow: () -> Unit,
    finish: (reschedule: Boolean) -> Unit,
    window: Duration = SEND_WINDOW,
) {
    val jobs = context.getSystemService(JobScheduler::class.java)
    jobs?.schedule(guardJob(context))
    val sent =
        try {
            sendWaiting(waiting, window, sendNow = sendNow)
        } finally {
            jobs?.cancel(OUTBOX_GUARD_JOB_ID)
        }
    finish(!sent)
    afterRun(context, sent, waitsNow = waiting.value == true)
}

/**
 * Whether Android stopped a run because the network went, from the [stopReason] it told (null before
 * Android 12, which does not tell; most stops of a network job are the network going).
 */
internal fun connectivityLost(stopReason: Int?): Boolean =
    stopReason == null ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY)

/**
 * What [OutboxJobService.onStartJob] does for job [jobId]: the guard job runs at once ([guardRun],
 * false: done); the outbox job starts a run ([startRun], true: it goes on until `jobFinished`).
 */
internal fun startJob(
    context: Context,
    jobId: Int,
    runGoing: Boolean,
    startRun: () -> Unit,
): Boolean {
    if (jobId == OUTBOX_GUARD_JOB_ID) {
        guardRun(context, runGoing)
        return false
    }
    startRun()
    return true
}

/**
 * Runs when the phone has a network while writes or notes wait. Starting the process restores the
 * session and builds the outbox and the notes ([PubkyMapsApplication.onCreate]); the run then waits
 * until they are sent, and Android runs it again later (after [BACKOFF_MS], doubling) when the
 * homeserver or OpenStreetMap could not be reached.
 * Also runs a run's guard job ([OUTBOX_GUARD_JOB_ID], [guardRun]).
 * A run that starts while the app is open also asks the account and the outbox to try now, which
 * starts both their own waits between retries over (the account's session restore and the outbox's
 * resend timer; at most twice a run).
 *
 * Known limit: a backoff built up while the phone had a network but the homeserver could not be
 * reached stays when the network goes and comes back between two runs (only a run stopped by the
 * network going is followed by a fresh job), so with the app closed such a write goes out when that
 * backoff ends; opening the app sends it at once. A run whose process died is started again by its
 * guard as a fresh job, with no backoff kept.
 */
class OutboxJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var run: Job? = null

    override fun onStartJob(params: JobParameters): Boolean =
        startJob(this, params.jobId, runGoing = run?.isActive == true) {
            val app = application as PubkyMapsApplication
            run =
                scope.launch {
                    runOutbox(
                        this@OutboxJobService,
                        app.outboxWaiting,
                        sendNow = app::sendWaitingNow,
                        finish = { reschedule -> jobFinished(params, reschedule) },
                    )
                }
        }

    /** Android stopped the run (the network went, or it needed the resources). */
    override fun onStopJob(params: JobParameters): Boolean {
        if (params.jobId == OUTBOX_GUARD_JOB_ID) return false
        run?.cancel()
        return rescheduleAfterStop(
            this,
            waits = (application as PubkyMapsApplication).outboxWaiting.value != false,
            connectivityLost =
                connectivityLost(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.stopReason else null),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
