(function () {
  "use strict";

  // ==================================================================== globals
  const $ = (id) => document.getElementById(id);
  const inputEl = $("input");

  const G = {
    sendOnEnter: true,
    projectPath: "",
    permissionMode: "default",
    model: "",
    effort: "",
    shareSelectionSetting: true,
    guardEnabled: true,
    autoFollow: true,
    ctx: null,
    account: null,
    limits: {},
    commands: null,
    recent: [],
    mention: { open: false, items: [], idx: 0, reqId: 0, start: -1 },
    slash: { open: false, items: [], idx: 0 },
    view: "chat",
    chats: {},
    order: [],
    active: null,
    seq: 0,
    guard: null,
    guardTab: "blocked",
    guardOnlyChat: true,
    logLines: [],
    logFilter: "ALL",
    logDebug: false,
    vulnInv: null,
    vulnRes: null,
    vulnBusy: false,
    vulnShowList: false,
    git: null,
    keepMin: 15,
    skills: null,
    skillQ: "",
    usageInsights: "",
    usageAt: 0,
    modelOpen: {},
    rules: null,
  };

  function toIde(obj) {
    if (window.__toIde) window.__toIde(JSON.stringify(obj));
  }
  function chat() {
    return G.chats[G.active];
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
      '<button data-act="insert" data-target="' + id + '" title="درج در ویرایشگر">درج</button>' +
      '<button data-act="copy" data-target="' + id + '" title="کپی">کپی</button></span></div>' +
      '<pre><code id="' + id + '">' + esc(code) + "</code></pre></div>"
    );
  }

  function markdown(src) {
    const lines = src.replace(/\r\n/g, "\n").split("\n");
    let out = "";
    let i = 0;
    let para = [];
    const flush = () => {
      if (para.length) {
        out += "<p>" + para.map(inline).join("<br>") + "</p>";
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
        out += "<h" + h[1].length + ">" + inline(h[2]) + "</h" + h[1].length + ">";
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
        out += "<table><tr>" + row(line).map((c) => "<th>" + inline(c) + "</th>").join("") + "</tr>";
        i += 2;
        while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) {
          out += "<tr>" + row(lines[i]).map((c) => "<td>" + inline(c) + "</td>").join("") + "</tr>";
          i++;
        }
        out += "</table>";
        continue;
      }
      const li = line.match(/^\s*([-*+]|\d+[.)])\s+(.*)$/);
      if (li) {
        flush();
        const tag = /\d/.test(li[1]) ? "ol" : "ul";
        out += "<" + tag + ">";
        while (i < lines.length) {
          const m = lines[i].match(/^\s*([-*+]|\d+[.)])\s+(.*)$/);
          if (!m) break;
          out += "<li>" + inline(m[2]) + "</li>";
          i++;
        }
        out += "</" + tag + ">";
        continue;
      }
      if (/^\s*>/.test(line)) {
        flush();
        const buf = [];
        while (i < lines.length && /^\s*>/.test(lines[i])) buf.push(lines[i++].replace(/^\s*>\s?/, ""));
        out += "<blockquote>" + buf.map(inline).join("<br>") + "</blockquote>";
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
  function fmtInt(n) {
    return (n || 0).toLocaleString("en-US");
  }
  const MONTHS = { jan: 0, feb: 1, mar: 2, apr: 3, may: 4, jun: 5, jul: 6, aug: 7, sep: 8, oct: 9, nov: 10, dec: 11 };
  /** "Nov 21 at 7:30am (Asia/Tehran)" -> epoch seconds (local time), or 0. */
  function parseResetText(t) {
    const m = /^([A-Za-z]{3})\w*\s+(\d{1,2})\s+at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)/i.exec(t || "");
    if (!m) return 0;
    let h = +m[3] % 12;
    if (m[5].toLowerCase() === "pm") h += 12;
    const now = new Date();
    const d = new Date(now.getFullYear(), MONTHS[m[1].toLowerCase()], +m[2], h, +(m[4] || 0));
    if (d.getTime() < now.getTime() - 86400000 * 30) d.setFullYear(d.getFullYear() + 1);
    return Math.floor(d.getTime() / 1000);
  }
  function fmtReset(sec) {
    if (!sec) return "";
    const d = Math.max(0, Math.round((sec * 1000 - Date.now()) / 60000));
    return Math.floor(d / 60) + "h " + (d % 60) + "m";
  }
  function modelLabel(id) {
    if (!id) return "پیش‌فرض";
    const known = ALL_MODELS.find((m) => m[0] === id);
    if (known) return known[1];
    const m = /^claude-([a-z]+)-(\d+)(?:-(\d))?(?:-\d{8})?(\[1m\])?$/.exec(id);
    if (m) return cap(m[1]) + " " + m[2] + (m[3] ? "." + m[3] : "") + (m[4] ? " · 1M" : "");
    return cap(id);
  }
  function countLines(s) {
    s = String(s || "").replace(/\n+$/, "");
    return s ? s.split("\n").length : 0;
  }
  function cap(s) {
    s = String(s || "");
    return s ? s[0].toUpperCase() + s.slice(1) : s;
  }

  // ==================================================================== chats (tabs)
  function mkTarget(el) {
    return { el, turn: null, blocks: {}, streamed: new Set() };
  }

  function welcomeEl() {
    const w = $("tplWelcome").content.cloneNode(true).querySelector(".welcome-wrap");
    w.querySelector(".cwd").textContent = "cwd: " + G.projectPath;
    return w;
  }

  function freshUsage(win) {
    // outTok/reasoning: finished turns. turnOut/turnReason: finished messages of the running turn.
    // cur: the message being streamed right now (estimated from text, replaced by exact counts).
    return { ctx: 0, window: win || 200000, outTok: 0, reasoning: 0, cost: 0, turnOut: 0, turnReason: 0, cur: null };
  }
  function liveOut(u) {
    return u.outTok + u.turnOut + (u.cur ? u.cur.out : 0);
  }
  function liveReason(u) {
    return u.reasoning + u.turnReason + (u.cur ? u.cur.reason : 0);
  }
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
    G.chats[id] = {
      id, title: "", busy: false, log, main: mkTarget(log), tools: {}, perms: {}, stderr: [],
      agents: {}, agentOrder: [], viewAgent: null, usage: freshUsage(),
      model: "", cwd: "", version: "", sessionId: null,
      connected: false, startedAt: 0, statusText: "", shareCtx: true, sessionInfo: null, failed: false,
    };
    G.order.push(id);
    activate(id);
    return G.chats[id];
  }

  function closeChat(id) {
    const c = G.chats[id];
    if (!c) return;
    toIde({ type: "closeChat", chat: id });
    c.log.remove();
    Object.values(c.agents).forEach((a) => a.el.remove());
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
    Object.values(c.agents).forEach((a) => a.el.remove());
    Object.assign(c, {
      title: "", main: mkTarget(c.log), tools: {}, perms: {}, agents: {}, agentOrder: [], viewAgent: null,
      usage: freshUsage(c.usage.window), sessionId: null, connected: false, sessionInfo: null, failed: false,
    });
    notice(c, c.main, "⎿  گفتگوی جدید شروع شد.", "");
    refreshChatUi();
  }

  function activate(id) {
    G.active = id;
    refreshChatUi();
    if (G.view !== "chat") renderView();
  }

  function refreshChatUi() {
    const c = chat();
    Object.values(G.chats).forEach((x) => {
      x.log.classList.toggle("hidden", !(x === c && !x.viewAgent));
      Object.values(x.agents).forEach((a) => a.el.classList.toggle("hidden", !(x === c && x.viewAgent === a.id)));
    });
    renderTabs();
    renderSubTabs();
    renderInfo();
    renderCtx();
    syncBusyUi();
  }

  function renderTabs() {
    $("chatTabs").innerHTML = G.order.map((id) => {
      const c = G.chats[id];
      const dot = c.busy ? "busy" : c.failed ? "err" : c.connected ? "ok" : "";
      const t = esc(c.title || "گفتگوی جدید");
      return '<div class="pill' + (id === G.active ? " active" : "") + '" data-chat="' + id + '" title="' + t + '">' +
        '<span class="dot ' + dot + '"></span><span class="t">' + t + "</span>" +
        '<span class="x" data-close="' + id + '" title="بستن">×</span></div>';
    }).join("");
  }

  function renderSubTabs() {
    const c = chat();
    const box = $("subTabs");
    if (!c) return (box.innerHTML = "");
    let html = '<div class="pill' + (c.viewAgent ? "" : " active") + '" data-agent="">گفتگو</div>';
    c.agentOrder.forEach((aid) => {
      const a = c.agents[aid];
      const dot = a.status === "running" ? "busy" : a.status === "error" ? "err" : "ok";
      html += '<div class="pill agent' + (c.viewAgent === aid ? " active" : "") + '" data-agent="' + aid + '" title="' + esc(a.desc) + '">' +
        '<span class="dot ' + dot + '"></span><span class="t">' + esc(a.desc || a.type || "Agent") + "</span></div>";
    });
    box.innerHTML = html;
  }

  // ==================================================================== rendering into a target (main chat or subagent)
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
    d.innerHTML = '<span class="prompt">&gt;</span><span class="text" dir="auto"></span>';
    d.querySelector(".text").textContent = text;
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
    d.dir = "auto";
    d.textContent = text;
    (T.turn || T.el).appendChild(d);
    scrollDown(T);
  }

  // ==================================================================== tools: "⏺ Name(arg)" + "⎿ result"
  const AGENT_TOOLS = { Task: 1, Agent: 1 };
  const TOOL_FA = {
    Read: "خواندن", Write: "نوشتن", Edit: "ویرایش", MultiEdit: "ویرایش", Bash: "اجرای دستور",
    Glob: "جستجوی فایل", Grep: "جستجو در کد", LS: "فهرست", WebFetch: "دریافت صفحه", WebSearch: "جستجوی وب",
    Task: "زیرعامل", Agent: "زیرعامل", TodoWrite: "فهرست کارها", NotebookEdit: "ویرایش نوت‌بوک",
    mcp__gapgpt__getDiagnostics: "بررسی خطاهای IDE", mcp__gapgpt__getOpenEditors: "فایل‌های باز IDE",
    mcp__gapgpt__getCurrentSelection: "انتخاب فعلی IDE", mcp__gapgpt__openFile: "باز کردن فایل در IDE",
    mcp__gapgpt__getWorkspaceFolders: "پوشه‌های پروژه",
  };

  function displayName(name) {
    if (name === "Edit" || name === "MultiEdit") return "Update";
    if (name === "TodoWrite") return "Update Todos";
    const m = /^mcp__(.+?)__(.+)$/.exec(name || "");
    if (m) return (m[1] === "gapgpt" ? "ide" : m[1]) + " - " + m[2] + " (MCP)";
    return name;
  }

  function toolSummary(name, input) {
    if (!input || name === "TodoWrite") return "";
    const v = input.file_path || input.notebook_path || input.command || input.pattern || input.url ||
      input.query || input.description || input.prompt || input.uri || input.filePath || "";
    let s = relPath(v);
    if (name === "Grep" && input.path) s = 'pattern: "' + s + '", path: "' + relPath(input.path) + '"';
    s = s.replace(/\s*\n\s*/g, " ");
    return s.length > 200 ? s.slice(0, 200) + "…" : s;
  }

  function agentFor(c, id) {
    let a = c.agents[id];
    if (!a) {
      const el = document.createElement("div");
      el.className = "chatlog hidden";
      $("chatLogs").appendChild(el);
      a = c.agents[id] = { id, desc: "", type: "", status: "running", started: Date.now(), ended: 0, el, T: mkTarget(el) };
      c.agentOrder.push(id);
    }
    return a;
  }

  function addTool(c, T, id, name, input) {
    let t = id && c.tools[id];
    if (!t) {
      const el = document.createElement("div");
      el.className = "tool running";
      el.innerHTML = '<div class="head"><span class="bullet">⏺</span><div class="call"><span class="name"></span>' +
        '<span class="arg"></span><span class="fa"></span></div></div>';
      el.querySelector(".name").textContent = displayName(name);
      el.querySelector(".fa").textContent = TOOL_FA[name] || "";
      turnEl(T).appendChild(el);
      t = { el, name, input: null, T };
      if (id) c.tools[id] = t;
    }
    if (input) {
      t.input = input;
      const arg = toolSummary(name, input);
      t.el.querySelector(".arg").textContent = arg ? "(" + arg + ")" : "";
      t.el.querySelector(".call").title = arg;
    }
    if (AGENT_TOOLS[name] && id) {
      const a = agentFor(c, id);
      if (input) {
        a.desc = input.description || a.desc;
        a.type = input.subagent_type || a.type;
      }
      if (!t.el.querySelector(".open")) {
        const o = document.createElement("span");
        o.className = "open";
        o.dataset.openAgent = id;
        o.textContent = "مشاهدهٔ زیرعامل ›";
        t.el.querySelector(".call").appendChild(o);
      }
      if (c === chat()) renderSubTabs();
      if (G.view === "workloads") renderWorkloads();
    }
    scrollDown(T);
    return t;
  }

  function resultText(content) {
    if (typeof content === "string") return content;
    if (Array.isArray(content)) return content.map((x) => (x && x.type === "text" ? x.text : x && x.type === "image" ? "[image]" : "")).join("\n");
    return "";
  }

  function diffHtml(oldStr, newStr, maxRows) {
    const a = String(oldStr || "").split("\n");
    const b = String(newStr || "").split("\n");
    let p = 0;
    while (p < a.length && p < b.length && a[p] === b[p]) p++;
    let s = 0;
    while (s < a.length - p && s < b.length - p && a[a.length - 1 - s] === b[b.length - 1 - s]) s++;
    const rows = [];
    const ctx = (l) => rows.push('<div class="ln"><span class="sign"> </span>' + esc(l) + "</div>");
    if (p > 0) ctx(a[p - 1]);
    a.slice(p, a.length - s).forEach((l) => rows.push('<div class="ln rem"><span class="sign">-</span>' + esc(l) + "</div>"));
    b.slice(p, b.length - s).forEach((l) => rows.push('<div class="ln add"><span class="sign">+</span>' + esc(l) + "</div>"));
    if (s > 0) ctx(a[a.length - s]);
    let html = rows.slice(0, maxRows).join("");
    if (rows.length > maxRows) html += '<div class="ln gap">  … +' + (rows.length - maxRows) + " lines</div>";
    return { html: '<div class="diff">' + html + "</div>", added: b.length - p - s, removed: a.length - p - s };
  }

  function editDiff(name, input) {
    if (name === "Edit") return diffHtml(input.old_string, input.new_string, 40);
    if (name === "MultiEdit" && Array.isArray(input.edits)) {
      let html = "", added = 0, removed = 0;
      input.edits.forEach((e) => {
        const d = diffHtml(e.old_string, e.new_string, 20);
        html += d.html;
        added += d.added;
        removed += d.removed;
      });
      return { html, added, removed };
    }
    if (name === "Write") {
      const lines = String(input.content || "").split("\n");
      const shown = lines.slice(0, 12).map((l) => '<div class="ln add"><span class="sign">+</span>' + esc(l) + "</div>").join("");
      const more = lines.length > 12 ? '<div class="ln gap">  … +' + (lines.length - 12) + " lines</div>" : "";
      return { html: '<div class="diff">' + shown + more + "</div>", added: lines.length, removed: 0 };
    }
    return null;
  }

  function todosHtml(todos) {
    if (!Array.isArray(todos)) return "";
    return '<div class="todos">' + todos.map((t) =>
      '<div class="t ' + esc(t.status || "") + '"><span class="box">' + (t.status === "completed" ? "☒" : "☐") + "</span>" +
      esc(t.content || t.activeForm || "") + "</div>").join("") + "</div>";
  }

  function previewLines(text, n) {
    const lines = String(text).replace(/\n+$/, "").split("\n");
    const rest = lines.length - n;
    return "<pre>" + esc(lines.slice(0, n).join("\n")) + "</pre>" +
      (rest > 0 ? '<span class="more">… +' + rest + ' خط (برای نمایش کلیک کنید)</span><pre class="hidden full">' +
        esc(lines.slice(n).join("\n")) + "</pre>" : "");
  }

  function setToolResult(c, id, t, isError, content) {
    const name = t.name, input = t.input || {}, text = resultText(content);
    let html;
    if (isError) html = previewLines(text.replace(/<\/?tool_use_error>/g, "").trim() || "Error", 4);
    else if (name === "Edit" || name === "MultiEdit" || name === "Write") {
      const d = editDiff(name, input);
      const summary = name === "Write"
        ? countLines(input.content) + " خط در " + relPath(input.file_path) + " نوشته شد"
        : relPath(input.file_path) + " به‌روزرسانی شد: " + (d ? d.added : 0) + " خط اضافه، " + (d ? d.removed : 0) + " خط حذف";
      html = '<div class="summary">' + esc(summary) + "</div>" + (d ? d.html : "");
    } else if (name === "Read") html = '<div class="summary">' + countLines(text) + " خط خوانده شد</div>";
    else if (name === "Grep" || name === "Glob" || name === "LS") {
      html = '<div class="summary">' + (/^No (files|matches)/i.test(text) ? "نتیجه‌ای پیدا نشد" : countLines(text) + " نتیجه پیدا شد") + "</div>";
    } else if (name === "TodoWrite") html = todosHtml(input.todos);
    else if (name === "WebFetch" || name === "WebSearch") html = '<div class="summary">انجام شد (' + text.length.toLocaleString() + " نویسه)</div>";
    else if (AGENT_TOOLS[name]) html = previewLines(text.trim() || "انجام شد", 3);
    else html = text.trim() ? previewLines(text.trim(), 3) : '<div class="summary">(بدون خروجی)</div>';
    let r = t.el.querySelector(".result");
    if (!r) {
      r = document.createElement("div");
      r.className = "result";
      t.el.appendChild(r);
    }
    r.innerHTML = '<span class="elbow">⎿</span><div class="body">' + html + "</div>";
    t.el.classList.remove("running");
    t.el.classList.add(isError ? "error" : "done");
    if (AGENT_TOOLS[name] && c.agents[id]) {
      const a = c.agents[id];
      a.status = isError ? "error" : "done";
      a.ended = Date.now();
      if (c === chat()) renderSubTabs();
      if (G.view === "workloads") renderWorkloads();
    }
  }

  // ==================================================================== permission prompts (CLI style)
  function showPermission(c, p) {
    const card = document.createElement("div");
    card.className = "perm" + (p.guardRule ? " guarded" : "");
    card.tabIndex = 0;
    const input = p.input || {};
    let title = "", body = "", question = "";
    if (p.isEdit) {
      const file = p.file || input.file_path || "";
      title = p.newFile ? "ساخت فایل" : "ویرایش فایل";
      body = '<div><span class="file" data-open="' + esc(file) + '">' + esc(relPath(file)) + "</span>" +
        (p.hasDiff ? '<span class="stats"><span class="add">+' + p.added + '</span> <span class="rem">-' + p.removed + "</span></span>" +
          ' <button data-perm="diff">مشاهده در Diff Viewer</button>' : "") + "</div>";
      const d = editDiff(p.tool, input);
      if (d) body += d.html;
      question = p.newFile ? "آیا این فایل ساخته شود؟" : "آیا این تغییرات اعمال شود؟";
    } else if (p.tool === "Bash") {
      title = "دستور Bash";
      body = "<pre>" + esc(input.command || "") + "</pre>" + (input.description ? '<div class="desc">' + esc(input.description) + "</div>" : "");
      question = "آیا اجرا شود؟";
    } else {
      title = '<span dir="ltr">' + esc(displayName(p.tool)) + "</span>";
      const summary = toolSummary(p.tool, input) || JSON.stringify(input, null, 2);
      body = "<pre>" + esc(summary.length > 1500 ? summary.slice(0, 1500) + "…" : summary) + "</pre>";
      question = "آیا اجازه داده شود؟";
    }
    if (p.guardRule) body = '<div class="guardnote">⛨ هشدار محافظ: ' + esc(p.guardText) + "</div>" + body;
    const opts = [["allow", "بله"]];
    if (!p.guardRule) opts.push(["always", p.isEdit ? "بله، و ویرایش‌ها را در این گفتگو دیگر نپرس" : "بله، و برای " + p.tool + " در این گفتگو دیگر نپرس"]);
    opts.push(["deny", "خیر، و به Claude بگو چه کار متفاوتی بکند (esc)"]);
    card.innerHTML = '<div class="ptitle">' + title + "</div>" + body + '<div class="q">' + question + "</div>" +
      '<div class="opts">' + opts.map((o, i) => '<button class="opt' + (i === 0 ? " sel" : "") + '" data-perm="' + o[0] + '">' +
        '<span class="cur">❯</span><span class="num">' + (i + 1) + ".</span><span>" + esc(o[1]) + "</span></button>").join("") + "</div>" +
      '<input class="reason" placeholder="(اختیاری) به Claude بگویید به جای آن چه کند…" dir="auto">' +
      '<div class="hint">کلیدهای ۱/۲/۳ یا ↑↓ و Enter · Esc برای رد</div>';
    card.dataset.id = p.id;
    c.perms[p.id] = card;
    const t = c.tools[p.id];
    const T = t ? t.T : c.main;
    if (t) t.el.after(card);
    else turnEl(T).appendChild(card);
    c.statusText = "منتظر تأیید شما…";
    if (c === chat()) {
      setStatus(c.statusText);
      scrollDown(T, true);
      if (!inputEl.value.trim()) card.focus();
    }
  }

  function resolvePermission(c, p) {
    const card = c.perms[p.id];
    if (!card) return;
    delete c.perms[p.id];
    card.classList.add("resolved");
    const o = document.createElement("div");
    const ok = p.outcome !== "denied";
    o.className = "outcome " + (ok ? "allowed" : "denied");
    o.innerHTML = '<span class="elbow">⎿</span><span></span>';
    o.lastChild.textContent = p.outcome === "allowedModified" ? "پذیرفته شد (با ویرایش‌های شما)" : ok ? "اجازه داده شد" : "رد شد";
    card.appendChild(o);
    if (c.busy) chatStatus(c, "در حال کار…");
    if (document.activeElement === card || document.activeElement === document.body) inputEl.focus();
  }

  function permAct(card, action) {
    if (!card || card.classList.contains("resolved")) return;
    const reason = (card.querySelector(".reason") || {}).value || "";
    toIde({ type: "permission", id: card.dataset.id, action, reason });
  }

  function firstPendingCard() {
    const c = chat();
    if (!c) return null;
    const ids = Object.keys(c.perms);
    return ids.length ? c.perms[ids[0]] : null;
  }

  function permKey(card, e) {
    const opts = [...card.querySelectorAll(".opt")];
    const cur = opts.findIndex((o) => o.classList.contains("sel"));
    const k = e.key;
    const digit = { "1": 0, "2": 1, "3": 2, "۱": 0, "۲": 1, "۳": 2 }[k];
    if (digit !== undefined && opts[digit]) {
      e.preventDefault();
      permAct(card, opts[digit].getAttribute("data-perm"));
    } else if (k === "ArrowDown" || k === "ArrowUp") {
      e.preventDefault();
      const n = (cur + (k === "ArrowDown" ? 1 : -1) + opts.length) % opts.length;
      opts.forEach((o, i) => o.classList.toggle("sel", i === n));
    } else if (k === "Enter") {
      e.preventDefault();
      permAct(card, opts[Math.max(0, cur)].getAttribute("data-perm"));
    } else if (k === "Escape") {
      e.preventDefault();
      permAct(card, "deny");
    }
  }

  // ==================================================================== busy state / status line / info panel
  const SPIN = ["·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢"];
  let spinI = 0;
  setInterval(() => {
    const c = chat();
    if (!c || !c.busy) return;
    $("spin").textContent = SPIN[spinI++ % SPIN.length];
    const turnTok = Math.round(c.usage.turnOut + (c.usage.cur ? c.usage.cur.out : 0));
    $("statusHint").textContent = "(" + Math.round((Date.now() - c.startedAt) / 1000) + "s · ↓ " + fmtNum(turnTok) + " توکن · esc برای توقف)";
  }, 120);

  function setBusy(c, b, text) {
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
    $("infoStateText").textContent = b ? "Running" : "Idle";
    if (b) setStatus(c.statusText || "در حال فکر کردن…");
  }

  function setStatus(text) {
    $("statusText").textContent = text;
  }
  function chatStatus(c, text) {
    c.statusText = text;
    if (c === chat()) setStatus(text);
  }

  /** G.limits is keyed by the CLI's own label ("Current session", "Current week (Fable)", …). */
  function limitLabel(k) {
    if (k === "Current session") return "نشست فعلی";
    if (/^Current week \(all models\)$/i.test(k)) return "هفتگی · همهٔ مدل‌ها";
    const m = /^Current week \((.+)\)$/.exec(k);
    if (m) return "هفتگی · " + m[1];
    return k;
  }
  function rateKeyToLabel(k) {
    if (k === "five_hour") return "Current session";
    if (k === "seven_day") return "Current week (all models)";
    const m = /^seven_day_(\w+)$/.exec(k);
    return m ? "Current week (" + cap(m[1]) + ")" : k;
  }
  function setLimit(label, pct, resetsAt) {
    const l = G.limits[label] || (G.limits[label] = {});
    l.pct = pct;
    if (resetsAt) l.resetsAt = resetsAt;
  }
  let usageTimer = 0;
  /** Ask the IDE to run the local /usage command (free); at most once a minute unless forced. */
  function requestUsage(force) {
    if (!force && Date.now() - G.usageAt < 60000) {
      if (!usageTimer) usageTimer = setTimeout(() => { usageTimer = 0; requestUsage(true); }, 60000 - (Date.now() - G.usageAt));
      return;
    }
    G.usageAt = Date.now();
    toIde({ type: "usage" });
  }

  function renderInfo() {
    const c = chat();
    if (!c) return;
    const u = c.usage;
    const pct = Math.min(100, Math.round((u.ctx / (u.window || 200000)) * 100));
    $("infoCtx").textContent = pct + "%";
    const bar = $("infoCtxBar");
    bar.style.width = pct + "%";
    bar.classList.toggle("high", pct >= 70);
    $("infoOut").textContent = fmtNum(Math.round(liveOut(u)));
    $("infoReason").textContent = fmtNum(Math.round(liveReason(u)));
    $("infoCost").textContent = "$" + u.cost.toFixed(u.cost < 1 ? 4 : 2);
    $("infoModel").textContent = c.model ? modelLabel(c.model) : G.model ? modelLabel(G.model) : "default";
    $("infoCwd").textContent = tildePath(c.cwd || G.projectPath);
    const a = G.account;
    $("infoAccount").textContent = a && a.email ? a.email : a && a.loggedIn === false ? "وارد نشده" : "—";
    $("infoPlan").textContent = a && (a.planTier || a.subscriptionType) ? "Claude " + cap(a.planTier || a.subscriptionType) : "—";
    $("infoProvider").textContent = (a && a.apiProvider) || "—";
    $("infoLimits").innerHTML = Object.keys(G.limits).map((k) => {
      const l = G.limits[k];
      const p = Math.round(l.pct || 0);
      return '<span class="lim"><span class="k">' + esc(limitLabel(k)) + '</span><span class="bar"><span style="width:' + p + '%"' +
        (p >= 80 ? ' class="high"' : "") + '></span></span><span class="v">' + p + "%</span><span>" + esc(fmtReset(l.resetsAt)) + "</span></span>";
    }).join("");
  }

  function trackUsage(c, msg) {
    const u = msg && msg.usage;
    if (!u) return;
    const ctx = (u.input_tokens || 0) + (u.cache_read_input_tokens || 0) + (u.cache_creation_input_tokens || 0);
    if (ctx > 0) c.usage.ctx = ctx;
    if (c === chat()) renderInfo();
  }

  function onRateLimit(info) {
    if (!info) return;
    if (info.unifiedWindows) {
      Object.keys(info.unifiedWindows).forEach((k) => {
        const w = info.unifiedWindows[k];
        setLimit(rateKeyToLabel(k), (w.utilization || 0) * 100, w.resetsAt);
      });
    } else if (info.rateLimitType) setLimit(rateKeyToLabel(info.rateLimitType), (info.utilization || 0) * 100, info.resetsAt);
    renderInfo();
    if (G.view === "session") renderSession();
  }

  // ==================================================================== Claude stream-json events
  function targetFor(c, parentId) {
    return parentId ? agentFor(c, parentId).T : c.main;
  }

  /** Rough token estimate for streamed text (~3.5 chars per token, Persian/code mixed). */
  function estTok(s) {
    return (s ? s.length : 0) / 3.5;
  }

  function handleStream(c, T, ev) {
    const main = T === c.main;
    const u = c.usage;
    switch (ev.type) {
      case "message_start":
        if (ev.message && ev.message.id) T.streamed.add(ev.message.id);
        T.blocks = {};
        if (main) {
          const mu = (ev.message && ev.message.usage) || {};
          const ctx = (mu.input_tokens || 0) + (mu.cache_read_input_tokens || 0) + (mu.cache_creation_input_tokens || 0);
          if (ctx > 0) u.ctx = ctx;
          u.cur = { out: mu.output_tokens || 0, reason: 0 };
          scheduleInfo(c);
        }
        break;
      case "message_delta":
        if (main && ev.usage) {
          const out = ev.usage.output_tokens || 0;
          const reason = (ev.usage.output_tokens_details && ev.usage.output_tokens_details.thinking_tokens) || 0;
          u.turnOut += out;
          u.turnReason += reason;
          u.cur = null;
          scheduleInfo(c);
        }
        break;
      case "content_block_start": {
        const cb = ev.content_block || {};
        if (cb.type === "text") T.blocks[ev.index] = { type: "text", el: newTextBlock(T), text: cb.text || "" };
        else if (cb.type === "tool_use") {
          addTool(c, T, cb.id, cb.name, null);
          T.blocks[ev.index] = { type: "tool" };
        } else if (cb.type === "thinking") chatStatus(c, "در حال فکر کردن…");
        break;
      }
      case "content_block_delta": {
        const b = T.blocks[ev.index];
        const d = ev.delta || {};
        if (main && u.cur) {
          const est = estTok(d.text || d.thinking || d.partial_json || "");
          u.cur.out += est;
          if (d.type === "thinking_delta") u.cur.reason += est;
          scheduleInfo(c);
        }
        if (d.type === "thinking_delta") chatStatus(c, "در حال فکر کردن…");
        if (b && b.type === "text" && d.type === "text_delta") {
          b.text += d.text;
          scheduleRender(T, b);
          chatStatus(c, "در حال نوشتن…");
        }
        break;
      }
    }
  }

  function handleAssistant(c, T, msg) {
    if (!msg || !Array.isArray(msg.content)) return;
    if (T === c.main) trackUsage(c, msg);
    const streamed = msg.id && T.streamed.has(msg.id);
    msg.content.forEach((x) => {
      if (x.type === "text" && !streamed) newTextBlock(T).innerHTML = markdown(x.text || "");
      else if (x.type === "tool_use") {
        addTool(c, T, x.id, x.name, x.input);
        chatStatus(c, "در حال " + (TOOL_FA[x.name] || "اجرای " + x.name) + "…");
      }
    });
    scrollDown(T);
  }

  function handleToolResults(c, T, msg) {
    if (!msg || !Array.isArray(msg.content)) return;
    msg.content.forEach((x) => {
      const t = x.type === "tool_result" && c.tools[x.tool_use_id];
      if (t) setToolResult(c, x.tool_use_id, t, !!x.is_error, x.content);
    });
    chatStatus(c, "در حال فکر کردن…");
    scrollDown(T);
  }

  function handleResult(c, ev) {
    const u = ev.usage || {};
    const cu = c.usage;
    // The result carries the exact totals of the whole turn; they replace the live numbers.
    cu.outTok += u.output_tokens || Math.round(cu.turnOut);
    cu.reasoning += (u.output_tokens_details && u.output_tokens_details.thinking_tokens) || Math.round(cu.turnReason);
    cu.turnOut = 0;
    cu.turnReason = 0;
    cu.cur = null;
    if (typeof ev.total_cost_usd === "number") c.usage.cost += ev.total_cost_usd;
    if (ev.modelUsage) Object.values(ev.modelUsage).forEach((m) => { if (m && m.contextWindow) c.usage.window = m.contextWindow; });
    if (ev.session_id) c.sessionId = ev.session_id;
    if (ev.is_error || (ev.subtype && ev.subtype !== "success")) notice(c, c.main, "خطا: " + (ev.result || ev.subtype || "نامشخص"), "error");
    const parts = [];
    if (ev.duration_ms) parts.push((ev.duration_ms / 1000).toFixed(1) + "s");
    if (ev.num_turns) parts.push(ev.num_turns + " مرحله");
    if (typeof ev.total_cost_usd === "number") parts.push("$" + ev.total_cost_usd.toFixed(4));
    if (parts.length) {
      const m = document.createElement("div");
      m.className = "meta";
      m.textContent = "✻ " + parts.join(" · ");
      turnEl(c.main).appendChild(m);
    }
    if (c === chat()) renderInfo();
    requestUsage(false);
    if (G.view === "session" && c === chat()) toIde({ type: "session", chat: c.id });
    scrollDown(c.main);
  }

  function onClaude(c, ev) {
    const T = targetFor(c, ev.parent_tool_use_id);
    switch (ev.type) {
      case "system":
        if (ev.subtype === "init") {
          chatStatus(c, "در حال فکر کردن…");
          c.model = ev.model || c.model;
          c.cwd = ev.cwd || c.cwd;
          c.version = ev.claude_code_version || c.version;
          if (Array.isArray(ev.skills)) c.activeSkills = ev.skills;
          if (Array.isArray(ev.plugins)) c.pluginPaths = ev.plugins.map((p) => p.path).filter(Boolean);
          if (G.view === "skills" && c === chat()) renderSkills();
          if (ev.session_id) c.sessionId = ev.session_id;
          if (!c.connected) {
            c.connected = true;
            const mcp = (ev.mcp_servers || []).find((m) => m.name === "gapgpt");
            notice(c, c.main, "✓ آماده — Claude Code" + (mcp && mcp.status === "connected" ? " به IDE متصل شد" : " اجرا شد"), "");
          }
          if (c === chat()) renderInfo();
        }
        break;
      case "rate_limit_event":
        onRateLimit(ev.rate_limit_info);
        break;
      case "stream_event":
        handleStream(c, T, ev.event || {});
        break;
      case "assistant":
        handleAssistant(c, T, ev.message);
        break;
      case "user":
        handleToolResults(c, T, ev.message);
        break;
      case "result":
        handleResult(c, ev);
        break;
    }
  }

  // ==================================================================== mode / model / effort / context chip
  const MODES = [
    ["default", "هر بار بپرس", "برای هر ویرایش و دستور اجازه گرفته می‌شود"],
    ["acceptEdits", "پذیرش خودکار ویرایش‌ها", "ویرایش فایل‌ها بدون پرسش؛ دستورها با پرسش"],
    ["plan", "حالت برنامه‌ریزی", "فقط بررسی و برنامه؛ هیچ تغییری اعمال نمی‌شود"],
    ["bypassPermissions", "بدون محدودیت", "همه چیز بدون پرسش (خطرناک — محافظ هم کار نمی‌کند)"],
    ["dontAsk", "نپرس", "هر چه نیاز به اجازه دارد خودکار رد می‌شود"],
    ["auto", "خودکار", "Claude خودش دربارهٔ اجازه‌ها تصمیم می‌گیرد"],
  ];
  // Aliases always resolve to the newest version of a family.
  const MODELS = [
    ["", "پیش‌فرض", "مدل پیش‌فرض حساب شما"],
    ["opus", "Opus (آخرین نسخه)", "قوی‌ترین؛ برای سخت‌ترین و طولانی‌ترین کارها"],
    ["sonnet", "Sonnet (آخرین نسخه)", "بهترین برای کارهای روزمره و پیچیده"],
    ["haiku", "Haiku (آخرین نسخه)", "سریع‌ترین؛ برای پاسخ‌های کوتاه و کارهای ساده"],
    ["fable", "Fable (آخرین نسخه)", "آخرین مدل خانوادهٔ Fable"],
    ["opusplan", "Opus Plan", "Opus برای برنامه‌ریزی، Sonnet برای اجرا"],
    ["opus[1m]", "Opus · 1M context", "context یک میلیون توکنی"],
    ["sonnet[1m]", "Sonnet · 1M context", "context یک میلیون توکنی"],
    ["fable[1m]", "Fable · 1M context", "context یک میلیون توکنی"],
  ];
  // Specific versions (model IDs known to Claude Code 2.1.x). Your plan may not include all of them.
  const MODEL_FAMILIES = [
    ["Opus", [["claude-opus-5-5", "Opus 5.5"], ["claude-opus-5", "Opus 5"], ["claude-opus-4-8", "Opus 4.8"], ["claude-opus-4-7", "Opus 4.7"],
      ["claude-opus-4-6", "Opus 4.6"], ["claude-opus-4-5", "Opus 4.5"], ["claude-opus-4-1", "Opus 4.1"], ["claude-opus-4-0", "Opus 4"]]],
    ["Sonnet", [["claude-sonnet-5-5", "Sonnet 5.5"], ["claude-sonnet-5", "Sonnet 5"], ["claude-sonnet-4-6", "Sonnet 4.6"],
      ["claude-sonnet-4-5", "Sonnet 4.5"], ["claude-sonnet-4-0", "Sonnet 4"]]],
    ["Haiku", [["claude-haiku-5-5", "Haiku 5.5"], ["claude-haiku-4-5", "Haiku 4.5"]]],
    ["Fable", [["claude-fable-5-2", "Fable 5.2"], ["claude-fable-5-1", "Fable 5.1"], ["claude-fable-5", "Fable 5"]]],
  ];
  const ALL_MODELS = MODELS.concat(...MODEL_FAMILIES.map((f) => f[1]));
  const EFFORTS = [["", "Default"], ["low", "Low"], ["medium", "Medium"], ["high", "High"], ["xhigh", "XHigh"], ["max", "Max"]];

  function setMode(m, notify) {
    G.permissionMode = m || "default";
    const e = MODES.find((x) => x[0] === G.permissionMode) || MODES[0];
    const lbl = $("ddMode").querySelector(".lbl");
    lbl.textContent = e[1];
    lbl.className = "lbl " + e[0];
    if (notify) toIde({ type: "setMode", value: G.permissionMode });
  }
  function setModel(m, notify) {
    G.model = m || "";
    const e = MODELS.find((x) => x[0] === G.model);
    $("ddModel").querySelector(".lbl").textContent = e ? e[1].replace(" (آخرین نسخه)", "") : modelLabel(G.model);
    if (notify) toIde({ type: "setModel", value: G.model });
    renderInfo();
  }
  function setEffort(v, notify) {
    G.effort = v || "";
    const e = EFFORTS.find((x) => x[0] === G.effort) || EFFORTS[0];
    $("ddEffort").querySelector(".lbl").textContent = e[1];
    if (notify) toIde({ type: "setEffort", value: G.effort });
  }
  function setGuardUi() {
    $("btnGuard").classList.toggle("on", G.guardEnabled);
    $("btnGuard").title = "محافظ (Guard): " + (G.guardEnabled ? "فعال" : "غیرفعال");
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
    chip.title = x.file + " — همراه پیام فرستاده " + (c.shareCtx ? "می‌شود" : "نمی‌شود") + ". برای تغییر کلیک کنید.";
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
      if (it.input) {
        const inp = document.createElement("input");
        inp.placeholder = it.input.placeholder || "";
        inp.dir = "auto";
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
    let right = window.innerWidth - r.right;
    if (window.innerWidth - right - w < 4) right = Math.max(4, window.innerWidth - w - 4);
    m.style.right = right + "px";
    m.style.left = "auto";
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

  function menuAnchorFor(id) {
    return $(id);
  }
  function modelMenu(anchor) {
    const items = MODELS.map((m) => ({ label: m[1], desc: m[2], checked: G.model === m[0], onClick: () => setModel(m[0], true) }));
    items.push({ sep: true }, { header: "انتخاب نسخهٔ مشخص" });
    MODEL_FAMILIES.forEach(([fam, versions]) => {
      const hasCurrent = versions.some((v) => v[0] === G.model);
      const open = G.modelOpen[fam] !== undefined ? G.modelOpen[fam] : hasCurrent;
      G.modelOpen[fam] = open;
      items.push({ label: (open ? "▾ " : "◂ ") + fam + "  (" + versions.length + " نسخه)", cls: "fam", family: fam, keepOpen: true });
      if (open) versions.forEach((v) => items.push({ label: v[1], desc: v[0], cls: "ver", checked: G.model === v[0], onClick: () => setModel(v[0], true) }));
    });
    if (G.model && !ALL_MODELS.some((m) => m[0] === G.model)) items.push({ label: G.model, checked: true });
    items.push({ sep: true }, { header: "مدل دیگر" }, {
      input: {
        placeholder: "شناسهٔ مدل، مثلاً claude-opus-5-5 و Enter",
        onEnter: (v) => {
          if (!v.trim()) return;
          setModel(v.trim(), true);
          closeMenu();
        },
      },
    });
    showMenu(anchor, items);
  }
  function modeMenu(anchor) {
    showMenu(anchor, MODES.map((m) => ({ label: m[1], desc: m[2], checked: G.permissionMode === m[0], onClick: () => setMode(m[0], true) })));
  }
  function effortMenu(anchor) {
    showMenu(anchor, EFFORTS.map((e) => ({ label: e[1], checked: G.effort === e[0], onClick: () => setEffort(e[0], true) })));
  }
  function moreMenu(anchor) {
    showMenu(anchor, [
      { label: "دنبال کردن خودکار اسکرول", checked: G.autoFollow, onClick: () => { G.autoFollow = !G.autoFollow; toIde({ type: "setAutoFollow", value: G.autoFollow }); } },
      { label: "جستجو در گفتگو", onClick: openSearch },
      { label: "پاک کردن این گفتگو (/clear)", onClick: () => chat() && clearChat(chat()) },
      { sep: true },
      { label: "آسیب‌پذیری وابستگی‌ها", onClick: () => switchView("vulns") },
      { label: "لاگ پلاگین", onClick: () => switchView("log") },
      { label: "تنظیمات", onClick: () => toIde({ type: "settings" }) },
      { sep: true },
      { label: "بستن این گفتگو", onClick: () => closeChat(G.active) },
      { label: "خروج از حساب Claude", onClick: () => toIde({ type: "logout" }) },
    ]);
  }
  let attachFilter = "";
  function attachMenu(anchor) {
    attachFilter = "";
    showMenu(anchor, [
      { input: { placeholder: "جستجوی فایل‌های اخیر…", focus: true, onInput: (v) => { attachFilter = v.toLowerCase(); renderRecent(); } } },
      { label: "فایل‌ها…", onClick: () => toIde({ type: "attach", kind: "files" }) },
      { label: "پوشه…", onClick: () => toIde({ type: "attach", kind: "dir" }) },
      { label: "تصویر…", onClick: () => toIde({ type: "attach", kind: "image" }) },
      { header: "فایل‌های اخیر" },
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
    if (!m.items.length) return (box.innerHTML = '<div class="none" dir="rtl">فایلی پیدا نشد</div>');
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
      '<span class="desc">' + esc(c.description || "") + '</span><span class="src">' + esc(c.source === "builtin" ? "" : c.source) + "</span></div>").join("");
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
      case "workloads":
        return renderWorkloads();
      case "git":
        renderGit();
        return toIde({ type: "git" });
      case "guard":
        renderGuard();
        return toIde({ type: "guard" });
      case "log":
        renderLogView();
        return toIde({ type: "log" });
      case "vulns":
        renderVulns();
        if (!G.vulnInv) toIde({ type: "vulnInventory" });
        return;
      case "session": {
        const c = chat();
        renderSession();
        requestUsage(false);
        if (c) toIde({ type: "session", chat: c.id });
        return;
      }
      case "skills": {
        const c = chat();
        renderSkills();
        return toIde({ type: "skills", pluginPaths: (c && c.pluginPaths) || [] });
      }
      case "rules":
        renderRules();
        return toIde({ type: "rules" });
    }
  }

  // ---------------------------------------------------------------- skills
  let skillsBuilt = false;
  function renderSkills() {
    const v = $("view-skills");
    if (!skillsBuilt) {
      skillsBuilt = true;
      v.innerHTML = '<div class="cardbox"><h4>اسکیل‌ها، دستورها و عامل‌ها</h4><div class="muted">هر چیزی که Claude در این پروژه می‌تواند از آن استفاده کند: ' +
        'از پوشهٔ <span class="mono ltr">.claude</span> پروژه، <span class="mono ltr">~/.claude</span> و پلاگین‌های نصب‌شده. «اجرا» دستور را در کادر پیام می‌گذارد.</div>' +
        '<input class="field" id="skQ" placeholder="جستجوی نام یا توضیح…" dir="auto" style="width:100%;margin-top:8px"></div><div id="skBody"></div>';
      $("skQ").addEventListener("input", (e) => {
        G.skillQ = e.target.value.toLowerCase();
        renderSkillsBody();
      });
    }
    renderSkillsBody();
  }
  function renderSkillsBody() {
    const box = $("skBody");
    if (!box) return;
    const d = G.skills;
    if (!d) return (box.innerHTML = '<div class="cardbox"><div class="muted">در حال خواندن…</div></div>');
    const c = chat();
    const active = (c && c.activeSkills) || null;
    const q = G.skillQ;
    const match = (x) => !q || (x.name + " " + x.description).toLowerCase().includes(q);
    const srcLabel = (src) => (src === "project" ? "پروژه" : src === "user" ? "کاربر" : src.replace(/^plugin:/, "پلاگین: "));
    const section = (title, items, runnable) => {
      const list = items.filter(match);
      const groups = {};
      list.forEach((x) => (groups[x.source] = groups[x.source] || []).push(x));
      const order = Object.keys(groups).sort((a, b) => (a === "project" ? -1 : b === "project" ? 1 : a === "user" ? -1 : b === "user" ? 1 : a.localeCompare(b)));
      return '<div class="cardbox"><h4>' + title + " (" + list.length + ")</h4>" + (list.length ? order.map((src) =>
        "<h5>" + esc(srcLabel(src)) + '</h5><div class="rows">' + groups[src].map((x) =>
          '<div class="r"><span class="grow"><span class="code">' + (runnable ? "/" : "") + esc(x.name) +
          (active && runnable && active.indexOf(x.name) >= 0 ? ' <span class="tag on">فعال در این نشست</span>' : "") +
          '</span><span class="sub" dir="auto">' + esc(x.description || "—") + "</span></span>" +
          '<span class="acts">' + (runnable ? '<button class="pillbtn" data-run-skill="' + esc(x.name) + '">اجرا</button>' : "") +
          '<button class="icon" data-open-path="' + esc(x.path) + '" title="باز کردن فایل">↗</button></span></div>').join("") + "</div>").join("")
        : '<div class="empty-note">چیزی پیدا نشد.</div>') + "</div>";
    };
    box.innerHTML = section("اسکیل‌ها", d.skills || [], true) + section("دستورهای سفارشی", d.commands || [], true) + section("عامل‌ها (subagents)", d.agents || [], false);
  }

  // ---------------------------------------------------------------- rules
  const SCOPE_FA = { project: "پروژه", local: "محلی (شخصی، داخل git نمی‌رود)", user: "کاربر (همهٔ پروژه‌ها)", parent: "پوشهٔ والد",
    "project-rule": "قانون پروژه (.claude/rules)", "user-rule": "قانون کاربر (~/.claude/rules)", "auto-memory": "حافظهٔ خودکار" };
  function renderRules() {
    const v = $("view-rules");
    const r = G.rules;
    if (!r) return (v.innerHTML = '<div class="cardbox"><div class="muted">در حال خواندن…</div></div>');
    const mem = r.memory || [];
    const projectMd = mem.find((m) => m.scope === "project" && /\/CLAUDE\.md$/.test(m.path) && !/\.claude\/CLAUDE\.md$/.test(m.path));
    let html = '<div class="cardbox"><h4>دستورالعمل‌ها (CLAUDE.md)</h4><div class="muted">Claude این فایل‌ها را در شروع هر نشست می‌خواند و از آن‌ها پیروی می‌کند.</div><div class="rows">' +
      mem.filter((m) => m.exists).map((m) => '<div class="r"><span class="grow"><span class="code">' + esc(tildePath(m.path)) +
        '</span><span class="sub">' + esc(SCOPE_FA[m.scope] || m.scope) + " · " + m.lines + ' خط</span><div class="preview">' + esc(m.preview || "") + "</div></span>" +
        '<span class="acts"><button class="icon" data-open-path="' + esc(m.path) + '" title="باز کردن">↗</button></span></div>').join("") + "</div>";
    if (projectMd && !projectMd.exists) {
      html += '<div class="notice warn">این پروژه هنوز CLAUDE.md ندارد.</div><div class="btnrow">' +
        '<button class="primary" data-run-init="1">ساخت با Claude (/init)</button>' +
        '<button class="pillbtn" data-create-path="' + esc(projectMd.path) + '">ساخت فایل خالی</button></div>';
    }
    html += "</div>";
    const settings = r.settings || [];
    const chips = (arr, cls) => (arr && arr.length ? '<div class="rulechips">' + arr.map((x) => '<span class="tag ' + cls + '">' + esc(x) + "</span>").join("") + "</div>" : "");
    html += '<div class="cardbox"><h4>قوانین دسترسی (permissions)</h4><div class="muted">ابزارها و دستورهایی که Claude بدون پرسش مجاز (allow)، با پرسش (ask) یا ممنوع (deny) است. ' +
      "قوانین deny از همه مهم‌ترند.</div>" + (settings.length ? settings.map((st) => {
        const has = (st.allow && st.allow.length) || (st.ask && st.ask.length) || (st.deny && st.deny.length) || st.defaultMode || (st.additionalDirectories && st.additionalDirectories.length);
        return "<h5>" + esc(SCOPE_FA[st.scope] || st.scope) + ' · <span class="mono ltr">' + esc(tildePath(st.path)) + '</span> <button class="icon" data-open-path="' + esc(st.path) + '">↗</button></h5>' +
          (has ? (st.defaultMode ? '<div class="sub">حالت پیش‌فرض: <span class="mono">' + esc(st.defaultMode) + "</span></div>" : "") +
            (st.allow && st.allow.length ? '<div class="sub">مجاز:</div>' + chips(st.allow, "allow") : "") +
            (st.ask && st.ask.length ? '<div class="sub">با پرسش:</div>' + chips(st.ask, "ask") : "") +
            (st.deny && st.deny.length ? '<div class="sub">ممنوع:</div>' + chips(st.deny, "deny") : "") +
            (st.additionalDirectories && st.additionalDirectories.length ? '<div class="sub">پوشه‌های اضافه:</div>' + chips(st.additionalDirectories, "") : "")
            : '<div class="empty-note">قانون دسترسی ندارد.</div>');
      }).join("") : '<div class="empty-note">هیچ فایل settings.json پیدا نشد.</div>') + "</div>";
    const hooks = [];
    settings.forEach((st) => (st.hooks || []).forEach((h) => hooks.push({ scope: st.scope, event: h.event, commands: h.commands || [] })));
    html += '<div class="cardbox"><h4>Hookها</h4><div class="muted">دستورهایی که در رویدادهای Claude (مثل قبل یا بعد از هر ابزار) خودکار اجرا می‌شوند.</div><div class="rows">' +
      (hooks.length ? hooks.map((h) => '<div class="r"><span class="tag">' + esc(h.event) + '</span><span class="grow">' +
        h.commands.map((cmd) => '<span class="code">' + esc(cmd) + "</span>").join("") + '<span class="sub">' + esc(SCOPE_FA[h.scope] || h.scope) + "</span></span></div>").join("")
        : '<div class="empty-note">هیچ hookی تعریف نشده.</div>') + "</div></div>";
    const plugins = [];
    settings.forEach((st) => (st.plugins || []).forEach((p) => plugins.push(p)));
    if (plugins.length) html += '<div class="cardbox"><h4>پلاگین‌های فعال</h4>' + chips(plugins, "on") + "</div>";
    v.innerHTML = html;
  }
  function sendFromView(text) {
    switchView("chat");
    send(text);
  }

  // ---------------------------------------------------------------- workloads
  function renderWorkloads() {
    const v = $("view-workloads");
    const c = chat();
    const keep = G.keepMin;
    const agents = c ? c.agentOrder.map((id) => c.agents[id]).filter((a) => a.status === "running" || !keep || Date.now() - a.ended < keep * 60000) : [];
    const opt = (m, l) => '<option value="' + m + '"' + (keep === m ? " selected" : "") + ">" + l + "</option>";
    v.innerHTML = '<div class="cardbox"><h4>کارها (Workloads)</h4>' +
      '<div class="muted">نگه‌داشتن کارهای تمام‌شده به مدت <select class="field" id="wlKeep">' +
      opt(5, "۵ دقیقه") + opt(15, "۱۵ دقیقه") + opt(60, "۱ ساعت") + opt(0, "همیشه") + "</select></div><br>" +
      '<div class="wl-canvas">' + (c ? '<div class="wl-root">' + esc(c.title || "گفتگوی جدید") + "</div>" : "") +
      (agents.length ? '<div class="wl-links' + (agents.length > 1 ? " multi" : "") + '">' + agents.map((a) =>
        '<div class="wl-node ' + a.status + '" data-open-agent="' + a.id + '"><span class="dot ' + (a.status === "running" ? "busy" : a.status === "error" ? "err" : "ok") +
        '"></span><span class="d">' + esc(a.desc || "Agent") + '</span><span class="ty">' + esc(a.type || "") + "</span></div>").join("") + "</div>"
        : '<div class="muted" style="margin-left:24px" dir="rtl">هنوز زیرعاملی (Task) در این گفتگو اجرا نشده.</div>') +
      "</div></div>";
    $("wlKeep").addEventListener("change", (e) => {
      G.keepMin = +e.target.value;
      renderWorkloads();
    });
  }

  // ---------------------------------------------------------------- git
  const GIT_ACTIONS = [["Git.Branches", "شاخه‌ها"], ["Git.Pull", "Pull"], ["Git.Fetch", "Fetch"], ["Vcs.Push", "Push"], ["Git.Merge", "Merge"], ["Git.Rebase", "Rebase"]];
  function renderGit() {
    const v = $("view-git");
    const g = G.git;
    if (!g) return (v.innerHTML = '<div class="cardbox"><div class="muted">در حال خواندن مخزن…</div></div>');
    if (g.error) return (v.innerHTML = '<div class="cardbox"><h4>مخزن</h4><div class="muted">' + esc(g.error) + "</div></div>");
    const kv = (k, val) => '<span class="k">' + k + '</span><span class="v">' + esc(val) + "</span>";
    v.innerHTML =
      '<div class="cardbox"><h4>مخزن</h4><div class="ltr" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;text-align:left">' +
      '<b class="mono">' + esc(g.root) + '</b><span class="badge">' + esc(g.branch) + "</span></div>" +
      '<div class="muted mono ltr" style="text-align:left">' + esc(g.head) + "</div>" +
      (g.headSubject ? '<div class="muted" dir="auto">' + esc(g.headSubject) + "</div>" : "") + "</div>" +
      '<div class="cards2"><div class="cardbox"><h4>شاخه</h4><div class="kv">' +
      (g.tracking ? kv("دنبال‌کردن", g.tracking) + kv("جلوتر (ahead)", g.ahead) + kv("عقب‌تر (behind)", g.behind) + kv("نقطهٔ جدا شدن", g.divergedAt || "—")
        : kv("دنبال‌کردن", "بدون upstream")) + kv("فایل‌های تغییرکرده", g.changed) +
      '</div><div class="muted" style="margin-top:6px">نسبت به آخرین fetch شما شمرده شده است.</div></div>' +
      '<div class="cardbox"><h4>commitهای اخیر</h4><div class="rows">' + (g.log || []).map((l) =>
        '<div class="r"><span class="mono" style="color:var(--accent)">' + esc(l.hash) + '</span><span class="grow" dir="auto">' + esc(l.subject) +
        '<span class="sub"> · ' + esc(l.author) + " · " + esc(l.when) + "</span></span></div>").join("") + "</div></div></div><br>" +
      '<div class="cardbox"><h4>عملیات</h4><h5>از Claude بخواه</h5><div class="btnrow">' +
      '<button class="pillbtn" data-git-ask="commit">Commit با Claude</button>' +
      '<button class="pillbtn" data-git-ask="revert">برگرداندن این فایل با Claude</button>' +
      '<button class="pillbtn" data-git-ask="review">بررسی تغییرات (/review)</button></div>' +
      '<h5>مخزن</h5><div class="btnrow"><button class="pillbtn" data-ide-action="Vcs.Show.Log">Git log</button>' +
      '<button class="pillbtn" data-ide-action="CheckinProject">Commit…</button><button class="pillbtn" data-git-refresh="1">↻ تازه‌سازی</button></div>' +
      '<h5>عملیات IDE</h5><div class="btnrow">' + GIT_ACTIONS.map((a) => '<button class="pillbtn" data-ide-action="' + a[0] + '">' + a[1] + "</button>").join("") +
      "</div></div>";
  }
  function gitAsk(kind) {
    if (kind === "commit") return sendFromView("تغییرات فعلی را بررسی کن (git status و git diff) و با یک پیام commit دقیق و مناسب commit کن. push نکن.");
    if (kind === "review") return sendFromView("/review");
    if (kind === "revert") {
      const f = G.ctx && G.ctx.file;
      if (!f) return alertNotice("اول فایلی را در ویرایشگر باز کنید.");
      sendFromView("تغییرات فایل `" + f + "` را نسبت به آخرین commit خلاصه کن، سپس آن را به نسخهٔ آخرین commit برگردان (git checkout -- " + f + ").");
    }
  }
  function alertNotice(t) {
    const c = chat();
    switchView("chat");
    notice(c, c.main, t, "warn");
  }

  // ---------------------------------------------------------------- guard
  let guardBuilt = false;
  function renderGuard() {
    const v = $("view-guard");
    if (!guardBuilt) {
      guardBuilt = true;
      v.innerHTML =
        '<div class="cardbox"><h4>لاگ محافظ (Guard)</h4><div style="display:flex;justify-content:space-between;gap:10px;flex-wrap:wrap">' +
        '<div class="muted" id="gSummary"></div><div><label class="switch"><input type="checkbox" id="gEnabled"> محافظ فعال</label> &nbsp; ' +
        '<label class="switch"><input type="checkbox" id="gOnlyChat"> فقط این گفتگو</label></div></div>' +
        '<div class="muted" style="margin-top:4px">محافظ هر درخواستی را که برای اجازه به IDE می‌آید با قوانین زیر می‌سنجد: قوانین «مسدود» بلافاصله رد می‌شوند و قوانین «هشدار» حتی اگر قبلاً «همیشه اجازه» داده باشید دوباره از شما می‌پرسند. در حالت «بدون محدودیت» درخواستی به IDE نمی‌آید.</div></div>' +
        '<div class="cardbox"><h4>فیلتر</h4><div style="display:grid;grid-template-columns:2fr 1fr 1fr;gap:8px">' +
        '<input class="field" id="gQ" placeholder="قانون، دستور، ابزار…" dir="auto"><select class="field" id="gCat"></select><select class="field" id="gRule"></select></div></div>' +
        '<div class="cardbox"><h4>تصمیم‌ها</h4><div class="segtabs" id="gTabs"></div><div class="rows" id="gRows"></div></div>' +
        '<div class="cards2"><div class="cardbox"><h4>قوانین</h4><div class="rows" id="gRules"></div></div>' +
        '<div class="cardbox"><h4>لیست سفید</h4><div class="rows" id="gWhite"></div></div></div>';
      $("gEnabled").addEventListener("change", (e) => toIde({ type: "guardToggle", value: e.target.checked }));
      $("gOnlyChat").addEventListener("change", (e) => {
        G.guardOnlyChat = e.target.checked;
        renderGuardBody();
      });
      ["gQ", "gCat", "gRule"].forEach((id) => $(id).addEventListener("input", renderGuardBody));
    }
    renderGuardBody();
  }
  const GUARD_TABS = [["blocked", "مسدود شده"], ["allowed", "اجازه داده شده"], ["denied", "رد شده"], ["whitelisted", "لیست سفید"], ["disabled", "غیرفعال"]];
  function guardTabCount(g) {
    const cnt = g.decisions.filter((d) => d.chat === G.active && d.outcome === "blocked").length;
    document.querySelector('#viewTabs .vt[data-view="guard"]').innerHTML = "محافظ" + (cnt ? '<span class="count">' + cnt + "</span>" : "");
  }
  function renderGuardBody() {
    const g = G.guard;
    if (!g || !guardBuilt) return;
    $("gEnabled").checked = g.enabled;
    $("gOnlyChat").checked = G.guardOnlyChat;
    const cats = [...new Set(g.rules.map((r) => r.category))];
    const keepSel = (id, opts) => {
      const el = $(id), cur = el.value;
      el.innerHTML = opts.map((o) => '<option value="' + esc(o[0]) + '">' + esc(o[1]) + "</option>").join("");
      el.value = cur;
    };
    keepSel("gCat", [["", "همهٔ دسته‌ها"]].concat(cats.map((c) => [c, c])));
    keepSel("gRule", [["", "همهٔ قوانین"]].concat(g.rules.map((r) => [r.id, r.id])));
    const q = $("gQ").value.toLowerCase(), cat = $("gCat").value, rule = $("gRule").value;
    let ds = g.decisions.slice().reverse();
    if (G.guardOnlyChat) ds = ds.filter((d) => d.chat === G.active);
    ds = ds.filter((d) => (!cat || d.category === cat) && (!rule || d.ruleId === rule) &&
      (!q || (d.ruleId + " " + d.tool + " " + d.target).toLowerCase().includes(q)));
    $("gSummary").textContent = "نمایش " + ds.length + " مورد" + (G.guardOnlyChat ? " از این گفتگو" : "") + ". لاگ ۵۰۰ مورد آخر کل پروژه را نگه می‌دارد.";
    $("gTabs").innerHTML = GUARD_TABS.map((t) => '<button class="' + (G.guardTab === t[0] ? "on" : "") + '" data-gtab="' + t[0] + '">' + t[1] +
      " (" + ds.filter((d) => d.outcome === t[0]).length + ")</button>").join("");
    const rows = ds.filter((d) => d.outcome === G.guardTab);
    $("gRows").innerHTML = rows.length ? rows.map((d) => '<div class="r"><span class="tag ' + d.outcome + '">' + esc(d.ruleId) + "</span>" +
      '<span class="grow"><span class="code">' + esc(d.target) + '</span><span class="sub">' + esc(d.time + " · " + d.tool + " · " + d.category) + "</span></span>" +
      ((d.outcome === "blocked" || d.outcome === "denied") && g.whitelist.indexOf(d.target) < 0
        ? '<button class="pillbtn" data-gwhite="' + esc(d.target) + '">افزودن به لیست سفید</button>' : "") + "</div>").join("")
      : '<div class="empty-note">در این گفتگو چیزی اینجا نیامده.</div>';
    $("gRules").innerHTML = g.rules.map((r) => '<div class="r"><label class="switch"><input type="checkbox" data-grule="' + r.id + '"' + (r.enabled ? " checked" : "") + "></label>" +
      '<span class="grow"><span dir="auto">' + esc(r.description) + '</span><span class="sub mono"> ' + esc(r.id) + " · " + esc(r.category) + "</span></span>" +
      '<span class="tag ' + r.action + '">' + (r.action === "block" ? "مسدود" : "هشدار") + "</span></div>").join("");
    $("gWhite").innerHTML = g.whitelist.length ? g.whitelist.map((w) => '<div class="r"><span class="grow code">' + esc(w) +
      '</span><button class="icon" data-gunwhite="' + esc(w) + '" title="حذف">×</button></div>').join("") : '<div class="empty-note">خالی است.</div>';
    guardTabCount(g);
  }

  // ---------------------------------------------------------------- log
  let logBuilt = false;
  function renderLogView() {
    const v = $("view-log");
    if (!logBuilt) {
      logBuilt = true;
      v.innerHTML = '<div class="cardbox"><h4>لاگ پلاگین</h4><div style="display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap">' +
        '<div class="chips" id="lChips"></div><button class="pillbtn" id="lCopy">کپی</button></div>' +
        '<div class="muted" id="lSummary" style="margin-top:6px"></div></div><div class="cardbox"><h4>خط‌ها</h4><div class="lines" id="lLines"></div></div>';
      $("lCopy").addEventListener("click", () => {
        toIde({ type: "copy", text: filteredLog().map((l) => l.time + " " + l.level + " [" + l.source + "] " + l.message).join("\n") });
        $("lCopy").textContent = "✓ کپی شد";
        setTimeout(() => ($("lCopy").textContent = "کپی"), 1200);
      });
    }
    renderLogBody();
  }
  function filteredLog() {
    return G.logFilter === "ALL" ? G.logLines : G.logLines.filter((l) => l.level === G.logFilter);
  }
  function renderLogBody() {
    if (!logBuilt) return;
    $("lChips").innerHTML = [["ALL", "همه"], ["WARN", "Warn"], ["INFO", "Info"], ["DEBUG", "Debug"]].map((f) =>
      '<button class="' + (G.logFilter === f[0] ? "on" : "") + '" data-lfilter="' + f[0] + '">' + f[1] + "</button>").join("") +
      '<button class="' + (G.logDebug ? "on" : "") + '" data-ldebug="1">' + (G.logDebug ? "● " : "○ ") + "ثبت Debug</button>";
    const ls = filteredLog();
    $("lSummary").textContent = ls.length + " از " + G.logLines.length + " خط · حداکثر ۲۰۰۰ خط نگه داشته می‌شود · debug " + (G.logDebug ? "روشن" : "خاموش");
    $("lLines").innerHTML = ls.slice(-500).map((l) => '<div class="ln ' + l.level + '"><span class="meta">' + esc(l.time) + ' </span><span class="lvl">' + l.level +
      '</span>\n<span class="meta">' + esc(l.source) + "</span>\n" + esc(l.message) + "</div>").join("") || '<div class="empty-note">خالی است.</div>';
  }

  // ---------------------------------------------------------------- vulnerabilities
  function renderVulns() {
    const v = $("view-vulns");
    const inv = G.vulnInv;
    const n = inv ? inv.count : "…";
    let html = '<div class="cardbox"><h4>آسیب‌پذیری وابستگی‌ها</h4>' +
      "<p>بررسی این پروژه در یک پایگاه دادهٔ آسیب‌پذیری یعنی فرستادن فهرست وابستگی‌هایش به یک سرویس خارجی. این کار هنوز انجام نشده و تا وقتی همین‌جا اجازه ندهید انجام نمی‌شود.</p>" +
      '<div class="kv"><span class="k">ارسال به</span><span class="v">OSV.dev (Open Source Security Foundation)</span>' +
      '<span class="k">Endpoint</span><span class="v">https://api.osv.dev/v1/querybatch</span><span class="k">تعداد اجزا</span><span class="v">' + n + "</span></div>" +
      "<h5>چه چیزی از این دستگاه خارج می‌شود</h5><ul>" +
      "<li>نام هر وابستگی که پروژه استفاده می‌کند — مستقیم و غیرمستقیم.</li><li>نسخهٔ دقیق هرکدام، همان‌طور که lockfile تعیین کرده.</li>" +
      "<li>اکوسیستم هرکدام: npm، PyPI، Pub، crates.io، Go یا Packagist.</li>" +
      "<li>هیچ چیز دیگر: نه محتوای فایل، نه مسیرهای پروژه، نه نام مخزن، نه حساب، نه هیچ شناسه‌ای.</li></ul>" +
      "<h5>یعنی چه</h5><ul><li>درخواست بدون احراز هویت است، اما از آدرس IP شما فرستاده می‌شود.</li>" +
      "<li>فهرست کامل وابستگی‌ها با نسخه‌های دقیق، تقریباً اثر انگشت یک کد خصوصی است.</li>" +
      "<li>به هر کسی که درخواست را ببیند نشان می‌دهد کدام نسخه‌های آسیب‌پذیر را دارید.</li>" +
      "<li>تا اینجا اجازه ندهید چیزی فرستاده نمی‌شود و هیچ اسکنی خودکار شروع نمی‌شود — نه هنگام باز شدن، نه build، نه زمان‌بندی.</li></ul>" +
      '<div class="btnrow" style="align-items:center"><button class="primary" id="vScan"' + (G.vulnBusy || !inv || !inv.count ? " disabled" : "") + ">" +
      (G.vulnBusy ? "در حال اسکن…" : "اجازه و اسکن همین حالا") + '</button><button class="icon" id="vList">نمایش فهرست دقیق ارسالی (' + n + ")</button></div>" +
      '<div class="muted">lockfileهای پشتیبانی‌شده: package-lock.json، pubspec.lock، requirements.txt (==)، poetry.lock، Cargo.lock، go.sum، composer.lock</div></div>';
    if (G.vulnShowList && inv) {
      html += '<div class="cardbox"><h4>فهرست دقیق</h4><div class="rows">' + inv.items.map((d) => '<div class="r"><span class="tag">' + esc(d.ecosystem) +
        '</span><span class="grow code">' + esc(d.name + "@" + d.version) + '</span><span class="sub mono">' + esc(d.file) + "</span></div>").join("") + "</div></div>";
    }
    const r = G.vulnRes;
    if (r) {
      if (r.error) html += '<div class="cardbox"><h4>نتیجه</h4><div class="notice error">اسکن ناموفق بود: ' + esc(r.error) + "</div></div>";
      else {
        html += '<div class="cardbox"><h4>نتیجه</h4><div class="muted">' + r.scanned + " جزء بررسی شد — " + r.vulnerable.length + " مورد آسیب‌پذیر</div>" +
          '<div class="rows">' + r.vulnerable.map((x) => '<div class="r"><span class="tag denied">' + esc(x.ecosystem) + '</span><span class="grow"><span class="code">' +
            esc(x.name + "@" + x.version) + '</span><span class="sub mono">' + esc(x.file) + "</span>" + (x.details || []).map((d) =>
            '<div class="sub" dir="auto"><a href="https://osv.dev/vulnerability/' + esc(d.id) + '">' + esc(d.id) + "</a>" + (d.severity ? " · " + esc(d.severity) : "") +
            " — " + esc(d.summary) + "</div>").join("") + (x.ids.length > 3 ? '<div class="sub">+' + (x.ids.length - 3) + " مورد دیگر</div>" : "") +
            "</span></div>").join("") + "</div></div>";
      }
    }
    v.innerHTML = html;
    $("vScan").addEventListener("click", () => {
      G.vulnBusy = true;
      G.vulnRes = null;
      toIde({ type: "vulnScan" });
      renderVulns();
    });
    $("vList").addEventListener("click", () => {
      G.vulnShowList = !G.vulnShowList;
      renderVulns();
    });
  }

  // ---------------------------------------------------------------- session
  const CTX_COLORS = ["#04403e", "#0b6b67", "#13908a", "#2fb3ab", "#66cdc6", "#a3e3dd", "#08504d", "#1c7d78", "#6cb6ff", "#7b7e84"];
  function renderSession() {
    const v = $("view-session");
    const c = chat();
    if (!c) return;
    const si = c.sessionInfo || {};
    const a = G.account || {};
    const plan = a.planTier || a.subscriptionType || "";
    const keys = Object.keys(G.limits);
    let html = '<div class="cardbox"><h4>محدودیت‌های پلن' + (plan ? " · " + esc(plan.toUpperCase()) : "") + "</h4>" +
      (keys.length ? keys.map((k) => {
        const l = G.limits[k];
        const p = l.pct || 0;
        return '<div class="meter"><div class="row1"><span>' + esc(limitLabel(k)) + '</span><span class="pct">' + p.toFixed(1) + "% used</span></div>" +
          '<div class="track"><div class="fill' + (p >= 95 ? " full" : p >= 75 ? " warn" : "") + '" style="width:' + p + '%"></div></div>' +
          '<div class="reset">بازنشانی تا ' + esc(fmtReset(l.resetsAt)) + "</div></div>";
      }).join("") : '<div class="empty-note">در حال خواندن با دستور /usage …</div>') +
      (G.usageInsights ? '<h5>چه چیزی مصرف را بالا می‌برد؟</h5><div class="preview" style="max-height:none;direction:ltr;text-align:left">' + esc(G.usageInsights) + "</div>" : "") +
      '<div class="btnrow" style="margin-top:6px"><button class="pillbtn" data-usage-refresh="1">↻ به‌روزرسانی مصرف</button></div></div>';
    const cx = si.context;
    html += '<div class="cardbox"><h4>Context</h4>';
    if (!cx) html += '<div class="empty-note">در حال محاسبه با دستور /context …</div>';
    else {
      const total = cx.total || 1;
      const cats = (cx.categories || []).filter((x) => x.name !== "Free space");
      html += '<div class="ltr" style="display:flex;justify-content:space-between"><span class="muted">Context</span><span class="mono">' +
        fmtInt(cx.used) + " / " + fmtInt(cx.total) + " · " + (cx.percent || 0) + "%</span></div>" +
        '<div class="stack">' + cats.map((x, i) => '<span title="' + esc(x.name) + '" style="width:' + (x.tokens / total) * 100 + "%;background:" +
          CTX_COLORS[i % CTX_COLORS.length] + '"></span>').join("") + "</div>" +
        '<div class="legend">' + (cx.categories || []).map((x) => '<span><i style="background:' +
          (x.name === "Free space" ? "var(--user-bg)" : CTX_COLORS[cats.indexOf(x) % CTX_COLORS.length]) + '"></i>' + esc(x.name) + "<b>" + fmtInt(x.tokens) + "</b></span>").join("") + "</div>";
    }
    html += "</div>";
    const kv = (k, val) => '<span class="k">' + k + '</span><span class="v" title="' + esc(val) + '">' + esc(val || "—") + "</span>";
    html += '<div class="cards2"><div class="cardbox"><h4>حساب</h4><div class="kv">' +
      (a.loggedIn === false ? kv("وضعیت", "وارد نشده") : kv("ایمیل", a.email) + kv("سازمان", a.orgName) + kv("پلن", plan ? "Claude " + cap(plan) : "") +
        kv("ارائه‌دهنده", a.apiProvider) + kv("روش ورود", a.authMethod) + kv("انقضای ورود", (a.loginExpiresAt || "").slice(0, 10))) +
      '</div><div class="btnrow" style="margin-top:8px"><button class="pillbtn" id="sLogout">خروج از حساب Claude</button></div></div>' +
      '<div class="cardbox"><h4>نشست</h4><div class="kv">' + kv("مدل", c.model || G.model || "default") + kv("پوشهٔ کاری", c.cwd || G.projectPath) +
      kv("نسخه", c.version || si.version) + kv("شناسهٔ نشست", c.sessionId || si.sessionId) +
      kv("حالت دسترسی", (MODES.find((m) => m[0] === G.permissionMode) || MODES[0])[1]) + kv("Effort", G.effort || "default") +
      '</div><div class="btnrow" style="margin-top:8px"><button class="pillbtn" id="sRefresh">↻ تازه‌سازی</button></div></div></div>';
    v.innerHTML = html;
    $("sLogout").addEventListener("click", () => toIde({ type: "logout" }));
    $("sRefresh").addEventListener("click", () => {
      c.sessionInfo = null;
      renderSession();
      toIde({ type: "session", chat: c.id });
    });
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
          setMode(p.permissionMode, false);
          setModel(p.model, false);
          setEffort(p.effort, false);
          if (p.shareSelection !== undefined) G.shareSelectionSetting = p.shareSelection;
          if (p.guardEnabled !== undefined) G.guardEnabled = p.guardEnabled;
          if (p.autoFollow !== undefined) G.autoFollow = p.autoFollow;
          setGuardUi();
          document.body.classList.toggle("light", !p.dark);
          renderInfo();
          renderCtx();
          break;
        }
        case "context":
          G.ctx = p && p.file ? p : null;
          renderCtx();
          break;
        case "account":
          G.account = p;
          renderInfo();
          if (G.view === "session") renderSession();
          break;
        case "claude":
          if (c) onClaude(c, p.event || {});
          break;
        case "stderr":
          if (c) {
            c.stderr.push(p.text);
            if (c.stderr.length > 30) c.stderr.shift();
          }
          break;
        case "error": {
          const cc = c || chat();
          notice(cc, cc.main, p.text, "error");
          cc.failed = true;
          setBusy(cc, false);
          break;
        }
        case "exit":
          if (!c) break;
          if (!p.gotResult && p.code !== 0) {
            const tail = c.stderr.slice(-8).join("\n");
            notice(c, c.main, (p.code === 143 || p.code === 137 || (p.code === 1 && !tail) ? "⎿  متوقف شد." : "claude با کد " + p.code + " خارج شد.") +
              (tail ? "\n\n" + tail : "") + (/login|auth|api key|401/i.test(tail) ? "\n\nیک بار در ترمینال دستور «claude» را اجرا و وارد حساب خود شوید." : ""),
              tail ? "error" : "warn");
            c.failed = !!tail;
          }
          Object.keys(c.perms).forEach((id) => resolvePermission(c, { id, outcome: "denied" }));
          Object.values(c.tools).forEach((t) => {
            if (t.el.classList.contains("running")) {
              t.el.classList.remove("running");
              t.el.classList.add("error");
            }
          });
          Object.values(c.agents).forEach((a) => {
            if (a.status === "running") {
              a.status = "error";
              a.ended = Date.now();
            }
          });
          c.main.turn = null;
          c.usage.outTok += Math.round(c.usage.turnOut + (c.usage.cur ? c.usage.cur.out : 0));
          c.usage.reasoning += Math.round(c.usage.turnReason + (c.usage.cur ? c.usage.cur.reason : 0));
          c.usage.turnOut = c.usage.turnReason = 0;
          c.usage.cur = null;
          if (c === chat()) renderInfo();
          setBusy(c, false);
          if (c === chat()) {
            renderSubTabs();
            inputEl.focus();
          }
          break;
        case "permission":
          if (c) showPermission(c, p);
          break;
        case "permissionResolved":
          if (c) resolvePermission(c, p);
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
          send(p.text);
          break;
        case "sessionInfo":
          if (c) {
            c.sessionInfo = p;
            if (p.version && !c.version) c.version = p.version;
            if (G.view === "session" && c === chat()) renderSession();
          }
          break;
        case "git":
          G.git = p;
          if (G.view === "git") renderGit();
          break;
        case "guard":
          G.guard = p;
          G.guardEnabled = p.enabled;
          setGuardUi();
          if (G.view === "guard") renderGuardBody();
          else guardTabCount(p);
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
        case "usage":
          (p.windows || []).forEach((w) => {
            const l = G.limits[w.label] || (G.limits[w.label] = {});
            l.pct = w.percent;
            const at = parseResetText(w.resets);
            if (at) l.resetsAt = at;
            l.resetText = w.resets;
          });
          if (p.insights) G.usageInsights = p.insights;
          renderInfo();
          if (G.view === "session") renderSession();
          break;
        case "skills":
          G.skills = p;
          if (G.view === "skills") renderSkills();
          break;
        case "rules":
          G.rules = p;
          if (G.view === "rules") renderRules();
          break;
        case "vulnInventory":
          G.vulnInv = p;
          if (G.view === "vulns") renderVulns();
          break;
        case "vulnResult":
          G.vulnBusy = false;
          G.vulnRes = p;
          if (G.view === "vulns") renderVulns();
          break;
      }
    },
  };

  // ==================================================================== user input
  function send(text) {
    text = (text || "").trim();
    const c = chat();
    if (!text || !c || c.busy) return;
    if (text === "/clear" || text === "/new") return clearChat(c);
    c.viewAgent = null;
    addUser(c.main, text);
    if (!c.title) c.title = text.replace(/\s+/g, " ").slice(0, 60);
    c.stderr = [];
    c.failed = false;
    setBusy(c, true, "در حال فکر کردن…");
    refreshChatUi();
    closeMention();
    closeSlash();
    toIde({ type: "send", chat: c.id, text, shareContext: c.shareCtx });
  }

  function autoGrow() {
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
    if (e.key === "Tab" && e.shiftKey) {
      e.preventDefault();
      const cycle = ["default", "acceptEdits", "plan", "bypassPermissions"];
      return setMode(cycle[(cycle.indexOf(G.permissionMode) + 1) % cycle.length], true);
    }
    const card = firstPendingCard();
    if (card && !inputEl.value.trim() && /^[123۱۲۳]$|^Escape$/.test(e.key)) return permKey(card, e);
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
    const card = e.target.closest && e.target.closest(".perm");
    if (card && !e.target.classList.contains("reason")) return permKey(card, e);
    if (card && e.target.classList.contains("reason") && (e.key === "Enter" || e.key === "Escape")) {
      e.preventDefault();
      return permAct(card, "deny");
    }
    const c = chat();
    if (e.key === "Escape" && !e.defaultPrevented && c && c.busy && !firstPendingCard()) toIde({ type: "stop", chat: c.id });
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
  $("btnGuard").addEventListener("click", () => switchView("guard"));
  const menuBtn = (id, fn) => $(id).addEventListener("click", (e) => {
    e.stopPropagation();
    if (menuAnchor === e.currentTarget) return closeMenu();
    fn(e.currentTarget);
  });
  menuBtn("btnMore", moreMenu);
  menuBtn("btnMore2", moreMenu);
  menuBtn("btnAttach", attachMenu);
  menuBtn("ddModel", modelMenu);
  menuBtn("ddMode", modeMenu);
  menuBtn("ddEffort", effortMenu);
  $("searchNext").addEventListener("click", () => findNext(false));
  $("searchPrev").addEventListener("click", () => findNext(true));
  $("searchClose").addEventListener("click", () => $("searchBar").classList.add("hidden"));
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

  function showAgent(c, aid) {
    c.viewAgent = aid || null;
    switchView("chat");
    refreshChatUi();
    const T = c.viewAgent ? c.agents[c.viewAgent].T : c.main;
    if (c.viewAgent && !T.el.children.length) T.el.innerHTML = '<div class="notice">هنوز پیامی از این زیرعامل نیامده.</div>';
    scrollDown(T, true);
  }

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
    const sub = t.closest("#subTabs .pill");
    if (sub) return showAgent(chat(), sub.dataset.agent);
    const openAgent = t.closest("[data-open-agent]");
    if (openAgent) return showAgent(chat(), openAgent.dataset.openAgent);
    const permBtn = t.closest("[data-perm]");
    if (permBtn) return permAct(permBtn.closest(".perm"), permBtn.getAttribute("data-perm"));
    if (t.getAttribute("data-open")) return toIde({ type: "openFile", path: t.getAttribute("data-open"), line: 0 });
    if (t.classList.contains("more")) {
      const full = t.nextElementSibling;
      if (full) full.classList.remove("hidden");
      return t.remove();
    }
    if (t.classList.contains("sugg")) return send(t.textContent);
    if (t.closest("[data-usage-refresh]")) return requestUsage(true);
    const fam = t.closest("[data-model-family]");
    if (fam) {
      const f = fam.dataset.modelFamily;
      G.modelOpen[f] = !G.modelOpen[f];
      return modelMenu(menuAnchorFor("ddModel"));
    }
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
    if (t.closest("[data-run-init]")) return sendFromView("/init");
    const cp = t.closest("[data-create-path]");
    if (cp) return toIde({ type: "createFile", path: cp.dataset.createPath, content: "# CLAUDE.md\n\n" });
    const ga = t.closest("[data-git-ask]");
    if (ga) return gitAsk(ga.dataset.gitAsk);
    const ia = t.closest("[data-ide-action]");
    if (ia) return toIde({ type: "ideAction", id: ia.dataset.ideAction });
    if (t.closest("[data-git-refresh]")) {
      G.git = null;
      renderGit();
      return toIde({ type: "git" });
    }
    const gt = t.closest("[data-gtab]");
    if (gt) {
      G.guardTab = gt.dataset.gtab;
      return renderGuardBody();
    }
    const gw = t.closest("[data-gwhite]");
    if (gw) return toIde({ type: "guardWhitelist", target: gw.dataset.gwhite });
    const gu = t.closest("[data-gunwhite]");
    if (gu) return toIde({ type: "guardUnwhitelist", target: gu.dataset.gunwhite });
    if (t.dataset.grule) return toIde({ type: "guardRule", id: t.dataset.grule, enabled: t.checked });
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
  setInterval(renderInfo, 60000); // keep "resets in" fresh
  setInterval(() => requestUsage(true), 5 * 60000);

  newChat();
  inputEl.focus();
})();
