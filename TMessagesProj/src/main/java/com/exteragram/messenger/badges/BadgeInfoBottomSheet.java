package com.exteragram.messenger.badges;

import android.app.Activity;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.api.model.ProfileStatus;
import com.exteragram.messenger.badges.source.BadgeFeature;
import com.exteragram.messenger.badges.source.BadgeInfo;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

/** Badge details sheet with a read-only card and an optional edit action. */
public final class BadgeInfoBottomSheet extends BottomSheet {

    private BadgeInfoBottomSheet(BaseFragment fragment, BadgeDTO badge, CharSequence owner, BadgeInfo info, long ownerId, boolean isChat) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        Activity activity = fragment.getParentActivity();
        Theme.ResourcesProvider provider = fragment.getResourceProvider();
        fixNavigationBar();

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(24), AndroidUtilities.dp(14), AndroidUtilities.dp(14));

        FrameLayout iconFrame = new FrameLayout(activity);
        iconFrame.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(80), Theme.getColor(Theme.key_profile_verifiedBackground, provider)));
        BackupImageView icon = new BackupImageView(activity);
        long iconDocumentId = info != null && info.getInfoIconDocumentId() != null && info.getInfoIconDocumentId() != 0
                ? info.getInfoIconDocumentId() : badge.getDocumentId();
        icon.setAnimatedEmojiDrawable(AnimatedEmojiDrawable.make(fragment.getCurrentAccount(), AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW, iconDocumentId));
        iconFrame.addView(icon, LayoutHelper.createFrame(56, 56, Gravity.CENTER));
        content.addView(iconFrame, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL));

        CharSequence titleText = format(info == null ? null : info.getInfoTitle(), owner, LocaleController.getString(R.string.ExteraBadgeInfoTitle));
        titleText = AnimatedEmojiSpan.cloneSpans(titleText, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW);
        TextView title = text(activity, provider, titleText, 22, Theme.key_windowBackgroundWhiteBlackText);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        content.addView(title, LayoutHelper.createLinear(-1, -2, Gravity.CENTER_HORIZONTAL, 18, 18, 18, 0));

        CharSequence messageText = format(info == null ? null : info.getInfoMessage(), owner, LocaleController.getString(defaultMessage(status(info))));
        messageText = AnimatedEmojiSpan.cloneSpans(messageText, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW);
        TextView message = text(activity, provider, messageText, 16, Theme.key_windowBackgroundWhiteBlackText);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        content.addView(message, LayoutHelper.createLinear(-1, -2, Gravity.CENTER_HORIZONTAL, 18, 8, 18, 0));

        List<BadgeFeature> features = info == null ? null : info.getInfoFeatures();
        if (features == null) {
            content.addView(feature(activity, provider, R.drawable.msg_info,
                    format(null, owner, LocaleController.getString(defaultFeatureTitle(status(info), 1))),
                    defaultFeatureTextValue(status(info), 1, owner)),
                    LayoutHelper.createLinear(-1, -2, 0, 0, 26, 0, 0));
            content.addView(feature(activity, provider, R.drawable.filled_profile_member_24,
                    format(null, owner, LocaleController.getString(defaultFeatureTitle(status(info), 2))),
                    defaultFeatureTextValue(status(info), 2, owner)),
                    LayoutHelper.createLinear(-1, -2, 0, 0, 16, 0, 0));
            content.addView(feature(activity, provider, R.drawable.msg_check,
                    format(null, owner, LocaleController.getString(defaultFeatureTitle(status(info), 3))),
                    defaultFeatureTextValue(status(info), 3, owner)),
                    LayoutHelper.createLinear(-1, -2, 0, 0, 16, 0, 0));
        } else {
            boolean first = true;
            for (BadgeFeature feature : features) {
                if (feature == null || !feature.isEnabled() || TextUtils.isEmpty(feature.title) && TextUtils.isEmpty(feature.text)) {
                    continue;
                }
                content.addView(feature(activity, provider, fragment.getCurrentAccount(), feature, owner),
                        LayoutHelper.createLinear(-1, -2, 0, 0, first ? 26 : 16, 0, 0));
                first = false;
            }
        }

        TextView close = text(activity, provider, format(info == null ? null : info.getCloseText(), owner, LocaleController.getString(R.string.ExteraBadgeInfoClose)), 16, Theme.key_featuredStickers_buttonText);
        close.setGravity(Gravity.CENTER);
        close.setTypeface(AndroidUtilities.bold());
        close.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), Theme.getColor(Theme.key_featuredStickers_addButton, provider)));
        close.setOnClickListener(v -> dismiss());
        content.addView(close, LayoutHelper.createLinear(-1, 48, 0, 14, 24, 14, 0));

        ScrollView scrollView = new ScrollView(getContext());
        scrollView.addView(content);
        setCustomView(scrollView);
    }

    public static void show(BaseFragment fragment, BadgeDTO badge, CharSequence owner) {
        show(fragment, badge, owner, null);
    }

    public static void show(BaseFragment fragment, BadgeDTO badge, CharSequence owner, BadgeInfo info) {
        show(fragment, badge, owner, info, 0, false);
    }

    public static void show(BaseFragment fragment, BadgeDTO badge, CharSequence owner, BadgeInfo info, long ownerId, boolean isChat) {
        if (fragment == null || fragment.getParentActivity() == null || badge == null) {
            return;
        }
        fragment.showDialog(new BadgeInfoBottomSheet(fragment, badge, owner, info, ownerId, isChat));
    }

    private static CharSequence format(String value, CharSequence owner, String fallback) {
        String text = TextUtils.isEmpty(value) ? fallback : value;
        text = text.replace("{USER}", owner == null ? "" : owner.toString());
        return AndroidUtilities.replaceTags(text);
    }

    private static LinearLayout feature(Activity activity, Theme.ResourcesProvider provider, int iconRes, CharSequence title, CharSequence subtitle) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        ImageView icon = new ImageView(activity);
        Drawable drawable = ContextCompat.getDrawable(activity, iconRes);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, provider), PorterDuff.Mode.MULTIPLY));
            icon.setImageDrawable(drawable);
        }
        row.addView(icon, LayoutHelper.createLinear(28, 28, 0, 0, 2, 20, 0));
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(activity, provider, title, 16, Theme.key_windowBackgroundWhiteBlackText);
        titleView.setTypeface(AndroidUtilities.bold());
        texts.addView(titleView, LayoutHelper.createLinear(-1, -2));
        TextView subtitleView = text(activity, provider, subtitle, 15, Theme.key_dialogTextGray3);
        subtitleView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        texts.addView(subtitleView, LayoutHelper.createLinear(-1, -2, 0, 3, 0, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, -2, 1.0f, 0, 0, 0, 0));
        return row;
    }

    private static LinearLayout feature(Activity activity, Theme.ResourcesProvider provider, int account, BadgeFeature feature, CharSequence owner) {
        CharSequence title = format(feature.title, owner, "");
        title = AnimatedEmojiSpan.cloneSpans(title, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW);
        CharSequence subtitle = format(feature.text, owner, "");
        subtitle = AnimatedEmojiSpan.cloneSpans(subtitle, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        View icon;
        if (feature.icon_document_id != null && feature.icon_document_id != 0) {
            BackupImageView emoji = new BackupImageView(activity);
            emoji.setAnimatedEmojiDrawable(AnimatedEmojiDrawable.make(account, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW, feature.icon_document_id));
            icon = emoji;
        } else {
            ImageView fallback = new ImageView(activity);
            fallback.setImageResource(R.drawable.msg_info);
            fallback.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, provider), PorterDuff.Mode.MULTIPLY));
            icon = fallback;
        }
        row.addView(icon, LayoutHelper.createLinear(28, 28, 0, 0, 2, 20, 0));
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(activity, provider, title, 16, Theme.key_windowBackgroundWhiteBlackText);
        titleView.setTypeface(AndroidUtilities.bold());
        texts.addView(titleView, LayoutHelper.createLinear(-1, -2));
        TextView subtitleView = text(activity, provider, subtitle, 15, Theme.key_dialogTextGray3);
        subtitleView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        texts.addView(subtitleView, LayoutHelper.createLinear(-1, -2, 0, 3, 0, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, -2, 1.0f, 0, 0, 0, 0));
        return row;
    }

    private static TextView text(Activity activity, Theme.ResourcesProvider provider, CharSequence value, int size, int colorKey) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(colorKey, provider));
        return view;
    }

    private static ProfileStatus status(BadgeInfo info) {
        return info == null || info.getStatus() == null ? ProfileStatus.DEFAULT : info.getStatus();
    }

    private static int defaultMessage(ProfileStatus status) {
        switch (status) {
            case DEVELOPER: return R.string.ExteraBadgeInfoMessageDeveloper;
            case SUPPORTER: return R.string.ExteraBadgeInfoMessageSupporter;
            case RESOURCE: return R.string.ExteraBadgeInfoMessageResource;
            case PARTNER: return R.string.ExteraBadgeInfoMessagePartner;
            default: return R.string.ExteraBadgeInfoMessage;
        }
    }

    private static int defaultFeatureTitle(ProfileStatus status, int feature) {
        switch (status) {
            case DEVELOPER:
                return feature == 1 ? R.string.ExteraBadgeInfoFeature1TitleDeveloper : feature == 2 ? R.string.ExteraBadgeInfoFeature2TitleDeveloper : R.string.ExteraBadgeInfoFeature3TitleDeveloper;
            case SUPPORTER:
                return feature == 1 ? R.string.ExteraBadgeInfoFeature1TitleSupporter : feature == 2 ? R.string.ExteraBadgeInfoFeature2TitleSupporter : R.string.ExteraBadgeInfoFeature3TitleSupporter;
            case RESOURCE:
                return feature == 1 ? R.string.ExteraBadgeInfoFeature1TitleResource : feature == 2 ? R.string.ExteraBadgeInfoFeature2TitleResource : R.string.ExteraBadgeInfoFeature3TitleResource;
            case PARTNER:
                return feature == 1 ? R.string.ExteraBadgeInfoFeature1TitlePartner : feature == 2 ? R.string.ExteraBadgeInfoFeature2TitlePartner : R.string.ExteraBadgeInfoFeature3TitlePartner;
            default:
                return feature == 1 ? R.string.ExteraBadgeInfoProjectRecognition : feature == 2 ? R.string.ExteraBadgeInfoVisibleProfile : R.string.ExteraBadgeInfoCommunitySupport;
        }
    }

    private static int defaultFeatureText(ProfileStatus status, int feature) {
        switch (status) {
            case DEVELOPER:
                return feature == 1 ? R.string.ExteraBadgeInfoFeature1TextDeveloper : feature == 2 ? R.string.ExteraBadgeInfoFeature2TextDeveloper : R.string.ExteraBadgeInfoFeature3TextDeveloper;
            case SUPPORTER:
                return feature == 1 ? R.string.ExteraBadgeInfoFeature1TextSupporter : feature == 2 ? R.string.ExteraBadgeInfoFeature2TextSupporter : R.string.ExteraBadgeInfoFeature3TextSupporter;
            case RESOURCE:
                return feature == 1 ? R.string.ExteraBadgeInfoFeature1TextResource : feature == 2 ? R.string.ExteraBadgeInfoFeature2TextResource : R.string.ExteraBadgeInfoFeature3TextResource;
            case PARTNER:
                return feature == 1 ? R.string.ExteraBadgeInfoFeature1TextPartner : feature == 2 ? R.string.ExteraBadgeInfoFeature2TextPartner : R.string.ExteraBadgeInfoFeature3TextPartner;
            default:
                return feature == 1 ? R.string.ExteraBadgeInfoProjectRecognitionText : feature == 2 ? R.string.ExteraBadgeInfoVisibleProfileText : R.string.ExteraBadgeInfoCommunitySupportText;
        }
    }

    private static CharSequence defaultFeatureTextValue(ProfileStatus status, int feature, CharSequence owner) {
        if (status == ProfileStatus.DEFAULT && feature == 2) {
            return owner == null
                    ? LocaleController.getString(R.string.ExteraBadgeInfoVisibleProfileGeneric)
                    : LocaleController.formatString(R.string.ExteraBadgeInfoVisibleProfileText, owner);
        }
        return format(null, owner, LocaleController.getString(defaultFeatureText(status, feature)));
    }
}
