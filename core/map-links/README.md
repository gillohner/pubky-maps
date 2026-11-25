# map-links

Reads and builds map links. No network, platform or FFI dependencies, so an app and a server
can share it (MIT).

## Reading

`parse(url) -> Option<MapLink>` reads a place from a link's text alone: `name`, `point`,
`osm` (element type and id), `zoom`, `query` (search text), `provider_id` (Google CID, Apple
`auid`/`place-id`, HERE id), `provider`, `needs_redirect` and the `original` link.
`find_in_text(text)` finds the first map link in shared text and takes the line above it as the
name when the link has none.

| Provider | Links read |
| --- | --- |
| `geo:` | `geo:lat,lon[,alt][;crs=wgs84][;u=…]`, Android `?z=` and `?q=lat,lon(Label)`; `geo:0,0?q=<text>` is a search with no point |
| Google Maps | `/maps/place/<name>/@lat,lon,zoom/data=…!3d<lat>!4d<lon>` (the `!3d/!4d` place point wins over the `@` view), `/maps/search/`, `/maps/dir/`, `/maps/@…`, `?q=`, `?query=` (`api=1`), `?daddr=`, `?destination=`, `?ll=`, `?cid=`, `ftid=`, `google.<tld>` and `maps.google.<tld>`, `google.navigation:q=`; `maps.app.goo.gl/<id>` and `goo.gl/maps/<id>` need a redirect |
| Apple Maps | `/place?coordinate=&name=&address=&place-id=`, `/directions?destination=`, `/search?query=&center=`, `/frame`, `/look-around`, legacy `?ll=&q=&sll=&daddr=&address=&auid=`; `maps.apple/p/<id>` needs a redirect |
| Waze | `waze.com/ul?ll=&q=&z=`, `ul/h<geohash>`, `live-map?h=`, `live-map/directions?to=ll.<lat>,<lon>`, `waze://?ll=` |
| OpenStreetMap | `/{node,way,relation}/<id>`, `/?mlat=&mlon=`, `#map=z/lat/lon`, `/go/<shortlink>` (with `?node=` etc.), `/search?query=`, `/directions?route=` |
| OsmAnd | `osmand.net/map?pin=lat,lon#z/lat/lon`, `/map?finish=`, `/go?lat=&lon=&z=&name=` |
| Organic Maps, CoMaps | `omaps.app`, `comaps.at`, `ge0.me` with a ge0 code or `lat,lon`, plus a name; `om://`, `cm://`, `comaps://`, `ge0://` (`map?ll=&n=`, `route?dll=&daddr=`, `search?query=`, ge0 codes) |
| HERE WeGo | `share.here.com/l/lat,lon,title`, `/r/<from>/<to>`, `/p/<id>` (id only), `here.com/?map=lat,lon,zoom&msg=` |

## Building

`build(app, target) -> Option<String>`, where `target` is a `Place` or `Directions` to a place
with a `TravelMode`. Place links exist for every app; directions for Google Maps (web and
`google.navigation:`), Apple Maps, Waze (driving), HERE WeGo and openstreetmap.org (no transit).
Organic Maps, CoMaps and OsmAnd have no documented "directions from where I am" link, and `geo:`
has none, so those return `None`.

## Short links

`resolve_redirect(fetcher, url, max_hops)` follows a short link and returns the first URL that
is not one, for `parse`. It reads only the `Location` header of each hop (HEAD, or GET when HEAD
is refused; the body is never read), over HTTPS, at most `MAX_REDIRECTS` (4) hops, and only to
short-link hosts; Google's consent interstitial is unwrapped from its `continue` parameter. The
caller supplies the HTTP through the `HeaderFetcher` trait.

## Place URIs

`canonical_place_uri(link)` is the OpenStreetMap element URL when the link names one, else
`geo:<lat>,<lon>` with at most 5 fraction digits (`geo_uri`, `format_coordinate`).

## Tests

```sh
cargo test -p map-links                       # unit, real-world fixtures, property tests
PROPTEST_CASES=1000000 cargo test --release -p map-links --test properties   # longer fuzz
```

`tests/fixtures.rs` holds real links (OsmAnd's parser tests, Organic Maps' url-processor and
ge0 vectors, openstreetmap.org short-link vectors, links checked live). `tests/properties.rs`
checks that every built link reads back as its place and fuzzes `parse` and `find_in_text`
(no panics, values in range).
