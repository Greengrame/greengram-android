package com.exteragram.messenger.badges.source;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.api.model.ProfileStatus;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Remote profile source with a persistent SharedPreferences cache. */
public final class ApiBadgeSource {
    private static final long DEVELOPER_BADGE_DOCUMENT_ID = 5359407509327085568L;
    private static final long SUPPORTER_BADGE_DOCUMENT_ID = 5391059537102927631L;
    private static final String CACHE_KEY = "exteraless_badges_profiles";
    private static final String ETAG_KEY = "exteraless_badges_etag";
    private static final String SYNC_KEY = "exteraless_badges_sync";
    private static final long SYNC_INTERVAL = 10 * 60 * 1000L;
    private static final Gson GSON = new Gson();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncInProgress = new AtomicBoolean();

    private final ConcurrentHashMap<Long, BadgeInfo> cache = new ConcurrentHashMap<>();

    public BadgeDTO getBadge(long id, boolean isUser) {
        BadgeInfo info = cache.get(id);
        if (info == null) {
            return null;
        }
        if (info.getBadge() != null) {
            return info.getBadge();
        }
        if (info.getStatus() == ProfileStatus.DEVELOPER) {
            return new BadgeDTO(DEVELOPER_BADGE_DOCUMENT_ID, null);
        }
        if (info.getStatus() == ProfileStatus.SUPPORTER) {
            return new BadgeDTO(SUPPORTER_BADGE_DOCUMENT_ID, null);
        }
        return null;
    }

    public boolean isDeveloper(long id) {
        BadgeInfo info = cache.get(id);
        return info != null && info.getStatus() == ProfileStatus.DEVELOPER;
    }

    public boolean canChangeBadge(long id) {
        BadgeInfo info = cache.get(id);
        return info != null && (info.getCanChangeBadge() || isDeveloper(id));
    }

    public boolean shouldShowBadgeButton(long id) {
        BadgeInfo info = cache.get(id);
        return info != null && info.getShowBadgeButton();
    }

    public BadgeInfo getInfo(long id) {
        return cache.get(id);
    }

    public void put(long id, BadgeInfo info) {
        if (info == null) {
            cache.remove(id);
        } else {
            cache.put(id, info);
        }
    }

    public void clear() {
        cache.clear();
    }

    public void loadToCache() {
        String cached = ExteraConfig.getPreferences().getString(CACHE_KEY, null);
        boolean hasCache = cached != null && !cached.isEmpty();
        if (cached != null && !cached.isEmpty()) {
            try {
                replace(GSON.fromJson(cached, new TypeToken<List<RemoteProfile>>() { }.getType()));
            } catch (Throwable e) {
                FileLog.e("Unable to read badge cache", e);
            }
        }
        if (!hasCache || System.currentTimeMillis() - ExteraConfig.getPreferences().getLong(SYNC_KEY, 0L) >= SYNC_INTERVAL) {
            refresh();
        }
    }

    public void refresh() {
        if (syncInProgress.compareAndSet(false, true)) {
            EXECUTOR.execute(() -> {
                try {
                    sync();
                } finally {
                    syncInProgress.set(false);
                }
            });
        }
    }

    private void sync() {
        HttpURLConnection connection = null;
        try {
            String base = ExteraConfig.getBadgeApiUrl().trim();
            if (base.isEmpty()) {
                return;
            }
            String endpoint = base.endsWith("/rest/v1")
                    ? base + "/profiles"
                    : (base.endsWith("/") ? base : base + "/") + "rest/v1/profiles";
            connection = (HttpURLConnection) new URL(endpoint + "?select=id,type,status,badge_document_id,badge_text,can_change_badge,show_badge_button,deleted,updated_at&deleted=eq.false").openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "application/json");
            String apiKey = ExteraConfig.getBadgeApiKey().trim();
            if (!apiKey.isEmpty()) {
                connection.setRequestProperty("apikey", apiKey);
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            String etag = ExteraConfig.getPreferences().getString(ETAG_KEY, null);
            if (etag != null && !etag.isEmpty()) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                ExteraConfig.getEditor().putLong(SYNC_KEY, System.currentTimeMillis()).apply();
                return;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return;
            }
            StringBuilder json = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
            }
            List<RemoteProfile> profiles = GSON.fromJson(json.toString(), new TypeToken<List<RemoteProfile>>() { }.getType());
            replace(profiles);
            android.content.SharedPreferences.Editor editor = ExteraConfig.getEditor()
                    .putString(CACHE_KEY, json.toString())
                    .putLong(SYNC_KEY, System.currentTimeMillis());
            String responseEtag = connection.getHeaderField("ETag");
            if (responseEtag != null && !responseEtag.isEmpty()) {
                editor.putString(ETAG_KEY, responseEtag);
            }
            editor.apply();
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationNameOnUIThread(
                        NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_EMOJI_STATUS);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            });
        } catch (Throwable e) {
            FileLog.e("Unable to sync badges", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void replace(List<RemoteProfile> profiles) {
        cache.clear();
        if (profiles == null) {
            return;
        }
        for (RemoteProfile profile : profiles) {
            if (profile == null || profile.id == 0) {
                continue;
            }
            ProfileStatus status;
            try {
                status = profile.status == null ? ProfileStatus.DEFAULT : ProfileStatus.valueOf(profile.status.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                status = ProfileStatus.DEFAULT;
            }
            long documentId;
            if (profile.badge_document_id != null) {
                documentId = profile.badge_document_id;
            } else if (status == ProfileStatus.DEVELOPER) {
                documentId = DEVELOPER_BADGE_DOCUMENT_ID;
            } else if (status == ProfileStatus.SUPPORTER) {
                documentId = SUPPORTER_BADGE_DOCUMENT_ID;
            } else {
                documentId = 0;
            }
            BadgeDTO badge = documentId == 0 ? null : new BadgeDTO(documentId, profile.badge_text);
            cache.put(profile.id, new BadgeInfo(badge, status, Boolean.TRUE.equals(profile.canChangeBadge), Boolean.TRUE.equals(profile.show_badge_button)));
        }
    }

    private static final class RemoteProfile {
        long id;
        String type;
        String status;
        Long badge_document_id;
        String badge_text;
        Boolean canChangeBadge;
        Boolean show_badge_button;
        Boolean deleted;
    }
}
