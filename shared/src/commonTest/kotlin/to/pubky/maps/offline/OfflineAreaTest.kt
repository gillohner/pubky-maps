package to.pubky.maps.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineAreaTest {
    private val switzerland = AreaBounds(west = 5.95, south = 45.81, east = 10.5, north = 47.81)
    private val zurich = AreaBounds(west = 8.448, south = 47.32, east = 8.625, north = 47.435)

    @Test
    fun countsTheTilesOfEveryZoomFromZeroToFourteen() {
        // go-pmtiles counts 37,314 for a slightly different box of Switzerland to zoom 14.
        assertEquals(37_326, tileCount(switzerland))
        assertEquals(130, tileCount(zurich))
    }

    @Test
    fun aPointNeedsOneTileAtEachZoom() {
        val point = AreaBounds(8.54, 47.37, 8.54, 47.37)
        assertEquals(15, tileCount(point))
        assertEquals(1, tileCount(point, maxZoom = 0))
    }

    @Test
    fun theWholeWorldIsEveryTileAndPolesStayInsideTheGrid() {
        val world = AreaBounds(-180.0, -90.0, 180.0, 90.0)
        // 1 + 4 + 16: zoom 0 to 2, each the full grid.
        assertEquals(21, tileCount(world, maxZoom = 2))
    }

    @Test
    fun anAreaWidensToTheEdgesOfTheTilesItTouches() {
        val tiles = tileBounds(zurich)
        // Zoom 14 tiles 8576..8584 x 5733..5741, edges as tilePoint gives them.
        assertEquals(tilePoint(14, 8576.0, 5733.0).lon, tiles.west)
        assertEquals(tilePoint(14, 8576.0, 5733.0).lat, tiles.north)
        assertEquals(tilePoint(14, 8585.0, 5742.0).lon, tiles.east)
        assertEquals(tilePoint(14, 8585.0, 5742.0).lat, tiles.south)
        assertTrue(tiles.west < zurich.west && tiles.south < zurich.south && tiles.east > zurich.east && tiles.north > zurich.north)
        // Just inside its edges it touches the same tiles: nothing more.
        val inside = 1e-9
        assertEquals(
            tileCount(zurich),
            tileCount(AreaBounds(tiles.west + inside, tiles.south + inside, tiles.east - inside, tiles.north - inside)),
        )
    }

    @Test
    fun aCityFitsInOneDownloadACountryDoesNot() {
        assertTrue(fitsInOneDownload(zurich))
        assertFalse(fitsInOneDownload(switzerland))
    }

    @Test
    fun theLimitLeavesRoomForTheStylesOtherSources() {
        // Grows a box around Zürich until it no longer fits: it stops short of 3,000 tiles.
        var half = 0.05
        while (fitsInOneDownload(AreaBounds(8.54 - half, 47.37 - half / 2, 8.54 + half, 47.37 + half / 2))) half += 0.01
        val last = AreaBounds(8.54 - half + 0.01, 47.37 - (half - 0.01) / 2, 8.54 + half - 0.01, 47.37 + (half - 0.01) / 2)
        assertTrue(tileCount(last) <= OfflineLimits.MAX_TILES - OfflineLimits.OTHER_SOURCES_MARGIN)
    }
}
