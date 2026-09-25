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
  // Re-injection guard: onPageFinished can fire more than once per document.
  const VERSION = 4; // /c/{id} conversation links are first-class (cleanup was blind to them)
  if ((globalThis.ArenaProbe?.version || 0) >= VERSION) return;
  const ARENA = 'https://arena.ai';
  const NEW_CHAT_LABELS = ['New Chat', 'New chat', '新建聊天', '新对话', '新建对话'];

  const visible = e => !!e?.isConnected && e.getClientRects().length > 0;
  const session = () => location.pathname.match(/^\/(?:agent|c)\/([a-zA-Z0-9-]{1,128})\/?$/)?.[1] || null;
  const agentPath = () => location.pathname.replace(/\/$/, '') === '/agent';
  const clean = t => String(t ?? '').replace(/\p{Cf}/gu, '').trim();
  const text = e => (e?.textContent || '').trim();
  const exact = (e, words) => words.includes(text(e));

  // Our own probe prompts are bare arithmetic ("N op N ="). A human names/writes
  // with words, so this never matches real user content.
  const isOwnPrompt = t => /^\s*\d{1,4}\s*[+\-*/×÷]\s*\d{1,4}\s*=\s*$/.test(String(t || ''));

  const sessionFromPath = path => path.match(/^\/(?:agent|c)\/([a-zA-Z0-9-]{1,128})\/?$/)?.[1] || null;
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
    if (!control) { await ensureSidebarOpen(); control = await waitFor(() => newChatControl(), '未找到 New Chat 入口，已停止'); }
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
  // Any conversation page: /agent/{id} or /c/{id} (the page id need not equal
  // the stream session id — the native side resolves aliases).
  const pathSession = () => location.pathname.match(/^\/(?:agent|c)\/([a-zA-Z0-9-]{1,128})\/?$/)?.[1] || null;
  // 会话探针: probe INSIDE the open conversation — send one arithmetic prompt
  // into it so the snoop/trace pipeline re-detects the routed model. Unlike
  // send()/sendToCurrent this never creates extra state and never renames;
  // on the blank new-chat page it falls back to send() (there is no
  // conversation to probe yet).
  async function probeInSession(args) {
    const prompt = String(args?.prompt || '');
    if (!isOwnPrompt(prompt)) throw Error('探针只发送算式提示');
    if (location.origin !== ARENA) throw Error('已离开 Arena');
    if (agentPath() && !session()) return send(args); // blank chat: create first
    if (isGenerating()) throw Error('当前回复仍在生成，已停止');
    const editor = composer();
    if (!editor) throw Error('未找到输入框');
    if (!fillPrompt(editor, prompt)) throw Error('输入内容失败；未发送');
    const button = await waitFor(() => findSend(editor), '发送按钮不可用；未发送');
    if (editorText(editor) !== prompt) throw Error('输入已变化；未发送');
    const before = pathSession();
    button.click();
    let id = before;
    try { id = (await waitFor(() => pathSession(), '发送后未确认会话 id', 30000)) || before || id; }
    catch (_) { /* keep the pre-send id: the send already happened */ }
    return { session: id || '' };
  }

  // ---- sidebar: open/close, full scan, reveal, archive ----
  //
  // The sidebar list is virtualized AND lazy-loaded: only rows near the viewport
  // are mounted, and older chats load when the list is scrolled to the bottom.
  // The old sweep scrolled to the bottom first and snapshotted only what was
  // mounted there, so the NEWEST rows (top of the list, usually the probe's own
  // residue) were never collected. sidebarScan walks the list top → bottom and
  // unions every row it sees, remembering the scroll offset where each row was
  // found so the archiver can jump straight back to it.

  const SIDEBAR_LINK = 'a[data-sidebar="menu-button"][href]';
  const OPEN_LABELS = ['Open sidebar', '展开侧栏', '打开侧边栏', '展开侧边栏'];
  const CLOSE_LABELS = ['Close sidebar', '收起侧栏', '关闭侧边栏', '收起侧边栏'];
  const TOGGLE_LABELS = ['Toggle Sidebar', 'Toggle sidebar', '切换侧栏'];
  const SHOW_MORE = /^(show more|load more|see more|view more|show all|显示更多|加载更多|查看更多|展开更多|显示全部)$/i;
  const LOADING = '[aria-busy="true"], [role="progressbar"], .animate-spin';
  const HREF_SESSION = /^(?:https:\/\/arena\.ai)?\/(?:agent|c)\/([a-zA-Z0-9-]{1,128})\/?(?:[?#].*)?$/;
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  // Same canonical form as ProbeLogic.normalizeTitle (Kotlin) — keep in sync.
  const normTitle = t => String(t ?? '').normalize('NFKC').replace(/\p{Cf}/gu, '')
    .replace(/[\u2010-\u2015\u2212\u2796\uFE58\uFE63\uFF0D]/g, '-')
    .replace(/[\u00D7\u2715\u2716\u2A09\u2A2F\u2217\u22C5\u2219\u00B7]/g, '×')
    .replace(/[\u00F7\u2215\u2044\u2797]/g, '÷').replace(/\u2795/g, '+')
    .replace(/\s+/g, ' ').trim();
  const isArithmeticTitle = t => /^\d{1,4}\s*[+\-*/×÷]\s*\d{1,4}\s*=$/.test(normTitle(t));

  function linkSession(a) {
    const href = a.getAttribute?.('href') || '';
    const m = href.match(HREF_SESSION);
    if (m) return m[1];
    try { const u = new URL(a.href, location.origin); return u.origin === ARENA ? sessionFromPath(u.pathname) : null; } catch { return null; }
  }
  const sidebarLinks = () => [...document.querySelectorAll(SIDEBAR_LINK)].filter(a => linkSession(a));
  const shown = a => visible(a) && !a.closest?.('[data-state="collapsed"][data-collapsible]');
  const sidebarIsOpen = () => sidebarLinks().some(shown);
  function sidebarLink(sessionId) {
    const all = sidebarLinks().filter(a => linkSession(a) === sessionId);
    return all.find(shown) || null;
  }
  const labelButtons = () => [...document.querySelectorAll('button[aria-label]')].filter(b => visible(b) && !b.disabled);
  const aria = b => (b.getAttribute('aria-label') || '').trim();

  // Set when the probe itself opened the sidebar, so it only closes what it opened
  // (on a tablet/desktop layout the sidebar may be open by the user's choice).
  let openedByProbe = false;

  function expandSidebar() {
    const buttons = labelButtons();
    const opener = buttons.find(b => OPEN_LABELS.includes(aria(b)))
      || buttons.find(b => TOGGLE_LABELS.includes(aria(b)) && b.closest?.('[data-state="collapsed"]'))
      || (!sidebarIsOpen() ? buttons.find(b => TOGGLE_LABELS.includes(aria(b))) : null);
    if (!opener) return false;
    opener.click();
    return true;
  }
  async function ensureSidebarOpen() {
    if (sidebarIsOpen()) return { open: true, opened: false };
    if (!expandSidebar()) {
      const ok = await waitFor(() => sidebarIsOpen(), '', 1200).catch(() => false);
      return { open: !!ok, opened: false };
    }
    const ok = await waitFor(() => sidebarIsOpen(), '', 4000).catch(() => false);
    if (ok) openedByProbe = true;
    return { open: !!ok, opened: !!ok };
  }
  // Close the sidebar — only when it is actually open. The old version clicked
  // the toggle unconditionally, which OPENED a closed sidebar. With
  // onlyIfOpenedByProbe it leaves a sidebar the user opened alone.
  function collapseSidebar(args) {
    if (args?.onlyIfOpenedByProbe && !openedByProbe) return { closed: false, reason: 'not-ours' };
    openedByProbe = false;
    if (!sidebarIsOpen()) return { closed: false, reason: 'already-closed' };
    const buttons = labelButtons();
    const closer = buttons.find(b => CLOSE_LABELS.includes(aria(b)))
      || buttons.find(b => TOGGLE_LABELS.includes(aria(b)) && !b.closest?.('[data-state="collapsed"]'));
    if (closer) { closer.click(); return { closed: true }; }
    // Phone sheet without a labelled close button: Escape dismisses Radix sheets.
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }));
    return { closed: true, via: 'escape' };
  }
  function sidebarScroller() {
    if (typeof getComputedStyle !== 'function') return null;
    for (const a of sidebarLinks()) {
      let p = a.parentElement;
      while (p && p !== document.body) {
        const s = getComputedStyle(p);
        if (/(auto|scroll)/.test(s.overflowY) && p.scrollHeight > p.clientHeight + 4) return p;
        p = p.parentElement;
      }
    }
    return null;
  }
  const atBottom = s => s.scrollTop + s.clientHeight >= s.scrollHeight - 4;
  function clickShowMore(scope) {
    const root = scope || document;
    const btn = [...root.querySelectorAll('button, [role="button"]')].find(b =>
      visible(b) && !b.disabled && !b.hasAttribute('aria-haspopup') && SHOW_MORE.test(text(b)));
    if (!btn) return false;
    btn.click();
    return true;
  }
  function sidebarState() {
    return { open: sidebarIsOpen(), openedByProbe, current: session(), rows: sidebarLinks().length };
  }

  /**
   * Full sidebar inventory: [{sessionId, title, pos}] where pos is the scroll
   * offset at which the row was seen. `complete` is false when the scan hit its
   * step budget before the list stopped growing.
   */
  async function sidebarScan(args) {
    const opened = await ensureSidebarOpen();
    if (!opened.open) throw Error('无法打开侧栏');
    if (!sidebarLinks().length) await waitFor(() => sidebarLinks().length > 0, '', 6000).catch(() => {});
    const seen = new Map();
    const scroller = sidebarScroller();
    const collect = () => {
      const pos = scroller ? Math.round(scroller.scrollTop) : 0;
      for (const a of sidebarLinks()) {
        const id = linkSession(a);
        const title = normTitle(text(a)).slice(0, 300);
        const prev = seen.get(id);
        if (!prev) seen.set(id, { sessionId: id, title, pos });
        else if (title && prev.title !== title) prev.title = title;
      }
    };
    const result = complete => ({ items: [...seen.values()], current: session(), complete, openedSidebar: opened.opened });
    if (!scroller) { collect(); return result(true); }

    const maxSteps = Math.max(20, Math.min(2000, Number(args?.maxSteps) || 800));
    const step = () => Math.max(60, Math.floor(scroller.clientHeight * 0.75));
    scroller.scrollTop = 0;
    await sleep(250);
    collect();
    let quiet = 0, lastHeight = -1, lastCount = -1;
    for (let i = 0; i < maxSteps; i++) {
      if (!atBottom(scroller)) {
        scroller.scrollTop = Math.min(scroller.scrollTop + step(), scroller.scrollHeight);
        await sleep(140);
        collect();
        continue;
      }
      // At the bottom: give lazy loading (or a "show more" button) time to add rows.
      const clicked = clickShowMore(scroller);
      await sleep(clicked ? 700 : 400);
      collect();
      const height = scroller.scrollHeight, count = seen.size;
      if (height === lastHeight && count === lastCount && !scroller.querySelector(LOADING)) {
        if (++quiet >= 4) return result(true);
      } else quiet = 0;
      lastHeight = height; lastCount = count;
    }
    return result(false);
  }

  /**
   * Scroll the virtualized sidebar until sessionId's row is MOUNTED and visible.
   * Tries the remembered scan offset (and nearby offsets — rows shift up as
   * earlier ones are archived) before falling back to full passes.
   */
  async function revealSidebarItem(args) {
    const sessionId = String(args?.sessionId || '');
    if (!/^[a-zA-Z0-9-]{1,128}$/.test(sessionId)) throw Error('会话 id 无效');
    const done = a => { a.scrollIntoView?.({ block: 'center', inline: 'nearest', behavior: 'instant' }); return a; };
    let a = sidebarLink(sessionId);
    if (a) return done(a);
    const opened = await ensureSidebarOpen();
    if (!opened.open) throw Error('无法打开侧栏');
    a = await waitFor(() => sidebarLink(sessionId), '', 600).catch(() => null);
    if (a) return done(a);
    const scroller = sidebarScroller();
    if (!scroller) throw Error('侧栏未找到该对话');
    const h = Math.max(60, scroller.clientHeight);
    const pos = Number(args?.pos);
    if (Number.isFinite(pos) && pos >= 0) {
      for (const f of [0, -0.5, -1, -1.5, -2, -3, 0.5, 1]) {
        scroller.scrollTop = Math.max(0, pos + f * h);
        await sleep(140);
        if ((a = sidebarLink(sessionId))) return done(a);
      }
    }
    for (const down of [true, false]) {
      scroller.scrollTop = down ? 0 : scroller.scrollHeight;
      for (let i = 0; i < 600; i++) {
        await sleep(120);
        if ((a = sidebarLink(sessionId))) return done(a);
        if (down ? atBottom(scroller) : scroller.scrollTop <= 0) break;
        scroller.scrollTop += (down ? 1 : -1) * Math.floor(h * 0.75);
      }
    }
    throw Error('侧栏未找到该对话');
  }

  // Reveal + archive one arithmetic chat from its sidebar row, without opening it.
  // Re-checks the title right before archiving: a chat whose title is no longer
  // bare arithmetic is never archived (defence in depth for the Kotlin planner).
  async function archiveFromSidebar(args) {
    const sessionId = String(args?.sessionId || '');
    const api = globalThis.ArenaConversationRename;
    if (!api) throw Error('归档模块未加载');
    if (sessionId === session()) throw Error('不归档当前打开的对话');
    const a = await revealSidebarItem({ sessionId, pos: args?.pos });
    if (!isArithmeticTitle(text(a))) throw Error('标题已不是算式，跳过');
    const r = await api.archive({ sessionId, isCurrent: () => session() !== sessionId, requireCurrentUrl: false, manageSidebar: false });
    return r || { archived: true, sessionId };
  }

  async function sidebarList(args) {
    // Backwards-compatible alias of sidebarScan.
    return sidebarScan(args);
  }

  async function rename(args) {
    const api = globalThis.ArenaConversationRename;
    if (!api) throw Error('重命名模块未加载');
    const wasOpen = sidebarIsOpen();
    try {
      const r = await api.rename({ sessionId: String(args?.sessionId || ''), model: String(args?.title || ''), isCurrent: () => true });
      return r || { ok: true };
    } finally {
      if (!wasOpen && sidebarIsOpen()) openedByProbe = true;
    }
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

  const ACTIONS = {
    precheck, newChat, ensureAgentMode, send, sendToCurrent, probeInSession,
    sidebarState, sidebarScan, sidebarList, collapseSidebar, archiveFromSidebar, rename, archive,
    ensureSidebarOpen: async () => ensureSidebarOpen(),
    revealSidebarItem: async args => { await revealSidebarItem(args); return { found: true }; },
  };

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

  globalThis.ArenaProbe = { call, isOwnPrompt, isArithmeticTitle, normTitle, version: VERSION };
})();
