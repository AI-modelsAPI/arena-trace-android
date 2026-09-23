/*
 * Arena auto-probe — page-side discrete DOM actions.
 *
 * Ported from the extension's auto-draw.js + conversation-rename.js, but WITHOUT
 * the orchestration loop and WITHOUT chrome.runtime: the native ProbeController
 * drives the loop and gets model names from the existing snoop -> TraceClient
 * pipeline. This layer only performs one safe DOM step per call and reports the
 * result back through ArenaProbeBridge.onResult(reqId, jsonResult).
 *
 * Every safety guard from the extension is preserved:
 *   - never overwrite a human draft (noDraft)
 *   - only clear our own arithmetic probe prompt
 *   - confirm Agent Mode before sending
 *   - rename/archive strictly through Arena's own menus, no private endpoints
 */
(() => {
  const ARENA = 'https://arena.ai';
  const NEW_CHAT_LABELS = ['New Chat', 'New chat', '新建聊天', '新对话', '新建对话'];

  const visible = e => !!e?.isConnected && e.getClientRects().length > 0;
  const session = () => location.pathname.match(/^\/agent\/([a-zA-Z0-9-]{1,128})\/?$/)?.[1] || null;
  const agentPath = () => location.pathname.replace(/\/$/, '') === '/agent';
  const clean = t => String(t ?? '').replace(/[\u200b\u200c\u200d\ufeff]/g, '').trim();
  const text = e => (e?.textContent || '').trim();
  const exact = (e, words) => words.includes(text(e));

  // Our own probe prompts are bare arithmetic ("N op N ="). A human names/writes
  // with words, so this never matches real user content.
  const isOwnPrompt = t => /^\s*\d{1,4}\s*[+\-*/×÷]\s*\d{1,4}\s*=\s*$/.test(String(t || ''));

  const sessionFromPath = path => path.match(/^\/agent\/([a-zA-Z0-9-]{1,128})\/?$/)?.[1] || null;
  const labelOf = e => ((e?.getAttribute?.('aria-label') || e?.placeholder || '') + ' ' + (e?.textContent || '')).trim();
  const isSearch = e => /search|搜索|查找/i.test(labelOf(e)) || e?.closest?.('[data-sidebar]');

  function editors() {
    const nodes = [];
    for (const sel of ['[contenteditable="true"]', 'textarea', '[role="textbox"]']) nodes.push(...document.querySelectorAll(sel));
    return [...new Set(nodes)].filter(e => visible(e) && !isSearch(e));
  }
  function placeholderText(e) {
    const own = e.getAttribute?.('data-placeholder') || e.getAttribute?.('aria-placeholder') || '';
    if (own) return String(own).trim();
    const inner = e.querySelector?.('[data-placeholder]');
    return String(inner?.getAttribute?.('data-placeholder') || '').trim();
  }
  function composerScore(e) {
    let s = 0;
    if (/message|prompt|发送|消息|提问|ask|chat/i.test(labelOf(e))) s += 4;
    if (e.closest?.('form')) s += 2;
    if (placeholderText(e)) s += 1;
    return s;
  }
  function composer() {
    const all = editors();
    if (!all.length) return null;
    let best = all.at(-1), score = composerScore(best);
    for (const e of all) { const s = composerScore(e); if (s > score) { best = e; score = s; } }
    return best;
  }
  function editorText(e) {
    if (!e) return '';
    const tag = (e.tagName || '').toUpperCase();
    if (tag === 'TEXTAREA' || tag === 'INPUT') return clean(e.value);
    if (/\bis-(?:editor-)?empty\b/.test(String(e.className || ''))) return '';
    const t = clean(e.innerText ?? e.textContent);
    const ph = placeholderText(e);
    if (t && ph && t === ph) return '';
    if (t && e.childElementCount === 1 && /placeholder|hint/i.test(String(e.firstElementChild?.className || ''))) return '';
    return t;
  }
  function noDraft(allowPrompt = false) {
    const all = editors();
    const main = composer();
    const suspects = main ? [main] : all;
    const offender = suspects.find(e => { const t = editorText(e); return t && !(allowPrompt && isOwnPrompt(t)); });
    if (offender) {
      const dump = all.map(e => `${String(e.tagName || '?').toLowerCase()}${e.id ? '#' + e.id : ''}:"${editorText(e).slice(0, 10)}…"`).join(' ');
      throw Error(`输入框有未发送内容（"${editorText(offender).slice(0, 24)}" · 共${all.length}个输入区 ${dump}），已停止；不会覆盖草稿`);
    }
  }
  function expandSidebar() {
    const buttons = [...document.querySelectorAll('button[aria-label]')].filter(b => visible(b) && !b.disabled);
    const opener = buttons.find(b => ['Open sidebar', '展开侧栏', '打开侧边栏', '展开侧边栏'].includes(b.getAttribute('aria-label')))
      || buttons.find(b => ['Toggle Sidebar', 'Toggle sidebar', '切换侧栏'].includes(b.getAttribute('aria-label')) && b.closest?.('[data-state="collapsed"]'));
    opener?.click();
  }
  function collapseSidebar() {
    const buttons = [...document.querySelectorAll('button[aria-label]')].filter(b => visible(b) && !b.disabled);
    const closer = buttons.find(b => ['Close sidebar', '收起侧栏', '关闭侧边栏', '收起侧边栏'].includes(b.getAttribute('aria-label')))
      || buttons.find(b => ['Toggle Sidebar', 'Toggle sidebar', '切换侧栏'].includes(b.getAttribute('aria-label')));
    closer?.click();
    return { closed: true };
  }
  function newChatControl() {
    const agentLink = a => { try { const u = new URL(a.href, location.origin); return u.origin === ARENA && u.pathname.replace(/\/$/, '') === '/agent'; } catch { return false; } };
    const links = [...document.querySelectorAll('a[href]')].filter(agentLink);
    const named = links.filter(a => NEW_CHAT_LABELS.includes((a.textContent || '').trim()) || NEW_CHAT_LABELS.includes((a.getAttribute?.('aria-label') || '').trim()));
    if (named.length) return named.find(visible) || named[0];
    const buttons = [...document.querySelectorAll('button,[role="button"]')];
    const labeled = buttons.filter(e => NEW_CHAT_LABELS.includes((e.textContent || '').trim()) || NEW_CHAT_LABELS.includes((e.getAttribute?.('aria-label') || '').trim()));
    return labeled.find(visible) || labeled[0] || null;
  }

  const isAgentLabel = txt => { const t = String(txt || '').trim(); return /^Agent(\s+Mode)?\b/i.test(t) || /agent\s*mode/i.test(t); };

  function fillPrompt(editor, value) {
    editor.focus();
    if (editorText(editor) === value) return true;
    const tag = (editor.tagName || '').toUpperCase();
    if (tag === 'TEXTAREA' || tag === 'INPUT') {
      const proto = tag === 'TEXTAREA' ? globalThis.HTMLTextAreaElement?.prototype : globalThis.HTMLInputElement?.prototype;
      const setter = proto && Object.getOwnPropertyDescriptor(proto, 'value')?.set;
      if (setter) setter.call(editor, value); else editor.value = value;
      editor.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
      editor.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
    } else {
      const selection = window.getSelection(), range = document.createRange();
      range.selectNodeContents(editor); selection.removeAllRanges(); selection.addRange(range);
      if (!document.execCommand('insertText', false, value)) {
        editor.textContent = value;
        editor.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
      }
    }
    return editorText(editor) === value;
  }
  function clearOwnDraft(editor) {
    if (!editor || !isOwnPrompt(editorText(editor))) return;
    editor.focus();
    const tag = (editor.tagName || '').toUpperCase();
    if (tag === 'TEXTAREA' || tag === 'INPUT') {
      const proto = tag === 'TEXTAREA' ? globalThis.HTMLTextAreaElement?.prototype : globalThis.HTMLInputElement?.prototype;
      const setter = proto && Object.getOwnPropertyDescriptor(proto, 'value')?.set;
      if (setter) setter.call(editor, ''); else editor.value = '';
      editor.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
    } else {
      const selection = window.getSelection(), range = document.createRange();
      range.selectNodeContents(editor); selection.removeAllRanges(); selection.addRange(range);
      document.execCommand('delete', false);
    }
  }
  function findSend(editor) {
    const ok = b => visible(b) && !b.disabled;
    const exactBtn = [...document.querySelectorAll('button[aria-label="Send message"]')].find(ok);
    if (exactBtn) return exactBtn;
    const buttons = [...document.querySelectorAll('button')].filter(ok);
    const named = buttons.find(b => /^(send( message)?|submit|发送(消息)?)$/i.test((b.getAttribute?.('aria-label') || '').trim()) || /^(send|发送)$/i.test((b.textContent || '').trim()));
    if (named) return named;
    const form = editor?.closest?.('form');
    if (form) { const submit = [...form.querySelectorAll('button[type="submit"], button')].find(b => ok(b) && b.getAttribute('type') !== 'reset'); if (submit) return submit; }
    const host = editor?.parentElement;
    if (host) { const local = [...host.querySelectorAll('button')].filter(ok); if (local.length === 1) return local[0]; }
    return null;
  }
  // "Generating" only counts when a Stop button is actually VISIBLE. Arena can
  // leave a hidden Stop button in the DOM after a reply finishes; matching on mere
  // presence made quick-send wrongly report "当前回复仍在生成". Also match zh labels.
  const isGenerating = () => [...document.querySelectorAll('button[aria-label]')]
    .some(b => visible(b) && /^(stop generating|stop|停止生成|停止回复|停止)$/i.test((b.getAttribute('aria-label') || '').trim()));

  const waitFor = (check, message, ms = 15000) => new Promise((resolve, reject) => {
    const end = Date.now() + ms;
    const tick = () => {
      let v; try { v = check(); } catch (e) { return reject(e); }
      if (v) return resolve(v);
      if (Date.now() >= end) return reject(Error(message));
      setTimeout(tick, 200);
    };
    tick();
  });

  // ---- discrete actions invoked by the native controller ----

  function precheck() {
    return {
      onArena: location.origin === ARENA,
      session: session(),
      agentPath: agentPath(),
      hasComposer: !!composer(),
      isGenerating: isGenerating(),
      renameBusy: !!(globalThis.ArenaConversationRename?.isBusy?.()),
      dialogOpen: [...document.querySelectorAll('[role="dialog"],[role="alertdialog"]')].some(visible),
    };
  }

  async function newChat() {
    if (location.origin !== ARENA) throw Error('已离开 Arena');
    noDraft(true);
    if (isGenerating()) throw Error('当前回复仍在生成，已停止');
    // Already on a fresh /agent composer with no session → nothing to do.
    if (!session() && agentPath() && composer()) { noDraft(true); return { session: null }; }
    // Prefer the New Chat control without opening the sidebar; only expand if it
    // isn't reachable, so probe rounds don't keep toggling the sidebar.
    let control = newChatControl();
    if (!control) { expandSidebar(); control = await waitFor(() => newChatControl(), '未找到 New Chat 入口，已停止'); }
    control.click();
    await waitFor(() => !session() && agentPath(), '新建聊天超时');
    await waitFor(() => composer(), '等待新聊天输入框超时');
    noDraft(true);
    return { session: null };
  }

  async function ensureAgentMode() {
    const combo = await waitFor(() => [...document.querySelectorAll('button[role="combobox"]')].find(visible), '未找到模式选择器');
    if (!isAgentLabel(combo.textContent)) {
      combo.click();
      const option = await waitFor(() => [...document.querySelectorAll('[role="option"]')].find(e => visible(e) && /agent\s*mode/i.test(e.textContent.trim()) && !e.hasAttribute('data-disabled') && e.getAttribute('aria-disabled') !== 'true'), '未找到 Agent Mode 选项');
      if (option.getAttribute('aria-selected') === 'true') combo.click(); else option.click();
    }
    await waitFor(() => [...document.querySelectorAll('button[role="combobox"]')].some(e => visible(e) && isAgentLabel(e.textContent)), '未能确认 Agent Mode');
    // Arena sometimes restores the just-sent prompt as the new draft; clear only ours.
    clearOwnDraft(composer());
    return { ok: true };
  }

  // Fill our probe prompt and send it. Returns the new session id after navigation.
  async function send(args) {
    const prompt = String(args?.prompt || '');
    if (!isOwnPrompt(prompt)) throw Error('探针只发送算式提示');
    if (location.origin !== ARENA || !agentPath()) throw Error('页面已变化，未发送');
    if (session()) throw Error('新聊天状态已变化，未发送');
    noDraft(true);
    const editor = composer();
    if (!editor) throw Error('输入框不可用');
    if (!fillPrompt(editor, prompt)) throw Error('输入消息失败；未发送');
    const button = await waitFor(() => findSend(editor), '发送按钮不可用；未发送');
    if (editorText(editor) !== prompt || session()) throw Error('输入或页面已变化；未发送');
    if (![...document.querySelectorAll('button[role="combobox"]')].some(b => visible(b) && isAgentLabel(b.textContent))) throw Error('模式已变化；未发送');
    button.click();
    const id = await waitFor(() => session(), '发送后未确认新会话；不重发', 30000);
    return { session: id };
  }

  // Fill the CURRENT conversation's composer with arbitrary text and send it.
  // Unlike send() this is NOT restricted to arithmetic and does NOT require a
  // fresh /agent — it targets whatever conversation is open. Triggered by an
  // explicit user long-press, so it may replace an existing draft.
  async function sendToCurrent(args) {
    const value = String(args?.text || '');
    if (!value.trim()) throw Error('发送内容为空');
    if (value.length > 8000) throw Error('内容过长（上限 8000 字）');
    if (location.origin !== ARENA) throw Error('已离开 Arena');
    if (isGenerating()) throw Error('当前回复仍在生成，已停止');
    const editor = composer();
    if (!editor) throw Error('未找到输入框');
    if (!fillPrompt(editor, value)) throw Error('输入内容失败；未发送');
    const button = await waitFor(() => findSend(editor), '发送按钮不可用；未发送');
    if (editorText(editor) !== value) throw Error('输入已变化；未发送');
    button.click();
    return { sent: true };
  }

  // Sidebar snapshot for title-based cleanup: [{sessionId, title}].
  // The list is virtualized (only visible links exist in the DOM), so scroll it
  // to the bottom until the count stops growing before snapshotting.
  function sidebarScroller() {
    if (typeof getComputedStyle !== 'function') return null;
    for (const a of document.querySelectorAll('a[data-sidebar="menu-button"][href]')) {
      let p = a.parentElement;
      while (p) {
        const s = getComputedStyle(p);
        if (/(auto|scroll)/.test(s.overflowY) && p.scrollHeight > p.clientHeight + 40) return p;
        p = p.parentElement;
      }
    }
    return null;
  }
  function collectSidebar() {
    const seen = new Set(), out = [];
    for (const a of document.querySelectorAll('a[data-sidebar="menu-button"][href]')) {
      let id = null;
      try { const u = new URL(a.href, location.origin); if (u.origin === ARENA) id = sessionFromPath(u.pathname); } catch { id = null; }
      if (!id || seen.has(id)) continue;
      seen.add(id);
      out.push({ sessionId: id, title: text(a).slice(0, 300) });
    }
    return out;
  }
  async function loadAllSidebar() {
    const scroller = sidebarScroller();
    if (!scroller) return;
    let last = -1, stable = 0;
    for (let i = 0; i < 30 && stable < 2; i++) {
      scroller.scrollTop = scroller.scrollHeight;
      await new Promise(r => setTimeout(r, 300));
      const n = collectSidebar().length;
      if (n === last) stable++; else { stable = 0; last = n; }
    }
  }
  async function sidebarList(args) {
    // expand defaults true; cleanup opens the sidebar ONCE up front and passes
    // expand:false on subsequent scans so we don't toggle it every pass.
    if (args?.expand !== false) expandSidebar();
    if (!collectSidebar().length) await waitFor(() => collectSidebar().length > 0, '侧栏对话列表未加载', 6000).catch(() => {});
    await loadAllSidebar();
    return { items: collectSidebar() };
  }

  // Navigate to a saved conversation by clicking its sidebar link (SPA nav keeps
  // this injected script alive). Required before archive, whose guard demands
  // being on that conversation's own page.
  function sidebarLink(sessionId) {
    return [...document.querySelectorAll('a[data-sidebar="menu-button"][href]')].find(a => {
      try { const u = new URL(a.href, location.origin); return u.origin === ARENA && sessionFromPath(u.pathname) === sessionId; } catch { return false; }
    }) || null;
  }
  async function openConversation(args) {
    const sessionId = String(args?.sessionId || '');
    if (!/^[a-zA-Z0-9-]{1,128}$/.test(sessionId)) throw Error('会话 id 无效');
    if (session() === sessionId) return { session: sessionId };
    // On a phone the sidebar Sheet auto-closes after a selection, so between
    // archives the target link may not be in the DOM. Reopen and wait for it,
    // retrying a few times (scroll the virtualized list) before giving up.
    let link = null;
    for (let attempt = 0; attempt < 3 && !link; attempt++) {
      expandSidebar();
      link = await waitFor(() => sidebarLink(sessionId), '', 2500).catch(() => null);
      if (!link) { await loadAllSidebar(); link = sidebarLink(sessionId); }
    }
    if (!link) throw Error('侧栏未找到该对话');
    link.click();
    await waitFor(() => session() === sessionId, '切换到该对话超时', 8000);
    return { session: sessionId };
  }

  async function rename(args) {
    const api = globalThis.ArenaConversationRename;
    if (!api) throw Error('重命名模块未加载');
    const r = await api.rename({ sessionId: String(args?.sessionId || ''), model: String(args?.title || ''), isCurrent: () => true });
    return r || { ok: true };
  }
  async function archive(args) {
    const api = globalThis.ArenaConversationRename;
    if (!api) throw Error('归档模块未加载');
    // Cleanup archives from the sidebar ⋯ menu without opening the chat, so it
    // must not require being on the chat's own URL, and must not toggle the
    // sidebar (the sweep opens/closes it exactly once around the whole loop).
    const requireCurrentUrl = args?.requireCurrentUrl !== false;
    const manageSidebar = args?.manageSidebar !== false;
    const r = await api.archive({ sessionId: String(args?.sessionId || ''), isCurrent: () => true, requireCurrentUrl, manageSidebar });
    return r || { archived: true };
  }

  const ACTIONS = { precheck, newChat, ensureAgentMode, send, sendToCurrent, sidebarList, collapseSidebar, openConversation, rename, archive };

  async function call(action, argsJson, reqId) {
    let res;
    try {
      const args = argsJson ? JSON.parse(argsJson) : {};
      const fn = ACTIONS[action];
      if (!fn) throw Error('unknown action: ' + action);
      const data = await fn(args);
      res = { ok: true, data: data ?? {} };
    } catch (e) {
      res = { ok: false, error: String(e && e.message || e) };
    }
    try { ArenaProbeBridge.onResult(reqId, JSON.stringify(res)); } catch (_) { }
  }

  globalThis.ArenaProbe = { call, isOwnPrompt };
})();
