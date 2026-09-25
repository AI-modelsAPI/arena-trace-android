/* Conversation watchdog — reports reply errors and empty-reply states in the
   OPEN conversation to the app (auto-refresh lives app-side, see ReplyWatchdog.kt).

   To the page this is an isolated world; the failure mode it watches is exactly
   that — page UI stops updating while the model is actually done / has errored.
   So short polling (setInterval) is intentional: it keeps reporting even when
   React, MutationObservers and event loops the page controls have stalled.

   Only status is reported ({ path, generating, errorText, assistantTextLength });
   conversation text never crosses the bridge (24-char snippet, native side drops
   anything longer). Idempotent; re-injection bumps the version. */
(() => {
  const VERSION = 1;
  if ((globalThis.ArenaWatchdog?.version || 0) >= VERSION) return;

  // Reaction delays. An error / empty-reply state must stay on screen this long
  // before it is reported, so the app never reloads mid-stream.
  const ERROR_GRACE_MS = 1000;
  const EMPTY_GRACE_MS = 4500;
  const TAIL_ROUNDS = 3; // ×600ms: last-checkpoint tail emitting nothing ⇒ "ended"
  const SCAN_MS = 600;
  const MAX_MESSAGE_CHARS = 50000;

  const clean = t => String(t ?? '').replace(/\s+/g, ' ').trim();

  // ---- error picker ----
  // Arena's reply-error card reads "Something went wrong with this response,
  // please try again.", plus a smaller "Something went wrong / No preview
  // available" artifact box. Both live INSIDE a reply card whose header names a
  // model. Vote / copy / composer / other chrome never contains these lines, so
  // plain body text (deepest match first) is sufficient and cheap enough.
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

  function errorText() {
    const body = document.body;
    if (!body) return '';
    const hit = matchError(clean(body.innerText || body.textContent || ''));
    if (!hit) return '';
    let hitEl = null, hitText = '';
    for (const el of body.querySelectorAll('div, p, span, section, article, aside, main')) {
      if (el.children.length > 0) continue; // deepest containers only
      const t = clean(el.textContent);
      if (t && t.length <= 400 && hit.test(t)) { hitEl = el; hitText = t; }
    }
    return clean(hitEl ? hitEl.textContent : hitText).slice(0, 40); // snippet, capped again natively
  }

  // ---- answer containers ----
  // The latest reply is the LAST described container on the page. An answer is
  // "present" when it carries any visible text beyond a bare error card.
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
  function answerRoots() {
    const scoped = slideWindow();
    if (scoped) return [scoped];
    const main = document.querySelector('main');
    return [main || document.body].filter(Boolean);
  }
  function hasVisibleText(t) {
    const s = clean(t).replace(matchError(t)?.source || '$^', ' ');
    // Text shorter than an error card or the "no preview" stub doesn't count.
    for (const typo of ['Something went wrong', 'No preview available', '出现了问题', '请重试', '请再试']) {
      if (s === typo) return false;
    }
    return s.length > 120 || (s.length > 0 && !matchError(s));
  }
  function lastReplyText() {
    const roots = answerRoots();
    let best = '';
    for (const root of roots) {
      if (!root?.querySelectorAll) continue;
      const msgs = root.querySelectorAll('div[class*="prose"], div[class*="message"], article, [data-message], [class*="assistant"], [class*="answer"]');
      for (const m of msgs) {
        const r = m.getBoundingClientRect();
        if (r.width < 1 || r.height < 1) continue;
        const t = clean(m.innerText || m.textContent);
        if (t.length > best.length && t.length <= MAX_MESSAGE_CHARS) best = t;
      }
    }
    return best;
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
  let generatingStreak = 0; // consecutive scans with no Stop button
  let lastTextLen = 0, tailQuiet = 0;
  let errorSince = 0, emptySince = 0, lastKey = '';

  function scan() {
    if (!onAgentPath() || !document.body) { lastKey = ''; errorSince = 0; emptySince = 0; generatingStreak = 0; return; }
    const now = Date.now();
    const path = location.pathname;
    const stop = isGenerating();
    generatingStreak = stop ? 0 : generatingStreak + 1;
    const generatingLikely = stop || generatingStreak < 2;
    const err = errorText();
    const text = generatingLikely ? '' : lastReplyText();
    const len = text.length;

    // Tail: streaming ended once the last checkpoint stops growing.
    if (len === lastTextLen && !stop) tailQuiet = Math.min(tailQuiet + 1, TAIL_ROUNDS);
    else tailQuiet = 0;
    lastTextLen = len;
    const ended = !stop && tailQuiet >= TAIL_ROUNDS;

    let key = '';
    if (err && !generatingLikely) {
      if (!errorSince) errorSince = now;
      if (now - errorSince >= ERROR_GRACE_MS) key = 'error:' + err;
    } else errorSince = 0;

    if (!key && ended && len < 40) {
      if (!emptySince) emptySince = now;
      if (now - emptySince >= EMPTY_GRACE_MS) key = 'empty';
    } else if (len >= 40) emptySince = 0;

    if (!key || key === lastKey) return;
    lastKey = key;
    try {
      ArenaProbeBridge.onLog('WATCH|' + JSON.stringify({
        k: key, path, generating: stop, len, at: now,
      }));
    } catch (_) {}
  }

  globalThis.ArenaWatchdog = { scan, version: VERSION };
  scan();
  setInterval(scan, SCAN_MS);
})();
