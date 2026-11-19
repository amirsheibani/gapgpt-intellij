(function () {
  "use strict";

  // ==================================================================== globals
  const $ = (id) => document.getElementById(id);
  const inputEl = $("input");

  const G = {
    sendOnEnter: true,
    projectPath: "",
    model: "",
    models: [],
    modelsLoading: false,
    modelsError: "",
    shareSelectionSetting: true,
    autoFollow: true,
    useGraphify: false,
    ctx: null,
    hasKey: null,
    commands: null,
    recent: [],
    mention: { open: false, items: [], idx: 0, reqId: 0, start: -1 },
    slash: { open: false, items: [], idx: 0 },
    view: "chat",
    chats: {},
    order: [],
    active: null,
    seq: 0,
    logLines: [],
    logFilter: "ALL",
    logDebug: false,
    git: null,
    skills: null,
    skillQ: "",
    rules: null,
    importPreview: null,
    importNote: null,
    lang: "en",
  };

  function toIde(obj) {
    if (window.__toIde) window.__toIde(JSON.stringify(obj));
  }
  function chat() {
    return G.chats[G.active];
  }

  // ==================================================================== i18n + text direction
  /** Picks the English or Persian string for the current UI language. Works for any value type. */
  const tr = (en, fa) => (G.lang === "fa" ? fa : en);
  const uiDir = () => (G.lang === "fa" ? "rtl" : "ltr");
  const thinking = () => tr("Thinking…", "در حال فکر کردن…");
  const RTL_CH = /[\u0591-\u07FF\u0860-\u08FF\uFB1D-\uFDFF\uFE70-\uFEFC]/g;
  const LTR_CH = /[A-Za-z\u00C0-\u024F\u0370-\u03FF\u0400-\u04FF]/g;
  /**
   * Direction of a piece of prose. Unlike dir="auto" (first strong character) it weighs the whole text, so a
   * Persian sentence that starts with an English word ("React یک کتابخانه است") is still right-to-left.
   * Code, URLs and paths are ignored; text without letters gets [fallback] (default: the UI direction).
   */
  function textDir(s, fallback) {
    s = String(s || "")
      .replace(/```[\s\S]*?(```|$)/g, " ")
      .replace(/`[^`\n]*`/g, " ")
      .replace(/https?:\/\/\S+/g, " ")
      .replace(/\S*[\/\\@]\S*/g, " ");
    const r = (s.match(RTL_CH) || []).length;
    const l = (s.match(LTR_CH) || []).length;
    if (!r && !l) return fallback || uiDir();
    return r >= 0.3 * (r + l) ? "rtl" : "ltr";
  }

  // ==================================================================== markdown (small, safe)
  function esc(s) {
    return String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  const FILE_RE = /^@?[\w.\-\/\\]+\.[A-Za-z0-9]{1,8}(:\d+|#L\d+(-\d+)?)?$/;

  function inline(text) {
    const codes = [];
    let s = text.replace(/`([^`\n]+)`/g, (_, c) => {
      codes.push(c);
      return "\u0000" + (codes.length - 1) + "\u0000";
    });
    s = esc(s);
    s = s.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>");
    s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank">$1</a>');
    s = s.replace(/\u0000(\d+)\u0000/g, (_, i) => {
      const c = codes[+i];
      const isFile = FILE_RE.test(c);
      return '<code class="inline' + (isFile ? ' file" data-file="' + esc(c) : "") + '">' + esc(c) + "</code>";
    });
    return s;
  }

  function codeBlock(lang, code) {
    const id = "cb" + Math.random().toString(36).slice(2);
    return (
      '<div class="codeblock"><div class="bar"><span>' + esc(lang || "") + '</span><span class="btns">' +
      '<button data-act="insert" data-target="' + id + '" title="' + tr("Insert at caret", "درج در ویرایشگر") + '">' + tr("Insert", "درج") + "</button>" +
      '<button data-act="copy" data-target="' + id + '" title="' + tr("Copy", "کپی") + '">' + tr("Copy", "کپی") + "</button></span></div>" +
      '<pre><code id="' + id + '">' + esc(code) + "</code></pre></div>"
    );
  }

  /** Small markdown renderer. Every block gets its own dir, so mixed Persian / English answers never break. */
  function markdown(src) {
    const lines = src.replace(/\r\n/g, "\n").split("\n");
    const base = textDir(src);
    const dirOf = (t) => ' dir="' + textDir(t, base) + '"';
    let out = "";
    let i = 0;
    let para = [];
    const flush = () => {
      if (para.length) {
        out += "<p" + dirOf(para.join("\n")) + ">" + para.map(inline).join("<br>") + "</p>";
        para = [];
      }
    };
    while (i < lines.length) {
      const line = lines[i];
      const fence = line.match(/^\s*(```+|~~~+)\s*([\w+#.\-]*)/);
      if (fence) {
        flush();
        const marker = fence[1];
        const buf = [];
        i++;
        while (i < lines.length && !lines[i].trim().startsWith(marker)) buf.push(lines[i++]);
        i++;
        out += codeBlock(fence[2], buf.join("\n"));
        continue;
      }
      const h = line.match(/^(#{1,4})\s+(.*)$/);
      if (h) {
        flush();
        out += "<h" + h[1].length + dirOf(h[2]) + ">" + inline(h[2]) + "</h" + h[1].length + ">";
        i++;
        continue;
      }
      if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
        flush();
        out += "<hr>";
        i++;
        continue;
      }
      if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|[\s:\-|]+\|\s*$/.test(lines[i + 1])) {
        flush();
        const row = (l) => l.trim().replace(/^\||\|$/g, "").split("|").map((c) => c.trim());
        let rows = "<tr>" + row(line).map((c) => "<th>" + inline(c) + "</th>").join("") + "</tr>";
        const text = [line];
        i += 2;
        while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) {
          rows += "<tr>" + row(lines[i]).map((c) => "<td>" + inline(c) + "</td>").join("") + "</tr>";
          text.push(lines[i]);
          i++;
        }
        out += "<table" + dirOf(text.join("\n")) + ">" + rows + "</table>";
        continue;
      }
      const li = line.match(/^\s*([-*+]|\d+[.)])\s+(.*)$/);
      if (li) {
        flush();
        const tag = /\d/.test(li[1]) ? "ol" : "ul";
        const items = [];
        while (i < lines.length) {
          const m = lines[i].match(/^\s*([-*+]|\d+[.)])\s+(.*)$/);
          if (!m) break;
          items.push(m[2]);
          i++;
        }
        out += "<" + tag + dirOf(items.join("\n")) + ">" + items.map((x) => "<li>" + inline(x) + "</li>").join("") + "</" + tag + ">";
        continue;
      }
      if (/^\s*>/.test(line)) {
        flush();
        const buf = [];
        while (i < lines.length && /^\s*>/.test(lines[i])) buf.push(lines[i++].replace(/^\s*>\s?/, ""));
        out += "<blockquote" + dirOf(buf.join("\n")) + ">" + buf.map(inline).join("<br>") + "</blockquote>";
        continue;
      }
      if (line.trim() === "") flush();
      else para.push(line);
      i++;
    }
    flush();
    return out;
  }

  // ==================================================================== small helpers
  function relPath(p) {
    p = String(p || "");
    if (G.projectPath && p.startsWith(G.projectPath)) p = p.slice(G.projectPath.length).replace(/^[\/\\]/, "");
    return p;
  }
  function tildePath(p) {
    const m = /^\/Users\/[^/]+|^\/home\/[^/]+/.exec(p || "");
    return m ? "~" + p.slice(m[0].length) : p || "—";
  }
  function fmtNum(n) {
    n = n || 0;
    return n >= 1e6 ? (n / 1e6).toFixed(1) + "M" : n >= 1e3 ? (n / 1e3).toFixed(1) + "k" : String(n);
  }
  function modelLabel(id) {
    return id || tr("Default", "پیش‌فرض");
  }

  // ==================================================================== chats (tabs)
  function mkTarget(el) {
    return { el, turn: null, block: null };
  }

  function welcomeEl() {
    const w = $("tplWelcome").content.cloneNode(true).querySelector(".welcome-wrap");
    fillWelcome(w);
    return w;
  }
  function fillWelcome(w) {
    w.querySelector(".wt").innerHTML = tr("Welcome to <b>GapGPT</b>!", "به <b>GapGPT</b> خوش آمدید!");
    w.querySelector(".cwd").textContent = "cwd: " + G.projectPath;
    const tips = tr([
      "Ask questions about code or anything else — in English or Persian.",
      "Use <kbd>@</kbd> to attach project files and <kbd>/</kbd> to run your skills.",
      "Rules and skills live in the <code>.gapgpt</code> folder; see the Rules and Skills tabs.",
      "<kbd>Esc</kbd> stops the reply.",
    ], [
      "درباره کد یا هر موضوع دیگری سؤال بپرسید — به فارسی یا انگلیسی.",
      "با <kbd>@</kbd> فایل‌های پروژه را پیوست و با <kbd>/</kbd> اسکیل‌هایتان را اجرا کنید.",
      "قوانین و اسکیل‌ها در پوشهٔ <code>.gapgpt</code> هستند؛ تب‌های «قوانین» و «اسکیل‌ها» را ببینید.",
      "<kbd>Esc</kbd> پاسخ را متوقف می‌کند.",
    ]);
    const sugg = tr(
      ["Explain the structure of this project", "Find possible bugs in the code", "Write a README for this project"],
      ["ساختار این پروژه را توضیح بده", "باگ‌های احتمالی کد را پیدا کن", "برای این پروژه یک README فارسی بنویس"]);
    w.querySelector(".tips").innerHTML = "<div>" + tr("Tips for getting started:", "نکته‌هایی برای شروع:") + "</div><ol>" +
      tips.map((x) => "<li>" + x + "</li>").join("") + "</ol>" + sugg.map((x) => '<button class="sugg">' + esc(x) + "</button>").join("");
  }

  function freshUsage() {
    // Totals of finished replies (from the API's `usage`) and the live estimate of the reply being streamed.
    return { prompt: 0, completion: 0, total: 0, cost: 0, priced: true, liveChars: 0 };
  }
  /** "$0.0123" (4 decimals under $1, else 2). */
  const fmtUsd = (v) => "$" + (v < 1 ? v.toFixed(4) : v.toFixed(2));
  /** Rough token estimate for streamed text (~3.5 chars per token, Persian/code mixed). */
  const estTok = (chars) => chars / 3.5;

  let infoScheduled = false;
  function scheduleInfo(c) {
    if (c !== chat() || infoScheduled) return;
    infoScheduled = true;
    requestAnimationFrame(() => {
      infoScheduled = false;
      renderInfo();
    });
  }

  function newChat() {
    const id = "c" + ++G.seq;
    const log = document.createElement("div");
    log.className = "chatlog";
    log.appendChild(welcomeEl());
    $("chatLogs").appendChild(log);
    G.chats[id] = { id, title: "", busy: false, log, main: mkTarget(log), usage: freshUsage(), startedAt: 0, statusText: "", shareCtx: true, failed: false };
    G.order.push(id);
    activate(id);
    return G.chats[id];
  }

  function closeChat(id) {
    const c = G.chats[id];
    if (!c) return;
    toIde({ type: "closeChat", chat: id });
    c.log.remove();
    delete G.chats[id];
    G.order = G.order.filter((x) => x !== id);
    if (!G.order.length) newChat();
    else if (G.active === id) activate(G.order[G.order.length - 1]);
    else renderTabs();
  }

  function clearChat(c) {
    toIde({ type: "clearChat", chat: c.id });
    c.log.innerHTML = "";
    c.log.appendChild(welcomeEl());
    Object.assign(c, { title: "", main: mkTarget(c.log), usage: freshUsage(), failed: false, busy: false });
    notice(c, c.main, tr("⎿  New conversation started.", "⎿  گفتگوی جدید شروع شد."), "");
    refreshChatUi();
  }

  // ==================================================================== plan mode
  function renderMode() {
    const c = chat();
    const b = $("modeChip");
    if (!b) return;
    const on = !!(c && c.plan);
    b.classList.toggle("on", on);
    b.textContent = on ? tr("⏸ plan mode", "⏸ حالت طرح") : tr("⏵ agent", "⏵ ایجنت");
    b.title = on
      ? tr("Plan mode: the assistant only reads and plans; it asks before changing anything. Click or Shift+Tab to turn off.",
        "حالت طرح: دستیار فقط می‌خواند و طرح می‌دهد و قبل از هر تغییر می‌پرسد. برای خاموش کردن کلیک کنید یا Shift+Tab.")
      : tr("Agent mode: the assistant may edit files and run commands (with your approval). Click or Shift+Tab for plan mode.",
        "حالت ایجنت: دستیار می‌تواند فایل‌ها را ویرایش و دستور اجرا کند (با تأیید شما). برای حالت طرح کلیک کنید یا Shift+Tab.");
  }
  function togglePlan() {
    const c = chat();
    if (!c || c.busy) return;
    c.plan = !c.plan;
    renderMode();
  }

  // ==================================================================== graphify toggle
  function renderGraphify() {
    const b = $("graphifyChip");
    if (!b) return;
    const on = !!G.useGraphify;
    b.classList.toggle("on", on);
    b.textContent = on ? "◆ graphify" : "◇ graphify";
    b.title = on
      ? tr("graphify on: the assistant starts from the project's code graph. Click to turn off.",
        "graphify روشن است: دستیار از گراف کد پروژه شروع می‌کند. برای خاموش کردن کلیک کنید.")
      : tr("graphify off: the code graph is not used. Click to turn on (builds the graph the first time).",
        "graphify خاموش است: از گراف کد استفاده نمی‌شود. برای روشن کردن کلیک کنید (بار اول گراف ساخته می‌شود).");
  }
  function toggleGraphify() {
    G.useGraphify = !G.useGraphify;
    renderGraphify();
    const c = chat();
    toIde(c ? { type: "setGraphify", value: G.useGraphify, chat: c.id } : { type: "setGraphify", value: G.useGraphify });
  }

  // ==================================================================== todo list
  function renderTodos(c, items) {
    flushBlock(c);
    const d = document.createElement("div");
    d.className = "tool done";
    d.innerHTML = '<div class="head"><span class="bullet">⏺</span><div class="call"><span class="name">Update Todos</span></div></div>' +
      '<div class="result"><span class="elbow">⎿</span><div class="body todos"></div></div>';
    const body = d.querySelector(".todos");
    items.forEach((t) => {
      const r = document.createElement("div");
      const st = t.status || "pending";
      r.className = "t " + st;
      r.dir = textDir(t.content);
      r.innerHTML = '<span class="box"></span><span class="tx"></span>';
      r.querySelector(".box").textContent = st === "completed" ? "☒" : st === "in_progress" ? "◐" : "☐";
      r.querySelector(".tx").textContent = t.content || "";
      body.appendChild(r);
    });
    turnEl(c.main).appendChild(d);
    scrollDown(c.main);
  }

  // ==================================================================== saved conversations (/resume)
  function openResume() {
    const c = chat();
    if (!c || c.busy) return;
    G.resumeAnchor = $("btnMore2");
    showMenu(G.resumeAnchor, [{ header: tr("Loading…", "در حال بارگذاری…") }]);
    toIde({ type: "listSessions", chat: c.id });
  }

  function fmtWhen(ms) {
    if (!ms) return "";
    const d = new Date(ms);
    const mins = Math.round((Date.now() - ms) / 60000);
    if (mins < 1) return tr("just now", "همین الان");
    if (mins < 60) return mins + tr(" min ago", " دقیقه پیش");
    if (mins < 60 * 24) return Math.round(mins / 60) + tr(" h ago", " ساعت پیش");
    return d.toLocaleDateString(G.lang === "fa" ? "fa-IR" : "en-US") + " " + d.toLocaleTimeString(G.lang === "fa" ? "fa-IR" : "en-US", { hour: "2-digit", minute: "2-digit" });
  }

  function showSessions(c, p) {
    if (!G.resumeAnchor || c !== chat()) return;
    const items = (p.items || []).filter((x) => !x.current);
    const list = [{ header: tr("Resume a conversation", "ادامهٔ یک گفتگو") }];
    if (!items.length) list.push({ header: tr("No saved conversations yet.", "هنوز گفتگوی ذخیره‌شده‌ای نیست.") });
    items.slice(0, 30).forEach((x) => list.push({
      label: x.title || tr("(untitled)", "(بدون عنوان)"),
      desc: fmtWhen(x.updated) + " · " + x.messages + tr(" messages", " پیام"),
      onClick: () => toIde({ type: "resumeSession", chat: c.id, id: x.id }),
    }));
    showMenu(G.resumeAnchor, list);
  }

  /** Rebuilds a chat from a saved transcript. */
  function renderTranscript(c, p) {
    c.log.innerHTML = "";
    Object.assign(c, { title: p.title || "", main: mkTarget(c.log), usage: freshUsage(), failed: false, busy: false, tools: {} });
    (p.items || []).forEach((it) => {
      switch (it.t) {
        case "user":
          addUser(c.main, it.text || "");
          break;
        case "assistant": {
          const el = newTextBlock(c.main);
          el.innerHTML = markdown(it.text || "");
          break;
        }
        case "tool":
          onToolStart(c, it);
          if (it.ended) onToolEnd(c, it);
          break;
        case "todos":
          renderTodos(c, it.items || []);
          break;
        case "notice":
          notice(c, c.main, it.text || "", it.kind || "");
          break;
      }
    });
    closeTools(c);
    c.main.turn = null;
    notice(c, c.main, tr("⎿  Resumed conversation from ", "⎿  ادامهٔ گفتگوی ") + fmtWhen(p.updated) + ".", "");
    setBusy(c, false);
    refreshChatUi();
    scrollDown(c.main, true);
  }

  function activate(id) {
    G.active = id;
    refreshChatUi();
    if (G.view !== "chat") renderView();
  }

  function refreshChatUi() {
    const c = chat();
    Object.values(G.chats).forEach((x) => x.log.classList.toggle("hidden", x !== c));
    renderTabs();
    renderInfo();
    renderCtx();
    renderMode();
    syncBusyUi();
  }

  function renderTabs() {
    $("chatTabs").innerHTML = G.order.map((id) => {
      const c = G.chats[id];
      const dot = c.busy ? "busy" : c.failed ? "err" : "";
      const t = esc(c.title || tr("New chat", "گفتگوی جدید"));
      return '<div class="pill' + (id === G.active ? " active" : "") + '" data-chat="' + id + '" title="' + t + '">' +
        '<span class="dot ' + dot + '"></span><span class="t" dir="' + textDir(c.title) + '">' + t + "</span>" +
        '<span class="x" data-close="' + id + '" title="' + tr("Close", "بستن") + '">×</span></div>';
    }).join("");
  }

  // ==================================================================== rendering into a chat
  function scrollDown(T, force) {
    const el = T.el;
    if (el.classList.contains("hidden")) return;
    const near = el.scrollHeight - el.scrollTop - el.clientHeight < 160;
    if (force || (G.autoFollow && near)) el.scrollTop = el.scrollHeight;
  }

  function hideWelcome(T) {
    const w = T.el.querySelector(".welcome-wrap");
    if (w) w.remove();
  }

  function addUser(T, text) {
    hideWelcome(T);
    const d = document.createElement("div");
    d.className = "msg user";
    d.innerHTML = '<span class="prompt">&gt;</span><div class="text"></div>';
    // One block per line, each with its own direction (a Persian question followed by an English line stays readable).
    const box = d.querySelector(".text");
    const base = textDir(text);
    box.dir = base;
    String(text).split("\n").forEach((line) => {
      const ln = document.createElement("div");
      ln.dir = textDir(line, base);
      if (line) ln.textContent = line;
      else ln.appendChild(document.createElement("br"));
      box.appendChild(ln);
    });
    T.el.appendChild(d);
    T.turn = null;
    scrollDown(T, true);
  }

  function turnEl(T) {
    if (!T.turn) {
      hideWelcome(T);
      T.turn = document.createElement("div");
      T.turn.className = "msg assistant";
      T.el.appendChild(T.turn);
    }
    return T.turn;
  }

  function newTextBlock(T) {
    const row = document.createElement("div");
    row.className = "row text";
    row.innerHTML = '<span class="bullet">⏺</span><div class="content md"></div>';
    turnEl(T).appendChild(row);
    return row.querySelector(".content");
  }

  const renderQueue = new Map();
  let renderScheduled = false;
  function scheduleRender(T, block) {
    renderQueue.set(block, T);
    if (renderScheduled) return;
    renderScheduled = true;
    requestAnimationFrame(() => {
      renderScheduled = false;
      renderQueue.forEach((t, b) => {
        b.el.innerHTML = markdown(b.text);
        scrollDown(t);
      });
      renderQueue.clear();
    });
  }

  function notice(c, T, text, kind) {
    T = T || c.main;
    const d = document.createElement("div");
    d.className = "notice " + (kind || "");
    d.dir = textDir(text);
    d.textContent = text;
    (T.turn || T.el).appendChild(d);
    scrollDown(T);
  }

  // ==================================================================== busy state / status line / info panel
  const SPIN = ["·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢"];
  let spinI = 0;
  setInterval(() => {
    const c = chat();
    if (!c || !c.busy) return;
    $("spin").textContent = SPIN[spinI++ % SPIN.length];
    $("statusHint").textContent = "(" + Math.round((Date.now() - c.startedAt) / 1000) + "s · ↓ ~" + fmtNum(Math.round(estTok(c.usage.liveChars))) +
      tr(" tokens · esc to interrupt)", " توکن · esc برای توقف)");
  }, 120);

  function setBusy(c, b, text) {
    if (c.busy === b && !text) return;
    c.busy = b;
    c.startedAt = b ? c.startedAt || Date.now() : 0;
    if (text) c.statusText = text;
    if (c === chat()) syncBusyUi();
    renderTabs();
  }

  function syncBusyUi() {
    const c = chat();
    const b = !!(c && c.busy);
    $("btnSend").classList.toggle("hidden", b);
    $("btnStop").classList.toggle("hidden", !b);
    $("status").classList.toggle("hidden", !b);
    $("infoState").classList.toggle("busy", b);
    $("infoStateText").textContent = b ? tr("Running", "در حال اجرا") : tr("Idle", "آماده");
    if (b) setStatus(c.statusText || thinking());
  }

  function setStatus(text) {
    $("statusText").textContent = text;
  }
  function chatStatus(c, text) {
    c.statusText = text;
    if (c === chat()) setStatus(text);
  }

  function renderInfo() {
    const c = chat();
    if (!c) return;
    const u = c.usage;
    $("infoPrompt").textContent = fmtNum(u.prompt);
    $("infoOut").textContent = fmtNum(u.completion + Math.round(estTok(u.liveChars)));
    $("infoTotal").textContent = fmtNum(u.total) + (u.priced && u.cost > 0 ? " · " + fmtUsd(u.cost) : "");
    $("infoModel").textContent = modelLabel(G.model);
    $("infoCwd").textContent = tildePath(G.projectPath);
    $("infoCtx").textContent = c.ctx && c.ctx.limit
      ? "~" + fmtNum(c.ctx.tokens) + " / " + fmtNum(c.ctx.limit) + " (" + Math.round((100 * c.ctx.tokens) / c.ctx.limit) + "%)"
      : "—";
    $("infoKey").textContent = G.hasKey === null ? "—" : G.hasKey ? tr("set", "تنظیم شده") : tr("missing (Settings › Tools › GapGPT)", "تنظیم نشده (Settings › Tools › GapGPT)");
  }

  // ==================================================================== streamed reply
  function onDelta(c, text) {
    if (!c.busy) return;
    const T = c.main;
    if (!T.block) T.block = { el: newTextBlock(T), text: "" };
    T.block.text += text;
    c.usage.liveChars += text.length;
    scheduleRender(T, T.block);
    chatStatus(c, tr("Writing…", "در حال نوشتن…"));
    scheduleInfo(c);
  }

  /** Renders the text of the reply now (instead of waiting for the next animation frame). */
  function flushBlock(c) {
    const T = c.main;
    if (T.block) {
      renderQueue.delete(T.block);
      T.block.el.innerHTML = markdown(T.block.text);
      T.block = null;
    }
    c.usage.liveChars = 0;
  }

  function onDone(c, p) {
    const secs = c.startedAt ? ((Date.now() - c.startedAt) / 1000).toFixed(1) + "s" : "";
    flushBlock(c);
    closeTools(c);
    const u = p.usage;
    if (u) {
      c.usage.prompt += u.prompt_tokens || 0;
      c.usage.completion += u.completion_tokens || 0;
      c.usage.total += u.total_tokens || (u.prompt_tokens || 0) + (u.completion_tokens || 0);
      if (typeof u.cost_usd === "number") c.usage.cost += u.cost_usd;
      else if (u.total_tokens) c.usage.priced = false;
    }
    if (p.stopped) notice(c, c.main, tr("⎿  Stopped.", "⎿  متوقف شد."), "warn");
    else {
      const parts = [];
      if (secs) parts.push(secs);
      if (u && u.total_tokens) parts.push(fmtNum(u.total_tokens) + tr(" tokens", " توکن"));
      if (parts.length) {
        const m = document.createElement("div");
        m.className = "meta";
        m.textContent = "✻ " + parts.join(" · ");
        if (u && typeof u.cost_usd === "number") {
          const cs = document.createElement("span");
          cs.className = "ltr";
          cs.dir = "ltr";
          cs.textContent = fmtUsd(u.cost_usd);
          m.append(" · ", cs);
        }
        turnEl(c.main).appendChild(m);
      }
    }
    c.main.turn = null;
    c.failed = false;
    setBusy(c, false);
    if (c === chat()) {
      renderInfo();
      scrollDown(c.main);
    }
  }

  /** Tool rows left open when a run ends (stopped or failed) lose their spinner and approval box. */
  function closeTools(c) {
    c.main.el.querySelectorAll(".tool .approval").forEach((e) => e.remove());
    c.main.el.querySelectorAll(".tool.running").forEach((e) => {
      e.classList.remove("running");
      e.classList.add("error");
    });
    c.tools = {};
  }

  // ==================================================================== agent tool calls
  const FILE_TOOLS = { Read: 1, Edit: 1, Write: 1 };

  function onToolStart(c, p) {
    flushBlock(c);
    const d = document.createElement("div");
    d.className = "tool running";
    d.innerHTML = '<div class="head"><span class="bullet">⏺</span><div class="call"><span class="name"></span><span class="arg"></span></div></div>';
    d.querySelector(".name").textContent = p.title || "";
    d.querySelector(".arg").textContent = p.label ? "(" + p.label + ")" : "";
    d.querySelector(".call").title = (p.title || "") + " " + (p.label || "");
    if (FILE_TOOLS[p.title] && p.label) {
      const o = document.createElement("span");
      o.className = "open";
      o.textContent = tr("open", "باز کردن");
      o.onclick = () => toIde({ type: "openFile", path: p.label, line: 0 });
      d.querySelector(".call").appendChild(o);
    }
    turnEl(c.main).appendChild(d);
    (c.tools = c.tools || {})[p.id] = d;
    chatStatus(c, tr("Running ", "در حال اجرای ") + (p.title || "tool") + "…");
    scrollDown(c.main);
  }

  function onToolApproval(c, p) {
    const d = c.tools && c.tools[p.id];
    if (!d) return;
    const box = document.createElement("div");
    box.className = "approval";
    const isPlan = p.kind === "plan";
    if (isPlan) {
      const md = document.createElement("div");
      md.className = "plan md";
      md.innerHTML = markdown(p.preview || "");
      box.appendChild(md);
    } else if (p.preview) {
      const pre = document.createElement("pre");
      pre.className = "preview";
      String(p.preview).split("\n").forEach((line) => {
        const ln = document.createElement("div");
        if (line.startsWith("+ ")) ln.className = "add";
        else if (line.startsWith("- ")) ln.className = "del";
        else if (line.startsWith("$ ")) ln.className = "cmd";
        ln.textContent = line || " ";
        pre.appendChild(ln);
      });
      box.appendChild(pre);
    }
    const isCmd = p.kind === "command";
    const isMcp = p.kind === "mcp";
    const q = document.createElement("div");
    q.className = "q";
    q.dir = uiDir();
    q.textContent = isPlan ? tr("Would you like to proceed with this plan?", "با این طرح ادامه بدهیم؟")
      : isMcp ? tr("Allow this MCP tool call?", "این فراخوانی ابزار MCP مجاز است؟")
      : isCmd ? tr("Run this command?", "این دستور اجرا شود؟") : tr("Apply this change?", "این تغییر اعمال شود؟");
    box.appendChild(q);
    const row = document.createElement("div");
    row.className = "btnrow";
    row.dir = uiDir();
    const decide = (decision) => {
      toIde({ type: "toolDecision", chat: c.id, id: p.id, decision });
      box.remove();
      chatStatus(c, decision === "deny" ? thinking() : tr("Running…", "در حال اجرا…"));
    };
    const btn = (label, cls, decision) => {
      const b = document.createElement("button");
      b.className = cls;
      b.textContent = label;
      b.onclick = () => decide(decision);
      row.appendChild(b);
      return b;
    };
    const first = btn(isPlan ? tr("Yes, start", "بله، شروع کن") : tr("Allow", "اجازه"), "primary", "allow");
    btn(isPlan ? tr("Yes, and auto-accept edits", "بله، و ویرایش‌ها را خودکار بپذیر")
      : isMcp ? tr("Always allow this server (this session)", "همیشه اجازه برای این سرور (این نشست)")
      : isCmd ? tr("Always allow commands (this session)", "همیشه اجازهٔ اجرای دستور (این نشست)")
      : tr("Always allow edits (this session)", "همیشه اجازهٔ ویرایش (این نشست)"), "pillbtn", "always");
    btn(isPlan ? tr("No, keep planning", "نه، ادامهٔ برنامه‌ریزی") : tr("Deny", "رد"), "pillbtn", "deny");
    if (p.hasDiff) {
      const v = document.createElement("button");
      v.className = "pillbtn";
      v.textContent = tr("View diff", "نمایش diff");
      v.onclick = () => toIde({ type: "showDiff", chat: c.id, id: p.id });
      row.appendChild(v);
    }
    box.appendChild(row);
    d.appendChild(box);
    chatStatus(c, tr("Waiting for your approval…", "در انتظار تأیید شما…"));
    scrollDown(c.main, true);
    if (c === chat()) first.focus();
  }

  function onToolEnd(c, p) {
    const d = c.tools && c.tools[p.id];
    if (!d) return;
    const ap = d.querySelector(".approval");
    if (ap) ap.remove();
    d.classList.remove("running");
    d.classList.add(p.ok ? "done" : "error");
    const r = document.createElement("div");
    r.className = "result";
    r.innerHTML = '<span class="elbow">⎿</span><div class="body"><div class="summary"></div></div>';
    r.querySelector(".summary").textContent = p.summary || "";
    if (p.detail) {
      const pre = document.createElement("pre");
      pre.textContent = p.detail;
      r.querySelector(".body").appendChild(pre);
    }
    d.appendChild(r);
    chatStatus(c, thinking());
    scrollDown(c.main);
  }

  function onError(c, text) {
    flushBlock(c);
    closeTools(c);
    notice(c, c.main, text, "error");
    c.main.turn = null;
    c.failed = true;
    setBusy(c, false);
    if (c === chat()) renderInfo();
  }

  // ==================================================================== model / context chip
  function setModel(m, notify) {
    G.model = m || "";
    $("ddModel").querySelector(".lbl").textContent = modelLabel(G.model);
    if (notify) toIde({ type: "setModel", value: G.model });
    renderInfo();
  }

  function renderCtx() {
    const chip = $("ctxChip");
    const c = chat();
    const x = G.ctx;
    if (!c || !x || !x.file || !G.shareSelectionSetting) return chip.classList.add("hidden");
    chip.classList.remove("hidden");
    const name = x.file.split("/").pop();
    const lines = x.endLine - x.startLine + 1;
    chip.textContent = "⧉ " + name + (x.hasSelection ? ":" + x.startLine + (lines > 1 ? "-" + x.endLine : "") : "");
    chip.title = x.file + tr(" — " + (c.shareCtx ? "is" : "is not") + " sent with your message. Click to toggle.",
      " — همراه پیام فرستاده " + (c.shareCtx ? "می‌شود" : "نمی‌شود") + ". برای تغییر کلیک کنید.");
    chip.classList.toggle("off", !c.shareCtx);
  }

  // ==================================================================== dropdown menu
  let menuAnchor = null;
  function showMenu(anchor, items) {
    const m = $("menu");
    closeMenu();
    menuAnchor = anchor;
    anchor.classList.add("open");
    m.innerHTML = "";
    items.forEach((it) => {
      if (it.sep) return m.insertAdjacentHTML("beforeend", '<div class="msep"></div>');
      if (it.header) return m.insertAdjacentHTML("beforeend", '<div class="mh">' + esc(it.header) + "</div>");
      if (it.error) {
        const d = document.createElement("div");
        d.className = "merr";
        d.dir = textDir(it.error);
        d.textContent = it.error;
        return m.appendChild(d);
      }
      if (it.input) {
        const inp = document.createElement("input");
        inp.placeholder = it.input.placeholder || "";
        inp.dir = uiDir();
        inp.addEventListener("input", () => it.input.onInput && it.input.onInput(inp.value));
        inp.addEventListener("keydown", (e) => {
          if (e.key === "Enter" && it.input.onEnter) it.input.onEnter(inp.value);
        });
        m.appendChild(inp);
        if (it.input.focus) setTimeout(() => inp.focus(), 0);
        return;
      }
      if (it.slot) {
        const d = document.createElement("div");
        d.id = it.slot;
        return m.appendChild(d);
      }
      m.appendChild(menuItem(it));
    });
    m.classList.remove("hidden");
    const r = anchor.getBoundingClientRect();
    const w = m.offsetWidth;
    m.dir = uiDir();
    if (G.lang === "fa") {
      let right = window.innerWidth - r.right;
      if (window.innerWidth - right - w < 4) right = Math.max(4, window.innerWidth - w - 4);
      m.style.right = right + "px";
      m.style.left = "auto";
    } else {
      let left = r.left;
      if (left + w > window.innerWidth - 4) left = Math.max(4, window.innerWidth - w - 4);
      m.style.left = left + "px";
      m.style.right = "auto";
    }
    if (r.top > window.innerHeight / 3) {
      m.style.top = "auto";
      m.style.bottom = window.innerHeight - r.top + 6 + "px";
    } else {
      m.style.bottom = "auto";
      m.style.top = r.bottom + 6 + "px";
    }
  }
  function menuItem(it) {
    const b = document.createElement("button");
    b.className = "mi" + (it.checked ? " checked" : "") + (it.cls ? " " + it.cls : "");
    b.innerHTML = '<span class="ml"><span class="lt"></span>' + (it.desc ? '<span class="md2"></span>' : "") + "</span>" +
      '<span class="ck">' + (it.checked ? "✓" : "") + "</span>";
    b.querySelector(".lt").textContent = it.label;
    if (it.desc) b.querySelector(".md2").textContent = it.desc;
    if (it.family) b.dataset.modelFamily = it.family;
    b.addEventListener("click", (e) => {
      if (it.keepOpen) return; // handled by the document click listener (re-renders the menu)
      e.stopPropagation();
      closeMenu();
      if (it.onClick) it.onClick();
    });
    return b;
  }
  function closeMenu() {
    $("menu").classList.add("hidden");
    if (menuAnchor) menuAnchor.classList.remove("open");
    menuAnchor = null;
  }

  function modelMenu(anchor) {
    const list = G.models.length ? G.models.slice() : ["GPT-5.2-Pro"];
    if (G.model && list.indexOf(G.model) < 0) list.unshift(G.model);
    let shown = list;
    const render = (q) => {
      const box = $("modelList");
      if (!box) return;
      q = (q || "").trim().toLowerCase();
      shown = q ? list.filter((m) => m.toLowerCase().indexOf(q) >= 0) : list;
      box.innerHTML = "";
      shown.forEach((m) => box.appendChild(menuItem({ label: m, checked: G.model === m, onClick: () => setModel(m, true) })));
      if (!shown.length) box.insertAdjacentHTML("beforeend", '<div class="mh">' + esc(tr("No match — Enter uses the typed name", "موردی نیست — Enter همین نام را استفاده می‌کند")) + "</div>");
    };
    const items = [
      {
        input: {
          placeholder: tr("Search or type a model name, then Enter", "جستجو یا نام مدل و Enter"),
          focus: true,
          onInput: render,
          onEnter: (v) => {
            const name = shown.length === 1 ? shown[0] : v.trim();
            if (!name) return;
            setModel(name, true);
            closeMenu();
          },
        },
      },
      { slot: "modelList" },
      { sep: true },
    ];
    if (G.modelsError) items.push({ error: G.modelsError });
    items.push({
      label: G.modelsLoading ? tr("Loading models…", "در حال دریافت مدل‌ها…") : tr("Refresh model list from GapGPT", "به‌روزرسانی فهرست مدل‌ها از گپ‌جی‌پی‌تی"),
      desc: G.models.length ? tr(G.models.length + " chat models", G.models.length + " مدل چت") : "",
      onClick: () => {
        fetchModels();
        modelMenu(anchor); // keep the menu open; it is re-rendered when the list arrives
      },
    });
    showMenu(anchor, items);
    render("");
    // First open with no list yet: load it once in the background.
    if (!G.models.length && !G.modelsLoading && !G.modelsError && G.hasKey !== false) {
      fetchModels();
      modelMenu(anchor);
    }
  }
  function fetchModels() {
    if (G.modelsLoading) return;
    G.modelsLoading = true;
    G.modelsError = "";
    toIde({ type: "fetchModels" });
  }
  function moreMenu(anchor) {
    showMenu(anchor, [
      { label: tr("Auto-scroll", "دنبال کردن خودکار اسکرول"), checked: G.autoFollow, onClick: () => { G.autoFollow = !G.autoFollow; toIde({ type: "setAutoFollow", value: G.autoFollow }); } },
      { label: tr("Search in chat", "جستجو در گفتگو"), onClick: openSearch },
      { label: tr("Clear this chat (/clear)", "پاک کردن این گفتگو (/clear)"), onClick: () => chat() && clearChat(chat()) },
      { label: tr("Resume a conversation (/resume)", "ادامهٔ یک گفتگو (/resume)"), onClick: () => setTimeout(openResume, 0) },
      { label: tr("Usage & balance (/usage)", "مصرف و اعتبار حساب (/usage)"), onClick: () => send("/usage", false) },
      { label: tr("Language: فارسی", "زبان: English"), onClick: () => setLang(G.lang === "fa" ? "en" : "fa", true) },
      { sep: true },
      { label: tr("Import rules & skills", "کپی قوانین و اسکیل‌ها"), onClick: () => { switchView("rules"); requestImportScan(); } },
      { label: tr("Plugin log", "لاگ پلاگین"), onClick: () => switchView("log") },
      { label: tr("Settings", "تنظیمات"), onClick: () => toIde({ type: "settings" }) },
      { sep: true },
      { label: tr("Close this chat", "بستن این گفتگو"), onClick: () => closeChat(G.active) },
    ]);
  }
  let attachFilter = "";
  function attachMenu(anchor) {
    attachFilter = "";
    showMenu(anchor, [
      { input: { placeholder: tr("Search recent files…", "جستجوی فایل‌های اخیر…"), focus: true, onInput: (v) => { attachFilter = v.toLowerCase(); renderRecent(); } } },
      { label: tr("Files…", "فایل‌ها…"), onClick: () => toIde({ type: "attach", kind: "files" }) },
      { label: tr("Folder…", "پوشه…"), onClick: () => toIde({ type: "attach", kind: "dir" }) },
      { label: tr("Image…", "تصویر…"), onClick: () => toIde({ type: "attach", kind: "image" }) },
      { header: tr("Recent files", "فایل‌های اخیر") },
      { slot: "recentSlot" },
    ]);
    renderRecent();
    toIde({ type: "recentFiles" });
  }
  function renderRecent() {
    const slot = $("recentSlot");
    if (!slot) return;
    slot.innerHTML = "";
    const items = G.recent.filter((f) => !attachFilter || f.toLowerCase().includes(attachFilter)).slice(0, 12);
    if (!items.length) slot.innerHTML = '<div class="mh">—</div>';
    items.forEach((f) => slot.appendChild(menuItem({ label: f, cls: "file", onClick: () => insertInput("@" + f + " ", true) })));
  }

  // ==================================================================== @-mention autocomplete
  function mentionQuery() {
    const pos = inputEl.selectionStart;
    if (pos !== inputEl.selectionEnd) return null;
    const m = inputEl.value.slice(0, pos).match(/(?:^|\s)@([^\s@]*)$/);
    return m ? { q: m[1], start: pos - m[1].length - 1 } : null;
  }
  function updateMention() {
    const mq = mentionQuery();
    if (!mq) return closeMention();
    G.mention.start = mq.start;
    G.mention.reqId++;
    toIde({ type: "fileSearch", q: mq.q, reqId: G.mention.reqId });
  }
  function showMention(p) {
    if (p.reqId !== G.mention.reqId || !mentionQuery()) return;
    Object.assign(G.mention, { items: p.items || [], idx: 0, open: true });
    renderMention();
  }
  function renderMention() {
    const box = $("mention");
    const m = G.mention;
    box.classList.remove("hidden");
    if (!m.items.length) return (box.innerHTML = '<div class="none" dir="' + uiDir() + '">' + tr("No files found", "فایلی پیدا نشد") + "</div>");
    box.innerHTML = m.items.map((path, i) => {
      const k = path.lastIndexOf("/");
      return '<div class="item' + (i === m.idx ? " active" : "") + '" data-idx="' + i + '">' + esc(path.slice(k + 1)) +
        ' <span class="dir">' + esc(k > 0 ? path.slice(0, k) : "") + "</span></div>";
    }).join("");
    const act = box.querySelector(".active");
    if (act) act.scrollIntoView({ block: "nearest" });
  }
  function closeMention() {
    G.mention.open = false;
    G.mention.items = [];
    $("mention").classList.add("hidden");
  }
  function acceptMention(i) {
    const m = G.mention;
    const path = m.items[i];
    if (path == null) return closeMention();
    const pos = inputEl.selectionStart;
    const v = inputEl.value;
    const ins = "@" + path + " ";
    inputEl.value = v.slice(0, m.start) + ins + v.slice(pos);
    inputEl.setSelectionRange(m.start + ins.length, m.start + ins.length);
    closeMention();
    autoGrow();
    inputEl.focus();
  }

  // ==================================================================== slash commands
  function slashQuery() {
    const v = inputEl.value;
    if (inputEl.selectionStart !== inputEl.selectionEnd) return null;
    const m = /^\/([^\s]*)$/.exec(v.slice(0, inputEl.selectionStart));
    return m && v.indexOf("\n") < 0 ? m[1].toLowerCase() : null;
  }
  function updateSlash() {
    const q = slashQuery();
    if (q === null) return closeSlash();
    if (!G.commands) {
      G.commands = [];
      toIde({ type: "listCommands" });
    }
    const sl = G.slash;
    sl.items = G.commands.filter((c) => c.name.toLowerCase().includes(q))
      .sort((a, b) => (a.name.toLowerCase().startsWith(q) ? 0 : 1) - (b.name.toLowerCase().startsWith(q) ? 0 : 1)).slice(0, 30);
    sl.idx = Math.min(sl.idx, Math.max(0, sl.items.length - 1));
    sl.open = sl.items.length > 0;
    const box = $("slash");
    if (!sl.open) return box.classList.add("hidden");
    box.classList.remove("hidden");
    box.innerHTML = sl.items.map((c, i) => '<div class="item' + (i === sl.idx ? " active" : "") + '" data-idx="' + i + '">/' + esc(c.name) +
      '<span class="desc" dir="' + textDir(c.description, "ltr") + '">' + esc(c.description || "") + '</span><span class="src">' + esc(c.source === "builtin" ? "" : c.source) + "</span></div>").join("");
    const act = box.querySelector(".active");
    if (act) act.scrollIntoView({ block: "nearest" });
  }
  function closeSlash() {
    G.slash.open = false;
    $("slash").classList.add("hidden");
  }
  function acceptSlash(i) {
    const c = G.slash.items[i];
    closeSlash();
    if (!c) return;
    inputEl.value = "/" + c.name + " ";
    inputEl.setSelectionRange(inputEl.value.length, inputEl.value.length);
    autoGrow();
    inputEl.focus();
  }

  // ==================================================================== views (composer tabs)
  function switchView(v) {
    G.view = v;
    document.querySelectorAll("#viewTabs .vt").forEach((b) => b.classList.toggle("active", b.dataset.view === v));
    document.querySelectorAll("#views .view").forEach((s) => s.classList.toggle("hidden", s.id !== "view-" + v));
    renderView();
  }
  function renderView() {
    switch (G.view) {
      case "git":
        renderGit();
        return toIde({ type: "git" });
      case "log":
        renderLogView();
        return toIde({ type: "log" });
      case "skills":
        renderSkills();
        return toIde({ type: "skills" });
      case "rules":
        renderRules();
        return toIde({ type: "rules" });
    }
  }

  // ---------------------------------------------------------------- import rules & skills
  function requestImportScan() {
    G.importPreview = null;
    G.importNote = tr("Looking for rules and skills…", "در حال جست‌وجوی قوانین و اسکیل‌ها…");
    refreshImportUi();
    toIde({ type: "scanInstructions" });
  }
  function refreshImportUi() {
    if (G.view === "skills") renderSkills();
    else if (G.view === "rules") renderRules();
  }
  const KIND_LABEL = {
    rule: ["Rule", "قانون"], skill: ["Skill", "اسکیل"], command: ["Command", "دستور"], agent: ["Agent", "عامل"], memory: ["Instructions", "دستورالعمل"],
  };
  function importHtml() {
    const p = G.importPreview;
    let html = '<div class="cardbox"><h4>' + tr("Import rules & skills", "کپی قوانین و اسکیل‌ها") + '</h4><div class="muted">' +
      tr('Copies existing rules and skills from other AI tools\' folders into <span class="mono ltr">.gapgpt</span>. The originals are not changed, so those tools keep working. You see the list first.',
        'قوانین و اسکیل‌های موجود در پوشه‌های ابزارهای هوش مصنوعی دیگر را در <span class="mono ltr">.gapgpt</span> کپی می‌کند. فایل‌های اصلی دست نمی‌خورند و آن ابزارها به کارشان ادامه می‌دهند. ابتدا فهرست را می‌بینید.') + "</div>";
    if (p && p.items.length) {
      html += '<div class="rows">' + p.items.map((x) => {
        const k = KIND_LABEL[x.kind];
        return '<div class="r"><span class="tag">' + esc(k ? tr(k[0], k[1]) : x.kind) + '</span><span class="grow"><span class="code">' + esc(x.name) +
          '</span><span class="sub mono ltr">' + esc(x.source) + " → " + esc(x.target) + "</span></span></div>";
      }).join("") + "</div>" +
        '<div class="btnrow"><button class="primary" data-import-confirm="1">' + tr("Copy " + p.items.length + " item(s)", "کپی " + p.items.length + " مورد") + "</button>" +
        '<button class="pillbtn" data-import-cancel="1">' + tr("Cancel", "لغو") + "</button></div>";
    } else {
      if (p) html += '<div class="empty-note">' + tr("Nothing new to import was found.", "چیز تازه‌ای برای کپی پیدا نشد.") + "</div>";
      html += '<div class="btnrow"><button class="primary" data-import-scan="1">' + tr("Import rules & skills", "کپی قوانین و اسکیل‌ها") + "</button></div>";
    }
    if (G.importNote) html += '<div class="notice" dir="' + textDir(G.importNote) + '">' + esc(G.importNote) + "</div>";
    return html + "</div>";
  }

  // ---------------------------------------------------------------- skills
  let skillsBuilt = false;
  function renderSkills() {
    const v = $("view-skills");
    if (!skillsBuilt) {
      skillsBuilt = true;
      v.innerHTML = '<div class="cardbox"><h4>' + tr("Skills", "اسکیل‌ها") + '</h4><div class="muted">' +
        tr('Skills from the project\'s <span class="mono ltr">.gapgpt</span> folder and <span class="mono ltr">~/.gapgpt</span>. Type <span class="mono ltr">/name</span> in the message box to use one; "Run" puts it there.',
          'اسکیل‌های پوشهٔ <span class="mono ltr">.gapgpt</span> پروژه و <span class="mono ltr">~/.gapgpt</span>. برای استفاده <span class="mono ltr">/name</span> را در کادر پیام بنویسید؛ «اجرا» آن را همان‌جا می‌گذارد.') + "</div>" +
        '<input class="field" id="skQ" placeholder="' + tr("Search name or description…", "جستجوی نام یا توضیح…") + '" dir="auto" style="width:100%;margin-top:8px"></div>' +
        '<div id="skBody"></div><div id="skImport"></div>';
      $("skQ").value = G.skillQ;
      $("skQ").addEventListener("input", (e) => {
        G.skillQ = e.target.value.toLowerCase();
        renderSkillsBody();
      });
    }
    renderSkillsBody();
    $("skImport").innerHTML = importHtml();
  }
  function renderSkillsBody() {
    const box = $("skBody");
    if (!box) return;
    const items = G.skills;
    if (!items) return (box.innerHTML = '<div class="cardbox"><div class="muted">' + tr("Loading…", "در حال خواندن…") + "</div></div>");
    const q = G.skillQ;
    const list = items.filter((x) => !q || (x.name + " " + x.description).toLowerCase().includes(q));
    const srcLabel = (src) => (src === "project" ? tr("Project", "پروژه") : src === "user" ? tr("User", "کاربر") : src);
    const groups = {};
    list.forEach((x) => (groups[x.source] = groups[x.source] || []).push(x));
    const order = Object.keys(groups).sort((a, b) => (a === "project" ? -1 : b === "project" ? 1 : a.localeCompare(b)));
    box.innerHTML = '<div class="cardbox"><h4>' + tr("Available skills", "اسکیل‌های موجود") + " (" + list.length + ")</h4>" + (list.length ? order.map((src) =>
      "<h5>" + esc(srcLabel(src)) + '</h5><div class="rows">' + groups[src].map((x) =>
        '<div class="r"><span class="grow"><span class="code">/' + esc(x.name) + '</span><span class="sub" dir="' + textDir(x.description, "ltr") + '">' +
        esc(x.description || "—") + "</span></span>" +
        '<span class="acts"><button class="pillbtn" data-run-skill="' + esc(x.name) + '">' + tr("Run", "اجرا") + "</button>" +
        '<button class="icon" data-open-path="' + esc(x.path) + '" title="' + tr("Open file", "باز کردن فایل") + '">↗</button></span></div>').join("") + "</div>").join("")
      : '<div class="empty-note">' + tr("No skills found.", "اسکیلی پیدا نشد.") + "</div>") + "</div>";
  }

  // ---------------------------------------------------------------- rules
  const SCOPES = {
    project: ["Project", "پروژه"], "project-rule": ["Project rule", "قانون پروژه"],
    user: ["User (all projects)", "کاربر (همهٔ پروژه‌ها)"], "user-rule": ["User rule", "قانون کاربر"],
  };
  function scopeLabel(s) {
    const l = SCOPES[s];
    return l ? tr(l[0], l[1]) : s;
  }
  function renderRules() {
    const v = $("view-rules");
    const items = G.rules;
    if (!items) return (v.innerHTML = '<div class="cardbox"><div class="muted">' + tr("Loading…", "در حال خواندن…") + "</div></div>");
    v.innerHTML = '<div class="cardbox"><h4>' + tr("Rules", "قوانین") + " (" + items.length + ")</h4><div class=\"muted\">" +
      tr('These files from <span class="mono ltr">.gapgpt</span> are sent to the model as instructions with every message.',
        'این فایل‌ها از <span class="mono ltr">.gapgpt</span> به‌عنوان دستورالعمل همراه هر پیام به مدل فرستاده می‌شوند.') + '</div><div class="rows">' +
      (items.length ? items.map((m) => '<div class="r"><span class="grow"><span class="code">' + esc(m.name) + '</span><span class="sub">' + esc(scopeLabel(m.scope)) +
        " · " + m.lines + tr(" lines", " خط") + '</span><div class="preview" dir="' + textDir(m.preview, "ltr") + '">' + esc(m.preview || "") + "</div></span>" +
        '<span class="acts"><button class="icon" data-open-path="' + esc(m.path) + '" title="' + tr("Open", "باز کردن") + '">↗</button></span></div>').join("")
        : '<div class="empty-note">' + tr("No rules yet. Add Markdown files under .gapgpt/rules or import existing ones below.",
          "هنوز قانونی نیست. فایل‌های Markdown را در .gapgpt/rules بگذارید یا موارد موجود را در پایین کپی کنید.") + "</div>") + "</div></div>" + importHtml();
  }
  // ---------------------------------------------------------------- git
  // ---------------------------------------------------------------- git
  const GIT_ACTIONS = [["Git.Branches", "Branches", "شاخه‌ها"], ["Git.Pull", "Pull", "Pull"], ["Git.Fetch", "Fetch", "Fetch"], ["Vcs.Push", "Push", "Push"],
    ["Git.Merge", "Merge", "Merge"], ["Git.Rebase", "Rebase", "Rebase"]];
  function renderGit() {
    const v = $("view-git");
    const g = G.git;
    if (!g) return (v.innerHTML = '<div class="cardbox"><div class="muted">' + tr("Reading the repository…", "در حال خواندن مخزن…") + "</div></div>");
    if (g.error) return (v.innerHTML = '<div class="cardbox"><h4>' + tr("Repository", "مخزن") + '</h4><div class="muted">' + esc(g.error) + "</div></div>");
    const kv = (k, val) => '<span class="k">' + k + '</span><span class="v">' + esc(val) + "</span>";
    v.innerHTML =
      '<div class="cardbox"><h4>' + tr("Repository", "مخزن") + '</h4><div class="ltr" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;text-align:left">' +
      '<b class="mono">' + esc(g.root) + '</b><span class="badge">' + esc(g.branch) + "</span></div>" +
      '<div class="muted mono ltr" style="text-align:left">' + esc(g.head) + "</div>" +
      (g.headSubject ? '<div class="muted" dir="' + textDir(g.headSubject, "ltr") + '">' + esc(g.headSubject) + "</div>" : "") + "</div>" +
      '<div class="cards2"><div class="cardbox"><h4>' + tr("Branch", "شاخه") + '</h4><div class="kv">' +
      (g.tracking ? kv(tr("Tracking", "دنبال‌کردن"), g.tracking) + kv(tr("Ahead", "جلوتر (ahead)"), g.ahead) + kv(tr("Behind", "عقب‌تر (behind)"), g.behind) +
        kv(tr("Diverged at", "نقطهٔ جدا شدن"), g.divergedAt || "—")
        : kv(tr("Tracking", "دنبال‌کردن"), tr("no upstream", "بدون upstream"))) + kv(tr("Changed files", "فایل‌های تغییرکرده"), g.changed) +
      '</div><div class="muted" style="margin-top:6px">' + tr("Counted against your last fetch.", "نسبت به آخرین fetch شما شمرده شده است.") + "</div></div>" +
      '<div class="cardbox"><h4>' + tr("Recent commits", "commitهای اخیر") + '</h4><div class="rows">' + (g.log || []).map((l) =>
        '<div class="r"><span class="mono" style="color:var(--accent)">' + esc(l.hash) + '</span><span class="grow" dir="' + textDir(l.subject, "ltr") + '">' + esc(l.subject) +
        '<span class="sub"> · ' + esc(l.author) + " · " + esc(l.when) + "</span></span></div>").join("") + "</div></div></div><br>" +
      "<div class=\"cardbox\"><h4>" + tr("Actions", "عملیات") + "</h4>" +
      "<h5>" + tr("Repository", "مخزن") + '</h5><div class="btnrow"><button class="pillbtn" data-ide-action="Vcs.Show.Log">Git log</button>' +
      '<button class="pillbtn" data-ide-action="CheckinProject">Commit…</button><button class="pillbtn" data-git-refresh="1">↻ ' + tr("Refresh", "تازه‌سازی") + "</button></div>" +
      "<h5>" + tr("IDE actions", "عملیات IDE") + '</h5><div class="btnrow">' +
      GIT_ACTIONS.map((a) => '<button class="pillbtn" data-ide-action="' + a[0] + '">' + tr(a[1], a[2]) + "</button>").join("") +
      "</div></div>";
  }

  // ---------------------------------------------------------------- log
  let logBuilt = false;
  function renderLogView() {
    const v = $("view-log");
    if (!logBuilt) {
      logBuilt = true;
      v.innerHTML = '<div class="cardbox"><h4>' + tr("Plugin log", "لاگ پلاگین") + '</h4><div style="display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap">' +
        '<div class="chips" id="lChips"></div><button class="pillbtn" id="lCopy">' + tr("Copy", "کپی") + "</button></div>" +
        '<div class="muted" id="lSummary" style="margin-top:6px"></div></div><div class="cardbox"><h4>' + tr("Lines", "خط‌ها") + '</h4><div class="lines" id="lLines"></div></div>';
      $("lCopy").addEventListener("click", () => {
        toIde({ type: "copy", text: filteredLog().map((l) => l.time + " " + l.level + " [" + l.source + "] " + l.message).join("\n") });
        $("lCopy").textContent = tr("✓ Copied", "✓ کپی شد");
        setTimeout(() => ($("lCopy").textContent = tr("Copy", "کپی")), 1200);
      });
    }
    renderLogBody();
  }
  function filteredLog() {
    return G.logFilter === "ALL" ? G.logLines : G.logLines.filter((l) => l.level === G.logFilter);
  }
  function renderLogBody() {
    if (!logBuilt) return;
    $("lChips").innerHTML = [["ALL", tr("All", "همه")], ["WARN", "Warn"], ["INFO", "Info"], ["DEBUG", "Debug"]].map((f) =>
      '<button class="' + (G.logFilter === f[0] ? "on" : "") + '" data-lfilter="' + f[0] + '">' + f[1] + "</button>").join("") +
      '<button class="' + (G.logDebug ? "on" : "") + '" data-ldebug="1">' + (G.logDebug ? "● " : "○ ") + tr("Record debug", "ثبت Debug") + "</button>";
    const ls = filteredLog();
    $("lSummary").textContent = tr(ls.length + " of " + G.logLines.length + " lines · up to 2000 lines are kept · debug " + (G.logDebug ? "on" : "off"),
      ls.length + " از " + G.logLines.length + " خط · حداکثر ۲۰۰۰ خط نگه داشته می‌شود · debug " + (G.logDebug ? "روشن" : "خاموش"));
    $("lLines").innerHTML = ls.slice(-500).map((l) => '<div class="ln ' + l.level + '"><span class="meta">' + esc(l.time) + ' </span><span class="lvl">' + l.level +
      '</span>\n<span class="meta">' + esc(l.source) + "</span>\n" + esc(l.message) + "</div>").join("") || '<div class="empty-note">' + tr("Empty.", "خالی است.") + "</div>";
  }

  // ==================================================================== search in chat
  function openSearch() {
    switchView("chat");
    $("searchBar").classList.remove("hidden");
    $("searchInput").focus();
    $("searchInput").select();
  }
  function findNext(back) {
    const q = $("searchInput").value;
    if (q && window.find) window.find(q, false, !!back, true, false, false, false);
  }

  // ==================================================================== bridge (IDE -> page)
  function insertInput(text, inl) {
    if (inl) {
      const v = inputEl.value;
      const pos = document.activeElement === inputEl ? inputEl.selectionStart : v.length;
      const pre = v.slice(0, pos);
      const ins = (pre && !/\s$/.test(pre) ? " " : "") + text;
      inputEl.value = pre + ins + v.slice(pos);
      autoGrow();
      inputEl.focus();
      inputEl.setSelectionRange(pos + ins.length, pos + ins.length);
    } else {
      inputEl.value = (inputEl.value ? inputEl.value.replace(/\s*$/, "") + "\n\n" : "") + text;
      autoGrow();
      inputEl.focus();
      inputEl.setSelectionRange(inputEl.value.length, inputEl.value.length);
    }
  }

  window.bridge = {
    receive(kind, p) {
      const c = p && p.chat ? G.chats[p.chat] : null;
      switch (kind) {
        case "init": {
          const r = document.documentElement.style;
          if (p.bg) r.setProperty("--bg", p.bg);
          if (p.fg) r.setProperty("--fg", p.fg);
          if (p.fontSize) r.setProperty("--font-size", p.fontSize + "px");
          G.sendOnEnter = p.sendOnEnter !== false;
          G.projectPath = p.projectPath || "";
          document.querySelectorAll(".welcome .cwd").forEach((e) => (e.textContent = "cwd: " + G.projectPath));
          if (p.language && p.language !== G.lang) setLang(p.language, false);
          setModel(p.model, false);
          if (Array.isArray(p.models)) G.models = p.models;
          if (p.shareSelection !== undefined) G.shareSelectionSetting = p.shareSelection;
          if (p.autoFollow !== undefined) G.autoFollow = p.autoFollow;
          if (p.useGraphify !== undefined) G.useGraphify = p.useGraphify;
          renderGraphify();
          document.body.classList.toggle("light", !p.dark);
          renderInfo();
          renderCtx();
          break;
        }
        case "models":
          G.modelsLoading = false;
          G.modelsError = p.error || "";
          if (Array.isArray(p.items)) G.models = p.items;
          // Re-open the model menu so the new list (or the error) shows right away.
          if (menuAnchor === $("ddModel")) modelMenu($("ddModel"));
          break;
        case "context":
          G.ctx = p && p.file ? p : null;
          renderCtx();
          break;
        case "keyStatus":
          G.hasKey = !!p.hasKey;
          renderInfo();
          break;
        case "delta":
          if (c) onDelta(c, String(p.text || ""));
          break;
        case "done":
          if (c) onDone(c, p);
          break;
        case "busy":
          if (c) setBusy(c, !!p.busy);
          break;
        case "error": {
          const cc = c || chat();
          if (cc) onError(cc, String(p.text || ""));
          break;
        }
        case "toolStart":
          if (c) onToolStart(c, p);
          break;
        case "toolApproval":
          if (c) onToolApproval(c, p);
          break;
        case "toolEnd":
          if (c) onToolEnd(c, p);
          break;
        case "todos":
          if (c) renderTodos(c, p.items || []);
          break;
        case "planMode":
          if (c) {
            c.plan = !!p.value;
            if (c === chat()) renderMode();
          }
          break;
        case "sessions":
          if (c) showSessions(c, p);
          break;
        case "transcript":
          if (c) renderTranscript(c, p);
          break;
        case "contextSize":
          if (c) {
            c.ctx = { tokens: p.tokens || 0, limit: p.limit || 0 };
            if (c === chat()) renderInfo();
          }
          break;
        case "status":
          if (c) chatStatus(c, String(p.text || ""));
          break;
        case "notice":
          if (c) notice(c, c.main, String(p.text || ""), p.kind || "");
          break;
        case "fileResults":
          showMention(p);
          break;
        case "recentFiles":
          G.recent = p.items || [];
          renderRecent();
          break;
        case "commands":
          G.commands = p.items || [];
          updateSlash();
          break;
        case "insertInput":
          switchView("chat");
          insertInput(p.text, p.inline);
          break;
        case "externalSend":
          switchView("chat");
          send(p.text, false);
          break;
        case "git":
          G.git = p;
          if (G.view === "git") renderGit();
          break;
        case "log":
          G.logLines = p.lines || [];
          G.logDebug = !!p.debug;
          renderLogBody();
          break;
        case "logLine":
          G.logLines.push(p);
          if (G.logLines.length > 2000) G.logLines.shift();
          if (G.view === "log") renderLogBody();
          break;
        case "skills":
          G.skills = p.items || [];
          if (G.view === "skills") renderSkills();
          break;
        case "rules":
          G.rules = p.items || [];
          if (G.view === "rules") renderRules();
          break;
        case "instructionsPreview":
          G.importPreview = { items: p.items || [] };
          G.importNote = null;
          refreshImportUi();
          break;
        case "instructionsImported": {
          const skipped = p.skipped || [];
          G.importPreview = null;
          G.importNote = tr("Copied " + (p.moved || 0) + " item(s) into .gapgpt.", (p.moved || 0) + " مورد در .gapgpt کپی شد.") +
            (skipped.length ? tr(" Skipped: ", " رد شد: ") + skipped.map((x) => x.name + " (" + x.reason + ")").join("، ") : "");
          refreshImportUi();
          break;
        }
      }
    },
  };

  // ==================================================================== user input
  function send(text, expandRefs) {
    text = (text || "").trim();
    const c = chat();
    if (!text || !c || c.busy) return;
    if (text === "/clear" || text === "/new") return clearChat(c);
    if (text === "/resume") {
      inputEl.value = "";
      closeSlash();
      return openResume();
    }
    addUser(c.main, text);
    if (!c.title) c.title = text.replace(/\s+/g, " ").slice(0, 60);
    c.failed = false;
    c.usage.liveChars = 0;
    c.main.block = null;
    setBusy(c, true, thinking());
    refreshChatUi();
    closeMention();
    closeSlash();
    toIde({ type: "send", chat: c.id, text, shareContext: c.shareCtx, expandRefs: expandRefs !== false, plan: !!c.plan });
  }

  function autoGrow() {
    inputEl.dir = textDir(inputEl.value, uiDir());
    inputEl.style.height = "auto";
    inputEl.style.height = Math.min(inputEl.scrollHeight + 2, window.innerHeight * 0.4) + "px";
  }

  inputEl.addEventListener("input", () => {
    autoGrow();
    updateMention();
    updateSlash();
  });
  inputEl.addEventListener("click", updateMention);
  inputEl.addEventListener("blur", () => setTimeout(() => { closeMention(); closeSlash(); }, 150));
  inputEl.addEventListener("keydown", (e) => {
    if (e.key === "Tab" && e.shiftKey && !e.isComposing) {
      e.preventDefault();
      return togglePlan();
    }
    const sl = G.slash;
    if (sl.open && !e.isComposing) {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        sl.idx = (sl.idx + (e.key === "ArrowDown" ? 1 : -1) + sl.items.length) % sl.items.length;
        return updateSlash();
      }
      if (e.key === "Tab" || (e.key === "Enter" && sl.items[sl.idx] && "/" + sl.items[sl.idx].name !== inputEl.value.trim())) {
        e.preventDefault();
        return acceptSlash(sl.idx);
      }
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        return closeSlash();
      }
    }
    const m = G.mention;
    if (m.open && !e.isComposing) {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        if (m.items.length) {
          m.idx = (m.idx + (e.key === "ArrowDown" ? 1 : -1) + m.items.length) % m.items.length;
          renderMention();
        }
        return;
      }
      if ((e.key === "Enter" || e.key === "Tab") && m.items.length) {
        e.preventDefault();
        return acceptMention(m.idx);
      }
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        return closeMention();
      }
    }
    if (e.key !== "Enter" || e.isComposing) return;
    const wantsSend = G.sendOnEnter ? !e.shiftKey : e.ctrlKey || e.metaKey;
    if (wantsSend) {
      e.preventDefault();
      const t = inputEl.value;
      inputEl.value = "";
      autoGrow();
      switchView("chat");
      send(t);
    }
  });

  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !$("menu").classList.contains("hidden")) {
      e.preventDefault();
      return closeMenu();
    }
    if (e.target.id === "searchInput") {
      if (e.key === "Enter") {
        e.preventDefault();
        findNext(e.shiftKey);
      } else if (e.key === "Escape") $("searchBar").classList.add("hidden");
      return;
    }
    if (e.target.closest && e.target.closest("#menu")) return;
    const c = chat();
    if (e.key === "Escape" && !e.defaultPrevented && c && c.busy) toIde({ type: "stop", chat: c.id });
  });

  // ---------------------------------------------------------------- language
  /** Static texts of chat.html: [selector, attribute ("" = text), English, Persian]. */
  const STATIC = [
    ["#btnNewChat", "title", "New chat", "گفتگوی جدید"],
    ["#btnSettings", "title", "Settings", "تنظیمات"],
    ["#searchInput", "placeholder", "Search in chat… (Enter: next)", "جستجو در گفتگو… (Enter: بعدی)"],
    ["#searchPrev", "title", "Previous", "قبلی"],
    ["#searchNext", "title", "Next", "بعدی"],
    ["#searchClose", "title", "Close", "بستن"],
    ['#viewTabs [data-view="chat"]', "", "Chat", "گفتگو"],
    ['#viewTabs [data-view="log"]', "", "Log", "لاگ"],
    ['#viewTabs [data-view="skills"]', "", "Skills", "اسکیل‌ها"],
    ['#viewTabs [data-view="rules"]', "", "Rules", "قوانین"],
    ["#btnNewChat2", "title", "New chat", "گفتگوی جدید"],
    ["#btnSearch", "title", "Search in chat", "جستجو در گفتگو"],
    ["#btnMore", "title", "More", "بیشتر"],
    ["#btnMore2", "title", "More", "بیشتر"],
    ["#input", "placeholder", "Ask GapGPT, or type / for skills and @ for files", "از GapGPT بپرسید، یا / برای اسکیل‌ها و @ برای فایل‌ها"],
    ["#btnAttach", "title", "Add files, folders or images", "افزودن فایل، پوشه یا تصویر"],
    ["#ddModel", "title", "Model", "مدل"],
    ["#btnLang", "title", "Language (English / فارسی)", "زبان (فارسی / English)"],
    ["#btnSend", "title", "Send (Enter)", "ارسال (Enter)"],
    ["#btnStop", "title", "Stop (Esc)", "توقف (Esc)"],
    ["#statusText", "", "Thinking…", "در حال فکر کردن…"],
  ];

  /** Switches the whole UI between English and Persian (direction, texts and the open view). */
  function setLang(l, notify) {
    G.lang = l === "fa" ? "fa" : "en";
    const root = document.documentElement;
    root.lang = G.lang;
    root.dir = uiDir();
    STATIC.forEach(([sel, attr, en, fa]) => {
      const el = document.querySelector(sel);
      if (!el) return;
      if (attr) el.setAttribute(attr, tr(en, fa));
      else el.textContent = tr(en, fa);
    });
    $("btnLang").querySelector(".lbl").textContent = G.lang === "fa" ? "FA" : "EN";
    autoGrow();
    document.querySelectorAll(".welcome-wrap").forEach(fillWelcome);
    setModel(G.model, false);
    renderGraphify();
    // Views that build their skeleton once are rebuilt in the new language on the next render.
    skillsBuilt = logBuilt = false;
    ["view-skills", "view-log"].forEach((id) => ($(id).innerHTML = ""));
    closeMenu();
    refreshChatUi();
    if (G.view !== "chat") renderView();
    if (notify) toIde({ type: "setLanguage", value: G.lang });
  }
  function langMenu(anchor) {
    showMenu(anchor, [
      { label: "English", desc: tr("Interface in English", "رابط کاربری انگلیسی"), checked: G.lang === "en", onClick: () => setLang("en", true) },
      { label: "فارسی", desc: tr("Interface in Persian (right-to-left)", "رابط کاربری فارسی (راست‌به‌چپ)"), checked: G.lang === "fa", onClick: () => setLang("fa", true) },
      { sep: true },
      { header: tr("You can write in either language; the assistant answers in the language you write in.",
        "به هر دو زبان می‌توانید بنویسید؛ دستیار به همان زبان پیام شما جواب می‌دهد.") },
    ]);
  }

  // Every text field picks its direction from what is typed (also Persian text that starts with an English word).
  document.addEventListener("input", (e) => {
    const t = e.target;
    if (t !== inputEl && t.matches && t.matches("textarea, input:not([type]), input[type=text], input[type=search]")) t.dir = textDir(t.value, uiDir());
  });

  // ---------------------------------------------------------------- buttons
  function onNewChat() {
    switchView("chat");
    newChat();
    inputEl.focus();
  }
  $("btnSend").addEventListener("click", () => {
    const t = inputEl.value;
    inputEl.value = "";
    autoGrow();
    switchView("chat");
    send(t);
  });
  $("btnStop").addEventListener("click", () => chat() && toIde({ type: "stop", chat: G.active }));
  $("btnNewChat").addEventListener("click", onNewChat);
  $("btnNewChat2").addEventListener("click", onNewChat);
  $("btnSettings").addEventListener("click", () => toIde({ type: "settings" }));
  $("btnSearch").addEventListener("click", openSearch);
  $("btnGit").addEventListener("click", () => switchView("git"));
  const menuBtn = (id, fn) => $(id).addEventListener("click", (e) => {
    e.stopPropagation();
    if (menuAnchor === e.currentTarget) return closeMenu();
    fn(e.currentTarget);
  });
  menuBtn("btnMore", moreMenu);
  menuBtn("btnMore2", moreMenu);
  menuBtn("btnAttach", attachMenu);
  menuBtn("ddModel", modelMenu);
  menuBtn("btnLang", langMenu);
  $("searchNext").addEventListener("click", () => findNext(false));
  $("searchPrev").addEventListener("click", () => findNext(true));
  $("searchClose").addEventListener("click", () => $("searchBar").classList.add("hidden"));
  $("modeChip").addEventListener("click", togglePlan);
  $("graphifyChip").addEventListener("click", toggleGraphify);
  $("ctxChip").addEventListener("click", () => {
    const c = chat();
    if (c) c.shareCtx = !c.shareCtx;
    renderCtx();
  });
  $("viewTabs").addEventListener("click", (e) => {
    const b = e.target.closest(".vt");
    if (b) switchView(b.dataset.view);
  });
  ["mention", "slash"].forEach((id) => $(id).addEventListener("mousedown", (e) => {
    const it = e.target.closest && e.target.closest(".item");
    if (!it) return;
    e.preventDefault();
    (id === "mention" ? acceptMention : acceptSlash)(+it.getAttribute("data-idx"));
  }));

  document.addEventListener("click", (e) => {
    const t = e.target;
    if (!t.closest("#menu")) closeMenu();
    const closeX = t.closest("[data-close]");
    if (closeX) return closeChat(closeX.dataset.close);
    const tab = t.closest("#chatTabs .pill");
    if (tab) {
      switchView("chat");
      return activate(tab.dataset.chat);
    }
    if (t.classList.contains("sugg")) return send(t.textContent);
    const rs = t.closest("[data-run-skill]");
    if (rs) {
      switchView("chat");
      inputEl.value = "/" + rs.dataset.runSkill + " ";
      autoGrow();
      inputEl.focus();
      return inputEl.setSelectionRange(inputEl.value.length, inputEl.value.length);
    }
    const op = t.closest("[data-open-path]");
    if (op) return toIde({ type: "openFile", path: op.dataset.openPath, line: 0 });
    if (t.closest("[data-import-scan]")) return requestImportScan();
    if (t.closest("[data-import-cancel]")) {
      G.importPreview = null;
      G.importNote = null;
      return refreshImportUi();
    }
    if (t.closest("[data-import-confirm]")) {
      G.importNote = tr("Copying…", "در حال کپی…");
      G.importPreview = null;
      refreshImportUi();
      return toIde({ type: "importInstructions" });
    }
    const ia = t.closest("[data-ide-action]");
    if (ia) return toIde({ type: "ideAction", id: ia.dataset.ideAction });
    if (t.closest("[data-git-refresh]")) {
      G.git = null;
      renderGit();
      return toIde({ type: "git" });
    }
    const lf = t.closest("[data-lfilter]");
    if (lf) {
      G.logFilter = lf.dataset.lfilter;
      return renderLogBody();
    }
    if (t.closest("[data-ldebug]")) {
      G.logDebug = !G.logDebug;
      toIde({ type: "logDebug", value: G.logDebug });
      return renderLogBody();
    }
    const act = t.getAttribute("data-act");
    if (act) {
      const code = document.getElementById(t.getAttribute("data-target"));
      if (!code) return;
      if (act === "copy") {
        toIde({ type: "copy", text: code.textContent });
        const old = t.textContent;
        t.textContent = "✓";
        setTimeout(() => (t.textContent = old), 1000);
      } else if (act === "insert") toIde({ type: "insertCode", text: code.textContent });
      return;
    }
    if (t.classList.contains("file") && t.getAttribute("data-file")) {
      const m = t.getAttribute("data-file").replace(/^@/, "").match(/^(.*?)(?::(\d+)|#L(\d+)(?:-\d+)?)?$/);
      const line = m[2] || m[3];
      return toIde({ type: "openFile", path: m[1], line: line ? parseInt(line, 10) : 0 });
    }
    const a = t.closest("a[href]");
    if (a) {
      e.preventDefault();
      toIde({ type: "openUrl", url: a.getAttribute("href") });
    }
  });

  window.addEventListener("resize", closeMenu);

  setLang("en", false);
  newChat();
  inputEl.focus();
})();
