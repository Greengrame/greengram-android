const $ = (id) => document.getElementById(id);
const STATUSES = ["DEFAULT", "DEVELOPER", "SUPPORTER", "RESOURCE", "PARTNER"];

let profiles = [];
let editingId = null;

async function api(path, options = {}) {
    const response = await fetch(path, {
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        ...options,
    });
    if (response.status === 401) {
        showLogin();
        throw new Error("unauthorized");
    }
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
        throw new Error((body && body.error) || `http ${response.status}`);
    }
    return body;
}

function showLogin() {
    $("login").classList.remove("hidden");
    $("app").classList.add("hidden");
}

function showApp() {
    $("login").classList.add("hidden");
    $("app").classList.remove("hidden");
}

async function boot() {
    const session = await api("/api/session");
    if (session.authenticated) {
        showApp();
        await load();
    } else {
        showLogin();
    }
}

async function load() {
    const deleted = $("show-deleted").checked;
    profiles = await api(`/api/profiles?deleted=${deleted}`);
    render();
}

function render() {
    const query = $("search").value.trim().toLowerCase();
    const list = $("list");
    list.innerHTML = "";

    const visible = profiles.filter((profile) => {
        if (!query) return true;
        const haystack = [
            profile.id,
            profile.status,
            profile.badge_text,
            profile.badge_info_title,
            profile.badge_info_message,
        ].filter(Boolean).join(" ").toLowerCase();
        return haystack.includes(query);
    });

    $("count").textContent = `${visible.length} из ${profiles.length} профилей`;

    for (const profile of visible) {
        list.appendChild(card(profile));
    }
}

function card(profile) {
    const node = document.createElement("article");
    node.className = "profile";

    const head = document.createElement("div");
    head.className = "profile-head";
    const id = document.createElement("span");
    id.className = "badge-id";
    id.textContent = profile.id;
    const tag = document.createElement("span");
    tag.className = `tag ${profile.status}`;
    tag.textContent = profile.status;
    head.append(id, tag);
    node.appendChild(head);

    const text = document.createElement("p");
    text.className = "profile-text";
    text.textContent = profile.badge_text || "—";
    node.appendChild(text);

    const flags = document.createElement("div");
    flags.className = "profile-flags";
    if (profile.forcemorebutton) flags.appendChild(flag("force more"));
    if (profile.show_badge_button) flags.appendChild(flag("show button"));
    if (profile.can_change_badge) flags.appendChild(flag("can change"));
    if (profile.deleted) flags.appendChild(flag("deleted"));
    node.appendChild(flags);

    const actions = document.createElement("div");
    actions.className = "profile-actions";
    const edit = document.createElement("button");
    edit.className = "ghost";
    edit.textContent = "Изменить";
    edit.onclick = () => openEditor(profile);
    actions.appendChild(edit);
    node.appendChild(actions);

    return node;
}

function flag(label) {
    const span = document.createElement("span");
    span.className = "flag";
    span.textContent = label;
    return span;
}

function openEditor(profile) {
    editingId = profile ? profile.id : null;
    $("editor-title").textContent = profile ? `Профиль ${profile.id}` : "Новый профиль";
    $("editor-error").textContent = "";

    $("f-newid").value = "";
    $("f-newid").disabled = Boolean(profile);
    $("f-type").value = profile ? profile.type : "USER";
    $("f-status").value = profile ? profile.status : "DEFAULT";
    $("f-document").value = profile && profile.badge_document_id != null ? profile.badge_document_id : "";
    $("f-info-icon").value = profile && profile.badge_info_icon_document_id != null ? profile.badge_info_icon_document_id : "";
    $("f-can-change").checked = profile ? Boolean(profile.can_change_badge) : false;
    $("f-show-button").checked = profile ? Boolean(profile.show_badge_button) : true;
    $("f-force-more").checked = profile ? Boolean(profile.forcemorebutton) : false;
    $("f-deleted").checked = profile ? Boolean(profile.deleted) : false;
    $("f-badge-text").value = profile && profile.badge_text ? profile.badge_text : "";
    $("f-button-text").value = profile && profile.badge_button_text ? profile.badge_button_text : "";
    $("f-info-title").value = profile && profile.badge_info_title ? profile.badge_info_title : "";
    $("f-info-message").value = profile && profile.badge_info_message ? profile.badge_info_message : "";
    $("f-close-text").value = profile && profile.badge_close_text ? profile.badge_close_text : "";

    const features = $("features");
    features.innerHTML = "";
    const source = profile && Array.isArray(profile.badge_info_features) ? profile.badge_info_features : [];
    for (const feature of source) {
        features.appendChild(featureRow(feature));
    }

    $("editor").showModal();
}

