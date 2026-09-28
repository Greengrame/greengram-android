# Update Changelog

Edit `changelog.xml` before publishing a new beta build. The first RSS `<item>` is used for the update dialog:

- `<title>` becomes the update dialog title.
- `<description>` becomes the release notes text.

Run `publishDebugUpdate` after changing the item for LibreTGB, or `publishReleaseUpdate` for LibreTG. The task uploads the matching APK, manifest, and `changelog.xml` to the `tg-updates` R2 bucket, then deploys the Worker.

The app sends its package name and `Accept-Language` header. The Worker uses the beta or release manifest and selects the matching `<libre:lang>` RSS item.

## Staged rollout

`publishReleaseUpdate` / `publishDebugUpdate` publish to 100% of devices. To offer a version to only part of them, lower its rollout on the stats page: devices are picked by a hash of their install ID and the version code, so a device is consistently in or out for one version. Pause (0%) stops offering it. Publishing again resets the rollout to 100%. App versions older than 12.10.12.3 send no install ID and get the update only at 100%.

## Stats

`https://tg.upd.dexx.moe/stats` (any username, password = worker secret `STATS_PASSWORD`) shows active devices per version over the last 7 days, a 30-day chart, and the rollout controls. Data lives in the D1 database `tg-updates-stats` (one row per device per day, kept 90 days).

Local testing:

    printf 'STATS_PASSWORD=local-test\n' > .dev.vars
    npx wrangler d1 migrations apply tg-updates-stats --local
    npx wrangler dev          # then, in another shell:
    node test/smoke.mjs
    node --test "src/*.test.ts"

## Publishing without rebuilding

The publish tasks build the APK first. If you already built it (for example with `assembleRelease assembleDebug` to test it), pass `-PskipBuild` to publish the existing APK as is:

    NATIVE_TARGET=arm64-v8a ./gradlew publishReleaseUpdate publishDebugUpdate -PskipBuild

The task refuses to publish if the APK's version code doesn't match `verCode` in `TMessagesProj/build.gradle`.
