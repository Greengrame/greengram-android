package app.exteraless.updates;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UpdateCheckTest {
    private static final String PACKAGE = "com.de.xxtg.beta";

    private static AppUpdateController.UpdateInfo info(int versionCode, String packageName, String apkUrl) {
        AppUpdateController.UpdateInfo info = new AppUpdateController.UpdateInfo();
        info.versionCode = versionCode;
        info.packageName = packageName;
        info.apkUrl = apkUrl;
        return info;
    }

    @Test
    public void failedRequestIsNotTreatedAsNoUpdate() {
        assertEquals(UpdateCheck.Outcome.FAILED, UpdateCheck.classify(false, null, 1274, PACKAGE));
    }

    @Test
    public void newerVersionForThisPackageIsAnUpdate() {
        assertEquals(UpdateCheck.Outcome.UPDATE, UpdateCheck.classify(true, info(1275, PACKAGE, "https://x/app.apk"), 1274, PACKAGE));
    }

    @Test
    public void rolloutExclusionOrPausedIsNoUpdate() {
        // Воркер отвечает {"error":"No update"}: Gson даёт versionCode 0.
        assertEquals(UpdateCheck.Outcome.NO_UPDATE, UpdateCheck.classify(true, info(0, null, null), 1274, PACKAGE));
        assertEquals(UpdateCheck.Outcome.NO_UPDATE, UpdateCheck.classify(true, null, 1274, PACKAGE));
    }

    @Test
    public void sameOrOlderVersionWrongPackageOrMissingApkIsNoUpdate() {
        assertEquals(UpdateCheck.Outcome.NO_UPDATE, UpdateCheck.classify(true, info(1274, PACKAGE, "https://x/app.apk"), 1274, PACKAGE));
        assertEquals(UpdateCheck.Outcome.NO_UPDATE, UpdateCheck.classify(true, info(1275, "com.de.xxtg", "https://x/app.apk"), 1274, PACKAGE));
        assertEquals(UpdateCheck.Outcome.NO_UPDATE, UpdateCheck.classify(true, info(1275, PACKAGE, ""), 1274, PACKAGE));
    }
}
