package app.mg4dash.showroom

/**
 * A paint of the car. The official MG4 colours (France, 2022–2024) first; [linear] is the colour in linear light.
 * The fun paints and the liveries come later (redesign step 5).
 */
class CarPaint(val id: String, val name: String, val hex: Int, val finish: PaintShop.Finish, val xpowerOnly: Boolean = false) {
    val linear = floatArrayOf(PaintShop.LUT[(hex shr 16) and 0xFF], PaintShop.LUT[(hex shr 8) and 0xFF], PaintShop.LUT[hex and 0xFF])

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
        val ALL get() = OFFICIAL

        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: OFFICIAL[4]
    }
}
