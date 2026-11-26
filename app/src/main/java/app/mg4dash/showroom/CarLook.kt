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
        set(v) { sp.edit().putString("paint", v.id).apply { if (!v.fun_) putString("last_official", v.id) }.apply() }

    var livery: Livery
        get() = Livery.byId(sp.getString("livery", null))
        set(v) = sp.edit().putString("livery", v.id).apply()

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

    /** Climate page: right-hand drive chosen by a long press (null: the car's own side). */
    var rhd: Boolean?
        get() = if (sp.contains("rhd")) sp.getBoolean("rhd", false) else null
        set(v) { if (v == null) sp.edit().remove("rhd").apply() else sp.edit().putBoolean("rhd", v).apply() }

    /** A version chosen: its wheels and roof come with it. */
    fun choose(v: Version) {
        sp.edit().putString("version", v.name).putString("wheel", v.wheel.id).putBoolean("roof", v.roof).apply()
    }

    /** Back to the default car. */
    fun stock() { sp.edit().remove("version").remove("paint").remove("roof").remove("wheel").remove("livery").remove("last_official").apply() }

    /** "Original": no livery and no fun colour, back to the last MG colour chosen (the app cannot know the car's real one). */
    fun original() {
        val last = CarPaint.OFFICIAL.firstOrNull { it.id == sp.getString("last_official", null) } ?: CarPaint.OFFICIAL[4]
        sp.edit().remove("livery").putString("paint", if (paint.fun_) last.id else paint.id).apply()
    }

    /** "Random": half the time a fun colour, half the time an MG colour with a livery. */
    fun random(rnd: kotlin.random.Random = kotlin.random.Random) {
        val l = Livery.ALL
        if (rnd.nextBoolean()) { paint = CarPaint.FUN.random(rnd); livery = if (rnd.nextBoolean()) l[0] else l[1 + rnd.nextInt(l.size - 1)] }
        else { paint = CarPaint.OFFICIAL.random(rnd); livery = l[1 + rnd.nextInt(l.size - 1)] }
    }

    fun config(view: PaintShop.View) = PaintShop.Config(view, version.body, paint, roof, wheel, version.trim, livery)

    /** "Brighton Blue", "Hunter Green satin · black roof". */
    fun name(c: Context): String = listOfNotNull(
        paint.label(c) + if (paint.finish == PaintShop.Finish.SATIN) " " + c.getString(app.mg4dash.R.string.sr_satin) else "",
        if (roof) c.getString(app.mg4dash.R.string.sr_black_roof) else null,
        if (livery.fx != null) c.getString(app.mg4dash.R.string.sr_livery_word, c.getString(livery.nameRes)) else null,
    ).joinToString(" · ")
}
