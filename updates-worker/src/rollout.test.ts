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
