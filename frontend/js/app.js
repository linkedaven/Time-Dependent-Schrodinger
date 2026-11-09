/* Page logic: reads the controls, asks the Java backend for a simulation, and plays the frames back. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const PLAY_FPS = 20;                       // 240 frames take 12 s at speed 1

  const NUM = ['v0', 'width', 'gap', 'omega', 'x0', 'k0', 'sigma', 'tmax'];
  const SEL = ['potential', 'method', 'n', 'dt', 'edges'];

  const BASE = { potential: 'barrier', v0: 6, width: 1, gap: 1.5, omega: 0.5, x0: -15, k0: 3, sigma: 1, method: 'split', n: 2048, dt: 0.002, tmax: 14, edges: 'absorbing' };
  const PRESETS = [
    { id: 'tunnel', name: 'Tunnelling through a barrier', note: 'Kinetic energy 4.5 is below the barrier height 6, yet about 15 % of the packet gets through.',
      p: {} },
    { id: 'over', name: 'Above the barrier: partial reflection', note: 'Even when the energy exceeds the barrier, part of the wave is reflected. Classically all of it would pass.',
      p: { width: 0.6, k0: 4 } },
    { id: 'step', name: 'Step: reflection and transmission', note: 'A step with energy above it. The transmitted wave is slower and shorter.',
      p: { potential: 'step', v0: 4, k0: 3.5, sigma: 1.2 } },
    { id: 'well', name: 'Quantum well', note: 'A well reflects part of a wave too, although the particle only gains energy over it.',
      p: { potential: 'well', v0: 8, width: 1.2, k0: 3 } },
    { id: 'double', name: 'Double barrier (resonant tunnelling)', note: 'The wave bounces between the two barriers, so the packet leaves the structure slowly. Run longer to see all of it leave.',
      p: { potential: 'double_barrier', width: 0.8, gap: 1.5, k0: 3, sigma: 1.5, tmax: 40 } },
    { id: 'harmonic', name: 'Harmonic oscillator (coherent state)', note: 'A packet of width 1/√(2ω) swings back and forth without changing shape. Its mean position follows the classical cosine.',
      p: { potential: 'harmonic', omega: 1, x0: -4, k0: 0, sigma: 0.7071, tmax: 25 } },
    { id: 'free', name: 'Free packet spreading', note: 'Position spread grows as σ√(1 + (t/2σ²)²) while the momentum spread stays 1/(2σ), so Δx·Δp grows from ½.',
      p: { potential: 'free', x0: -10, k0: 2, sigma: 0.8 } },
    { id: 'well2', name: 'Double well: tunnelling between wells', note: 'Starting in the left well, the packet tunnels through the central barrier and back.',
      p: { potential: 'double_well', v0: 2.5, width: 1.5, x0: -1.5, k0: 0, sigma: 0.41, tmax: 40, edges: 'walls' } },
    { id: 'euler', name: 'Forward Euler blows up', note: 'The simplest method multiplies the norm by more than 1 at every step. The run stops when the norm passes 10⁶.',
      p: { method: 'euler', n: 512, dt: 0.004, tmax: 8 } }
  ];

  const state = { data: null, pos: 0, playing: true, speed: 1, series: 'norm', seq: 0, abort: null, busy: 0, dirty: true };
  let bufs = null;

  // ---------------------------------------------------------------- controls
  const f1 = v => (+v).toFixed(1), f2 = v => (+v).toFixed(2);
  const FORMAT = { v0: f1, width: f1, gap: f1, omega: f2, x0: f1, k0: f1, sigma: f2, tmax: v => (+v).toFixed(0) };

  const ROWS = { free: [], step: ['v0'], barrier: ['v0', 'width'], well: ['v0', 'width'], double_barrier: ['v0', 'width', 'gap'], harmonic: ['omega'], double_well: ['v0', 'width'] };
  const LABELS = {
    step: { v0: 'Step height V₀' },
    barrier: { v0: 'Barrier height V₀', width: 'Barrier width a' },
    well: { v0: 'Well depth V₀', width: 'Well width a' },
    double_barrier: { v0: 'Barrier height V₀', width: 'Barrier width a' },
    double_well: { v0: 'Barrier height V₀ (at x = 0)', width: 'Well half-separation d' }
  };

  function readParams() {
    const p = {};
    NUM.forEach(id => { p[id] = +$(id).value; });
    SEL.forEach(id => { p[id] = $(id).value; });
    return p;
  }

  function applyParams(p) {
    const all = Object.assign({}, BASE, p);
    NUM.forEach(id => { $(id).value = all[id]; });
    SEL.forEach(id => { $(id).value = String(all[id]); });
    updateLabels();
  }

  function updateLabels() {
    NUM.forEach(id => { $('val-' + id).textContent = FORMAT[id]($(id).value); });
    const kind = $('potential').value;
    ['v0', 'width', 'gap', 'omega'].forEach(id => { $('row-' + id).hidden = !ROWS[kind].includes(id); });
    const lab = LABELS[kind] || {};
    $('lab-v0').textContent = lab.v0 || 'Height V₀';
    $('lab-width').textContent = lab.width || 'Width a';

    const p = readParams();
    const sk = 1 / (2 * p.sigma), kin = 0.5 * p.k0 * p.k0 + 1 / (8 * p.sigma * p.sigma);
    let info = `Momentum spread σₖ = 1/(2σ) = ${f2(sk)}. Mean energy ½k₀² + 1/(8σ²) = ${f2(kin)}.`;
    if (['step', 'barrier', 'double_barrier'].includes(kind) && p.v0 > 0) {
      const e = 0.5 * p.k0 * p.k0;
      info += ` ½k₀² = ${f2(e)} is ${e < p.v0 ? 'below' : 'above'} V₀ = ${f1(p.v0)}.`;
    }
    $('packetInfo').textContent = info;

    const frames = 239, spf = Math.max(1, Math.ceil(p.tmax / (p.dt * frames)));
    const steps = spf * frames;
    const text = {
      split: 'Unitary: the norm stays 1 to rounding error. Exact in space, error O(Δt²) in time. Treats the box as periodic.',
      cn: 'Unitary: the norm stays 1 to rounding error. Second order in Δt and Δx, and one tridiagonal solve per step.',
      euler: 'Not stable: every step multiplies the norm by more than 1, whatever the step size.'
    }[p.method];
    $('solverInfo').textContent = `${text} ${steps.toLocaleString('en-US')} steps of size ${(p.tmax / steps).toPrecision(2)}.`;
  }

  // ---------------------------------------------------------------- running a simulation
  let busyTimer = 0, runTimer = 0;

  function showMessage(text, busy) {
    const m = $('msg');
    m.hidden = !text;
    m.className = busy ? 'msg busy' : 'msg';
    m.textContent = text || '';
  }

  async function run() {
    const my = ++state.seq;
    if (state.abort) state.abort.abort();
    state.abort = new AbortController();
    clearTimeout(busyTimer);
    busyTimer = setTimeout(() => showMessage('Computing…', true), 200);
    document.body.style.cursor = 'progress';
    try {
      const d = await SchApi.simulate(readParams(), state.abort.signal);
      if (my !== state.seq) return;
      setData(d);
      showMessage('');
    } catch (e) {
      if (e.name === 'AbortError') return;
      if (my === state.seq) showMessage(e.message);
    } finally {
      if (my === state.seq) { clearTimeout(busyTimer); document.body.style.cursor = ''; }
    }
  }

  function scheduleRun(ms) {
    clearTimeout(runTimer);
    runTimer = setTimeout(run, ms);
  }

  function setData(d) {
    state.data = d;
    const m = d.meta;
    bufs = { dens: new Float32Array(m.m), re: new Float32Array(m.m), im: new Float32Array(m.m), mom: new Float32Array(m.mk), mom0: new Float32Array(m.mk) };
    const off0 = 3 * m.m;
    bufs.mom0.set(d.data.subarray(off0, off0 + m.mk));
    $('scrub').max = m.frames - 1;
    state.pos = 0;
    state.playing = true;
    $('scatterCard').hidden = !m.scattering;
    renderNotes();
    updatePlayButton();
    state.dirty = true;
  }

  function renderNotes() {
    const m = state.data.meta, box = $('notes');
    box.innerHTML = '';
    const add = (text, bad) => { const p = document.createElement('p'); p.textContent = text; if (bad) p.className = 'bad'; box.appendChild(p); };
    if (m.blewUpAt !== null && m.blewUpAt !== undefined) {
      add(`The solver blew up at t = ${(+m.blewUpAt).toPrecision(3)}: the norm passed 10⁶, so only the frames before that are shown.`, true);
    }
    (m.warnings || []).forEach(w => add(w, false));
  }

  // ---------------------------------------------------------------- playback
  function updatePlayButton() {
    $('play').textContent = state.playing ? 'Pause' : 'Play';
    $('scrub').value = Math.round(state.pos);
  }

  $('play').addEventListener('click', () => {
    if (!state.data) return;
    const last = state.data.meta.frames - 1;
    if (!state.playing && state.pos >= last) state.pos = 0;
    state.playing = !state.playing;
    updatePlayButton(); state.dirty = true;
  });
  $('restart').addEventListener('click', () => { state.pos = 0; state.playing = true; updatePlayButton(); state.dirty = true; });
  $('scrub').addEventListener('input', e => { state.pos = +e.target.value; state.playing = false; updatePlayButton(); state.dirty = true; });
  $('speed').addEventListener('change', e => { state.speed = +e.target.value; });
  $('showRe').addEventListener('change', () => { state.dirty = true; });
  $('showIm').addEventListener('change', () => { state.dirty = true; });
  $('series').addEventListener('change', e => { state.series = e.target.value; state.dirty = true; });

  // ---------------------------------------------------------------- one frame of data
  const lerp = (arr, i0, i1, u) => arr[i0] + (arr[i1] - arr[i0]) * u;

  function currentView() {
    const d = state.data, m = d.meta;
    const i0 = Math.min(m.frames - 1, Math.floor(state.pos)), i1 = Math.min(m.frames - 1, i0 + 1), u = state.pos - i0;
    const a = i0 * d.stride, b = i1 * d.stride;
    const mix = (dst, off, len) => { for (let i = 0; i < len; i++) dst[i] = d.data[a + off + i] + (d.data[b + off + i] - d.data[a + off + i]) * u; };
    mix(bufs.dens, 0, m.m); mix(bufs.re, m.m, m.m); mix(bufs.im, 2 * m.m, m.m); mix(bufs.mom, 3 * m.m, m.mk);
    const p = readParams();
    const sk = 1 / (2 * p.sigma);
    const kView = Math.min(m.kmaxView, Math.max(6, Math.ceil((Math.abs(p.k0) + 5 * sk + 2) / 2) * 2));
    const piece = ['step', 'barrier', 'double_barrier'].includes(m.potential);
    const sc = {};
    ['t', 'norm', 'accounted', 'energy', 'xmean', 'pmean', 'dxSpread', 'dpSpread'].forEach(k => { sc[k] = lerp(m[k], i0, i1, u); });
    if (m.scattering) ['pLeft', 'pInside', 'pRight'].forEach(k => { sc[k] = lerp(m[k], i0, i1, u); });
    return {
      meta: m, dens: bufs.dens, re: bufs.re, im: bufs.im, mom: bufs.mom, mom0: bufs.mom0, kView,
      vBarrier: piece ? p.v0 : 0, energy: sc.energy, xmean: sc.xmean, sc, params: p,
      show: { re: $('showRe').checked, im: $('showIm').checked }
    };
  }

  // ---------------------------------------------------------------- drawing
  const digitsFor = span => Math.max(2, Math.min(9, Math.ceil(-Math.log10(span || 1)) + 2));

  function range(arrs, floor) {
    let lo = Infinity, hi = -Infinity;
    arrs.forEach(a => a.forEach(v => { if (v < lo) lo = v; if (v > hi) hi = v; }));
    if (floor !== undefined) lo = Math.min(lo, floor);
    const span = hi - lo, pad = span > 0 ? span * 0.15 : Math.max(Math.abs(hi) * 0.01, 1e-6);
    return [lo - pad, hi + pad];
  }

  function seriesConfig(v) {
    const m = v.meta, t = m.t, cursor = v.sc.t;
    const cfg = { t, cursor, series: [], hlines: [], legend: [] };
    let note = '';
    if (state.series === 'norm') {
      cfg.series = [{ y: m.norm, color: '#54a0ff' }, { y: m.accounted, color: '#1dd1a1', dash: [5, 3], width: 1.4 }];
      cfg.legend = [{ color: '#54a0ff', text: 'on the grid' }, { color: '#1dd1a1', text: 'plus absorbed', dash: true }];
      [cfg.ylo, cfg.yhi] = m.absorbing ? [0, 1.05] : range([m.norm, m.accounted]);
      if (m.absorbing) cfg.yhi = Math.max(1.05, cfg.yhi);
      note = m.absorbing
        ? 'Probability still on the grid (blue) falls as waves are absorbed at the edges. Grid plus absorbed (green) must stay 1.'
        : 'Total probability. A unitary method keeps it at 1 to rounding error; forward Euler does not.';
    } else if (state.series === 'energy') {
      cfg.series = [{ y: m.energy, color: '#ff9f43' }];
      cfg.hlines = [{ y: m.energy[0], color: 'rgba(224,224,224,.45)' }];
      [cfg.ylo, cfg.yhi] = range([m.energy]);
      cfg.legend = [{ color: '#ff9f43', text: '⟨H⟩' }];
      note = 'Mean energy. Constant for exact evolution. The split-step value wobbles by O(Δt²) near sharp edges, and absorbing edges take energy away.';
    } else if (state.series === 'xmean') {
      cfg.series = [{ y: m.xmean, color: '#1dd1a1' }];
      cfg.legend = [{ color: '#1dd1a1', text: '⟨x⟩' }];
      const arrs = [m.xmean];
      if (m.xExact) { cfg.series.push({ y: m.xExact, color: '#e0e0e0', dash: [5, 3], width: 1.2 }); cfg.legend.push({ color: '#e0e0e0', text: 'exact', dash: true }); arrs.push(m.xExact); }
      [cfg.ylo, cfg.yhi] = range(arrs);
      note = m.xExact ? 'Dashed: exact ⟨x⟩ = x₀ cos ωt + (k₀/ω) sin ωt.' : 'Mean position. In free space it moves at the speed k₀.';
    } else {
      const prod = m.dxSpread.map((a, i) => a * m.dpSpread[i]);
      cfg.series = [{ y: prod, color: '#54a0ff' }];
      cfg.hlines = [{ y: 0.5, color: 'rgba(255,107,107,.7)' }];
      cfg.legend = [{ color: '#54a0ff', text: 'Δx · Δp' }];
      [cfg.ylo, cfg.yhi] = range([prod], 0.5);
      note = 'The uncertainty principle demands Δx·Δp ≥ ½ (dashed). A Gaussian packet starts exactly on the bound.';
    }
    cfg.fmtY = y => y.toFixed(digitsFor(cfg.yhi - cfg.ylo));
    $('serNote').textContent = note;
    return cfg;
  }

  function probConfig(v) {
    const m = v.meta, right = v.params.k0 >= 0;
    const far = right ? m.pRight : m.pLeft, near = right ? m.pLeft : m.pRight;
    const cfg = {
      t: m.t, cursor: v.sc.t, ylo: 0, yhi: 1.02, fmtY: y => y.toFixed(1),
      series: [{ y: near, color: '#54a0ff' }, { y: far, color: '#ff9f43' }, { y: m.pInside, color: '#9a9a9a', width: 1.3 }],
      hlines: [], legend: [{ color: '#54a0ff', text: 'reflected' }, { color: '#ff9f43', text: 'transmitted' }, { color: '#9a9a9a', text: 'inside' }]
    };
    if (m.exact.applicable) cfg.hlines = [{ y: m.exact.transmitted, color: 'rgba(255,159,67,.8)' }, { y: m.exact.reflected, color: 'rgba(84,160,255,.8)' }];
    return cfg;
  }

  function pct(x) { return (100 * x).toFixed(2) + ' %'; }

  function renderScatterRows(v) {
    const m = v.meta, right = v.params.k0 >= 0, s = v.sc;
    const far = right ? s.pRight : s.pLeft, near = right ? s.pLeft : s.pRight;
    const farEnd = (right ? m.pRight : m.pLeft).slice(-1)[0], nearEnd = (right ? m.pLeft : m.pRight).slice(-1)[0];
    const rows = [['Reflected now', pct(near)], ['Transmitted now', pct(far)], ['Inside the structure now', pct(s.pInside)]];
    if (m.exact.applicable) {
      rows.push(['Transmitted at the end', pct(farEnd)], ['Exact transmitted T', pct(m.exact.transmitted)],
        ['Simulation minus exact', ((farEnd - m.exact.transmitted) * 100).toFixed(2) + ' points']);
    } else {
      rows.push(['Reflected at the end', pct(nearEnd)], ['Transmitted at the end', pct(farEnd)]);
    }
    $('scatterRows').innerHTML = rows.map(([k, val]) => `<dt>${k}</dt><dd>${val}</dd>`).join('');
  }

  function renderChips(v) {
    const s = v.sc, m = v.meta, prod = s.dxSpread * s.dpSpread;
    const tot = m.absorbing ? `${s.norm.toFixed(5)} <span class="muted">(+ absorbed = ${s.accounted.toFixed(6)})</span>` : s.norm.toPrecision(7);
    $('chips').innerHTML =
      `<span>t = <b>${s.t.toFixed(2)}</b> / ${(+m.tmax).toFixed(1)}</span>` +
      `<span>∫|ψ|² = <b>${tot}</b></span>` +
      `<span>⟨H⟩ = <b>${s.energy.toFixed(4)}</b></span>` +
      `<span>⟨x⟩ = <b>${s.xmean.toFixed(3)}</b></span>` +
      `<span>⟨p⟩ = <b>${s.pmean.toFixed(3)}</b></span>` +
      `<span>Δx·Δp = <b>${prod.toFixed(4)}</b> <span class="muted">(≥ 0.5)</span></span>` +
      `<span class="muted">${m.method}</span>`;
  }

  function draw() {
    if (!state.data) return;
    const v = currentView();
    SchPlots.drawMain($('cv'), v);
    SchPlots.drawMomentum($('mom'), v);
    SchPlots.timePlot($('ser'), seriesConfig(v));
    if (v.meta.scattering) { SchPlots.timePlot($('prob'), probConfig(v)); renderScatterRows(v); }
    renderChips(v);
    $('scrub').value = Math.round(state.pos);
  }

  // ---------------------------------------------------------------- main loop
  let last = performance.now();
  function loop(now) {
    requestAnimationFrame(loop);
    const dt = Math.min(0.1, (now - last) / 1000); last = now;
    if (state.data && state.playing) {
      state.pos += dt * PLAY_FPS * state.speed;
      const end = state.data.meta.frames - 1;
      if (state.pos >= end) { state.pos = end; state.playing = false; updatePlayButton(); }
      state.dirty = true;
    }
    if (state.dirty) { state.dirty = false; draw(); }
  }

  // ---------------------------------------------------------------- wiring
  const presetSel = $('preset');
  presetSel.innerHTML = '<option value="">Custom</option>' + PRESETS.map(p => `<option value="${p.id}">${p.name}</option>`).join('');
  presetSel.addEventListener('change', () => {
    const p = PRESETS.find(x => x.id === presetSel.value);
    $('presetNote').textContent = p ? p.note : '';
    if (!p) return;
    applyParams(p.p);
    run();
  });

  NUM.concat(SEL).forEach(id => {
    $(id).addEventListener('input', () => {
      updateLabels();
      presetSel.value = ''; $('presetNote').textContent = '';
      scheduleRun(NUM.includes(id) ? 250 : 60);
    });
  });

  new ResizeObserver(() => { state.dirty = true; }).observe($('plot'));
  new ResizeObserver(() => { state.dirty = true; }).observe(document.querySelector('.right'));
  setInterval(SchApi.ping, 20000);
  window.addEventListener('beforeunload', SchApi.bye);

  window.__sch = {                    // used by the automated browser tests
    state, readParams, applyParams,
    ready: () => !!state.data && state.seq > 0 && document.body.style.cursor !== 'progress' && $('msg').hidden,
    setPos: p => { state.pos = p; state.playing = false; updatePlayButton(); state.dirty = true; }
  };

  presetSel.value = 'tunnel';
  $('presetNote').textContent = PRESETS[0].note;
  applyParams(PRESETS[0].p);
  requestAnimationFrame(loop);
  run();
})();
