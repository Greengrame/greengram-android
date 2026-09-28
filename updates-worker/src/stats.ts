import { daysAgo, utcDay } from "./rollout.ts";

export interface CheckRecord {
    installId: string | null;
    packageName: string;
    versionCode: number;
}

export interface VersionCount {
    versionCode: number;
    devices: number;
}

export interface DailyCount {
    day: string;
    versionCode: number;
    devices: number;
}

export interface PackageStats {
    daily: DailyCount[];
    active7d: VersionCount[];
    legacy7d: number;
}

export async function recordCheck(db: D1Database, record: CheckRecord, now: Date): Promise<void> {
    const day = utcDay(now);
    try {
        if (record.installId) {
            await db.prepare(
                "INSERT INTO checks (day, install_id, package, version_code) VALUES (?1, ?2, ?3, ?4) "
                + "ON CONFLICT (day, install_id, package) DO UPDATE SET version_code = excluded.version_code"
            ).bind(day, record.installId, record.packageName, record.versionCode).run();
        } else {
            await db.prepare(
                "INSERT INTO legacy_checks (day, package, count) VALUES (?1, ?2, 1) "
                + "ON CONFLICT (day, package) DO UPDATE SET count = count + 1"
            ).bind(day, record.packageName).run();
        }
    } catch (error) {
        console.error("Failed to record update check", error);
    }
}

export async function loadPackageStats(db: D1Database, packageName: string, now: Date): Promise<PackageStats> {
    const since30 = daysAgo(now, 29);
    const since7 = daysAgo(now, 6);
    const [daily, active, legacy] = await db.batch([
        db.prepare(
            "SELECT day, version_code AS versionCode, COUNT(*) AS devices FROM checks "
            + "WHERE package = ?1 AND day >= ?2 GROUP BY day, version_code ORDER BY day"
        ).bind(packageName, since30),
        // SQLite returns the row holding MAX(day), so each install counts once under its latest version.
        db.prepare(
            "SELECT version_code AS versionCode, COUNT(*) AS devices FROM ("
            + "SELECT install_id, version_code, MAX(day) AS last_day FROM checks "
            + "WHERE package = ?1 AND day >= ?2 GROUP BY install_id"
            + ") GROUP BY version_code ORDER BY version_code DESC"
        ).bind(packageName, since7),
        db.prepare(
            "SELECT COALESCE(SUM(count), 0) AS checks FROM legacy_checks WHERE package = ?1 AND day >= ?2"
        ).bind(packageName, since7)
    ]);
    const legacyRow = legacy.results[0] as { checks?: number } | undefined;
    return {
        daily: daily.results as DailyCount[],
        active7d: active.results as VersionCount[],
        legacy7d: Number(legacyRow?.checks ?? 0)
    };
}

export async function deleteOldStats(db: D1Database, now: Date): Promise<void> {
    const cutoff = daysAgo(now, 90);
    await db.batch([
        db.prepare("DELETE FROM checks WHERE day < ?1").bind(cutoff),
        db.prepare("DELETE FROM legacy_checks WHERE day < ?1").bind(cutoff)
    ]);
}
