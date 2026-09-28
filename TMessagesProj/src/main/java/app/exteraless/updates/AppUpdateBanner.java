package app.exteraless.updates;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.DialogsHintCell;

/** Баннер обновления над списком чатов: доступно → загрузка с прогрессом → готово к установке. */
public final class AppUpdateBanner {
    private AppUpdateBanner() {
    }

    public static boolean isVisible() {
        return AppUpdateController.isBannerVisible();
    }

    public static void bind(BaseFragment fragment, DialogsHintCell cell) {
        AppUpdateController.UpdateInfo update = AppUpdateController.getUpdate();
        if (update == null) {
            return;
        }
        String versionName = update.versionName == null ? Integer.toString(update.versionCode) : update.versionName;
        switch (AppUpdateController.getState()) {
            case AppUpdateController.STATE_DOWNLOADING: {
                long total = AppUpdateController.getTotalBytes();
                long downloaded = AppUpdateController.getDownloadedBytes();
                String progress = total > 0
                        ? LocaleController.formatString(R.string.ExteraUpdateBannerProgress, AppUpdateController.getDownloadPercent(),
                                AndroidUtilities.formatFileSize(downloaded), AndroidUtilities.formatFileSize(total))
                        : AndroidUtilities.formatFileSize(downloaded);
                cell.setOnClickListener(null);
                cell.setText(LocaleController.formatString(R.string.ExteraUpdateBannerDownloading, versionName), progress, false, false);
                break;
            }
            case AppUpdateController.STATE_READY:
                cell.setOnClickListener(v -> AppUpdateController.startUpdate());
                cell.setText(LocaleController.formatString(R.string.ExteraUpdateBannerReady, versionName), LocaleController.getString(R.string.ExteraUpdateBannerInstall));
                break;
            default:
                cell.setOnClickListener(v -> AppUpdateBottomSheet.show(fragment, update));
                cell.setText(LocaleController.formatString(R.string.ExteraUpdateBannerTitle, versionName), LocaleController.getString(R.string.ExteraUpdateBannerText));
                if (!update.mandatory) {
                    cell.setOnCloseListener(v -> AppUpdateController.hideBanner());
                }
                break;
        }
    }
}
