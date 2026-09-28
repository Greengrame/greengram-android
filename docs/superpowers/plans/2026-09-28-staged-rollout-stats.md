# Staged Rollout and Install Stats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a LibreTG release be offered to a percentage of devices, and show unique active devices per version on a password-protected `/stats` page served by the update worker.

**Architecture:**
- The app sends a random install UUID and its own version code with each `update.json` request.
- The Cloudflare worker (`updates-worker`) records one D1 row per device per day and decides whether to offer the update from a SHA-256 bucket of `installId:versionCode` against the manifest's `rollout` field.
- A server-rendered `/stats` page reads D1 and can rewrite `rollout` in the R2 manifest.
- The Gradle publish tasks write `rollout` (default 100, `-Prollout=N` to stage).

**Tech Stack:**
- Cloudflare Workers (TypeScript, bundled by wrangler 4), Cloudflare D1 (SQLite), R2.
- `node --test` on Node 24 (runs `.ts` natively) for unit tests.
- Java (Android app), Groovy (Gradle).

**Spec:** `docs/superpowers/specs/2026-09-28-staged-rollout-stats-design.md`

## Global Constraints

- Install ID header: `X-LibreTG-Install`. Client version header: `X-LibreTG-Version`. Both are sent on `update.json` requests only.
- Install ID: a canonical UUID (case-insensitive), stored lowercase. Anything else is a legacy client.
- Bucket: the first 4 bytes of SHA-256(`"<installId>:<versionCode>"`) as uint32, mod 100. Offered when `bucket < rollout`.
- `rollout` is an integer 0–100. A missing value means 100. At 100 everyone is offered; at 0 nobody; in between, only valid install IDs.
- Response when not offered: HTTP 200 `{"error":"No update"}`.
- `day` = UTC `YYYY-MM-DD`. Stats kept 90 days. Cleanup cron `17 3 * * *`.
- D1 binding `STATS`, database `tg-updates-stats`, migrations in `updates-worker/migrations`.
- `/stats*` uses Basic auth against worker secret `STATS_PASSWORD` (the username is ignored). Unset secret → 503.
- `POST /stats/rollout` requires `Origin` equal to the worker origin.
- Stats writes are best-effort (`ctx.waitUntil`, errors caught); update checks never fail because of stats.
- No IP addresses or account data are stored.
- The real `STATS_PASSWORD` value never appears in the repo, logs or chat. Local testing uses `.dev.vars` with `STATS_PASSWORD=local-test` (gitignored).
- Steps that touch the live Cloudflare account (D1 create, remote migration, deploy) need explicit user confirmation right before running.

## Review Focus

- **Existing manifests with no `rollout` field** must keep offering the update to everyone (treated as 100). Pinned in Task 1's `parseRollout`/`shouldOffer` tests.
- **Bad rollout input on the page** (`"abc"`, `""`, `"150"`, `"-5"`): non-numeric is rejected with 400, out-of-range is clamped. Pinned in Task 1 (`parseRollout`) and the Task 4 smoke test (`POST rollout=abc` → 400).
- **Uppercase, braced or garbage install IDs**: uppercase is normalized, anything else is treated as legacy (never offered below 100%). Pinned in Task 1 (`normalizeInstallId`).
- **D1 unavailable or throwing** must not break `update.json`. Pinned in Task 2 (`recordCheck` with a throwing fake DB resolves).
- **A password containing `:`**: Basic auth splits at the first colon only. Pinned in Task 3 (`isAuthorized`).

---

## File Structure

| File | Responsibility |
|---|---|
| `updates-worker/src/rollout.ts` (new) | Pure logic: install ID validation, rollout parsing, bucketing, offer decision, UTC day helpers |
| `updates-worker/src/rollout.test.ts` (new) | Unit tests for the above |
| `updates-worker/src/stats.ts` (new) | D1 access: record a check, load per-package stats, delete old rows |
| `updates-worker/src/stats.test.ts` (new) | Unit tests with a fake D1 |
| `updates-worker/src/statsPage.ts` (new) | Basic-auth check, active-device summary, HTML rendering |
| `updates-worker/src/statsPage.test.ts` (new) | Unit tests for auth, summary and rendering |
| `updates-worker/migrations/0001_stats.sql` (new) | D1 schema |
| `updates-worker/src/index.ts` (modify) | Routing: `update.json` recording + rollout gate, `/stats`, `/stats/rollout`, cron |
| `updates-worker/wrangler.toml` (modify) | D1 binding, cron trigger |
| `updates-worker/test/smoke.mjs` (new) | End-to-end test against `wrangler dev` |
| `updates-worker/.gitignore` (new) | `.dev.vars`, `.wrangler` |
| `updates-worker/README.md` (modify) | Rollout and stats usage |
| `build.gradle` (modify) | `-Prollout` → manifest `rollout` |
| `TMessagesProj/src/main/java/app/exteraless/updates/AppUpdateController.java` (modify) | Install ID and headers |

---

### Task 0: Commit the tested update-flow work and create the D1 database

