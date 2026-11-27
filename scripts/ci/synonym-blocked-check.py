#!/usr/bin/env python3
"""Checks how scripts/ci/synonym-blocked.py reads `dumpsys jobscheduler` after it killed the app during a
run of the outbox job, on excerpts of real dumps. How the job ran again (rerun_after_kill), run
45837259115: Android restarted the crashed job service at once on attempt 1 and the run's guard job ran
on attempt 2; both count, a restart whose run has not set its own guard yet waits, and no start since
the kill waits. That the kill ended a run (died_since), runs 45837259115 and 45874052666: a STOP naming
a dead process counts whichever way Android noticed it, other stops and stops before the kill do not.
How it waits for the phone's network (await_network), on a fake clock: a name that resolved once and
then not (Wi-Fi dropping after the boot, run 46040293875) is waited for until it kept resolving.
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

# That the kill ended a run. Run 45837259115: Android's service connection saw the process die.
expect(blocked.died_since(waiting, 30.4), ["unexpectedly disconnected"], "died: unexpectedly disconnected")
# Run 45874052666, jobs-retry-after.txt (about 82 s after the kill): Android met the dead process first
# when it told the run of a network change; then the guard ran and scheduled job 1 anew.
network = dump([job(1, "db7f313", "1s216ms"), job(2, "dbf84e", "1s210ms")],
               [entry("-1m32s540ms", "START", 1),
                entry("-1m21s550ms", "STOP", 1, " host crashed when trying to inform of network change"),
                entry("-1s778ms", "START", 2), entry("-1s219ms", "START", 1),
                entry("-1s217ms", "STOP", 2, " onStartJob returned false")])
expect(blocked.died_since(network, 81.6), ["host crashed when trying to inform of network change"],
       "died: host crashed when informed of a network change")
expect(blocked.rerun_after_kill(network, 81.6), "guard", "the guard job ran (run 45874052666)")
# A STOP from before the kill, and runs that ended on their own, do not count.
expect(blocked.died_since(network, 81.5), [], "died: a STOP before the kill")
expect(blocked.died_since(dump([], [entry("-6s", "STOP", 1, " stopped by the system"),
                                    entry("-1s", "STOP", 2, " onStartJob returned false")]), 7.0),
       [], "died: runs that ended on their own")
expect(blocked.died_since(dump([], [entry("-2s", "STOP", 2, " unexpectedly disconnected")]), 3.0), [],
       "died: the guard's own stop")
# Made up: the other two ways Android 15 ends a run whose process died.
expect(blocked.died_since(dump([], [entry("-2s", "STOP", 1, " binding died"),
                                    entry("-1s", "STOP", 1, " host crashed when trying to stop")]), 3.0),
       ["binding died", "host crashed when trying to stop"], "died: binding died, host crashed when stopped")

for age, want in (("172ms", 0.172), ("30s523ms", 30.523), ("2m12s915ms", 132.915), ("1h0m1s", 3601.0),
                  ("1d0h0m0s0ms", 86400.0)):
    expect(blocked.seconds(age), want, f"seconds({age})")
expect(blocked.enqueued(android, 2), 29.865, "enqueued(job 2)")
expect(blocked.enqueued(android, 1), 30.533, "enqueued(job 1)")
expect(blocked.enqueued(dump([job(1, "b7bfa85", "173ms")], []), 2), None, "enqueued(no job 2)")


class Phone:
    """A fake clock and a phone whose names resolve while `up(t, host)` is true; each check takes a second."""

    def __init__(self, up):
        self.t = 0.0
        self.up = up

    def resolves(self, host):
        self.t += 1.0
        return self.up(self.t, host)

    def now(self):
        return self.t

    def sleep(self, s):
        self.t += s


def await_network(up, hosts=("tiles.versatiles.org",)):
    """What await_network returns on a phone where names resolve while `up(t, host)`, and when."""
    phone = Phone(up)
    problem = blocked.await_network(phone.resolves, list(hosts), now=phone.now, sleep=phone.sleep)
    return problem, phone.t


# A check is a second, then 2 s until the next one.
# Run 46040293875 attempt 1: the first check resolves, Wi-Fi drops right after it (the log ends there, so
# its return at 20 s is made up); the test goes on only once the name kept resolving for the settle time
# after that (checks at 22..34 s).
expect(await_network(lambda t, h: t <= 1 or t >= 20), (None, 34.0), "Wi-Fi up, down, up again")
# Up from the start: on after the settle time (checks at 1..13 s), not at the first check.
expect(await_network(lambda t, h: True), (None, 13.0), "up from the start")
# Every host is checked in each round; the settle time counts from the round's end.
expect(await_network(lambda t, h: True, ("a.example", "b.example")), (None, 14.0), "two hosts up")
expect(await_network(lambda t, h: h != "b.example", ("a.example", "b.example")),
       ("b.example does not resolve on the phone after 120 s", 122.0), "a host that never resolves")
expect(await_network(lambda t, h: False, ("a.example", "b.example")),
       ("a.example, b.example do not resolve on the phone after 120 s", 122.0), "no network, two hosts")
expect(await_network(lambda t, h: False), ("tiles.versatiles.org does not resolve on the phone after 120 s", 121.0),
       "no network")
# A network that comes up just before the wait is over gets its settle time.
expect(await_network(lambda t, h: t >= 115), (None, 127.0), "a network up late")
# A network that comes and goes for the whole wait is no network either: it fails on its first miss after it.
expect(await_network(lambda t, h: t % 9 < 6),
       ("the phone's network did not stay up for 10 s in 120 s (tiles.versatiles.org does not resolve)", 124.0),
       "a network that comes and goes")

if failures:
    print("FAIL synonym-blocked.py:\n  " + "\n  ".join(failures))
    sys.exit(1)
print("synonym-blocked.py waits for a network that stays up and reads the job states as Android 15 writes them")
