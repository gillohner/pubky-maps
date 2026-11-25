#!/usr/bin/env python3
"""Records the search fixtures the query-set test reads: for every query in queries.json, the
Photon and Pinakes answers as the app asks for them (same parameters, same User-Agent).

Usage: record-fixtures.py <fixture dir> [photon base] [pinakes base]
Run it again when the services' data moved on; then check the expected first results in
queries.json still make sense and run the tests.
"""
import json
import sys
import time
import urllib.parse
import urllib.request

USER_AGENT = "PubkyMaps/fixtures"


def get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def main() -> None:
    out = sys.argv[1]
    photon = sys.argv[2] if len(sys.argv) > 2 else "https://photon.komoot.io"
    pinakes = sys.argv[3] if len(sys.argv) > 3 else "https://8092--main--pinakes--gil.coder.riginode.xyz"
    with open(f"{out}/queries.json", encoding="utf-8") as f:
        spec = json.load(f)
    lat, lon, zoom, radius = spec["lat"], spec["lon"], spec["zoom"], spec["radius_m"]
    for case in spec["queries"]:
        q, slug = case["query"], case["fixture"]
        photon_url = f"{photon}/api?" + urllib.parse.urlencode(
            {"q": q, "lat": lat, "lon": lon, "zoom": zoom, "limit": spec["limit"], "lang": spec["lang"]}
        )
        pinakes_url = f"{pinakes}/v1/search?" + urllib.parse.urlencode(
            {"q": q, "type": "Place", "near": f"{lat},{lon}", "radius": radius, "limit": spec["limit"]}
        )
        for name, url in (("photon", photon_url), ("pinakes", pinakes_url)):
            body = json.loads(get(url))
            with open(f"{out}/{slug}.{name}.json", "w", encoding="utf-8") as f:
                json.dump(body, f, ensure_ascii=False, indent=1)
                f.write("\n")
        print(f"recorded {q!r}")
        # Photon's public server asks for fair use.
        time.sleep(1)


if __name__ == "__main__":
    main()
