# MG4Dash

Full-screen launcher for the MG4 and its 1920 × 720 screen, for every head unit firmware (SWI69, SWI131/132/173, SWI68, SWI133, SWI165; see [Compatibility](#compatibility-beta)). It replaces the stock home screen with one dashboard (climate, battery, map, weather, media) and adds Climate, Energy, Vehicle, Sound and Phone pages, in dark, light or automatic mode following the car's lights.

Languages: English (default), French, German, Spanish, Italian, Dutch and Turkish, following the car's system language. Units: km and kWh/100 km or miles and mi/kWh (following the car or set by hand), tyre pressure in bar, psi or kPa.

Current version: **1.6.0** ([releases](https://github.com/Skittle6938/MG4Dash/releases)). Betas (1.6.1-beta…) can be installed from the app, see [Updates](#updates).

> Screenshots are rendered on a PC with simulated data and the **default settings** unless stated otherwise; the map excerpt is a fixed view of Paris (OpenStreetMap).

**Contents**: [Home screen](#home-screen) · [Media player](#media-player) · [Energy card](#energy-card) · [Bottom-right widget](#bottom-right-widget) · [Map card](#map-card) · [Other layouts](#other-home-layouts) · [Themes](#themes-and-day--night) · [Pages](#pages) · [Settings](#settings) · [Updates](#updates) · [Compatibility](#compatibility-beta) · [Install](#install-on-the-car) · [Gestures](#gestures)

## Home screen

![Home screen, default settings](docs/screenshots/home.png?v=21)

The default home screen (*Full* layout, vertical bar on the left). From left to right:

- **Vertical bar**: Settings, Phone, Energy, Vehicle and Home, with the space at the top left for MG's fog light button. Each icon also has a long press or double tap shortcut, see [Gestures](#gestures).
- **Climate card**: set temperature (LO → HI in 1° steps) and outside temperature, fan from off to maximum, then power, A/C, AUTO, ECO, air directions and recirculation.
  - Fan minus at the lowest speed turns the climate off; long press jumps to the lowest speed.
  - Long press A/C for **intermittent A/C** (the compressor runs in cycles; tap A/C to stop).
  - Long press the temperature to calibrate the displayed set point (−5° to +5°, display only).
  - Long press the power button for [Keep climate on](#keep-climate-on).
- **Energy card**: battery % to the tenth, range, average consumption, drive mode, KERS gauge (tap a segment for the regeneration level, long press for one-pedal driving) and the Charge / V2L button. See [Energy card](#energy-card).
- **Map card**: OpenStreetMap map following the car, with speed, heading and altitude. See [Map card](#map-card).
- **Weather card**: Open-Meteo forecast for the car's position, hourly or daily (swipe or tap the dots). When a refresh fails (no data connection) the last forecast stays for up to 3 hours.
- **Media card**: see [Media player](#media-player).
- **Bottom-right widget**: trip energy, live power or phone favourites. See [Bottom-right widget](#bottom-right-widget).

**Heated seats and steering wheel**: on equipped cars, a row of buttons sits above the temperature (driver seat, steering wheel, passenger seat). Settings › Vehicle › Heated seats and steering wheel: Auto (detected), Yes or No.

![Heated seats and steering wheel](docs/screenshots/home_heated_seats.png?v=21)

## Media player

The media card follows the car's active audio source (DAB / FM radio, Bluetooth, USB, Android Auto, CarPlay) and other apps playing music on the head unit. Swipe it up / down for the next source, left / right for the next / previous track (like the steering wheel buttons), tap the source name (« Radio DAB ▾ ») for the source menu, tap the picture to open the source's app, long press play / pause for the [Sound](#sound) page.

The player (Full, Map and Simple layouts) works like the Android Auto player: the album art or station image covers the card (black bars above and below are cropped as long as the image still fits the card's width, and its left edge colour extends it with a fade), title and artist over it, play button and progress bar in the image's main colour. Without an image, a white or black background following the theme.

| Bluetooth | Android Auto |
|---|---|
| ![Bluetooth player](docs/screenshots/home_theme_charcoal_orange.png?v=21) | ![Android Auto player](docs/screenshots/home_light.png?v=21) |

**Sources**: the source menu only lists the sources available right now: Bluetooth, USB and Android Auto / CarPlay appear when a phone or a USB key is connected, and other apps playing music (for example [DiAuto MG4+](https://github.com/Skittle6938/DiAuto-MG4) or SimpMusic) are offered too. Switching to an app sends it play, leaving it pauses it, and when an app starts playing the other apps are paused. DAB or FM is chosen in the radio app, as on the stock home screen.

**Audio source at startup** (Settings › Home › Audio source): *Automatic* (default) remembers the sources you pick and goes back by itself to the last one still available, as soon as it returns (phone reconnected, USB key plugged in…); a source that disappears is replaced after 1 s. You can also leave it to the car (*Same as the car*) or always start on Radio, Bluetooth, USB or CarPlay / Android Auto. CarPlay is never picked automatically. *Playback at startup*: as when the car was switched off (default), always paused or always playing.

**Steering wheel**: left / right change track on the source shown, apps included. The car does not know the apps (DiAuto, SimpMusic…) and still hands these buttons to its last own source, often the radio, which briefly takes the sound: MG4Dash puts the radio back on its station and returns to the app at once.

## Energy card

By default the energy card shows the battery, the range bar, the average consumption, the drive mode and KERS. The **range bar** has one colour following the level (green, orange below 30 %, red below 15 %); the % at the start of the trip is marked on it, with the part used since then in a lighter shade.

Settings › Home › *Live power in the energy card* replaces the drive mode and KERS with a live power bar, kW and the average consumption; the charge / V2L line only shows when plugged in (the trip start marker then hides so the range stays aligned with the %). *Voltage and current under the power bar* (off by default) adds the battery voltage with its trip minimum and the current with its trip maximum.

| Live power, plugged in | Live power with voltage and current, driving |
|---|---|
| ![Live power in the energy card](docs/screenshots/home_energy_card_power.png?v=21) | ![Live power with voltage and current](docs/screenshots/home_energy_card_power_driving.png?v=21) |

## Bottom-right widget

Swipe or tap it to switch between its pages:

- **Trip energy**: one bar and the share of driving, climate control and accessories since start-up (details on the Energy page).
- **Live power**: kW drawn from or recovered into the battery every second, on a two-way bar from zero with a 10 s peak marker, the trip average, voltage and current. It hides a **full-throttle special**: floor it and a big rocket crosses the whole screen (slowing down in the middle, its smoke fading quickly), or lightning, or flames, then the peak reached shows (Settings › Look › Live power). Around Halloween (25 October to 2 November) a jack-o'-lantern joins them 🎃. This page leaves the widget when *Live power in the energy card* is on.
- **Phone**: favourite contacts, 3 by default (tap to call, long press to change one or to pick 2, 3, 4, 6 or 8 shortcuts, on one or two rows; contacts already chosen are kept).

| | |
|---|---|
| ![Live power](docs/screenshots/home_widget_power.png?v=21) | ![Phone favourites](docs/screenshots/home_widget_phone.png?v=21) |
| ![Full-throttle special](docs/screenshots/boost_rocket_on.png?v=21) | |

## Map card

- **Map**: follows the car, north up or heading up (tap the recenter button again), default zoom 14 to 18 and colours (auto, light, dark) in Settings › Home. The zoom and full screen buttons hide after a few seconds. Without data connection the map stays as long as the area is in the tile cache, then the card switches to consumption (can be turned off).
- **Consumption**: the button at the top right switches map → consumption → shortcuts. Period menu: since the last charge, current trip (one point per km, one every 2 minutes when stopped, from the energy measured by MG4Dash) or last 12 months (one point per trip). The dashed line is the average, with its value.
- **Shortcuts**: 3 rows of apps or Home Assistant actions (scripts, automations, scenes, gate, garage door…). Tap an empty slot or long press a slot to change it; the Home Assistant address and token are set in Settings › Connections. The shortcuts page can be turned off.
- **Full screen**: the button below the zoom enlarges the map over the whole screen; any bar button brings it back.

| Consumption | Shortcuts |
|---|---|
| ![Consumption](docs/screenshots/home_map_layout_consumption.png?v=21) | ![Shortcuts](docs/screenshots/home_map_layout_shortcuts.png?v=21) |

**Another app in the map card**: long press the map to show another installed app inside the card instead of the map, for example an EV route planner such as ABRP or a navigation app such as Waze. It runs live and can be touched; the card buttons fade out after 5 s and come back on a touch, and the full screen button enlarges the app (it stays full screen through day / night changes until you press Home or the button again). Pick "OpenStreetMap map" in the same list to go back. *The apps below are drawn for the example.*

| | |
|---|---|
| ![EV route planner in the map card](docs/screenshots/home_map_app_planner.png?v=21) | ![Navigation app in the map card](docs/screenshots/home_map_app_guidance.png?v=21) |

**Turn-by-turn**: with [DiAuto MG4+](https://github.com/Skittle6938/DiAuto-MG4) (or another Headunit Revived-based Android Auto app that broadcasts `com.andrerinas.headunitrevived.NAVIGATION_UPDATE`), the next manoeuvre is shown over the map: arrow, distance, street, time and distance left, arrival time. The stock Android Auto app does not receive this information.

![Navigation banner](docs/screenshots/home_map_layout_nav.png?v=21)

## Other home layouts

Settings › Home › Home layout: **Full** (default), **Map** or **Simple**; vertical bar on the left (default) or bottom dock.

| Map layout | Simple layout |
|---|---|
| ![Map layout](docs/screenshots/home_map_layout.png?v=21) | ![Simple layout](docs/screenshots/home_simple.png?v=21) |
| Weather with date and time, climate, a stack (energy, trip energy, live power, phone; swipe to change, back to the battery when plugged in) above the media, and the map over the whole right column. | Fewer buttons and bigger text: climate, battery and media panels, two shortcuts and today's weather. Tap a panel to open its page. |
| **Bottom dock** | **Right-hand drive** |
| ![Bottom dock](docs/screenshots/home_bottom_bar.png?v=21) | ![Vertical bar on the right](docs/screenshots/home_right_hand.png?v=21) |
| Vertical bar turned off: dock at the bottom with clock and date (long press the clock: clock or Wi-Fi settings). | On right-hand drive cars (detected), the vertical bar goes on the right, next to the driver. |

## Themes and day / night

Settings › Look: **Auto** (default, follows the car's lights), **Dark** or **Light**; 5 backgrounds and 5 button colours, applied to every page and to [Radio Dash](https://github.com/Skittle6938/MG4-Radio-Dash). Battery green, heating orange, cooling cyan and alert red keep their meaning.

- *Smooth light / dark switch* (on by default): the switch waits for 5 s of stable lights (no flicker under a bridge) and fades over 2 s. Off: it switches as soon as the lights change.
- *Home panel outline*: fading downwards (dashboard style, default) or full.

| Light mode | Charcoal background, orange buttons |
|---|---|
| ![Light mode](docs/screenshots/home_light.png?v=21) | ![Charcoal and orange](docs/screenshots/home_theme_charcoal_orange.png?v=21) |

## Pages

### Climate
![Climate](docs/screenshots/climate.png?v=21)
Temperature, fan speed, combinable air directions, AUTO, A/C, ECO, recirculation and front / rear defrost. On old firmwares (SWI68, SWI133, SWI165), MG4Dash comes back in front when the stock launcher opens its own climate screen (Settings › Vehicle, on by default).

### Keep climate on
A camp mode, off by default (Settings › Vehicle › *Keep climate on*), then a long press on MG4Dash's climate power button. It keeps the car on with the climate running while you are away or sleeping in the car: **now** (for a set time or without limit), or **at a set time** in one or more runs with a pause between them (the A/C compressor is switched on at each run).

- After a countdown (20 s by default) **MG4Dash locks the doors itself**: get out and take the key with you. Do not lock with the key fob, which switches the whole car off and ends the mode.
- A large status screen stays readable from outside: outside temperature, climate state, time left, runs and battery. Its brightness drops to the minimum once locked (a tap brings it back for 10 s), and an option turns it fully off.
- The mode ends after the last run, at the end of the set time or below 20 % battery, and stops as soon as the doors are unlocked or the car drives off. Leave the light stalk on Auto.
- **Never leave a child or an animal alone in the car**: the climate can stop at any time (battery, fault, car switching off).

| | | |
|---|---|---|
| ![At a set time](docs/screenshots/climhold_menu_at_en.png?v=21) | ![Countdown](docs/screenshots/climhold_countdown_en.png?v=21) | ![Status screen](docs/screenshots/climhold_screen_en.png?v=21) |

### Energy
![Energy](docs/screenshots/energy.png?v=21)
Charging (start / stop, charge limit where the car applies it, AC current, time left, input voltage and current, reason when the car stops charging, cable lock), scheduled charging, V2L (adapter state, stop threshold, start / stop), battery heating while driving, and consumption by period (driving, climate, accessories, energy recovered by braking, consumption curve).

### Vehicle
![Vehicle](docs/screenshots/vehicle.png?v=21)
Drive mode (with Eco, Snow and Custom where available), regeneration (level 6 is one-pedal driving), driver assistance, tyre pressures and temperatures, vehicle status, a shortcut to MG4 Control, and the **battery health estimate**: while driving, MG4Dash measures the energy taken from the battery and divides it by the drop in battery %; from a 15 % drop (added up over several trips) it gives a dated measurement of the usable capacity, averaged over 30 days and compared with a new battery of your model (51, 64 or 77 kWh). This is an estimate, not an official measurement.

### Sound
![Sound](docs/screenshots/sound.png?v=21)
Vehicle › *Sound*, or a long press on play / pause. Drag the dot on the MG4 seen from above to set the balance and fader (double tap or *Centre* to reset). 5-band equaliser with the car's presets (Classic, Jazz, Pop, Rock, Vocal) or your own curve, *Loudness* and *Flat*. A live spectrum of what is playing is shown behind the sliders for Bluetooth, USB and apps; the radio's sound does not go through Android, so the spectrum stays flat on the radio. These are the car's own settings, the same as the stock sound menu.

### Phone
![Phone](docs/screenshots/phone.png?v=21)
Recent calls, contacts with search and a keypad, from the phone connected over Bluetooth. Calls go through the hands-free account, like the stock phone app, whose in-call screen is kept.

### Startup
![Startup animation](docs/screenshots/splash.png?v=21)
While the head unit starts, a short MG4Dash animation replaces Android's "Phone is starting" screen; the home screen opens as soon as the system is ready.

## Settings

Five tabs along the top; the last one used opens next time. Each on / off option is a single button that lights up when it is on. Dialogs and messages follow the MG4Dash theme.

| Home | Look |
|---|---|
| ![Settings, Home](docs/screenshots/settings_en_tab0.png?v=21) | ![Settings, Look](docs/screenshots/settings_en_tab1.png?v=21) |
| **Vehicle** | **System** |
| ![Settings, Vehicle](docs/screenshots/settings_en_tab2.png?v=21) | ![Settings, System](docs/screenshots/settings_en_tab4.png?v=21) |

| Tab | Options |
|---|---|
| Home | home layout (Full, Simple, Map), audio source and playback at startup, vertical bar, full screen (hides the SAIC side bar and banner), live power in the energy card, voltage and current under the power bar, map stack back to the battery when plugged in, map zoom and colours, hide the map buttons, shortcuts page, switch to the energy widget when offline |
| Look | light / dark / auto, smooth light / dark switch, panel outline, background, button colour, full-throttle special |
| Vehicle | units, tyre pressure unit, heated seats and steering wheel, Keep climate on, keep MG4Dash in front of the SAIC climate screen (old firmwares), usable battery capacity, battery model, MG4 XPower |
| Connections | weather (fallback city or current position), Home Assistant (address, token, test) |
| System | replace the SAIC home, maintenance mode, briefly open the SAIC launcher at startup, updates (include betas, Radio Dash), diagnostics (compatibility test, log, energy data recorder) |

## Updates

Settings › System › *Check* downloads the latest MG4Dash release from GitHub and installs it (internet connection needed). Turn on *Include beta versions* to also get the betas, numbered after the next release (1.6.1-beta0, 1.6.1-beta1…). When a new official release is out, MG4Dash also offers it at startup: *Update*, *Later* or *Don't notify me again for this release*.

**Radio Dash**: on SWI69 with the matching radio app, the same page installs the latest [Radio Dash](https://github.com/Skittle6938/MG4-Radio-Dash) (the radio redesigned in the MG4Dash style); restart the head unit afterwards by holding Home about 20 s.

## Compatibility (beta)

MG4Dash detects the head unit firmware at startup (`ro.build.mt2712.version`) and picks the matching way to talk to the car, using what [MG4Control](https://github.com/SliDeeN/MG4Control) documented for each firmware:

| Firmware | Car access | Status |
|---|---|---|
| SWI69 | `caradapter` service (climate, EV, settings, general) | tested by the author (daily use) |
| SWI131, SWI132, SWI173 | same as SWI69 | beta; SWI173 reported in [#33](https://github.com/Skittle6938/MG4Dash/issues/33) / [#35](https://github.com/Skittle6938/MG4Dash/issues/35) |
| SWI68 | climate, charging, V2L, radio and driving assistance through the stock launcher's SDK (`AirConditionManager`, `VehicleChargingManager`, `VehicleControlManager` in `com.saicmotor.hmi.launcher`); battery, range, charge state, drive mode, KERS, speed and charge control through `CarPropertyManager` | beta, tested by users ([#19](https://github.com/Skittle6938/MG4Dash/issues/19)): radio, Eco / Snow modes, start / stop charging and cable lock confirmed |
| SWI133 | same as SWI68, with battery, range and charge state read from the car properties (no EV service) | beta, user reports in [#13](https://github.com/Skittle6938/MG4Dash/issues/13) |
| SWI165 | same as SWI68 | beta, tested by users ([#28](https://github.com/Skittle6938/MG4Dash/issues/28), [#37](https://github.com/Skittle6938/MG4Dash/issues/37)): climate, charging and charge limit, cable lock, Keep climate on confirmed |

All MG4 firmwares share the 1920 × 720 screen and the AOSP platform key, so the same APK installs on every one of them.

Features a firmware does not expose are hidden instead of failing (for example the charge limit, only shown where the car applies it itself: MG4Dash cannot stop the charge for the car, it is shut down when the car is locked). The stock home screen and the stock energy and settings apps are found automatically. Radio Dash only works on SWI69.

**Help support your firmware**: open Settings › Compatibility › Run the test, then scan the QR code with your phone. It opens a pre-filled [compatibility issue](https://github.com/Skittle6938/MG4Dash/issues/new?template=compatibility.md) with a short report (versions, which services answer, a few car readings: no position, VIN or account). Nothing is sent automatically.

## Project layout

- `app/src/main/java/app/mg4dash/`: the app (Kotlin)
  - `car/CarBridge.kt`: vehicle access through `com.saicmotor.caradapter` (climate code 7, EV code 4, settings code 8, general code 1)
  - `car/CarProps.kt`: BMS, charging and consumption properties through CarPropertyManager
  - `car/LegacyCar.kt`: climate on the SWI68 / SWI165 / SWI133 firmwares (stock launcher SDK)
  - `system/Firmware.kt`: firmware detection
  - `settings/Compat.kt`: compatibility report and QR code
  - `car/CarApi.kt`: binder clients written for MG4Dash (descriptors and transaction numbers observed on the car)
  - `energy/ConsumptionEstimator.kt`: kWh/100 estimated from battery % and kilometres
  - `energy/EnergyMonitor.kt`, `energy/ConsumptionCurve.kt`: energy measured from battery voltage × current, split into driving / climate / accessories, and the consumption curves
  - `ui/MediaStyle.kt`: media player presentation
  - `system/Projection.kt`: Android Auto / CarPlay session read from the AllGo remote UI service
  - `energy/EnergyLogger.kt`: read-only CSV recorder of battery power, consumption counters and equipment states
  - `media/MediaHub.kt`: active player and audio sources (the car's and other apps'), automatic source, steering wheel for apps
  - `map/NavRepo.kt`: turn-by-turn data broadcast by DiAuto / Headunit Revived
  - `shortcuts/Shortcuts.kt`: shortcut grid and Home Assistant REST calls
  - `weather/WeatherRepo.kt`: Open-Meteo weather
  - `system/HomeSwitcher.kt`: maintenance mode (gives the home screen back to the SAIC launcher)
  - `LauncherApp.kt`: crash guard, after 3 crashes in 3 minutes the SAIC launcher becomes the home screen again
- `app/src/car/`: car manifest (`sharedUserId=android.uid.system`)
- `app/src/emulator/`: build without the system uid, with simulated data
- `i18n/`: translations (`strings.psv`) and the generator of the `values-*/strings.xml` files
- `keys/`: AOSP platform test certificate (public, the same one the car uses)

## Signing key

The private key is not in the repository. Download the AOSP platform test key (the one the SAIC apps are signed with):

```powershell
$u = "https://android.googlesource.com/platform/build/+/refs/tags/android-9.0.0_r61/target/product/security/platform.pk8?format=TEXT"
[IO.File]::WriteAllBytes("keys\platform.pk8", [Convert]::FromBase64String((Invoke-WebRequest $u -UseBasicParsing).Content))
```

## Build

```powershell
.\sign_platform.ps1                       # signed car build → dist\MG4Dash-<version>.apk
.\gradlew.bat testEmulatorDebugUnitTest   # screenshots 1920×720 → app\build\screenshots\
py -3 i18n\gen.py                         # regenerate the translations after editing i18n\strings.psv
```

## Install on the car

`adb install` is refused on this head unit; push the APK and install it as a system app update:

```
adb push MG4Dash-<version>.apk /data/local/tmp/x.apk
adb shell pm install -r -f /data/local/tmp/x.apk
adb shell rm /data/local/tmp/x.apk
```

1. Try it without changing the home screen: `adb shell am start -n app.mg4dash/.MainActivity`
2. Make it the home screen: tap "Settings" in the dock > "Replace SAIC home". This only disables the SAIC home activity, whose HOME priority of 2 cannot be beaten otherwise.

The SAIC launcher package (`com.saicmotor.launcher`) is never disabled: its service handles the camera, audio sources and some SystemUI commands.

## Going back

- Maintenance mode: tap "Settings" in the dock, then "Maintenance mode".
- If MG4Dash no longer starts after an update ("ClassNotFoundException" or "oat_file ... Check failed" in logcat), stop it, recompile it while it is stopped, then make it the home screen again:
  `adb shell am force-stop app.mg4dash`, `adb shell cmd package compile -m speed -f app.mg4dash`, `adb shell am force-stop app.mg4dash`, then `adb shell cmd package set-home-activity --user 0 app.mg4dash/.MainActivity`.
  Compiling while MG4Dash runs (it relaunches itself right after an install) corrupts its compiled code: always force-stop first.
- Note: `adb shell pm enable com.saicmotor.launcher/.view.MainActivity` is refused on this head unit (the shell may not change component state); only MG4Dash's Maintenance mode can re-enable the SAIC home. Until then, Android offers a launcher chooser.
- Uninstall: `adb uninstall app.mg4dash`

## Gestures

- "Settings" in the dock or the vertical bar: tap for the launcher settings, long press for the car settings; double tap it in the vertical bar: Wi-Fi
- Long press "Home" in the dock: list of installed apps
- Long press the clock in the dock: clock or Wi-Fi settings button
- Vertical bar: double tap Energy turns the screen off, long press Energy switches the car off (never while moving); double tap Vehicle locks / unlocks the doors, long press Vehicle turns ESC off / on
- Long press "Android Auto" in the dock: Android Auto, CarPlay or another app
- Tap or swipe the bottom-right widget (or the map layout stack): next page; long press a phone favourite to change it
- Swipe the media card up / down: next audio source; left / right: next / previous track; tap its source name: source menu
- Long press the set temperature: temperature calibration; long press fan minus: lowest fan speed; long press A/C: intermittent A/C
- Long press play / pause: Sound page
- Steering wheel * button: 360 camera on / off
- Swipe the weather card: hourly ↔ daily forecast
- Tap a segment of the KERS gauge: regeneration level; long press: one-pedal driving
- Button at the top right of the map: map → consumption → shortcuts; long press a shortcut to edit it
- Long press the map: show another app in the map card
- Fan minus at the lowest speed: climate off
- Map "recenter" button: resume following the car; press again to switch between north up and car heading up (green)

## Credits

- [MG4Control](https://github.com/SliDeeN/MG4Control) (SliDeeN): documentation of the A9 carapi path, of the climate and air-direction ranges, and of the per-firmware car access used by the compatibility beta.
- [MG4-Custom-Launcher](https://github.com/adammcdonagh/MG4-Custom-Launcher) (adammcdonagh): Bluetooth album art, the discovery of the AOSP test keys and the stock launcher SDK on SWI68.
- [MG4_Simple_Launcher](https://github.com/Tommasov/MG4_Simple_Launcher) (Tommasov): notes on the vehicle adapter service of other firmwares.
- Maps © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (osmdroid), weather by [Open-Meteo](https://open-meteo.com/).

## Support

MG4Dash is free and will stay free. If it is useful to you and you want to help cover the AI subscription and the time spent on it, you can leave a few euros here: [Ko-fi](https://ko-fi.com/skittle6938). Entirely optional, thank you!

## Licence

MG4Dash is free software under the [GNU General Public License v3](COPYING) or later. This applies to every published version, including the releases before 0.10.0. Parts of the firmware detection and of the SWI68 / SWI165 / SWI133 climate code are adapted from [MG4Control](https://github.com/SliDeeN/MG4Control) (GPL v3; earlier MG4Control code under the MIT licence, notice kept in [licenses/MG4Control-MIT.txt](licenses/MG4Control-MIT.txt)).

Personal project, not affiliated with SAIC / MG. Use at your own risk.
