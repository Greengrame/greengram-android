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
