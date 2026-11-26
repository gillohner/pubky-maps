#!/usr/bin/env python3
"""Checks scripts/ci/gradle-advisories.py against a fake OSV: an advisory of a shipped library fails,
an ignored one (by id or alias) and a withdrawn one do not, a stale ignore fails, page tokens and more
than one batch are followed, an answer with too few results is refused, and the lists and ignores
are read strictly. CI: android.yml, build job.

Usage: gradle-advisories-check.py
"""
import importlib.util
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location("gradle_advisories", Path(__file__).with_name("gradle-advisories.py"))
adv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adv)
failures = []


def expect(got, want, what):
    if got != want:
        failures.append(f"{what}: {got!r}, not {want!r}")


def refuses(fn, text, what):
    try:
        fn(text)
        failures.append(f"{what}: not refused")
    except (ValueError, adv.tomllib.TOMLDecodeError):
        pass


OKIO = ("com.squareup.okio:okio", "3.3.0")
BC = ("org.bouncycastle:bcprov-jdk18on", "1.77")
KTOR = ("io.ktor:ktor-client-core", "3.6.1")
VULNS = {
    "GHSA-w33c-445m-f8w7": {"id": "GHSA-w33c-445m-f8w7", "summary": "Okio Signed to Unsigned Conversion Error",
                            "aliases": ["CVE-2023-3635"], "database_specific": {"severity": "MODERATE"}},
    "GHSA-bc01": {"id": "GHSA-bc01", "summary": "Bouncy Castle timing", "database_specific": {"severity": "HIGH"}},
    "GHSA-bc02": {"id": "GHSA-bc02", "summary": "Bouncy Castle old", "withdrawn": "2025-01-01T00:00:00Z"},
}


class FakeOsv:
    """querybatch answers: okio has one advisory, bouncycastle two over two pages, ktor none."""

    def __init__(self):
        self.batches = []
        self.got = []

    def post(self, url, body):
        expect(url, f"{adv.OSV}/querybatch", "querybatch address")
        self.batches.append(body["queries"])
        results = []
        for q in body["queries"]:
            expect(q["package"]["ecosystem"], "Maven", "ecosystem")
            lib = (q["package"]["name"], q["version"])
            if lib == OKIO:
                results.append({"vulns": [{"id": "GHSA-w33c-445m-f8w7"}]})
            elif lib == BC and "page_token" not in q:
                results.append({"vulns": [{"id": "GHSA-bc01"}], "next_page_token": "p2"})
            elif lib == BC:
                expect(q["page_token"], "p2", "page token")
                results.append({"vulns": [{"id": "GHSA-bc02"}]})
            else:
                results.append({})
        return {"results": results}

    def get(self, url):
        advisory = url.rsplit("/", 1)[1]
        self.got.append(advisory)
        return VULNS[advisory]


libs = adv.libraries("# shipped\n\ncom.squareup.okio:okio:3.3.0\norg.bouncycastle:bcprov-jdk18on:1.77\n"
                     "io.ktor:ktor-client-core:3.6.1\n")
expect(libs, [OKIO, BC, KTOR], "libraries()")
for bad in ("okio:3.3.0", "a:b:c:d", "a::1", "a:b:"):
    refuses(adv.libraries, bad, f"libraries({bad!r})")

osv = FakeOsv()
problems, notes = adv.check(libs, {}, osv.post, osv.get)
expect(len(osv.batches), 2, "querybatch requests (second for the page token)")
expect([q["package"]["name"] for q in osv.batches[1]], [BC[0]], "second page asks only bouncycastle")
expect(sorted(osv.got), sorted(VULNS), "advisories fetched once each")
expect(len(problems), 2, f"problems without ignores {problems}")
expect(any("okio:3.3.0: GHSA-w33c-445m-f8w7 (MODERATE)" in p for p in problems), True, "okio advisory fails")
expect(any("GHSA-bc01 (HIGH)" in p for p in problems), True, "bouncycastle advisory fails")
expect(any("GHSA-bc02" in p for p in problems), False, "withdrawn advisory does not fail")
expect(any("GHSA-bc02 is withdrawn" in n for n in notes), True, "withdrawn advisory is noted")

osv = FakeOsv()
ignored = {"CVE-2023-3635": "okio 3.3.0: never reads untrusted gzip", "GHSA-bc01": "constant-time path unused"}
problems, notes = adv.check(libs, ignored, osv.post, osv.get)
expect(problems, [], "ignores by alias and id")
expect(sum("ignored:" in n for n in notes), 2, "both ignores noted")

osv = FakeOsv()
problems, _ = adv.check(libs, dict(ignored, **{"GHSA-gone": "fixed long ago"}), osv.post, osv.get)
expect(problems, ["gradle/advisory-ignores.toml: GHSA-gone matches no shipped library any more; drop it"],
       "stale ignore fails")

osv = FakeOsv()
problems, _ = adv.check([KTOR], {}, osv.post, osv.get)
expect((problems, osv.got), ([], []), "clean library")

many = [(f"g:a{i}", "1") for i in range(adv.BATCH + 5)]
osv = FakeOsv()
adv.check(many, {}, osv.post, osv.get)
expect([len(b) for b in osv.batches], [adv.BATCH, 5], "batches of at most BATCH queries")


def short_post(url, body):
    return {"results": [{}]}


try:
    adv.check([OKIO, KTOR], {}, short_post, None)
    failures.append("an answer with too few results was not refused")
except ValueError:
    pass

expect(adv.ignores('[[ignore]]\nid = "GHSA-1"\nreason = "why"\n'), {"GHSA-1": "why"}, "ignores()")
expect(adv.ignores(Path(adv.IGNORES).read_text()), {}, "the repository's ignore file reads")
for bad, what in (('[[ignore]]\nid = "GHSA-1"\n', "no reason"),
                  ('[[ignore]]\nid = "GHSA-1"\nreason = " "\n', "blank reason"),
                  ('[[ignore]]\nreason = "why"\n', "no id"),
                  ('[[ignore]]\nid = "GHSA-1"\nreason = "a"\n[[ignore]]\nid = "GHSA-1"\nreason = "b"\n', "twice"),
                  ('[[ignore]]\nid = "GHSA-1"\nreason = "a"\nuntil = "x"\n', "unknown key"),
                  ('[[ignores]]\nid = "GHSA-1"\nreason = "a"\n', "unknown table")):
    refuses(adv.ignores, bad, f"ignores with {what}")

for f in failures:
    print(f"FAIL {f}")
print(f"gradle-advisories-check: {len(failures)} failures")
sys.exit(1 if failures else 0)
