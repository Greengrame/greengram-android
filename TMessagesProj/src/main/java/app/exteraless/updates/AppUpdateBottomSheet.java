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
    private AppUpdateBottomSheet(BaseFragment fragment, AppUpdateController.UpdateInfo info) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        Activity activity = fragment.getParentActivity();
        fixNavigationBar();

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(24), AndroidUtilities.dp(14), AndroidUtilities.dp(14));

        FrameLayout iconFrame = new FrameLayout(activity);
        iconFrame.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(80), 0xff3f9be8));
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.msg_download);
        icon.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        iconFrame.addView(icon, LayoutHelper.createFrame(42, 42, Gravity.CENTER));
        content.addView(iconFrame, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL));

        TextView title = text(activity, info.title == null ? LocaleController.getString(R.string.ExteraUpdateTitle) : info.title, 22, Theme.key_windowBackgroundWhiteBlackText);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        content.addView(title, LayoutHelper.createLinear(-1, -2, Gravity.CENTER_HORIZONTAL, 18, 18, 18, 0));

        TextView notes = text(activity, info.releaseNotes == null ? LocaleController.getString(R.string.ExteraUpdateReady) : info.releaseNotes, 16, Theme.key_windowBackgroundWhiteBlackText);
        notes.setGravity(Gravity.CENTER);
        notes.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        content.addView(notes, LayoutHelper.createLinear(-1, -2, Gravity.CENTER_HORIZONTAL, 18, 8, 18, 0));

        String versionName = info.versionName == null ? Integer.toString(info.versionCode) : info.versionName;
        TextView version = text(activity, LocaleController.formatString(R.string.ExteraUpdateVersion, versionName), 15, Theme.key_dialogTextGray3);
        version.setGravity(Gravity.CENTER);
        content.addView(version, LayoutHelper.createLinear(-1, -2, Gravity.CENTER_HORIZONTAL, 18, 12, 18, 0));

        TextView update = text(activity, LocaleController.getString(R.string.ExteraUpdateButton), 16, Theme.key_featuredStickers_buttonText);
        update.setTextSize(16);
        update.setGravity(Gravity.CENTER);
        update.setTypeface(AndroidUtilities.bold());
        update.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), Theme.getColor(Theme.key_featuredStickers_addButton, fragment.getResourceProvider())));
        update.setOnClickListener(v -> {
            update.setEnabled(false);
            update.setText(LocaleController.getString(R.string.ExteraUpdateDownloading));
            AppUpdateController.downloadAndInstall(fragment, info);
            dismiss();
        });
        content.addView(update, LayoutHelper.createLinear(-1, 48, 0, 14, 24, 14, 0));

        TextView later = text(activity, LocaleController.getString(R.string.ExteraUpdateLater), 16, Theme.key_windowBackgroundWhiteBlueText4);
        later.setGravity(Gravity.CENTER);
        later.setOnClickListener(v -> dismiss());
        content.addView(later, LayoutHelper.createLinear(-1, 42));

        ScrollView scrollView = new ScrollView(getContext());
        scrollView.addView(content);
        setCustomView(scrollView);
    }

    public static void show(BaseFragment fragment, AppUpdateController.UpdateInfo info) {
        if (fragment != null && fragment.getParentActivity() != null && info != null) {
            fragment.showDialog(new AppUpdateBottomSheet(fragment, info));
        }
    }

    private static TextView text(Activity activity, CharSequence value, int size, int colorKey) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(colorKey));
        return view;
    }
}
