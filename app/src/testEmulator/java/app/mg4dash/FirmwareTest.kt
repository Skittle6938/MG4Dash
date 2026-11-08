package app.mg4dash

import app.mg4dash.system.Firmware
import app.mg4dash.system.Firmware.Family
import app.mg4dash.system.Firmware.Gen
import org.junit.Assert.assertEquals
import org.junit.Test

/** Firmware generation and car access family: a wrong answer sends MG4Dash down the wrong car route. */
class FirmwareTest {
    @Test fun eachKnownPrefix() {
        assertEquals(Gen.SWI69, Firmware.parse("SWI69-R45-12345"))
        assertEquals(Gen.SWI68, Firmware.parse("SWI68_R73"))
        assertEquals(Gen.SWI131, Firmware.parse("SWI131_R27"))
        assertEquals(Gen.SWI132, Firmware.parse("SWI132_R43"))
        assertEquals(Gen.SWI133, Firmware.parse("SWI133_R42"))
        assertEquals(Gen.SWI165, Firmware.parse("SWI165-R10"))
        assertEquals(Gen.SWI173, Firmware.parse("SWI173-R02"))
    }

    @Test fun unknownOrMissing() {
        assertEquals(Gen.UNKNOWN, Firmware.parse(null))
        assertEquals(Gen.UNKNOWN, Firmware.parse(""))
        assertEquals(Gen.UNKNOWN, Firmware.parse("eng.build.20240101"))
        assertEquals(Gen.UNKNOWN, Firmware.parse("swi69-lowercase"))
    }

    @Test fun a9Family() {
        for (g in listOf(Gen.SWI69, Gen.SWI131, Gen.SWI132, Gen.SWI173)) assertEquals("$g", Family.A9, Firmware.familyOf(g))
    }

    @Test fun legacyFamily() {
        for (g in listOf(Gen.SWI68, Gen.SWI165, Gen.SWI133)) assertEquals("$g", Family.LEGACY, Firmware.familyOf(g))
    }

    @Test fun everyGenerationHasAFamily() {
        for (g in Gen.values()) Firmware.familyOf(g)
        assertEquals(Family.UNKNOWN, Firmware.familyOf(Gen.UNKNOWN))
    }
}
