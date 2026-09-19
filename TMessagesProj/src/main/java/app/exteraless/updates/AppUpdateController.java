package app.exteraless.updates;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.google.gson.Gson;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdateController {
    private static final String MANIFEST_URL = "https://tg.upd.dexx.moe/update.json";
    private static final String FALLBACK_MANIFEST_URL = "https://tg-updates.rtstaller1.workers.dev/update.json";
    private static final String APK_FILE_NAME = "exteraless-update.apk";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Gson GSON = new Gson();
    private static volatile boolean started;

    private AppUpdateController() {
    }

    public static void init(Context context) {
        if (started) {
            return;
        }
        started = true;
        AndroidUtilities.runOnUIThread(AppUpdateController::checkNow, 4000L);
    }

    public static void checkNow() {
        EXECUTOR.execute(() -> {
            try {
                String packageName = ApplicationLoader.getApplicationId();
                String language = Locale.getDefault().toLanguageTag();
                UpdateInfo info = null;
                try {
                    info = loadManifest(MANIFEST_URL, packageName, language);
                } catch (Throwable ignored) {
                    // Fall back to the workers.dev endpoint when the custom domain is unavailable.
                }
                if (info == null) {
                    try {
                        info = loadManifest(FALLBACK_MANIFEST_URL, packageName, language);
                    } catch (Throwable e) {
                        FileLog.e("Fallback update check failed", e);
                        return;
                    }
                }
                if (info == null || info.versionCode <= BuildConfig.VERSION_CODE
                        || !ApplicationLoader.getApplicationId().equals(info.packageName)
                        || info.apkUrl == null || info.apkUrl.isEmpty()) {
                    return;
                }
                final UpdateInfo updateInfo = info;
                AndroidUtilities.runOnUIThread(() -> {
                    BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                    if (fragment != null) {
                        AppUpdateBottomSheet.show(fragment, updateInfo);
                    }
                });
            } catch (Throwable e) {
                FileLog.e("Update check failed", e);
            }
        });
    }

    private static UpdateInfo loadManifest(String manifestUrl, String packageName, String language) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Language", language);
        connection.setRequestProperty("X-LibreTG-Package", packageName);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            StringBuilder json = new StringBuilder();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    json.append(new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            return GSON.fromJson(json.toString(), UpdateInfo.class);
        } finally {
            connection.disconnect();
        }
    }

    static void downloadAndInstall(BaseFragment fragment, UpdateInfo info) {
        EXECUTOR.execute(() -> {
            File output = new File(ApplicationLoader.applicationContext.getCacheDir(), APK_FILE_NAME);
            File partial = new File(output.getParentFile(), APK_FILE_NAME + ".part");
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(info.apkUrl).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(60000);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("Update server returned HTTP " + connection.getResponseCode());
                }
                try (InputStream input = connection.getInputStream(); FileOutputStream outputStream = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        outputStream.write(buffer, 0, count);
                    }
                } finally {
                    connection.disconnect();
                }
                if (info.sha256 != null && !info.sha256.isEmpty() && !info.sha256.startsWith("REPLACE_")) {
                    String actual = sha256(partial);
                    if (!info.sha256.equalsIgnoreCase(actual)) {
                        throw new SecurityException("Update checksum mismatch");
                    }
                }
                if (output.exists()) {
                    output.delete();
                }
                if (!partial.renameTo(output)) {
                    throw new IllegalStateException("Cannot finalize update file");
                }
                AndroidUtilities.runOnUIThread(() -> install(fragment, output));
            } catch (Throwable e) {
                partial.delete();
                FileLog.e("Update download failed", e);
                AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.ExteraUpdateDownloadFailed)).show());
            }
        });
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

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
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
