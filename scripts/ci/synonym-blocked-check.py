#!/usr/bin/env python3
"""Checks how scripts/ci/synonym-blocked.py reads `dumpsys jobscheduler` after it killed the app during a
run of the outbox job (rerun_after_kill), on excerpts of the dumps of run 45837259115: Android restarted
the crashed job service at once on attempt 1 and the run's guard job ran on attempt 2; both count, a
restart whose run has not set its own guard yet waits, and no start since the kill waits.
CI: android.yml, build job.

Usage: synonym-blocked-check.py
"""
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
spec = importlib.util.spec_from_file_location("synonym_blocked", HERE / "synonym-blocked.py")
blocked = importlib.util.module_from_spec(spec)
spec.loader.exec_module(blocked)
failures = []


def expect(got, want, what):
    if got != want:
        failures.append(f"{what}: {got!r}, not {want!r}")


def dump(jobs, history):
    """A `dumpsys jobscheduler` as Android 15 writes it, with only the parts the script reads."""
    return ("Registered 165 jobs:\n" + "".join(jobs)
            + "PrefetchController:\n  Cached launch times:\n\n  Job history:\n" + "".join(history)
            + "Pending queue:\n  None\n")


def job(job_id, key, enqueue):
    return (f"  JOB #u0a209/{job_id}: {key} to.pubky.maps/.android.OutboxJobService\n"
            "    u0a209 tag=*job*/to.pubky.maps/.android.OutboxJobService\n"
            "    Source: uid=u0a209 user=0 pkg=to.pubky.maps\n"
            f"    Enqueue time: -{enqueue}\n"
            "    Ready: false (job=true user=true !restricted=true !pending=true !active=false)\n")


def entry(age, kind, job_id, rest=""):
    return f"  {age:>17}   {kind:>5}: #u0a209/{job_id} to.pubky.maps/.android.OutboxJobService{rest}\n"


killed_run = [entry("-41s582ms", "START", 1), entry("-30s541ms", "STOP", 1, " unexpectedly disconnected")]

# Attempt 1, jobs-retry-030s.txt (30.5 s after the kill): Android started job 1 18 ms after the kill's STOP,
# and that run scheduled its own guard 0.66 s later.
android = dump([job(1, "b7bfa85", "30s533ms"), job(2, "4632718", "29s865ms")],
               killed_run + [entry("-30s523ms", "START", 1)])
expect(blocked.rerun_after_kill(android, 30.6), "android", "Android restarted the service (attempt 1)")

# Attempt 1, jobs-retry-000s.txt (0.2 s after the kill): restarted, but the guard registered is still the
# killed run's (scheduled before the restart), so it waits for the new run's.
early = dump([job(1, "b7bfa85", "173ms"), job(2, "efda89e", "10s443ms")],
             [entry("-11s231ms", "START", 1), entry("-190ms", "STOP", 1, " unexpectedly disconnected"),
              entry("-172ms", "START", 1)])
expect(blocked.rerun_after_kill(early, 0.2), None, "restart before its run set a guard")

# The same restart with no guard registered at all.
expect(blocked.rerun_after_kill(dump([job(1, "b7bfa85", "173ms")], killed_run[:1] + [
    entry("-190ms", "STOP", 1, " unexpectedly disconnected"), entry("-172ms", "START", 1)]), 0.2),
    None, "restart with no guard")

# Attempt 2, jobs-retry-after.txt (about 133 s after the kill): the guard ran, then job 1.
guard = dump([job(1, "6164734", "2s971ms"), job(2, "572afa3", "2s967ms")],
             [entry("-2m23s383ms", "START", 1), entry("-2m12s915ms", "STOP", 1, " unexpectedly disconnected"),
              entry("-3s471ms", "START", 2), entry("-2s975ms", "START", 1),
              entry("-2s974ms", "STOP", 2, " onStartJob returned false")])
expect(blocked.rerun_after_kill(guard, 133.0), "guard", "the guard job ran (attempt 2)")

# Attempt 2, jobs-retry-000s.txt and jobs-retry-030s.txt: no restart, the killed run's start (before the
# kill) and its guard (registered, not due yet) do not count.
killed_start = dump([job(1, "2386ea8", "177ms"), job(2, "f55bef4", "9s983ms")],
                    [entry("-10s660ms", "START", 1), entry("-192ms", "STOP", 1, " unexpectedly disconnected")])
expect(blocked.rerun_after_kill(killed_start, 0.2), None, "no start since the kill (0 s)")
waiting = dump([job(1, "2386ea8", "30s343ms"), job(2, "f55bef4", "40s149ms")],
               [entry("-40s821ms", "START", 1), entry("-30s353ms", "STOP", 1, " unexpectedly disconnected")])
expect(blocked.rerun_after_kill(waiting, 30.4), None, "no start since the kill (30 s)")

# Made up: Android restarted the service, that run was stopped, and the next run has not set its guard
# yet; a guard scheduled before the latest start is not that run's.
second = dump([job(1, "b7bfa85", "5s"), job(2, "4632718", "39s")],
              killed_run + [entry("-40s", "START", 1), entry("-6s", "STOP", 1, " stopped by the system"),
                            entry("-5s", "START", 1)])
expect(blocked.rerun_after_kill(second, 40.5), None, "a guard from before the latest start")

for age, want in (("172ms", 0.172), ("30s523ms", 30.523), ("2m12s915ms", 132.915), ("1h0m1s", 3601.0),
                  ("1d0h0m0s0ms", 86400.0)):
    expect(blocked.seconds(age), want, f"seconds({age})")
expect(blocked.enqueued(android, 2), 29.865, "enqueued(job 2)")
expect(blocked.enqueued(android, 1), 30.533, "enqueued(job 1)")
expect(blocked.enqueued(dump([job(1, "b7bfa85", "173ms")], []), 2), None, "enqueued(no job 2)")

if failures:
    print("FAIL synonym-blocked.py:\n  " + "\n  ".join(failures))
    sys.exit(1)
print("synonym-blocked.py reads the job states as Android 15 writes them")
