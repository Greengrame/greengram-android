import express from "express";
import cookieParser from "cookie-parser";
import crypto from "node:crypto";

const PORT = Number(process.env.PORT || 8080);
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "";
const SUPABASE_URL = (process.env.SUPABASE_URL || "").replace(/\/+$/, "");
const SUPABASE_SERVICE_KEY = process.env.SUPABASE_SERVICE_KEY || "";
const SESSION_SECRET = process.env.SESSION_SECRET || crypto.randomBytes(32).toString("hex");
const COOKIE_NAME = "badge_session";
const SESSION_TTL_MS = 12 * 60 * 60 * 1000;

const STATUSES = ["DEFAULT", "DEVELOPER", "SUPPORTER", "RESOURCE", "PARTNER"];
const COLUMNS = [
  "id", "type", "status", "badge_document_id", "badge_text", "can_change_badge",
  "show_badge_button", "forcemorebutton", "badge_button_text",
  "badge_info_icon_document_id", "badge_info_title", "badge_info_message",
  "badge_close_text", "badge_info_features", "deleted", "updated_at",
].join(",");

if (!ADMIN_PASSWORD || !SUPABASE_URL || !SUPABASE_SERVICE_KEY) {
  console.error("Missing ADMIN_PASSWORD, SUPABASE_URL or SUPABASE_SERVICE_KEY");
  process.exit(1);
}

const app = express();
app.use(express.json({ limit: "1mb" }));
app.use(cookieParser());

function sign(value) {
  return crypto.createHmac("sha256", SESSION_SECRET).update(value).digest("hex");
}

function issueSession(res) {
  const expires = Date.now() + SESSION_TTL_MS;
  const payload = `admin.${expires}`;
  const token = `${payload}.${sign(payload)}`;
  res.cookie(COOKIE_NAME, token, {
    httpOnly: true,
    sameSite: "lax",
    secure: true,
    maxAge: SESSION_TTL_MS,
  });
}

function isAuthed(req) {
  const raw = req.cookies[COOKIE_NAME];
  if (!raw) return false;
  const parts = raw.split(".");
  if (parts.length !== 3) return false;
  const payload = `${parts[0]}.${parts[1]}`;
  if (parts[2] !== sign(payload)) return false;
  const expires = Number(parts[1]);
  if (expires <= Date.now()) return false;
  return true;
}

function requireAuth(req, res, next) {
  if (!isAuthed(req)) {
    return res.status(401).json({ error: "unauthorized" });
  }
  next();
}

async function supabase(path, options = {}) {
  const response = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
    ...options,
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      Authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
  });
  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  if (!response.ok) {
    const message = body && body.message ? body.message : `supabase ${response.status}`;
    throw new Error(message);
  }
  return body;
}

app.post("/api/login", (req, res) => {
  const password = typeof req.body?.password === "string" ? req.body.password : "";
  if (password !== ADMIN_PASSWORD) {
    return res.status(401).json({ error: "invalid password" });
  }

  issueSession(res);
  res.json({ ok: true });
});

app.post("/api/logout", (req, res) => {
  res.clearCookie(COOKIE_NAME);
  res.json({ ok: true });
});

app.get("/api/session", (req, res) => {
  res.json({ authenticated: isAuthed(req), statuses: STATUSES });
});

