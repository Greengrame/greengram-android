import { test } from "node:test";
import assert from "node:assert/strict";
import { deleteOldStats, loadPackageStats, recordCheck } from "./stats.ts";

interface Call { sql: string; params: unknown[] }

function fakeDb(results: unknown[][] = []) {
    const calls: Call[] = [];
    const statement = (sql: string) => ({
        sql,
        params: [] as unknown[],
        bind(...params: unknown[]) {
            this.params = params;
            return this;
        },
        async run() {
            calls.push({ sql: this.sql, params: this.params });
            return { success: true };
        }
    });
    const db = {
        prepare: statement,
        async batch(statements: { sql: string; params: unknown[] }[]) {
            statements.forEach(s => calls.push({ sql: s.sql, params: s.params }));
            return statements.map((_, i) => ({ results: results[i] ?? [] }));
        }
    };
    return { db, calls };
}

const NOW = new Date("2026-09-28T12:00:00Z");
const ID = "3f2b8c1e-9a4d-4e7b-8c21-5d6e7f8a9b0c";

test("recordCheck upserts one row per install and day", async () => {
    const { db, calls } = fakeDb();
    await recordCheck(db as never, { installId: ID, packageName: "com.de.xxtg", versionCode: 1275 }, NOW);
    assert.equal(calls.length, 1);
    assert.match(calls[0].sql, /INSERT INTO checks/);
    assert.match(calls[0].sql, /ON CONFLICT/);
    assert.deepEqual(calls[0].params, ["2026-09-28", ID, "com.de.xxtg", 1275]);
});

test("recordCheck counts legacy clients separately", async () => {
    const { db, calls } = fakeDb();
    await recordCheck(db as never, { installId: null, packageName: "com.de.xxtg.beta", versionCode: 0 }, NOW);
    assert.match(calls[0].sql, /INSERT INTO legacy_checks/);
    assert.deepEqual(calls[0].params, ["2026-09-28", "com.de.xxtg.beta"]);
});

test("recordCheck never throws when D1 fails", async () => {
    const db = { prepare() { throw new Error("D1 is down"); } };
    await assert.doesNotReject(recordCheck(db as never, { installId: ID, packageName: "com.de.xxtg", versionCode: 1275 }, NOW));
});

test("loadPackageStats queries the right windows and maps results", async () => {
    const { db, calls } = fakeDb([
        [{ day: "2026-09-28", versionCode: 1275, devices: 3 }],
        [{ versionCode: 1275, devices: 3 }],
        [{ checks: 7 }]
    ]);
    const stats = await loadPackageStats(db as never, "com.de.xxtg", NOW);
    assert.deepEqual(calls.map(c => c.params), [
        ["com.de.xxtg", "2026-08-30"],
        ["com.de.xxtg", "2026-09-22"],
        ["com.de.xxtg", "2026-09-22"]
    ]);
    assert.deepEqual(stats, {
        daily: [{ day: "2026-09-28", versionCode: 1275, devices: 3 }],
        active7d: [{ versionCode: 1275, devices: 3 }],
        legacy7d: 7
    });
});

test("deleteOldStats removes rows older than 90 days from both tables", async () => {
    const { db, calls } = fakeDb();
    await deleteOldStats(db as never, NOW);
    assert.equal(calls.length, 2);
    assert.ok(calls.every(c => c.params[0] === "2026-06-30"));
});
