package app.mg4dash

import app.mg4dash.radar.Ahead
import app.mg4dash.radar.CamKind
import app.mg4dash.radar.Camera
import app.mg4dash.radar.Country
import app.mg4dash.radar.DefaultLimits
import app.mg4dash.radar.Geo
import app.mg4dash.radar.IsaWrite
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

    @Test fun alertKindsAndDefaults() {
        assertEquals(RadarAlert.Kind.ZONE, RadarAlert.kindOf(Level.STRONG, critical = true))
        assertEquals(RadarAlert.Kind.STRONG, RadarAlert.kindOf(Level.STRONG, critical = false))
        assertEquals(RadarAlert.Kind.LIGHT, RadarAlert.kindOf(Level.LIGHT, critical = false))
        assertNull(RadarAlert.kindOf(Level.NONE, critical = false))
        // Defaults (Marc's mockup, 17/11): approach chime 60 % once, light silent, strong beep 70 % every 10 s, zone loud 100 % every 5 s
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

    // ── Network ──

    @Test fun officialServerFirstNoOther() {
        // The official server first, always (TLS 1.3 through Conscrypt); one public fallback, outside Russia
        assertEquals(listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"),
            app.mg4dash.radar.Overpass.order())
        assertTrue(app.mg4dash.radar.Overpass.SERVERS.none { it.contains("mail.ru") })
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
        assertEquals(listOf(10, 20, 30, 50), r.PREPARE_RADII)
        assertEquals(20, app.mg4dash.system.Prefs(org.robolectric.RuntimeEnvironment.getApplication()).radarPrepareKm)
        // 30 km around Paris: 60 km square, one tile (0.02°) per request, about 28 x 42
        val boxes = r.prepareBoxes(48.86, 2.35, 30)
        assertTrue(boxes.size in 1100..1400)
        assertEquals(boxes.size, boxes.toSet().size)
        // Every point within 30 km is in one of the tiles fetched
        val tiles = boxes.flatMap { r.boxTiles(it) }
        listOf(48.86 + 0.26 to 2.35, 48.86 to 2.35 - 0.39, 48.86 - 0.26 to 2.35 + 0.39).forEach { (la, lo) ->
            assertTrue(RadarData.Key.of(la, lo) in tiles)
        }
        val sizes = r.PREPARE_RADII.map { r.prepareEstimate(it, 48.86) }
        assertEquals(sizes.sortedBy { it.boxes }, sizes)
        val e = sizes[2]
        assertEquals(e.boxes * r.PREP_BOX_BYTES_GZIP, e.download)
        assertEquals(e.boxes * r.PREP_BOX_BYTES_KEPT, e.kept)
        assertTrue(e.minutes in 60..110)
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

    /** 50 km at most (Marc, 17/11): a radius stored before (80) comes back as 50; the compressing server asked first. */
    @Test fun preparationRadiusClamped() {
        val r = app.mg4dash.radar.RadarRepo
        assertEquals(50, r.prepareRadius(80))
        assertEquals(30, r.prepareRadius(30))
        assertEquals(10, r.prepareRadius(5))
        val prefs = app.mg4dash.system.Prefs(org.robolectric.RuntimeEnvironment.getApplication())
        prefs.raw.edit().putInt("radar_prepare_km", 80).commit()
        assertEquals(50, prefs.radarPrepareKm)
    }

    // ── Radar5: HTTP answers, live refresh, ISA ──

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
        fun tile(ageH: Long, level: RadarData.Level = RadarData.Level.FULL) = RadarData.Tile(key, level, now - ageH * h, emptyList(), emptyList())
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

    @Test fun isaWriteDecision() {
        val w = app.mg4dash.radar.IsaWrite
        // A tagged limit, nothing sent yet: sent; the same again: nothing; too soon after the last write: nothing
        assertEquals(IsaWrite.Decision.Send(50f), w.decide(50, false, null, null, 0, 10_000, false))
        assertEquals(IsaWrite.Decision.Nothing, w.decide(50, false, null, 50, 0, 10_000, false))
        assertEquals(IsaWrite.Decision.Nothing, w.decide(70, false, null, 50, 8_000, 10_000, false))
        assertEquals(IsaWrite.Decision.Send(70f), w.decide(70, false, null, 50, 0, 10_000, false))
        // The car already says the same: nothing
        assertEquals(IsaWrite.Decision.Nothing, w.decide(70, false, 70, 50, 0, 10_000, false))
        // Estimated or unknown: cleared once, then nothing
        assertEquals(IsaWrite.Decision.Clear, w.decide(50, true, null, 70, 0, 10_000, false))
        assertEquals(IsaWrite.Decision.Clear, w.decide(null, false, null, 70, 0, 10_000, false))
        assertEquals(IsaWrite.Decision.Nothing, w.decide(null, false, null, 0, 0, 10_000, false))
        // mph: 113 km/h sent as 70
        assertEquals(IsaWrite.Decision.Send(70f), w.decide(113, false, null, null, 0, 10_000, true))
        // The writes mirror the stock map: value to both, status to both, then the unit
        val ws = w.writes(IsaWrite.Decision.Send(50f), hasIsa = false, miles = false)
        assertEquals(listOf(0x2160a185, 0x2160a1a8, 0x2140a183, 0x2140a1a9, 0x2140a184), ws.map { it.id })
        assertEquals(50f, ws[0].f); assertEquals(50f, ws[1].f)
        assertEquals(2, ws[4].i)                                                // km/h without the car's ISA: 2, as stock
        assertEquals(1, w.writes(IsaWrite.Decision.Send(50f), hasIsa = true, miles = false)[4].i)
        assertEquals(0f, w.writes(IsaWrite.Decision.Clear, false, false)[0].f)
        assertTrue(w.writes(IsaWrite.Decision.Nothing, false, false).isEmpty())
        // Held back: nothing reaches the car
        assertFalse(w.WRITE_ALLOWED)
        var written = 0
        w.apply(50, false, null, 1_000_000, false, false) { written++; true }
        assertEquals(0, written)
    }

    @Test fun isaLogChangesAndOsmSteps() {
        val c = org.robolectric.RuntimeEnvironment.getApplication()
        val log = app.mg4dash.radar.IsaLog
        log.reset(); log.file(c).delete()
        val values = HashMap<Int, String>()
        var all = mapOf(1 to "0", 2 to "5")
        log.sample(c, 1_000, 50, 48f, read = { values[it] ?: "" }, readAll = { all })
        values[0x2160a212] = "50.0"
        all = mapOf(1 to "0", 2 to "7")
        log.sample(c, 2_000, 70, 66f, read = { values[it] ?: "" }, readAll = { all })
        val text = log.file(c).readText()
        assertTrue(text.startsWith("time,what,name,id,before,after,osm_kmh,gps_kmh"))
        assertTrue(text.contains("prop,SPEED_AST_SYS_TRAGET_VCU,0x2160a212,,50.0,70,66"))
        // Property 2 changed in step with OSM's limit (50 → 70): logged as a candidate
        assertTrue(text.contains("with-osm,?,0x2,5,7,70,66"))
        assertTrue(text.contains("osm,OSM,0x0,50,70,70,66"))
        assertNull(app.mg4dash.radar.Isa.CAR_LIMIT_PROP)
    }
}