app.get("/api/profiles", requireAuth, async (req, res) => {
  try {
    const includeDeleted = req.query.deleted === "true";
    const filter = includeDeleted ? "" : "&deleted=eq.false";
    const rows = await supabase(`profiles?select=${COLUMNS}&order=id.asc${filter}`);
    res.json(rows);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.patch("/api/profiles/:id", requireAuth, async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isFinite(id) || id === 0) {
    return res.status(400).json({ error: "invalid id" });
  }
  const body = req.body || {};
  const patch = {};

  if (typeof body.status === "string" && STATUSES.includes(body.status.toUpperCase())) {
    patch.status = body.status.toUpperCase();
  }
  if ("badge_document_id" in body) {
    patch.badge_document_id = toBigId(body.badge_document_id);
  }
  if ("badge_text" in body) {
    patch.badge_text = body.badge_text === "" || body.badge_text === null ? null : String(body.badge_text);
  }
  if ("can_change_badge" in body) patch.can_change_badge = Boolean(body.can_change_badge);
  if ("show_badge_button" in body) patch.show_badge_button = Boolean(body.show_badge_button);
  if ("forcemorebutton" in body) patch.forcemorebutton = Boolean(body.forcemorebutton);
  if ("deleted" in body) patch.deleted = Boolean(body.deleted);
  if ("badge_button_text" in body) patch.badge_button_text = emptyToNull(body.badge_button_text);
  if ("badge_info_icon_document_id" in body) {
    patch.badge_info_icon_document_id = toBigId(body.badge_info_icon_document_id);
  }
  if ("badge_info_title" in body) patch.badge_info_title = emptyToNull(body.badge_info_title);
  if ("badge_info_message" in body) patch.badge_info_message = emptyToNull(body.badge_info_message);
  if ("badge_close_text" in body) patch.badge_close_text = emptyToNull(body.badge_close_text);
  if ("badge_info_features" in body) {
    patch.badge_info_features = normalizeFeatures(body.badge_info_features);
  }

  if (Object.keys(patch).length === 0) {
    return res.status(400).json({ error: "nothing to update" });
  }

  try {
    const rows = await supabase(`profiles?id=eq.${id}`, {
      method: "PATCH",
      headers: { Prefer: "return=representation" },
      body: JSON.stringify(patch),
    });
    if (!rows || rows.length === 0) {
      return res.status(404).json({ error: "profile not found" });
    }
    res.json(rows[0]);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post("/api/profiles", requireAuth, async (req, res) => {
  const body = req.body || {};
  const id = Number(body.id);
  if (!Number.isFinite(id) || id === 0) {
    return res.status(400).json({ error: "invalid id" });
  }
  const row = {
    id,
    type: body.type === "CHAT" ? "CHAT" : "USER",
    status: STATUSES.includes(String(body.status).toUpperCase()) ? String(body.status).toUpperCase() : "DEFAULT",
    can_change_badge: Boolean(body.can_change_badge),
    show_badge_button: body.show_badge_button === undefined ? true : Boolean(body.show_badge_button),
    forcemorebutton: Boolean(body.forcemorebutton),
  };
  try {
    const rows = await supabase("profiles", {
      method: "POST",
      headers: { Prefer: "return=representation,resolution=merge-duplicates" },
      body: JSON.stringify(row),
    });
    res.json(rows && rows[0] ? rows[0] : row);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

function toBigId(value) {
  if (value === null || value === undefined || value === "") return null;
  const text = String(value).trim();
  if (!/^\d+$/.test(text)) return null;
  return text;
}

function emptyToNull(value) {
  if (value === undefined) return null;
  if (value === null) return null;
  const text = String(value);
  return text.length === 0 ? null : text;
}

function normalizeFeatures(value) {
  if (value === null || value === undefined) return null;
  if (!Array.isArray(value)) return null;
  const cleaned = value.map((item) => {
    if (!item || typeof item !== "object") return null;
    const icon = item.icon_document_id === null || item.icon_document_id === undefined || item.icon_document_id === ""
      ? null : String(item.icon_document_id);
    return {
      enabled: item.enabled === undefined ? true : Boolean(item.enabled),
      icon_document_id: icon !== null && /^\d+$/.test(icon) ? icon : null,
      title: item.title ? String(item.title) : null,
      text: item.text ? String(item.text) : null,
    };
  }).filter((item) => item !== null);
  return cleaned.length === 0 ? null : cleaned;
}

app.use(express.static(new URL("./public", import.meta.url).pathname, { index: "index.html" }));

app.listen(PORT, "0.0.0.0", () => {
  console.log(`badge-web listening on ${PORT}`);
});
