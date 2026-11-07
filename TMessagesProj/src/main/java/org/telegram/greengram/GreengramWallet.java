package org.telegram.greengram;

import android.content.Context;

import org.telegram.messenger.browser.Browser;
import org.telegram.ui.LaunchActivity;

// Greengram has no TON blockchain: the wallet is the @wallet bot's main app,
// the same one the web client opens. Everything that would show the built-in
// TON wallet comes here instead.
public final class GreengramWallet {

    public static final String BOT = "wallet";

    private GreengramWallet() {
    }

    public static void open(Context context) {
        if (context == null) {
            context = LaunchActivity.instance;
        }
        if (context == null) {
            return;
        }
        Browser.openUrl(context, "https://t.me/" + BOT + "?startapp");
    }
}
