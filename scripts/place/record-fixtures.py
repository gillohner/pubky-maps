#!/usr/bin/env python3
"""Records the place page fixtures the mapper tests read: Pinakes cards (by-uri), the lists holding
a place, the places in a map area, and Nexus' views of the same places (by_ids, replies, embeds,
users, collection posts), asked as the app asks for them.

Usage: record-fixtures.py <fixture dir> [pinakes base] [nexus base]
Run it again when the services' data moved on, then run the tests: they check what the fixtures
held when they were recorded (names, counts), so a changed answer shows up as a failing test.
"""
import json
import sys
import urllib.parse
import urllib.request

USER_AGENT = "PubkyMaps/fixtures"

# Places on the development instances with the data each section needs.
KUNSTHAUS = "https://www.openstreetmap.org/relation/3768127"  # also_on, two lists, tags, a rating reply
SCHOBER = "https://www.openstreetmap.org/node/270803095"  # tags by several people, replies, embeds, lists
GROSSMUENSTER_WIKIDATA = "https://www.wikidata.org/wiki/Q684948"  # image with credits, description
HAUPTBAHNHOF = "https://www.openstreetmap.org/node/1236383343"
HENRICI = "https://www.openstreetmap.org/node/633601362"
BBOX = "8.52,47.36,8.56,47.38"  # Zürich's old town


def request(url: str, body: dict | None = None) -> object:
    data = None if body is None else json.dumps(body).encode()
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read())


def save(out: str, name: str, value: object) -> None:
    with open(f"{out}/{name}", "w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"recorded {name}")


def main() -> None:
    out = sys.argv[1]
    pinakes = sys.argv[2] if len(sys.argv) > 2 else "https://8092--main--pinakes--gil.coder.riginode.xyz"
    nexus = sys.argv[3] if len(sys.argv) > 3 else "https://8080--main--pubky-staging--gil.coder.riginode.xyz"

    def by_uri(uri: str) -> dict:
        return request(f"{pinakes}/v1/resources/by-uri?" + urllib.parse.urlencode({"uri": uri}))

    kunsthaus = by_uri(KUNSTHAUS)
    schober = by_uri(SCHOBER)
    save(out, "pinakes-card-kunsthaus.json", kunsthaus)
    save(out, "pinakes-card-schober.json", schober)
    save(out, "pinakes-card-grossmuenster-wikidata.json", by_uri(GROSSMUENSTER_WIKIDATA))

    collections = request(f"{pinakes}/v1/resources/{schober['id']}/collections?limit=20")
    save(out, "pinakes-collections-schober.json", collections)
    save(out, "pinakes-bbox-zurich.json", request(f"{pinakes}/v1/search?" + urllib.parse.urlencode({"bbox": BBOX, "type": "Place", "referenced": "true", "sort": "activity", "limit": 500})))

    views = request(
        f"{nexus}/v1/stream/resources/by_ids?limit_tags=20&limit_taggers=5",
        {"uris": [KUNSTHAUS, SCHOBER, HAUPTBAHNHOF, HENRICI]},
    )
    save(out, "nexus-by-ids-zurich.json", views)
    ids = {view["details"]["uri"]: view["details"]["id"] for view in views}
    save(out, "nexus-replies-schober.json", request(f"{nexus}/v1/resource/{ids[SCHOBER]}/replies?limit=20"))
    save(out, "nexus-embeds-schober.json", request(f"{nexus}/v1/resource/{ids[SCHOBER]}/embeds?limit=20"))
    save(out, "nexus-replies-kunsthaus.json", request(f"{nexus}/v1/resource/{ids[KUNSTHAUS]}/replies?limit=20"))

    people = sorted({tagger for view in views for tag in view["tags"] for tagger in tag["taggers"]})
    save(out, "nexus-users.json", request(f"{nexus}/v1/stream/users/by_ids", {"user_ids": people}))

    post_ids = []
    for item in collections["items"]:
        author, post = item["uri"].removeprefix("pubky://").split("/pub/social/v1/posts/")
        post_ids.append(f"{author}:{post}")
    save(out, "nexus-collection-posts.json", request(f"{nexus}/v1/stream/posts/by_ids", {"post_ids": post_ids}))


if __name__ == "__main__":
    main()
