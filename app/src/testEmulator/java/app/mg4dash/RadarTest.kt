package app.mg4dash

import app.mg4dash.radar.Ahead
import app.mg4dash.radar.CamKind
import app.mg4dash.radar.Camera
import app.mg4dash.radar.Country
import app.mg4dash.radar.DefaultLimits
import app.mg4dash.radar.Geo
import app.mg4dash.radar.Level
import app.mg4dash.radar.Matcher
import app.mg4dash.radar.RadarAlert
import app.mg4dash.radar.RadarData
import app.mg4dash.radar.Road
import app.mg4dash.radar.Speeds
import app.mg4dash.radar.Thresholds
import app.mg4dash.radar.Zones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Speed camera alerter (test, OSM only, Marc 16/11), on OpenStreetMap extracts (test resources in radar/, trimmed
 * Overpass answers): the north Boulevard Périphérique in Paris (both rings, 50), the Voie Pierre Mathis in Nice (50,
 * one way watched), Via Carlo Amoretti in Milan (untagged roads nearby) and Euston Road in London (20 mph).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RadarTest {
    private fun load(name: String): Pair<List<Road>, List<Camera>> =
        RadarData.parse(javaClass.getResourceAsStream("/radar/$name.json")!!.bufferedReader().readText())

    private fun zonesAt(data: Pair<List<Road>, List<Camera>>, lat: Double, lon: Double, heading: Float): Pair<app.mg4dash.radar.Match, List<Ahead.Zone>> {
        val m = Matcher().update(data.first, lat, lon, heading, 0)!!
        val path = Ahead.path(m, lat, lon, Ahead.Graph(data.first))
        return m to Ahead.zones(path, data.second)
    }

    private fun road(id: Long, hw: String, ms: Int?, oneway: Int, vararg pts: Pair<Double, Double>, first: Long = id * 10, fwd: Int? = null, bwd: Int? = null) =
        Road(id, hw, LongArray(pts.size) { first + it }, DoubleArray(pts.size) { pts[it].first }, DoubleArray(pts.size) { pts[it].second }, ms, fwd, bwd, oneway)

    // ── Paris, north Boulevard Périphérique (Porte de la Chapelle – Porte d'Aubervilliers) ──

    private val inner = setOf(1308268050L, 1308268047, 14257252, 165524945, 184752188, 10345998, 19923012, 19923016)
    private val outer = setOf(52985347L, 19922999, 19923007, 10345999, 165524944, 1308268048, 1308268049)

    @Test fun periphCamerasAndLimit() {
        val (roads, cams) = load("periph")
        assertTrue(roads.filter { it.id in inner || it.id in outer }.all { it.maxspeed == 50 && it.oneway == 1 })
        val innerCam = cams.first { it.id == 5329620318L }
        val outerCam = cams.first { it.id == 5329621078L }
        assertEquals(50, innerCam.maxspeed)
        // Enforcement from → to: the inner ring goes east here, the outer one west
        assertTrue(Geo.angle(innerCam.bearing!!, 90f) < 20f)
        assertTrue(Geo.angle(outerCam.bearing!!, 270f) < 20f)
    }

    @Test fun periphRingByHeading() {
        val (roads, _) = load("periph")
        // About 15 m apart: the ring is the one going the car's way
        assertTrue(Matcher().update(roads, 48.90080, 2.3605, 90f, 0)!!.road.id in inner)
        assertTrue(Matcher().update(roads, 48.90080, 2.3605, 270f, 0)!!.road.id in outer)
    }

    @Test fun periphZones() {
        val data = load("periph")
        // Inner ring eastwards: its camera only, a 500 m zone (50), the car already in it
        val (m, zones) = zonesAt(data, 48.90076, 2.3605, 90f)
        assertEquals(50, m.limit)
        assertFalse(m.estimated)
        val z = zones.first()
        assertEquals(5329620318L, z.cam.id)
        assertEquals(500, z.length)
        assertTrue(z.start < 0 && z.end > 0)
        assertTrue(zones.none { it.cam.id == 5329621078L })
        // Outer ring westwards from Porte d'Aubervilliers: the outer camera, ahead
        val (_, w) = zonesAt(data, 48.90040, 2.3765, 270f)
        assertEquals(5329621078L, w.first().cam.id)
        assertTrue(w.none { it.cam.id == 5329620318L })
    }

    // ── Nice, Voie Pierre Mathis ──

    @Test fun niceUrbanZoneOneWayOnly() {
        val data = load("nice")
        assertEquals(Country.FR, Country.of(43.707, 7.279))
        // Westwards: the camera checks this carriageway, a 500 m zone at 50
        val (m, zones) = zonesAt(data, 43.70701, 7.2797, 270f)
        assertEquals(206822554L, m.road.id)
        assertEquals(1, zones.size)
        assertEquals(500, zones[0].length)
        assertEquals(50, zones[0].limit)
        // Eastwards on the other carriageway, a few metres away: the camera checks the other way
        val (e, east) = zonesAt(data, 43.70704, 7.2776, 90f)
        assertEquals(80222841L, e.road.id)
        assertTrue(east.isEmpty())
    }

    // ── Milan, Via Carlo Amoretti ──

    @Test fun italianDefaultsAndZone() {
        val data = load("milano")
        assertEquals(Country.IT, Country.of(45.5194, 9.1418))
        // Untagged roads: the Italian defaults, estimated
        val unclassified = data.first.first { it.id == 273256041L }
        assertNull(unclassified.maxspeed)
        assertEquals(90, DefaultLimits.of(unclassified))
        assertEquals(50, DefaultLimits.of(data.first.first { it.id == 273256043L }))
        assertEquals(130, DefaultLimits.of("motorway", Country.IT))
        assertEquals(80, DefaultLimits.of("secondary", Country.FR))
        // The camera on the tagged street (50, westwards): the same zone rule as everywhere for now
        val (m, zones) = zonesAt(data, 45.51948, 9.14200, 270f)
        assertEquals(50, m.limit)
        assertEquals(1277834561L, zones.first().cam.id)   // device of enforcement 1578171
        assertEquals(500, zones.first().length)
    }

    // ── London, Euston Road ──

    @Test fun ukMphLimitsAndDefaults() {
        val (roads, cams) = load("london")
        assertEquals(Country.GB, Country.of(51.5278, -0.1294))
        val euston = roads.first { it.id == 746410871L }
        assertEquals(32, euston.maxspeed)                  // "20 mph"
        assertEquals(20, Speeds.toUnit(euston.maxspeed!!.toFloat(), miles = true))
        assertEquals(32, cams.first { it.id == 110100L }.maxspeed)
        assertEquals(80, DefaultLimits.parse("50 mph"))
        assertEquals(50, Speeds.toUnit(80f, miles = true))
        // UK defaults by road type, in km/h: 70 mph on motorways and dual carriageways, 60 single, 30 in towns
        assertEquals(113, DefaultLimits.of("motorway", Country.GB))
        assertEquals(113, DefaultLimits.of("trunk", Country.GB, oneway = 1))
        assertEquals(97, DefaultLimits.of("primary", Country.GB, oneway = 0))
        assertEquals(48, DefaultLimits.of("residential", Country.GB))
        val untagged = road(1, "primary", null, 0, 51.5280 to -0.1300, 51.5280 to -0.1280)
        val m = Matcher().update(listOf(untagged), 51.5280, -0.1290, 90f, 0)!!
        assertEquals(97, m.limit)
        assertTrue(m.estimated)
        assertEquals(Country.FR, Country.of(50.95, 1.85))    // Calais stays in France
    }

    // ── Generic ──

    @Test fun noRoadWhenFarOrAcross() {
        val r = road(1, "primary", 70, 0, 48.0 to 2.0, 48.0 to 2.01)
        assertNull(Matcher().update(listOf(r), 48.0006, 2.005, 90f, 0))
        assertNull(Matcher().update(listOf(r), 48.0, 2.005, 0f, 0))
        assertNotNull(Matcher().update(listOf(r), 48.0, 2.005, 270f, 0))
    }

    @Test fun keepsTheRoadUntilAnotherIsStableFor3s() {
        val a = road(1, "primary", 70, 0, 48.0 to 2.0, 48.0 to 2.01)
        val b = road(2, "trunk_link", 50, 0, 48.00012 to 2.004, 48.00012 to 2.006)
        val m = Matcher()
        assertEquals(1L, m.update(listOf(a, b), 48.0, 2.005, 90f, 0)!!.road.id)
        // 14 m north: b is much nearer, taken only once it has been the best for 3 s
        val lat = 48.0 + 14 / Geo.M_PER_LAT
        assertEquals(1L, m.update(listOf(a, b), lat, 2.005, 90f, 1_000)!!.road.id)
        assertEquals(1L, m.update(listOf(a, b), lat, 2.005, 90f, 3_500)!!.road.id)
        assertEquals(2L, m.update(listOf(a, b), lat, 2.005, 90f, 4_100)!!.road.id)
    }

    @Test fun directionalLimits() {
        val r = road(7, "primary", 70, 0, 48.0 to 2.0, 48.01 to 2.0, fwd = 50, bwd = 90)
        assertEquals(50, Matcher().update(listOf(r), 48.005, 2.0, 0f, 0)!!.limit)
        assertEquals(90, Matcher().update(listOf(r), 48.005, 2.0, 180f, 0)!!.limit)
    }

    @Test fun nextLimitAlongTheRoad() {
        val a = road(1, "trunk", 110, 1, 48.0 to 2.0, 48.0 to 2.02, first = 10)
        val b = road(2, "trunk", 90, 1, 48.0 to 2.02, 48.0 to 2.04, first = 11)
        val m = Matcher().update(listOf(a, b), 48.0, 2.01, 90f, 0)!!
        val (dist, lim) = Ahead.nextLimit(Ahead.path(m, 48.0, 2.01, Ahead.Graph(listOf(a, b))))!!
        assertEquals(90, lim)
        assertEquals(Geo.dist(48.0, 2.01, 48.0, 2.02), dist, 2.0)
    }

    @Test fun zoneLengthRule() {
        assertEquals(500, Zones.length(50, "residential"))
        assertEquals(500, Zones.length(30, "primary"))
        assertEquals(2000, Zones.length(80, "primary"))
        assertEquals(2000, Zones.length(90, "trunk"))
        assertEquals(4000, Zones.length(110, "trunk"))
        assertEquals(4000, Zones.length(130, "motorway"))
        assertEquals(2000, Zones.length(110, "primary"))
    }

    @Test fun directionFilter() {
        val east = Camera(1, 0.0, 0.0, CamKind.FIXED, 50, 95f)
        assertTrue(Zones.sameWay(east, 100f))
        assertFalse(Zones.sameWay(east, 280f))
        assertTrue(Zones.sameWay(Camera(2, 0.0, 0.0, CamKind.FIXED, null, null), 280f))
        assertEquals(180f, Zones.direction("180")!!, 0.01f)
        assertEquals(45f, Zones.direction("NE")!!, 0.01f)
        assertNull(Zones.direction("both"))
    }

    @Test fun thresholdsWithHysteresis() {
        val t = Thresholds(1f, 6f, percent = false, miles = false)
        assertEquals(Level.NONE, t.level(50f, 50, Level.NONE))
        assertEquals(Level.LIGHT, t.level(51f, 50, Level.NONE))
        assertEquals(Level.STRONG, t.level(56f, 50, Level.LIGHT))
        // Down by 1: still strong (2 km/h of hysteresis), down by 3: light
        assertEquals(Level.STRONG, t.level(55f, 50, Level.STRONG))
        assertEquals(Level.LIGHT, t.level(53f, 50, Level.STRONG))
        assertEquals(Level.LIGHT, t.level(49.5f, 50, Level.LIGHT))
        assertEquals(Level.NONE, t.level(48.5f, 50, Level.LIGHT))
        assertEquals(Level.NONE, t.level(200f, null, Level.STRONG))
    }

    @Test fun thresholdsInPercentAndMiles() {
        val pct = Thresholds(2f, 10f, percent = true, miles = false)
        assertEquals(11f, pct.marginKmh(10f, 110), 0.01f)
        assertEquals(Level.STRONG, pct.level(121f, 110, Level.NONE))
        assertEquals(Level.LIGHT, pct.level(113f, 110, Level.NONE))
        val mph = Thresholds(1f, 5f, percent = false, miles = true)
        assertEquals(8.05f, mph.marginKmh(5f, 110), 0.01f)
        assertEquals(68, Speeds.toUnit(110f, true))
        assertEquals(110, Speeds.toUnit(110f, false))
        assertEquals(112.65f, Speeds.toKmh(70f, true), 0.01f)
    }

    @Test fun defaultLimitByRoadType() {
        assertEquals(130, DefaultLimits.of("motorway"))
        assertEquals(110, DefaultLimits.of("trunk"))
        assertEquals(80, DefaultLimits.of("secondary"))
        assertEquals(50, DefaultLimits.of("residential"))
        assertEquals(20, DefaultLimits.of("living_street"))
        assertNull(DefaultLimits.of("footway"))
        val r = road(8, "residential", null, 0, 48.0 to 2.0, 48.001 to 2.0)
        val m = Matcher().update(listOf(r), 48.0005, 2.0, 0f, 0)!!
        assertEquals(50, m.limit)
        assertTrue(m.estimated)
    }

    @Test fun maxspeedTags() {
        assertEquals(50, DefaultLimits.parse("50"))
        assertEquals(48, DefaultLimits.parse("30 mph"))
        assertEquals(50, DefaultLimits.parse("FR:urban"))
        assertEquals(80, DefaultLimits.parse("FR:rural"))
        assertEquals(90, DefaultLimits.parse("IT:rural"))
        assertEquals(97, DefaultLimits.parse("GB:nsl_single"))
        assertEquals(113, DefaultLimits.parse("GB:nsl_dual"))
        assertEquals(30, DefaultLimits.parse("FR:zone30"))
        assertNull(DefaultLimits.parse("none"))
        assertNull(DefaultLimits.parse("signals"))
    }

    @Test fun tileRoundTrip() {
        val (roads, cams) = load("periph")
        val key = RadarData.Key.of(48.9008, 2.3614)
        val t = RadarData.Tile(key, RadarData.Level.FULL, 1234L, roads, cams)
        val back = RadarData.decode(key, RadarData.encode(t))!!
        assertEquals(RadarData.Level.FULL, back.level)
        assertEquals(1234L, back.fetched)
        assertEquals(roads.map { it.id }, back.roads.map { it.id })
        assertEquals(roads[3].lat[1], back.roads[3].lat[1], 1e-6)
        val cam = cams.first { it.id == 5329620318L }
        assertEquals(cam.bearing!!, back.cameras.first { it.id == cam.id }.bearing!!, 0.1f)
        assertNotNull(RadarData.keysAround(48.9008, 2.3614, 0.012, 0.017).firstOrNull { it == key })
    }

    @Test fun beepsOnLevels() {
        assertEquals(true, RadarAlert.beepFor(Level.STRONG, Level.LIGHT, RadarAlert.SOUND_STRONG))
        assertNull(RadarAlert.beepFor(Level.LIGHT, Level.NONE, RadarAlert.SOUND_STRONG))
        assertEquals(false, RadarAlert.beepFor(Level.LIGHT, Level.NONE, RadarAlert.SOUND_BOTH))
        assertNull(RadarAlert.beepFor(Level.STRONG, Level.LIGHT, RadarAlert.SOUND_NONE))
        assertNull(RadarAlert.beepFor(Level.STRONG, Level.STRONG, RadarAlert.SOUND_BOTH))
    }

    // ── Network ──

    @Test fun overpassServersTls12First() {
        // overpass-api.de only speaks TLS 1.3, which Android 9 lacks: last
        assertEquals("https://overpass-api.de/api/interpreter", app.mg4dash.radar.Overpass.order(0).last())
        val o = app.mg4dash.radar.Overpass.order(2)
        assertEquals(app.mg4dash.radar.Overpass.SERVERS[2], o.first())
        assertEquals(app.mg4dash.radar.Overpass.SERVERS.toSet(), o.toSet())
    }

    @Test fun overpassBusyAndBackoff() {
        assertTrue(app.mg4dash.radar.Overpass.busy(429) && app.mg4dash.radar.Overpass.busy(504) && app.mg4dash.radar.Overpass.busy(503))
        assertFalse(app.mg4dash.radar.Overpass.busy(400))
        assertEquals(5_000L, app.mg4dash.radar.Overpass.backoffMs(2, busy = false))
        assertEquals(10_000L, app.mg4dash.radar.Overpass.backoffMs(3, busy = false))
        assertEquals(30_000L, app.mg4dash.radar.Overpass.backoffMs(2, busy = true))
        assertEquals(120_000L, app.mg4dash.radar.Overpass.backoffMs(4, busy = true))
    }

    @Test fun wifiAlwaysFirst() {
        assertEquals(listOf("wifi", null), app.mg4dash.radar.Overpass.routes("wifi", wifiOnly = false))
        assertEquals(listOf("wifi"), app.mg4dash.radar.Overpass.routes("wifi", wifiOnly = true))
        assertEquals(listOf<String?>(null), app.mg4dash.radar.Overpass.routes<String>(null, wifiOnly = false))
        assertTrue(app.mg4dash.radar.Overpass.routes<String>(null, wifiOnly = true).isEmpty())
    }

    // ── Prepared trips ──

    @Test fun prepareRadiusBoxesAndEstimate() {
        val r = app.mg4dash.radar.RadarRepo
        assertEquals(listOf(10, 20, 30, 50, 80), r.PREPARE_RADII)
        assertEquals(30, app.mg4dash.system.Prefs(org.robolectric.RuntimeEnvironment.getApplication()).radarPrepareKm)
        // 30 km around Paris: 60 km square in boxes of 2 x 2 tiles (0.04°), about 15 x 21
        val boxes = r.prepareBoxes(48.86, 2.35, 30)
        assertTrue(boxes.size in 280..360)
        assertEquals(boxes.size, boxes.toSet().size)
        // Every point within 30 km is in one of the tiles fetched
        val tiles = boxes.flatMap { r.boxTiles(it) }
        listOf(48.86 + 0.26 to 2.35, 48.86 to 2.35 - 0.39, 48.86 - 0.26 to 2.35 + 0.39).forEach { (la, lo) ->
            assertTrue(RadarData.Key.of(la, lo) in tiles)
        }
        val sizes = r.PREPARE_RADII.map { r.prepareEstimate(it, 48.86) }
        assertEquals(sizes.sortedBy { it.first }, sizes)
        assertEquals(sizes[2].first * r.PREP_BOX_BYTES, sizes[2].second)
    }

    /** Boxes on the tile grid: a tile belongs to one box only, whatever the starting position, so it is never fetched in halves or kept twice. */
    @Test fun prepareBoxesOnTheTileGrid() {
        val r = app.mg4dash.radar.RadarRepo
        val a = r.prepareBoxes(48.86, 2.35, 10).flatMap { r.boxTiles(it) }
        val b = r.prepareBoxes(48.87, 2.37, 10).flatMap { r.boxTiles(it) }
        assertEquals(a.size, a.toSet().size)
        // Two preparations from nearby positions share whole tiles, the same keys
        assertTrue(a.toSet().intersect(b.toSet()).size > a.size / 2)
        r.prepareBoxes(48.86, 2.35, 10).forEach { sw ->
            assertEquals(0, Math.floorMod(sw.y, r.PREP_TILES)); assertEquals(0, Math.floorMod(sw.x, r.PREP_TILES))
        }
    }

    /** The same tile written again replaces its file: the kept data does not grow. */
    @Test fun sameTileWrittenTwiceKeptOnce() {
        val dir = java.nio.file.Files.createTempDirectory("radar").toFile()
        val (roads, cams) = load("periph")
        val key = RadarData.Key.of(48.9008, 2.3614)
        RadarData.write(dir, RadarData.Tile(key, RadarData.Level.MAIN, 1L, roads, cams))
        val (n1, size1) = RadarData.size(dir)
        RadarData.write(dir, RadarData.Tile(key, RadarData.Level.FULL, 2L, roads, cams))
        val (n2, size2) = RadarData.size(dir)
        assertEquals(1, n1); assertEquals(1, n2)
        assertTrue(Math.abs(size2 - size1) < 10)
        assertEquals(RadarData.Level.FULL, RadarData.read(dir, key)!!.level)
        dir.deleteRecursively()
    }
}
