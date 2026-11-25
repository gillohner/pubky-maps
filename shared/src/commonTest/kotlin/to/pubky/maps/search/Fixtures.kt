package to.pubky.maps.search

import okio.FileSystem
import okio.Path.Companion.toPath
import to.pubky.maps.links.GeoPoint

/** The recorded service answers in src/commonTest/fixtures/search (tests run in the module directory). */
internal object Fixtures {
    private val dir = "src/commonTest/fixtures/search".toPath()

    fun read(name: String): String = FileSystem.SYSTEM.read(dir / name) { readUtf8() }

    fun photon(slug: String): List<PhotonPlace> = parsePhoton(read("$slug.photon.json"))

    fun pinakes(slug: String): List<PinakesPlace> = parsePinakes(read("$slug.pinakes.json"))

    /** The map of the query set: Zürich at zoom 13 on a phone. */
    val zurich = SearchArea(centre = GeoPoint(47.3769, 8.5417), zoom = 13.0, halfDiagonalMeters = 3333.0)
}
