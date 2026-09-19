package com.exteragram.messenger.badges;

import android.app.Activity;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.api.dto.BadgeDTO;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/** Recognition screen shown from a badge's Learn More action. */
public final class BadgeInfoBottomSheet extends BottomSheet {
    private BadgeInfoBottomSheet(BaseFragment fragment, BadgeDTO badge, CharSequence owner) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        Activity activity = fragment.getParentActivity();
        fixNavigationBar();

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(24), AndroidUtilities.dp(14), AndroidUtilities.dp(14));

        FrameLayout iconFrame = new FrameLayout(activity);
        iconFrame.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(80), 0xffdc443d));
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.msg_fave);
        icon.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        iconFrame.addView(icon, LayoutHelper.createFrame(42, 42, Gravity.CENTER));
        content.addView(iconFrame, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL));

        TextView title = text(activity, LocaleController.getString(R.string.ExteraBadgeInfoTitle), 22, Theme.key_windowBackgroundWhiteBlackText);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        content.addView(title, LayoutHelper.createLinear(-1, -2, Gravity.CENTER_HORIZONTAL, 18, 18, 18, 0));

        TextView message = text(activity,
                LocaleController.getString(R.string.ExteraBadgeInfoMessage),
                16, Theme.key_windowBackgroundWhiteBlackText);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        content.addView(message, LayoutHelper.createLinear(-1, -2, Gravity.CENTER_HORIZONTAL, 18, 8, 18, 0));

        content.addView(feature(activity, R.drawable.msg_info,
                LocaleController.getString(R.string.ExteraBadgeInfoProjectRecognition),
                LocaleController.getString(R.string.ExteraBadgeInfoProjectRecognitionText)),
                LayoutHelper.createLinear(-1, -2, 0, 0, 26, 0, 0));
        content.addView(feature(activity, R.drawable.filled_profile_member_24,
                LocaleController.getString(R.string.ExteraBadgeInfoVisibleProfile),
                owner == null
                        ? LocaleController.getString(R.string.ExteraBadgeInfoVisibleProfileGeneric)
                        : LocaleController.formatString(R.string.ExteraBadgeInfoVisibleProfileText, owner)),
                LayoutHelper.createLinear(-1, -2, 0, 0, 16, 0, 0));
        content.addView(feature(activity, R.drawable.msg_check,
                LocaleController.getString(R.string.ExteraBadgeInfoCommunitySupport),
                LocaleController.getString(R.string.ExteraBadgeInfoCommunitySupportText)),
                LayoutHelper.createLinear(-1, -2, 0, 0, 16, 0, 0));

        TextView close = text(activity, LocaleController.getString(R.string.ExteraBadgeInfoClose), 16, Theme.key_featuredStickers_buttonText);
        close.setGravity(Gravity.CENTER);
        close.setTypeface(AndroidUtilities.bold());
        close.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), Theme.getColor(Theme.key_featuredStickers_addButton, fragment.getResourceProvider())));
        close.setOnClickListener(v -> dismiss());
        content.addView(close, LayoutHelper.createLinear(-1, 48, 0, 14, 24, 14, 0));

        ScrollView scrollView = new ScrollView(getContext());
        scrollView.addView(content);
        setCustomView(scrollView);
    }

    public static void show(BaseFragment fragment, BadgeDTO badge, CharSequence owner) {
        if (fragment == null || fragment.getParentActivity() == null || badge == null) {
            return;
        }
        fragment.showDialog(new BadgeInfoBottomSheet(fragment, badge, owner));
    }

    private static LinearLayout feature(Activity activity, int iconRes, String title, String subtitle) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        ImageView icon = new ImageView(activity);
        Drawable drawable = ContextCompat.getDrawable(activity, iconRes);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.MULTIPLY));
            icon.setImageDrawable(drawable);
        }
        row.addView(icon, LayoutHelper.createLinear(28, 28, 0, 0, 2, 20, 0));
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(activity, title, 16, Theme.key_windowBackgroundWhiteBlackText);
        titleView.setTypeface(AndroidUtilities.bold());
        texts.addView(titleView, LayoutHelper.createLinear(-1, -2));
        TextView subtitleView = text(activity, subtitle, 15, Theme.key_dialogTextGray3);
        subtitleView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        texts.addView(subtitleView, LayoutHelper.createLinear(-1, -2, 0, 3, 0, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, -2, 1.0f, 0, 0, 0, 0));
        return row;
    }

    private static TextView text(Activity activity, CharSequence value, int size, int colorKey) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(colorKey));
        return view;
    }
}
