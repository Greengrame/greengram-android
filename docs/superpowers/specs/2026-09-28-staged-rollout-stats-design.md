# Staged rollout and install stats

Date: 2026-09-28
Status: design approved in chat, pending spec review

## Goal

Limit how many users a bad LibreTG/LibreTGB release can reach, and see how many devices run each version.

- **Staged rollout:** a published version can be offered to only a percentage of devices, raised (or paused) later.
- **Install stats:** unique active devices per version per day, per channel.

Success looks like: publish 1276 at 20%, watch the `/stats` page show ~20% of active devices move to 1276, raise to 100% from the page, with no app update needed to control any of it.

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Where to view stats and control rollout | A password-protected page served by the update worker at `/stats` |
| How rollout grows | Publish defaults to 100%. Staged rollout is opt-in per publish (`-Prollout=20`) and changed manually on the page |
| Stats precision | Unique devices, via a random per-install ID |
| Storage | Cloudflare D1, bound to the existing `tg-updates` worker |

## Components

### 1. App: install ID (`AppUpdateController`)

- On first use, generate `UUID.randomUUID()` and store it in the `libretg_updates` shared preferences under `installId`.
- Send it as the `X-LibreTG-Install` header on `update.json` requests only (not on changelog or APK requests).
- The ID is not linked to the Telegram account or the device, and is reset when app data is cleared.

### 2. Worker: `/update.json`

For each request:

1. Read `packageName` as today. Pick the channel: `release` for `com.de.xxtg`, else `beta`.
2. Read `X-LibreTG-Install`. It counts as valid only if it is a canonical UUID. Anything else, including a missing header, counts as a **legacy** client.
3. Record the check with `ctx.waitUntil`, so the response isn't delayed:
   - Valid ID: upsert `checks(day, install_id, package, version_code)`. `version_code` comes from a new optional `X-LibreTG-Version` header, which the app sends with `BuildConfig.VERSION_CODE`. If it is missing, store 0.
   - Legacy: increment `legacy_checks(day, package)`.
4. Load the manifest. `rollout` is an integer 0–100, and a missing value means 100.
5. Decide whether this device is offered the update:
   - `rollout >= 100`: everyone.
   - `rollout <= 0`: nobody.
   - Otherwise only clients with a valid ID, and only when `bucket(installId, manifest.versionCode) < rollout`.
   - `bucket = first 4 bytes of SHA-256("<installId>:<versionCode>") as uint32, mod 100`.
6. Not offered: respond `{"error":"No update"}` with HTTP 200 and no `versionCode`. The app already treats any manifest without a newer `versionCode` as "no update", and with the new update flow it also removes the banner.
7. Offered: respond as today (manifest plus changelog title and notes).

`day` is the UTC date `YYYY-MM-DD`.

### 3. Worker: D1 schema (`updates-worker/migrations/0001_stats.sql`)

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

- The upsert is `ON CONFLICT DO UPDATE SET version_code = excluded.version_code`: if a device updates mid-day, it counts under its newest version.
- A daily cron trigger in the same worker deletes rows older than 90 days from both tables.
- D1's free tier allows 100k writes/day, which is roughly 10k active devices checking several times a day.

### 4. Worker: `/stats` page

- **Auth:** HTTP Basic auth on every `/stats*` route. The password is compared against the worker secret `STATS_PASSWORD` and the username is ignored. If the secret is unset, the route returns 503 rather than being left open.
- **`GET /stats`:** one server-rendered HTML page with no external scripts. For each channel it shows:
  - the live version and its current rollout %;
  - active devices over the last 7 days by version: count and share, with versions below 1% grouped as "older";
  - a 30-day inline-SVG stacked bar chart of unique devices per day by version;
  - legacy check counts over the last 7 days, labeled as not unique;
  - a rollout form: buttons for 0 / 20 / 50 / 100 and a number field.
- **`POST /stats/rollout`:** form fields `channel` and `rollout`.
  - Validates the channel (`release` or `beta`) and clamps rollout to 0–100.
  - Requires `Origin` to match the worker's own origin.
  - Rewrites `manifests/<channel>.json` in R2 with the new `rollout`, then redirects back to `/stats`.
- The method guard changes from "GET only" to "GET, plus POST on `/stats/rollout`".

### 5. Publish tasks (`build.gradle`)

- `registerUpdatePublisher` writes `rollout` into the manifest: `project.findProperty('rollout')` parsed as an int and clamped to 0–100, defaulting to 100.
- The local manifest file (committed as today) includes the `rollout` field.
- A publish always resets rollout to its `-Prollout` value (default 100), overriding whatever the page set for the previous version.

### 6. Worker config (`wrangler.toml`)

- `[[d1_databases]]` binding `STATS` → database `tg-updates-stats`.
- `[triggers] crons = ["17 3 * * *"]` for the daily cleanup.
- `migrations_dir = "migrations"` for the D1 binding.

## Edge cases

- **Old app versions** (no install ID): counted in `legacy_checks` only, and offered the update only at 100%.
- **Rollout lowered or paused:** devices that were offered the update but haven't downloaded it lose the offer on their next check, and the app clears the banner. Downloads already in progress finish normally.
- **Bucket independence:** being in the first 20% for 1276 says nothing about 1277, because the version is part of the hash.
- **Junk or forged IDs:** anything that isn't a UUID is treated as legacy. Forging many UUIDs could inflate stats, which is acceptable for a hobby project and means nothing is authenticated.
- **D1 unavailable:** stats writes are best-effort (`waitUntil` with errors caught and logged), and update checks never fail because of stats.
- **Privacy:** no IP addresses or account data are stored. Only the random ID, package, version and day.

## Testing

- **Worker, locally:** `wrangler dev` with a local D1 (migrations applied with `--local`).
  - Script 1,000 requests with random UUIDs at `rollout: 20` and assert an offered share of 15–25%.
  - Assert the same ID gets a stable answer across repeated requests.
  - Assert legacy requests are offered only at 100.
  - Assert `/stats` returns 401 without the password and 200 with it.
  - Assert `POST /stats/rollout` updates the manifest.
- **App:** a debug build on the test phone. Confirm a `checks` row appears with its install ID and version 1274, and that the update is offered or hidden correctly when rollout is set to 0 and then 100 on the page.

## Rollout of this feature (needs user confirmation at each step)

1. `wrangler d1 create tg-updates-stats`, which creates a resource in the user's Cloudflare account.
2. `wrangler d1 migrations apply tg-updates-stats --remote`.
3. The user runs `npx wrangler secret put STATS_PASSWORD` themselves.
4. `wrangler deploy`, which serves live update checks for all users. Old apps are unaffected at 100% rollout.
5. The next app release (12.10.12.3) ships the install ID, and stats start filling in as users update.

## Out of scope

- Automatic rollout schedules.
- A/B tests or feature flags (the same bucketing could support them later).
- Crash reporting (a separate idea; upstream already has `app.exteraless.crash`).
