/* Conversation watchdog — reports reply errors and empty-reply states in the
   OPEN conversation to the app (auto-refresh lives app-side, see ReplyWatchdog.kt).

   Tightened v2 — an IDLE conversation is never touched. The watchdog only
   evaluates while there has been recent conversation activity:
     FRESH_MS = user sent a message, a Stop button showed, the reply text grew,
                or a session-stream request was observed, within the last 2 min.
   Nothing else fires: a chat you just read, or a failed card left on screen
   from a previous turn, stays silent forever. The freshness stamp survives a
   reload in sessionStorage, so the legit "model done, page shows nothing —
   reload fixes it" case still works after the first auto reload.

   To the page this is an isolated world; the failure mode it watches is exactly
   that — page UI stops updating while the model is actually done / has errored.
   So short polling (setInterval) is intentional: it keeps reporting even when
   React, MutationObservers and the page's own event loops have stalled.

   Only status is reported ({ k, path, generating, len, at, act }); conversation
   text never crosses the bridge (24-char snippet, native side drops anything
   longer). Idempotent; re-injection bumps the version. */
(() => {
  const VERSION = 2;
  if ((globalThis.ArenaWatchdog?.version || 0) >= VERSION) return;

  // Evaluation gate: without recent conversation activity we never even look.
  const FRESH_MS = 120_000;
  // Reaction delays. An error / empty-reply state must stay on screen this long
  // before it is reported, so the app never reloads mid-stream.
  const ERROR_GRACE_MS = 1000;
  const EMPTY_GRACE_MS = 4500;
  const TAIL_ROUNDS = 3; // ×600ms: last-checkpoint tail emitting nothing ⇒ "ended"
  const SCAN_MS = 600;
  const MAX_MESSAGE_CHARS = 50000;
  const ACTIVITY_KEY = 'ati_watchdog_activity';

  const clean = t => String(t ?? '').replace(/\s+/g, ' ').trim();

  // ---- activity tracking (the freshness gate) ----
  // "User just sent / model just streamed" evidence. Sits behind try/catch —
  // losing one signal only narrows the gate, it can never widen it.
  let lastActivityAt = (() => {
    try { return Number(sessionStorage.getItem(ACTIVITY_KEY)) || 0; } catch (_) { return 0; }
  })();
  function touchActivity() {
    lastActivityAt = Date.now();
    try { sessionStorage.setItem(ACTIVITY_KEY, String(lastActivityAt)); } catch (_) {}
  }
  const fresh = () => Date.now() - lastActivityAt <= FRESH_MS;

  const SEND_LABEL = /^(send( message)?|submit|发送(消息)?)$/i;
  addEventListener('keydown', ev => {
    if (ev.key === 'Enter' && !ev.shiftKey &&
        ev.target?.closest?.('textarea,[contenteditable="true"]')) touchActivity();
  }, true);
  addEventListener('click', ev => {
    const b = ev.target?.closest?.('button');
    if (!b) return;
    const label = (b.getAttribute('aria-label') || '').trim() || clean(b.textContent);
    if (SEND_LABEL.test(label) || b.type === 'submit') touchActivity();
  }, true);

  // Session-stream requests show up as resource timing entries. They are the
  // only reliable "the model is talking" signal in the broken case where the
  // UI never shows a Stop button. Converted to epoch via performance.timeOrigin.
  let lastSeenResource = 0;
  function scanStreamResources(now) {
    try {
      const origin = performance.timeOrigin || 0;
      if (!origin) return;
      for (const e of performance.getEntriesByType('resource')) {
        if (!/\/sessions\/|\/ai-proxy\//.test(e.name || '')) continue;
        const end = Math.round(origin + (e.responseEnd || e.startTime || 0));
        if (end > lastSeenResource) {
          lastSeenResource = end;
          if (now - end < FRESH_MS) touchActivity();
        }
      }
    } catch (_) {}
  }

  // ---- error picker ----
  // Arena's reply-error card reads "Something went wrong with this response,
  // please try again.", plus a smaller "Something went wrong / No preview
  // available" artifact box. Only the LAST significant content on the page may
  // count: an error card from a PREVIOUS turn (with a healthy reply below it)
  // must not trigger a reload.
  const ERROR_PATTERNS = [
    /something\s+went\s+wrong(\s+with\s+this\s+response)?[,\s]*\s*(please\s+)?try\s+again/i,
    /no\s+preview\s+available/i,
    /network\s+error[^\n]{0,80}(try\s+again|please)/i,
    /\berror\b[^\n]{0,80}please\s+try\s+again/i,
    /request\s+failed[^\n]{0,80}(try\s+again|please)/i,
    /rate\s*limit(ed)?\b/i,
    /too\s+many\s+requests/i,
    /出现了一点?问题[，,。\s]*.*(重试|再试)/,
    /出了点?问题[，,。\s]*.*(重试|再试)/,
    /遇到问题[，,。\s]*.*(重试|再试)/,
    /出了些问题[，,。\s]*.*(重试|再试)/,
    /出现了问题/,
    /发生错误[，,。\s]*.*(重试|再试)/,
    /网络错误[^\n]{0,40}(重试|再试|稍后)/,
    /请求失败[^\n]{0,40}(重试|再试|稍后)/,
    /请(稍候|稍后)?重试/,
    /请(稍候|稍后)?再试/,
    /(操作)?过于频繁/,
    /429/,
    /trace\s*id/i, // arena error card footer ("…Trace ID …" appears only on errors)
  ];
  const matchError = t => ERROR_PATTERNS.find(re => re.test(t));

  const after = (a, b) => {
    try { return !!(b.compareDocumentPosition(a) & 4); } catch (_) { return false; }
  };

  // The last TAIL error element (deepest match). Falsy text when the newest
  // error has a healthy, non-trivial reply below it — that's an OLD card.
  function tailErrorText(skipAfter) {
    const body = document.body;
    if (!body) return '';
    const bodyText = clean(body.innerText || body.textContent || '');
    const hit = matchError(bodyText);
    if (!hit) return '';
    let last = null;
    for (const el of body.querySelectorAll('div, p, span, section, article')) {
      if (el.children.length > 0) continue; // deepest containers only
      const t = clean(el.textContent);
      if (t && t.length <= 400 && hit.test(t)) last = el;
    }
    if (!last) return bodyText.slice(0, 40);
    for (const m of replyContainers()) {
      // A reply below the error ⇒ the error belongs to an earlier turn.
      if (m.text.length > 40 && after(m.el, last)) return '';
    }
    return clean(last.textContent).slice(0, 40); // capped again natively
  }

  // ---- answer containers ----
  // Reply-like text blocks in the conversation, excluding composer/vote/chrome.
  const REPLY_SEL = 'div[class*="prose"], div[class*="message"], article, [data-message], [class*="assistant"], [class*="answer"]';
  const CHROME_SEL = '[contenteditable="true"], textarea, button, [role="button"], form, nav, header, footer';

  function slideWindow() {
    const slides = document.querySelectorAll(':is([aria-roledescription="slide"], [data-carousel-item])');
    let best = null;
    for (const s of slides) {
      if (s.getAttribute('aria-hidden') === 'true') continue;
      const r = s.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) continue;
      if (r.left < -8) continue; // slid away to the left
      if (!best || r.left < best.getBoundingClientRect().left + 8) best = s;
    }
    return best;
  }

  // {el, text} of every plausible reply block on the page, document order.
  function replyContainers() {
    const roots = [];
    const scoped = slideWindow();
    if (scoped) roots.push(scoped);
    else {
      const main = document.querySelector('main');
      roots.push(main || document.body);
    }
    const out = [];
    const seen = new Set();
    for (const root of roots) {
      if (!root?.querySelectorAll) continue;
      for (const el of root.querySelectorAll(REPLY_SEL)) {
        if (seen.has(el)) continue;
        seen.add(el);
        if (el.closest?.(CHROME_SEL)) continue; // vote bar / composer / chrome
        const r = el.getBoundingClientRect();
        if (r.width < 1 || r.height < 1) continue;
        let skip = false;
        for (const parent of out) {
          if (parent.el.contains(el)) { skip = true; break; }
        }
        if (skip) continue; // keep the outermost match, not nested dupes
        const t = clean(el.innerText || el.textContent);
        if (t && t.length <= MAX_MESSAGE_CHARS) out.push({ el, text: t });
      }
    }
    return out;
  }

  // "Generating" only counts when a Stop button is actually VISIBLE (same rule
  // as probe.js — Arena leaves a hidden Stop button in the DOM after a reply).
  const isGenerating = () => [...document.querySelectorAll('button[aria-label]')]
    .some(b => {
      const r = b.getBoundingClientRect();
      return r.width > 0 && r.height > 0 &&
        /^(stop generating|stop|停止生成|停止回复|停止)$/i.test((b.getAttribute('aria-label') || '').trim());
    });
  const onAgentPath = () => /^\/agent|^\/c\//.test(location.pathname);

  // ---- reporter ----
  let lastPath = '';
  let generatingStreak = 0; // consecutive scans with no Stop button
  let lastRawLen = -1, prevRawLen = -1, tailQuiet = 0; // -1 = no baseline yet
  let errorSince = 0, emptySince = 0, lastKey = '';

  function resetAccumulators() {
    lastKey = '';
    errorSince = 0;
    emptySince = 0;
    generatingStreak = 0;
    tailQuiet = 0;
  }

  function scan() {
    const path = location.pathname;
    if (!onAgentPath() || !document.body) {
      lastPath = '';
      resetAccumulators();
      lastRawLen = -1; prevRawLen = -1;
      return;
    }
    if (path !== lastPath) { // SPA conversation switch: never carry verdicts over
      lastPath = path;
      resetAccumulators();
      // Tell the native shell: doUpdateVisitedHistory misses replaceState and
      // some sidebar navigations, so the panel would otherwise keep showing the
      // previous conversation's log.
      try { ArenaProbeBridge.onLog('PATH|' + path); } catch (_) {}
      lastRawLen = -1; prevRawLen = -1;
    }
    const now = Date.now();
    scanStreamResources(now);

    // Evidence gathering happens BEFORE the freshness gate: a visible Stop
    // button or a growing reply IS activity and keeps/re-opens the window,
    // even in a tab that started with no history (e.g. right after a reload).
    const stop = isGenerating();
    if (stop) touchActivity();
    const rawLen = replyContainers().at(-1)?.text.length || 0;
    if (lastRawLen >= 0 && rawLen > lastRawLen) touchActivity();
    lastRawLen = rawLen;

    if (!fresh()) { // idle conversation: stay silent
      resetAccumulators();
      return;
    }

    generatingStreak = stop ? 0 : generatingStreak + 1;
    const generatingLikely = stop || generatingStreak < 2;

    // Tail: streaming ended once the last checkpoint stops growing.
    if (!stop && rawLen === prevRawLen) tailQuiet = Math.min(tailQuiet + 1, TAIL_ROUNDS);
    else tailQuiet = 0;
    prevRawLen = rawLen;
    const ended = !stop && tailQuiet >= TAIL_ROUNDS;

    const err = tailErrorText();
    let key = '';
    if (err && !generatingLikely) {
      if (!errorSince) errorSince = now;
      if (now - errorSince >= ERROR_GRACE_MS) key = 'error:' + err;
    } else errorSince = 0;

    if (!key && ended && rawLen < 40) {
      if (!emptySince) emptySince = now;
      if (now - emptySince >= EMPTY_GRACE_MS) key = 'empty';
    } else if (rawLen >= 40) emptySince = 0;

    if (!key || key === lastKey) return;
    lastKey = key;
    try {
      ArenaProbeBridge.onLog('WATCH|' + JSON.stringify({
        k: key, path, generating: stop, len: rawLen, at: now, act: lastActivityAt,
      }));
    } catch (_) {}
  }

  globalThis.ArenaWatchdog = { scan, touchActivity, version: VERSION };
  scan();
  setInterval(scan, SCAN_MS);
})();
