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
