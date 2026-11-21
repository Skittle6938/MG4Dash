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
import app.mg4dash.radar.RadarRepo
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
 * Speed camera alerter (test, OSM only, 16/11), on OpenStreetMap extracts (test resources in radar/, trimmed
 * Overpass answers): the north Boulevard Périphérique in Paris (both rings, 50), the Voie Pierre Mathis in Nice (50,
 * one way watched), Via Carlo Amoretti in Milan (untagged roads nearby) and Euston Road in London (20 mph).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RadarTest {
    /** The servers' shared state (left out, preferred) starts clean for each test: no test depends on another's. */
    @org.junit.Before @org.junit.After fun cleanServers() = app.mg4dash.radar.Overpass.resetSkips()

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

    @Test fun switchesLimitAtAConnectedRoadBoundary() {
        val west = road(1, "primary", 50, 0, 48.0 to 2.0, 48.0 to 2.001, first = 10)
        val east = road(2, "primary", 70, 0, 48.0 to 2.001, 48.0 to 2.002, first = 11)
        val roads = listOf(west, east)
        val eastbound = Matcher()
        assertEquals(50, eastbound.update(roads, 48.0, 2.00096, 90f, 0)?.limit)
        assertEquals(70, eastbound.update(roads, 48.0, 2.00104, 90f, 1_000)?.limit)
        val westbound = Matcher()
        assertEquals(70, westbound.update(roads, 48.0, 2.00104, 270f, 0)?.limit)
        assertEquals(50, westbound.update(roads, 48.0, 2.00096, 270f, 1_000)?.limit)
        val skippedBoundary = Matcher()
        assertEquals(50, skippedBoundary.update(roads, 48.0, 2.00096, 90f, 0)?.limit)
        assertEquals(70, skippedBoundary.update(roads, 48.0, 2.0014, 90f, 1_000)?.limit)
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

    @Test fun mixedThresholdsUsePercentStartingAt100Kmh() {
        val mixed = Thresholds(1f, 5f, percent = false, miles = false,
            atOrAbove100 = Thresholds(2f, 5f, percent = true, miles = false))
        assertEquals(Level.STRONG, mixed.level(95f, 90, Level.NONE))
        assertEquals(Level.NONE, mixed.level(101f, 100, Level.NONE))
        assertEquals(Level.LIGHT, mixed.level(102f, 100, Level.NONE))
        assertEquals(Level.LIGHT, mixed.level(136f, 130, Level.NONE))
        assertEquals(Level.STRONG, mixed.level(136.5f, 130, Level.NONE))
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

    @Test fun alertKindsAndDefaults() {
        assertEquals(RadarAlert.Kind.ZONE, RadarAlert.kindOf(Level.STRONG, critical = true))
        assertEquals(RadarAlert.Kind.STRONG, RadarAlert.kindOf(Level.STRONG, critical = false))
        assertEquals(RadarAlert.Kind.LIGHT, RadarAlert.kindOf(Level.LIGHT, critical = false))
        assertNull(RadarAlert.kindOf(Level.NONE, critical = false))
        // Defaults (mockup, 17/11): approach chime 60 % once, light silent, strong beep 70 % every 10 s, zone loud 100 % every 5 s
        assertEquals(RadarAlert.Sound.CHIME, RadarAlert.Kind.APPROACH.sound); assertEquals(60, RadarAlert.Kind.APPROACH.volume); assertEquals(0, RadarAlert.Kind.APPROACH.repeat)
        assertEquals(RadarAlert.Sound.NONE, RadarAlert.Kind.LIGHT.sound)
        assertEquals(RadarAlert.Sound.BEEP, RadarAlert.Kind.STRONG.sound); assertEquals(70, RadarAlert.Kind.STRONG.volume); assertEquals(10, RadarAlert.Kind.STRONG.repeat)
        assertEquals(RadarAlert.Sound.LOUD, RadarAlert.Kind.ZONE.sound); assertEquals(100, RadarAlert.Kind.ZONE.volume); assertEquals(5, RadarAlert.Kind.ZONE.repeat)
        assertEquals(listOf(0, 5, 10, 60, 300), RadarAlert.REPEATS)
        val prefs = app.mg4dash.system.Prefs(org.robolectric.RuntimeEnvironment.getApplication())
        assertEquals(RadarAlert.Sound.LOUD, prefs.radarSoundOf(RadarAlert.Kind.ZONE))
        prefs.setRadarSoundOf(RadarAlert.Kind.ZONE, RadarAlert.Sound.BEEP)
        assertEquals(RadarAlert.Sound.BEEP, prefs.radarSoundOf(RadarAlert.Kind.ZONE))
    }

    @Test fun alertRepeater() {
        val r = RadarAlert.Repeater()
        val rep = { k: RadarAlert.Kind -> k.repeat }
        // A new alert sounds at once
        assertEquals(RadarAlert.Kind.STRONG, r.tick(RadarAlert.Kind.STRONG, 0, rep))
        assertNull(r.tick(RadarAlert.Kind.STRONG, 9_000, rep))
        assertEquals(RadarAlert.Kind.STRONG, r.tick(RadarAlert.Kind.STRONG, 10_000, rep))   // every 10 s
        // Into a zone: at once, then every 5 s
        assertEquals(RadarAlert.Kind.ZONE, r.tick(RadarAlert.Kind.ZONE, 11_000, rep))
        assertNull(r.tick(RadarAlert.Kind.ZONE, 15_000, rep))
        assertEquals(RadarAlert.Kind.ZONE, r.tick(RadarAlert.Kind.ZONE, 16_000, rep))
        // Back under the limit: nothing; a light alert never repeated
        assertNull(r.tick(null, 17_000, rep))
        assertEquals(RadarAlert.Kind.LIGHT, r.tick(RadarAlert.Kind.LIGHT, 18_000, rep))
        assertNull(r.tick(RadarAlert.Kind.LIGHT, 600_000, rep))
    }

    @Test fun repeatIntervalSurvivesDroppingBelowTheThreshold() {
        val r = RadarAlert.Repeater()
        val strong = RadarAlert.Kind.STRONG
        val light = RadarAlert.Kind.LIGHT
        val rep = { k: RadarAlert.Kind -> k.repeat }
        assertEquals(strong, r.tick(strong, 0, rep))
        assertNull(r.tick(null, 1_000, rep))
        assertNull(r.tick(strong, 2_000, rep))
        assertEquals(light, r.tick(light, 3_000, rep))
        assertNull(r.tick(strong, 9_999, rep))
        assertEquals(strong, r.tick(strong, 10_000, rep))
        assertEquals(light, r.tick(light, 10_001, rep))
        assertNull(r.tick(light, 600_000, rep))
    }

    // ── Network ──


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
        assertEquals(listOf(10, 20, 30, 50), r.PREPARE_RADII)
        assertEquals(20, app.mg4dash.system.Prefs(org.robolectric.RuntimeEnvironment.getApplication()).radarPrepareKm)
        // 30 km around Paris: 60 km square, blocks of 4 x 4 tiles (0.08°) per request, about 8 x 11
        val boxes = r.prepareBoxes(48.86, 2.35, 30)
        assertTrue("${boxes.size}", boxes.size in 60..110)
        assertEquals(boxes.size, boxes.toSet().size)
        // Every point within 30 km is in one of the tiles fetched
        val tiles = boxes.flatMap { r.boxTiles(it) }
        listOf(48.86 + 0.26 to 2.35, 48.86 to 2.35 - 0.39, 48.86 - 0.26 to 2.35 + 0.39).forEach { (la, lo) ->
            assertTrue(RadarData.Key.of(la, lo) in tiles)
        }
        val sizes = r.PREPARE_RADII.map { r.prepareEstimate(it, 48.86) }
        assertEquals(sizes.sortedBy { it.boxes }, sizes)
        val e = sizes[2]
        // Counted in tiles (16 per block); in Europe from dresel, sent whole
        assertEquals(r.prepareBoxes(48.86, 0.025, 30).size * 16, e.boxes)
        assertEquals(e.boxes * r.PREP_BOX_BYTES, e.download)
        assertEquals(e.boxes * r.PREP_BOX_BYTES_KEPT, e.kept)
        assertTrue("${e.minutes}", e.minutes in 15..50)
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

    /** 50 km at most (17/11): a radius stored before (80) comes back as 50; the compressing server asked first. */
    @Test fun preparationRadiusClamped() {
        val r = app.mg4dash.radar.RadarRepo
        assertEquals(50, r.prepareRadius(80))
        assertEquals(30, r.prepareRadius(30))
        assertEquals(10, r.prepareRadius(5))
        val prefs = app.mg4dash.system.Prefs(org.robolectric.RuntimeEnvironment.getApplication())
        prefs.raw.edit().putInt("radar_prepare_km", 80).commit()
        assertEquals(50, prefs.radarPrepareKm)
    }

    // ── Radar5: HTTP answers and live refresh ──

    private fun http(head: String, body: ByteArray) = (head.toByteArray(Charsets.ISO_8859_1) + body).inputStream()

    @Test fun httpAnswerChunkedAndGzip() {
        val json = "{\"elements\":[]}".toByteArray()
        val gz = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { g -> g.write(json) } }.toByteArray()
        // Two chunks, gzip
        val a = gz.copyOfRange(0, 10); val b = gz.copyOfRange(10, gz.size)
        val chunked = "${Integer.toHexString(a.size)}\r\n".toByteArray() + a + "\r\n${Integer.toHexString(b.size)}\r\n".toByteArray() + b + "\r\n0\r\n\r\n".toByteArray()
        val r = app.mg4dash.radar.RadarHttp.readResponse(http("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Encoding: gzip\r\n\r\n", chunked))
        assertEquals(200, r.code)
        assertEquals(String(json), String(r.body))
        // Content-Length, plain
        val r2 = app.mg4dash.radar.RadarHttp.readResponse(http("HTTP/1.1 429 Too Many Requests\r\nContent-Length: 4\r\n\r\n", "busy".toByteArray()))
        assertEquals(429, r2.code); assertEquals("busy", String(r2.body))
    }

    @Test fun liveRefreshOfTheTileUnderTheCar() {
        val r = app.mg4dash.radar.RadarRepo
        val key = RadarData.Key.of(48.9, 2.36)
        val h = 3_600_000L
        val now = 100 * 24 * h
        // With a road: a tile without any is asked again after an hour (emptyRegionalAnswerNotTrusted)
        val road = Road(1, "primary", longArrayOf(1, 2), doubleArrayOf(48.9, 48.901), doubleArrayOf(2.36, 2.361), 50)
        fun tile(ageH: Long, level: RadarData.Level = RadarData.Level.FULL) = RadarData.Tile(key, level, now - ageH * h, listOf(road), emptyList())
        assertTrue(r.needsFetch(null, now, 6 * h))                              // missing
        assertFalse(r.needsFetch(tile(2), now, 6 * h))                          // 2 h old, refresh after 6 h
        assertTrue(r.needsFetch(tile(7), now, 6 * h))                           // older than the refresh age
        assertFalse(r.needsFetch(tile(7), now, 24 * h))                         // 1 day chosen
        assertTrue(r.needsFetch(tile(1, RadarData.Level.MAIN), now, 24 * h))    // main roads only: completed
        assertTrue(r.needsFetch(tile(31 * 24), now, 720 * h))                   // past the 30 days in any case
        assertEquals(listOf(1, 2, 6, 24, 168, 720), r.REFRESH_HOURS)
        val prefs = app.mg4dash.system.Prefs(org.robolectric.RuntimeEnvironment.getApplication())
        assertEquals(6, prefs.radarRefreshHours)
        prefs.raw.edit().putInt("radar_refresh_hours", 5).commit()
        assertEquals(6, prefs.radarRefreshHours)                                // not offered: the default
    }

    // ── Radar5b: saturated servers, blocks split ──

    @Test fun busyAnswersAndStatusPage() {
        val o = app.mg4dash.radar.Overpass
        assertTrue(o.busy(502))                      // "at capacity" from overpass-api.de
        assertEquals(30_000L, o.busyWaitMs(1)); assertEquals(60_000L, o.busyWaitMs(2)); assertEquals(300_000L, o.busyWaitMs(9))
        assertEquals(0, o.slotWait("Connected as: 1\nRate limit: 2\n2 slots available now.\nCurrently running queries"))
        assertEquals(12, o.slotWait("Rate limit: 2\nSlot available after: 2026-11-17T12:00:12Z, in 12 seconds.\nSlot available after: 2026-11-17T12:00:40Z, in 40 seconds."))
        assertEquals(0, o.slotWait("Slot available after: 2026-11-17T12:00:12Z, in -3 seconds."))
        assertNull(o.slotWait("<html>502</html>"))
    }

    @Test fun blocksSplitIntoQuartersThenTiles() {
        val r = app.mg4dash.radar.RadarRepo
        val sw = RadarData.Key(2440, 117)
        assertEquals(4, r.blockTiles(sw, 2).size)
        val q = r.subBlocks(sw, 2)
        assertEquals(4, q.size)
        assertTrue(q.all { it.second == 1 })
        // The quarters cover exactly the block's tiles, each once
        assertEquals(r.blockTiles(sw, 2).toSet(), q.flatMap { r.blockTiles(it.first, it.second) }.toSet())
        assertTrue(r.subBlocks(sw, 1).isEmpty())
        // A 4 x 4 block: quarters of 2 x 2
        assertEquals(listOf(2, 2, 2, 2), r.subBlocks(sw, 4).map { it.second })
        assertEquals(16, r.subBlocks(sw, 4).flatMap { r.blockTiles(it.first, it.second) }.toSet().size)
    }

    /** Saturated servers (17/11: "29 s" stayed on screen): the wait counts down from the worker's absolute time. */
    @Test fun retryCountdownFromAbsoluteTime() {
        val t0 = 1_000_000L
        val p = RadarRepo.Prep(3, 140, 0, RadarRepo.PrepPhase.RETRY, busyUntil = t0 + 29_500)
        assertEquals(30L, p.retryLeftS(t0))
        assertEquals(29L, p.retryLeftS(t0 + 1_000))
        assertEquals(1L, p.retryLeftS(t0 + 29_000))
        assertEquals(1L, p.retryLeftS(t0 + 40_000))     // overdue: the try is under way, never 0 or negative
        assertEquals(null, p.copy(phase = RadarRepo.PrepPhase.RUNNING).retryLeftS(t0))
        assertEquals(null, p.copy(busyUntil = 0).retryLeftS(t0))
    }

    /** The waits between rounds while the servers stay saturated: 30 s, then doubling up to 5 min, or the server's own. */
    @Test fun busyWaitsGrow() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), (1..6).map { app.mg4dash.radar.Overpass.busyWaitMs(it) })
    }

    /** A preparation cut by the app stopping goes on at the next start, around the same place, then forgets it when cancelled. */
    @Test fun preparationTakenUpAgainAtStart() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val sp = ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        sp.edit().putString("radar_prep_resume", "48.8966,2.3354").commit()
        RadarRepo.start(ctx)
        try {
            assertEquals(true, RadarRepo.prepare.value?.running)
        } finally {
            RadarRepo.cancelPrepare()
            // The page shows it stopped at once; the worker then forgets the place
            val until = System.currentTimeMillis() + 5_000
            while (sp.getString("radar_prep_resume", null) != null && System.currentTimeMillis() < until) Thread.sleep(100)
            RadarRepo.stop()
        }
        assertEquals(RadarRepo.PrepPhase.CANCELLED, RadarRepo.prepare.value?.phase)
        assertEquals(null, sp.getString("radar_prep_resume", null))
    }



    /** A query stopped half-way comes back with HTTP 200 and a "remark": a failed try, never a tile kept with roads missing. */
    @Test fun runtimeErrorIsNotData() {
        val o = app.mg4dash.radar.Overpass
        val stopped = """{"version":0.6,"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 1 after 26 seconds."}"""
        assertNotNull(o.runtimeError(stopped))
        assertNull(o.runtimeError("""{"version":0.6,"elements":[{"type":"way","id":1}]}"""))
        // A remark that is not an error (Overpass also sends notes) is data
        assertNull(o.runtimeError("""{"elements":[],"remark":"note: nothing found"}"""))
    }

    /** The live downloads start with the tile under the car, then the nearest ones. */
    @Test fun carTileFirst() {
        val lat = 45.0101; val lon = 7.0105
        val keys = RadarData.keysAround(lat, lon, 0.012, 0.017)
        val sorted = keys.sortedBy { RadarRepo.tileDistance(it, lat, lon) }
        assertEquals(RadarData.Key.of(lat, lon), sorted.first())
        assertTrue(keys.first() != sorted.first())
    }

    /** Look-ahead (18/11): the car's tile first and complete, then those along the heading, about 2 min of driving. */
    @Test fun liveLookAhead() {
        val lat = 45.0101; val lon = 7.0105
        val here = RadarData.Key.of(lat, lon)
        // Northbound at 130 km/h: 2 min is about 4.3 km ahead
        val fast = RadarRepo.liveWants(lat, lon, 0f, 130f)
        assertEquals(here to RadarData.Level.FULL, fast.first())
        val north = RadarData.Key.of(lat + 4_000 / 111_320.0, lon)
        val iNorth = fast.indexOfFirst { it.first == north }
        assertTrue("tile 4 km ahead wanted", iNorth > 0)
        assertEquals("far ahead, main roads only", RadarData.Level.MAIN, fast[iNorth].second)
        // Ahead before the side and rear tiles
        val south = RadarData.Key.of(lat - 1_200 / 111_320.0, lon)
        assertTrue(iNorth < fast.indexOfFirst { it.first == south })
        // Slow: complete tiles, not as far
        val slow = RadarRepo.liveWants(lat, lon, 0f, 40f)
        assertTrue(slow.all { it.second == RadarData.Level.FULL })
        assertTrue(slow.none { it.first == north })
        // No heading (parked): the car's tile and those around, complete
        assertTrue(RadarRepo.liveWants(lat, lon, null, null).all { it.second == RadarData.Level.FULL })
    }

    /** A main-roads tile never stands in for the complete one once the car gets near; it does for the far ahead. */
    @Test fun lightTileUpgradedWhenNear() {
        val now = 10_000_000L
        val light = RadarData.Tile(RadarData.Key(1, 1), RadarData.Level.MAIN, now - 1_000, emptyList(), emptyList())
        assertTrue(RadarRepo.needsFetch(light, now, 3_600_000L, RadarData.Level.FULL))
        assertFalse(RadarRepo.needsFetch(light, now, 3_600_000L, RadarData.Level.MAIN))
        val full = RadarData.Tile(RadarData.Key(1, 1), RadarData.Level.FULL, now - 1_000, emptyList(), emptyList())
        assertFalse(RadarRepo.needsFetch(full, now, 3_600_000L, RadarData.Level.MAIN))
    }

    /** Old tiles stay in use until a new one is in (18/11): the three cases. */
    @Test fun oldTilesKept() {
        val now = 100L * 86_400_000L
        val road = Road(1, "primary", longArrayOf(1L, 2L), doubleArrayOf(45.0, 45.001), doubleArrayOf(7.0, 7.001), 50)
        val old = RadarData.Tile(RadarData.Key(1, 1), RadarData.Level.FULL, now - 40L * 86_400_000L, listOf(road), emptyList())
        // 1. Past its 30 days: still a tile (used), only wanted again first
        assertFalse(old.fresh(now))
        assertTrue(RadarRepo.needsFetch(old, now, 6 * 3_600_000L))
        // 2. A failed or stopped download, or an answer with no road, never replaces it
        assertNull(RadarRepo.replacement(old, null))
        assertNull(RadarRepo.replacement(old, RadarData.Tile(old.key, RadarData.Level.FULL, now, emptyList(), emptyList())))
        // 3. A main-roads tile never replaces a complete one, even older; it fills an empty place
        val light = RadarData.Tile(old.key, RadarData.Level.MAIN, now, listOf(road), emptyList())
        assertNull(RadarRepo.replacement(old, light))
        assertEquals(light, RadarRepo.replacement(null, light))
        assertFalse(RadarRepo.needsFetch(old, now, 6 * 3_600_000L, RadarData.Level.MAIN))
        // A good complete answer does replace it
        val fresh = RadarData.Tile(old.key, RadarData.Level.FULL, now, listOf(road), emptyList())
        assertEquals(fresh, RadarRepo.replacement(old, fresh))
    }


    /** Stop (18/11): the request in progress is cut at once and the page shows it stopped now. */
    @Test fun stopCutsTheRequestAtOnce() {
        val server = java.net.ServerSocket(0)
        val accepted = java.util.concurrent.CountDownLatch(1)
        Thread { try { server.accept(); accepted.countDown(); Thread.sleep(30_000) } catch (_: Exception) {} }.apply { isDaemon = true; start() }
        var error: Throwable? = null
        val t = Thread {
            try {
                val s = java.net.Socket()
                val f = app.mg4dash.radar.RadarHttp::class.java.getDeclaredField("open").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") (f.get(app.mg4dash.radar.RadarHttp) as MutableMap<Thread, java.net.Socket>)[Thread.currentThread()] = s
                s.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 2_000)
                s.soTimeout = 30_000
                s.getInputStream().read()   // waits as a slow server would
            } catch (e: Throwable) { error = e }
        }
        t.start()
        assertTrue(accepted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val start = System.currentTimeMillis()
        app.mg4dash.radar.RadarHttp.abort(t)
        t.join(5_000)
        assertFalse(t.isAlive)
        assertTrue("cut in ${System.currentTimeMillis() - start} ms", System.currentTimeMillis() - start < 2_000)
        assertTrue(error is java.io.IOException)
        server.close()
    }

    // ── Overpass servers (18/11: by region, dresel first, one request for several tiles) ──

    @Test fun serversByRegion() {
        val o = app.mg4dash.radar.Overpass
        o.resetSkips()
        assertEquals(app.mg4dash.radar.Overpass.Region.EUROPE, o.region(48.89, 2.33))        // Paris
        assertEquals(app.mg4dash.radar.Overpass.Region.EUROPE, o.region(51.5, -0.12))        // London
        assertEquals(app.mg4dash.radar.Overpass.Region.EUROPE, o.region(41.9, 12.5))         // Rome
        assertEquals(app.mg4dash.radar.Overpass.Region.NORTH_AMERICA, o.region(40.7, -74.0)) // New York
        assertEquals(app.mg4dash.radar.Overpass.Region.OTHER, o.region(-33.9, 151.2))        // Sydney
        assertEquals(o.DRESEL_EU, o.order(app.mg4dash.radar.Overpass.Region.EUROPE).first())
        assertEquals(o.DRESEL_NA, o.order(app.mg4dash.radar.Overpass.Region.NORTH_AMERICA).first())
        assertEquals(o.OFFICIAL, o.order(app.mg4dash.radar.Overpass.Region.OTHER))
        assertEquals(o.OFFICIAL, o.order(app.mg4dash.radar.Overpass.Region.EUROPE).drop(1))
        // Never these
        assertTrue(o.SERVERS.none { it.contains("mail.ru") || it.contains("private.coffee") })
        assertTrue(o.USER_AGENT.startsWith("MG4Dash/") && o.USER_AGENT.contains("github.com/Skittle6938/MG4Dash"))
        assertEquals(45_000, o.readTimeoutMs(o.DRESEL_EU)); assertEquals(40, o.queryTimeoutS(o.DRESEL_EU))
        assertEquals(90_000, o.readTimeoutMs(o.OFFICIAL[0])); assertEquals(85, o.queryTimeoutS(o.OFFICIAL[0]))
    }

    @Test fun lastWorkingServerFirstPerRegion() {
        val o = app.mg4dash.radar.Overpass
        o.resetSkips()
        val z = o.OFFICIAL[0]
        o.worked(app.mg4dash.radar.Overpass.Region.EUROPE, z)
        assertEquals(z, o.order(app.mg4dash.radar.Overpass.Region.EUROPE).first())
        // Not elsewhere
        assertEquals(o.DRESEL_NA, o.order(app.mg4dash.radar.Overpass.Region.NORTH_AMERICA).first())
        o.resetSkips()
    }

    @Test fun silentOrBusyServerLeftOut() {
        val o = app.mg4dash.radar.Overpass
        o.resetSkips()
        val t = 1_000_000L
        // No answer twice in a row: left out 30 min
        o.noAnswer(o.DRESEL_EU, t); assertEquals(o.DRESEL_EU, o.order(app.mg4dash.radar.Overpass.Region.EUROPE, t).first())
        o.noAnswer(o.DRESEL_EU, t); assertEquals(o.OFFICIAL, o.order(app.mg4dash.radar.Overpass.Region.EUROPE, t + 1))
        assertEquals(o.DRESEL_EU, o.order(app.mg4dash.radar.Overpass.Region.EUROPE, t + o.SKIP_STEPS_MS[0]).first())
        o.resetSkips()
        // 429 / 406: paused 30 s
        assertTrue(o.tooMany(429) && o.tooMany(406) && !o.tooMany(502))
        o.pause(o.DRESEL_EU, t)
        assertEquals(o.OFFICIAL, o.order(app.mg4dash.radar.Overpass.Region.EUROPE, t + 1))
        assertEquals(o.DRESEL_EU, o.order(app.mg4dash.radar.Overpass.Region.EUROPE, t + o.PAUSE_MS).first())
        // All left out: all tried anyway
        o.OFFICIAL.forEach { o.pause(it, t) }; o.pause(o.DRESEL_EU, t)
        assertEquals(o.servers(app.mg4dash.radar.Overpass.Region.EUROPE), o.order(app.mg4dash.radar.Overpass.Region.EUROPE, t + 1))
        // 502 / 504: busy, the next one at once (not paused)
        assertTrue(o.busy(502) && o.busy(504))
        o.resetSkips()
    }

    /** One request for the tiles wanted together, at most 4 tiles across, the same level only. */
    @Test fun liveTilesInOneRequest() {
        val F = RadarData.Level.FULL; val M = RadarData.Level.MAIN
        val want = listOf(RadarData.Key(100, 200) to F, RadarData.Key(100, 201) to F, RadarData.Key(101, 200) to F,
            RadarData.Key(103, 200) to F, RadarData.Key(104, 200) to F, RadarData.Key(100, 202) to M)
        val g = RadarRepo.liveGroup(want)
        assertEquals(listOf(RadarData.Key(100, 200), RadarData.Key(100, 201), RadarData.Key(101, 200), RadarData.Key(103, 200)), g)
        assertTrue(RadarRepo.liveGroup(emptyList()).isEmpty())
    }

    /** A big answer split into tiles gives each tile what a request for it alone would have. */
    @Test fun splitMatchesTileByTile() {
        val a = RadarData.Key(2000, 300); val b = RadarData.Key(2000, 301)
        fun lat(k: RadarData.Key, f: Double) = (k.y + f) * RadarData.TILE
        fun lon(k: RadarData.Key, f: Double) = (k.x + f) * RadarData.TILE
        val inA = Road(1, "primary", longArrayOf(1, 2), doubleArrayOf(lat(a, .2), lat(a, .4)), doubleArrayOf(lon(a, .2), lon(a, .4)), 50)
        val across = Road(2, "secondary", longArrayOf(3, 4), doubleArrayOf(lat(a, .5), lat(b, .5)), doubleArrayOf(lon(a, .9), lon(b, .1)), 70)
        val cam = Camera(9, lat(b, .5), lon(b, .5), CamKind.FIXED, 70, null)
        val both = RadarData.split(listOf(a, b), RadarData.Level.FULL, 1L, listOf(inA, across), listOf(cam)).associateBy { it.key }
        val onlyA = RadarData.split(listOf(a), RadarData.Level.FULL, 1L, listOf(inA, across), emptyList()).single()
        assertEquals(onlyA.roads.map { it.id }.toSet(), both.getValue(a).roads.map { it.id }.toSet())
        assertEquals(setOf(2L), both.getValue(b).roads.map { it.id }.toSet())
        assertEquals(listOf(9L), both.getValue(b).cameras.map { it.id })
        assertTrue(both.getValue(a).cameras.isEmpty())
    }

    @Test fun preparationBlocksAreBigger() {
        assertEquals(4, RadarRepo.PREP_TILES)
        // Quarters when a block fails or is too big: 4 -> 2 -> 1
        assertEquals(4, RadarRepo.subBlocks(RadarData.Key(0, 0), 4).size)
        assertTrue(RadarRepo.subBlocks(RadarData.Key(0, 0), 4).all { it.second == 2 })
        assertEquals(16, RadarRepo.blockTiles(RadarData.Key(0, 0), 4).size)
    }

    /** dresel's coverage (Geofabrik's Europe and North America), not a plain rectangle. */
    @Test fun regionsFollowTheExtracts() {
        val o = app.mg4dash.radar.Overpass
        val EU = app.mg4dash.radar.Overpass.Region.EUROPE; val NA = app.mg4dash.radar.Overpass.Region.NORTH_AMERICA; val X = app.mg4dash.radar.Overpass.Region.OTHER
        // Europe, its islands and Turkey
        listOf(48.86 to 2.35, 38.72 to -9.14, 36.72 to -4.42, 37.39 to -5.98, 38.12 to 13.36, 39.22 to 9.11, 35.34 to 25.13, 35.9 to 14.51,
            64.15 to -21.94, 60.17 to 24.94, 53.9 to 27.56, 50.0 to 36.23, 41.01 to 28.98, 39.93 to 32.86, 35.17 to 33.36).forEach { (la, lo) ->
            assertEquals("$la,$lo", EU, o.region(la, lo)) }
        // Not in it: North Africa, the Middle East, the Caucasus, Russia beyond Belarus
        listOf(36.81 to 10.18, 36.75 to 3.06, 34.02 to -6.84, 32.89 to 13.19, 33.89 to 35.5, 33.51 to 36.29, 31.77 to 35.21,
            40.18 to 44.51, 40.41 to 49.87, 55.75 to 37.62).forEach { (la, lo) -> assertTrue("$la,$lo", o.region(la, lo) != EU) }
        // North America: US, Canada, Mexico, Alaska, Hawaii, Greenland; not Central America nor the Caribbean
        listOf(40.71 to -74.0, 34.05 to -118.24, 45.5 to -73.57, 19.43 to -99.13, 61.22 to -149.9, 21.31 to -157.86, 64.18 to -51.72)
            .forEach { (la, lo) -> assertEquals("$la,$lo", NA, o.region(la, lo)) }
        listOf(14.63 to -90.51, 23.11 to -82.37, 18.47 to -69.9, -33.87 to 151.21, 35.68 to 139.69).forEach { (la, lo) -> assertEquals("$la,$lo", X, o.region(la, lo)) }
    }

    /** An empty answer from dresel is checked elsewhere, and an empty tile is asked again after an hour. */
    @Test fun emptyRegionalAnswerNotTrusted() {
        val o = app.mg4dash.radar.Overpass
        assertTrue(o.regional(o.DRESEL_EU) && o.regional(o.DRESEL_NA) && o.OFFICIAL.none { o.regional(it) })
        assertTrue(o.noRoads("""{"version":0.6,"elements":[]}"""))
        assertTrue(o.noRoads("""{"elements":[{"type": "node","id":1}]}"""))
        assertFalse(o.noRoads("""{"elements":[{"type": "way","id":1}]}"""))
        assertFalse(o.noRoads("""{"elements":[{"type":"way","id":1}]}"""))
        val now = 10L * 3_600_000L
        val empty = RadarData.Tile(RadarData.Key(1, 1), RadarData.Level.FULL, now - 30 * 60_000L, emptyList(), emptyList())
        assertFalse(RadarRepo.needsFetch(empty, now, 6 * 3_600_000L))
        assertTrue(RadarRepo.needsFetch(empty, now + 31 * 60_000L, 6 * 3_600_000L))
    }

    /** No answer from every server at once is the car's network (a tunnel): no server blamed. */
    @Test fun ourNetworkIsNotTheServersFault() {
        val o = app.mg4dash.radar.Overpass
        assertTrue(o.silentToCount(4, listOf("a", "b", "c", "d")).isEmpty())
        assertEquals(listOf("a"), o.silentToCount(4, listOf("a")))
        assertTrue(o.silentToCount(0, emptyList()).isEmpty())
    }

    /** A silent server is left out longer each time (1, 2, 5, 10, 30 min), forgiven on an answer or a network change. */
    @Test fun growingPenalty() {
        val o = app.mg4dash.radar.Overpass
        val EU = app.mg4dash.radar.Overpass.Region.EUROPE
        o.resetSkips()
        var t = 1_000_000L
        for (step in o.SKIP_STEPS_MS) {
            o.noAnswer(o.DRESEL_EU, t); o.noAnswer(o.DRESEL_EU, t)
            assertEquals(o.OFFICIAL, o.order(EU, t + step - 1))
            assertEquals(o.DRESEL_EU, o.order(EU, t + step).first())
            t += step
        }
        // At most 30 min
        o.noAnswer(o.DRESEL_EU, t); o.noAnswer(o.DRESEL_EU, t)
        assertEquals(o.DRESEL_EU, o.order(EU, t + 30 * 60_000L).first())
        // An answer starts it again at 1 min
        o.answered(o.DRESEL_EU)
        o.noAnswer(o.DRESEL_EU, t); o.noAnswer(o.DRESEL_EU, t)
        assertEquals(o.DRESEL_EU, o.order(EU, t + 60_000L).first())
        // A network change forgives at once, a 429 pause stays
        o.noAnswer(o.DRESEL_EU, t); o.noAnswer(o.DRESEL_EU, t)
        o.pause(o.OFFICIAL[0])
        o.networkChanged()
        assertEquals(o.DRESEL_EU, o.order(EU).first())
        assertTrue(o.OFFICIAL[0] !in o.order(EU))
        o.resetSkips()
    }

    /** A head unit's network often never gets Android's check: with internet it still downloads, without blaming servers. */
    @Test fun downloadsWithoutAndroidsCheck() {
        assertTrue(RadarRepo.canTry(internet = true))
        assertFalse(RadarRepo.canTry(internet = false))
        assertTrue(RadarRepo.blameAllowed(validated = true))
        assertFalse(RadarRepo.blameAllowed(validated = false))
    }
}
