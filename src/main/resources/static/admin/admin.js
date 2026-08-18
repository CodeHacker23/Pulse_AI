(() => {
  const META = {
    dashboard: { title: "Обзор", sub: "Живой статус бота и скаутов" },
    proxies: { title: "Прокси", sub: "Залил список → проверил → закрепил за скаутом" },
    accounts: { title: "Скауты", sub: "Сессия · вход · запуск · кабинет" },
    inbox: { title: "Кабинет TG", sub: "Слева чаты/группы · справа диалог скаута" },
    audience: { title: "ЦА / join", sub: "Массовый join списком + парсинг участников" },
    campaigns: { title: "Рассылки", sub: "Кампании outreach" },
    templates: { title: "Шаблоны ЛС", sub: "Тексты для рассылки" },
    logs: { title: "Журнал", sub: "Что делали скауты" },
    radar: { title: "Радар", sub: "Наблюдения и хиты" },
  };

  const TYPE_RU = {
    PARSER: "Парсер",
    OBSERVER: "Наблюдатель",
    OUTREACH: "Рассыльщик",
    SENDER: "Рассыльщик",
  };
  const STATUS_RU = {
    ACTIVE: "Работает",
    PAUSED: "Пауза",
    WARMING: "Прогрев",
    FLOOD: "Флуд",
    FLOOD_WAIT: "Карантин",
    BANNED: "Бан",
    RUNNING: "Идёт",
    SENT: "Отправлено",
    REPLIED: "Ответил",
    PENDING: "Ждёт",
    OK: "Ок",
    INVALID: "Мёртвый",
    OFF: "Выкл",
    SUCCESS: "Ок",
    FAILED: "Ошибка",
    ERROR: "Ошибка",
    BURNED: "Сгорел",
    DEAD: "Мёртвый",
  };

  const state = {
    token: localStorage.getItem("pulse_admin_token") || "",
    view: "dashboard",
    accounts: [],
    selectedAccountId: null,
    scoutOnline: false,
    keyAudit: null,
    dialogs: [],
    dialogKind: "all",
    inboxAccountId: null,
    peer: null,
    peerUsername: "",
  };

  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");

  function toast(msg, isErr = false) {
    const el = $("toast");
    el.textContent = msg;
    el.classList.toggle("err", !!isErr);
    el.classList.remove("hidden");
    clearTimeout(toast._t);
    toast._t = setTimeout(() => el.classList.add("hidden"), 3400);
  }

  async function api(path, options = {}) {
    const url = new URL(path, window.location.origin);
    if (state.token) url.searchParams.set("token", state.token);
    const headers = Object.assign({}, options.headers || {});
    if (state.token) headers["X-Admin-Token"] = state.token;
    if (options.json) {
      headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(options.json);
      delete options.json;
    }
    const res = await fetch(url.toString(), { ...options, headers });
    if (res.status === 401) {
      logout(true);
      throw new Error("Неверный токен");
    }
    const ct = res.headers.get("content-type") || "";
    let data = null;
    const text = await res.text();
    if (ct.includes("application/json") || (text && text.trim().startsWith("{"))) {
      try { data = JSON.parse(text); } catch { /* keep text */ }
    }
    if (!res.ok) {
      throw new Error(formatApiError(res.status, data, text));
    }
    if (data && data.ok === false) {
      // soft fail: return payload so caller can toast, but also expose error
      data._httpOk = true;
    }
    return data != null ? data : text;
  }

  function formatApiError(status, data, text) {
    if (data && typeof data === "object") {
      if (data.error) return String(data.error);
      if (data.detail) {
        if (typeof data.detail === "string") return data.detail;
        if (Array.isArray(data.detail)) {
          return data.detail.map((d) => d.msg || JSON.stringify(d)).join("; ");
        }
      }
      if (data.message) return String(data.message);
    }
    const raw = (text || "").replace(/<[^>]+>/g, " ").trim();
    if (raw && raw.length < 180) return raw;
    if (status === 404) return "404 — нет такого API (перезапусти бота)";
    if (status === 422) return "422 — неверный запрос (файл/поля)";
    if (status === 400) return "400 — плохой запрос";
    return `Ошибка ${status}`;
  }

  function showLogin(err) {
    $("app").classList.add("hidden");
    $("login-screen").classList.remove("hidden");
    if (err) {
      $("login-error").textContent = err;
      $("login-error").classList.remove("hidden");
    }
  }

  function showApp() {
    $("login-screen").classList.add("hidden");
    $("app").classList.remove("hidden");
  }

  function logout(bad) {
    state.token = "";
    localStorage.removeItem("pulse_admin_token");
    showLogin(bad ? "Сессия сброшена. Введи токен снова." : "");
  }

  async function login(token) {
    state.token = token.trim();
    await api("/admin/api/dashboard");
    localStorage.setItem("pulse_admin_token", state.token);
    const u = new URL(window.location.href);
    u.searchParams.set("token", state.token);
    history.replaceState(null, "", u.toString());
    showApp();
    setView(state.view);
  }

  function setView(name) {
    state.view = name;
    document.querySelectorAll(".nav-item").forEach((b) => {
      b.classList.toggle("active", b.dataset.view === name);
    });
    document.querySelectorAll(".view").forEach((v) => {
      v.classList.add("hidden");
      v.classList.remove("anim-view");
    });
    const el = $(`view-${name}`);
    el.classList.remove("hidden");
    void el.offsetWidth;
    el.classList.add("anim-view");
    $("view-title").textContent = META[name].title;
    $("view-sub").textContent = META[name].sub;
    refresh();
  }

  async function refresh() {
    try {
      const loaders = {
        dashboard: loadDashboard,
        proxies: loadProxies,
        accounts: loadAccounts,
        inbox: loadInboxCabinet,
        audience: loadAudienceHub,
        campaigns: loadCampaigns,
        templates: loadTemplates,
        logs: loadLogs,
        radar: loadRadar,
      };
      await loaders[state.view]?.();
    } catch (e) {
      toast(e.message || String(e), true);
    }
  }

  function statusPill(status) {
    const s = String(status || "").toUpperCase();
    let cls = "warn";
    if (["ACTIVE", "RUNNING", "OK", "SUCCESS", "HOT", "WARM"].includes(s)) cls = "ok";
    if (["PAUSED", "BANNED", "FLOOD", "FLOOD_WAIT", "ERROR", "FAILED", "INVALID", "DEAD", "BURNED"].includes(s)) cls = "bad";
    if (["COLD", "PENDING", "WARMING"].includes(s)) cls = "info";
    const label = STATUS_RU[s] || s;
    return `<span class="status-pill ${cls}">${esc(label)}</span>`;
  }

  function typeRu(t) {
    return TYPE_RU[String(t || "").toUpperCase()] || t || "—";
  }

  function fillAccountSelect(sel, filterFn) {
    const list = filterFn ? state.accounts.filter(filterFn) : state.accounts;
    sel.innerHTML = list.map((a) =>
      `<option value="${a.id}">#${a.id} ${esc(a.label)} · ${esc(typeRu(a.accountType))}</option>`
    ).join("") || `<option value="">Нет аккаунтов</option>`;
  }

  function formatScoutId(a) {
    const t = String(a.accountType || "").toUpperCase();
    const send = t === "SENDER" || t === "OUTREACH";
    const id = a.id;
    const band = send
      ? (id >= 100 && id <= 999 ? "send" : id >= 2000 ? "send-x" : "send-legacy")
      : (id >= 1 && id <= 99 ? "watch" : id >= 1000 ? "watch-x" : "watch");
    return `<span class="scout-id scout-id-${band}" title="${send ? "пишущий" : "парсер/наблюдатель"}">#${esc(String(id))}</span>`;
  }

  function isBurned(a) {
    return a.burned === true || String(a.status || "").toUpperCase() === "BURNED";
  }

  function dupKeyBadge(a) {
    const info = state.keyAudit?.accounts?.[String(a.id)];
    if (!info?.duplicateKey) return "";
    const withWho = (info.duplicateWith || []).join(", ");
    return ` <span class="status-pill bad" title="Тот же auth_key, что у: ${esc(withWho)} — сгорит">дубль ключа</span>`;
  }

  function liveStatusCell(a) {
    const db = statusPill(a.status) + dupKeyBadge(a);
    if (isBurned(a)) {
      return `${db} <span class="status-pill bad" title="Ключ убит Telegram — нужен новый tdata">ключ мёртв</span>`;
    }
    if (a.inQuarantine || String(a.status || "").toUpperCase() === "FLOOD_WAIT") {
      const until = a.quarantineUntil ? esc(String(a.quarantineUntil).replace("T", " ").slice(0, 16)) : "?";
      return `${db} <span class="status-pill bad" title="После FLOOD нельзя ACTIVE до конца карантина">до ${until}</span>`;
    }
    if (a.tgAuthorized === true) {
      return `${db} <span class="status-pill ok">TG в сети</span>`;
    }
    if (a.tgAuthorized === false) {
      return `${db} <span class="status-pill bad">TG нет</span>`;
    }
    if (String(a.status || "").toUpperCase() === "ACTIVE") {
      return `${db} <span class="status-pill warn">TG ?</span>`;
    }
    return db;
  }

  function accountRows(accounts, full) {
    if (!accounts?.length) return `<p class="muted">Аккаунтов пока нет</p>`;
    const head = `<table><thead><tr>
      <th>ID</th><th>Имя</th><th>Телефон / TG</th><th>Роль</th><th>Статус</th><th>Сегодня</th>
      ${full ? "<th>Действия</th>" : ""}
    </tr></thead><tbody>`;
    const body = accounts.map((a) => {
      const dead = isBurned(a);
      const actions = full ? `<td class="actions">
        <button type="button" class="btn btn-sm btn-primary" data-act="open" data-id="${a.id}">Открыть</button>
        ${dead ? "" : `<button type="button" class="btn btn-sm" data-act="pause" data-id="${a.id}">Пауза</button>
        <button type="button" class="btn btn-sm" data-act="resume" data-id="${a.id}">Старт</button>
        <button type="button" class="btn btn-sm" data-act="assign" data-id="${a.id}">Дать прокси</button>
        ${["SENDER","OUTREACH"].includes(String(a.accountType||"").toUpperCase())
          ? `<button type="button" class="btn btn-sm" data-act="spambot" data-id="${a.id}">SpamBot ${a.spambotToday ?? 0}/${a.spambotMax ?? 4}</button>`
          : `<button type="button" class="btn btn-sm" data-act="enroll" data-id="${a.id}">В пул групп</button>`}
        ${dead ? "" : `<button type="button" class="btn btn-sm" data-act="backfill" data-id="${a.id}">Архив ЛС</button>`}
        <button type="button" class="btn btn-sm" data-act="rotate" data-id="${a.id}">Сменить прокси</button>
        <button type="button" class="btn btn-sm" data-act="burn" data-id="${a.id}">В мёртвые</button>`}
        <button type="button" class="btn btn-sm" data-act="wipe" data-id="${a.id}" title="Снести .session под новый tdata">Сбросить сессию</button>
        ${dead && ["PARSER","OBSERVER"].includes(String(a.accountType||"").toUpperCase())
          ? `<button type="button" class="btn btn-sm btn-primary" data-act="failover" data-id="${a.id}">Перенести пул</button>`
          : ""}
        <button type="button" class="btn btn-sm btn-danger" data-act="delete" data-id="${a.id}">Удалить</button>
      </td>` : "";
      const idHint = a.phone || a.tgPhone || a.externalRef || "—";
      return `<tr class="account-row ${dead ? "row-dead" : ""}" data-id="${a.id}">
        <td class="mono">${formatScoutId(a)}</td>
        <td>${esc(a.label)}</td>
        <td class="mono">${esc(idHint)}</td>
        <td>${esc(typeRu(a.accountType))}</td>
        <td>${liveStatusCell(a)}</td>
        <td class="mono">${a.sentToday ?? 0}/${a.dailyLimit ?? "—"}</td>
        ${actions}
      </tr>`;
    }).join("");
    return head + body + "</tbody></table>";
  }

  function fillIdentityCard(identity, me) {
    const card = $("identity-card");
    const form = $("identity-form");
    const idn = identity || {};
    const phone = idn.phone || me?.phone || "";
    const userId = idn.userId || me?.id || "";
    const dcId = idn.dcId || "";
    const hint = idn.authKeyHint || "";
    const note = idn.shopNote || "";
    if (form) {
      form.classList.remove("hidden");
      form.phone.value = phone || "";
      form.userId.value = userId || "";
      form.dcId.value = dcId || "";
      form.shopNote.value = note || "";
    }
    if (!card) return;
    if (!phone && !userId && !dcId && !hint && !note) {
      card.className = "identity-card muted";
      card.innerHTML = `Нет данных покупки — впиши телефон / User ID с лолза и сохрани, или жми «Восстановить из secrets».`;
      return;
    }
    card.className = "identity-card";
    card.innerHTML = `
      <div class="identity-title">Данные для входа / магазин</div>
      <div class="identity-grid">
        <div><span>Номер</span><b class="mono">${esc(phone || "—")}</b></div>
        <div><span>User ID</span><b class="mono">${esc(userId || "—")}</b></div>
        <div><span>DC ID</span><b class="mono">${esc(dcId || "—")}</b></div>
        <div><span>Auth Key</span><b class="mono">${esc(hint || "—")}</b></div>
      </div>
      ${note ? `<div class="identity-note">${esc(note)}</div>` : ""}
      ${idn.hasSecrets ? `<div class="identity-src">есть запись в accounts.secrets.json</div>` : ""}`;
  }

  function fillTgCard(id, me) {
    const first = me.first_name || me.firstName || "";
    const last = me.last_name || me.lastName || "";
    const uname = me.username || "";
    const about = me.about || me.bio || "";
    const form = $("profile-form");
    if (form) {
      form.firstName.value = first;
      form.lastName.value = last;
      form.username.value = uname;
      if (form.about) form.about.value = about;
    }
    if ($("tg-card")) {
      $("tg-card").classList.remove("muted");
      $("tg-card").innerHTML = `
        <p class="tg-name">${esc((first + " " + last).trim() || ("Скаут #" + id))}</p>
        <div class="tg-user">${uname ? "@" + esc(uname) : "без @username"}</div>
        <div class="tg-about">${esc(about || "Нет «о себе»")}</div>
        <div class="tg-meta">${statusPill(state.accounts.find((a) => String(a.id) === String(id))?.status || "WARMING")}</div>`;
    }
  }

  function fillTgCardEmpty(id, reason) {
    if ($("tg-card")) {
      $("tg-card").classList.remove("muted");
      $("tg-card").innerHTML = `
        <p class="tg-name">Скаут #${esc(String(id))}</p>
        <div class="tg-user">Telegram ещё не подключён</div>
        <div class="tg-about">${esc(reason || "")}</div>`;
    }
  }

  function setLoginBadge(online, me, extra) {
    const el = $("login-badge");
    if (!el) return;
    const startBtn = $("btn-start-scout");
    const openBtn = $("btn-open-tg");
    state.scoutOnline = !!online;
    if (startBtn) startBtn.disabled = !online;
    if (openBtn) openBtn.disabled = !online;
    if (!state.selectedAccountId) {
      el.className = "login-badge muted";
      el.textContent = "Не выбран";
      return;
    }
    if (online && me) {
      const uname = me.username ? "@" + me.username : ("id " + (me.id || ""));
      const name = [me.first_name || me.firstName, me.last_name || me.lastName].filter(Boolean).join(" ") || uname;
      el.className = "login-badge online";
      el.innerHTML = `<b>В сети</b> · ${esc(name)} · ${esc(uname)}${extra ? " · " + esc(extra) : ""}`;
    } else {
      el.className = "login-badge offline";
      el.innerHTML = `<b>Не вошёл</b> · загрузи tdata.zip / .session / auth_key${extra ? " · " + esc(extra) : ""}`;
    }
  }

  async function openScout(id) {
    state.selectedAccountId = id;
    const acc = state.accounts.find((a) => String(a.id) === String(id));
    const form = $("profile-form");
    if (form) {
      form.classList.remove("hidden");
      form.accountId.value = id;
    }
    if ($("connect-body")) $("connect-body").classList.remove("hidden");
    if ($("connect-hint")) {
      $("connect-hint").textContent = `Скаут #${id}${acc ? " · " + acc.label : ""}`;
    }
    setLoginBadge(false, null, "проверка…");

    let st = {};
    try {
      st = await api(`/admin/api/accounts/${id}/status`);
    } catch (e) {
      st = { ok: false, error: e.message };
    }

    if (!st.registered) {
      try {
        const reg = await api(`/admin/api/accounts/${id}/register-sidecar`, { method: "POST" });
        if (reg.ok) st = await api(`/admin/api/accounts/${id}/status`);
        else toast(reg.error || "sidecar offline", true);
      } catch (e) {
        toast(e.message || "sidecar offline", true);
      }
    }

    const lines = [
      `БД: ${st.dbStatus || acc?.status || "?"} · ${st.dbType || acc?.accountType || "?"}`,
      `Sidecar: ${st.registered ? "ok" : "нет"} · session-файл: ${st.sessionFile ? "есть" : "нет"}`,
      `Авторизация TG: ${st.authorized ? "ДА — ты в аккаунте" : "НЕТ"}`,
    ];
    if (st.authError) lines.push(String(st.authError));
    if ($("connect-status")) $("connect-status").textContent = lines.join("\n");
    fillIdentityCard(st.identity || {}, st.me || null);

    try {
      const r = await api(`/admin/api/accounts/${id}/me`);
      if (r.ok && r.me) {
        fillTgCard(id, r.me);
        fillIdentityCard(st.identity || {}, r.me);
        $("profile-me").textContent = JSON.stringify(r.me, null, 2);
        setLoginBadge(true, r.me, st.dbStatus || acc?.status);
        toast("Вход в Telegram подтверждён");
      } else {
        fillTgCardEmpty(id, r.error || st.authError || "нет сессии");
        $("profile-me").textContent = r.error || st.authError || "offline";
        setLoginBadge(false, null, r.error || st.authError || "");
        toast("Не вошёл в TG — загрузи сессию или «Восстановить из secrets»", true);
      }
    } catch (e) {
      fillTgCardEmpty(id, e.message);
      setLoginBadge(false, null, e.message);
      toast(e.message, true);
    }
  }

  async function openTelegramWorkspace(accountId) {
    const id = accountId || state.selectedAccountId;
    if (!id) {
      toast("Сначала открой скаута", true);
      return;
    }
    if (!state.scoutOnline) {
      toast("Сначала войди в аккаунт (tdata / session)", true);
      return;
    }
    setView("inbox");
    await loadInboxAccounts();
    const sel = $("inbox-account");
    if (sel) sel.value = String(id);
    state.inboxAccountId = Number(id);
    try {
      await loadDialogsForAccount(Number(id));
      toast("Чаты скаута #" + id);
    } catch (e) {
      toast(e.message, true);
    }
  }

  function kindRu(k) {
    return ({ user: "ЛС", bot: "бот", group: "группа", channel: "канал" })[k] || k || "";
  }

  const AVATAR_COLORS = ["#5b8def", "#e17076", "#faa774", "#a695e7", "#7bc862", "#6ec9cb", "#ee7aae", "#f5bd4b"];

  function initials(name) {
    const parts = String(name || "?").trim().split(/\s+/).filter(Boolean);
    if (!parts.length) return "?";
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }

  function avatarColor(seed) {
    const s = String(seed || "");
    let h = 0;
    for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
    return AVATAR_COLORS[h % AVATAR_COLORS.length];
  }

  function fmtDialogTime(iso) {
    if (!iso) return "";
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return "";
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    if (sameDay) {
      return d.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
    }
    return d.toLocaleDateString("ru-RU", { day: "2-digit", month: "2-digit" });
  }

  function inboxAccountLabel() {
    const id = Number($("inbox-account")?.value || state.inboxAccountId || 0);
    const a = (state.accounts || []).find((x) => Number(x.id) === id);
    if (!a) return id ? `скаут #${id}` : "аккаунт скаута";
    return `${a.label || a.sessionName || "скаут"} · #${a.id}`;
  }

  function updateReadBanner(unread) {
    const banner = $("mark-read-banner");
    const btn = $("mark-read-banner-btn");
    const headBtn = $("mark-read-btn");
    const n = Number(unread || 0);
    if (banner && btn) {
      if (n > 0) {
        banner.classList.remove("hidden");
        btn.textContent = `Отметить прочитанными ${n}`;
      } else {
        banner.classList.add("hidden");
      }
    }
    if (headBtn) {
      headBtn.disabled = !state.peer;
      headBtn.textContent = n > 0 ? `Прочитано · ${n}` : "Прочитано";
    }
  }

  function renderDialogs(rows) {
    const q = ($("dialog-filter")?.value || "").trim().toLowerCase();
    const kind = state.dialogKind || "all";
    let list = rows || [];
    if (kind !== "all") {
      list = list.filter((r) => {
        const k = String(r.kind || "user");
        if (kind === "group") return k === "group";
        if (kind === "channel") return k === "channel";
        if (kind === "user") return k === "user" || k === "bot";
        return true;
      });
    }
    if (q) {
      list = list.filter((r) =>
        String(r.name || "").toLowerCase().includes(q)
        || String(r.username || "").toLowerCase().includes(q)
        || String(r.peer_id || "").includes(q));
    }
    if (!list.length) {
      $("dialogs-list").innerHTML = `<p class="muted" style="padding:16px">Пусто${kind !== "all" ? " в этой вкладке" : ""}${q ? " / фильтр" : ""}</p>`;
      return;
    }
    $("dialogs-list").innerHTML = list.map((r) => {
      const title = r.name || r.username || r.peer_id;
      const unread = Number(r.unread || 0);
      return `
      <button type="button" class="dialog-item ${String(r.peer_id) === String(state.peer) ? "active" : ""}"
        data-peer="${esc(r.peer_id)}" data-user="${esc(r.username)}" data-name="${esc(r.name)}"
        data-kind="${esc(r.kind || "user")}" data-unread="${unread}">
        <div class="tg-avatar" style="background:${avatarColor(r.peer_id)}">${esc(initials(title))}</div>
        <div class="tg-dialog-body">
          <div class="name"><span>${esc(title)}</span><span class="tg-dialog-time">${esc(fmtDialogTime(r.date))}</span></div>
          <div class="preview">${esc(r.last_message || kindRu(r.kind) || "—")}${r.from_archive ? " · архив" : ""}</div>
        </div>
        ${unread ? `<span class="badge">${unread}</span>` : `<span class="badge badge-empty"></span>`}
      </button>`;
    }).join("");
  }

  async function loadDialogsForAccount(accountId) {
    const d = await api(`/admin/api/dialogs?accountId=${accountId}&limit=80`);
    if (!d.ok) throw new Error(d.error || "не удалось загрузить чаты");
    state.dialogs = d.dialogs || [];
    state.inboxSource = d.source || "live";
    renderDialogs(state.dialogs);
    const badge = $("chat-archive-badge");
    if (badge) {
      const fromArchive = d.source === "archive";
      badge.classList.toggle("hidden", !fromArchive);
      badge.title = fromArchive ? (d.liveError || "сессия недоступна") : "";
    }
  }

  async function loadInboxCabinet() {
    await loadInboxAccounts();
    const sel = $("inbox-account");
    if (state.selectedAccountId && sel) {
      sel.value = String(state.selectedAccountId);
      state.inboxAccountId = Number(state.selectedAccountId);
    }
    const accountId = Number(sel?.value || state.inboxAccountId);
    if (!accountId) {
      $("dialogs-list").innerHTML = `<p class="muted">Выбери скаута сверху</p>`;
      return;
    }
    try {
      await loadDialogsForAccount(accountId);
    } catch (e) {
      $("dialogs-list").innerHTML = `<p class="error">${esc(e.message)}</p>`;
    }
  }

  async function loadAudienceHub() {
    await loadAudienceAccounts();
    await loadChatsImport();
  }

  async function loadDashboard() {
    const d = await api("/admin/api/dashboard");
    state.accounts = d.accounts || [];
    const side = d.sidecar || {};
    const px = d.proxies || {};
    const active = state.accounts.filter((a) => a.status === "ACTIVE").length;
    const senders = state.accounts.filter((a) =>
      ["SENDER", "OUTREACH"].includes(String(a.accountType || "").toUpperCase())).length;
    const sent = state.accounts.reduce((s, a) => s + (a.sentToday || 0), 0);
    $("dash-stats").innerHTML = `
      <div class="stat"><div class="label">Скаут-сервер</div>
        <div class="value ${side.reachable ? "ok" : "bad"}">${side.reachable ? "Онлайн" : "Оффлайн"}</div>
        <div class="muted mono">${esc(d.sidecarUrl || "")}</div></div>
      <div class="stat"><div class="label">Аккаунты</div>
        <div class="value">${state.accounts.length}</div>
        <div class="muted">ACTIVE ${active} · SENDER ${senders} · проблем ${d.problemAccounts ?? 0}</div></div>
      <div class="stat"><div class="label">ЛС сегодня</div>
        <div class="value">${sent}</div>
        <div class="muted">очередь PENDING: ${d.pendingProspects ?? 0}</div></div>
      <div class="stat"><div class="label">Рассылки</div>
        <div class="value">${d.runningCampaigns ?? 0}</div>
        <div class="muted">на паузе: ${d.pausedCampaigns ?? 0}</div></div>
      <div class="stat"><div class="label">Прокси</div>
        <div class="value ${px.ok ? "ok" : "bad"}">${px.ok ? (px.valid ?? 0) + "/" + (px.total ?? 0) : "—"}</div>
        <div class="muted">${px.ok ? ("занято " + (px.assigned ?? 0)) : esc(px.error || "sidecar")}</div></div>`;
    $("dash-accounts").innerHTML = accountRows(state.accounts, false);
    if (d.proxyInboxPath) $("proxy-inbox-path").textContent = d.proxyInboxPath;
  }

  async function loadProxies() {
    const d = await api("/admin/api/proxies");
    if (d.inboxPath) $("proxy-inbox-path").textContent = d.inboxPath;
    const list = d.proxies || [];
    const assign = d.assignments || {};
    const usedIds = new Set(Object.values(assign).map((v) => Number(v)));
    const byProxy = {};
    Object.entries(assign).forEach(([accId, pid]) => {
      byProxy[pid] = byProxy[pid] || [];
      byProxy[pid].push(accId);
    });
    $("proxy-count").textContent = String(list.length);
    const hint = $("proxy-import-hint");
    if (hint) {
      hint.textContent = d.ok
        ? `Живых: ${list.filter((p) => p.valid !== false).length} · занято: ${usedIds.size} · свободно: ${list.filter((p) => p.valid !== false && !usedIds.has(Number(p.id))).length}`
        : "";
    }
    if (!d.ok) {
      $("proxy-list").innerHTML = `<p class="error">${esc(d.error || "скаут-сервер недоступен")}</p>
        <p class="muted">Прокси хранятся в sidecar (<code>scout-sidecar/proxies.json</code>). Перезапусти sidecar с актуальным кодом и нажми «Залить в пул» снова.</p>`;
      return;
    }
    if (!list.length) {
      $("proxy-list").innerHTML = `<p class="muted">Пул пуст — вставь список или выбери .txt.</p>`;
      return;
    }
    $("proxy-list").innerHTML = `<table><thead><tr>
      <th>ID</th><th>Адрес</th><th>Логин</th><th>Состояние</th><th>Привязка</th><th>Проверка</th>
    </tr></thead><tbody>${list.map((p) => {
      const id = Number(p.id);
      const dead = p.valid === false;
      const linked = byProxy[id] || byProxy[String(id)] || [];
      let stateLabel = dead ? "мёртвый" : (linked.length ? "в работе" : "в запасе");
      let pill = dead ? statusPill("DEAD") : (linked.length ? statusPill("OK") : statusPill("PENDING"));
      return `<tr>
      <td class="mono">#${esc(p.id)}</td>
      <td class="mono">${esc(p.host)}:${esc(p.port)}</td>
      <td class="mono">${esc(p.user || "—")}</td>
      <td>${pill} <span class="muted">${esc(stateLabel)}</span></td>
      <td class="mono">${linked.length ? linked.map((a) => "acc#" + a).join(", ") : "—"}</td>
      <td class="mono">${p.last_check_ok == null ? "—" : (p.last_check_ok ? `живое ${p.last_check_ms || 0}мс` : esc(p.last_error || "дохлый"))}</td>
    </tr>`;
    }).join("")}</tbody></table>`;
  }

  async function loadAccounts() {
    try {
      await api("/admin/api/accounts/sync-sidecar", { method: "POST" });
    } catch (_) { /* sidecar offline — список всё равно покажем */ }
    const d = await api("/admin/api/dashboard");
    state.accounts = d.accounts || [];
    const role = $("accounts-role-filter")?.value || "";
    const life = $("accounts-life-filter")?.value ?? "alive";
    let filtered = state.accounts;
    if (role) {
      filtered = filtered.filter((a) => String(a.accountType || "").toUpperCase() === role);
    }
    if (life === "alive") {
      filtered = filtered.filter((a) => !isBurned(a));
    } else if (life === "burned") {
      filtered = filtered.filter((a) => isBurned(a));
    }
    const dead = state.accounts.filter(isBurned).length;
    const alive = state.accounts.length - dead;
    const hint = $("accounts-count-hint");
    if (hint) {
      hint.textContent = `Всего ${state.accounts.length} · рабочих ${alive} · сгоревших ${dead}`;
    }
    await loadKeyAudit();
    $("accounts-table").innerHTML = accountRows(filtered, true);
  }

  async function loadKeyAudit() {
    const box = $("key-audit");
    if (!box) return;
    let audit;
    try {
      audit = await api("/admin/api/sessions/audit");
    } catch (_) {
      box.classList.add("hidden");
      return;
    }
    state.keyAudit = audit;
    const dups = audit.duplicates || [];
    const orphans = audit.orphans || [];
    if (!dups.length && !orphans.length) {
      box.classList.add("hidden");
      box.innerHTML = "";
      return;
    }
    const nameOf = (token) => {
      const m = /^acc#(\d+)$/.exec(token);
      if (!m) return `файл ${token}`;
      const acc = state.accounts.find((a) => String(a.id) === m[1]);
      return acc ? `#${m[1]} ${acc.label}` : `#${m[1]}`;
    };
    const parts = [];
    if (dups.length) {
      parts.push(`<h3>Один ключ в нескольких сессиях — Telegram их убьёт</h3>`);
      parts.push(`<ul>${dups.map((d) =>
        `<li><span class="mono">${esc(d.keyHash)}</span>: ${d.used_by.map(nameOf).map(esc).join(" + ")}</li>`
      ).join("")}</ul>`);
      parts.push(`<p class="muted" style="margin:0 0 8px">Оставь ключ ровно в одной сессии: лишним сделай «Сбросить сессию» и залей их собственный tdata.</p>`);
    }
    if (orphans.length) {
      parts.push(`<h3>Бесхозные .session (ни к одному скауту не привязаны)</h3>`);
      parts.push(`<ul>${orphans.map((o) =>
        `<li><span class="mono">${esc(o.file)}</span> <span class="mono muted">${esc(o.keyHash || "нет ключа")}</span>
          <button type="button" class="btn btn-sm btn-danger" data-orphan="${esc(o.file)}">Удалить файл</button></li>`
      ).join("")}</ul>`);
    }
    box.classList.remove("hidden", "audit-clean");
    box.innerHTML = parts.join("");
  }

  $("key-audit")?.addEventListener("click", async (ev) => {
    const btn = ev.target.closest("[data-orphan]");
    if (!btn) return;
    const file = btn.dataset.orphan;
    if (!window.confirm(`Удалить файл сессии ${file}?`)) return;
    btn.disabled = true;
    try {
      const r = await api(`/admin/api/sessions/orphan/${encodeURIComponent(file)}`, { method: "DELETE" });
      toast(r.ok === false ? (r.error || "не вышло") : `Удалён ${file}`, r.ok === false);
      await loadAccounts();
    } catch (e) { toast(e.message, true); }
    finally { btn.disabled = false; }
  });

  async function loadChatsImport() {
    const d = await api("/admin/api/dashboard");
    state.accounts = d.accounts || [];
    const sel = $("chat-import-account");
    const parsers = state.accounts.filter((a) =>
      ["PARSER", "OBSERVER"].includes(String(a.accountType || "").toUpperCase()));
    sel.innerHTML = `<option value="">Авто (первый PARSER)</option>` +
      parsers.map((a) =>
        `<option value="${a.id}">#${a.id} ${esc(a.label)} · ${esc(typeRu(a.accountType))}</option>`
      ).join("");
  }

  async function loadInboxAccounts() {
    if (!state.accounts.length) {
      const d = await api("/admin/api/dashboard");
      state.accounts = d.accounts || [];
    }
    fillAccountSelect($("inbox-account"), (a) => {
      const t = String(a.accountType || "").toUpperCase();
      return t === "OUTREACH" || t === "SENDER" || true; // покажем все, но лучше sender
    });
  }

  async function loadAudienceAccounts() {
    if (!state.accounts.length) {
      const d = await api("/admin/api/dashboard");
      state.accounts = d.accounts || [];
    }
    fillAccountSelect($("audience-account"), (a) => {
      const t = String(a.accountType || "").toUpperCase();
      return t === "PARSER" || t === "OBSERVER" || true;
    });
  }

  async function openDialog(peer, name, username, unreadHint) {
    state.peer = String(peer);
    state.peerUsername = username || "";
    $("chat-title").textContent = name || username || peer;
    const subBits = [];
    subBits.push(inboxAccountLabel());
    if (username) subBits.push("@" + username);
    if ($("chat-sub")) $("chat-sub").textContent = subBits.join(" · ");
    $("reply-form").classList.remove("hidden");
    const fromList = document.querySelector(`.dialog-item[data-peer="${CSS.escape(String(peer))}"]`);
    const unread = unreadHint != null
      ? Number(unreadHint)
      : Number(fromList?.dataset?.unread || 0);
    updateReadBanner(unread);
    document.querySelectorAll(".dialog-item").forEach((el) => {
      el.classList.toggle("active", el.dataset.peer === state.peer);
    });
    const accountId = Number($("inbox-account").value);
    const d = await api("/admin/api/dialogs/messages", {
      method: "POST",
      json: { accountId, peer: state.peer, limit: 50 },
    });
    const fromArchive = d.source === "archive";
    const archBadge = $("chat-archive-badge");
    if (archBadge) {
      archBadge.classList.toggle("hidden", !fromArchive);
      archBadge.title = fromArchive ? (d.liveError || "сессия недоступна") : "";
    }
    if (fromArchive) {
      $("reply-form").classList.add("hidden");
    }
    if (!d.ok) {
      $("chat-messages").innerHTML = `<div class="tg-empty error">${esc(d.error || "не удалось")}</div>`;
      return;
    }
    const msgs = d.messages || [];
    if (!msgs.length) {
      $("chat-messages").innerHTML = `<div class="tg-empty">${fromArchive
        ? "В архиве пусто — пока сессия жива, сообщения копируются сами"
        : "Сообщений нет — можно написать первым"}</div>`;
      return;
    }
    $("chat-messages").classList.remove("muted");
    $("chat-messages").innerHTML = msgs.map((m) => {
      const cls = [m.out || m.from_me ? "out" : "in", m.deleted ? "deleted" : ""].filter(Boolean).join(" ");
      const metaBits = [fmtDialogTime(m.date) || m.date || ""];
      if (m.edited) metaBits.push("изм.");
      if (m.deleted) metaBits.push("удалено");
      let mediaHtml = "";
      if (m.hasMedia && m.mediaUrl) {
        const src = withToken(m.mediaUrl);
        const kind = String(m.mediaKind || "");
        if (kind === "photo" || (m.mediaMime || "").startsWith("image/")) {
          mediaHtml = `<a href="${esc(src)}" target="_blank" rel="noopener"><img class="tg-arch-media" src="${esc(src)}" alt=""/></a>`;
        } else if (kind === "video" || (m.mediaMime || "").startsWith("video/")) {
          mediaHtml = `<video class="tg-arch-media" controls src="${esc(src)}"></video>`;
        } else if (kind === "voice" || kind === "audio" || (m.mediaMime || "").startsWith("audio/")) {
          mediaHtml = `<audio controls src="${esc(src)}"></audio>`;
        } else {
          const label = esc(m.mediaFileName || "файл");
          mediaHtml = `<a class="tg-arch-file" href="${esc(src)}" target="_blank" rel="noopener">📎 ${label}</a>`;
        }
      } else if (m.mediaKind && !m.text) {
        mediaHtml = `<div class="muted">вложение (${esc(m.mediaKind)}) — файл не сохранён</div>`;
      }
      const textHtml = m.text ? `<div class="tg-arch-text">${esc(m.text)}</div>` : "";
      return `
      <div class="bubble ${cls}">
        ${mediaHtml}${textHtml}
        <span class="meta">${esc(metaBits.join(" · "))}</span>
      </div>`;
    }).join("");
    $("chat-messages").scrollTop = $("chat-messages").scrollHeight;
  }

  function withToken(path) {
    try {
      const u = new URL(path, window.location.origin);
      if (state.token) u.searchParams.set("token", state.token);
      return u.pathname + u.search;
    } catch (_) {
      const join = path.includes("?") ? "&" : "?";
      return state.token ? `${path}${join}token=${encodeURIComponent(state.token)}` : path;
    }
  }

  async function markDialogRead() {
    if (!state.peer) return;
    const accountId = Number($("inbox-account").value);
    const r = await api("/admin/api/dialogs/read", {
      method: "POST",
      json: { accountId, peer: state.peer },
    });
    if (!r.ok) throw new Error(r.error || "не удалось отметить");
    const item = document.querySelector(`.dialog-item[data-peer="${CSS.escape(state.peer)}"]`);
    if (item) {
      item.dataset.unread = "0";
      const badge = item.querySelector(".badge");
      if (badge) {
        badge.classList.add("badge-empty");
        badge.textContent = "";
      }
    }
    const dlg = (state.dialogs || []).find((x) => String(x.peer_id) === String(state.peer));
    if (dlg) dlg.unread = 0;
    updateReadBanner(0);
    toast("Отмечено прочитанным");
  }

  async function loadCampaigns() {
    const list = await api("/admin/api/campaigns");
    if (!list.length) {
      $("campaigns-table").innerHTML = `<p class="muted">Кампаний нет</p>`;
      return;
    }
    $("campaigns-table").innerHTML = `<table><thead><tr>
      <th>ID</th><th>Название</th><th>Статус</th><th>Отправлено</th><th></th>
    </tr></thead><tbody>${list.map((c) => `<tr>
      <td class="mono">#${c.id}</td>
      <td>${esc(c.name)}</td>
      <td>${statusPill(c.status)}</td>
      <td class="mono">${c.sentCount ?? 0}</td>
      <td class="actions">
        ${c.status === "RUNNING"
          ? `<button class="btn btn-sm" data-camp="pause" data-id="${c.id}">Пауза</button>`
          : `<button class="btn btn-sm" data-camp="start" data-id="${c.id}">Запустить</button>`}
      </td>
    </tr>`).join("")}</tbody></table>`;
  }

  async function loadTemplates() {
    const list = await api("/admin/api/templates");
    if (!list.length) {
      $("templates-list").innerHTML = `<p class="muted">Шаблонов пока нет</p>`;
      return;
    }
    $("templates-list").innerHTML = list.map((t) => `
      <article class="template-card">
        <h3>${esc(t.name)} <span class="chip">${esc(t.scenario)}</span></h3>
        <pre>${esc(t.body)}</pre>
      </article>`).join("");
  }

  async function loadLogs() {
    const list = await api("/admin/api/logs");
    if (!list.length) {
      $("logs-table").innerHTML = `<p class="muted">Журнал пуст</p>`;
      return;
    }
    $("logs-table").innerHTML = `<table><thead><tr>
      <th>Когда</th><th>Действие</th><th>Статус</th><th>Акк</th><th>Детали</th><th>Ошибка</th>
    </tr></thead><tbody>${list.map((e) => `<tr>
      <td class="mono">${esc(e.createdAt)}</td>
      <td>${esc(e.action)}</td>
      <td>${statusPill(e.status)}</td>
      <td class="mono">${esc(e.scoutAccountId)}</td>
      <td class="mono">${esc(e.payload)}</td>
      <td class="mono">${esc(e.errorText)}</td>
    </tr>`).join("")}</tbody></table>`;
  }

  async function loadRadar() {
    const d = await api("/admin/api/radar");
    const watches = d.watches || [];
    const hits = d.hits || [];
    $("radar-watches").innerHTML = watches.length
      ? `<table><thead><tr><th>ID</th><th>Ссылка</th><th>Активен</th></tr></thead><tbody>
        ${watches.map((w) => `<tr><td class="mono">#${w.id}</td><td>${esc(w.linkOrUsername)}</td>
        <td>${w.active ? statusPill("OK") : statusPill("OFF")}</td></tr>`).join("")}
        </tbody></table>`
      : `<p class="muted">Наблюдений нет</p>`;
    $("radar-hits").innerHTML = hits.length
      ? `<table><thead><tr><th>Текст</th></tr></thead><tbody>
        ${hits.map((h) => `<tr><td>${esc(h.snippet)}</td></tr>`).join("")}
        </tbody></table>`
      : `<p class="muted">Хитов нет</p>`;
  }

  async function importProxyText(text) {
    const r = await api("/admin/api/proxies/import", { method: "POST", json: { text } });
    toast(r.ok ? `Добавлено в пул: ${r.added}` : (r.error || "Ошибка"), !r.ok);
    if (r.ok) $("proxy-text").value = "";
    await loadProxies();
  }

  // events
  $("login-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    try {
      await login($("token-input").value);
    } catch (e) {
      $("login-error").textContent = e.message || "Ошибка входа";
      $("login-error").classList.remove("hidden");
    }
  });

  $("logout-btn").addEventListener("click", () => logout(false));
  $("refresh-btn").addEventListener("click", () => refresh());

  document.getElementById("nav").addEventListener("click", (ev) => {
    const btn = ev.target.closest("[data-view]");
    if (btn) setView(btn.dataset.view);
  });

  $("import-text-btn").addEventListener("click", async () => {
    try { await importProxyText($("proxy-text").value); }
    catch (e) { toast(e.message, true); }
  });

  $("proxy-file-btn")?.addEventListener("click", () => $("proxy-file").click());
  $("photo-btn")?.addEventListener("click", () => $("profile-photo").click());
  $("accounts-role-filter")?.addEventListener("change", () => loadAccounts());
  $("accounts-life-filter")?.addEventListener("change", () => loadAccounts());

  $("add-scout-form")?.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const fd = new FormData(ev.target);
    try {
      const r = await api("/admin/api/accounts", {
        method: "POST",
        json: {
          label: fd.get("label"),
          accountType: fd.get("accountType"),
          dailyLimit: Number(fd.get("dailyLimit") || 35),
        },
      });
      toast(r.ok ? `Скаут #${r.account?.id} создан` : (r.error || "Ошибка"), !r.ok);
      if ($("add-scout-hint")) $("add-scout-hint").textContent = r.hint || "";
      ev.target.reset();
      await loadAccounts();
    } catch (e) { toast(e.message, true); }
  });

  $("chat-import-btn").addEventListener("click", async () => {
    const btn = $("chat-import-btn");
    try {
      btn.disabled = true;
      $("chat-import-result").textContent = "Кладём в общий пул…";
      const accountId = $("chat-import-account").value;
      const body = { text: $("chat-import-text").value };
      if (accountId) body.accountId = accountId;
      const r = await api("/admin/api/chats/import", { method: "POST", json: body });
      if (r.ok === false || r.error) {
        $("chat-import-result").textContent = r.error || "ошибка";
        toast(r.error || "ошибка", true);
        return;
      }
      const pool = r.pool || {};
      const lines = (r.lines || []).map((l) => l.link || l.title || "").join("\n");
      $("chat-import-result").textContent =
        `${r.detail || "В пуле"}\nчатов ACTIVE: ${pool.chatsActive ?? "?"} · PENDING: ${pool.pending ?? "?"} · JOINED: ${pool.joined ?? "?"}\n\n${lines}`;
      toast(r.detail || "В пуле — join сам ~30с");
      await refreshChatPoolStats();
      await loadChatMatrix();
    } catch (e) {
      toast(e.message, true);
      $("chat-import-result").textContent = e.message || String(e);
    } finally {
      btn.disabled = false;
    }
  });

  async function refreshChatPoolStats() {
    const el = $("chat-pool-stats");
    if (!el) return;
    try {
      const p = await api("/admin/api/chats/pool");
      const warn = p.needMoreWatchers
        ? ` · ⚠ живых парсеров ${p.liveWatchers ?? 0}/${p.minWatchers ?? 2}`
        : ` · парсеров ${p.liveWatchers ?? 0}`;
      el.textContent = `Пул: ${p.chatsActive ?? 0} чатов · очередь ${p.pending ?? 0} · вступили ${p.joined ?? 0} · ошибок ${p.failed ?? 0} · интервал ${p.joinIntervalSec ?? 30}с · join ≤${p.joinLimitPerDay ?? 60}/сутки${warn}`;
    } catch (_) { /* ignore */ }
  }

  function cellEnrolled(status) {
    const s = String(status || "").toUpperCase();
    return s === "PENDING" || s === "JOINING" || s === "JOINED" || s === "FAILED";
  }

  function cellLabel(status) {
    const s = String(status || "").toUpperCase();
    if (s === "JOINED") return "вступил";
    if (s === "PENDING") return "очередь";
    if (s === "JOINING") return "сейчас";
    if (s === "FAILED") return "ошибка";
    if (s === "LEFT") return "снят";
    return "";
  }

  async function loadChatMatrix() {
    const el = $("chat-matrix");
    if (!el) return;
    try {
      const d = await api("/admin/api/chats/matrix");
      const watchers = d.watchers || [];
      const chats = d.chats || [];
      const cells = d.cells || {};
      if (!watchers.length) {
        el.innerHTML = `<p class="muted">Нет PARSER/OBSERVER — заведи наблюдателя</p>`;
        return;
      }
      if (!chats.length) {
        el.innerHTML = `<p class="muted">Пул пуст — сначала «В пул + очередь»</p>`;
        return;
      }
      const head = `<table class="matrix"><thead><tr><th class="sticky">Чат</th>${
        watchers.map((w) => `<th>#${w.id}<br><span class="muted">${esc(w.label)}</span><br><span class="mono">${w.joinsToday ?? 0}/${d.joinLimitPerDay ?? 60}</span></th>`).join("")
      }</tr></thead><tbody>`;
      const body = chats.map((c) => {
        const title = c.title || c.link || ("#" + c.id);
        const tds = watchers.map((w) => {
          const st = cells[`${c.id}:${w.id}`] || "";
          const on = cellEnrolled(st);
          const burned = String(w.status || "").toUpperCase() === "BURNED"
            || String(w.status || "").toUpperCase() === "BANNED";
          return `<td class="matrix-cell">
            <label class="matrix-check">
              <input type="checkbox" data-chat="${c.id}" data-acc="${w.id}" ${on ? "checked" : ""} ${burned ? "disabled" : ""} />
              <span class="cell-st cell-st-${esc(st.toLowerCase() || "none")}">${esc(cellLabel(st))}</span>
            </label>
          </td>`;
        }).join("");
        return `<tr><td class="sticky mono" title="${esc(c.link || "")}">${esc(title)}</td>${tds}</tr>`;
      }).join("");
      el.innerHTML = head + body + "</tbody></table>";
    } catch (e) {
      el.innerHTML = `<p class="error">${esc(e.message)}</p>`;
    }
  }
  $("chat-pool-refresh")?.addEventListener("click", () => {
    refreshChatPoolStats();
    loadChatMatrix();
  });
  $("chat-matrix-refresh")?.addEventListener("click", () => loadChatMatrix());
  refreshChatPoolStats();
  loadChatMatrix();

  $("chat-matrix")?.addEventListener("change", async (ev) => {
    const inp = ev.target.closest("input[type=checkbox][data-chat][data-acc]");
    if (!inp) return;
    const chatId = Number(inp.dataset.chat);
    const accountId = Number(inp.dataset.acc);
    const enrolled = inp.checked;
    try {
      inp.disabled = true;
      const r = await api("/admin/api/chats/membership", {
        method: "POST",
        json: { chatId, accountId, enrolled },
      });
      toast(r.ok === false ? (r.error || "не вышло") : (r.detail || "Ок"), r.ok === false);
      await loadChatMatrix();
      await refreshChatPoolStats();
    } catch (e) {
      toast(e.message, true);
      inp.checked = !enrolled;
    } finally {
      inp.disabled = false;
    }
  });

  $("chat-import-file").addEventListener("change", async (ev) => {
    const file = ev.target.files?.[0];
    if (!file) return;
    try {
      const text = await file.text();
      $("chat-import-text").value = text;
      toast("Файл подставлен — нажми «В пул + очередь»");
    } catch (e) { toast(e.message, true); }
    ev.target.value = "";
  });

  $("proxy-file").addEventListener("change", async (ev) => {
    const file = ev.target.files?.[0];
    if (!file) return;
    try {
      const fd = new FormData();
      fd.append("file", file);
      const url = new URL("/admin/api/proxies/import-file", window.location.origin);
      url.searchParams.set("token", state.token);
      const res = await fetch(url, {
        method: "POST",
        headers: { "X-Admin-Token": state.token },
        body: fd,
      });
      if (!res.ok) throw new Error(await res.text());
      const r = await res.json();
      toast(r.ok ? `Из файла: +${r.added}` : (r.error || "Ошибка"), !r.ok);
      await loadProxies();
    } catch (e) { toast(e.message, true); }
    ev.target.value = "";
  });

  const drop = $("proxy-drop");
  ["dragenter", "dragover"].forEach((evName) => {
    drop.addEventListener(evName, (e) => {
      e.preventDefault();
      drop.classList.add("drag");
    });
  });
  ["dragleave", "drop"].forEach((evName) => {
    drop.addEventListener(evName, (e) => {
      e.preventDefault();
      drop.classList.remove("drag");
    });
  });
  drop.addEventListener("drop", async (e) => {
    const file = e.dataTransfer?.files?.[0];
    if (!file) return;
    try {
      const text = await file.text();
      await importProxyText(text);
    } catch (err) { toast(err.message, true); }
  });

  $("check-proxies-btn").addEventListener("click", async () => {
    try {
      $("check-proxies-btn").disabled = true;
      const r = await api("/admin/api/proxies/check", { method: "POST" });
      toast(r.ok ? `Живых: ${r.alive}, мёртвых: ${r.dead}` : (r.error || "Ошибка"), !r.ok);
      await loadProxies();
    } catch (e) { toast(e.message, true); }
    finally { $("check-proxies-btn").disabled = false; }
  });

  $("pull-inbox-btn").addEventListener("click", async () => {
    try {
      const r = await api("/admin/api/proxies/pull-inbox", { method: "POST" });
      toast(r.ok ? `Inbox: +${r.added ?? 0}` : (r.error || "Ошибка"), !r.ok);
      await loadProxies();
    } catch (e) { toast(e.message, true); }
  });

  $("purge-proxies-btn").addEventListener("click", async () => {
    try {
      const r = await api("/admin/api/proxies/purge", { method: "POST" });
      toast(r.ok ? "Мёртвые убраны" : (r.error || "Ошибка"), !r.ok);
      await loadProxies();
    } catch (e) { toast(e.message, true); }
  });

  document.addEventListener("click", async (ev) => {
    const table = $("accounts-table");
    if (!table || !table.contains(ev.target)) return;
    const btn = ev.target.closest("[data-act]");
    if (btn) {
      const id = btn.dataset.id;
      const act = btn.dataset.act;
      try {
        btn.disabled = true;
        if (act === "open" || act === "profile") {
          await openScout(id);
          return;
        }
        if (act === "delete") {
          const acc = state.accounts.find((x) => String(x.id) === String(id));
          const name = acc ? `${acc.label} (#${id})` : `#${id}`;
          if (!window.confirm(`Удалить скаута ${name}?\nКарточка, сессия и привязка прокси будут снесены.`)) {
            return;
          }
          const r = await api(`/admin/api/accounts/${id}`, { method: "DELETE" });
          toast(r.ok === false ? (r.error || "не вышло") : (r.detail || "Удалён"), r.ok === false);
          if (String(state.selectedAccountId) === String(id)) {
            state.selectedAccountId = null;
            if ($("connect-body")) $("connect-body").classList.add("hidden");
          }
          await loadAccounts();
          return;
        }
        if (act === "wipe") {
          if (!window.confirm(`Снести .session у #${id}?\nДанные покупки (телефон / User ID) останутся — нужен новый tdata.zip.`)) {
            return;
          }
          const r = await api(`/admin/api/accounts/${id}/wipe-session`, { method: "POST" });
          toast(r.ok === false ? (r.error || "не вышло") : (r.detail || "Сессия снесена"), r.ok === false);
          await loadAccounts();
          return;
        }
        if (act === "burn") {
          const r = await api(`/admin/api/accounts/${id}/mark-burned`, { method: "POST" });
          const msg = r.detail || (r.failover && r.failover.detail) || "Перенесён в «Сгоревшие»";
          toast(r.ok === false ? (r.error || "не вышло") : msg, r.ok === false);
          await loadAccounts();
          return;
        }
        if (act === "failover") {
          const alive = state.accounts.filter((a) =>
            ["PARSER", "OBSERVER"].includes(String(a.accountType || "").toUpperCase())
            && !isBurned(a)
            && String(a.id) !== String(id));
          let toAccountId = null;
          if (alive.length === 1) {
            toAccountId = alive[0].id;
          } else if (alive.length > 1) {
            const pick = window.prompt(
              "ID живого PARSER/OBSERVER, кому отдать пул (пусто = авто):\n"
              + alive.map((a) => `#${a.id} ${a.label}`).join("\n"));
            if (pick === null) return;
            if (pick.trim()) toAccountId = Number(pick.trim());
          }
          const body = toAccountId ? { toAccountId } : {};
          const r = await api(`/admin/api/accounts/${id}/failover`, { method: "POST", json: body });
          toast(r.ok === false ? (r.error || r.detail || "не вышло") : (r.detail || "Пул перенесён"), r.ok === false);
          await loadAccounts();
          await refreshChatPoolStats();
          return;
        }
        if (act === "enroll") {
          const r = await api(`/admin/api/accounts/${id}/enroll-pool`, { method: "POST" });
          toast(r.ok === false ? (r.error || "не вышло") : (r.detail || "Ок"), r.ok === false);
          await refreshChatPoolStats();
          await loadChatMatrix();
          return;
        }
        if (act === "backfill") {
          const r = await api(`/admin/api/accounts/${id}/archive-backfill`, {
            method: "POST",
            json: { force: true },
          });
          const started = r.started === true;
          const msg = started
            ? "Бэкфилл архива запущен"
            : (r.reason === "already running"
              ? "Уже качает историю"
              : r.reason === "recent"
                ? "Недавно уже было — жми ещё раз через неделю или force"
                : (r.error || r.reason || "не стартовал"));
          toast(r.ok === false ? (r.error || "не вышло") : msg, r.ok === false || (!started && r.reason !== "already running"));
          return;
        }
        const map = {
          pause: `/admin/api/accounts/${id}/pause`,
          resume: `/admin/api/accounts/${id}/resume`,
          spambot: `/admin/api/accounts/${id}/spambot`,
          rotate: `/admin/api/accounts/${id}/rotate-proxy`,
          assign: `/admin/api/accounts/${id}/assign-proxy`,
        };
        if (!map[act]) return;
      const r = await api(map[act], { method: "POST" });
      const err = r.error || r.detail || "";
      toast(r.ok === false ? (err || "не вышло") : (err && err !== "null" ? `Готово: ${err}` : "Готово"), r.ok === false);
        await loadAccounts();
      } catch (e) { toast(e.message, true); }
      finally { btn.disabled = false; }
      return;
    }
    const row = ev.target.closest("tr.account-row[data-id]");
    if (row) await openScout(row.dataset.id);
  });

  $("session-file")?.addEventListener("change", async (ev) => {
    const file = ev.target.files?.[0];
    const id = state.selectedAccountId || $("profile-form")?.accountId?.value;
    if (!file || !id) {
      toast("Сначала открой скаута", true);
      ev.target.value = "";
      return;
    }
    try {
      const fd = new FormData();
      fd.append("file", file);
      const url = new URL(`/admin/api/accounts/${id}/session`, window.location.origin);
      url.searchParams.set("token", state.token);
      const res = await fetch(url, {
        method: "POST",
        headers: { "X-Admin-Token": state.token },
        body: fd,
      });
      const text = await res.text();
      let r;
      try { r = JSON.parse(text); } catch { throw new Error(text || res.statusText); }
      if (!res.ok) throw new Error(r.error || text);
      toast(r.authorized ? "Сессия подключена" : (r.verifyError || r.error || "Файл сохранён, авторизация не прошла"), !r.authorized);
      if (r.me) fillTgCard(id, r.me);
      await openScout(id);
    } catch (e) { toast(e.message, true); }
    ev.target.value = "";
  });

  $("tdata-file")?.addEventListener("change", async (ev) => {
    const file = ev.target.files?.[0];
    const id = state.selectedAccountId || $("profile-form")?.accountId?.value;
    if (!file || !id) {
      toast("Сначала открой скаута", true);
      ev.target.value = "";
      return;
    }
    if (!/\.zip$/i.test(file.name)) {
      toast("Нужен .zip с папкой tdata (не сама папка)", true);
      ev.target.value = "";
      return;
    }
    try {
      toast("Конвертирую tdata…");
      const fd = new FormData();
      fd.append("file", file);
      const url = new URL(`/admin/api/accounts/${id}/tdata`, window.location.origin);
      url.searchParams.set("token", state.token);
      const res = await fetch(url, {
        method: "POST",
        headers: { "X-Admin-Token": state.token },
        body: fd,
      });
      const text = await res.text();
      let r;
      try { r = JSON.parse(text); } catch { throw new Error(text || res.statusText); }
      if (!res.ok) throw new Error(r.error || text);
      toast(r.ok ? "tdata подключена — должен быть бейдж «В сети»" : (r.error || "Ошибка tdata"), !r.ok);
      if (r.me) fillTgCard(id, r.me);
      await openScout(id);
      if (r.ok && r.authorized !== false) {
        toast("Готово: Запустить → Открыть Telegram");
      }
    } catch (e) { toast(e.message, true); }
    ev.target.value = "";
  });

  $("auth-key-form")?.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const id = state.selectedAccountId || $("profile-form")?.accountId?.value;
    if (!id) {
      toast("Сначала открой скаута", true);
      return;
    }
    const fd = new FormData(ev.target);
    try {
      const r = await api(`/admin/api/accounts/${id}/auth-key`, {
        method: "POST",
        json: {
          authKeyHex: fd.get("authKeyHex"),
          dcId: Number(fd.get("dcId") || 2),
        },
      });
      toast(r.ok ? "Сессия из auth_key ок" : (r.error || "Ошибка"), !r.ok);
      if (r.me) fillTgCard(id, r.me);
      await openScout(id);
    } catch (e) { toast(e.message, true); }
  });

  $("btn-restore-secrets")?.addEventListener("click", async () => {
    const id = state.selectedAccountId;
    if (!id) return toast("Сначала открой скаута", true);
    try {
      const r = await api(`/admin/api/accounts/${id}/restore-secrets`, { method: "POST" });
      toast(r.ok ? "Восстановлено из secrets" : (r.error || "нет secrets"), !r.ok);
      if (r.identity) fillIdentityCard(r.identity, r.me);
      if (r.me) fillTgCard(id, r.me);
      await openScout(id);
    } catch (e) { toast(e.message, true); }
  });

  $("identity-form")?.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const id = state.selectedAccountId;
    if (!id) return toast("Сначала открой скаута", true);
    const fd = new FormData(ev.target);
    try {
      const body = {
        phone: String(fd.get("phone") || "").trim() || null,
        shopNote: String(fd.get("shopNote") || "").trim(),
      };
      const uid = String(fd.get("userId") || "").trim();
      const dc = String(fd.get("dcId") || "").trim();
      if (uid) body.userId = Number(uid);
      if (dc) body.dcId = Number(dc);
      const r = await api(`/admin/api/accounts/${id}/identity`, { method: "POST", json: body });
      toast(r.ok ? "Данные акка сохранены" : (r.error || "ошибка"), !r.ok);
      if (r.identity) fillIdentityCard(r.identity, null);
    } catch (e) { toast(e.message, true); }
  });

  $("profile-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const fd = new FormData(ev.target);
    const id = fd.get("accountId");
    try {
      const r = await api(`/admin/api/accounts/${id}/profile`, {
        method: "POST",
        json: {
          firstName: fd.get("firstName"),
          lastName: fd.get("lastName"),
          username: fd.get("username"),
          about: fd.get("about"),
        },
      });
      toast(r.ok ? "Сохранено в Telegram" : (r.error || "Ошибка"), !r.ok);
      if (r.me) $("profile-me").textContent = JSON.stringify(r.me, null, 2);
    } catch (e) { toast(e.message, true); }
  });

  $("profile-photo").addEventListener("change", async (ev) => {
    const file = ev.target.files?.[0];
    const id = $("profile-form").accountId?.value;
    if (!file || !id) {
      toast("Сначала открой профиль скаута", true);
      return;
    }
    try {
      const fd = new FormData();
      fd.append("file", file);
      const url = new URL(`/admin/api/accounts/${id}/photo`, window.location.origin);
      url.searchParams.set("token", state.token);
      const res = await fetch(url, {
        method: "POST",
        headers: { "X-Admin-Token": state.token },
        body: fd,
      });
      if (!res.ok) throw new Error(await res.text());
      const r = await res.json();
      toast(r.ok ? "Фото обновлено" : (r.error || "Ошибка"), !r.ok);
      if (r.me) $("profile-me").textContent = JSON.stringify(r.me, null, 2);
    } catch (e) { toast(e.message, true); }
    ev.target.value = "";
  });

  $("btn-test-login")?.addEventListener("click", async () => {
    if (!state.selectedAccountId) return toast("Сначала открой скаута", true);
    await openScout(state.selectedAccountId);
  });
  $("btn-start-scout")?.addEventListener("click", async () => {
    const id = state.selectedAccountId;
    if (!id) return toast("Сначала открой скаута", true);
    if (!state.scoutOnline) return toast("Сначала войди в TG", true);
    try {
      const r = await api(`/admin/api/accounts/${id}/resume`, { method: "POST" });
      toast(r.ok === false ? (r.error || "не вышло") : "Скаут ACTIVE — можно работать", r.ok === false);
      await loadAccounts();
      await openScout(id);
    } catch (e) { toast(e.message, true); }
  });
  $("btn-pause-scout")?.addEventListener("click", async () => {
    const id = state.selectedAccountId;
    if (!id) return toast("Сначала открой скаута", true);
    try {
      const r = await api(`/admin/api/accounts/${id}/pause`, { method: "POST" });
      toast(r.ok === false ? (r.error || "не вышло") : "На паузе", r.ok === false);
      await loadAccounts();
      await openScout(id);
    } catch (e) { toast(e.message, true); }
  });
  $("btn-open-tg")?.addEventListener("click", () => openTelegramWorkspace());

  $("inbox-account")?.addEventListener("change", async () => {
    const accountId = Number($("inbox-account").value);
    state.inboxAccountId = accountId;
    state.selectedAccountId = accountId;
    try {
      await loadDialogsForAccount(accountId);
      toast(`Чатов: ${state.dialogs.length}`);
    } catch (e) { toast(e.message, true); }
  });

  $("inbox-load")?.addEventListener("click", async () => {
    try {
      const accountId = Number($("inbox-account").value);
      state.inboxAccountId = accountId;
      await loadDialogsForAccount(accountId);
      toast(`Чатов: ${state.dialogs.length}`);
    } catch (e) { toast(e.message, true); }
  });

  $("inbox-backfill")?.addEventListener("click", async () => {
    const accountId = Number($("inbox-account").value);
    if (!accountId) return toast("Выбери скаута", true);
    try {
      const r = await api(`/admin/api/accounts/${accountId}/archive-backfill`, {
        method: "POST",
        json: { force: true },
      });
      toast(
        r.ok === false
          ? (r.error || "не вышло")
          : (r.started ? "Бэкфилл запущен — обнови кабинет через минуту" : (r.reason === "already running" ? "Уже качает" : (r.error || "не стартовал"))),
        r.ok === false
      );
    } catch (e) { toast(e.message, true); }
  });

  $("kind-tabs")?.addEventListener("click", (ev) => {
    const tab = ev.target.closest(".kind-tab");
    if (!tab) return;
    state.dialogKind = tab.dataset.kind || "all";
    document.querySelectorAll(".kind-tab").forEach((t) => t.classList.toggle("active", t === tab));
    renderDialogs(state.dialogs);
  });

  $("dialog-filter")?.addEventListener("input", () => renderDialogs(state.dialogs));

  $("resolve-btn")?.addEventListener("click", async () => {
    try {
      const accountId = Number($("inbox-account").value || state.inboxAccountId);
      const query = ($("resolve-query")?.value || "").trim();
      if (!accountId || !query) return toast("Укажи скаута и @/ссылку", true);
      const r = await api("/admin/api/dialogs/resolve", {
        method: "POST",
        json: { accountId, query },
      });
      if (!r.ok) throw new Error(r.error || "не найден");
      const p = r.peer || {};
      if (p.kind === "invite") {
        toast("Это инвайт — жми «Вступить по ссылке»");
        return;
      }
      await openDialog(String(p.peer_id), p.name || p.username, p.username || "");
      toast(`Открыт: ${p.name || p.username}`);
    } catch (e) { toast(e.message, true); }
  });

  $("join-btn")?.addEventListener("click", async () => {
    try {
      const accountId = Number($("inbox-account").value || state.inboxAccountId);
      const link = ($("resolve-query")?.value || "").trim();
      if (!accountId || !link) return toast("Вставь ссылку t.me/…", true);
      const r = await api("/admin/api/chats/import", {
        method: "POST",
        json: { accountId: String(accountId), text: link },
      });
      if (r.error) throw new Error(r.error);
      const line = (r.lines && r.lines[0]) || {};
      toast(line.ok ? `Вступил: ${line.title || link}` : (line.error || "join fail"), !line.ok);
      await loadDialogsForAccount(accountId);
    } catch (e) { toast(e.message, true); }
  });

  $("dialogs-list").addEventListener("click", async (ev) => {
    const item = ev.target.closest(".dialog-item");
    if (!item) return;
    try {
      await openDialog(item.dataset.peer, item.dataset.name, item.dataset.user, item.dataset.unread);
    } catch (e) { toast(e.message, true); }
  });

  async function onMarkReadClick() {
    try { await markDialogRead(); } catch (e) { toast(e.message, true); }
  }
  $("mark-read-btn")?.addEventListener("click", onMarkReadClick);
  $("mark-read-banner-btn")?.addEventListener("click", onMarkReadClick);

  $("reply-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    if (!state.peer) return;
    const text = $("reply-text").value.trim();
    if (!text) return;
    try {
      const accountId = Number($("inbox-account").value);
      const r = await api("/admin/api/dialogs/reply", {
        method: "POST",
        json: { accountId, peer: state.peer, text },
      });
      toast(r.ok ? "Ответ ушёл" : (r.error || "Ошибка"), !r.ok);
      if (r.ok) {
        $("reply-text").value = "";
        await openDialog(state.peer, $("chat-title").textContent, state.peerUsername);
      }
    } catch (e) { toast(e.message, true); }
  });

  $("audience-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const fd = new FormData(ev.target);
    try {
      const r = await api("/admin/api/audience/parse", {
        method: "POST",
        json: {
          accountId: Number(fd.get("accountId") || $("audience-account").value),
          link: fd.get("link"),
          limit: Number(fd.get("limit") || 300),
          minScore: Number(fd.get("minScore") || 35),
        },
      });
      if (!r.ok) throw new Error(r.error || "парсинг не вышел");
      const users = r.users || [];
      $("audience-count").textContent = String(users.length);
      if (!users.length) {
        $("audience-list").innerHTML = `<p class="muted">Никого не нашли по фильтру. Снизь мин. балл.</p>`;
        return;
      }
      $("audience-list").innerHTML = `<table><thead><tr>
        <th>@</th><th>Имя</th><th>Балл</th><th>Тир</th><th>Почему</th>
      </tr></thead><tbody>${users.map((u) => `<tr>
        <td class="mono">@${esc(u.username)}</td>
        <td>${esc(u.display_name)}</td>
        <td class="mono">${esc(u.score)}</td>
        <td>${statusPill(String(u.tier || "").toUpperCase())}</td>
        <td class="mono">${esc((u.reasons || []).join(", "))}</td>
      </tr>`).join("")}</tbody></table>`;
      toast(`ЦА: ${users.length} чел.`);
    } catch (e) { toast(e.message, true); }
  });

  $("campaigns-table").addEventListener("click", async (ev) => {
    const btn = ev.target.closest("[data-camp]");
    if (!btn) return;
    const id = btn.dataset.id;
    const path = btn.dataset.camp === "pause"
      ? `/admin/api/campaigns/${id}/pause`
      : `/admin/api/campaigns/${id}/start`;
    try {
      await api(path, { method: "POST" });
      toast("Готово");
      await loadCampaigns();
    } catch (e) { toast(e.message, true); }
  });

  $("template-form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const fd = new FormData(ev.target);
    try {
      await api("/admin/api/templates", {
        method: "POST",
        json: {
          scenario: fd.get("scenario"),
          name: fd.get("name"),
          body: fd.get("body"),
        },
      });
      toast("Шаблон сохранён");
      ev.target.reset();
      await loadTemplates();
    } catch (e) { toast(e.message, true); }
  });

  const params = new URLSearchParams(window.location.search);
  const qToken = params.get("token");
  if (qToken) state.token = qToken;

  if (state.token) {
    login(state.token).catch(() => showLogin("Токен не принят"));
  } else {
    showLogin();
  }
})();
