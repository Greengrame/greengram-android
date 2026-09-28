package app.exteraless.updates;

/** Итог проверки обновления: сбой запроса не должен выглядеть как «обновления нет». */
final class UpdateCheck {
    enum Outcome {
        /** Сеть или сервер недоступны: текущее состояние сохраняется. */
        FAILED,
        /** Сервер ответил, что обновления для этого устройства нет (в т.ч. раскатка на паузе). */
        NO_UPDATE,
        UPDATE
    }

    private UpdateCheck() {
    }

    static Outcome classify(boolean requestSucceeded, AppUpdateController.UpdateInfo info, int currentVersionCode, String packageName) {
        if (!requestSucceeded) {
            return Outcome.FAILED;
        }
        if (info == null || info.versionCode <= currentVersionCode || !packageName.equals(info.packageName)
                || info.apkUrl == null || info.apkUrl.isEmpty()) {
            return Outcome.NO_UPDATE;
        }
        return Outcome.UPDATE;
    }
}
