/* Page-world SSE tap for the Android WebView shell.
   Same hooking strategy as the extension's snoop.js, but reports to the native
   bridge (window.ArenaTrace) instead of window.postMessage. Never captures
   conversation text — only the public run token. */
(() => {
  if (window.__ATI_SNOOP__) return;
  window.__ATI_SNOOP__ = true;
  const TOKEN_KEY = /^public[-_]access[-_]?token$/i;
  function sessionFromUrl(url) {
    try {
      const u = new URL(url, location.href);
      if (u.origin !== 'https://arena.ai') return null;
      return u.pathname.match(/^\/ai-proxy\/realtime\/v\d+\/sessions\/([a-zA-Z0-9-]+)\/(?:out|stream)$/)?.[1]
        || u.pathname.match(/^\/ai-proxy\/(?:v\d+\/)?realtime\/sessions\/([a-zA-Z0-9-]+)\/(?:out|stream)$/)?.[1]
        || null;
    } catch { return null; }
  }
  function emit(token, sessionId) {
    if (typeof token !== 'string' || token.length > 16384 || token.split('.').length !== 3) return;
    try { window.ArenaTrace && ArenaTrace.onSnoop(JSON.stringify({sessionId, token})); } catch (e) {}
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
  async function tapBody(body, sessionId) {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    while (true) {
      const {done, value} = await reader.read();
      if (done) break;
      buf += decoder.decode(value, {stream: true});
      if (buf.length > 2 * 1024 * 1024) buf = buf.slice(-65536);
      const parts = buf.split(/\r?\n\r?\n/);
      buf = parts.pop() || '';
      for (const part of parts) scanSse(part + '\n\n', sessionId);
    }
  }
  const origFetch = window.fetch;
  window.fetch = async function (...args) {
    const response = await origFetch.apply(this, args);
    const url = typeof args[0] === 'string' ? args[0] : args[0]?.url;
    const sessionId = sessionFromUrl(url);
    if (!sessionId || !response.ok || !response.body) return response;
    try {
      const [page, probe] = response.body.tee();
      tapBody(probe, sessionId).catch(() => {});
      return new Response(page, {headers: response.headers, status: response.status, statusText: response.statusText});
    } catch { return response; }
  };
  const OrigES = window.EventSource;
  if (typeof OrigES === 'function') {
    window.EventSource = function (url, config) {
      const es = new OrigES(url, config);
      const sessionId = sessionFromUrl(url);
      if (sessionId) es.addEventListener('message', ev => { if (typeof ev.data === 'string') scanSse('data: ' + ev.data + '\n\n', sessionId); });
      return es;
    };
    window.EventSource.prototype = OrigES.prototype;
    window.EventSource.CONNECTING = OrigES.CONNECTING;
    window.EventSource.OPEN = OrigES.OPEN;
    window.EventSource.CLOSED = OrigES.CLOSED;
  }
})();
