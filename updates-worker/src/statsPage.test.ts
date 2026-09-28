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
