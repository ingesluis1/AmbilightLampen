/*
 * Meubels in één kijkhoek (schuin van voren, zoals Stardew Valley): de vloer van bovenaf op ware grootte, elk meubel
 * steekt met zijn hoogte omhoog. Je ziet de bovenkant en de voorkant.
 *
 * Elk meubel is een lijstje blokken in 3D (meters): x 0..W (links naar rechts als je ervoor staat), z 0..D (achterkant
 * naar voorkant), h (hoogte vanaf de vloer). Per draairichting rekenen we uit wat je ziet:
 *   S = voorkant naar de kijker (zuid), N = achterkant naar de kijker, W = voorkant naar links, E = voorkant naar rechts.
 * Daarna tekenen we de blokken van achter naar voren: bovenvlak (lichter) en voorvlak, met een donkere rand.
 */
(function (root) {
  const PPM = 16;           // pixels per meter op de vloer
  const HF = 1.0;           // hoogte op ware grootte (zoals Stardew Valley)
  const OUT = 0.32;         // lijnen binnenin: zoveel donkerder dan de kleur (de buitenrand is apart, donker)
  const INK = '#2a1a2e';    // buitenrand, zoals de rest van de kaart
  const TF = 0.6;           // bovenvlakken ingedrukt (diepte telt voor 60%), zodat de voorkant meer opvalt; platte dingen (kleed) op ware diepte

  function hex(c) { return [1, 3, 5].map(i => parseInt(c.slice(i, i + 2), 16)); }
  function shade(c, f) {
    if (c.startsWith('rgba')) return c;
    const v = hex(c).map(x => f >= 0 ? x + (255 - x) * f : x * (1 + f));
    return '#' + v.map(x => Math.max(0, Math.min(255, Math.round(x))).toString(16).padStart(2, '0')).join('');
  }

  // ---------- blokken naar het scherm ----------
  /** Blok in voorwerp-ruimte naar scherm-ruimte (meters) voor een kijkrichting. Scherm: x naar rechts, z van achter (0) naar voren. */
  function toView(b, v, W, D) {
    let x0, x1, z0, z1;
    if (v === 'S') { x0 = b.x0; x1 = b.x1; z0 = b.z0; z1 = b.z1; }
    else if (v === 'N') { x0 = W - b.x1; x1 = W - b.x0; z0 = D - b.z1; z1 = D - b.z0; }
    else if (v === 'W') { x0 = D - b.z1; x1 = D - b.z0; z0 = W - b.x1; z1 = W - b.x0; }   // voorkant links; rechterhand naar achteren
    else { x0 = b.z0; x1 = b.z1; z0 = b.x0; z1 = b.x1; }                                  // E: voorkant rechts
    return { ...b, x0, x1, z0, z1 };
  }

  /**
   * Teken een voorwerp. parts(W, D) geeft de blokken; v de kijkrichting. Geeft een canvas terug met daarin het voorwerp,
   * en waar de vloervoetafdruk zit: de onderkant van het plaatje (min marge) is de voorrand van de voetafdruk.
   */
  function render(parts, W, D, v, opt = {}) {
    const side = v === 'W' || v === 'E';
    const SW = side ? D : W, SD = side ? W : D;                     // voetafdruk op het scherm (meters)
    const bs = parts.filter(b => !b.onlyView || b.onlyView === v).map(b => toView(b, v, W, D));
    const hf = opt.hf || HF, px = m => Math.round(m * PPM), ph = m => Math.round(m * PPM * hf);
    const tf = opt.tf || TF, pz = m => Math.round(m * PPM * tf);
    const fw = px(SW), fd = px(SD), fdz = pz(SD), maxH = Math.max(0, ...bs.map(b => ph(b.h1)));
    const M = 2, cw = fw + M * 2, ch = Math.max(fd, fdz + maxH) + M * 2;
    const c = document.createElement('canvas'); c.width = cw; c.height = ch;
    const g = c.getContext('2d');
    const base = ch - M;                                            // y van de voorrand van de voetafdruk
    // schaduw op de vloer, iets naar rechtsonder
    if (!opt.noShadow) { g.fillStyle = 'rgba(30,10,30,.28)'; g.fillRect(M + 1, base - fdz + 1, fw, fdz); }
    // het voorwerp op een eigen laag; van achter naar voren, op dezelfde plek eerst wat lager is
    const L = document.createElement('canvas'); L.width = cw; L.height = ch; const lg = L.getContext('2d');
    const rect = b => { const x0 = px(b.x0), x1 = Math.max(x0 + 1, px(b.x1)), yT = -(fdz - pz(b.z0)) - ph(b.h1), yB = -(fdz - pz(b.z1)) - ph(b.h0); return [x0, x1, yT, yB]; };
    const ordered = drawOrder(bs, rect);
    for (const b of ordered) if (!b.overlay) drawBox(lg, b, M, base, fdz, px, ph, pz);
    // donkere buitenrand om het hele voorwerp (alleen waar het niet doorzichtig is, zoals glas)
    if (!opt.noInk) {
      const id = lg.getImageData(0, 0, cw, ch), d = id.data, solid = i => d[i * 4 + 3] > 200, ink = hex(INK), add = [];
      for (let y = 0; y < ch; y++) for (let x = 0; x < cw; x++) { const i = y * cw + x; if (d[i * 4 + 3] > 0) continue;
        if ((x > 0 && solid(i - 1)) || (x < cw - 1 && solid(i + 1)) || (y > 0 && solid(i - cw)) || (y < ch - 1 && solid(i + cw))) add.push(i); }
      for (const i of add) { d[i * 4] = ink[0]; d[i * 4 + 1] = ink[1]; d[i * 4 + 2] = ink[2]; d[i * 4 + 3] = 255; }
      lg.putImageData(id, 0, 0);
    }
    // dunne dingen (leuning) zonder buitenrand er overheen
    for (const b of ordered) if (b.overlay) drawBox(lg, b, M, base, fdz, px, ph, pz);
    g.drawImage(L, 0, 0);
    return { canvas: c, fw, fd, margin: M, height: maxH };
  }

  /**
   * Volgorde: A vóór B als A erachter staat (A.z1 <= B.z0) of eronder (A.h1 <= B.h0, op dezelfde plek), alleen als ze
   * elkaar op het scherm kunnen raken (x overlapt). Zo komt iets op een tafel (een laptop, een kookplaat) altijd bovenop.
   */
  function drawOrder(bs, rect) {
    const n = bs.length, E = 1e-6, key = (a, b) => (a.z1 - b.z1) || (a.h0 - b.h0);
    const R = bs.map(rect);
    const before = (a, b, ra, rb) => {
      if (!(ra[0] < rb[1] && rb[0] < ra[1] && ra[2] < rb[3] && rb[2] < ra[3])) return 0;   // raken elkaar niet op het scherm
      if (a.z1 <= b.z0 + E) return 1; if (b.z1 <= a.z0 + E) return -1;
      if (a.h1 <= b.h0 + E) return 1; if (b.h1 <= a.h0 + E) return -1;
      return 0;
    };
    const out = [], indeg = new Array(n).fill(0), adj = bs.map(() => []);
    for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) { const r = before(bs[i], bs[j], R[i], R[j]); if (r > 0) { adj[i].push(j); indeg[j]++; } else if (r < 0) { adj[j].push(i); indeg[i]++; } }
    const done = new Array(n).fill(false);
    for (let k = 0; k < n; k++) {
      let best = -1;
      for (let i = 0; i < n; i++) if (!done[i] && indeg[i] === 0 && (best < 0 || key(bs[i], bs[best]) < 0)) best = i;
      if (best < 0) for (let i = 0; i < n; i++) if (!done[i] && (best < 0 || key(bs[i], bs[best]) < 0)) best = i;   // kring: gewoon op volgorde
      done[best] = true; out.push(bs[best]); for (const j of adj[best]) indeg[j]--;
    }
    return out;
  }

  function drawBox(g, b, M, base, fd, px, ph, pz) {
    const x0 = M + px(b.x0), x1 = Math.max(x0 + 1, M + px(b.x1));
    const zb = pz(b.z0), zf = Math.max(zb, pz(b.z1));
    const h0 = ph(b.h0), h1 = Math.max(h0 + (b.thin ? 1 : 1), ph(b.h1));
    const yTopBack = base - (fd - zb) - h1, yTopFront = base - (fd - zf) - h1, yBot = base - (fd - zf) - h0;
    const col = b.c, top = b.top || shade(col, 0.18), front = col, out = b.o || shade(col, -OUT);
    const w = x1 - x0, ht = yTopFront - yTopBack, hf = yBot - yTopFront;
    if (w <= 0) return;
    if (b.ball) {
      const rx = w / 2, ry = rx, cx = (x0 + x1 - 1) / 2, bot = yBot - 1 - (b.lift || 0), top = Math.round(bot - 2 * ry + 1), cy = (top + bot) / 2;
      for (let y = top; y <= bot; y++) for (let x = x0; x < x1; x++) {
        const dx = (x - cx) / rx, dy = (y - cy) / ry, d2 = dx * dx + dy * dy; if (d2 > 1) continue;
        const edge = d2 > Math.pow(1 - 1.4 / Math.min(rx, ry), 2), lit = dx * -0.6 + dy * -0.8;
        g.fillStyle = edge && !b.noOutline ? out : lit > 0.45 ? shade(col, 0.35) : lit < -0.35 ? shade(col, -0.16) : col; g.fillRect(x, y, 1, 1);
      }
      if (b.draw) b.draw(g, { x0, x1, yTopBack, yTopFront, yBot });
      return;
    }
    const r = b.round ? Math.min(2, Math.floor(w / 3), Math.floor((ht + hf) / 3)) : 0;
    // rand (hele vorm), dan de vlakken erin
    const fillR = (x, y, ww, hh, color, rr) => {
      if (ww <= 0 || hh <= 0) return;
      g.fillStyle = color;
      if (!rr) { g.fillRect(x, y, ww, hh); return; }
      for (let i = 0; i < hh; i++) {
        const k = i < rr ? rr - i : i >= hh - rr ? i - (hh - rr - 1) : 0;
        g.fillRect(x + k, y + i, ww - 2 * k, 1);
      }
    };
    // doorzichtig (glas): alleen een rand, niet het hele vlak
    if (col.startsWith('rgba') && !b.noOutline) { g.fillStyle = out; g.fillRect(x0, yTopBack, w, 1); g.fillRect(x0, yBot - 1, w, 1); g.fillRect(x0, yTopBack, 1, ht + hf); g.fillRect(x1 - 1, yTopBack, 1, ht + hf); }
    else if (!b.noOutline) fillR(x0, yTopBack, w, ht + hf, out, r);
    const ins = b.noOutline ? 0 : 1;
    // bovenvlak
    if (ht > 0) fillR(x0 + ins, yTopBack + ins, w - 2 * ins, Math.max(0, ht - (hf > 0 ? 0 : 2 * ins)), top, Math.max(0, r - 1));
    // voorvlak
    if (hf > 0) {
      fillR(x0 + ins, yTopFront + (ht > 0 ? 0 : ins), w - 2 * ins, hf - ins - (ht > 0 ? 0 : ins), front, Math.max(0, r - 1));
      // lichte voorrand van het bovenvlak, donkere onderrand
      if (b.plain) { if (b.draw) b.draw(g, { x0, x1, yTopBack, yTopFront, yBot }); return; }
      if (ht > 0 && w > 2) { g.fillStyle = shade(top, 0.25); g.fillRect(x0 + ins + (r ? 1 : 0), yTopFront - 1, w - 2 * ins - (r ? 2 : 0), 1); }
      if (hf > 2 && w > 2) { g.fillStyle = shade(front, -0.18); g.fillRect(x0 + ins + (r ? 1 : 0), yBot - 1 - ins, w - 2 * ins - (r ? 2 : 0), 1); }
      // volume: rechterkant van het voorvlak iets donkerder
      if (w > 4 && hf > 2 && !front.startsWith('rgba')) { g.fillStyle = shade(front, -0.12); g.fillRect(x1 - ins - 1 - (r ? 1 : 0), yTopFront + 1, 1, hf - 2 - ins); }
      // houtnerf op houten vlakken: korte donkere streepjes, vast patroon
      if ((b.tex === 'wood' || WOODS.has(col)) && w > 4 && hf > 3) {
        g.fillStyle = shade(front, -0.1);
        for (let y = yTopFront + 2; y < yBot - 2; y += 2) for (let x = x0 + 2; x < x1 - 3; x++) {
          const hsh = ((x * 73856093) ^ (y * 19349663)) >>> 0; if (hsh % 11 === 0) g.fillRect(x, y, Math.min(3, x1 - 3 - x), 1); }
      }
    }
    if (b.draw) b.draw(g, { x0, x1, yTopBack, yTopFront, yBot });   // extra pixels (bv. een vlammetje), alleen van voren
  }

  // ---------- bouwstenen ----------
  const B = (x0, x1, z0, z1, h0, h1, c, more) => ({ x0, x1, z0, z1, h0, h1, c, ...(more || {}) });
  /** Iets op het voorvlak (deurtje, knop, scherm): heel dun blok net voor de voorkant. */
  const front = (W, D, x0, x1, h0, h1, c, more) => B(x0, x1, D - 0.02, D, h0, h1, c, more);
  const legs4 = (W, D, inset, h, c, t = 0.05) => [
    B(inset, inset + t, inset, inset + t, 0, h, c), B(W - inset - t, W - inset, inset, inset + t, 0, h, c),
    B(inset, inset + t, D - inset - t, D - inset, 0, h, c), B(W - inset - t, W - inset, D - inset - t, D - inset, 0, h, c)];
  const WOODS = new Set(['#a86a3c', '#7a4a2a', '#c98a52', '#8a5a35', '#b07a45', '#c8a070']);
  const WOOD = '#a86a3c', WOODD = '#7a4a2a', WOODL = '#c98a52', WHITE = '#ecebe6', STEEL = '#b8c0c8', BLACK = '#2a2830';

  /** Stoel (zitting naar voren = +z), op plek (cx, cz), gedraaid: 'S' rug achter, 'N' rug voor, 'W'/'E'. */
  function chairAt(cx, cz, face, c = '#8a5a35', size = 0.44) {
    const s = size / 2, p = [];
    // poten, zitting, rug; daarna draaien om (cx, cz)
    const raw = [...legs4(size, size, 0.02, 0.44, shade(c, -0.3), 0.04), B(0, size, 0, size, 0.42, 0.47, c), B(0, size, 0, 0.06, 0.47, 0.92, c)];
    for (const b of raw) {
      let r;
      if (face === 'S') r = b;                                                     // rug aan de achterkant
      else if (face === 'N') r = { ...b, x0: size - b.x1, x1: size - b.x0, z0: size - b.z1, z1: size - b.z0 };
      else if (face === 'E') r = { ...b, x0: b.z0, x1: b.z1, z0: size - b.x1, z1: size - b.x0 };   // rug links
      else r = { ...b, x0: size - b.z1, x1: size - b.z0, z0: b.x0, z1: b.x1 };                     // W: rug rechts
      p.push({ ...r, x0: r.x0 + cx - s, x1: r.x1 + cx - s, z0: r.z0 + cz - s, z1: r.z1 + cz - s });
    }
    return p;
  }

  // ---------- de meubels ----------
  const BLUE = '#4f7aa8', BLUED = '#3d6390';
  const F = {
    sofa: (W, D) => {
      const p = [B(0, W, 0.18, D, 0, 0.22, BLUED), B(0, W, 0, 0.24, 0, 0.86, BLUED), B(0, 0.2, 0.18, D, 0, 0.6, BLUED), B(W - 0.2, W, 0.18, D, 0, 0.6, BLUED)];
      const n = W > 1.8 ? 3 : 2, cw = (W - 0.4) / n;
      for (let i = 0; i < n; i++) { const a = 0.2 + i * cw + 0.01, b = 0.2 + (i + 1) * cw - 0.01;
        p.push(B(a, b, 0.24, D - 0.02, 0.22, 0.44, BLUE, { round: true }), B(a, b, 0.2, 0.36, 0.44, 0.8, BLUE, { round: true })); }
      p.push(B(0.3, 0.55, 0.36, 0.46, 0.44, 0.66, '#e8b84a', { round: true }), B(W - 0.55, W - 0.3, 0.36, 0.46, 0.44, 0.66, '#8fc46a', { round: true }));
      return p;
    },
    corner: (W, D) => {
      const sd = Math.min(D, 0.95), p = [];
      p.push(B(0, W, 0, 0.24, 0, 0.86, BLUED), B(0, 0.24, 0, D, 0, 0.86, BLUED));                 // rug achter en links
      p.push(B(0.24, W, 0.24, sd, 0, 0.22, BLUED), B(0.24, sd, sd, D, 0, 0.22, BLUED));             // onderstel
      p.push(B(W - 0.2, W, 0.18, sd, 0, 0.6, BLUED), B(0.24, sd, D - 0.2, D, 0, 0.6, BLUED));       // armleuningen
      const n = Math.max(1, Math.round((W - 0.44 - sd) / 0.7)), cw = (W - 0.2 - sd) / n;
      p.push(B(0.24, sd, 0.24, sd, 0.22, 0.44, BLUE, { round: true }));                             // hoek
      for (let i = 0; i < n; i++) p.push(B(sd + i * cw + 0.01, sd + (i + 1) * cw - 0.01, 0.24, sd - 0.02, 0.22, 0.44, BLUE, { round: true }));
      const m = Math.max(1, Math.round((D - 0.2 - sd) / 0.7)), ch = (D - 0.2 - sd) / m;
      for (let i = 0; i < m; i++) p.push(B(0.24, sd - 0.02, sd + i * ch + 0.01, sd + (i + 1) * ch - 0.01, 0.22, 0.44, BLUE, { round: true }));
      p.push(B(0.24, W - 0.2, 0.2, 0.36, 0.44, 0.8, BLUE, { round: true }), B(0.2, 0.36, 0.36, D - 0.2, 0.44, 0.8, BLUE, { round: true }));
      p.push(B(0.45, 0.7, 0.36, 0.46, 0.44, 0.66, '#e8b84a', { round: true }), B(W - 0.55, W - 0.3, 0.36, 0.46, 0.44, 0.66, '#8fc46a', { round: true }),
        B(0.36, 0.46, D - 0.6, D - 0.35, 0.44, 0.66, '#e07a6a', { round: true }));
      return p;
    },
    armchair: (W, D) => {
      const C = '#c0604a', CD = '#9c4a38';
      return [B(0, W, 0.18, D, 0, 0.22, CD), B(0, W, 0, 0.24, 0, 0.88, CD), B(0, 0.18, 0.18, D, 0, 0.6, CD), B(W - 0.18, W, 0.18, D, 0, 0.6, CD),
        B(0.19, W - 0.19, 0.24, D - 0.02, 0.22, 0.44, C, { round: true }), B(0.19, W - 0.19, 0.2, 0.36, 0.44, 0.82, C, { round: true })];
    },
    coffee: (W, D) => [...legs4(W, D, 0.04, 0.36, WOODD), B(0, W, 0, D, 0.36, 0.42, WOOD),
      B(0.12, 0.42, 0.12, 0.34, 0.42, 0.44, '#5a8ac0'), B(W - 0.25, W - 0.15, D - 0.3, D - 0.2, 0.42, 0.52, '#f2ead8', { round: true })],
    dining: (W, D) => {
      const tz0 = 0.35, tz1 = D - 0.35, p = [...legs4(W - 0.2, tz1 - tz0, 0.04, 0.72, WOODD).map(b => ({ ...b, x0: b.x0 + 0.1, x1: b.x1 + 0.1, z0: b.z0 + tz0, z1: b.z1 + tz0 })),
        B(0.1, W - 0.1, tz0, tz1, 0.72, 0.77, '#b07a45'), B(W / 2 - 0.1, W / 2 + 0.1, D / 2 - 0.1, D / 2 + 0.1, 0.77, 0.9, '#f2ead8', { round: true })];
      for (const fx of [0.27, 0.73]) { p.push(...chairAt(W * fx, 0.24, 'S'), ...chairAt(W * fx, D - 0.24, 'N')); }
      return p;
    },
    plant: (W, D) => [B(W * 0.25, W * 0.75, D * 0.25, D * 0.75, 0, 0.3, '#c0704a', { round: true }),
      B(W * 0.1, W * 0.55, D * 0.2, D * 0.6, 0.3, 0.62, '#5a9a4a', { round: true }), B(W * 0.45, W * 0.9, D * 0.25, D * 0.65, 0.3, 0.55, '#4e8a3c', { round: true }),
      B(W * 0.25, W * 0.75, D * 0.35, D * 0.85, 0.3, 0.75, '#6ab05a', { round: true })],
    rug: (W, D) => [B(0, W, 0, D, 0, 0.01, '#5a9a5a', { top: '#5a9a5a', noShadow: true }), B(0.12, W - 0.12, 0.12, D - 0.12, 0.01, 0.02, '#4e8a4e', { top: '#4e8a4e', noOutline: true }),
      B(0.2, W - 0.2, 0.2, D - 0.2, 0.02, 0.03, '#62a862', { top: '#62a862', noOutline: true })],
    cabinet: (W, D) => {
      const C = '#8a5a35', p = [B(0, W, 0, D, 0.04, 0.78, C), B(0.03, W - 0.03, 0.03, D - 0.03, 0, 0.04, shade(C, -0.4))];
      const n = W > 1 ? 3 : 2; for (let i = 0; i < n; i++) { const a = W / n * i + 0.03, b = W / n * (i + 1) - 0.03;
        p.push(front(W, D, a, b, 0.1, 0.72, shade(C, 0.08)), front(W, D, i % 2 ? a + 0.05 : b - 0.08, i % 2 ? a + 0.08 : b - 0.05, 0.4, 0.5, '#e8c84a')); }
      p.push(B(0.15, 0.4, 0.1, 0.3, 0.78, 0.82, '#5a8ac0'), B(W - 0.3, W - 0.18, 0.12, 0.26, 0.78, 0.98, '#e07a6a', { round: true }));
      return p;
    },
    bed: (W, D) => [B(0, W, 0, D, 0, 0.3, WOOD), B(0, W, 0, 0.08, 0, 0.72, WOODD), B(0.04, W - 0.04, 0.08, D - 0.04, 0.3, 0.5, '#f2efe8'),
      B(0.12, W / 2 - 0.04, 0.12, 0.5, 0.5, 0.6, '#ffffff', { round: true }), B(W / 2 + 0.04, W - 0.12, 0.12, 0.5, 0.5, 0.6, '#ffffff', { round: true }),
      B(0.02, W - 0.02, 0.62, D - 0.02, 0.3, 0.56, '#4f7aa8'), B(0.02, W - 0.02, 0.62, 0.72, 0.3, 0.57, '#77a0c9')],
    singlebed: (W, D) => [B(0, W, 0, D, 0, 0.3, WOOD), B(0, W, 0, 0.08, 0, 0.68, WOODD), B(0.04, W - 0.04, 0.08, D - 0.04, 0.3, 0.5, '#f2efe8'),
      B(0.12, W - 0.12, 0.12, 0.45, 0.5, 0.6, '#ffffff', { round: true }), B(0.02, W - 0.02, 0.58, D - 0.02, 0.3, 0.56, '#5a9a5a'), B(0.02, W - 0.02, 0.58, 0.68, 0.3, 0.57, '#7ab87a')],
    bookshelf: (W, D) => {
      const t = 0.04, p = [B(0, W, 0, 0.03, 0, 1.8, '#3a2418'), B(0, t, 0, D, 0, 1.8, WOODD), B(W - t, W, 0, D, 0, 1.8, WOODD), B(0, W, 0, D, 1.76, 1.8, WOODD), B(0, W, 0, D, 0, 0.06, WOODD)];
      const books = ['#c0396a', '#3f6fa8', '#e8b84a', '#4e8a3c', '#8a5bb0', '#e07a6a', '#f2ead8'];
      for (let s = 0; s < 4; s++) {
        const y0 = 0.06 + s * 0.43; if (s > 0) p.push(B(t, W - t, 0.03, D, y0 - 0.03, y0, WOOD));
        let x = t + 0.02, k = s * 3;
        while (x < W - t - 0.06) { const bw = 0.05 + ((k * 7) % 3) * 0.02, bh = 0.26 + ((k * 5) % 3) * 0.05; if (x + bw > W - t - 0.02) break;
          p.push(B(x, x + bw, 0.05, D - 0.04, y0, y0 + bh, books[k % books.length], { noOutline: true })); x += bw + 0.008; k++; }
      }
      return p;
    },
    sidetable: (W, D) => [B(W * 0.4, W * 0.6, D * 0.4, D * 0.6, 0, 0.48, WOODD), B(0, W, 0, D, 0.48, 0.53, WOOD, { round: true }),
      B(W * 0.35, W * 0.6, D * 0.35, D * 0.6, 0.53, 0.68, '#5a8ac0', { round: true })],
    roundtable: (W, D) => {
      const p = [B(W / 2 - 0.08, W / 2 + 0.08, D / 2 - 0.08, D / 2 + 0.08, 0, 0.72, WOODD), B(W / 2 - 0.3, W / 2 + 0.3, D / 2 - 0.3, D / 2 + 0.3, 0, 0.04, WOODD, { round: true }),
        B(0.3, W - 0.3, 0.3, D - 0.3, 0.72, 0.77, '#b07a45', { round: true }), B(W / 2 - 0.1, W / 2 + 0.1, D / 2 - 0.1, D / 2 + 0.1, 0.77, 0.88, '#f2ead8', { round: true })];
      p.push(...chairAt(W / 2, 0.24, 'S'), ...chairAt(W / 2, D - 0.24, 'N'), ...chairAt(0.24, D / 2, 'E'), ...chairAt(W - 0.24, D / 2, 'W'));
      return p;
    },
    desk: (W, D) => {
      const dd = Math.min(0.7, D - 0.4), p = [B(0.02, 0.06, 0.04, dd - 0.04, 0, 0.72, WOODD), B(W - 0.5, W - 0.04, 0.04, dd - 0.04, 0, 0.72, WOOD),
        B(0, W, 0, dd, 0.72, 0.76, '#c8a070'), front(W, dd, W - 0.46, W - 0.08, 0.5, 0.68, WOODL), front(W, dd, W - 0.46, W - 0.08, 0.28, 0.46, WOODL),
        B(W * 0.3, W * 0.62, 0.1, 0.14, 0.82, 1.15, BLACK), B(W * 0.44, W * 0.48, 0.14, 0.2, 0.76, 0.82, BLACK),
        B(W * 0.32, W * 0.6, 0.15, 0.16, 0.85, 1.12, '#5a8ac0', { noOutline: true }), B(W * 0.32, W * 0.58, dd - 0.3, dd - 0.15, 0.76, 0.78, '#d8d8e0')];
      return [...p.map(b => b.z1 === dd && b.z0 === dd - 0.02 ? b : b), ...officeChair(W * 0.46, D - 0.3, 'N', 0.56)];
    },
    chair: (W, D) => chairAt(W / 2, D / 2, 'S', '#8a5a35', Math.min(W, D) - 0.04),
    wardrobe: (W, D) => {
      const C = '#8a5a35', p = [B(0, W, 0, D, 0.05, 2.0, C), B(0.03, W - 0.03, 0.03, D - 0.03, 0, 0.05, shade(C, -0.4)), B(-0.02 + 0.02, W, 0, D, 1.96, 2.04, WOODD)];
      const n = W > 1.3 ? 3 : 2; for (let i = 0; i < n; i++) { const a = W / n * i + 0.03, b = W / n * (i + 1) - 0.03;
        p.push(front(W, D, a, b, 0.12, 1.9, shade(C, 0.08)), front(W, D, i === n - 1 ? a + 0.05 : b - 0.08, i === n - 1 ? a + 0.08 : b - 0.05, 0.9, 1.15, '#e8c84a')); }
      return p;
    },
    kitchen: (W, D) => kitchenRun(W, D, [['cab', 0.6], ['sink', 0.8], ['cab', 0.4], ['hob', 0.6]]),
    fireplace: (W, D) => {
      const S = '#8a8a92', p = [B(0, W, 0, D, 0, 1.0, S), B(-0.0, W, 0, D, 1.0, 1.06, WOOD), front(W, D, W * 0.25, W * 0.75, 0.08, 0.6, '#2a1a1a')];
      p.push(front(W, D, W * 0.3, W * 0.7, 0.1, 0.22, '#e8642a', { noOutline: true }), front(W, D, W * 0.36, W * 0.5, 0.18, 0.36, '#f0a030', { noOutline: true }),
        front(W, D, W * 0.5, W * 0.62, 0.16, 0.3, '#ffd060', { noOutline: true }));
      for (let i = 0; i < 4; i++) p.push(front(W, D, 0.05 + i * W / 4, 0.05 + i * W / 4 + 0.02, 0.62, 0.95, shade(S, -0.15), { noOutline: true }));
      p.push(B(0.15, 0.3, 0.1, 0.3, 1.06, 1.25, '#e07a6a', { round: true }), B(W - 0.35, W - 0.2, 0.1, 0.3, 1.06, 1.2, '#5a8ac0', { round: true }));
      return p;
    },
    piano: (W, D) => [B(0, W, 0, 0.32, 0, 1.25, '#24222a'), B(0.04, 0.1, 0.32, D - 0.04, 0, 0.72, '#24222a'), B(W - 0.1, W - 0.04, 0.32, D - 0.04, 0, 0.72, '#24222a'),
      B(0.02, W - 0.02, 0.32, D - 0.18, 0.7, 0.76, '#24222a'), B(0.06, W - 0.06, 0.34, D - 0.2, 0.76, 0.77, '#f4f2ea', { noOutline: true,
        draw: (g, r) => { g.fillStyle = '#24222a'; for (let x = r.x0 + 2; x < r.x1 - 1; x += 3) g.fillRect(x, r.yTopBack, 1, Math.max(1, r.yTopFront - r.yTopBack - 1)); } }),
      B(W * 0.3, W * 0.7, D - 0.3, D, 0, 0.48, '#3a3640')],
    beanbag: (W, D) => [B(0.05, W - 0.05, 0.1, D - 0.05, 0, 0.32, '#d8743a', { round: true }), B(0.15, W - 0.12, 0.05, D * 0.55, 0.25, 0.62, '#e0844a', { round: true })],
    pouf: (W, D) => [B(0.02, W - 0.02, 0.02, D - 0.02, 0, 0.42, '#d8c8a8', { round: true, draw: (g, r) => { g.fillStyle = '#b8a888'; g.fillRect(Math.round((r.x0 + r.x1) / 2), r.yTopBack + 2, 1, r.yTopFront - r.yTopBack - 3); } })],
    fridge: (W, D) => [B(0, W, 0, D, 0, 1.85, WHITE), front(W, D, 0.03, W - 0.03, 0.62, 1.82, '#f6f6f2'), front(W, D, 0.03, W - 0.03, 0.04, 0.6, '#f6f6f2'),
      front(W, D, 0.06, 0.1, 1.2, 1.6, STEEL), front(W, D, 0.06, 0.1, 0.3, 0.52, STEEL)],
    stove: (W, D) => [B(0, W, 0, D, 0, 0.9, WHITE), ...hobTop(0.04, W - 0.04, 0.04, D - 0.04, 0.9), front(W, D, 0.06, W - 0.06, 0.1, 0.62, '#3a3a42'),
      front(W, D, 0.14, W - 0.14, 0.2, 0.5, '#e89040'), front(W, D, 0.06, W - 0.06, 0.7, 0.86, '#d8d8d2', { draw: knobs })],
    hob: (W, D) => kitchenRun(W, D, [['hob', W]]),
    oven: (W, D) => [B(0, W, 0, D, 0, 0.9, '#ecebe6'), B(0, W, 0, D, 0.86, 0.9, '#d8d0c0'), front(W, D, 0.05, W - 0.05, 0.08, 0.7, '#3a3a42'),
      front(W, D, 0.12, W - 0.12, 0.18, 0.55, '#e89040'), front(W, D, 0.08, W - 0.08, 0.6, 0.65, STEEL)],
    sink: (W, D) => kitchenRun(W, D, [['sink', W]]),
    dishwasher: (W, D) => [B(0, W, 0, D, 0, 0.88, '#e4e6ea'), B(0, W, 0, D, 0.86, 0.9, '#d8d0c0'), front(W, D, 0.03, W - 0.03, 0.06, 0.74, '#eef0f2'),
      front(W, D, 0.03, W - 0.03, 0.76, 0.84, '#9aa4ae', { draw: (g, r) => { g.fillStyle = '#5ac06a'; g.fillRect(r.x1 - 4, r.yTopFront + 1, 1, 1); } })],
    island: (W, D) => {
      const dd = D - 0.35, p = [B(0, W, 0, dd, 0, 0.88, '#ecebe6'), B(0, W, 0, dd + 0.1, 0.88, 0.93, '#d8d0c0'), ...hobTop(W / 2 - 0.3, W / 2 + 0.3, 0.12, dd - 0.12, 0.93)];
      for (const fx of [0.2, 0.5, 0.8]) p.push(...stool(W * fx, D - 0.15));
      return p;
    },
    nightstand: (W, D) => [B(0, W, 0, D, 0.05, 0.55, '#a86a3c'), ...legs4(W, D, 0.02, 0.06, WOODD), front(W, D, 0.04, W - 0.04, 0.32, 0.5, WOODL),
      front(W, D, W / 2 - 0.03, W / 2 + 0.03, 0.4, 0.43, '#e8c84a'), B(W * 0.25, W * 0.6, D * 0.2, D * 0.6, 0.55, 0.62, '#5a8ac0'), B(W * 0.6, W * 0.85, D * 0.3, D * 0.55, 0.55, 0.7, '#f2ead8', { round: true })],
    vanity: (W, D) => {
      const dd = Math.min(0.45, D - 0.3), p = [...legs4(W, dd, 0.03, 0.72, WOODD), B(0, W, 0, dd, 0.72, 0.77, '#f2ead8'), front(W, dd, 0.06, W - 0.06, 0.62, 0.72, '#e8dcc8'),
        B(W * 0.2, W * 0.8, 0.02, 0.06, 0.77, 1.45, '#d8c8b0', { round: true }), B(W * 0.24, W * 0.76, 0.06, 0.07, 0.82, 1.4, '#bcd8e8', { noOutline: true }),
        B(W * 0.65, W * 0.75, 0.15, 0.25, 0.77, 0.85, '#e07aa0', { round: true })];
      p.push(B(W / 2 - 0.2, W / 2 + 0.2, D - 0.32, D - 0.02, 0, 0.45, '#e8a0b8', { round: true }));
      return p;
    },
    computer: (W, D) => onDesk([B(W * 0.1, W * 0.62, 0.05, 0.09, 0.08, 0.42, BLACK), B(W * 0.12, W * 0.6, 0.09, 0.1, 0.1, 0.4, '#5a8ac0', { noOutline: true }),
      B(W * 0.34, W * 0.38, 0.09, 0.15, 0, 0.08, BLACK), B(W * 0.1, W * 0.62, D - 0.2, D - 0.05, 0, 0.02, '#d8d8e0'), B(W * 0.72, W * 0.9, 0.05, D - 0.08, 0, 0.42, '#24222a'),
      B(W * 0.89, W * 0.9, 0.08, D - 0.1, 0.05, 0.4, '#c060e0', { noOutline: true })]),
    laptop: (W, D) => onDesk([B(0.02, W - 0.02, D * 0.45, D - 0.02, 0, 0.02, '#9aa4ae'), B(0.02, W - 0.02, D * 0.38, D * 0.45, 0, 0.22, '#9aa4ae'),
      B(0.04, W - 0.04, D * 0.45, D * 0.46, 0.03, 0.2, '#5a8ac0', { noOutline: true })]),
    dualdesk: (W, D) => {
      const dd = Math.min(0.75, D - 0.45), p = [B(0.02, 0.06, 0.04, dd - 0.04, 0, 0.72, WOODD), B(W - 0.06, W - 0.02, 0.04, dd - 0.04, 0, 0.72, WOODD),
        B(0, W, 0, dd, 0.72, 0.76, '#c8a070')];
      for (const [a, b] of [[0.18, 0.62], [0.68, 1.12]].map(([a, b]) => [a * W / 1.6, b * W / 1.6])) p.push(B(a, b, 0.08, 0.12, 0.84, 1.16, BLACK), B(a + 0.02, b - 0.02, 0.12, 0.13, 0.86, 1.14, '#5a8ac0', { noOutline: true }),
        B((a + b) / 2 - 0.02, (a + b) / 2 + 0.02, 0.12, 0.18, 0.76, 0.84, BLACK));
      p.push(B(W * 0.3, W * 0.65, dd - 0.28, dd - 0.12, 0.76, 0.78, '#d8d8e0'), B(W - 0.32, W - 0.1, 0.1, dd - 0.1, 0.76, 1.2, '#24222a'),
        B(W - 0.11, W - 0.1, 0.12, dd - 0.12, 0.8, 1.15, '#c060e0', { noOutline: true }));
      return [...p, ...officeChair(W * 0.45, D - 0.32, 'N', 0.6)];
    },
    pctower: (W, D) => [B(0, W, 0, D, 0.02, 0.48, '#24222a'), front(W, D, 0.04, 0.07, 0.08, 0.44, '#c060e0', { noOutline: true }), front(W, D, W - 0.08, W - 0.05, 0.38, 0.42, '#5ac06a', { noOutline: true })],
    officechair: (W, D) => officeChair(W / 2, D / 2, 'S', Math.min(W, D)),
    gamingchair: (W, D) => officeChair(W / 2, D / 2, 'S', Math.min(W, D), true),
    coatrack: (W, D) => {
      const p = [B(0, W, 0, 0.04, 1.45, 1.6, WOODD), B(0, W, 0, D, 0, 0.42, WOOD), front(W, D, 0.04, W - 0.04, 0.05, 0.3, WOODL)];
      const coats = ['#a0303a', '#3f6fa8', '#5a5a62', '#c8a040'], n = Math.max(2, Math.floor(W / 0.3));
      for (let i = 0; i < n; i++) { const x = (i + 0.5) * W / n; p.push(B(x - 0.11, x + 0.11, 0.04, 0.3, 0.75, 1.48, coats[i % coats.length], { round: true })); }
      return p;
    },
    shoerack: (W, D) => {
      const p = [B(0, W, 0, D, 0, 0.5, WOOD), front(W, D, 0.04, W - 0.04, 0.04, 0.46, '#3a2418'), front(W, D, 0.04, W - 0.04, 0.24, 0.27, WOOD)];
      const sh = ['#c0396a', '#2a2830', '#e8e0d0', '#3f6fa8'];
      for (let r = 0; r < 2; r++) for (let x = 0.07, k = r; x < W - 0.16; x += 0.18, k++) p.push(B(x, x + 0.14, D - 0.2, D - 0.03, 0.04 + r * 0.23, 0.12 + r * 0.23, sh[k % 4], { noOutline: true }));
      p.push(B(0.1, 0.35, 0.05, 0.3, 0.5, 0.56, '#e8b84a'));
      return p;
    },
    stairs: (W, D) => {
      // eenvoudig: zes dichte treden die samen één vlak vormen (alleen de bovenkant van elke tree licht),
      // en één doorlopende lichte leuning die met de treden mee trapt, op een paar palen
      const n = 6, run = D / n, rise = 2.6 / n, RAIL = '#7a4a2a', p = [];
      for (let i = 0; i < n; i++) { const z1 = D - i * run, z0 = z1 - run, h = (i + 1) * rise, r = h + 0.9;
        p.push(B(0, W, z0, z1, 0, h, '#a06a38', { top: '#c8945a', noOutline: true, plain: true }));
        p.push(B(0, 0.05, z0, z1, r - 0.05, r, RAIL, { noOutline: true, plain: true, overlay: true }));                                   // leuning
        if (i > 0) p.push(B(0, 0.05, z1 - 0.05, z1, r - rise - 0.05, r, RAIL, { noOutline: true, plain: true, overlay: true }));          // trapje in de leuning
        if (i % 2 === 0 || i === n - 1) p.push(B(0, 0.05, z0 + run * 0.45, z0 + run * 0.55, h, r, RAIL, { noOutline: true, plain: true, overlay: true }));   // paal
      }
      return p;
    },
    // trap van voren/achteren: twaalf treden met stootbord en rode loper (de zijkant gebruikt de eenvoudige 'stairs')
    stairsFront: (W, D) => {
      const n = 12, run = D / n, rise = 2.6 / n, p = [];
      for (let i = 0; i < n; i++) { const z1 = D - i * run, z0 = z1 - run, h = (i + 1) * rise;
        p.push(B(0.08, W, z0, z1, h - 0.07, h, i % 2 ? '#b07a45' : '#a06a38', { top: i % 2 ? '#c8945a' : '#bc8850' }));
        p.push(B(0.08, W, z1 - 0.03, z1, h - rise, h - 0.07, '#8a5a35', { noOutline: true }));
        p.push(B(0.3, W - 0.22, z0, z1 - run * 0.35, h, h + 0.01, '#a0303a', { noOutline: true }));
        p.push(B(0, 0.08, z0, z1, Math.max(0, h - rise - 0.25), h + 0.06, WOODD));
        p.push(B(0, 0.06, z0, z1, h + 0.86, h + 0.92, WOOD));
        if (i % 4 === 0 || i === n - 1) p.push(B(0, 0.06, z0 + run * 0.35, z0 + run * 0.65, h, h + 0.88, WOODD));
      }
      return p;
    },
    // mobiele airco: kast met rooster bovenop, bedieningsstrook voor, rooster opzij en de slang achter
    aircoMobile: (W, D, dark) => {
      const C = dark ? '#3a3a42' : '#ecebe6', G = dark ? '#24222a' : '#b8bcc4';
      return [B(0.02, W - 0.02, 0.02, D - 0.02, 0, 0.04, '#5a5a62'), B(0, W, 0, D, 0.04, 0.76, C, { round: true }),
        B(0.06, W - 0.06, 0.06, D - 0.12, 0.76, 0.77, G, { noOutline: true }),
        front(W, D, 0.06, W - 0.06, 0.62, 0.72, dark ? '#24222a' : '#d8dade', { draw: (g, r) => { g.fillStyle = '#5ac06a'; g.fillRect(r.x1 - 4, r.yTopFront + 1, 1, 1); } }),
        front(W, D, 0.08, W - 0.08, 0.2, 0.52, G, { noOutline: true, draw: (g, r) => { g.fillStyle = dark ? '#3a3a42' : '#9aa0a8'; for (let y = r.yTopFront + 1; y < r.yBot - 1; y += 2) g.fillRect(r.x0 + 1, y, r.x1 - r.x0 - 2, 1); } }),
        B(W / 2 - 0.06, W / 2 + 0.06, -0.0, 0.05, 0.3, 0.42, '#9aa0a8', { round: true })];
    },
    tvstand: (W, D) => {
      const tw = W - 0.4, th = tw * 0.56;
      return [B(0, W, 0, D, 0.06, 0.48, '#6b4430'), B(0.04, W - 0.04, 0.04, D - 0.04, 0, 0.06, '#3a2418'),
        front(W, D, 0.06, W / 2 - 0.02, 0.12, 0.42, '#86583e'), front(W, D, W / 2 + 0.02, W - 0.06, 0.12, 0.42, '#86583e'),
        B(W / 2 - 0.12, W / 2 + 0.12, D / 2 - 0.08, D / 2 + 0.04, 0.48, 0.52, '#24222a'), B(W / 2 - 0.03, W / 2 + 0.03, D / 2 - 0.04, D / 2, 0.52, 0.6, '#24222a'),
        B(0.2, W - 0.2, D / 2 - 0.05, D / 2, 0.58, 0.58 + th, '#0c0c10'), B(0.23, W - 0.23, D / 2, D / 2 + 0.005, 0.61, 0.55 + th, '#1c2030', { noOutline: true, onlyView: 'S' })];
    },
    toilet: (W, D) => [B(0.04, W - 0.04, 0, 0.2, 0, 0.82, '#f2f2f0'), B(0.06, W - 0.06, 0.18, D - 0.05, 0, 0.38, '#f6f6f4', { round: true }),
      B(0.05, W - 0.05, 0.16, D - 0.03, 0.38, 0.42, '#e8e8e4', { round: true }), B(0.12, W - 0.12, 0.02, 0.1, 0.82, 0.85, STEEL)],
    washbasin: (W, D) => [B(0.04, W - 0.04, 0, D - 0.06, 0.3, 0.8, WOOD), front(W, D - 0.06, 0.07, W - 0.07, 0.34, 0.76, WOODL), B(0, W, 0, D, 0.8, 0.87, '#f6f6f4', {
      draw: (g, r) => { g.fillStyle = '#bcd8e8'; g.fillRect(r.x0 + 3, r.yTopBack + 2, r.x1 - r.x0 - 6, Math.max(1, r.yTopFront - r.yTopBack - 3)); } }),
      B(W / 2 - 0.02, W / 2 + 0.02, 0.02, 0.08, 0.87, 1.0, STEEL), B(W * 0.12, W * 0.88, 0, 0.03, 1.05, 1.7, '#d8e8f0')],
    shower: (W, D) => {
      const G = 'rgba(190,225,245,.12)', GO = 'rgba(110,150,175,.75)', GT = 'rgba(215,238,250,.15)';
      const tiles = (g, r) => { g.fillStyle = '#c8d4dc'; for (let y = r.yTopFront + 3; y < r.yBot - 1; y += 4) g.fillRect(r.x0 + 1, y, r.x1 - r.x0 - 2, 1);
        for (let x = r.x0 + 4; x < r.x1 - 1; x += 4) g.fillRect(x, r.yTopFront + 1, 1, r.yBot - r.yTopFront - 2); };
      return [B(0, W, 0, D, 0, 0.06, '#e8eef2'), B(0.05, W - 0.05, 0.05, D - 0.05, 0.06, 0.065, '#d0dce4', { top: '#d0dce4', noOutline: true }),
        B(W / 2 - 0.04, W / 2 + 0.04, D / 2 - 0.04, D / 2 + 0.04, 0.065, 0.07, '#8a96a0', { top: '#8a96a0', noOutline: true }),
        B(0, W, 0, 0.05, 0, 2.05, '#e8f0f4', { draw: tiles }), B(0, 0.05, 0.05, D, 0, 2.05, '#e8f0f4'),                 // tegelwanden achter en links
        B(W - 0.2, W - 0.16, 0.05, 0.09, 1.0, 1.95, STEEL), B(W - 0.3, W - 0.08, 0.06, 0.22, 1.92, 1.96, STEEL),     // stang en douchekop
        B(W - 0.26, W - 0.12, 0.08, 0.2, 1.88, 1.92, '#9aa4ae', { noOutline: true }), B(W - 0.25, W - 0.19, 0.05, 0.09, 0.9, 1.0, STEEL),
        B(W - 0.04, W, 0.05, D, 0.06, 2.0, G, { o: GO, top: GT }), B(0.05, W, D - 0.04, D, 0.06, 2.0, G, { o: GO, top: GT }),          // glazen wanden
        B(W - 0.04, W, D - 0.04, D, 0.06, 2.0, STEEL, { noOutline: true }), B(0.05, W, D - 0.04, D, 1.96, 2.0, STEEL, { noOutline: true }),
        B(W - 0.04, W, 0.05, D, 1.96, 2.0, STEEL, { noOutline: true }),
        B(0.12, 0.2, D - 0.05, D - 0.04, 0.4, 1.6, 'rgba(255,255,255,.55)', { noOutline: true })];                   // glans op het glas
    },
    bath: (W, D) => {
      const R = 0.08, Wt = '#f6f6f4', H = 0.58;
      return [B(R, W - R, R, D - R, 0, 0.3, '#e4e4e0', { noOutline: true }),                                          // bodem van de kuip
        B(R, W - R, R, D - R, 0.3, 0.44, '#6aaad8', { top: '#8ec6ea', noOutline: true }),                             // water
        B(0, W, 0, R, 0, H, Wt), B(0, W, D - R, D, 0, H, '#ecebe6', { top: Wt }),                                     // achter- en voorwand
        B(0, R + 0.06, R, D - R, 0, H, Wt), B(W - R, W, R, D - R, 0, H, Wt),                                          // zijwanden
        B(R + 0.08, R + 0.14, 0.01, 0.05, H, 0.74, STEEL), B(R + 0.08, R + 0.14, 0.05, 0.15, 0.7, 0.74, STEEL),      // kraan met uitloop
        B(W - R - 0.2, W - R - 0.06, 0.02, 0.06, H, 0.62, '#e8b84a', { noOutline: true }),                             // zeepje op de rand
        front(W, D, 0.06, W - 0.06, 0.06, 0.5, '#f2f2ee')];
    },
    washer: (W, D) => laundry(W, D, false),
    dryer: (W, D) => laundry(W, D, true),
    towelrail: (W, D) => [B(0.02, 0.06, 0, 0.05, 0.25, 1.2, STEEL), B(W - 0.06, W - 0.02, 0, 0.05, 0.25, 1.2, STEEL),
      B(0, W, 0.04, 0.08, 1.1, 1.14, STEEL), B(0, W, 0.04, 0.08, 0.72, 0.76, STEEL), B(0, W, 0.04, 0.08, 0.4, 0.44, STEEL),
      B(W * 0.15, W * 0.85, 0.08, 0.12, 0.55, 1.15, '#c86a5a', { top: '#e07a6a' }), B(W * 0.15, W * 0.85, 0.0, 0.04, 0.8, 1.15, '#a85a4a'),   // handdoek over de stang
      B(W * 0.15, W * 0.85, 0.08, 0.12, 0.6, 0.64, '#f2ead8', { noOutline: true })],
    litterbox: (W, D) => {
      // ZEDAR K1200 (vooraanzicht): witte bol, groot rond blauwgrijs venster met een witte geribbelde rand,
      // witte ovale voet met een donkere onderrand en een rij knopjes
      const m = W / 2, s = Math.min(W, D), zc = D / 2 + 0.01, r = s * 0.47;
      return [B(0.02, W - 0.02, 0.06, D - 0.02, 0, 0.04, '#3a3a42', { round: true }),
        B(0.03, W - 0.03, 0.06, D - 0.03, 0.04, 0.11, '#f4f4f0', { round: true }),
        B(m - r, m + r, zc - r, zc + r, 0.1, 0.1 + 2 * r, '#f6f6f2', { ball: true }),
        B(m - r * 0.78, m + r * 0.78, zc + r - 0.01, zc + r, 0.16, 0.16 + r * 1.56, '#d6d8dc', { ball: true, onlyView: 'S' }),
        B(m - r * 0.64, m + r * 0.64, zc + r, zc + r + 0.005, 0.19, 0.19 + r * 1.28, '#8f9ab2', { ball: true, noOutline: true, onlyView: 'S' }),
        B(m - r * 0.5, m + r * 0.5, zc + r + 0.005, zc + r + 0.008, 0.2, 0.3, '#6f7a92', { noOutline: true, onlyView: 'S' }),      // bodem van het venster
        B(m - 0.11, m - 0.09, D - 0.03, D - 0.02, 0.06, 0.08, '#5a5a62', { noOutline: true, plain: true, onlyView: 'S' }), B(m - 0.05, m + 0.05, D - 0.03, D - 0.02, 0.06, 0.08, '#2a2830', { noOutline: true, plain: true, onlyView: 'S' }),
        B(m + 0.09, m + 0.11, D - 0.03, D - 0.02, 0.06, 0.08, '#5a5a62', { noOutline: true, plain: true, onlyView: 'S' })];
    },
  };

  // ---------- hulpjes voor meubels ----------
  /** Kookplaat: zwart glas met vier pitten, plat op het werkblad (hoogte h). */
  function hobTop(x0, x1, z0, z1, h) {
    const p = [B(x0, x1, z0, z1, h, h + 0.012, BLACK, { top: '#2e2c36' })], mx = (x0 + x1) / 2, mz = (z0 + z1) / 2, rx = (x1 - x0) / 4, rz = (z1 - z0) / 4;
    for (const [fx, fz] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) p.push(B(mx + fx * rx - 0.05, mx + fx * rx + 0.05, mz + fz * rz - 0.05, mz + fz * rz + 0.05, h + 0.012, h + 0.016, '#5a5a66', { top: '#6a6a76', noOutline: true }));
    return p;
  }
  function hobDraw(g, r) { g.fillStyle = '#5a5a62'; const cx = (r.x0 + r.x1) / 2, cy = (r.yTopBack + r.yTopFront) / 2; for (const [dx, dy] of [[-3, -2], [2, -2], [-3, 2], [2, 2]]) g.fillRect(Math.round(cx + dx), Math.round(cy + dy), 2, 1); }
  function hobDraw2(g, r) { g.fillStyle = BLACK; const w = Math.min(10, r.x1 - r.x0 - 4), x = Math.round((r.x0 + r.x1) / 2 - w / 2), h = Math.max(2, r.yTopFront - r.yTopBack - 6); g.fillRect(x, r.yTopBack + 3, w, h);
    g.fillStyle = '#5a5a62'; g.fillRect(x + 1, r.yTopBack + 4, 2, 1); g.fillRect(x + w - 3, r.yTopBack + 4, 2, 1); }
  function knobs(g, r) { g.fillStyle = BLACK; for (let x = r.x0 + 2; x < r.x1 - 1; x += 3) g.fillRect(x, r.yTopFront + 1, 1, 1); }
  function onDesk(parts) { return parts.map(b => ({ ...b, h0: b.h0 + 0.76, h1: b.h1 + 0.76 })); }
  function stool(cx, cz) { return [B(cx - 0.03, cx + 0.03, cz - 0.03, cz + 0.03, 0, 0.66, STEEL), B(cx - 0.15, cx + 0.15, cz - 0.15, cz + 0.15, 0.66, 0.72, '#7a4a2a', { round: true })]; }
  function laundry(W, D, dryer) {
    return [B(0, W, 0, D, 0, 0.86, '#f2f2f0'), front(W, D, 0.03, W - 0.03, 0.72, 0.83, '#dcdcd8', { draw: (g, r) => { g.fillStyle = '#5a8ac0'; g.fillRect(r.x0 + 2, r.yTopFront + 1, 3, 1); g.fillStyle = BLACK; g.fillRect(r.x1 - 4, r.yTopFront + 1, 2, 1); } }),
      front(W, D, W * 0.18, W * 0.82, 0.12, 0.64, '#9aa4ae', { round: true }), front(W, D, W * 0.26, W * 0.74, 0.18, 0.58, dryer ? '#5a5a62' : '#6a9ac8', { round: true, noOutline: true })];
  }
  /** Keukenblok: kastjes met werkblad, met spoelbak (sink) en kookplaat (hob) op het blad. */
  function kitchenRun(W, D, segs) {
    const tot = segs.reduce((a, s) => a + s[1], 0), f = W / tot, p = [B(0, W, 0, D, 0.08, 0.88, '#ecebe6'), B(0.03, W - 0.03, 0.03, D - 0.06, 0, 0.08, '#5a5a62'), B(0, W, 0, D, 0.88, 0.93, '#d8d0c0')];
    let x = 0;
    for (const [k, w0] of segs) {
      const w = w0 * f, a = x + 0.02, b = x + w - 0.02;
      p.push(front(W, D, a, b, 0.12, 0.84, '#f4f4f0'), front(W, D, b - 0.07, b - 0.04, 0.62, 0.78, STEEL));
      if (k === 'sink') { const m = (a + b) / 2;
        p.push(B(a + 0.06, b - 0.06, 0.1, D - 0.1, 0.93, 0.945, '#c8d0d8', { top: '#c8d0d8' }), B(a + 0.1, b - 0.1, 0.14, D - 0.14, 0.945, 0.95, '#5a6a78', { top: '#5a6a78', noOutline: true }),
          B(m - 0.03, m + 0.03, 0.03, 0.09, 0.93, 1.2, STEEL), B(m - 0.025, m + 0.025, 0.09, 0.24, 1.15, 1.2, STEEL)); }
      if (k === 'hob') p.push(...hobTop(a + 0.05, b - 0.05, 0.07, D - 0.09, 0.93));
      x += w;
    }
    return p;
  }
  /** Bureaustoel (of gamestoel) op (cx, cz), kijkend naar 'S' (rug achter) of 'N' (rug naar de kijker, aan een bureau). */
  function officeChair(cx, cz, face, size, gaming) {
    const s = size / 2, C = gaming ? '#24222a' : '#3a3a48', A = gaming ? '#c0303a' : '#5a5a6a';
    let raw = [B(s - 0.25, s + 0.25, s - 0.04, s + 0.04, 0, 0.04, BLACK), B(s - 0.04, s + 0.04, s - 0.25, s + 0.25, 0, 0.04, BLACK),
      B(s - 0.03, s + 0.03, s - 0.03, s + 0.03, 0.04, 0.44, STEEL), B(s - 0.24, s + 0.24, s - 0.2, s + 0.24, 0.44, 0.52, C, { round: true }),
      B(s - 0.22, s + 0.22, s - 0.26, s - 0.18, 0.52, gaming ? 1.32 : 1.05, C, { round: true })];
    if (gaming) raw.push(B(s - 0.08, s - 0.04, s - 0.27, s - 0.26, 0.6, 1.28, A, { noOutline: true }), B(s + 0.04, s + 0.08, s - 0.27, s - 0.26, 0.6, 1.28, A, { noOutline: true }));
    raw.push(B(s - 0.27, s - 0.23, s - 0.15, s + 0.15, 0.52, 0.66, A), B(s + 0.23, s + 0.27, s - 0.15, s + 0.15, 0.52, 0.66, A));
    // de rugleuning heeft zijn "voorvlak" aan de achterkant (z klein); bij N kijken we op de achterkant van de rug
    if (face === 'N') raw = raw.map(b => ({ ...b, x0: size - b.x1, x1: size - b.x0, z0: size - b.z1, z1: size - b.z0 }));
    return raw.map(b => ({ ...b, x0: b.x0 + cx - s, x1: b.x1 + cx - s, z0: b.z0 + cz - s, z1: b.z1 + cz - s }));
  }

  const FRONT_ONLY = new Set(['shower']);
  F.aircoMobileZwart = (W, D) => F.aircoMobile(W, D, true);
  /** Een meubel tekenen in een richting; sommige meubels hebben alleen een voorkant (eventueel gespiegeld). */
  function renderItem(k, W, D, v, opt = {}) {
    if (!F[k]) return null;
    if (opt.flipX) { const f0 = F[k], f = (w, d) => f0(w, d).map(b => ({ ...b, x0: w - b.x1, x1: w - b.x0 })); return renderParts(f, k, W, D, v, opt); }
    return renderParts(F[k], k, W, D, v, opt);
  }
  function renderParts(Fk, k, W, D, v, opt) {
    if (k === 'stairs' && (v === 'S' || v === 'N')) return render(F.stairsFront(W, D), W, D, v, opt);
    if (!FRONT_ONLY.has(k)) return render(Fk(W, D), W, D, v, opt);
    const side = v === 'W' || v === 'E', w = side ? D : W, d = side ? W : D, r = render(Fk(w, d), w, d, 'S', opt);
    if (v === 'S' || v === 'W') return r;
    const c = document.createElement('canvas'); c.width = r.canvas.width; c.height = r.canvas.height; const g = c.getContext('2d');
    g.translate(c.width, 0); g.scale(-1, 1); g.drawImage(r.canvas, 0, 0);
    return { ...r, canvas: c };
  }
  root.Furniture34 = { PPM, HF, render, renderItem, F, shade };
})(typeof window !== 'undefined' ? window : globalThis);
