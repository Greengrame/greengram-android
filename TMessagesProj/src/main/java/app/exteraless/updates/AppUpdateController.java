package app.exteraless.updates;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Xml;

import androidx.core.content.FileProvider;

import com.google.gson.Gson;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

public final class AppUpdateController {
    private static final String BASE_URL = "https://tg.upd.dexx.moe";
    private static final String FALLBACK_BASE_URL = "https://tg-updates.rtstaller1.workers.dev";
    private static final String APK_FILE_NAME = "exteraless-update.apk";
    private static final String PREFS_NAME = "libretg_updates";
    private static final String KEY_LAST_VERSION_CODE = "lastVersionCode";
    private static final String KEY_INSTALL_ID = "installId";
    private static final long RECHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Gson GSON = new Gson();

    public static final int STATE_NONE = 0;
    public static final int STATE_AVAILABLE = 1;
    public static final int STATE_DOWNLOADING = 2;
    public static final int STATE_READY = 3;

    // Всё состояние ниже меняется только на UI-потоке. Оно живёт в памяти процесса:
    // скрытый баннер возвращается на следующем запуске вместе с шитом.
    private static boolean started;
    private static boolean checking;
    private static long lastCheckTime;
    private static int state = STATE_NONE;
    private static UpdateInfo update;
    private static boolean bannerHidden;
    private static int sheetShownVersionCode;
    private static boolean whatsNewShown;
    private static long downloadedBytes;
    private static long totalBytes;
    private static File readyFile;

    private AppUpdateController() {
    }

    public static void init(Context context) {
        if (started) {
            return;
        }
        started = true;
        checkWhatsNew();
        AndroidUtilities.runOnUIThread(AppUpdateController::checkNow, 4000L);
    }

    public static void onAppResumed() {
        if (started && lastCheckTime != 0 && state != STATE_DOWNLOADING
                && SystemClock.elapsedRealtime() - lastCheckTime >= RECHECK_INTERVAL_MS) {
            checkNow();
        }
    }

