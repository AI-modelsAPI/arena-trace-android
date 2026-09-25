/* Page-world SSE tap for the Android WebView shell.
   Same hooking strategy as the extension's snoop.js, but reports to the native
   bridge (window.ArenaTrace) instead of window.postMessage. Never captures
   conversation text — only the public run token.

   v2: arena can move the session stream between transports. We tap ALL of them:
   fetch streaming, EventSource, XMLHttpRequest progressive reads and WebSocket
   frames — plus a raw JWT-shape fallback inside those session-stream payloads,
   so a token nested in a non-SSE (plain JSON) body is found too.

   Each event carries the page path AT CAPTURE TIME so the native side can drop
   tokens from a conversation the user already left (a late/old stream would
   otherwise be attributed to the chat now on screen). For diagnostics only, a
   content-free counter line is logged for every newly seen token and for the
   first stream tap per (session, transport) — never the token itself. */
(() => {
  if (window.__ATI_SNOOP__) return;
  window.__ATI_SNOOP__ = true;
  const TOKEN_KEY = /^public[-_]access[-_]?token$/i;
  // A JWT-shaped publicAccessToken: three base64url segments, first one starts
  // with eyJ ("{\"…" — every JWT does). Long ones are capped in emit().
  const JWT_LIKE = /eyJ[A-Za-z0-9_-]{6,2000}\.[A-Za-z0-9_-]{6,4000}\.[A-Za-z0-9_-]{6,2000}/g;
  const MAX_SEEN = 256;
  const seen = new Set();
  const tokenCounts = new Map();
  const tappedStreams = new Set();

  function sessionFromUrl(url) {
    try {
      const u = new URL(url, location.href);
      // Arena is always served over HTTPS to the page; realtime WebSockets use
      // wss:// — match by hostname + the two page-legal protocol families.
      if (u.hostname !== 'arena.ai') return null;
      const proto = u.protocol;
      if (proto !== 'https:' && proto !== 'wss:') return null;
      return u.pathname.match(/^\/ai-proxy\/realtime\/v\d+\/sessions\/([a-zA-Z0-9-]+)\/(?:out|stream)\/?$/)?.[1]
        || u.pathname.match(/^\/ai-proxy\/(?:v\d+\/)?realtime\/sessions\/([a-zA-Z0-9-]+)\/(?:out|stream)\/?$/)?.[1]
        || null;
    } catch { return null; }
  }
  function urlOf(input) {
    if (typeof input === 'string') return input;
    if (input instanceof URL) return input.href;
    return input?.url;
  }
  function logLine(line) {
    try { window.ArenaProbeBridge && ArenaProbeBridge.onLog(line); } catch (_) {}
  }
  function noteTap(sessionId, kind) {
    const key = sessionId + '|' + kind;
    if (tappedStreams.has(key) || tappedStreams.size > 64) return;
    tappedStreams.add(key);
    logLine('截获会话流（' + kind + '）');
  }
  function emit(token, sessionId) {
    if (typeof token !== 'string' || token.length > 16384 || token.split('.').length !== 3) return;
    const page = location.pathname;
    // The stream repeats a run's token on many records; forward each
    // (page, session, token) once. The page is part of the key so a token first
    // seen on another chat is re-sent once the user opens its own chat.
    const key = page + '|' + sessionId + '|' + token;
    if (seen.has(key)) return;
    seen.add(key);
    if (seen.size > MAX_SEEN) seen.delete(seen.values().next().value);
    try { window.ArenaTrace && ArenaTrace.onSnoop(JSON.stringify({ sessionId, token, page })); } catch (e) {}
    const n = (tokenCounts.get(sessionId) || 0) + 1;
    tokenCounts.set(sessionId, n);
    if (tokenCounts.size > 64) tokenCounts.delete(tokenCounts.keys().next().value);
    logLine(n === 1 ? '截获运行令牌 #1' : '截获新一轮令牌 #' + n);
  }
  function takeTokens(obj, sessionId) {
    const records = Array.isArray(obj?.records) ? obj.records : obj ? [obj] : [];
    for (const record of records) {
      const headers = record?.headers;
      const pairs = Array.isArray(headers) ? headers : headers && typeof headers === 'object' ? Object.entries(headers) : [];
      for (const pair of pairs) {
        if (Array.isArray(pair) && TOKEN_KEY.test(String(pair[0] || '')) && typeof pair[1] === 'string') emit(pair[1], sessionId);
      }
      if (typeof record?.publicAccessToken === 'string') emit(record.publicAccessToken, sessionId);
    }
  }
  function scanSse(text, sessionId) {
    if (typeof text !== 'string' || !text) return;
    for (const chunk of text.split(/\r?\n\r?\n/)) {
      const data = chunk.split(/\r?\n/).filter(l => l.startsWith('data:')).map(l => l.slice(5).replace(/^ /, '')).join('\n');
      if (!data) continue;
      let obj; try { obj = JSON.parse(data); } catch { continue; }
      takeTokens(obj, sessionId);
    }
  }
  // Fallback: scan raw payload text for JWT-shaped tokens (covers non-SSE
  // bodies such as a plain JSON response on the stream endpoints).
  function scanRaw(text, sessionId) {
    if (typeof text !== 'string' || text.length < 40) return;
    if (!/eyJ/.test(text)) return;
    JWT_LIKE.lastIndex = 0;
    let m;
    let budget = 8;
    while ((m = JWT_LIKE.exec(text)) && budget-- > 0) emit(m[0], sessionId);
  }
  function scanChunk(text, sessionId) {
    scanSse(text, sessionId);
    scanRaw(text, sessionId);
  }
  async function tapBody(body, sessionId) {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      if (buf.length > 2 * 1024 * 1024) buf = buf.slice(-65536);
      const parts = buf.split(/\r?\n\r?\n/);
      buf = parts.pop() || '';
      // Scan the join of the remainder too, so a token split across a chunk
      // boundary is still caught by the raw fallback (data lines are retried
      // on the next read through buf).
      for (const part of parts) scanChunk(part + '\n\n', sessionId);
      if (buf.length > 40 && /eyJ/.test(buf)) scanRaw(buf.slice(-131072), sessionId);
    }
  }
  const origFetch = window.fetch;
  if (typeof origFetch === 'function') {
    window.fetch = async function (...args) {
      const response = await origFetch.apply(this, args);
      const sessionId = sessionFromUrl(urlOf(args[0]));
      if (!sessionId || !response.ok || !response.body) return response;
      noteTap(sessionId, 'fetch');
      try {
        const [page, probe] = response.body.tee();
        tapBody(probe, sessionId).catch(() => {});
        const copy = new Response(page, { headers: response.headers, status: response.status, statusText: response.statusText });
        // Keep the properties a Response built from a stream would otherwise lose.
        try { Object.defineProperty(copy, 'url', { value: response.url }); } catch (_) {}
        try { Object.defineProperty(copy, 'redirected', { value: response.redirected }); } catch (_) {}
        return copy;
      } catch { return response; }
    };
  }
  const OrigES = window.EventSource;
  if (typeof OrigES === 'function') {
    window.EventSource = function (url, config) {
      const es = new OrigES(url, config);
      const sessionId = sessionFromUrl(urlOf(url));
      if (sessionId) {
        noteTap(sessionId, 'eventsource');
        es.addEventListener('message', ev => {
          if (typeof ev.data === 'string') scanChunk('data: ' + ev.data + '\n\n', sessionId);
        });
      }
      return es;
    };
    window.EventSource.prototype = OrigES.prototype;
    window.EventSource.CONNECTING = OrigES.CONNECTING;
    window.EventSource.OPEN = OrigES.OPEN;
    window.EventSource.CLOSED = OrigES.CLOSED;
  }
  // XMLHttpRequest progressive read: the classic way to stream a POSTed SSE,
  // and a path the fetch tap cannot see.
  const OrigXHR = window.XMLHttpRequest;
  if (typeof OrigXHR === 'function' && OrigXHR.prototype) {
    const origOpen = OrigXHR.prototype.open;
    const origSend = OrigXHR.prototype.send;
    if (typeof origOpen === 'function' && typeof origSend === 'function') {
      OrigXHR.prototype.open = function () {
        try { this.__atiSid = sessionFromUrl(urlOf(arguments[1])); } catch (_) { this.__atiSid = null; }
        return origOpen.apply(this, arguments);
      };
      OrigXHR.prototype.send = function () {
        const sid = this.__atiSid;
        if (sid) {
          noteTap(sid, 'xhr');
          let offset = 0;
          try {
            this.addEventListener('readystatechange', () => {
              if (this.readyState < 3) return;
              let text;
              try { text = this.responseText; } catch (_) { return; }
              if (typeof text !== 'string') return;
              const chunk = text.slice(offset);
              offset = text.length;
              if (chunk.length) scanChunk(chunk, sid);
              else scanRaw(text.slice(-131072), sid);
            });
          } catch (_) {}
        }
        return origSend.apply(this, arguments);
      };
    }
  }
  // WebSocket frames on the session-stream endpoints (arena's realtime).
  const OrigWS = window.WebSocket;
  if (typeof OrigWS === 'function') {
    window.WebSocket = function (url, protocols) {
      const ws = protocols !== undefined ? new OrigWS(url, protocols) : new OrigWS(url);
      const sessionId = sessionFromUrl(urlOf(url));
      if (sessionId) {
        noteTap(sessionId, 'websocket');
        ws.addEventListener('message', ev => {
          if (typeof ev.data === 'string') scanChunk(ev.data + '\n\n', sessionId);
        });
      }
      return ws;
    };
    window.WebSocket.prototype = OrigWS.prototype;
    window.WebSocket.CONNECTING = OrigWS.CONNECTING;
    window.WebSocket.OPEN = OrigWS.OPEN;
    window.WebSocket.CLOSING = OrigWS.CLOSING;
    window.WebSocket.CLOSED = OrigWS.CLOSED;
  }
})();
