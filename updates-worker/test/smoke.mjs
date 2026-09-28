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
