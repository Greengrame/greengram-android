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

function json(data: unknown, cacheControl = "no-store") {
    return new Response(JSON.stringify(data), {
        headers: {
            "content-type": "application/json; charset=utf-8",
            "cache-control": cacheControl,
            "access-control-allow-origin": "*"
        }
    });
}

function decodeXml(value: string): string {
    return value
        .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
        .replace(/&amp;/g, "&")
        .replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'")
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">");
}

function readTag(source: string, tag: string): string | null {
    const match = source.match(new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)</${tag}>`, "i"));
    return match ? decodeXml(match[1].trim()) : null;
}

async function readLatestChangelog(env: Env, acceptLanguage: string): Promise<{ title: string | null; description: string | null } | null> {
    const object = await env.UPDATES.get("changelog.xml");
    if (!object) {
        return null;
    }
    const xml = await object.text();
    const items = [...xml.matchAll(/<item\b[^>]*>([\s\S]*?)<\/item>/gi)].map(match => match[1]);
    if (items.length === 0) {
        return null;
    }
    const requestedLanguages = acceptLanguage
        .split(",")
        .map(value => value.split(";")[0].trim().toLowerCase())
        .filter(Boolean);
    const entries = items.map(item => ({
        item,
        language: readTag(item, "libre:lang")?.toLowerCase() || "en"
    }));
    const selected = entries.find(entry => requestedLanguages.some(language =>
        language === entry.language || language.split("-")[0] === entry.language.split("-")[0]
    )) || entries.find(entry => entry.language === "en") || entries[0];
    return {
        title: readTag(selected.item, "title"),
        description: readTag(selected.item, "description")
    };
}

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
        if (url.pathname === "/changelog.xml") {
            const object = await env.UPDATES.get("changelog.xml");
            if (!object) {
                return new Response("Changelog not uploaded", { status: 404 });
            }
            const headers = new Headers();
            object.writeHttpMetadata(headers);
            headers.set("content-type", "application/rss+xml; charset=utf-8");
            headers.set("cache-control", "no-cache");
            headers.set("etag", object.httpEtag);
            return new Response(object.body, { headers });
        }
        if (url.pathname === "/app.apk" || url.pathname === "/app-beta.apk" || url.pathname === "/app-release.apk") {
            const objectKey = url.pathname === "/app-release.apk" ? "app-release.apk" : "app-beta.apk";
            const object = await env.UPDATES.get(objectKey);
            if (!object) {
                return new Response("APK not uploaded", { status: 404 });
            }
            const headers = new Headers();
            object.writeHttpMetadata(headers);
            headers.set("content-type", "application/vnd.android.package-archive");
            headers.set("cache-control", "no-cache");
            headers.set("etag", object.httpEtag);
            return new Response(object.body, { headers });
        }
        return json({ error: "Not found" }, "no-store");
    },

    async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
        ctx.waitUntil(deleteOldStats(env.STATS, new Date()));
    }
};
