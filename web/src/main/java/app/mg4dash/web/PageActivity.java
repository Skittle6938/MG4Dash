package app.mg4dash.web;

/**
 * Full screen page with a close button, on the main screen, in its own task (it does not replace the
 * card's page): the compatibility report MG4Dash opens on GitHub (#95), the car's browser refusing
 * pages on some head units ("Due to safety reason, this page is forbidden on the head unit").
 */
public class PageActivity extends WebActivity {
    @Override
    boolean inCardOnly() { return false; }
}