function featureRow(feature = {}) {
    const row = document.createElement("div");
    row.className = "feature";
    row.innerHTML = `
        <label>Заголовок <input class="f-title" value=""></label>
        <label>Текст <input class="f-text" value=""></label>
        <label>Document ID <input class="f-icon" type="number"></label>
        <label class="check"><input class="f-enabled" type="checkbox"> включён</label>
        <button type="button" class="ghost remove">Удалить</button>
    `;
    row.querySelector(".f-title").value = feature.title || "";
    row.querySelector(".f-text").value = feature.text || "";
    row.querySelector(".f-icon").value = feature.icon_document_id != null ? feature.icon_document_id : "";
    row.querySelector(".f-enabled").checked = feature.enabled === undefined ? true : Boolean(feature.enabled);
    row.querySelector(".remove").onclick = () => row.remove();
    return row;
}

function collectFeatures() {
    return Array.from($("features").children).map((row) => {
        const icon = row.querySelector(".f-icon").value;
        return {
            enabled: row.querySelector(".f-enabled").checked,
            icon_document_id: icon === "" ? null : Number(icon),
            title: row.querySelector(".f-title").value || null,
            text: row.querySelector(".f-text").value || null,
        };
    });
}

async function save() {
    const payload = {
        type: $("f-type").value,
        status: $("f-status").value,
        badge_document_id: $("f-document").value === "" ? null : Number($("f-document").value),
        badge_text: $("f-badge-text").value,
        can_change_badge: $("f-can-change").checked,
        show_badge_button: $("f-show-button").checked,
        forcemorebutton: $("f-force-more").checked,
        deleted: $("f-deleted").checked,
        badge_button_text: $("f-button-text").value,
        badge_info_icon_document_id: $("f-info-icon").value === "" ? null : Number($("f-info-icon").value),
        badge_info_title: $("f-info-title").value,
        badge_info_message: $("f-info-message").value,
        badge_close_text: $("f-close-text").value,
        badge_info_features: collectFeatures(),
    };

    try {
        if (editingId) {
            await api(`/api/profiles/${editingId}`, { method: "PATCH", body: JSON.stringify(payload) });
        } else {
            payload.id = Number($("f-newid").value);
            if (!payload.id) {
                throw new Error("укажи Telegram ID");
            }
            await api("/api/profiles", { method: "POST", body: JSON.stringify(payload) });
        }
        $("editor").close();
        await load();
    } catch (error) {
        $("editor-error").textContent = error.message;
    }
}

$("login-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    $("login-error").textContent = "";
    try {
        await api("/api/login", { method: "POST", body: JSON.stringify({ password: $("password").value }) });
        $("password").value = "";
        showApp();
        await load();
    } catch (error) {
        $("login-error").textContent = "Неверный пароль";
    }
});

$("logout").onclick = async () => {
    await api("/api/logout", { method: "POST" });
    showLogin();
};

$("search").oninput = render;
$("show-deleted").onchange = load;
$("new-profile").onclick = () => openEditor(null);
$("add-feature").onclick = () => $("features").appendChild(featureRow());
$("cancel").onclick = () => $("editor").close();
$("editor-form").addEventListener("submit", (event) => {
    event.preventDefault();
    save();
});

boot().catch(() => showLogin());
