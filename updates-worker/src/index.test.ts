import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "./index.ts";

const PASSWORD = "local-test";
const AUTH = "Basic " + btoa(`me:${PASSWORD}`);

function manifestObject(versionCode: number) {
    return { text: async () => JSON.stringify({ packageName: "com.de.xxtg", versionCode, versionName: "12.10.12.3" }) };
}

const brokenD1 = {
    prepare() {
        throw new Error("D1 is down");
    },
    async batch() {
        throw new Error("D1 is down");
    }
};

const env = {
    UPDATES: { get: async (key: string) => key.startsWith("manifests/") ? manifestObject(1276) : null },
    STATS: brokenD1,
    STATS_PASSWORD: PASSWORD
};
const ctx = { waitUntil(promise: Promise<unknown>) { promise.catch(() => {}); } };

test("the stats page keeps its rollout controls when D1 fails", async () => {
    const response = await worker.fetch(new Request("https://tg.upd.dexx.moe/stats", { headers: { Authorization: AUTH } }), env as never, ctx as never);
    assert.equal(response.status, 200);
    const html = await response.text();
    assert.match(html, /action="\/stats\/rollout"/);
    assert.match(html, /Stats unavailable/);
});

test("update checks still work when D1 fails", async () => {
    const response = await worker.fetch(new Request("https://tg.upd.dexx.moe/update.json?package=com.de.xxtg", {
        headers: { "X-LibreTG-Install": crypto.randomUUID(), "X-LibreTG-Version": "1275" }
    }), env as never, ctx as never);
    assert.equal(response.status, 200);
    assert.equal((await response.json()).versionCode, 1276);
});