The update-flow changes (banner, progress, mandatory, what's new, APK reuse, `prepareUpdate`) are device-tested but uncommitted. Commit them first so this feature's commits are clean. Create the D1 database now, so `wrangler.toml` carries a real ID from the start and the publish tasks' `wrangler deploy` never sees a placeholder.

**Files:**
- Modify: `updates-worker/wrangler.toml`

- [ ] **Step 1: Commit the update-flow work**

```bash
cd /f/exteraless
git add TMessagesProj/src/main/java/app/exteraless/updates/ TMessagesProj/src/main/java/org/telegram/messenger/NotificationCenter.java TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BaseFragment.java TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java TMessagesProj/src/main/res/values/strings_exteraless_updates.xml TMessagesProj/src/main/res/values-ru-rRU/strings_exteraless_updates.xml build.gradle
git commit -m "Improve the update flow with a banner, progress, mandatory updates and what's new"
```

- [ ] **Step 2: Ask the user to confirm, then create the database**

Ask: "Create D1 database `tg-updates-stats` in your Cloudflare account now?" On yes:

```bash
cd /f/exteraless/updates-worker && npx wrangler d1 create tg-updates-stats
```

Expected: output containing `"database_id": "<uuid>"`.

- [ ] **Step 3: Add the binding and cron to `wrangler.toml`**

Append (with the real `database_id` from Step 2):

```toml

[[d1_databases]]
binding = "STATS"
database_name = "tg-updates-stats"
database_id = "<uuid from step 2>"
migrations_dir = "migrations"

[triggers]
crons = ["17 3 * * *"]
```

- [ ] **Step 4: Commit**

```bash
git add updates-worker/wrangler.toml
git commit -m "Bind the update worker to the stats D1 database"
```

---

### Task 1: Rollout logic

**Files:**
- Create: `updates-worker/src/rollout.ts`
- Test: `updates-worker/src/rollout.test.ts`

**Interfaces:**
- Produces:
  - `normalizeInstallId(value: string | null | undefined): string | null`
  - `parseRollout(value: unknown): number | null`
  - `bucketFor(installId: string, versionCode: number): Promise<number>`
  - `shouldOffer(rollout: number, installId: string | null, versionCode: number): Promise<boolean>`
  - `utcDay(date: Date): string`
  - `daysAgo(date: Date, days: number): string`

- [ ] **Step 1: Write the failing tests**

`updates-worker/src/rollout.test.ts`:

```ts
import { test } from "node:test";
import assert from "node:assert/strict";
import { bucketFor, daysAgo, normalizeInstallId, parseRollout, shouldOffer, utcDay } from "./rollout.ts";

const ID = "3f2b8c1e-9a4d-4e7b-8c21-5d6e7f8a9b0c";

test("normalizeInstallId accepts UUIDs and lowercases them", () => {
    assert.equal(normalizeInstallId(ID), ID);
    assert.equal(normalizeInstallId(ID.toUpperCase()), ID);
});

test("normalizeInstallId rejects anything that is not a bare UUID", () => {
    for (const value of [null, undefined, "", "abc", `{${ID}}`, `${ID} `, ID.replace(/-/g, "")]) {
        assert.equal(normalizeInstallId(value), null, String(value));
    }
});

test("parseRollout accepts numbers and numeric strings, clamped and rounded", () => {
    assert.equal(parseRollout(20), 20);
    assert.equal(parseRollout("20"), 20);
    assert.equal(parseRollout(" 50 "), 50);
    assert.equal(parseRollout("20.5"), 21);
    assert.equal(parseRollout(150), 100);
    assert.equal(parseRollout("-5"), 0);
});

test("parseRollout rejects missing and non-numeric values", () => {
    for (const value of [undefined, null, "", "abc", "20%", NaN, Infinity, {}]) {
        assert.equal(parseRollout(value), null, String(value));
    }
});

test("a manifest without rollout offers the update to everyone", async () => {
    const rollout = parseRollout(undefined) ?? 100;
    assert.equal(await shouldOffer(rollout, null, 1276), true);
    assert.equal(await shouldOffer(rollout, ID, 1276), true);
});

test("bucketFor is stable and within 0-99", async () => {
    const bucket = await bucketFor(ID, 1276);
    assert.equal(await bucketFor(ID, 1276), bucket);
    assert.ok(bucket >= 0 && bucket < 100);
});

test("shouldOffer respects 0, 100 and legacy clients", async () => {
    assert.equal(await shouldOffer(0, ID, 1276), false);
    assert.equal(await shouldOffer(100, null, 1276), true);
    assert.equal(await shouldOffer(20, null, 1276), false);
    assert.equal(await shouldOffer(20, ID, 1276), (await bucketFor(ID, 1276)) < 20);
});

test("about 20% of random installs are offered at rollout 20", async () => {
    let offered = 0;
    for (let i = 0; i < 2000; i++) {
        if (await shouldOffer(20, crypto.randomUUID(), 1276)) {
            offered++;
        }
    }
    assert.ok(offered > 300 && offered < 500, `offered ${offered} of 2000`);
});

test("buckets differ between versions", async () => {
    let differ = 0;
    for (let i = 0; i < 50; i++) {
        const id = crypto.randomUUID();
        if ((await bucketFor(id, 1276)) !== (await bucketFor(id, 1277))) {
            differ++;
        }
    }
    assert.ok(differ > 40, `only ${differ} of 50 differ`);
});

test("utcDay and daysAgo use UTC dates", () => {
    const now = new Date("2026-09-28T23:30:00-03:00");
    assert.equal(utcDay(now), "2026-09-29");
    assert.equal(daysAgo(now, 6), "2026-09-23");
});
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `cd /f/exteraless/updates-worker && node --test src/rollout.test.ts`
Expected: FAIL with `Cannot find module` for `./rollout.ts`.

- [ ] **Step 3: Implement `updates-worker/src/rollout.ts`**

```ts
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NUMBER_PATTERN = /^\s*-?\d+(\.\d+)?\s*$/;
const DAY_MS = 24 * 60 * 60 * 1000;

export function normalizeInstallId(value: string | null | undefined): string | null {
    return typeof value === "string" && UUID_PATTERN.test(value) ? value.toLowerCase() : null;
}

function clampRollout(value: number): number {
    return Math.min(100, Math.max(0, Math.round(value)));
}

export function parseRollout(value: unknown): number | null {
    if (typeof value === "number") {
        return Number.isFinite(value) ? clampRollout(value) : null;
    }
    if (typeof value === "string" && NUMBER_PATTERN.test(value)) {
        return clampRollout(Number(value));
    }
    return null;
}

export async function bucketFor(installId: string, versionCode: number): Promise<number> {
    const data = new TextEncoder().encode(`${installId}:${versionCode}`);
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
    const value = ((digest[0] << 24) | (digest[1] << 16) | (digest[2] << 8) | digest[3]) >>> 0;
    return value % 100;
}

export async function shouldOffer(rollout: number, installId: string | null, versionCode: number): Promise<boolean> {
    if (rollout >= 100) {
        return true;
    }
    if (rollout <= 0 || installId === null) {
        return false;
    }
    return (await bucketFor(installId, versionCode)) < rollout;
}

export function utcDay(date: Date): string {
    return date.toISOString().slice(0, 10);
}

export function daysAgo(date: Date, days: number): string {
    return utcDay(new Date(date.getTime() - days * DAY_MS));
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `cd /f/exteraless/updates-worker && node --test src/rollout.test.ts`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add updates-worker/src/rollout.ts updates-worker/src/rollout.test.ts
git commit -m "Add staged rollout bucketing for the update worker"
```

---

### Task 2: D1 stats storage

**Files:**
- Create: `updates-worker/migrations/0001_stats.sql`
- Create: `updates-worker/src/stats.ts`
- Test: `updates-worker/src/stats.test.ts`

**Interfaces:**
- Consumes: `utcDay`, `daysAgo` from `./rollout.ts`
- Produces:
  - `interface CheckRecord { installId: string | null; packageName: string; versionCode: number }`
  - `interface VersionCount { versionCode: number; devices: number }`
  - `interface DailyCount { day: string; versionCode: number; devices: number }`
  - `interface PackageStats { daily: DailyCount[]; active7d: VersionCount[]; legacy7d: number }`
  - `recordCheck(db: D1Database, record: CheckRecord, now: Date): Promise<void>` (never throws)
  - `loadPackageStats(db: D1Database, packageName: string, now: Date): Promise<PackageStats>`
  - `deleteOldStats(db: D1Database, now: Date): Promise<void>`

- [ ] **Step 1: Write the migration `updates-worker/migrations/0001_stats.sql`**

```sql
CREATE TABLE checks (
    day TEXT NOT NULL,
    install_id TEXT NOT NULL,
    package TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    PRIMARY KEY (day, install_id, package)
);
CREATE INDEX checks_package_day ON checks (package, day);

CREATE TABLE legacy_checks (
    day TEXT NOT NULL,
    package TEXT NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY (day, package)
);
```

- [ ] **Step 2: Write the failing tests `updates-worker/src/stats.test.ts`**

```ts
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
```

- [ ] **Step 3: Run the tests and verify they fail**

Run: `cd /f/exteraless/updates-worker && node --test src/stats.test.ts`
Expected: FAIL with `Cannot find module` for `./stats.ts`.

- [ ] **Step 4: Implement `updates-worker/src/stats.ts`**

```ts
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
```

- [ ] **Step 5: Run the tests and verify they pass**

Run: `cd /f/exteraless/updates-worker && node --test src/stats.test.ts`
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add updates-worker/migrations/0001_stats.sql updates-worker/src/stats.ts updates-worker/src/stats.test.ts
git commit -m "Add D1 storage for update check stats"
```

---

### Task 3: Stats page rendering and auth

**Files:**
- Create: `updates-worker/src/statsPage.ts`
- Test: `updates-worker/src/statsPage.test.ts`

**Interfaces:**
- Consumes: `PackageStats`, `VersionCount`, `DailyCount` from `./stats.ts`
- Produces:
  - `isAuthorized(authorization: string | null, password: string): boolean`
  - `interface SummaryRow { label: string; devices: number; share: number }`
  - `summarizeActive(active: VersionCount[]): { rows: SummaryRow[]; total: number }`
  - `interface ChannelView { channel: "release" | "beta"; title: string; liveVersionCode: number | null; liveVersionName: string | null; rollout: number; stats: PackageStats }`
  - `renderStatsPage(channels: ChannelView[], today: string): string`

- [ ] **Step 1: Write the failing tests `updates-worker/src/statsPage.test.ts`**

```ts
import { test } from "node:test";
import assert from "node:assert/strict";
import { isAuthorized, renderStatsPage, summarizeActive, type ChannelView } from "./statsPage.ts";

const basic = (user: string, password: string) => "Basic " + btoa(`${user}:${password}`);

test("isAuthorized checks only the password", () => {
    assert.equal(isAuthorized(basic("anyone", "s3cret"), "s3cret"), true);
    assert.equal(isAuthorized(basic("", "s3cret"), "s3cret"), true);
    assert.equal(isAuthorized(basic("anyone", "wrong"), "s3cret"), false);
});

test("isAuthorized splits at the first colon so passwords may contain colons", () => {
    assert.equal(isAuthorized(basic("me", "a:b:c"), "a:b:c"), true);
});

test("isAuthorized rejects missing or malformed headers", () => {
    for (const header of [null, "", "Bearer x", "Basic !!!notbase64", "Basic " + btoa("nocolon")]) {
        assert.equal(isAuthorized(header, "s3cret"), false, String(header));
    }
});

test("summarizeActive computes shares and groups versions under 1% as older", () => {
    const summary = summarizeActive([
        { versionCode: 1276, devices: 150 },
        { versionCode: 1275, devices: 49 },
        { versionCode: 1270, devices: 1 }
    ]);
    assert.equal(summary.total, 200);
    assert.deepEqual(summary.rows, [
        { label: "1276", devices: 150, share: 75 },
        { label: "1275", devices: 49, share: 24.5 },
        { label: "older", devices: 1, share: 0.5 }
    ]);
});

test("summarizeActive handles no data", () => {
    assert.deepEqual(summarizeActive([]), { rows: [], total: 0 });
});

const view = (overrides: Partial<ChannelView> = {}): ChannelView => ({
    channel: "beta",
    title: "LibreTGB",
    liveVersionCode: 1276,
    liveVersionName: "12.10.12.3",
    rollout: 20,
    stats: {
        daily: [{ day: "2026-09-28", versionCode: 1276, devices: 2 }],
        active7d: [{ versionCode: 1276, devices: 2 }],
        legacy7d: 5
    },
    ...overrides
});

test("renderStatsPage shows version, rollout, counts and rollout forms", () => {
    const html = renderStatsPage([view()], "2026-09-28");
    assert.match(html, /LibreTGB/);
    assert.match(html, /12\.10\.12\.3 \(1276\)/);
    assert.match(html, /20%/);
    assert.match(html, /<svg/);
    assert.match(html, /action="\/stats\/rollout"/);
    assert.match(html, /name="channel" value="beta"/);
    assert.match(html, /5 checks/);
});

test("renderStatsPage escapes manifest text", () => {
    const html = renderStatsPage([view({ liveVersionName: "<script>x</script>" })], "2026-09-28");
    assert.doesNotMatch(html, /<script>x<\/script>/);
    assert.match(html, /&lt;script&gt;/);
});

test("renderStatsPage handles a channel with no manifest and no data", () => {
    const html = renderStatsPage([view({ liveVersionCode: null, liveVersionName: null, stats: { daily: [], active7d: [], legacy7d: 0 } })], "2026-09-28");
    assert.match(html, /not published/);
    assert.match(html, /No devices yet/);
});
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `cd /f/exteraless/updates-worker && node --test src/statsPage.test.ts`
Expected: FAIL with `Cannot find module` for `./statsPage.ts`.

- [ ] **Step 3: Implement `updates-worker/src/statsPage.ts`**

```ts
import type { DailyCount, PackageStats, VersionCount } from "./stats.ts";

export interface SummaryRow {
    label: string;
    devices: number;
    share: number;
}

export interface ChannelView {
    channel: "release" | "beta";
    title: string;
    liveVersionCode: number | null;
    liveVersionName: string | null;
    rollout: number;
    stats: PackageStats;
}

const COLORS = ["#3f9be8", "#34c47c", "#f5a524", "#e5484d", "#8e4ec6", "#12a594"];
const OLDER_COLOR = "#8b8d98";

export function isAuthorized(authorization: string | null, password: string): boolean {
    if (!authorization || !authorization.startsWith("Basic ")) {
        return false;
    }
    let decoded: string;
    try {
        decoded = atob(authorization.slice(6).trim());
    } catch {
        return false;
    }
    const separator = decoded.indexOf(":");
    if (separator < 0) {
        return false;
    }
    return constantTimeEquals(decoded.slice(separator + 1), password);
}

function constantTimeEquals(a: string, b: string): boolean {
    if (a.length !== b.length) {
        return false;
    }
    let difference = 0;
    for (let i = 0; i < a.length; i++) {
        difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
    }
    return difference === 0;
}

function round1(value: number): number {
    return Math.round(value * 10) / 10;
}

export function summarizeActive(active: VersionCount[]): { rows: SummaryRow[]; total: number } {
    const total = active.reduce((sum, row) => sum + row.devices, 0);
    if (total === 0) {
        return { rows: [], total: 0 };
    }
    const rows: SummaryRow[] = [];
    let older = 0;
    for (const row of [...active].sort((a, b) => b.versionCode - a.versionCode)) {
        const share = row.devices * 100 / total;
        if (share < 1) {
            older += row.devices;
        } else {
            rows.push({ label: String(row.versionCode), devices: row.devices, share: round1(share) });
        }
    }
    if (older > 0) {
        rows.push({ label: "older", devices: older, share: round1(older * 100 / total) });
    }
    return { rows, total };
}

function escapeHtml(value: string): string {
    return value
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

function lastDays(today: string, count: number): string[] {
    const end = new Date(`${today}T00:00:00Z`).getTime();
    const days: string[] = [];
    for (let i = count - 1; i >= 0; i--) {
        days.push(new Date(end - i * 86400000).toISOString().slice(0, 10));
    }
    return days;
}

function renderChart(daily: DailyCount[], today: string): string {
    const days = lastDays(today, 30);
    const versions = [...new Set(daily.map(row => row.versionCode))].sort((a, b) => b - a);
    const colorOf = (versionCode: number) => {
        const index = versions.indexOf(versionCode);
        return index >= 0 && index < COLORS.length ? COLORS[index] : OLDER_COLOR;
    };
    const perDay = new Map<string, DailyCount[]>();
    for (const row of daily) {
        perDay.set(row.day, [...(perDay.get(row.day) ?? []), row]);
    }
    const max = Math.max(1, ...days.map(day => (perDay.get(day) ?? []).reduce((sum, row) => sum + row.devices, 0)));
    const width = 600;
    const height = 160;
    const barWidth = width / days.length;
    const bars: string[] = [];
    days.forEach((day, index) => {
        let y = height;
        for (const row of (perDay.get(day) ?? []).sort((a, b) => a.versionCode - b.versionCode)) {
            const barHeight = row.devices * height / max;
            y -= barHeight;
            bars.push(`<rect x="${(index * barWidth + 1).toFixed(1)}" y="${y.toFixed(1)}" width="${(barWidth - 2).toFixed(1)}" height="${barHeight.toFixed(1)}" fill="${colorOf(row.versionCode)}"><title>${day} · ${row.versionCode}: ${row.devices}</title></rect>`);
        }
    });
    const legend = versions.map(version => `<span class="key"><i style="background:${colorOf(version)}"></i>${version}</span>`).join("");
    return `<svg viewBox="0 0 ${width} ${height}" role="img" aria-label="Unique devices per day, last 30 days">${bars.join("")}</svg>`
        + `<div class="legend">${legend}<span class="axis">max ${max}/day</span></div>`;
}

function renderChannel(view: ChannelView, today: string): string {
    const live = view.liveVersionCode === null
        ? "not published"
        : `${escapeHtml(view.liveVersionName ?? String(view.liveVersionCode))} (${view.liveVersionCode})`;
    const summary = summarizeActive(view.stats.active7d);
    const rows = summary.rows.length === 0
        ? `<p class="muted">No devices yet</p>`
        : `<table><tr><th>Version</th><th>Devices</th><th>Share</th></tr>${summary.rows.map(row =>
            `<tr><td>${escapeHtml(row.label)}</td><td>${row.devices}</td><td>${row.share}%</td></tr>`).join("")}</table>`;
    const channel = escapeHtml(view.channel);
    const quick = [0, 20, 50, 100].map(value =>
        `<button name="rollout" value="${value}"${value === view.rollout ? " class=\"current\"" : ""}>${value === 0 ? "Pause" : value + "%"}</button>`).join("");
    return `<section>
<h2>${escapeHtml(view.title)}</h2>
<p>Live: <b>${live}</b> · Rollout: <b>${view.rollout}%</b></p>
<form method="post" action="/stats/rollout" class="rollout"><input type="hidden" name="channel" value="${channel}">${quick}</form>
<form method="post" action="/stats/rollout" class="rollout"><input type="hidden" name="channel" value="${channel}"><input type="number" name="rollout" min="0" max="100" value="${view.rollout}" required><button>Set</button></form>
<h3>Active devices, last 7 days (${summary.total})</h3>
${rows}
<p class="muted">Older app versions without an install ID: ${view.stats.legacy7d} checks (not unique)</p>
<h3>Unique devices per day, last 30 days</h3>
${renderChart(view.stats.daily, today)}
</section>`;
}

export function renderStatsPage(channels: ChannelView[], today: string): string {
    return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>LibreTG stats</title>
<style>
:root { --bg: #ffffff; --fg: #1c2024; --muted: #60646c; --line: #e0e1e6; --accent: #3f9be8; }
@media (prefers-color-scheme: dark) { :root { --bg: #111418; --fg: #edeef0; --muted: #b0b4ba; --line: #2e3135; } }
body { background: var(--bg); color: var(--fg); font: 15px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 680px; padding: 16px; }
section { border-top: 1px solid var(--line); padding: 8px 0 16px; }
table { border-collapse: collapse; width: 100%; }
th, td { border-bottom: 1px solid var(--line); padding: 4px 8px; text-align: left; }
.muted, .axis { color: var(--muted); }
.rollout { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0; }
button, input { font: inherit; padding: 6px 12px; border-radius: 8px; border: 1px solid var(--line); background: transparent; color: var(--fg); }
button.current { background: var(--accent); border-color: var(--accent); color: #fff; }
svg { width: 100%; height: auto; display: block; }
.legend { display: flex; flex-wrap: wrap; gap: 12px; font-size: 13px; margin-top: 4px; }
.key i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 4px; }
</style>
</head>
<body>
<h1>LibreTG stats</h1>
<p class="muted">As of ${escapeHtml(today)} (UTC)</p>
${channels.map(view => renderChannel(view, today)).join("\n")}
</body>
</html>`;
}
```

- [ ] **Step 4: Run the tests and verify they pass**

Run: `cd /f/exteraless/updates-worker && node --test src/statsPage.test.ts`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add updates-worker/src/statsPage.ts updates-worker/src/statsPage.test.ts
git commit -m "Add the update stats page renderer"
```

---

### Task 4: Wire the worker together and smoke-test it locally

**Files:**
- Modify: `updates-worker/src/index.ts`
- Create: `updates-worker/.gitignore`
- Create: `updates-worker/test/smoke.mjs`
- Modify: `updates-worker/README.md`

**Interfaces:**
- Consumes:
  - from `./rollout.ts`: `normalizeInstallId`, `parseRollout`, `shouldOffer`, `utcDay`
  - from `./stats.ts`: `recordCheck`, `loadPackageStats`, `deleteOldStats`
  - from `./statsPage.ts`: `isAuthorized`, `renderStatsPage`, `ChannelView`
- Produces: HTTP behavior per the spec (`/update.json` gate and recording, `/stats`, `POST /stats/rollout`, daily cron).

- [ ] **Step 1: Write the smoke test `updates-worker/test/smoke.mjs`**

```js
// Runs against `wrangler dev` (see README). Uses the local .dev.vars password, never the real one.
import assert from "node:assert/strict";

const BASE = process.env.WORKER_URL ?? "http://127.0.0.1:8787";
const AUTH = "Basic " + btoa("me:local-test");
const BETA = "com.de.xxtg.beta";

async function check(installId) {
    const headers = { "X-LibreTG-Package": BETA, "X-LibreTG-Version": "1274" };
    if (installId) {
        headers["X-LibreTG-Install"] = installId;
    }
    const response = await fetch(`${BASE}/update.json?package=${BETA}`, { headers });
    assert.equal(response.status, 200);
    return typeof (await response.json()).versionCode === "number";
}

async function setRollout(value, origin = BASE) {
    const headers = { Authorization: AUTH, "Content-Type": "application/x-www-form-urlencoded" };
    if (origin) {
        headers.Origin = origin;
    }
    return fetch(`${BASE}/stats/rollout`, {
        method: "POST",
        headers,
        body: `channel=beta&rollout=${encodeURIComponent(value)}`,
        redirect: "manual"
    });
}

assert.equal((await setRollout("20")).status, 303, "set rollout 20");

let offered = 0;
for (let i = 0; i < 500; i++) {
    if (await check(crypto.randomUUID())) {
        offered++;
    }
}
console.log(`offered at 20%: ${offered}/500`);
assert.ok(offered > 60 && offered < 140, `offered ${offered}/500`);

const id = crypto.randomUUID();
const first = await check(id);
assert.equal(await check(id), first, "stable per install");
assert.equal(await check(id.toUpperCase()), first, "uppercase ID is the same install");
assert.equal(await check(null), false, "legacy not offered below 100");
assert.equal(await check("not-a-uuid"), false, "junk ID treated as legacy");

assert.equal((await fetch(`${BASE}/stats`)).status, 401, "stats needs auth");
assert.equal((await fetch(`${BASE}/stats`, { headers: { Authorization: "Basic " + btoa("me:wrong") } })).status, 401);
const page = await fetch(`${BASE}/stats`, { headers: { Authorization: AUTH } });
assert.equal(page.status, 200);
const html = await page.text();
assert.match(html, /LibreTGB/);
assert.match(html, /Rollout: <b>20%<\/b>/);

assert.equal((await setRollout("abc")).status, 400, "non-numeric rollout rejected");
assert.equal((await setRollout("100", null)).status, 403, "missing Origin rejected");
assert.equal((await setRollout("100", "https://evil.example")).status, 403, "foreign Origin rejected");
assert.equal((await setRollout("100")).status, 303);
assert.equal(await check(null), true, "legacy offered at 100");

assert.equal((await fetch(`${BASE}/update.json`, { method: "POST" })).status, 405);
console.log("smoke test passed");
```

- [ ] **Step 2: Create `updates-worker/.gitignore`**

```
.wrangler/
.dev.vars
```

- [ ] **Step 3: Set up the local environment and verify the smoke test fails against the current worker**

```bash
cd /f/exteraless/updates-worker
printf 'STATS_PASSWORD=local-test\n' > .dev.vars
npx wrangler d1 migrations apply tg-updates-stats --local
sed 's/"versionCode": [0-9]*/"versionCode": 1276/' manifests/beta.json > .wrangler/beta-test.json
npx wrangler r2 object put tg-updates/manifests/beta.json --file .wrangler/beta-test.json --content-type application/json --local
```

Start `npx wrangler dev --port 8787` in the background, then run:

Run: `node test/smoke.mjs`
Expected: FAIL at `set rollout 20` (the current worker returns 405 for POST).

- [ ] **Step 4: Rewrite `updates-worker/src/index.ts`**

Keep `json`, `decodeXml`, `readTag` and `readLatestChangelog` unchanged. Replace the `Env` and `UpdateManifest` interfaces, `readManifest` and the default export, and add the imports and helpers:

```ts
import { normalizeInstallId, parseRollout, shouldOffer, utcDay } from "./rollout.ts";
import { deleteOldStats, loadPackageStats, recordCheck } from "./stats.ts";
import { isAuthorized, renderStatsPage, type ChannelView } from "./statsPage.ts";

export interface Env {
    UPDATES: R2Bucket;
    STATS: D1Database;
    STATS_PASSWORD?: string;
}

interface UpdateManifest {
    packageName: string;
    versionCode: number;
    versionName: string;
    title: string;
    releaseNotes: string;
    apkUrl: string;
    sha256: string;
    mandatory: boolean;
    rollout?: number;
}

type Channel = "release" | "beta";

const CHANNELS: { channel: Channel; packageName: string; title: string }[] = [
    { channel: "release", packageName: "com.de.xxtg", title: "LibreTG" },
    { channel: "beta", packageName: "com.de.xxtg.beta", title: "LibreTGB" }
];
```

```ts
async function readManifest(env: Env, channel: Channel): Promise<UpdateManifest | null> {
    const object = await env.UPDATES.get(`manifests/${channel}.json`);
    if (!object) {
        return null;
    }
    return JSON.parse(await object.text()) as UpdateManifest;
}

async function writeManifest(env: Env, channel: Channel, manifest: UpdateManifest): Promise<void> {
    await env.UPDATES.put(`manifests/${channel}.json`, JSON.stringify(manifest, null, 4) + "\n", {
        httpMetadata: { contentType: "application/json; charset=utf-8", cacheControl: "no-cache" }
    });
}

async function handleUpdateCheck(request: Request, env: Env, ctx: ExecutionContext, url: URL): Promise<Response> {
    const packageName = request.headers.get("X-LibreTG-Package")
        || url.searchParams.get("package")
        || "com.de.xxtg.beta";
    const channel: Channel = packageName === "com.de.xxtg" ? "release" : "beta";
    const installId = normalizeInstallId(request.headers.get("X-LibreTG-Install"));
    const clientVersion = Number.parseInt(request.headers.get("X-LibreTG-Version") ?? "", 10);
    ctx.waitUntil(recordCheck(env.STATS, {
        installId,
        packageName,
        versionCode: Number.isFinite(clientVersion) ? clientVersion : 0
    }, new Date()));

    const manifest = await readManifest(env, channel);
    if (!manifest) {
        return json({ error: "Update manifest not uploaded" }, "no-store");
    }
    const rollout = parseRollout(manifest.rollout) ?? 100;
    if (!await shouldOffer(rollout, installId, manifest.versionCode)) {
        return json({ error: "No update" }, "no-store");
    }
    const changelog = await readLatestChangelog(env, request.headers.get("Accept-Language") || "en");
    const update = { ...manifest };
    if (changelog?.title) {
        update.title = changelog.title;
    }
    if (changelog?.description) {
        update.releaseNotes = changelog.description;
    }
    return json(update);
}

async function handleStats(request: Request, env: Env, url: URL): Promise<Response> {
    if (!env.STATS_PASSWORD) {
        return new Response("Stats password is not configured", { status: 503 });
    }
    if (!isAuthorized(request.headers.get("Authorization"), env.STATS_PASSWORD)) {
        return new Response("Authentication required", {
            status: 401,
            headers: { "www-authenticate": 'Basic realm="LibreTG stats", charset="UTF-8"' }
        });
    }
    if (url.pathname === "/stats/rollout" && request.method === "POST") {
        return updateRollout(request, env, url);
    }
    if (url.pathname !== "/stats") {
        return json({ error: "Not found" }, "no-store");
    }
    const now = new Date();
    const views: ChannelView[] = await Promise.all(CHANNELS.map(async ({ channel, packageName, title }) => {
        const manifest = await readManifest(env, channel);
        return {
            channel,
            title,
            liveVersionCode: manifest?.versionCode ?? null,
            liveVersionName: manifest?.versionName ?? null,
            rollout: parseRollout(manifest?.rollout) ?? 100,
            stats: await loadPackageStats(env.STATS, packageName, now)
        };
    }));
    return new Response(renderStatsPage(views, utcDay(now)), {
        headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" }
    });
}

async function updateRollout(request: Request, env: Env, url: URL): Promise<Response> {
    if (request.headers.get("Origin") !== url.origin) {
        return new Response("Cross-origin request rejected", { status: 403 });
    }
    const form = await request.formData();
    const channel = form.get("channel");
    const rollout = parseRollout(form.get("rollout"));
    if ((channel !== "release" && channel !== "beta") || rollout === null) {
        return new Response("Invalid channel or rollout", { status: 400 });
    }
    const manifest = await readManifest(env, channel);
    if (!manifest) {
        return new Response("Manifest not uploaded", { status: 404 });
    }
    manifest.rollout = rollout;
    await writeManifest(env, channel, manifest);
    return Response.redirect(`${url.origin}/stats`, 303);
}

export default {
    async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
        const url = new URL(request.url);
        const isStats = url.pathname === "/stats" || url.pathname.startsWith("/stats/");
        const isRolloutPost = request.method === "POST" && url.pathname === "/stats/rollout";
        if (request.method !== "GET" && !isRolloutPost) {
            return new Response("Method Not Allowed", { status: 405 });
        }
        if (isStats) {
            return handleStats(request, env, url);
        }
        if (url.pathname === "/health") {
            return json({ ok: true });
        }
        if (url.pathname === "/update.json") {
            return handleUpdateCheck(request, env, ctx, url);
        }
        // /changelog.xml and APK routes stay exactly as before.
    },

    async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
        ctx.waitUntil(deleteOldStats(env.STATS, new Date()));
    }
};
```

The comment line in `fetch` stands for the existing `/changelog.xml`, `/app.apk` / `/app-beta.apk` / `/app-release.apk` and final `Not found` blocks, which are moved there unchanged.

- [ ] **Step 5: Restart `wrangler dev` and run the smoke test until it passes**

Run: `node test/smoke.mjs`
Expected: `offered at 20%: <60-140>/500`, then `smoke test passed`.

- [ ] **Step 6: Run all unit tests**

Run: `node --test src/`
Expected: all tests pass.

- [ ] **Step 7: Document rollout and stats in `updates-worker/README.md`**

Append:

```markdown
## Staged rollout

`publishReleaseUpdate` / `publishDebugUpdate` publish to 100% of devices. Pass `-Prollout=20` to offer the new version to about 20% of devices first. Devices are picked by a hash of their install ID and the version code, so a device is in or out consistently for one version. Raise, lower or pause (0%) the rollout on the stats page. Any publish resets the rollout to its `-Prollout` value. App versions older than 12.10.12.3 send no install ID and get the update only at 100%.

## Stats

`https://tg.upd.dexx.moe/stats` (any username, password = worker secret `STATS_PASSWORD`) shows active devices per version over the last 7 days, a 30-day chart, and the rollout controls. Data lives in the D1 database `tg-updates-stats` (one row per device per day, kept 90 days).

Local testing:

    printf 'STATS_PASSWORD=local-test\n' > .dev.vars
    npx wrangler d1 migrations apply tg-updates-stats --local
    npx wrangler dev          # then, in another shell:
    node test/smoke.mjs
    node --test src/
```

- [ ] **Step 8: Commit**

```bash
git add updates-worker/src/index.ts updates-worker/.gitignore updates-worker/test/smoke.mjs updates-worker/README.md
git commit -m "Serve staged rollouts and the stats page from the update worker"
```

---

### Task 5: `-Prollout` on the publish tasks

**Files:**
- Modify: `build.gradle` (`registerUpdatePublisher`)

**Interfaces:**
- Produces: the manifest JSON gains `"rollout": <int 0-100>`, which the Task 4 worker reads.

- [ ] **Step 1: Verify the current behaviour (no validation)**

Run: `cd /f/exteraless && ./gradlew publishDebugUpdate --dry-run -Prollout=abc`
Expected: BUILD SUCCESSFUL (the property is ignored today).

- [ ] **Step 2: Parse `-Prollout` when the task is configured**

Inside `tasks.register(taskName) { ... }`, before `doLast`, add:

```groovy
        String rolloutProperty = project.findProperty('rollout')?.toString()
        int rollout = 100
        if (rolloutProperty != null) {
            if (!rolloutProperty.isInteger()) {
                throw new GradleException("-Prollout must be an integer from 0 to 100, got '${rolloutProperty}'")
            }
            rollout = Math.max(0, Math.min(100, Integer.parseInt(rolloutProperty)))
        }
```

In the `manifest` map, after `mandatory: false`, add `rollout: rollout`. Change the final lifecycle line to:

```groovy
            logger.lifecycle("Published ${packageName} ${versionName} (${versionCode}) to ${rollout}% of devices, SHA256 ${sha256}")
```

- [ ] **Step 3: Verify validation**

Run: `./gradlew publishDebugUpdate --dry-run -Prollout=abc`
Expected: FAIL with `-Prollout must be an integer from 0 to 100, got 'abc'`.

Run: `./gradlew publishDebugUpdate --dry-run -Prollout=20`
Expected: BUILD SUCCESSFUL, with tasks listed as SKIPPED.

- [ ] **Step 4: Commit**

```bash
git add build.gradle
git commit -m "Add -Prollout to the update publish tasks"
```

---

### Task 6: App sends its install ID and version

**Files:**
- Modify: `TMessagesProj/src/main/java/app/exteraless/updates/AppUpdateController.java`

**Interfaces:**
- Produces: the `X-LibreTG-Install` (lowercase UUID) and `X-LibreTG-Version` (`BuildConfig.VERSION_CODE`) headers on `update.json` requests only.

- [ ] **Step 1: Add the install ID**

Add a constant next to `KEY_LAST_VERSION_CODE`:

```java
    private static final String KEY_INSTALL_ID = "installId";
```

Add a method after `getTotalBytes()`:

```java
    // Случайный идентификатор установки для раскатки и статистики: не связан с аккаунтом и сбрасывается вместе с данными приложения.
    private static String getInstallId() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String installId = prefs.getString(KEY_INSTALL_ID, null);
        if (installId == null) {
            installId = java.util.UUID.randomUUID().toString();
            prefs.edit().putString(KEY_INSTALL_ID, installId).apply();
        }
        return installId;
    }
```

- [ ] **Step 2: Send the headers only for `update.json`**

Change the `httpGet` signature to `private static String httpGet(String url, String accept, boolean identify) throws Exception` and, after the `X-LibreTG-Package` header, add:

```java
        if (identify) {
            connection.setRequestProperty("X-LibreTG-Install", getInstallId());
            connection.setRequestProperty("X-LibreTG-Version", Integer.toString(BuildConfig.VERSION_CODE));
        }
```

Update the callers: the two `loadManifest` calls pass `true`, and the two changelog calls in `checkWhatsNew` pass `false`.

- [ ] **Step 3: Compile**

Run: `cd /f/exteraless && NATIVE_TARGET=arm64-v8a ./gradlew :TMessagesProj:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add TMessagesProj/src/main/java/app/exteraless/updates/AppUpdateController.java
git commit -m "Send an install ID with update checks for staged rollouts"
```

The on-device check happens in Task 7, once the worker is live.

---

### Task 7: Deploy and verify live (each command needs user confirmation)

- [ ] **Step 1: Ask, then apply the migration remotely**

Ask: "Apply the stats migration to the live `tg-updates-stats` database?" On yes:

```bash
cd /f/exteraless/updates-worker && npx wrangler d1 migrations apply tg-updates-stats --remote
```

Expected: `0001_stats.sql` applied.

- [ ] **Step 2: Ask, then deploy the worker**

Ask: "Deploy the new update worker? It serves live update checks. Existing apps keep getting updates, since manifests have no rollout field and count as 100%." On yes:

```bash
npx wrangler deploy
```

- [ ] **Step 3: Verify live behaviour without the password**

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://tg.upd.dexx.moe/stats
curl -s "https://tg.upd.dexx.moe/update.json?package=com.de.xxtg.beta" | head -c 200
```

Expected: `401`, then the beta manifest JSON with `versionCode` 1275 (offered, since the rollout is 100).

- [ ] **Step 4: Verify on the phone**

Build a debug APK at verCode 1274 (per the test-install workflow) and install it on `192.168.0.105:5555`. Open the app, then:

```bash
npx wrangler d1 execute tg-updates-stats --remote --command "SELECT day, package, version_code FROM checks"
```

Expected: a row `<today> | com.de.xxtg.beta | 1274`.

- [ ] **Step 5: The user opens `https://tg.upd.dexx.moe/stats`, logs in and checks the page**

Ask the user to set LibreTGB to Pause, relaunch the test app (the update banner should not appear), then set it back to 100%.

- [ ] **Step 6: Ask before pushing the commits to `dexx`**
