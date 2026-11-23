# Contributing to MG4Dash

Thanks for wanting to help! MG4Dash runs on the head unit of a car, so changes have to stay safe and simple while
driving. Before you start something big, open an issue or a discussion so we can agree on it first.

## The basics

- **Build and tests.** Use the emulator flavour on a PC:
  `./gradlew testEmulatorDebugUnitTest`
  To get the screenshot tests too, add `-Pscreenshots` (only those) or `-PallTests` (everything). The images are
  written to `app/build/screenshots/`.
- **Texts.** Every text the driver sees goes in `i18n/strings.psv` (key, then English, French, German, Spanish,
  Italian, Dutch, Turkish, Czech), then `python i18n/gen.py`. Never write a text directly in the code. CI runs
  `gen.py --check`.
- **Code comments** are in English.
- **Settings.** Every setting key must be either personal (it follows the profile) or shared, in
  `system/Profiles.kt`; a test checks this. The settings backup takes every key unless it is listed as local in
  `system/SettingsBackup.kt`.
- **Pull requests.** Keep one subject per PR. For any visible change, add before/after screenshots in both light and
  dark themes.

## Adding a home card

A *card* is a widget placed in one of the home screen's places. A card follows the **card contract**
(`app/src/main/java/app/mg4dash/cards/HomeCard.kt`), so the home screen handles it like every other card.

### Steps

1. **Copy the template.** Copy `app/src/testEmulator/java/app/mg4dash/cards/TemplateCard.kt` into
   `app/src/main/java/app/mg4dash/cards/YourCard.kt`. It shows every part of a card, in order.
2. **Declare it.** Add one entry to `HomeSlots.Card` (`ui/HomeSlots.kt`): an id, its name (a string), an icon,
   `big = true` if it can fill a place two columns wide, and `manual = true` (a new card only appears where the driver
   puts it).
3. **Register it.** Add one line to `HomeCards.factories` (`cards/HomeCards.kt`).
4. **Add its texts** to `i18n/strings.psv`, then run `python i18n/gen.py`.
5. **Check it.** Run
   `./gradlew testEmulatorDebugUnitTest -Pscreenshots --tests app.mg4dash.cards.CardGalleryShots`
   and look at `app/build/screenshots/cards/`: your card appears in every size it accepts, in dark and light. Put
   these pictures in your PR.

That's all. You don't need to touch `MainActivity` or the editor.

### The contract

| Part | What the card does |
|---|---|
| **Identity** | `card`: its entry in `HomeSlots.Card`. `view`: its view, which fills its place. The home screen adds the frame (rounded corners, outline). |
| **Size** | `place(p)`: it adapts to the shape of its place. `p.small` is a small place, `p.medium` a medium one, `p.tall` a full-height one, `p.wide` one two columns wide, and `p.big` a place in the larger display mode (Grand affichage), where text is already larger. A card never measures the screen itself. |
| **Life** | `shown(true)`: show the data at once, then follow it. `shown(false)`: stop everything (collectors, timers, network). It may be called twice in a row. |
| **Options** | `options(popup)`: add its settings to the standard options window, which a long press on the card opens. Return `null` if the card has no options. |
| **Settings** | `CardPrefs(context, card)`: keys `card_<id>_<name>`. They follow the profile and go in the settings backup by themselves. |
| **No data** | `CardKit.empty(context)` shows "No data yet" instead of an empty card. |

### Look and feel

- **Colours.** Only the theme's colours, through `CardKit` or `Themes.color(context, R.color.…)`:
  - `text_value` for values and the main text;
  - `text_sub` for labels;
  - `text_title` for the card's title;
  - `panel_border` for dividers;
  - `button_idle` for button backgrounds;
  - `accent_action` for the accent.

  Never use a fixed colour: the light theme, the dark theme and the accent the driver chose then work by themselves.
- **Building blocks.** Use `CardKit` for margins (20 dp / 16 dp), the title, text lines (17, 44, 22 and 18 sp),
  dividers and buttons, so all cards look alike.
- **Touch.** A touch can open the related page. A long press opens the options. Swipes belong to the place: they change
  card, so make the whole card clickable (`isClickable = true`) so that a swipe starting on it still changes card.
- **Safety.** Nothing that needs reading or typing while driving, and no animation that grabs the eye.
- **Data.** Read car data from `CarBridge.state`, and media from `MediaHub`. Use mobile data sparingly: only while
  shown, and keep it small.

### Existing cards

The **last trips** and **driving** cards already follow the contract (`ui/TripsCard.kt`, `ui/DrivingCard.kt`). The
others are still built by the home screen itself and will be moved to the contract one by one.