    public static void checkNow() {
        if (checking) {
            return;
        }
        checking = true;
        lastCheckTime = SystemClock.elapsedRealtime();
        EXECUTOR.execute(() -> {
            UpdateInfo info = null;
            try {
                info = loadManifest();
            } catch (Throwable e) {
                FileLog.e("Update check failed", e);
            }
            final UpdateInfo loaded = info;
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                onManifestLoaded(loaded);
            });
        });
    }

    private static void onManifestLoaded(UpdateInfo info) {
        boolean valid = info != null && info.versionCode > BuildConfig.VERSION_CODE
                && ApplicationLoader.getApplicationId().equals(info.packageName)
                && info.apkUrl != null && !info.apkUrl.isEmpty();
        if (state == STATE_DOWNLOADING || state == STATE_READY) {
            if (!valid || info.versionCode == update.versionCode) {
                return;
            }
            if (state == STATE_DOWNLOADING) {
                // Более новая версия подхватится следующей проверкой после окончания загрузки.
                return;
            }
            readyFile = null;
        }
        if (!valid) {
            // Например, раскатка поставлена на паузу: обновление больше не предлагается.
            if (state != STATE_NONE) {
                state = STATE_NONE;
                update = null;
                notifyStateChanged();
            }
            return;
        }
        update = info;
        state = STATE_AVAILABLE;
        if (info.versionCode != sheetShownVersionCode) {
            sheetShownVersionCode = info.versionCode;
            bannerHidden = false;
            // Новый шит закрыл бы «Что нового»: в этой сессии о свежем обновлении сообщает только баннер.
            if (!whatsNewShown) {
                runWithFragment(fragment -> AppUpdateBottomSheet.show(fragment, info), 0);
            }
        }
        notifyStateChanged();
    }

    public static int getState() {
        return state;
    }

    public static UpdateInfo getUpdate() {
        return update;
    }

    public static boolean isBannerVisible() {
        if (state == STATE_NONE || update == null) {
            return false;
        }
        return state != STATE_AVAILABLE || update.mandatory || !bannerHidden;
    }

    public static void hideBanner() {
        bannerHidden = true;
        notifyStateChanged();
    }

    public static int getDownloadPercent() {
        return totalBytes > 0 ? (int) Math.min(100, downloadedBytes * 100 / totalBytes) : 0;
    }

    public static long getDownloadedBytes() {
        return downloadedBytes;
    }

    public static long getTotalBytes() {
        return totalBytes;
    }

    // Случайный идентификатор установки для раскатки и статистики: не связан с аккаунтом и сбрасывается вместе с данными приложения.
    private static String getInstallId() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String installId = prefs.getString(KEY_INSTALL_ID, null);
        if (installId == null) {
            installId = java.util.UUID.randomUUID().toString();
            prefs.edit().putString(KEY_INSTALL_ID, installId).apply();
        }
        return installId;
    }

    private static void notifyStateChanged() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateBannerChanged);
    }

    // showDialog молча отказывает, пока идёт анимация перехода (например, сразу после запуска),
    // поэтому действие повторяется, пока не вернёт true.
    private static void runWithFragment(Predicate<BaseFragment> action, int attempt) {
        AndroidUtilities.runOnUIThread(() -> {
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            boolean done = fragment != null && fragment.getParentActivity() != null && action.test(fragment);
            if (!done && attempt < 10) {
                runWithFragment(action, attempt + 1);
            }
        }, attempt == 0 ? 0 : 1000L);
    }

    private static UpdateInfo loadManifest() throws Exception {
        String packageName = ApplicationLoader.getApplicationId();
        String query = "/update.json?package=" + URLEncoder.encode(packageName, "UTF-8");
        String json;
        try {
            json = httpGet(BASE_URL + query, "application/json", true);
        } catch (Throwable ignored) {
            // Запасной workers.dev-адрес на случай недоступности своего домена.
            json = httpGet(FALLBACK_BASE_URL + query, "application/json", true);
        }
        return json == null ? null : GSON.fromJson(json, UpdateInfo.class);
    }

    private static String httpGet(String url, String accept, boolean identify) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag());
        connection.setRequestProperty("X-LibreTG-Package", ApplicationLoader.getApplicationId());
        if (identify) {
            connection.setRequestProperty("X-LibreTG-Install", getInstallId());
            connection.setRequestProperty("X-LibreTG-Version", Integer.toString(BuildConfig.VERSION_CODE));
        }
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    static void startUpdate() {
        if (update == null) {
            return;
        }
        if (state == STATE_READY && readyFile != null && readyFile.exists()) {
            runWithFragment(fragment -> {
                install(fragment, readyFile);
                return true;
            }, 0);
            return;
        }
        if (state == STATE_DOWNLOADING) {
            return;
        }
        final UpdateInfo info = update;
        state = STATE_DOWNLOADING;
        downloadedBytes = 0;
        totalBytes = 0;
        notifyStateChanged();
        EXECUTOR.execute(() -> {
            File output = apkFile();
            File partial = new File(output.getParentFile(), APK_FILE_NAME + ".part");
            try {
                if (!isVerifiedApk(output, info)) {
                    download(info, partial);
                    if (hasChecksum(info) && !info.sha256.equalsIgnoreCase(sha256(partial))) {
                        throw new SecurityException("Update checksum mismatch");
                    }
                    if (output.exists()) {
                        output.delete();
                    }
                    if (!partial.renameTo(output)) {
                        throw new IllegalStateException("Cannot finalize update file");
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (update != info) {
                        return;
                    }
                    state = STATE_READY;
                    readyFile = output;
                    notifyStateChanged();
                    runWithFragment(fragment -> {
                        install(fragment, output);
                        return true;
                    }, 0);
                });
            } catch (Throwable e) {
                partial.delete();
                FileLog.e("Update download failed", e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (update != info) {
                        return;
                    }
                    state = STATE_AVAILABLE;
                    bannerHidden = false;
                    notifyStateChanged();
                    runWithFragment(fragment -> {
                        BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.ExteraUpdateDownloadFailed)).show();
                        return true;
                    }, 0);
                });
            }
        });
    }

    private static void download(UpdateInfo info, File partial) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(info.apkUrl).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(60000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("Update server returned HTTP " + connection.getResponseCode());
            }
            final long total = connection.getContentLengthLong();
            AndroidUtilities.runOnUIThread(() -> totalBytes = Math.max(total, 0));
            try (InputStream input = connection.getInputStream(); FileOutputStream outputStream = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int lastPercent = -1;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    outputStream.write(buffer, 0, count);
                    done += count;
                    int percent = total > 0 ? (int) (done * 100 / total) : -1;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        final long downloaded = done;
                        AndroidUtilities.runOnUIThread(() -> {
                            downloadedBytes = downloaded;
                            notifyStateChanged();
                        });
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static File apkFile() {
        return new File(ApplicationLoader.applicationContext.getCacheDir(), APK_FILE_NAME);
    }

    private static boolean hasChecksum(UpdateInfo info) {
        return info.sha256 != null && !info.sha256.isEmpty() && !info.sha256.startsWith("REPLACE_");
    }

    // Уже скачанный APK (например, после отменённой установки) переиспользуется,
    // только если его можно сверить по SHA-256 из манифеста.
    private static boolean isVerifiedApk(File file, UpdateInfo info) {
        try {
            return hasChecksum(info) && file.exists() && info.sha256.equalsIgnoreCase(sha256(file));
        } catch (Throwable e) {
            return false;
        }
    }

    private static void install(BaseFragment fragment, File file) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !fragment.getParentActivity().getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + fragment.getParentActivity().getPackageName()));
            fragment.getParentActivity().startActivity(settings);
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(fragment.getParentActivity(),
                    ApplicationLoader.getApplicationId() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            fragment.getParentActivity().startActivity(intent);
        } catch (Throwable e) {
            FileLog.e("Cannot open update installer", e);
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.ExteraUpdateInstallerFailed)).show();
        }
    }

    private static void checkWhatsNew() {
        Context context = ApplicationLoader.applicationContext;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int lastVersionCode = prefs.getInt(KEY_LAST_VERSION_CODE, 0);
        int currentVersionCode = BuildConfig.VERSION_CODE;
        if (lastVersionCode == currentVersionCode) {
            return;
        }
        prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply();
        EXECUTOR.execute(() -> apkFile().delete());
        boolean updated;
        if (lastVersionCode == 0) {
            // Версии до этой ничего не записывали: отличаем обновление от свежей установки по PackageInfo.
            try {
                PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                updated = packageInfo.lastUpdateTime > packageInfo.firstInstallTime;
            } catch (Throwable e) {
                updated = false;
            }
        } else {
            updated = lastVersionCode < currentVersionCode;
        }
        if (!updated) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                String xml;
                try {
                    xml = httpGet(BASE_URL + "/changelog.xml", "application/rss+xml", false);
                } catch (Throwable ignored) {
                    xml = httpGet(FALLBACK_BASE_URL + "/changelog.xml", "application/rss+xml", false);
                }
                ChangelogEntry entry = xml == null ? null : findChangelogEntry(xml, currentVersionCode, Locale.getDefault().getLanguage());
                if (entry != null && entry.description != null && !entry.description.isEmpty()) {
                    AndroidUtilities.runOnUIThread(() -> whatsNewShown = true);
                    runWithFragment(fragment -> AppUpdateBottomSheet.showWhatsNew(fragment, entry.title, entry.description), 0);
                }
            } catch (Throwable e) {
                FileLog.e("What's new check failed", e);
            }
        });
    }

    private static ChangelogEntry findChangelogEntry(String xml, int versionCode, String language) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new java.io.StringReader(xml));
        String guidPrefix = "libretg-" + versionCode;
        ChangelogEntry fallback = null;
        ChangelogEntry current = null;
        String tag = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                tag = parser.getName();
                if ("item".equals(tag)) {
                    current = new ChangelogEntry();
                }
            } else if ((event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) && current != null && tag != null) {
                String text = parser.getText().trim();
                if (text.isEmpty()) {
                    continue;
                }
                switch (tag) {
                    case "title": current.title = text; break;
                    case "description": current.description = text; break;
                    case "guid": current.guid = text; break;
                    case "lang":
                    case "libre:lang": current.language = text.toLowerCase(Locale.ROOT); break;
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("item".equals(parser.getName()) && current != null) {
                    if (current.guid != null && (current.guid.equals(guidPrefix) || current.guid.startsWith(guidPrefix + "-"))) {
                        String entryLanguage = current.language == null ? "en" : current.language;
                        if (entryLanguage.equals(language)) {
                            return current;
                        }
                        if (fallback == null || "en".equals(entryLanguage)) {
                            fallback = current;
                        }
                    }
                    current = null;
                }
                tag = null;
            }
        }
        return fallback;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", value));
        }
        return result.toString();
    }

    private static final class ChangelogEntry {
        String title;
        String description;
        String guid;
        String language;
    }

    public static final class UpdateInfo {
        public String packageName;
        public int versionCode;
        public String versionName;
        public String title;
        public String releaseNotes;
        public String apkUrl;
        public String sha256;
        public boolean mandatory;
    }
}
