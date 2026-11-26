package app.mg4dash.showroom

import app.mg4dash.R

/**
 * A paint of the car. The official MG4 colours (France, 2022–2024) first, named in English as MG does; then the fun
 * ones (step 5, 26/11), named with [nameRes]. [linear] is the base colour in linear light; [fx] computes the colour of
 * each point for the patterned ones; [swatch] gives the sample's colours (a sweep).
 */
class CarPaint(
    val id: String, val name: String, val hex: Int, val finish: PaintShop.Finish, val xpowerOnly: Boolean = false,
    val fun_: Boolean = false, val nameRes: Int = 0, linear: FloatArray? = null, val fx: ((Shade.S) -> Unit)? = null,
    val swatch: IntArray? = null,
) {
    val linear = linear ?: floatArrayOf(PaintShop.LUT[(hex shr 16) and 0xFF], PaintShop.LUT[(hex shr 8) and 0xFF], PaintShop.LUT[hex and 0xFF])

    fun label(c: android.content.Context) = if (nameRes != 0) c.getString(nameRes) else name

    companion object {
        val OFFICIAL = listOf(
            CarPaint("white", "Dover White", 0xE9EBEA, PaintShop.Finish.SOLID),
            CarPaint("black", "Pebble Black", 0x101114, PaintShop.Finish.METAL),
            CarPaint("silver", "Medal Silver", 0xB9BDC2, PaintShop.Finish.METAL),
            CarPaint("grey", "Andes Grey", 0x5A6069, PaintShop.Finish.METAL),
            CarPaint("blue", "Brighton Blue", 0x008CC8, PaintShop.Finish.SOLID),
            CarPaint("red", "Diamond Red", 0xB40C12, PaintShop.Finish.METAL),
            CarPaint("orange", "Fizzy Orange", 0xF0641A, PaintShop.Finish.METAL),
            CarPaint("green", "Hunter Green", 0x7C9277, PaintShop.Finish.SATIN, xpowerOnly = true),
        )
        val FUN = listOf(
            CarPaint("gold", "", 0xE2A520, PaintShop.Finish.CHROME, fun_ = true, nameRes = R.string.sr_p_gold, linear = floatArrayOf(1f, .70f, .26f), swatch = intArrayOf(0xFFF1B0, 0xE2A520, 0x8A5A0A, 0xF6CF5A)),
            CarPaint("chrome", "", 0xB0B8C2, PaintShop.Finish.CHROME, fun_ = true, nameRes = R.string.sr_p_chrome, linear = floatArrayOf(.93f, .94f, .96f), swatch = intArrayOf(0xFFFFFF, 0x9AA3AE, 0x3B424C, 0xE4E8EE)),
            CarPaint("rainbow", "", 0xFF2A2A, PaintShop.Finish.METAL, fun_ = true, nameRes = R.string.sr_p_rainbow, linear = floatArrayOf(.55f, .5f, .5f), fx = Shade.rainbow, swatch = intArrayOf(0xFF2A2A, 0xFFB300, 0xF4F000, 0x22D04A, 0x12B8F0, 0x3A4BFF, 0xA02BFF)),
            CarPaint("cameleon", "", 0x1C6BFF, PaintShop.Finish.METAL, fun_ = true, nameRes = R.string.sr_p_chameleon, linear = floatArrayOf(.1f, .4f, .7f), fx = Shade.chameleon, swatch = intArrayOf(0x0FD3B0, 0x1C6BFF, 0x7A2BFF, 0xFF2D95, 0xFFB02E, 0x0FD3B0)),
            CarPaint("galaxy", "", 0x3B1470, PaintShop.Finish.SOLID, fun_ = true, nameRes = R.string.sr_p_galaxy, linear = floatArrayOf(.03f, .02f, .12f), fx = Shade.galaxy, swatch = intArrayOf(0xB0257A, 0x3B1470, 0x03061C)),
            CarPaint("sunset", "", 0xE8304A, PaintShop.Finish.METAL, fun_ = true, nameRes = R.string.sr_p_sunset, linear = floatArrayOf(.7f, .15f, .1f), fx = Shade.sunset, swatch = intArrayOf(0x2A0845, 0x8E1558, 0xE8304A, 0xFF8A1F, 0xFFD84D)),
            CarPaint("candy", "", 0xC77DFF, PaintShop.Finish.METAL, fun_ = true, nameRes = R.string.sr_p_candy, linear = floatArrayOf(.6f, .35f, .75f), fx = Shade.candy, swatch = intArrayOf(0xFF4FA3, 0xC77DFF, 0x4CC9F0, 0x3DF5C8)),
            CarPaint("carbon", "", 0x16181B, PaintShop.Finish.SOLID, fun_ = true, nameRes = R.string.sr_p_carbon, linear = floatArrayOf(.03f, .03f, .032f), fx = Shade.carbon, swatch = intArrayOf(0x16181B, 0x3A3D42, 0x0C0D0F, 0x2A2C30, 0x111214, 0x34373B)),
        )
        val ALL get() = OFFICIAL + FUN

        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: OFFICIAL[4]
    }
}

/** A livery, painted over the chosen paint (step 5, 26/11). */
class Livery(val id: String, val nameRes: Int, val fx: ((Shade.S) -> Unit)?, val decals: List<String> = emptyList(), val base: String = "blue") {
    companion object {
        val ALL = listOf(
            Livery("none", R.string.sr_l_none, null),
            Livery("rally", R.string.sr_l_rally, Shade.rally, listOf("num"), "white"),
            Livery("flames", R.string.sr_l_flames, Shade.flames, base = "black"),
            Livery("bolt", R.string.sr_l_bolt, Shade.bolt, listOf("bolt"), "grey"),
            Livery("check", R.string.sr_l_check, Shade.check, base = "white"),
            Livery("pixels", R.string.sr_l_pixels, Shade.pixels, base = "silver"),
            Livery("tricolore", R.string.sr_l_tricolore, Shade.tricolore, base = "white"),
            Livery("camo", R.string.sr_l_camo, Shade.camo, base = "green"),
            Livery("leopard", R.string.sr_l_leopard, Shade.leopard, base = "orange"),
            Livery("zebra", R.string.sr_l_zebra, Shade.zebra, base = "white"),
            Livery("ladybug", R.string.sr_l_ladybug, Shade.ladybug, base = "red"),
            Livery("cow", R.string.sr_l_cow, Shade.cow, base = "white"),
        )
        val NONE get() = ALL[0]
        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: NONE
    }
}
