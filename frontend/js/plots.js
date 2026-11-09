/* All the drawing: the main wave plot, the momentum distribution and the two time plots. */
window.SchPlots = (() => {
  'use strict';

  const C = { dens: '#1dd1a1', re: '#ff9f43', im: '#54a0ff', pot: '#8d99ae', text: '#a9a9a9', bright: '#e0e0e0', panel: '#101116' };

  function prep(canvas) {
    const r = canvas.parentElement.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
    const W = Math.max(60, r.width), H = Math.max(60, r.height);
    if (canvas.width !== Math.round(W * dpr) || canvas.height !== Math.round(H * dpr)) {
      canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
    }
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = '#14151a'; ctx.fillRect(0, 0, W, H);
    return { ctx, W, H };
  }

  function niceTicks(lo, hi, target) {
    const span = hi - lo;
    if (!(span > 0)) return [lo];
    const raw = span / target, p = Math.pow(10, Math.floor(Math.log10(raw))), f = raw / p;
    const step = (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * p, out = [];
    for (let v = Math.ceil(lo / step - 1e-9) * step; v <= hi + 1e-9; v += step) out.push(Math.abs(v) < step * 1e-9 ? 0 : +v.toPrecision(12));
    return out;
  }
  const fmt = v => {
    if (v === 0) return '0';
    const a = Math.abs(v);
    return (a >= 1000 || a < 0.001) ? v.toExponential(1).replace('e+', 'e') : String(+v.toPrecision(3));
  };

  function line(ctx, pts, color, width, dash) {
    ctx.save();
    ctx.strokeStyle = color; ctx.lineWidth = width; ctx.lineJoin = 'round';
    if (dash) ctx.setLineDash(dash);
    ctx.beginPath();
    pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
    ctx.stroke();
    ctx.restore();
  }

  // ---------------------------------------------------------------- main plot
  /**
   * Top panel: |psi|^2 (left axis) over the potential V(x) (right axis) with the mean energy as a dashed line.
   * Bottom panel: Re psi and Im psi.
   */
  function drawMain(canvas, v) {
    const { ctx, W, H } = prep(canvas);
    const m = v && v.meta;
    if (!m) return;
    const padL = 54, padR = 50, padT = 12, padB = 36, gap = 26;
    const iw = W - padL - padR, availH = H - padT - padB - gap;
    const h1 = Math.max(70, Math.round(availH * 0.58)), h2 = Math.max(50, availH - h1);
    const y1 = padT, y2 = padT + h1 + gap;
    const span = m.xmax - m.xmin;
    const X = x => padL + (x - m.xmin) / span * iw;
    const xi = i => m.xmin + (i / m.m) * span;
    const yMax = Math.max(m.denMax * 1.12, 1e-12);
    const vLow = m.vMin < 0 ? m.vMin * 1.15 : 0, vTop = m.vTop;
    const YD = d => y1 + h1 - d / yMax * h1;
    const YV = q => y1 + h1 - (q - vLow) / (vTop - vLow) * h1;
    const amp = Math.sqrt(m.denMax) * 1.12 || 1;
    const YR = a => y2 + h2 / 2 - a / amp * (h2 / 2);

    ctx.fillStyle = C.panel; ctx.fillRect(padL, y1, iw, h1); ctx.fillRect(padL, y2, iw, h2);

    // the edge region of the grid
    const e0 = m.edgeStart;
    ctx.fillStyle = m.absorbing ? 'rgba(255,107,107,.08)' : 'rgba(150,160,190,.10)';
    [[m.xmin, -e0], [e0, m.xmax]].forEach(([a, b]) => {
      ctx.fillRect(X(a), y1, X(b) - X(a), h1); ctx.fillRect(X(a), y2, X(b) - X(a), h2);
    });
    ctx.fillStyle = 'rgba(200,170,170,.55)'; ctx.font = '10.5px system-ui, sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
    [[m.xmin, -e0], [e0, m.xmax]].forEach(([a, b]) => ctx.fillText(m.absorbing ? 'absorbing' : 'wall', (X(a) + X(b)) / 2, y1 + h1 - 16));

    // grid and axes
    ctx.lineWidth = 1; ctx.font = '10.5px system-ui, sans-serif';
    niceTicks(m.xmin, m.xmax, 10).forEach(x => {
      ctx.strokeStyle = 'rgba(255,255,255,.05)';
      ctx.beginPath(); ctx.moveTo(X(x) + .5, y1); ctx.lineTo(X(x) + .5, y1 + h1); ctx.moveTo(X(x) + .5, y2); ctx.lineTo(X(x) + .5, y2 + h2); ctx.stroke();
      ctx.strokeStyle = '#555'; ctx.beginPath(); ctx.moveTo(X(x) + .5, y2 + h2); ctx.lineTo(X(x) + .5, y2 + h2 + 4); ctx.stroke();
      ctx.fillStyle = C.text; ctx.textAlign = 'center'; ctx.textBaseline = 'top'; ctx.fillText(fmt(x), X(x), y2 + h2 + 6);
    });
    ctx.strokeStyle = '#555'; ctx.strokeRect(padL + .5, y1 + .5, iw - 1, h1 - 1); ctx.strokeRect(padL + .5, y2 + .5, iw - 1, h2 - 1);
    ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
    niceTicks(0, yMax, 4).forEach(d => {
      ctx.strokeStyle = 'rgba(255,255,255,.05)'; ctx.beginPath(); ctx.moveTo(padL, YD(d) + .5); ctx.lineTo(padL + iw, YD(d) + .5); ctx.stroke();
      ctx.fillStyle = C.dens; ctx.fillText(fmt(d), padL - 6, YD(d));
    });
    ctx.textAlign = 'left';
    niceTicks(vLow, vTop, 4).forEach(q => { ctx.fillStyle = C.pot; ctx.fillText(fmt(q), padL + iw + 6, YV(q)); });
    ctx.textAlign = 'right';
    niceTicks(-amp, amp, 2).forEach(a => { ctx.fillStyle = C.text; ctx.fillText(fmt(a), padL - 6, YR(a)); });

    // potential
    ctx.save();
    ctx.beginPath(); ctx.rect(padL, y1, iw, h1); ctx.clip();
    const base = YV(Math.max(vLow, Math.min(0, vTop)));
    ctx.beginPath(); ctx.moveTo(X(xi(0)), base);
    for (let i = 0; i < m.m; i++) ctx.lineTo(X(xi(i)), YV(Math.max(vLow, Math.min(vTop, m.V[i]))));
    ctx.lineTo(X(xi(m.m - 1)), base); ctx.closePath();
    ctx.fillStyle = 'rgba(141,153,174,.20)'; ctx.fill();
    ctx.strokeStyle = C.pot; ctx.lineWidth = 1.4; ctx.stroke();

    // mean energy
    if (v.energy >= vLow && v.energy <= vTop) {
      ctx.setLineDash([6, 4]); ctx.strokeStyle = 'rgba(224,224,224,.7)'; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(padL, YV(v.energy)); ctx.lineTo(padL + iw, YV(v.energy)); ctx.stroke(); ctx.setLineDash([]);
    }

    // probability density
    const grad = ctx.createLinearGradient(0, y1, 0, y1 + h1);
    grad.addColorStop(0, 'rgba(29,209,161,.50)'); grad.addColorStop(1, 'rgba(29,209,161,.05)');
    ctx.beginPath(); ctx.moveTo(X(xi(0)), YD(0));
    for (let i = 0; i < m.m; i++) ctx.lineTo(X(xi(i)), YD(Math.min(v.dens[i], yMax)));
    ctx.lineTo(X(xi(m.m - 1)), YD(0)); ctx.closePath(); ctx.fillStyle = grad; ctx.fill();
    ctx.beginPath();
    for (let i = 0; i < m.m; i++) { const px = X(xi(i)), py = YD(Math.min(v.dens[i], yMax)); if (i) ctx.lineTo(px, py); else ctx.moveTo(px, py); }
    ctx.strokeStyle = C.dens; ctx.lineWidth = 1.8; ctx.stroke();

    // mean position
    const mx = X(v.xmean);
    if (mx >= padL && mx <= padL + iw) {
      ctx.fillStyle = C.bright; ctx.beginPath(); ctx.moveTo(mx, y1 + h1 - 1); ctx.lineTo(mx - 5, y1 + h1 + 8); ctx.lineTo(mx + 5, y1 + h1 + 8); ctx.closePath();
      ctx.fill();
    }
    ctx.restore();

    // real and imaginary parts
    ctx.save();
    ctx.beginPath(); ctx.rect(padL, y2, iw, h2); ctx.clip();
    ctx.strokeStyle = 'rgba(255,255,255,.18)'; ctx.beginPath(); ctx.moveTo(padL, YR(0) + .5); ctx.lineTo(padL + iw, YR(0) + .5); ctx.stroke();
    const trace = (arr, color) => {
      ctx.beginPath();
      for (let i = 0; i < m.m; i++) { const px = X(xi(i)), py = YR(Math.max(-amp, Math.min(amp, arr[i]))); if (i) ctx.lineTo(px, py); else ctx.moveTo(px, py); }
      ctx.strokeStyle = color; ctx.lineWidth = 1.4; ctx.stroke();
    };
    if (v.show.im) trace(v.im, C.im);
    if (v.show.re) trace(v.re, C.re);
    ctx.restore();

    // labels and legends
    ctx.font = '12px system-ui, sans-serif'; ctx.textBaseline = 'middle'; ctx.textAlign = 'left';
    let lx = padL + 8, ly = y1 + 14;
    const item = (col, text, dashed) => {
      ctx.strokeStyle = col; ctx.lineWidth = 2; ctx.setLineDash(dashed ? [4, 3] : []);
      ctx.beginPath(); ctx.moveTo(lx, ly); ctx.lineTo(lx + 16, ly); ctx.stroke(); ctx.setLineDash([]);
      ctx.fillStyle = '#c9c9c9'; ctx.fillText(text, lx + 21, ly);
      lx += 21 + ctx.measureText(text).width + 16;
    };
    item(C.dens, '|ψ|²'); item(C.pot, 'V(x)'); item('rgba(224,224,224,.8)', '⟨E⟩', true);
    lx = padL + 8; ly = y2 + 13;
    if (v.show.re) item(C.re, 'Re ψ');
    if (v.show.im) item(C.im, 'Im ψ');
    ctx.fillStyle = C.text; ctx.textAlign = 'right';
    ctx.fillText('x', padL + iw, y2 + h2 + 26);
    ctx.fillStyle = C.dens; ctx.textAlign = 'left'; ctx.fillText('|ψ|²', 4, 8);
    ctx.fillStyle = C.pot; ctx.textAlign = 'right'; ctx.fillText('V, E', W - 4, 8);
    ctx.fillStyle = C.text; ctx.textAlign = 'center'; ctx.fillText('▲ ⟨x⟩', padL + 24, y2 - 10);
  }

  // ---------------------------------------------------------------- momentum distribution
  function drawMomentum(canvas, v) {
    const { ctx, W, H } = prep(canvas);
    const m = v && v.meta;
    if (!m) return;
    const L = 40, R = 10, T = 14, B = 28, pw = W - L - R, ph = H - T - B;
    const dk = (2 * m.kmaxView) / m.mk, kv = v.kView;
    const X = k => L + (k + kv) / (2 * kv) * pw;
    const yMax = Math.max(m.momMax * 1.1, 1e-12), Y = d => T + ph - d / yMax * ph;
    const kOf = b => (b - m.mk / 2) * dk;

    ctx.strokeStyle = '#555'; ctx.lineWidth = 1; ctx.strokeRect(L + .5, T + .5, pw - 1, ph - 1);
    ctx.font = '10.5px system-ui, sans-serif'; ctx.fillStyle = C.text; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
    niceTicks(-kv, kv, 6).forEach(k => {
      ctx.strokeStyle = 'rgba(255,255,255,.05)'; ctx.beginPath(); ctx.moveTo(X(k) + .5, T); ctx.lineTo(X(k) + .5, T + ph); ctx.stroke();
      ctx.fillStyle = C.text; ctx.fillText(fmt(k), X(k), T + ph + 5);
    });
    ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
    niceTicks(0, yMax, 3).forEach(d => { ctx.fillStyle = C.text; ctx.fillText(fmt(d), L - 5, Y(d)); });
    ctx.textAlign = 'right'; ctx.fillStyle = C.text; ctx.fillText('k', W - R, H - 8);

    ctx.save();
    ctx.beginPath(); ctx.rect(L, T, pw, ph); ctx.clip();
    // k at which the kinetic energy equals the barrier or step height
    if (v.vBarrier > 0) {
      ctx.setLineDash([3, 4]); ctx.strokeStyle = 'rgba(141,153,174,.8)';
      const kb = Math.sqrt(2 * v.vBarrier);
      [-kb, kb].forEach(k => { ctx.beginPath(); ctx.moveTo(X(k), T); ctx.lineTo(X(k), T + ph); ctx.stroke(); });
      ctx.setLineDash([]); ctx.fillStyle = C.pot; ctx.textAlign = 'left'; ctx.textBaseline = 'top';
      ctx.fillText('k²/2 = V₀', X(kb) + 4, T + 4);
    }
    // the starting distribution (dashed) and the current one
    ctx.beginPath();
    for (let b = 0; b < m.mk; b++) { const p = [X(kOf(b)), Y(v.mom0[b])]; if (b) ctx.lineTo(p[0], p[1]); else ctx.moveTo(p[0], p[1]); }
    ctx.setLineDash([4, 3]); ctx.strokeStyle = 'rgba(224,224,224,.55)'; ctx.lineWidth = 1; ctx.stroke(); ctx.setLineDash([]);
    ctx.beginPath(); ctx.moveTo(X(kOf(0)), Y(0));
    for (let b = 0; b < m.mk; b++) ctx.lineTo(X(kOf(b)), Y(Math.min(v.mom[b], yMax)));
    ctx.lineTo(X(kOf(m.mk - 1)), Y(0)); ctx.closePath();
    ctx.fillStyle = 'rgba(84,160,255,.30)'; ctx.fill();
    ctx.beginPath();
    for (let b = 0; b < m.mk; b++) { const px = X(kOf(b)), py = Y(Math.min(v.mom[b], yMax)); if (b) ctx.lineTo(px, py); else ctx.moveTo(px, py); }
    ctx.strokeStyle = C.im; ctx.lineWidth = 1.6; ctx.stroke();
    ctx.restore();

    ctx.font = '11px system-ui, sans-serif'; ctx.textBaseline = 'middle'; ctx.textAlign = 'left';
    let lx = L + 4;
    const item = (col, text, dashed) => {
      ctx.strokeStyle = col; ctx.lineWidth = 2; ctx.setLineDash(dashed ? [4, 3] : []);
      ctx.beginPath(); ctx.moveTo(lx, 7); ctx.lineTo(lx + 12, 7); ctx.stroke(); ctx.setLineDash([]);
      ctx.fillStyle = '#bdbdbd'; ctx.fillText(text, lx + 16, 7); lx += 16 + ctx.measureText(text).width + 12;
    };
    item(C.im, 'now'); item('rgba(224,224,224,.7)', 'at t = 0', true);
  }

  // ---------------------------------------------------------------- plots against time
  /**
   * cfg: t (array), series [{y, color, dash}], hlines [{y, color, label}], cursor (a time), ylo, yhi, legend [{color, text, dash}]
   */
  function timePlot(canvas, cfg) {
    const { ctx, W, H } = prep(canvas);
    if (!cfg || !cfg.t || cfg.t.length < 2) return;
    const L = 44, R = 10, T = 18, B = 26, pw = W - L - R, ph = H - T - B;
    const t0 = cfg.t[0], t1 = cfg.t[cfg.t.length - 1];
    const X = t => L + (t - t0) / ((t1 - t0) || 1) * pw, Y = y => T + ph - (y - cfg.ylo) / ((cfg.yhi - cfg.ylo) || 1) * ph;

    ctx.strokeStyle = '#555'; ctx.lineWidth = 1; ctx.strokeRect(L + .5, T + .5, pw - 1, ph - 1);
    ctx.font = '10.5px system-ui, sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
    niceTicks(t0, t1, 5).forEach(t => {
      ctx.strokeStyle = 'rgba(255,255,255,.05)'; ctx.beginPath(); ctx.moveTo(X(t) + .5, T); ctx.lineTo(X(t) + .5, T + ph); ctx.stroke();
      ctx.fillStyle = C.text; ctx.fillText(fmt(t), X(t), T + ph + 5);
    });
    ctx.textAlign = 'right'; ctx.textBaseline = 'middle';
    niceTicks(cfg.ylo, cfg.yhi, 3).forEach(y => {
      ctx.strokeStyle = 'rgba(255,255,255,.05)'; ctx.beginPath(); ctx.moveTo(L, Y(y) + .5); ctx.lineTo(L + pw, Y(y) + .5); ctx.stroke();
      ctx.fillStyle = C.text; ctx.fillText(cfg.fmtY ? cfg.fmtY(y) : fmt(y), L - 5, Y(y));
    });
    ctx.fillStyle = C.text; ctx.textAlign = 'right'; ctx.fillText('t', W - R, H - 8);

    ctx.save();
    ctx.beginPath(); ctx.rect(L, T, pw, ph); ctx.clip();
    (cfg.hlines || []).forEach(h => {
      ctx.setLineDash([5, 4]); ctx.strokeStyle = h.color; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(L, Y(h.y)); ctx.lineTo(L + pw, Y(h.y)); ctx.stroke(); ctx.setLineDash([]);
    });
    cfg.series.forEach(s => line(ctx, cfg.t.map((t, i) => [X(t), Y(s.y[i])]), s.color, s.width || 1.8, s.dash));
    if (cfg.cursor !== undefined) {
      ctx.strokeStyle = 'rgba(255,255,255,.55)'; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(X(cfg.cursor) + .5, T); ctx.lineTo(X(cfg.cursor) + .5, T + ph); ctx.stroke();
    }
    ctx.restore();

    ctx.font = '11px system-ui, sans-serif'; ctx.textBaseline = 'middle'; ctx.textAlign = 'left';
    let lx = L + 2;
    (cfg.legend || []).forEach(it => {
      ctx.strokeStyle = it.color; ctx.lineWidth = 2; ctx.setLineDash(it.dash ? [4, 3] : []);
      ctx.beginPath(); ctx.moveTo(lx, 8); ctx.lineTo(lx + 12, 8); ctx.stroke(); ctx.setLineDash([]);
      ctx.fillStyle = '#bdbdbd'; ctx.fillText(it.text, lx + 16, 8); lx += 16 + ctx.measureText(it.text).width + 12;
    });
  }

  return { drawMain, drawMomentum, timePlot, niceTicks, fmt };
})();
