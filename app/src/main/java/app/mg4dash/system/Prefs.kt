package app.mg4dash.system

import android.content.Context
import android.content.SharedPreferences

/** Réglages persistants du launcher. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)

    /** Capacité utile de la batterie, pour l'estimation de consommation (MG4 Standard 51 kWh : ~49 utiles). */
    var batteryKwh: Float
        get() = sp.getFloat("battery_kwh", 49f)
        set(v) = sp.edit().putFloat("battery_kwh", v).apply()

    /** Batterie d'origine du modèle, en kWh bruts : 51, 64 ou 77 (référence de la santé batterie). */
    var batteryModel: Int
        get() = sp.getInt("battery_model", 51)
        set(v) = sp.edit().putInt("battery_model", v).apply()

    /** Style Carte : barre verticale à gauche au lieu du dock du bas (issue #6). */
    var verticalDock: Boolean
        get() = sp.getBoolean("vertical_dock", false)
        set(v) = sp.edit().putBoolean("vertical_dock", v).apply()

    /** Barre verticale deux fois plus large, icônes plus grandes (#67). */
    var wideBar: Boolean
        get() = sp.getBoolean("wide_bar", false)
        set(v) = sp.edit().putBoolean("wide_bar", v).apply()

    /** Style Carte : sans les titres des widgets (issue #6). */
    /** Période du widget consommation : 0 = depuis la recharge, 1 = trajet en cours, 2 = 1 000 derniers km. */
    var consumptionPeriod: Int
        get() = sp.getInt("consumption_period", 0)
        set(v) = sp.edit().putInt("consumption_period", v).apply()

    /** Pression des pneus : 0 = auto (psi en miles, sinon bar), 1 = bar, 2 = psi, 3 = kPa. */
    var pressureUnit: Int
        get() = sp.getInt("pressure_unit", 0)
        set(v) = sp.edit().putInt("pressure_unit", v).apply()

    /** A/C intermittent (#34) : durées de marche et d'arrêt du compresseur, en secondes. */
    var acCycleOnSec: Int
        get() = sp.getInt("ac_cycle_on", 300)
        set(v) = sp.edit().putInt("ac_cycle_on", v.coerceIn(30, 1800)).apply()
    var acCycleOffSec: Int
        get() = sp.getInt("ac_cycle_off", 300)
        set(v) = sp.edit().putInt("ac_cycle_off", v.coerceIn(30, 1800)).apply()
    /** A/C intermittent activé par le conducteur : il reste actif d'un trajet à l'autre jusqu'à ce qu'il l'arrête (#61). */
    var acCycleOn: Boolean
        get() = sp.getBoolean("ac_cycle_active", false)
        set(v) = sp.edit().putBoolean("ac_cycle_active", v).apply()

    /** Calibration de la consigne affichée, en degrés (−5 à +5) : affichage seulement, la voiture reçoit sa valeur. */
    var tempOffset: Int
        get() = sp.getInt("temp_offset", 0).coerceIn(-5, 5)
        set(v) = sp.edit().putInt("temp_offset", v.coerceIn(-5, 5)).apply()

    /** Carte : zoom par défaut (14 à 18) et thème (0 = comme l'appli, 1 = clair, 2 = sombre). */
    var mapZoom: Int
        get() = sp.getInt("map_zoom", 16)
        set(v) = sp.edit().putInt("map_zoom", v).apply()
    var mapTheme: Int
        get() = sp.getInt("map_theme", 0)
        set(v) = sp.edit().putInt("map_theme", v).apply()
    /** Sans connexion (et sans tuiles en cache), la carte cède la place au widget énergie (#15). */
    /** Boutons zoom +, zoom − et plein écran masqués, affichés quelques secondes au toucher de la carte (#15). */
    var mapAutoHide: Boolean
        get() = sp.getBoolean("map_auto_hide", false)
        set(v) = sp.edit().putBoolean("map_auto_hide", v).apply()
    /** Page de raccourcis dans la rotation du widget carte (#38) ; décochée : carte ↔ consommation. */
    var shortcutsPage: Boolean
        get() = sp.getBoolean("shortcuts_page", true)
        set(v) = sp.edit().putBoolean("shortcuts_page", v).apply()
    /** Appli affichée dans le widget carte à la place de la carte (#41, essai) ; null = carte OSM. */
    var mapApp: String?
        get() = sp.getString("map_app", null)
        set(v) = sp.edit().putString("map_app", v).apply()
    var mapOfflineSwitch: Boolean
        get() = sp.getBoolean("map_offline_switch", true)
        set(v) = sp.edit().putBoolean("map_offline_switch", v).apply()

    /** Sièges / volant chauffants : 0 = auto (batterie 64 / 77 kWh), 1 = afficher, 2 = masquer. */
    var heatedSeats: Int
        get() = sp.getInt("heated_seats", 0)
        set(v) = sp.edit().putInt("heated_seats", v).apply()

    /** Unités : 0 = comme la voiture, 1 = km, 2 = miles. */
    var units: Int
        get() = sp.getInt("units", 0)
        set(v) = sp.edit().putInt("units", v).apply()

    /** Titres des widgets : toujours masqués (moins chargé, l'option a été retirée). */
    val hideTitles: Boolean get() = true

    /** Météo du style Carte : 0 = prévisions par heure, 1 = par jour. */
    var weatherPage: Int
        get() = sp.getInt("weather_page", 0)
        set(v) = sp.edit().putInt("weather_page", v).apply()

    /** Capacité ajustée à la main (sinon elle suit la voiture ou l'estimation mesurée). */
    var batteryKwhManual: Boolean
        get() = sp.getBoolean("battery_kwh_manual", sp.contains("battery_kwh"))   // réglée avant cette option : on la garde
        set(v) = sp.edit().putBoolean("battery_kwh_manual", v).apply()

    /** Position météo de repli quand le véhicule ne fournit pas de localisation. */
    var weatherLat: Float
        get() = sp.getFloat("weather_lat", 48.8566f)
        set(v) = sp.edit().putFloat("weather_lat", v).apply()
    var weatherLon: Float
        get() = sp.getFloat("weather_lon", 2.3522f)
        set(v) = sp.edit().putFloat("weather_lon", v).apply()
    /** Modèle météo Open-Meteo (#67) : vide = choix automatique d'Open-Meteo (« best match »). */
    var weatherModel: String
        get() = sp.getString("weather_model", "") ?: ""
        set(v) = sp.edit().putString("weather_model", v).apply()

    var weatherCity: String
        get() = sp.getString("weather_city", "Paris") ?: "Paris"
        set(v) = sp.edit().putString("weather_city", v).apply()

    /** Clim maintenue (appui long sur le bouton marche de la clim). Désactivé par défaut. */
    var climateHoldEnabled: Boolean
        get() = sp.getBoolean("climhold_enabled", false)
        set(v) = sp.edit().putBoolean("climhold_enabled", v).apply()
    /** Durée maximale de la clim maintenue en minutes ; 0 = illimitée (seul le seuil de batterie l'arrête). */
    /** Délai avant le verrouillage des portes en clim maintenue, en secondes (le temps de sortir avec la clé). */
    var climateHoldLockSec: Int
        get() = sp.getInt("climhold_lock_sec", 20)
        set(v) = sp.edit().putInt("climhold_lock_sec", v).apply()
    /** Clim maintenue à une heure précise (sinon tout de suite), heure du premier cycle (minutes depuis minuit), cycles. */
    var climateHoldScheduled: Boolean
        get() = sp.getBoolean("climhold_scheduled", false)
        set(v) = sp.edit().putBoolean("climhold_scheduled", v).apply()
    var climateHoldStartMin: Int
        get() = sp.getInt("climhold_start_min", 18 * 60)
        set(v) = sp.edit().putInt("climhold_start_min", v).apply()
    /** Clim maintenue : écran éteint une fois les portes verrouillées (dormir dans la voiture, discrétion). */
    var climateHoldScreenOff: Boolean
        get() = sp.getBoolean("climhold_screen_off", false)
        set(v) = sp.edit().putBoolean("climhold_screen_off", v).apply()
    var climateHoldRuns: Int
        get() = sp.getInt("climhold_runs", 1)
        set(v) = sp.edit().putInt("climhold_runs", v).apply()
    var climateHoldRunMin: Int
        get() = sp.getInt("climhold_run_min", 15)
        set(v) = sp.edit().putInt("climhold_run_min", v).apply()
    var climateHoldGapMin: Int
        get() = sp.getInt("climhold_gap_min", 30)
        set(v) = sp.edit().putInt("climhold_gap_min", v).apply()
    var climateHoldMaxMin: Int
        get() = sp.getInt("climhold_max_min", 0)
        set(v) = sp.edit().putInt("climhold_max_min", v).apply()

    /** MG4 XPower (320 kW) : reconnue d'elle-même au-delà de 350 ch mesurés, ou choisie dans les réglages. */
    var xpower: Boolean
        get() = sp.getBoolean("xpower", false)
        set(v) = sp.edit().putBoolean("xpower", v).apply()

    /** Animation pleine puissance du widget Puissance : 0 = aucune, 1 = fusée, 2 = éclair, 3 = flammes, 4 = au hasard. */
    var powerFx: Int
        get() = sp.getInt("power_fx", 4)
        set(v) = sp.edit().putInt("power_fx", v).apply()

    /**
     * Carte énergie « puissance » (#29) : barre de puissance en direct dans la carte énergie, sans
     * mode de conduite ni KERS ; la page Puissance quitte alors le widget défilant.
     */
    /** Source audio au démarrage : 0 automatique (défaut), 1 comme la voiture, 2 radio, 3 Bluetooth, 4 USB, 5 CarPlay / Android Auto. */
    var startSource: Int
        get() = sp.getInt("start_source", 0)
        set(v) = sp.edit().putInt("start_source", v).apply()

    /** Source Bluetooth proposée aussi pendant Android Auto, CarPlay ou DiAuto. */
    var btDuringProjection: Boolean
        get() = sp.getBoolean("bt_during_projection", true)
        set(v) = sp.edit().putBoolean("bt_during_projection", v).apply()

    /** Téléphone branché en USB avant le démarrage : relancé pour qu'Android Auto démarre (repris d'AA Kick). */
    var usbKick: Boolean
        get() = sp.getBoolean("usb_kick", true)
        set(v) = sp.edit().putBoolean("usb_kick", v).apply()

    /** Appli de connexion du téléphone désactivée par MG4Dash (appui long sur le bouton Android Auto). */
    var connectionOff: String?
        get() = sp.getString("connection_off", null)
        set(v) = sp.edit().putString("connection_off", v).apply()

    /** Lecture au démarrage de la voiture : 0 comme à l'arrêt (défaut), 1 toujours en pause, 2 toujours en lecture. */
    var startPlayback: Int
        get() = sp.getInt("start_playback", 0)
        set(v) = sp.edit().putInt("start_playback", v).apply()

    /** La musique jouait-elle à l'arrêt de la voiture (pour « comme à l'arrêt ») ? */
    var lastPlaying: Boolean
        get() = sp.getBoolean("last_playing", true)
        set(v) = sp.edit().putBoolean("last_playing", v).apply()

    /** Sources choisies par l'utilisateur, la plus récente d'abord (mode automatique). */
    var sourceHistory: List<String>
        get() = sp.getString("source_history", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("source_history", v.joinToString(",")).apply()

    /** Carte puissance : tension et courant (actuels, mini / maxi) sous la barre ; désactivé par défaut. */
    var powerVa: Boolean
        get() = sp.getBoolean("power_va", false)
        set(v) = sp.edit().putBoolean("power_va", v).apply()

    var energyCardPower: Boolean
        get() = sp.getBoolean("energy_card_power", false)
        set(v) = sp.edit().putBoolean("energy_card_power", v).apply()

    /** Widget secondaire de la colonne droite : 0 = trajet, 1 = statut, 2 = météo compacte. */
    var secondaryWidget: Int
        get() = sp.getInt("secondary_widget", 0)
        set(v) = sp.edit().putInt("secondary_widget", v).apply()

    /** Ouvre brièvement le launcher SAIC au démarrage pour qu'il enregistre son interface. */
    var warmUpStock: Boolean
        get() = sp.getBoolean("warmup_stock", true)
        set(v) = sp.edit().putBoolean("warmup_stock", v).apply()

    /** Mode Entretien : le launcher SAIC reste l'accueil, MG4Dash ne reprend pas la main. */
    var valetMode: Boolean
        get() = sp.getBoolean("valet_mode", false)
        set(v) = sp.edit().putBoolean("valet_mode", v).commit().let { }

    /** Limite de charge voulue (%), envoyée à la voiture. */
    var chargeLimit: Int
        get() = sp.getInt("charge_limit", 100)
        set(v) = sp.edit().putInt("charge_limit", v).apply()

    /** Masque la barre latérale et le bandeau SAIC (mode immersif). */
    var fullscreen: Boolean
        get() = sp.getBoolean("fullscreen", true)
        set(v) = sp.edit().putBoolean("fullscreen", v).apply()

    /** Widget central haut : 0 = carte OpenStreetMap, 1 = raccourcis. */
    /** Ancien SDK : remettre MG4Dash devant quand le launcher SAIC ouvre son écran clim (#48). */
    var hideSaicClimate: Boolean
        get() = sp.getBoolean("hide_saic_climate", true)
        set(v) = sp.edit().putBoolean("hide_saic_climate", v).apply()

    /** Passage jour / nuit : true = attente de 5 s et fondu (défaut), false = immédiat. */
    var themeSmooth: Boolean
        get() = sp.getBoolean("theme_smooth", true)
        set(v) = sp.edit().putBoolean("theme_smooth", v).apply()

    var homeWidget: Int
        get() = sp.getInt("home_widget", 0)
        set(v) = sp.edit().putInt("home_widget", v).apply()

    /** Bouton Android Auto du dock : composant d'une autre appli, ou null = Android Auto d'origine. */
    var dockAaTarget: String?
        get() = sp.getString("dock_aa_target", null)
        set(v) = sp.edit().putString("dock_aa_target", v).apply()

    /** Home Assistant : adresse (ex. http://homeassistant.local:8123) et jeton d'accès longue durée. */
    var haUrl: String
        get() = sp.getString("ha_url", "") ?: ""
        set(v) = sp.edit().putString("ha_url", v.trim()).apply()
    var haToken: String
        get() = sp.getString("ha_token", "") ?: ""
        set(v) = sp.edit().putString("ha_token", v.trim()).apply()

    /** Enregistreur de données énergie (diagnostic, fichier CSV). */
    /** Style « Carte » : page affichée dans la pile (0 énergie, 1 trajet, 2 puissance, 3 téléphone). */
    var stackPage: Int
        get() = sp.getInt("stack_page", 0)
        set(v) = sp.edit().putInt("stack_page", v).apply()

    /** Pile automatique : revient sur la batterie quand la voiture est branchée. */
    var stackAuto: Boolean
        get() = sp.getBoolean("stack_auto", true)
        set(v) = sp.edit().putBoolean("stack_auto", v).apply()

    /** Case de gauche du dock : 0 = horloge, 1 = réglages Wi-Fi. */
    var dockSlot: Int
        get() = sp.getInt("dock_slot", 0)
        set(v) = sp.edit().putInt("dock_slot", v).apply()

    /** Carte de l'accueil orientée selon le cap de la voiture (sinon nord en haut). */
    var mapHeadingUp: Boolean
        get() = sp.getBoolean("map_heading_up", false)
        set(v) = sp.edit().putBoolean("map_heading_up", v).apply()

    /** Mise à jour : proposer aussi les versions bêta (pré-versions GitHub). */
    var updateBeta: Boolean
        get() = sp.getBoolean("update_beta", true)
        set(v) = sp.edit().putBoolean("update_beta", v).apply()

    /** Release non bêta pour laquelle l'utilisateur a choisi « ne plus notifier » au démarrage (version, ex. 1.1.0). */
    var updateSkipped: String?
        get() = sp.getString("update_skipped", null)
        set(v) = sp.edit().putString("update_skipped", v).apply()

    var energyLog: Boolean
        get() = sp.getBoolean("energy_log", true)
        set(v) = sp.edit().putBoolean("energy_log", v).apply()

    /** Style de l'accueil : 0 = complet (3 colonnes), 1 = épuré (3 volets + 2 raccourcis + météo). */
    var homeStyle: Int
        get() = sp.getInt("home_style", 0)
        set(v) = sp.edit().putInt("home_style", v).apply()

    /** Thème : fond (0 bleu nuit, 1 noir, 2 anthracite, 3 vert sombre, 4 bordeaux) et boutons (0 bleu, 1 gris, 2 rouge, 3 vert, 4 orange). */
    var themeBackground: Int
        get() = sp.getInt("theme_bg", 1)   // noir par défaut (Marc, 29/10)
        set(v) = sp.edit().putInt("theme_bg", v).commit().let { }
    /** Horloge en 12 h sans AM / PM ; 24 h par défaut (#18). */
    var clock12: Boolean
        get() = sp.getBoolean("clock_12", false)
        set(v) = sp.edit().putBoolean("clock_12", v).apply()
    /** Contour des panneaux de l'accueil : estompé vers le bas (style tableau de bord, #38) ou complet (#40). */
    var panelFade: Boolean
        get() = sp.getBoolean("panel_fade", true)
        set(v) = sp.edit().putBoolean("panel_fade", v).apply()
    /** Clair / sombre : 0 = auto (jour / nuit de la voiture), 1 = sombre, 2 = clair. */
    var themeMode: Int
        get() = sp.getInt("theme_mode", 0)
        set(v) = sp.edit().putInt("theme_mode", v).commit().let { }
    var themeAccent: Int
        get() = sp.getInt("theme_accent", 0)
        set(v) = sp.edit().putInt("theme_accent", v).commit().let { }

    val raw: SharedPreferences get() = sp
}
