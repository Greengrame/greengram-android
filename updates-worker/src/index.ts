export interface Env {
    UPDATES: R2Bucket;
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
}

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

async function readManifest(env: Env, channel: string): Promise<UpdateManifest | null> {
    const object = await env.UPDATES.get(`manifests/${channel}.json`);
    if (!object) {
        return null;
    }
    return JSON.parse(await object.text()) as UpdateManifest;
}

export default {
    async fetch(request: Request, env: Env): Promise<Response> {
        const url = new URL(request.url);
        if (request.method !== "GET") {
            return new Response("Method Not Allowed", { status: 405 });
        }
        if (url.pathname === "/health") {
            return json({ ok: true });
        }
        if (url.pathname === "/update.json") {
            const packageName = request.headers.get("X-LibreTG-Package")
                || url.searchParams.get("package")
                || "com.de.xxtg.beta";
            const channel = packageName === "com.de.xxtg" ? "release" : "beta";
            const manifest = await readManifest(env, channel);
            if (!manifest) {
                return json({ error: "Update manifest not uploaded" }, "no-store");
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
    }
};
