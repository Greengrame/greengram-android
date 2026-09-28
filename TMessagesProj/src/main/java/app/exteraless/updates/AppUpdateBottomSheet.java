package app.exteraless.updates;

import android.app.Activity;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

public final class AppUpdateBottomSheet extends BottomSheet {
    private final boolean blocking;

    private AppUpdateBottomSheet(BaseFragment fragment, int iconRes, CharSequence titleText, CharSequence notesText,
                                 CharSequence versionText, CharSequence buttonText, Runnable onButton, boolean blocking) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        this.blocking = blocking;
        Activity activity = fragment.getParentActivity();
        fixNavigationBar();
        if (blocking) {
            setCanDismissWithSwipe(false);
            setCanDismissWithTouchOutside(false);
        }

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(24), AndroidUtilities.dp(14), AndroidUtilities.dp(14));

        FrameLayout iconFrame = new FrameLayout(activity);
        iconFrame.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(80), 0xff3f9be8));
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        iconFrame.addView(icon, LayoutHelper.createFrame(42, 42, Gravity.CENTER));
        content.addView(iconFrame, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL));

        TextView title = text(activity, titleText, 22, Theme.key_windowBackgroundWhiteBlackText);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        content.addView(title, LayoutHelper.createLinear(-1, -2, Gravity.CENTER_HORIZONTAL, 18, 18, 18, 0));

        TextView notes = text(activity, notesText, 16, Theme.key_windowBackgroundWhiteBlackText);
        notes.setGravity(Gravity.CENTER);
        notes.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        content.addView(notes, LayoutHelper.createLinear(-1, -2, Gravity.CENTER_HORIZONTAL, 18, 8, 18, 0));

        if (versionText != null) {
            TextView version = text(activity, versionText, 15, Theme.key_dialogTextGray3);
            version.setGravity(Gravity.CENTER);
            content.addView(version, LayoutHelper.createLinear(-1, -2, Gravity.CENTER_HORIZONTAL, 18, 12, 18, 0));
        }

        TextView button = text(activity, buttonText, 16, Theme.key_featuredStickers_buttonText);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(AndroidUtilities.bold());
        button.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), Theme.getColor(Theme.key_featuredStickers_addButton, fragment.getResourceProvider())));
        button.setOnClickListener(v -> {
            if (onButton != null) {
                onButton.run();
            }
            dismiss();
        });
        content.addView(button, LayoutHelper.createLinear(-1, 48, 0, 14, 24, 14, blocking ? 8 : 0));

        if (!blocking && onButton != null) {
            TextView later = text(activity, LocaleController.getString(R.string.ExteraUpdateLater), 16, Theme.key_windowBackgroundWhiteBlueText4);
            later.setGravity(Gravity.CENTER);
            later.setOnClickListener(v -> dismiss());
            content.addView(later, LayoutHelper.createLinear(-1, 42));
        }

        ScrollView scrollView = new ScrollView(getContext());
        scrollView.addView(content);
        setCustomView(scrollView);
    }

    @Override
    public void setCanceledOnTouchOutside(boolean cancel) {
        // BaseFragment.showDialog всегда включает закрытие по тапу снаружи; обязательное обновление так не закрывается.
        super.setCanceledOnTouchOutside(cancel && !blocking);
    }

    @Override
    public void onBackPressed() {
        if (!blocking) {
            super.onBackPressed();
        }
    }

    public static boolean show(BaseFragment fragment, AppUpdateController.UpdateInfo info) {
        if (fragment == null || fragment.getParentActivity() == null || info == null) {
            return false;
        }
        String versionName = info.versionName == null ? Integer.toString(info.versionCode) : info.versionName;
        return fragment.showDialog(new AppUpdateBottomSheet(fragment, R.drawable.msg_download,
                info.title == null ? LocaleController.getString(R.string.ExteraUpdateTitle) : info.title,
                info.releaseNotes == null ? LocaleController.getString(R.string.ExteraUpdateReady) : info.releaseNotes,
                LocaleController.formatString(R.string.ExteraUpdateVersion, versionName),
                LocaleController.getString(R.string.ExteraUpdateButton),
                AppUpdateController::startUpdate,
                info.mandatory)) != null;
    }

    public static boolean showWhatsNew(BaseFragment fragment, String title, String notes) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return false;
        }
        return fragment.showDialog(new AppUpdateBottomSheet(fragment, R.drawable.msg_info,
                LocaleController.getString(R.string.ExteraUpdateWhatsNew),
                notes,
                title,
                LocaleController.getString(R.string.ExteraUpdateWhatsNewButton),
                null,
                false)) != null;
    }

    private static TextView text(Activity activity, CharSequence value, int size, int colorKey) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(colorKey));
        return view;
    }
}
