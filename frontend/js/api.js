/* Talks to the Java backend. Every function returns a promise and throws an Error with a readable message. */
window.SchApi = (() => {
  'use strict';

  const NETWORK = 'Cannot reach the Java server. Is it still running?';

  /**
   * Runs one simulation. The reply is binary: int32 length of the JSON, the JSON (padded to a multiple of 4),
   * then every frame as float32: density (m), Re psi (m), Im psi (m), momentum density (mk).
   */
  async function simulate(params, signal) {
    const q = Object.entries(params).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
    let r;
    try {
      r = await fetch('/api/simulate?' + q, { signal });
    } catch (e) {
      if (e.name === 'AbortError') throw e;
      throw new Error(NETWORK);
    }
    if (!r.ok) {
      const body = await r.json().catch(() => ({}));
      throw new Error(body.error || 'The server answered with HTTP ' + r.status + '.');
    }
    const buf = await r.arrayBuffer();
    const jsonLen = new DataView(buf).getInt32(0, true);
    const meta = JSON.parse(new TextDecoder().decode(new Uint8Array(buf, 4, jsonLen)));
    const data = new Float32Array(buf, 4 + jsonLen);
    const stride = 3 * meta.m + meta.mk;
    if (data.length !== meta.frames * stride) throw new Error('The server sent an unexpected amount of data.');
    return { meta, data, stride };
  }

  return {
    simulate,
    ping: () => fetch('/api/ping', { method: 'POST' }).catch(() => {}),
    bye: () => navigator.sendBeacon && navigator.sendBeacon('/api/bye')
  };
})();
