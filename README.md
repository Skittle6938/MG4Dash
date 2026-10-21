# MG4Dash

Full-screen launcher for the MG4 and its 1920 × 720 screen, for every head unit firmware (SWI69, SWI131/132/173, SWI68, SWI133, SWI165; see [Compatibility](#compatibility-beta)): three home layouts, Climate, Energy, Vehicle and Phone pages linked by a vertical bar or a bottom dock, dark, light or automatic mode following the car's lights, and five background and button colour themes.

Languages: English (default), French, German, Spanish, Italian and Dutch, following the car's system language. Units: km and kWh/100 km or miles and mi/kWh (following the car or set by hand), tyre pressure in bar, psi or kPa.

Current version: **1.4.0** ([releases](https://github.com/Skittle6938/MG4Dash/releases)).

> Screenshots are rendered on a PC with simulated data; the map excerpt is a fixed view of Paris (OpenStreetMap).

### Home
![Home](docs/screenshots/home.png?v=15)
Shown with the vertical bar on the left, the default on a fresh install (the bottom dock is still available, see [Vertical bar](#vertical-bar)). Climate (temperature, fan, then two rows of buttons: power, A/C, AUTO, ECO, air directions, recirculation), energy (battery % to the tenth, range with a plug icon, estimated consumption, drive mode, KERS, Charge / Start V2L button), the map, Open-Meteo weather, a media card that follows the car's active audio source (DAB/FM radio, Bluetooth, USB, Android Auto) and a bottom-right widget with three pages (see below).

- **Range bar**: in the style of the live power bar, one colour following the level (green, orange below 30 %, red below 15 %); the % at the start of the trip is marked on the bar, with the part used during the trip in a lighter shade.
- **Media card**: swipe up / down to switch to the next available source (radio, Bluetooth, USB, Android Auto / CarPlay when connected); playback starts by itself. DAB+ stations show their slideshow image, or the station image cached by the radio.
- **Weather**: swipe it, or tap its dots, to switch between hourly and daily forecasts, with coloured icons (and a moon on clear nights). The forecast follows the car's position (refreshed every 5 km, city name every 2 km); a fallback city can be set, or taken from the current position, in Settings › Weather. As soon as an update fails (no data connection), it shows the time and the outside temperature, and retries until the connection and the position are back.
- **Climate card**: the fan goes from off to maximum; long press fan minus jumps to the lowest speed. Long press the temperature to calibrate the displayed set point (−5° to +5°, display only, the car keeps its own value).
- **Heated seats and steering wheel**: on equipped cars, a row of buttons above the temperature (see [below](#heated-seats-and-steering-wheel)).
- **Media source menu**: tap the source name on the media card to pick radio, Bluetooth, USB or Android Auto / CarPlay.
- **KERS gauge**: tap a segment to set the regeneration level (low, medium, high, adaptive); long press toggles one-pedal driving.

Long press the Android Auto button in the dock to make it open Android Auto, CarPlay or the app of your choice.

### Heated seats and steering wheel
![Heated seats and steering wheel](docs/screenshots/home_heated_seats.png?v=15)
On equipped cars, a row of three buttons sits above the climate temperature: driver seat, steering wheel and passenger seat. Tap a seat to step through its levels (off, then 1 to 3 heat arrows, lit while on), tap the steering wheel to turn it on or off. The same row appears on the Simple layout. Settings › Heated seats and steering wheel: Auto (detected), Yes or No.

### Bottom-right widget
Swipe or tap it to switch between three pages, all readable at a glance while driving:
- **Trip energy**: one big bar and the share of driving, climate control and accessories since start-up (details on the Energy page).
- **Live power**: kW drawn from or recovered into the battery, refreshed every second, on a two-way bar from zero (scale following the battery model, with a 10 s peak marker), with the trip average, the battery voltage and current (green while regenerating). It also hides a **full-throttle special**: floor it and the widget jumps to this page with a rocket, a lightning bolt or flames (or one at random), then goes back (Settings › Energy to pick it or turn it off). The scale and the special follow the MG4 XPower when it is detected or set in Settings › Energy.
- **Phone**: three favourite contacts chosen from the phone book (tap to call, long press to change).

| | |
|---|---|
| ![Live power](docs/screenshots/home_widget_power.png?v=15) | ![Phone favourites](docs/screenshots/home_widget_phone.png?v=15) |
| ![Full-throttle special](docs/screenshots/boost_rocket_on.png?v=15) | |

### Map home layout
![Map layout](docs/screenshots/home_map_layout.png?v=15)
A third layout (Settings › Home layout › Map), suggested in [issue #6](https://github.com/Skittle6938/MG4Dash/issues/6): weather with the date and time, climate on the left, a stack (energy, trip energy, live power, phone) above the media in the centre, and the map over the whole right column. Swipe the stack to change page; with the automatic option, it jumps back to the battery once when the car is plugged in. Speed, heading and altitude sit at the top left of the map; in heading-up mode the car is placed in the lower part to show more of the road ahead.

The map widget also has a **consumption** page and **3 rows of shortcuts** (button at its top right). Without data connection the map stays on screen as long as the area around the car is in the tile cache, and only then switches to consumption (this switch can be turned off in the settings). Default zoom (14 to 18) and map colours (Auto, following the car's day / night whatever the app theme, Light or Dark) are set in the launcher settings.

The consumption page has a period menu: since the last charge, current trip, last 1,000 km, or the last 12 months (one total and one point per month).

| | |
|---|---|
| ![Map layout, consumption](docs/screenshots/home_map_layout_consumption.png?v=15) | ![Map layout, shortcuts](docs/screenshots/home_map_layout_shortcuts.png?v=15) |
| ![Consumption over 12 months](docs/screenshots/home_map_layout_consumption_year.png?v=15) | |

### Vertical bar
![Vertical bar](docs/screenshots/home_map_layout_vertical.png?v=15)
Settings › Home layout › *Vertical bar on the left* (on by default on a fresh install): an icon bar on the left, with the space at the top for the MG fog light button. It applies at once to every home layout, every page and to Radio Dash. Turn it off to get the dock at the bottom instead (below, left).

- **Settings**: tap for the launcher settings, long press for the car settings, double tap for Wi-Fi.
- **Android Auto**: tap to open it, long press to choose Android Auto, CarPlay or another app.
- **Energy**: tap for the Energy page, double tap to turn the screen off like the stock launcher (touch the screen to wake it), long press to switch the car off (never while moving).
- **Vehicle**: tap for the Vehicle page, double tap to lock / unlock the doors, long press to turn ESC off / on (the icon stays red while it is off).
- **Phone** and **Home**.

| | |
|---|---|
| ![Bottom dock](docs/screenshots/home_bottom_bar.png?v=15) | ![Vertical bar, energy page](docs/screenshots/energy_vertical.png?v=15) |

Card titles are left out on every layout for a calmer screen; the media card keeps its source name, which opens the source menu.

### Turn-by-turn on the map
![Navigation banner](docs/screenshots/home_map_layout_nav.png?v=15)
With [DiAuto](https://github.com/merthankaraman/DiAuto-MG4) (or another Headunit Revived-based Android Auto app that broadcasts `com.andrerinas.headunitrevived.NAVIGATION_UPDATE`), the next manoeuvre is shown over the home map: arrow, distance, street, time and distance left, arrival time. The stock Android Auto app does not receive this information from the phone, so it only works with these apps.

### Simple home layout
![Simple home](docs/screenshots/home_simple.png?v=15)
An alternative, calmer home screen for those who want fewer buttons and bigger text: three large panels (climate, battery, media), two configurable shortcuts and today's weather, with the vertical bar, or a reduced bottom dock (Home, Phone, Android Auto, Settings). Choose it in the launcher settings, under "Home layout"; tap a panel to open the matching page.

### Shortcuts and Home Assistant
![Shortcuts](docs/screenshots/home_shortcuts.png?v=15)
The button at the top right of the map swaps it for pages of shortcuts: apps or Home Assistant actions (scripts, automations, scenes, buttons, gate, garage door…). Tap an empty slot, or long press any slot, to change it. The Home Assistant address and token are set in the launcher settings.

### Themes
![Charcoal background, orange buttons](docs/screenshots/home_theme_charcoal_orange.png?v=15)
Background (night blue, black, charcoal, dark green, dark burgundy) and button colour (blue, grey, red, green, orange) are chosen separately in the launcher settings and apply to every page. Battery green, heating orange, cooling cyan and alert red keep their meaning. Radio Dash follows the same theme.

Light mode: dark, light or auto in the same settings. In auto, every page (and Radio Dash, even while it stays on screen) switches to dark when the car's lights come on, and back to light when they go off.

![Light mode, home](docs/screenshots/home_light.png?v=15)

| | |
|---|---|
| ![Climate, light](docs/screenshots/climate_light.png?v=15) | ![Energy, light](docs/screenshots/energy_light.png?v=15) |
| ![Simple home, light](docs/screenshots/home_simple_light.png?v=15) | ![Settings, light](docs/screenshots/settings_light.png?v=15) |

### Climate
![Climate](docs/screenshots/climate.png?v=15)
Temperature in 1° steps (LO → HI), fan speed, combinable air direction, AUTO, A/C, ECO, recirculation and front / rear defrost.

### Keep climate on
![Keep climate on, at a set time](docs/screenshots/climhold_menu_at_en.png?v=15)
A camp mode, off by default (Settings › Home layout › *Keep climate on*), then a long press on MG4Dash's climate power button (home screen or Climate page). It keeps the car on with the climate running while you are away or sleeping in the car: **now** (for a set time or without limit), or **at a set time** in one or more runs with a pause between them.

- After a countdown (20 s by default) **MG4Dash locks the doors itself**: get out and take the key with you. Do not lock with the key fob, which switches the whole car off, head unit included, and ends the mode.
- A large status screen stays readable from outside: outside temperature, climate state, time left, runs and battery. It is always dark, its brightness drops to the minimum once locked (a tap brings it back for 10 s), and an option turns it fully off for sleeping or discretion.
- The mode ends after the last run, at the end of the set time or below 20 % battery (climate off, car switched off), and stops as soon as the doors are unlocked or the car drives off.
- The lights follow the light stalk: leave it on Auto.
- **Never leave a child or an animal alone in the car**: the climate can stop at any time (battery, fault, car switching off).

| | |
|---|---|
| ![Countdown before locking](docs/screenshots/climhold_countdown_en.png?v=15) | ![Status screen](docs/screenshots/climhold_screen_en.png?v=15) |

### Energy
![Energy](docs/screenshots/energy.png?v=15)
Charging (start / stop, charge limit on firmwares where the car applies it, AC current, time left, AC input voltage and current, reason when the car stops charging, charging cable lock), scheduled charging, V2L (adapter state, stop threshold, start / stop), battery heating while driving (same switch as the stock Energy app) and consumption (since the last charge, current trip, last 1,000 km or last 12 months: driving, climate, accessories, energy and range recovered by braking, consumption curve).

### Vehicle
![Vehicle](docs/screenshots/vehicle.png?v=15)
Drive mode (including Eco, Snow and Custom where the car has them), regeneration (tap the gauge to change it; level 6 is one-pedal driving), driver assistance, tyre pressures and temperatures, and vehicle status, with a shortcut to MG4 Control.

### Phone
![Phone](docs/screenshots/phone.png?v=15)
Recent calls, contacts with a search field and a keypad, from the phone connected over Bluetooth. Calls go through the hands-free (HFP) account, like the stock phone app, whose in-call screen is kept.

### Battery health (estimated)
Shown on the Vehicle page. While driving, MG4Dash measures the energy actually taken from the battery (regeneration deducted) and divides it by the drop in battery % over the same time. Only what MG4Dash sees with the screen on counts, so the drop adds up over several trips and days, without waiting for a charge; from a 15 % drop, that gives one dated measurement of the usable capacity. The average of the last 30 days, weighted by the % drop, is shown to one decimal with the distance it covers, against the usable capacity of a new battery of your model (51, 64 or 77 kWh, chosen in Settings › Energy). This is an estimate made by MG4Dash, not an official measurement, and the feature is still being tested.

The car does not share the battery or motor temperature with the head unit, so MG4Dash cannot show them.

### Launcher settings
![Settings](docs/screenshots/settings.png?v=15)
Grouped by category in one scrolling page. Each on/off option is a single button that lights up when it is on.

| Section | Options |
|---|---|
| Home layout | Full, Simple or Map; vertical bar on the left; full screen (hides the SAIC side bar and banner); units (like the car, km · kWh/100 km, miles · mi/kWh); heated seats and steering wheel buttons (auto, yes, no); tyre pressure unit (auto, bar, psi, kPa); default map zoom; map colours (auto, light, dark); switch to the energy widget when offline; map layout stack back to the battery when plugged in; Keep climate on |
| Theme | background, button colour, light / dark / auto (follows the car's lights) |
| Energy | usable battery capacity (read from the car, measured, or set by hand), battery model for the health estimate, MG4 XPower, full-throttle special |
| Home screen | replace the SAIC home, maintenance mode, briefly open the SAIC launcher at startup |
| Update | check and install, include beta versions; Radio Dash update (SWI69 only) |
| Weather | fallback city, or use my position |
| Home Assistant | address, token, connection test |
| Diagnostics | compatibility test, built-in log, energy data recorder |

**Update**: Settings › Update › Check downloads the latest MG4Dash release from GitHub and installs it (internet connection needed); turn on *Include beta versions* to also get the betas, numbered after the next release (1.4.1-beta0, 1.4.1-beta1…). When a new official (non-beta) release is out, MG4Dash also offers it in a popup at startup: *Update*, *Later*, or *Don't notify me again for this release*.

**Radio Dash**: on SWI69 with the matching radio app, Settings › Update also installs the latest [Radio Dash](https://github.com/Skittle6938/MG4-Radio-Dash) release (restart the head unit afterwards by holding Home about 20 s).

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
  - `energy/EnergyLogger.kt`: read-only CSV recorder of battery power, consumption counters and equipment states
  - `media/MediaHub.kt`: active player, chosen from the car's audio source like the stock launcher
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
- Swipe the media card up / down: next audio source; tap its source name: source menu
- Long press the set temperature: temperature calibration; long press fan minus: lowest fan speed
- Swipe the weather card: hourly ↔ daily forecast
- Tap a segment of the KERS gauge: regeneration level; long press: one-pedal driving
- Button at the top right of the map: map → consumption → shortcuts; long press a shortcut to edit it
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
