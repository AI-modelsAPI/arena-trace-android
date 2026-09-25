/* Bridge prelude — must run before snoop.js / probe.js / watchdog.js.

   Preferred transport: window.ArenaTraceMsg, injected by
   WebViewCompat.addWebMessageListener ONLY into https://arena.ai frames (other
   origins, iframes of other sites and pages opened from links never see it).
   This prelude exposes the API the page scripts already call on top of it:
     window.ArenaTrace.onSnoop(payload)
     window.ArenaProbeBridge.onResult(reqId, resultJson) / onLog(line)
   Wire format: JSON {"t":"snoop"|"result"|"log", "p":"…", "id":"…"}
   (parsed by BridgeMessage.kt).

   On old WebViews without the message channel the app injects Java objects under
   the same names instead, and this prelude does nothing. Idempotent. */
(() => {
  const ch = window.ArenaTraceMsg;
  if (!ch || typeof ch.postMessage !== 'function') return;
  if (window.__ATI_BRIDGE__) return;
  try { Object.defineProperty(window, '__ATI_BRIDGE__', { value: true }); } catch (_) { return; }
  const send = msg => { try { ch.postMessage(JSON.stringify(msg)); } catch (_) {} };
  const expose = (name, api) => {
    try {
      Object.defineProperty(window, name, { value: Object.freeze(api), writable: false, configurable: false });
    } catch (_) {}
  };
  expose('ArenaTrace', {
    onSnoop: payload => send({ t: 'snoop', p: String(payload) }),
  });
  expose('ArenaProbeBridge', {
    onResult: (reqId, resultJson) => send({ t: 'result', id: String(reqId), p: String(resultJson) }),
    onLog: line => send({ t: 'log', p: String(line) }),
  });
})();
