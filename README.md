# MG4Dash

Full-screen launcher for the MG4 and its 1920 × 720 screen, for every head unit firmware (SWI69, SWI131/132/173, SWI68, SWI133, SWI165; see [Compatibility](#compatibility-beta)). It replaces the stock home screen with one dashboard (climate, battery, map, weather, media) and adds Climate, Energy, Vehicle, Sound and Phone pages, in dark, light or automatic mode following the car's lights.

Languages: English (default), French, German, Spanish, Italian, Dutch and Turkish, following the car's system language. Units: km and kWh/100 km or miles and mi/kWh (following the car or set by hand), tyre pressure in bar, psi or kPa.

> [!IMPORTANT]
> **Safety net: MG4Dash Rescue (since 1.7.1).** MG4Dash now installs a tiny companion system app by itself, *MG4Dash Rescue*. If MG4Dash is your home screen and does not start after the head unit boots, Rescue repairs it (recompiles and relaunches it) and, if that is not enough, puts the SAIC home screen back. It also brings the SAIC home screen back if MG4Dash is uninstalled without going through *Maintenance mode* first. If MG4Dash is not your home screen, it does nothing. Installing 1.7.1 or later is strongly recommended.

Current version: **1.7.1** ([releases](https://github.com/Skittle6938/MG4Dash/releases)). Betas (1.7.2-beta…) can be installed from the app, see [Updates](#updates).

> Screenshots are rendered on a PC with simulated data and the **default settings** unless stated otherwise; the map excerpt is a fixed view of Paris (OpenStreetMap).

**Contents**: [Home screen](#home-screen) · [Media player](#media-player) · [Energy card](#energy-card) · [Bottom-right widget](#bottom-right-widget) · [Map card](#map-card) · [Other layouts](#other-home-layouts) · [Themes](#themes-and-day--night) · [Pages](#pages) · [Settings](#settings) · [Updates](#updates) · [Compatibility](#compatibility-beta) · [Install](#install-on-the-car) · [Replace / restore the SAIC home](#home-screen-replace-and-go-back) · [Gestures](#gestures)

## Home screen

![Home screen, default settings](docs/screenshots/home.png?v=25)

The default home screen (*Full* layout, vertical bar on the left). From left to right:

- **Vertical bar**: Settings, Phone, Energy, Vehicle and Home, with the space at the top left for MG's fog light button. Each icon also has a long press or double tap shortcut, see [Gestures](#gestures).
- **Climate card**: set temperature (LO → HI in 1° steps) and outside temperature, fan from off to maximum, then power, A/C, AUTO, ECO, air directions and recirculation.
  - Fan minus at the lowest speed turns the climate off; long press jumps to the lowest speed.
  - Long press A/C for **intermittent A/C** (the compressor runs in cycles; tap A/C to stop).
  - Long press the temperature to calibrate the displayed set point (−5° to +5°, display only).
  - Long press the power button for [Keep climate on](#keep-climate-on).
- **Energy card**: battery % to the tenth, range, average consumption, drive mode, KERS gauge (tap a segment for the regeneration level, long press for one-pedal driving) and the Charge / V2L button. See [Energy card](#energy-card).
- **Map card**: OpenStreetMap map following the car, with speed, heading and altitude. See [Map card](#map-card).
- **Weather card**: Open-Meteo forecast for the car's position, hourly or daily (swipe). When a refresh fails (no data connection) the last forecast stays for up to 3 hours.
- **Connection icons**: on the weather card, Bluetooth, Wi-Fi, hotspot, mobile network bars and USB, as on the stock banner (the bars need a readable T-box, so not on old firmwares). Touch them for the details and links to the Bluetooth and Wi-Fi settings. On by default, in Settings › Home; with the Simple layout or with the option off, the Wi-Fi badge stays on the bar.
- **Media card**: see [Media player](#media-player).
- **Bottom-right widget**: trip energy, live power or phone favourites. See [Bottom-right widget](#bottom-right-widget).

**Heated seats and steering wheel**: on equipped cars, a row of buttons sits above the temperature (driver seat, steering wheel, passenger seat). Settings › Vehicle › Heated seats and steering wheel: Auto (detected), Yes or No.

![Heated seats and steering wheel](docs/screenshots/home_heated_seats.png?v=25)

## Media player

The media card follows the car's active audio source (DAB / FM radio, Bluetooth, USB, Android Auto, CarPlay) and other apps playing music on the head unit. Swipe it up / down for the next source, left / right for the next / previous track (like the steering wheel buttons), tap the source name (« Radio DAB ▾ ») for the source menu, tap the picture to open the source's app, long press play / pause for the [Sound](#sound) page.

The player (Full, Map and Simple layouts) works like the Android Auto player: the album art or station image covers the card (black bars above and below are cropped as long as the image still fits the card's width, and its left edge colour extends it with a fade), title and artist over it, play button and progress bar in the image's main colour. Without an image, a white or black background following the theme.

| Bluetooth | Android Auto |
|---|---|
| ![Bluetooth player](docs/screenshots/home_theme_charcoal_orange.png?v=25) | ![Android Auto player](docs/screenshots/home_light.png?v=25) |

**Sources**: the source menu only lists the sources available right now: Bluetooth, USB and Android Auto / CarPlay appear when a phone or a USB key is connected, and other apps playing music (for example [DiAuto MG4+](https://github.com/Skittle6938/DiAuto-MG4) or SimpMusic) are offered too. Switching to an app sends it play, leaving it pauses it, and when an app starts playing the other apps are paused. DAB or FM is chosen in the radio app, as on the stock home screen.

**Open Android Auto from anywhere**: with a phone connected, slide two fingers down from the top of the screen, on the home screen, a page or the settings. Without a phone, nothing happens. Moving the map with two fingers does not trigger it, as both fingers must start at the top.

**Cut the phone connection**: double tap the Android Auto button (dock or vertical bar) to close the Android Auto / CarPlay session and keep it off; the icon turns red, even after a restart. The phone stays connected over Bluetooth for calls and music, and Bluetooth is offered in the sources (it is hidden while Android Auto, CarPlay or DiAuto is running). Double tap again: Android Auto comes back by itself, with the phone still plugged in. It works the same with [DiAuto MG4+](https://github.com/Skittle6938/DiAuto-MG4) chosen for the button (long press › Other app): its session is closed and it reconnects wirelessly on the next double tap. Music that was playing carries on over Bluetooth after a cut.

**Audio source at startup** (Settings › Home › Audio source): *Automatic* (default) remembers the sources you pick and goes back by itself to the last one still available, as soon as it returns (phone reconnected, USB key plugged in…); a source that disappears is replaced after 1 s. You can also leave it to the car (*Same as the car*) or always start on Radio, Bluetooth, USB or CarPlay / Android Auto. CarPlay is never picked automatically. *Playback at startup*: as when the car was switched off (default), always paused or always playing.

**Steering wheel**: left / right change track on the source shown, apps included. The car does not know the apps (DiAuto, SimpMusic…) and still hands these buttons to its last own source, often the radio, which briefly takes the sound: MG4Dash puts the radio back on its station and returns to the app at once. While the wheel buttons control the driver's display or the quick climate window, MG4Dash ignores them.

## Energy card

By default the energy card shows the battery, the range bar, the average consumption, the drive mode and KERS. The **range bar** has one colour following the level (green, orange below 30 %, red below 15 %); the % at the start of the trip is marked on it, with the part used since then in a lighter shade.

Settings › Home › *Live power in the energy card* replaces the drive mode and KERS with a live power bar, kW and the average consumption; the charge / V2L line only shows when plugged in (the trip start marker then hides so the range stays aligned with the %). *Voltage and current under the power bar* (off by default) adds the battery voltage with its trip minimum and the current with its trip maximum.

A tap on the right half of the card (the consumption or the range) changes the consumption, and the range above the battery bar follows it. A small logo in front of both says which one is shown, and its name shows for 2 s after each tap:

| Logo | Consumption | Range |
|---|---|---|
| lightning bolt | since the last charge | charge level × estimated battery health × new usable capacity (Vehicle page, your own value included) ÷ consumption |
| route | current trip (as before) | same |
| live | last km | same, it moves a lot from one km to the next |
| car | the car's recent consumption (SAIC) | the car's own range |

A consumption not known yet (a trip under 2 km, for example) is skipped, and while it is unknown the range is the car's, with the car logo. The choice is kept. The Energy page opens from its button on the bar, not from the cards, to avoid opening it by mistake.

![A tap on the consumption of the power card: its logo before it and before the range, its name for 2 s](docs/screenshots/home_energy_card_power_sources.png?v=25)

| Live power, plugged in | Live power with voltage and current, driving |
|---|---|
| ![Live power in the energy card](docs/screenshots/home_energy_card_power.png?v=25) | ![Live power with voltage and current](docs/screenshots/home_energy_card_power_driving.png?v=25) |

## Bottom-right widget

Swipe or tap it to switch between its pages:

- **Trip energy**: one bar and the share of driving, climate control and accessories since start-up (details on the Energy page).
- **Live power**: kW drawn from or recovered into the battery every second, on a two-way bar from zero with a 10 s peak marker, the trip average, voltage and current. It hides a **full-throttle special**: floor it and lightning or flames fill the card, then the peak reached shows (Settings › Look › Live power). This page leaves the widget when *Live power in the energy card* is on.
- **Phone**: favourite contacts, 3 by default (tap to call, long press to change one or to pick 2, 3, 4, 6 or 8 shortcuts, on one or two rows; contacts already chosen are kept).

| | |
|---|---|
| ![Live power](docs/screenshots/home_widget_power.png?v=25) | ![Phone favourites](docs/screenshots/home_widget_phone.png?v=25) |

## Map card

- **Map**: follows the car, north up or heading up (tap the recenter button again), default zoom 14 to 18 and colours (auto, light, dark) in Settings › Home. Only the recenter button is shown: two fingers zoom, a double tap goes full screen and back. Without data connection the map stays as long as the area is in the tile cache, then the card switches to consumption (can be turned off).
- **Consumption**: a swipe switches map → consumption → shortcuts. Period menu: since the last charge, current trip (one point per km, one every 2 minutes when stopped; since the last charge and the last 12 months are placed by date and smoothed, from the energy measured by MG4Dash) or last 12 months (one point per trip). The dashed line is the average: energy out of the battery minus regeneration, over the distance. Its value is on the chart's title line, in blue, with the consumption of the last km in orange (useful on a long climb); on the current trip, its duration shows in the top right corner. **Regen** shows the energy recovered and the part of the distance it powered: recovered energy ÷ energy out of the battery × distance. With 30.7 kWh out, 12.1 kWh recovered and 149 km, that is 39 % of the energy, so about 59 km of the 149 km. These km are already part of the distance, they don't add to the range.
- **Shortcuts**: 3 rows of apps or Home Assistant actions (scripts, automations, scenes, gate, garage door…). Tap an empty slot or long press a slot to change it; the Home Assistant address and token are set in Settings › Connections. A Home Assistant shortcut shows its live state under its name (a garage door drawn open and in orange when open, blue when closed, grey when unavailable): a cover, lock, switch or light shows its own state, and *Displayed state* in the shortcut's menu picks another entity, such as the door sensor of a garage opened by a script, or none.
- **Web app** (off by default): a web page of your choice in the card, for example a Home Assistant dashboard. Turn it on and enter its address in the edit mode (below). At rest, the page is handled like the other cards: a one-finger swipe, horizontal or vertical, changes card, a double tap goes full screen and back, and the page sees none of it. Touch it once to wake it: its outline lights up and it takes the touches as in a browser (one finger scrolls, two fingers zoom, a double tap stays the page's, such as YouTube's 10 s jump), until it is left alone for 10 s (1 to 120 s, with the arrows of *Page awake* in the edit mode below). On the car, the page is shown by *MG4Dash Web*, a small separate app embedded in MG4Dash, which installs it by itself the first time the card is shown: Android refuses web pages to an app with the system rights, which MG4Dash has. When another card takes its place, the page goes on (its sound, live values) but no longer draws, nor decodes the image of its videos. The page's sound is a player like the others: the media card shows it (title, picture) and the steering wheel buttons play, pause and skip it (the page's next video on YouTube). On the car, videos are decoded by the processor, in VP9 and at most 360p in the card (480p in full screen): the car's video chip gives no picture to web pages. Sites that only offer H.264 videos play their sound without picture.
- **Full screen**: the button below the zoom enlarges the map over the whole screen; any bar button brings it back.

### Choosing and moving the cards

Pinch the home screen with two fingers, as on Samsung or Pixel home screens, or use Settings › Home › *Edit the cards*. Each place of the home screen shows over itself what it can hold, and the home screen follows at once:

- A **medium place** (the top row of the Full layout, the bottom row of the Map layout) shows the climate, the media, or cards in turn: pick *Climate*, *Media* or *Cards*. The climate and the media are in one place each, and never alternate with cards; picked in another place, they swap with what was there.
- In a place of **cards**, touch a card to show it there or to remove it. A card is in one place at a time: taken from another place, it leaves it (a small drawing of the places shows where it is). Each place keeps at least one card. A swipe, or the button at the top right over a web page or an app, goes to the next card of the place.
- A **smaller place** gets a compact version: the consumption without its totals line and legend (as in the Full layout), the shortcuts on one row. The climate and the media don't fit there.
- **Default** puts the home screen of the layout back as it was at first, after a confirmation. **Done** closes the edit mode.

The Full and Map layouts each keep their own choice; the Simple layout has no cards to choose. The pinch is left to the map for its zoom, and to a web page or an app shown in a card.

| Layout | Default places, from left to right (· between places) |
|---|---|
| Full | top: climate · map, consumption, shortcuts · media; bottom: energy · weather by hour, by day · trip, power, phone |
| Map | top: weather by hour, by day · energy, trip, power, phone; right: map, consumption, shortcuts; bottom: climate · media |

![Edit mode of the Full layout](docs/screenshots/edit_cards_full.png?v=25)

In a place with the map, the edit mode also sets the app shown instead of the map; with the web app, its address. The Power card is left out when *Live power in the energy card* is on.

| Consumption | Shortcuts |
|---|---|
| ![Consumption](docs/screenshots/home_map_layout_consumption.png?v=25) | ![Shortcuts](docs/screenshots/home_map_layout_shortcuts.png?v=25) |

**Another app in the map card**: pinch the home screen, then touch *Map* to show another installed app inside the card instead of the map, for example an EV route planner such as ABRP or a navigation app such as Waze. It runs live and rests and wakes like the web app: at rest, swipe it for the next card or touch it twice to enlarge it (it stays full screen through day / night changes until you press Home or touch it twice again); touch it once to use it. Pick "OpenStreetMap map" in the same list to go back. *The apps below are drawn for the example.*

| | |
|---|---|
| ![EV route planner in the map card](docs/screenshots/home_map_app_planner.png?v=25) | ![Navigation app in the map card](docs/screenshots/home_map_app_guidance.png?v=25) |

**Turn-by-turn**: with [DiAuto MG4+](https://github.com/Skittle6938/DiAuto-MG4) (or another Headunit Revived-based Android Auto app that broadcasts `com.andrerinas.headunitrevived.NAVIGATION_UPDATE`), the next manoeuvre is shown over the map: arrow, distance, street, time and distance left, arrival time. The stock Android Auto app does not receive this information.

![Navigation banner](docs/screenshots/home_map_layout_nav.png?v=25)

## Other home layouts

Settings › Home › Home layout: **Full** (default), **Map** or **Simple**. *Menu bar*: vertical on the driver's side (default), vertical left, vertical right or horizontal at the bottom.

| Map layout | Simple layout |
|---|---|
| ![Map layout](docs/screenshots/home_map_layout.png?v=25) | ![Simple layout](docs/screenshots/home_simple.png?v=25) |
| Weather with date and time, climate, a stack (energy, trip energy, live power, phone; swipe to change, back to the battery when plugged in) above the media, and the map over the whole right column. | Fewer buttons and bigger text: climate, battery and media panels, two shortcuts and today's weather. Tap the climate panel to open its page; the Energy page opens from the bar. |
| **Bottom dock** | **Right-hand drive** |
| ![Bottom dock](docs/screenshots/home_bottom_bar.png?v=25) | ![Vertical bar on the right](docs/screenshots/home_right_hand.png?v=25) |
| *Horizontal at the bottom*: dock with clock and date (long press the clock: clock or Wi-Fi settings). | On right-hand drive cars (detected), the vertical bar goes on the right, next to the driver; it can also be forced to either side. |

## Themes and day / night

Settings › Look: **Auto** (default, follows the car's lights), **Dark** or **Light**; 5 backgrounds and 5 button colours, applied to every page and to [Radio Dash](https://github.com/Skittle6938/MG4-Radio-Dash). Battery green, heating orange, cooling cyan and alert red keep their meaning.

- *Night after sunset* (on by default, Auto only): dark from sunset to sunrise at the car's position, even with the side lights off. Computed on the head unit, no network needed; today's times are shown under the option.
- *Smooth light / dark switch* (on by default): the switch waits for 5 s of stable lights (no flicker under a bridge) and fades over 2 s. Off: it switches as soon as the lights change.
- *Home panel outline*: fading downwards (dashboard style, default) or full.

| Light mode | Charcoal background, orange buttons |
|---|---|
| ![Light mode](docs/screenshots/home_light.png?v=25) | ![Charcoal and orange](docs/screenshots/home_theme_charcoal_orange.png?v=25) |

## Pages

### Climate
![Climate](docs/screenshots/climate.png?v=25)
Temperature, fan speed, combinable air directions, AUTO, A/C, ECO, recirculation and front / rear defrost. On old firmwares (SWI68, SWI133, SWI165), MG4Dash comes back in front when the stock launcher opens its own climate screen (Settings › Vehicle, on by default).

### Keep climate on
A camp mode, off by default (Settings › Vehicle › *Keep climate on*), then a long press on MG4Dash's climate power button. It keeps the car on with the climate running while you are away or sleeping in the car: **now** (for a set time or without limit), or **at a set time** in one or more runs with a pause between them (the A/C compressor is switched on at each run).

- After a countdown (20 s by default) **MG4Dash locks the doors itself**: get out and take the key with you. Do not lock with the key fob, which switches the whole car off and ends the mode.
- A large status screen stays readable from outside: outside temperature, climate state, time left, runs and battery. Its brightness drops to the minimum once locked (a tap brings it back for 10 s), and an option turns it fully off.
- The mode ends after the last run, at the end of the set time or below 20 % battery, and stops as soon as the doors are unlocked or the car drives off. Leave the light stalk on Auto.
- **Never leave a child or an animal alone in the car**: the climate can stop at any time (battery, fault, car switching off).

| | | |
|---|---|---|
| ![At a set time](docs/screenshots/climhold_menu_at_en.png?v=25) | ![Countdown](docs/screenshots/climhold_countdown_en.png?v=25) | ![Status screen](docs/screenshots/climhold_screen_en.png?v=25) |

### Energy
![Energy](docs/screenshots/energy.png?v=25)
Charging (start / stop, charge limit where the car applies it, AC current, time left, input voltage and current, reason when the car stops charging, cable lock), scheduled charging, V2L (adapter state, stop threshold, start / stop), battery heating while driving, and consumption by period: a ring and the share of driving (net of regeneration), climate, battery heating and accessories, energy recovered beside the total, and a stacked curve of these items over time. Points recorded before this version show in grey. In miles, the stacked curve is in kWh/100 mi.

### Vehicle
![Vehicle](docs/screenshots/vehicle.png?v=25)
Drive mode (with Eco, Snow and Custom where available), regeneration (level 6 is one-pedal driving), driver assistance, tyre pressures and temperatures, vehicle status, a shortcut to MG4 Control, and the **battery health estimate**: while driving, MG4Dash measures the energy taken from the battery and divides it by the drop in battery %; from a 15 % drop (added up over several trips) it gives a dated measurement of the usable capacity, averaged over 30 days and compared with a new battery of your model. The default references are 49, 61 and 72.5 kWh for the 51, 64 and 77 kWh models: they are estimates of the energy from 100 % to 0 % displayed, not the battery's total usable capacity, since it also keeps a buffer. You can enter your own in the settings. This is an estimate, not an official measurement.

### Sound
![Sound](docs/screenshots/sound.png?v=25)
Vehicle › *Sound*, or a long press on play / pause. Drag the dot on the MG4 seen from above to set the balance and fader (double tap or *Centre* to reset). 5-band equaliser with the car's presets (Classic, Jazz, Pop, Rock, Vocal) or your own curve, *Loudness* and *Flat*. A live spectrum of what is playing is shown behind the sliders for Bluetooth, USB and apps; the radio's sound does not go through Android, so the spectrum stays flat on the radio. These are the car's own settings, the same as the stock sound menu. On SWI68 and the other old firmwares the page uses the car's own sound service: written from the firmware, tests welcome.

### Phone
![Phone](docs/screenshots/phone.png?v=25)
Recent calls, contacts with search and a keypad, from the phone connected over Bluetooth. Calls go through the hands-free account, like the stock phone app, whose in-call screen is kept.

### Startup
![Startup animation](docs/screenshots/splash.png?v=25)
While the head unit starts, a short MG4Dash animation replaces Android's "Phone is starting" screen; the home screen opens as soon as the system is ready.

## Settings

Five tabs along the top; the last one used opens next time. Each on / off option is a single button that lights up when it is on. Dialogs and messages follow the MG4Dash theme.

**Keyboard**: text fields in MG4Dash use its own keyboard with large keys, over the two cards on the driver's side on the phone page, and on two thirds of the screen elsewhere (AZERTY in French, QWERTZ in German, QWERTY otherwise). Long press a letter for its accents, long press shift to keep it on, long press the delete key to clear the field. On the car, the fields of the web app card's page use it too: it opens over home, beside the card when it can, and OK is the Enter key; a touch outside the keyboard and the card closes it. Settings › Look › *MG4Dash keyboard* goes back to Android's keyboard. Other apps keep their keyboard.

| Home | Look |
|---|---|
| ![Settings, Home](docs/screenshots/settings_en_tab0.png?v=25) | ![Settings, Look](docs/screenshots/settings_en_tab1.png?v=25) |
| **Vehicle** | **System** |
| ![Settings, Vehicle](docs/screenshots/settings_en_tab2.png?v=25) | ![Settings, System](docs/screenshots/settings_en_tab4.png?v=25) |

| Tab | Options |
|---|---|
| Home | home layout (Full, Simple, Map), places and cards of the home screen (edit mode), audio source and playback at startup, menu bar (bottom or vertical, side), wide side bar, connection icons, full screen (hides the SAIC side bar and banner), live power in the energy card, voltage and current under the power bar, map stack back to the battery when plugged in, map zoom and colours, switch to the energy widget when offline |
| Look | light / dark / auto, night after sunset, smooth light / dark switch, clock 24 h or 12 h (no AM / PM), panel outline, background, button colour, full-throttle special |
| Vehicle | units, tyre pressure unit, heated seats and steering wheel, Keep climate on, keep MG4Dash in front of the SAIC climate screen (old firmwares), startup sound (audio file from a USB stick or internal storage, 30 s and 20 MB at most, with its own volume), battery capacity when new, current capacity and health, battery model, MG4 XPower |
| Connections | weather (fallback city or current position), Home Assistant (address, token, test) |
| System | replace the SAIC home, maintenance mode, updates (include betas, Radio Dash), diagnostics (compatibility test, log, energy data recorder: off by default, saved every 5 minutes to limit memory wear), gesture tour |

## Updates

Settings › System shows one line for MG4Dash and one for Radio Dash, with the installed version and the latest one found. The button on the right says *Check*, or *Install* when a newer version is known; it checks GitHub again, shows the release notes and installs (internet connection needed). *Check automatically* (on by default) looks for new versions once a week, or every day with *Include beta versions* on; the betas are numbered after the next release (1.7.2-beta0, 1.7.2-beta1…). When a new official release is found, MG4Dash also offers it on the home screen: *Update*, *Later* or *Don't notify me again for this release*.

**Radio Dash**: the same page installs the latest [Radio Dash](https://github.com/Skittle6938/MG4-Radio-Dash) (the radio redesigned in the MG4Dash style) on any firmware whose stock radio it can replace, and says so when it can't (a newer stock radio, or one signed with another key). Tested on SWI69 and SWI68 R67. Restart the head unit afterwards by holding Home about 20 s. When Radio Dash is installed, DAB and FM are two separate sources on the music card (Settings › Home, on by default).

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

**Help support your firmware**: open Settings › Compatibility › Run the test, then scan the QR code with your phone. It opens a pre-filled [compatibility issue](https://github.com/Skittle6938/MG4Dash/issues/new?template=compatibility.md) with a short report (versions, which services answer, a few car readings: no position, VIN or account). Nothing is sent automatically. *Open in browser* shows the same page on the head unit, in MG4Dash Web (the car's browser refuses pages on some head units, [#95](https://github.com/Skittle6938/MG4Dash/issues/95)).

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
  - `system/Projection.kt`: Android Auto / CarPlay session read from (and closed through) the AllGo remote UI service
  - `system/ConnectionToggle.kt`: phone connection cut / restored from the Android Auto button
  - `system/BtAudio.kt`: Bluetooth music of the phone asked again when Android Auto stops
  - `energy/EnergyLogger.kt`: read-only CSV recorder of battery power, consumption counters and equipment states
  - `media/MediaHub.kt`: active player and audio sources (the car's and other apps'), automatic source, steering wheel for apps
  - `map/NavRepo.kt`: turn-by-turn data broadcast by DiAuto / Headunit Revived
  - `shortcuts/Shortcuts.kt`: shortcut grid and Home Assistant REST calls
  - `shortcuts/HaLive.kt`: live state of the Home Assistant shortcuts, read while they are on screen
  - `ui/WebCard.kt`, `system/WebViewer.kt`: web app card of the map widget, and MG4Dash Web, which shows its page and the compatibility report on the car
  - `weather/WeatherRepo.kt`: Open-Meteo weather
  - `system/HomeSwitcher.kt`: maintenance mode (gives the home screen back to the SAIC launcher)
  - `LauncherApp.kt`: crash guard, after 3 crashes in 3 minutes the SAIC launcher becomes the home screen again
- `app/src/car/`: car manifest (`sharedUserId=android.uid.system`)
- `app/src/emulator/`: build without the system uid, with simulated data
- `rescue/`: MG4Dash Rescue, the safety net (system uid), embedded as `app/src/car/assets/rescue.apk`
- `web/`: MG4Dash Web, the page of the web app card and the full screen compatibility report (own uid, Android refuses WebView to the system uid), embedded as `app/src/car/assets/web.apk`
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

`rescue/` and `web/` are built apart (`.\gradlew.bat :web:assembleRelease`), signed with the platform key like `sign_platform.ps1` does, and their APK is committed in `app/src/car/assets/`; raise the version in `system/Rescue.kt` or `system/WebViewer.kt` with the one of the module, so that MG4Dash installs the new one.

## Install on the car

`adb install` is refused on this head unit; push the APK and install it as a system app update:

```
adb push MG4Dash-<version>.apk /data/local/tmp/x.apk
adb shell pm install -r -f /data/local/tmp/x.apk
adb shell rm /data/local/tmp/x.apk
```

1. Try it without changing the home screen: `adb shell am start -n app.mg4dash/.MainActivity`
2. Make it the home screen: see [Home screen: replace and go back](#home-screen-replace-and-go-back). This only disables the SAIC home activity, whose HOME priority of 2 cannot be beaten otherwise.

The SAIC launcher package (`com.saicmotor.launcher`) is never disabled: its service handles the camera, audio sources and some SystemUI commands.

## Home screen: replace and go back

![Settings › System › Home screen](docs/screenshots/settings_system.png?v=25)

1. Tap the **gear** (Settings) in the side bar, then the **System** tab.
2. Under **HOME SCREEN**, tap **Replace SAIC home** and confirm. MG4Dash now opens at startup and with the car's Home button.
3. **Back to the original** at any time (dealer visit, a problem, before uninstalling): same place, tap **Maintenance mode** and confirm. The SAIC home screen is the home screen again, exactly as before (a phone connection cut with the Android Auto button is restored too). Tap **Replace SAIC home** to come back to MG4Dash.

**Nothing is uninstalled or deleted.** *Replace SAIC home* only switches off the SAIC home screen *as the home screen*: the SAIC launcher app stays installed, and its service keeps running the camera, audio sources and system bar as usual. The rest of the car is not touched. *Maintenance mode* switches the SAIC home screen back on in one tap. And if MG4Dash ever crashed 3 times within 3 minutes, the SAIC home screen would come back by itself.

The gesture tour shows the same two buttons.

## Going back

- Maintenance mode: see [Home screen: replace and go back](#home-screen-replace-and-go-back).
- If MG4Dash no longer starts after an update ("ClassNotFoundException" or "oat_file ... Check failed" in logcat), stop it, recompile it while it is stopped, then make it the home screen again:
  `adb shell am force-stop app.mg4dash`, `adb shell cmd package compile -m speed -f app.mg4dash`, `adb shell am force-stop app.mg4dash`, then `adb shell cmd package set-home-activity --user 0 app.mg4dash/.MainActivity`.
  Compiling while MG4Dash runs (it relaunches itself right after an install) corrupts its compiled code: always force-stop first.
- Note: `adb shell pm enable com.saicmotor.launcher/.view.MainActivity` is refused on this head unit (the shell may not change component state); only MG4Dash's Maintenance mode can re-enable the SAIC home. Until then, Android offers a launcher chooser.
- Uninstall: `adb uninstall app.mg4dash`

## Gestures

A short tour of the main gestures opens after a first install (*Skip* closes it for good); see it again in Settings › System › *Show the gesture tour*.

- "Settings" in the dock or the vertical bar: tap for the launcher settings, long press for the car settings
- Long press "Home" in the dock: list of installed apps
- Long press the clock in the dock: clock or Wi-Fi settings button
- Vertical bar: double tap Energy turns the screen off, long press Energy switches the car off (never while moving); double tap Vehicle locks / unlocks the doors, long press Vehicle turns ESC off / on
- Long press "Android Auto" in the dock or the vertical bar: Android Auto, CarPlay or another app; double tap: cut / restore the phone connection (see [Media player](#media-player))
- Tap or swipe the bottom-right widget (or the map layout stack): next page; long press a phone favourite to change it
- Swipe the media card up / down: next audio source; left / right: next / previous track; tap its source name: source menu
- Long press the set temperature: temperature calibration; long press fan minus: lowest fan speed; long press A/C: intermittent A/C
- Long press play / pause: Sound page
- Steering wheel * button: 360 camera on / off
- Swipe the map card: map → consumption → shortcuts → web app (the cards on); move and zoom the map with two fingers (one finger in full screen); double tap the map for full screen and back; over the web app or an app at rest, the same, and a touch wakes it
- Pinch the home screen with two fingers: choose the cards of each widget (see [Choosing the cards](#choosing-the-cards))
- Long press the connection icons: choose which ones show, including USB-A and USB-C, which are off by default
- Two fingers sliding down from the top of the screen: open Android Auto when a phone is connected
- Swipe the weather card: hourly ↔ daily forecast
- Tap a segment of the KERS gauge: regeneration level; long press: one-pedal driving
- Long press a shortcut: edit it; with an app shown in the map card, its button at the top right: map → consumption → shortcuts
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

## Appendix: MG4 battery capacities

The most likely values for a new battery, measured from 100 % until the car stops, at the battery (without charging losses):

- **51 kWh LFP: about 50 kWh**
  - Between the 49.5 kWh measured in tests and the 50.8 kWh reported by owners.
  - From 100 % to 0 % shown: about 49 kWh.
- **64 kWh NMC: about 62.5 kWh**
  - Between the official 61.7 kWh and the 63.2 kWh measured by Green NCAP.
  - From 100 % to 0 % shown: about 61 kWh.
- **77 kWh NMC: about 74.5 kWh**
  - The official 74.4 kWh are probably reached or slightly exceeded, as on the 64 kWh.
  - From 100 % to 0 % shown: about 72.5 kWh.

For range, rely on the "0 % shown" values. These are the ones MG4Dash uses by default.

## Licence

MG4Dash is free software under the [GNU General Public License v3](COPYING) or later. This applies to every published version, including the releases before 0.10.0. Parts of the firmware detection and of the SWI68 / SWI165 / SWI133 climate code are adapted from [MG4Control](https://github.com/SliDeeN/MG4Control) (GPL v3; earlier MG4Control code under the MIT licence, notice kept in [licenses/MG4Control-MIT.txt](licenses/MG4Control-MIT.txt)).

Personal project, not affiliated with SAIC / MG. Use at your own risk.
