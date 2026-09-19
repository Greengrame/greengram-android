package com.exteragram.messenger.badges;

import android.content.SharedPreferences;
import android.widget.FrameLayout;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.badges.source.ApiBadgeSource;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BulletinFactory;

import java.util.function.Consumer;

/** Badge policy facade backed by the project's own Supabase profile database. */
public final class BadgesController {

    public static final BadgesController INSTANCE = new BadgesController();

    private static final ApiBadgeSource apiBadgeSource = new ApiBadgeSource();
    private static final BadgeDTO DEVELOPER_BADGE = new BadgeDTO(5359407509327085568L, null);
    private static final BadgeDTO SUPPORTER_BADGE = new BadgeDTO(5391059537102927631L, null);
    private static final BadgeDTO TRUSTED_BADGE = new BadgeDTO(5452008215409629764L, null);

    private static final String PREFS_NAME = "exteraless_badges";
    private static final String KEY_DOCUMENT = "badge_document_";
    private static final String KEY_TEXT = "badge_text_";

    private BadgesController() {
    }

    public void initialize() {
        apiBadgeSource.loadToCache();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
    }

    private static long selfId() {
        TLRPC.User self = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
        return self == null ? 0 : self.id;
    }

    public BadgeDTO getBadge() {
        return getBadgeFor(selfId());
    }

    public BadgeDTO getBadge(TLObject object) {
        if (object instanceof TLRPC.User) {
            return getBadgeFor(((TLRPC.User) object).id);
        }
        if (object instanceof TLRPC.Chat) {
            return getBadgeFor(((TLRPC.Chat) object).id);
        }
        return null;
    }

    public ApiBadgeSource getApiBadgeSource() {
        return apiBadgeSource;
    }

    private BadgeDTO getBadgeFor(long id) {
        if (id == 0) {
            return null;
        }
        BadgeDTO fromSource = apiBadgeSource.getBadge(id, id > 0);
        if (fromSource != null) {
            return fromSource;
        }
        SharedPreferences preferences = prefs();
        long documentId = preferences.getLong(KEY_DOCUMENT + id, 0);
        if (documentId == 0) {
            return null;
        }
        return new BadgeDTO(documentId, preferences.getString(KEY_TEXT + id, null));
    }

    public BadgeDTO getDefaultBadge() {
        return isDeveloper() ? DEVELOPER_BADGE : SUPPORTER_BADGE;
    }

    public BadgeDTO getDefaultBadge(TLRPC.User user) {
        return isDeveloper(user) ? DEVELOPER_BADGE : SUPPORTER_BADGE;
    }

    public BadgeDTO getSecondaryBadge(TLRPC.User user) {
        return null;
    }

    public boolean hasBadge() {
        return getBadge() != null;
    }

    public boolean hasBadge(TLObject object) {
        return getBadge(object) != null;
    }

    public boolean canChangeBadge() {
        return canChangeBadge(UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser());
    }

    public boolean canChangeBadge(TLRPC.User user) {
        return user != null && (user.id == selfId() || apiBadgeSource.canChangeBadge(user.id));
    }

    public boolean isDeveloper() {
        return apiBadgeSource.isDeveloper(selfId());
    }

    public boolean isDeveloper(TLRPC.User user) {
        return user != null && apiBadgeSource.isDeveloper(user.id);
    }

    public boolean isExtera(long id) {
        return apiBadgeSource.isDeveloper(id);
    }

    public boolean isExtera(TLRPC.Chat chat) {
        return chat != null && isExtera(chat.id);
    }

    public boolean isTrusted(long id) {
        return id == 2562664432L;
    }

    public boolean shouldUseSecondaryBadgeSlot(TLRPC.User user, BadgeDTO badge) {
        return false;
    }

    public void updateBadge(BadgeDTO badge, Consumer<String> callback) {
        long id = selfId();
        if (id != 0) {
            SharedPreferences.Editor editor = prefs().edit();
            if (badge == null) {
                editor.remove(KEY_DOCUMENT + id).remove(KEY_TEXT + id);
            } else {
                editor.putLong(KEY_DOCUMENT + id, badge.getDocumentId())
                        .putString(KEY_TEXT + id, badge.getText());
            }
            editor.apply();
        }
        if (callback != null) {
            callback.accept("ok");
        }
    }

    public void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.Chat chat,
                                  Theme.ResourcesProvider resourcesProvider, int account) {
        showBadgeBulletin(fragment, badge, chat, resourcesProvider, account, null, Boolean.FALSE);
    }

    public void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.Chat chat,
                                   Theme.ResourcesProvider resourcesProvider, int account,
                                   FrameLayout containerLayout, Boolean showButton) {
        showBadgeBulletinInternal(fragment, badge, chat == null ? null : chat.title, chat == null ? null : chat.title, account);
    }

    public void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.User user,
                                  Theme.ResourcesProvider resourcesProvider, int account) {
        showBadgeBulletin(fragment, badge, user, resourcesProvider, account, null, Boolean.FALSE);
    }

    public void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.User user,
                                   Theme.ResourcesProvider resourcesProvider, int account,
                                   FrameLayout containerLayout, Boolean showButton) {
        if (user == null) {
            return;
        }
        CharSequence fallback = LocaleController.formatString(
                isDeveloper(user) ? R.string.ExteraBadgeDeveloper : R.string.ExteraBadgeSupporter,
                user.first_name);
        showBadgeBulletinInternal(fragment, badge, fallback, user.first_name, account);
    }

    public void showBadgeBulletin(BaseFragment fragment, TLRPC.User user,
                                   Theme.ResourcesProvider resourcesProvider, int account,
                                   FrameLayout containerLayout, Boolean showButton) {
        if (user == null) {
            return;
        }
        CharSequence fallback = LocaleController.formatString(
                isDeveloper(user) ? R.string.ExteraBadgeDeveloper : R.string.ExteraBadgeSupporter,
                user.first_name);
        showBadgeBulletinInternal(fragment, getBadge(user), fallback, user.first_name, account);
    }

    private void showBadgeBulletinInternal(BaseFragment fragment, BadgeDTO badge, CharSequence fallback, CharSequence owner, int account) {
        if (fragment == null || badge == null) {
            return;
        }
        CharSequence text = badge.getText();
        if (text == null || text.length() == 0) {
            text = fallback == null ? "Extera badge" : fallback;
        }
        TLRPC.Document document = AnimatedEmojiDrawable.findDocument(account, badge.getDocumentId());
        if (document != null) {
            BulletinFactory.of(fragment).createEmojiBulletin(document, text,
                    LocaleController.getString(R.string.LearnMore),
                    () -> BadgeInfoBottomSheet.show(fragment, badge, owner)).show();
        } else {
            BadgeInfoBottomSheet.show(fragment, badge, owner);
        }
    }
}
