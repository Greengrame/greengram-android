package org.telegram.greengram;

import android.net.Uri;
import android.text.TextUtils;

import java.util.regex.Pattern;

// The instance's web links stand in for t.me ones: Web A shares <instance>/nft/slug,
// /u/name, /+invite, and the server's own me_url_prefix is <instance>/u/. Turned
// into t.me links here, the usual handling opens them in the app instead of the
// browser.
public final class GreengramLinks {

    private static final Pattern PATH = Pattern.compile("^/(?:u/|nft/|\\+|c/|addstickers/|addemoji/|addlist/|boost/|auction/)[^/].*");

    private GreengramLinks() {
    }

    public static Uri toTMe(Uri uri, int account) {
        if (uri == null || uri.getHost() == null) {
            return uri;
        }
        final String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            return uri;
        }
        final GreengramBackend.Profile profile = GreengramBackend.load(account);
        if (profile == null || TextUtils.isEmpty(profile.instance)) {
            return uri;
        }
        final String host = Uri.parse(profile.instance).getHost();
        if (host == null || !host.equalsIgnoreCase(uri.getHost())) {
            return uri;
        }
        String path = uri.getEncodedPath();
        if (path == null || !PATH.matcher(path).matches()) {
            return uri;
        }
        if (path.startsWith("/u/")) {
            path = path.substring(2);
        }
        return uri.buildUpon().scheme("https").encodedAuthority("t.me").encodedPath(path).build();
    }

    public static String toTMe(String url, int account) {
        if (url == null) {
            return null;
        }
        final Uri uri = Uri.parse(url);
        final Uri out = toTMe(uri, account);
        return out == uri ? url : out.toString();
    }
}
