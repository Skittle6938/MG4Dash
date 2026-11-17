package app.mg4dash

/**
 * JUnit category of the tests that render screens to images (build/screenshots): slow, run apart, only when the
 * display changes (`./gradlew testEmulatorDebugUnitTest -Pscreenshots`; `-PallTests` for everything). The default
 * run leaves them out, the display's logic without images stays in it.
 */
interface Screenshot
