package app.mg4dash.showroom

import android.content.Context

/**
 * The driver's car as the pages draw it (26/11, pages redesign): version, paint, roof and wheels, chosen in "Colours
 * and style" (long press on the car).
 */
class CarLook(context: Context) {
    /** [body]: renders without (0) or with (1) the double spoiler; [trim]: matt black sills (all but the XPower). */
    enum class Version(val label: String, val body: Int, val wheel: PaintShop.Wheel, val roof: Boolean, val trim: Boolean) {
        STANDARD("Standard", 0, PaintShop.Wheel.AERO17, false, true),
        COMFORT("Comfort", 0, PaintShop.Wheel.AERO17, false, true),
        LUXURY("Luxury", 1, PaintShop.Wheel.LUX18, false, true),
        XPOWER("XPower", 1, PaintShop.Wheel.XP18, true, false),
    }

    private val sp = context.getSharedPreferences("car_look", Context.MODE_PRIVATE)

    var version: Version
        get() = Version.values().firstOrNull { it.name == sp.getString("version", null) } ?: Version.STANDARD
        set(v) = sp.edit().putString("version", v.name).apply()

    var paint: CarPaint
        get() = CarPaint.byId(sp.getString("paint", null))
        set(v) = sp.edit().putString("paint", v.id).apply()

    var roof: Boolean
        get() = sp.getBoolean("roof", version.roof)
        set(v) = sp.edit().putBoolean("roof", v).apply()

    var wheel: PaintShop.Wheel
        get() = PaintShop.Wheel.values().firstOrNull { it.id == sp.getString("wheel", null) } ?: version.wheel
        set(v) = sp.edit().putString("wheel", v.id).apply()

    /** Profile (false) or three-quarter (true) on the Vehicle page. */
    var threeQuarter: Boolean
        get() = sp.getBoolean("three_quarter", true)
        set(v) = sp.edit().putBoolean("three_quarter", v).apply()

    /** A version chosen: its wheels and roof come with it. */
    fun choose(v: Version) {
        sp.edit().putString("version", v.name).putString("wheel", v.wheel.id).putBoolean("roof", v.roof).apply()
    }

    /** Back to the default car. */
    fun stock() { sp.edit().clear().apply() }

    fun config(view: PaintShop.View) = PaintShop.Config(view, version.body, paint, roof, wheel, version.trim)

    /** "Brighton Blue", "Hunter Green satin · black roof". */
    fun name(satin: String, blackRoof: String): String =
        listOfNotNull(paint.name + if (paint.finish == PaintShop.Finish.SATIN) " $satin" else "", if (roof) blackRoof else null).joinToString(" · ")
}
