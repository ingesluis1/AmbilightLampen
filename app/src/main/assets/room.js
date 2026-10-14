/*
 * Tekenmotor voor de kamer in pixel-art-stijl (1 meter = 16 pixels, daarna scherp opgeschaald).
 * Wordt gebruikt door het tv-scherm (tv.html) en de telefoon-editor (index.html).
 */
const Room = (() => {
  const PPM = 16;

  const ITEMS = {
    sofa:     { name: 'Bank',       w: 2.2, d: 0.9 },
    corner:   { name: 'Hoekbank',   w: 2.6, d: 2.0 },
    armchair: { name: 'Fauteuil',   w: 0.9, d: 0.9 },
    coffee:   { name: 'Salontafel', w: 1.0, d: 0.55 },
    dining:   { name: 'Eettafel',   w: 1.6, d: 1.5 },
    plant:    { name: 'Plant',      w: 0.5, d: 0.5 },
    rug:      { name: 'Kleed',      w: 2.4, d: 1.6 },
    cabinet:  { name: 'Kast',       w: 1.2, d: 0.45 },
    bed:      { name: 'Bed',        w: 1.6, d: 2.0 },
  };

  function hash(x, y, k) {
    let h = (x * 374761393 + y * 668265263 + k * 1442695041) | 0;
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    return ((h ^ (h >>> 16)) & 1023) / 1023;
  }
  const hex = c => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
  const mul = (c, f) => c.map(v => Math.max(0, Math.min(255, Math.round(v * f))));

  function isFloor(L, x, y) { const c = x >= 0 && y >= 0 && x < L.w && y < L.h ? (L.floor[y] || '')[x] : undefined; return c !== undefined && c !== '0'; }
  function mat(L, x, y) { return (L.floor[y] || '')[x] || '1'; }

  /** Vloersoorten: code in de floor-rijen, naam, en kleur per pixel. */
  const FLOORS = [
    { code: '1', name: 'Eiken' }, { code: '2', name: 'Licht hout' }, { code: '3', name: 'Donker hout' },
    { code: '7', name: 'Parket' }, { code: '4', name: 'Blauwe tegels' }, { code: '8', name: 'Zwart-wit' },
    { code: '5', name: 'Steen' }, { code: '6', name: 'Tapijt' },
  ];
  const PLANKS = {
    '1': { c: ['#c58b55', '#bb804c', '#c9935e', '#b97d4a'], line: '#86532f', seam: '#94603a' },
    '2': { c: ['#e2bb84', '#d9b078', '#e8c491', '#d5aa70'], line: '#a87e4c', seam: '#b88c58' },
    '3': { c: ['#7e4d30', '#73452b', '#865335', '#6c4128'], line: '#45261a', seam: '#55301f' },
  };
  const hexCache = {};
  const H = c => hexCache[c] || (hexCache[c] = hex(c));
  function floorPixel(code, px, py) {
    const lx = px & 15, ly = py & 15;
    if (PLANKS[code]) {
      const P = PLANKS[code];
      const row = Math.floor(py / 4), within = py % 4;
      const off = Math.floor(hash(0, row, 7) * 24);
      const plank = Math.floor((px + off) / 24);
      let col = H(P.c[Math.floor(hash(plank, row, 3) * 4) % 4]);
      if (within === 3) col = H(P.line);
      else if ((px + off) % 24 === 0) col = H(P.seam);
      else if (within === 0) col = mul(col, 1.06);
      else if (hash(px, py, 11) > 0.94) col = mul(col, 0.92);
      return col;
    }
    if (code === '7') { // parket: blokjes van 4x4 met afwisselend liggende en staande latjes
      const bx = px >> 2, by = py >> 2, horiz = (bx + by) % 2 === 0;
      const along = horiz ? py & 3 : px & 3;
      let col = H(['#b8834e', '#ad7845', '#c18c56'][Math.floor(hash(bx, by, 4) * 3) % 3]);
      if (along === 3 || along === 1 && hash(bx, by, 6) > 0.5) col = mul(col, 0.86);
      if ((horiz ? px & 3 : py & 3) === 3) col = mul(col, 0.8);
      return col;
    }
    if (code === '4' || code === '8') { // tegels van een halve meter met voegen
      const t = ((px >> 3) + (py >> 3)) & 1;
      const A = code === '4' ? ['#dfe7f3', '#8ea8d6'] : ['#ece6da', '#3a3640'];
      let col = H(A[t]);
      if ((px & 7) === 7 || (py & 7) === 7) col = code === '4' ? H('#6f86b4') : H('#8a8478');
      else if ((px & 7) === 0 || (py & 7) === 0) col = mul(col, 1.05);
      return col;
    }
    if (code === '5') { // natuursteen, verspringend
      const by = py >> 3, bx = (px + (by & 1) * 4) >> 3;
      let col = mul(H('#a9a49d'), 0.9 + hash(bx, by, 8) * 0.2);
      if ((py & 7) === 7 || ((px + (by & 1) * 4) & 7) === 7) col = H('#77726b');
      else if (hash(px, py, 12) > 0.9) col = mul(col, 0.93);
      return col;
    }
    if (code === '6') { // tapijt: zacht gespikkeld
      const n = hash(px, py, 13);
      return mul(H('#b8a48c'), n > 0.85 ? 0.92 : n < 0.1 ? 1.06 : 1);
    }
    return H('#c58b55');
  }

  /** Eén keer per wijziging: vloer, muren en meubels. */
  function base(L, lamps) {
    const W = (L.w + 2) * PPM, H = (L.h + 2) * PPM;
    const c = document.createElement('canvas'); c.width = W; c.height = H;
    const g = c.getContext('2d');
    const img = g.createImageData(W, H), d = img.data;
    const VOID = hex('#1d1124');
    for (let py = 0; py < H; py++) for (let px = 0; px < W; px++) {
      const cx = Math.floor(px / PPM) - 1, cy = Math.floor(py / PPM) - 1;
      let col = VOID;
      if (isFloor(L, cx, cy)) {
        col = floorPixel(mat(L, cx, cy), px, py);
        // schaduw langs muren
        const lx = px % PPM, ly = py % PPM;
        if (!isFloor(L, cx, cy - 1) && ly < 3) col = mul(col, 0.72 + ly * 0.08);
        if (!isFloor(L, cx - 1, cy) && lx < 2) col = mul(col, 0.84);
        if (!isFloor(L, cx + 1, cy) && lx > 13) col = mul(col, 0.9);
      }
      const i = (py * W + px) * 4;
      d[i] = col[0]; d[i + 1] = col[1]; d[i + 2] = col[2]; d[i + 3] = 255;
    }
    g.putImageData(img, 0, 0);
    walls(g, L);

    // Kleden eerst, dan meubels van achter naar voren
    const items = (L.items || []).slice().sort((a, b) => (a.type === 'rug' ? -1 : 0) - (b.type === 'rug' ? -1 : 0) || a.y - b.y);
    for (const it of items) drawItem(g, it);
    if (L.tv) drawTv(g, L.tv);
    return c;
  }

  function walls(g, L) {
    const TOP = '#3b2240', HI = '#7a4a72', CAP = '#4a2a4f';
    for (let y = -1; y <= L.h; y++) for (let x = -1; x <= L.w; x++) {
      if (isFloor(L, x, y)) continue;
      const N = isFloor(L, x, y - 1), S = isFloor(L, x, y + 1), Wf = isFloor(L, x - 1, y), E = isFloor(L, x + 1, y);
      const NW = isFloor(L, x - 1, y - 1), NE = isFloor(L, x + 1, y - 1), SW = isFloor(L, x - 1, y + 1), SE = isFloor(L, x + 1, y + 1);
      if (!(N || S || Wf || E || NW || NE || SW || SE)) continue;
      const X = (x + 1) * PPM, Y = (y + 1) * PPM;
      if (S) {
        // voorkant van de muur: kapje, behang met streepjes, plint
        g.fillStyle = TOP; g.fillRect(X, Y, PPM, 5);
        g.fillStyle = HI; g.fillRect(X, Y + 4, PPM, 1);
        for (let i = 0; i < PPM; i++) {
          g.fillStyle = ((X + i) % 6 === 0) ? '#cc8d78' : '#dca18a';
          g.fillRect(X + i, Y + 5, 1, 8);
        }
        g.fillStyle = '#e7b39c'; g.fillRect(X, Y + 5, PPM, 1);
        g.fillStyle = '#a7684a'; g.fillRect(X, Y + 13, PPM, 1);
        g.fillStyle = '#8a5238'; g.fillRect(X, Y + 14, PPM, 1);
        g.fillStyle = '#5a3040'; g.fillRect(X, Y + 15, PPM, 1);
      }
      g.fillStyle = TOP;
      if (N) { g.fillRect(X, Y, PPM, 5); g.fillStyle = HI; g.fillRect(X, Y, PPM, 1); g.fillStyle = TOP; }
      if (Wf) { g.fillRect(X, Y, 5, PPM); g.fillStyle = HI; g.fillRect(X, Y, 1, PPM); g.fillStyle = TOP; }
      if (E) { g.fillRect(X + PPM - 5, Y, 5, PPM); g.fillStyle = HI; g.fillRect(X + PPM - 1, Y, 1, PPM); g.fillStyle = TOP; }
      if (NW && !N && !Wf) g.fillRect(X, Y, 5, 5);
      if (NE && !N && !E) g.fillRect(X + PPM - 5, Y, 5, 5);
      if (SW && !S && !Wf) g.fillRect(X, Y + PPM - 5, 5, 5);
      if (SE && !S && !E) g.fillRect(X + PPM - 5, Y + PPM - 5, 5, 5);
      if (S && Wf) { g.fillStyle = TOP; g.fillRect(X, Y, 5, PPM); }
      if (S && E) { g.fillStyle = TOP; g.fillRect(X + PPM - 5, Y, 5, PPM); }
      g.fillStyle = CAP;
    }
  }

  /** Tekent iets in zijn eigen assenstelsel (voorkant = onderkant) en draait het naar dir. */
  function rotated(g, x, y, wM, dM, dir, draw) {
    const w = Math.max(3, Math.round(wM * PPM)), d = Math.max(3, Math.round(dM * PPM));
    const t = document.createElement('canvas'); t.width = w + 2; t.height = d + 2;
    const tg = t.getContext('2d');
    draw(tg, w, d);
    const q = ((Math.round((dir - 90) / 90) % 4) + 4) % 4;
    const cx = Math.round((x + 1) * PPM), cy = Math.round((y + 1) * PPM);
    g.save(); g.translate(cx, cy); g.rotate(q * Math.PI / 2);
    g.drawImage(t, -Math.floor(w / 2), -Math.floor(d / 2));
    g.restore();
  }

  function box(g, x, y, w, h, fill, outline) {
    g.fillStyle = outline; g.fillRect(x, y, w, h);
    g.fillStyle = fill; g.fillRect(x + 1, y + 1, w - 2, h - 2);
  }
  function shadow(g, w, d) { g.fillStyle = 'rgba(30,10,30,.30)'; g.fillRect(1, 1, w, d + 1); }

  const SPRITES = {
    sofa(g, w, d) {
      shadow(g, w, d);
      const O = '#22314a', B = '#4f7aa8', DK = '#3d6390', LT = '#77a0c9';
      box(g, 0, 0, w, d, DK, O);
      const back = Math.max(3, Math.round(d * 0.34));
      g.fillStyle = LT; g.fillRect(1, 1, w - 2, 1);
      const arm = 3;
      g.fillStyle = DK; g.fillRect(1, 1, arm, d - 2); g.fillRect(w - 1 - arm, 1, arm, d - 2);
      g.fillStyle = LT; g.fillRect(1, 1, arm, 1); g.fillRect(w - 1 - arm, 1, arm, 1);
      const sx = 1 + arm, sw = w - 2 - 2 * arm, n = sw > 22 ? 3 : 2, cw = Math.floor(sw / n);
      for (let i = 0; i < n; i++) {
        const x0 = sx + i * cw, ww = i === n - 1 ? sw - cw * (n - 1) : cw;
        box(g, x0, back, ww, d - back - 1, B, O);
        g.fillStyle = LT; g.fillRect(x0 + 1, back + 1, ww - 2, 1);
      }
      g.fillStyle = '#e8b84a'; g.fillRect(sx + 2, back - 2, 4, 3);
      g.fillStyle = '#8fc46a'; g.fillRect(sx + sw - 6, back - 2, 4, 3);
    },
    corner(g, w, d) {
      // L-vorm: rugleuning langs de achterkant en de linkerkant (spiegelen kan via flip)
      const O = '#22314a', B = '#4f7aa8', DK = '#3d6390', LT = '#77a0c9';
      const sd = Math.min(d, Math.max(10, Math.round(0.9 * 16))), back = Math.max(3, Math.round(sd * 0.34)), arm = 3;
      g.fillStyle = 'rgba(30,10,30,.30)'; g.fillRect(1, 1, w, sd + 1); g.fillRect(1, 1, sd + 1, d);
      g.fillStyle = O; g.fillRect(0, 0, w, sd); g.fillRect(0, 0, sd, d);
      g.fillStyle = DK; g.fillRect(1, 1, w - 2, sd - 2); g.fillRect(1, 1, sd - 2, d - 2);
      g.fillStyle = LT; g.fillRect(1, 1, w - 2, 1); g.fillRect(1, 1, 1, d - 2);
      // armleuningen aan de open uiteinden
      g.fillStyle = DK; g.fillRect(w - 1 - arm, 1, arm, sd - 2); g.fillRect(1, d - 1 - arm, sd - 2, arm);
      g.fillStyle = LT; g.fillRect(w - 1 - arm, 1, arm, 1);
      // zitkussens: de hoek + het lange stuk naar beneden, en het stuk naar rechts
      const cx0 = back, cy0 = back;
      const down = d - 1 - arm - cy0;
      const nd = down > 26 ? 2 : 1, ch = Math.floor(down / nd);
      for (let i = 0; i < nd; i++) {
        const y0 = cy0 + i * ch, hh = i === nd - 1 ? down - ch * (nd - 1) : ch;
        box(g, cx0, y0, sd - 1 - cx0, hh, B, O); g.fillStyle = LT; g.fillRect(cx0 + 1, y0 + 1, sd - 3 - cx0, 1);
      }
      const across = w - 1 - arm - (sd - 1);
      const na = across > 22 ? 2 : 1, cw = Math.floor(across / na);
      for (let i = 0; i < na; i++) {
        const x0 = sd - 1 + i * cw, ww = i === na - 1 ? across - cw * (na - 1) : cw;
        box(g, x0, cy0, ww, sd - 1 - cy0, B, O); g.fillStyle = LT; g.fillRect(x0 + 1, cy0 + 1, ww - 2, 1);
      }
      g.fillStyle = '#e8b84a'; g.fillRect(back + 1, back - 2, 4, 3);
      g.fillStyle = '#8fc46a'; g.fillRect(w - arm - 7, back - 2, 4, 3);
      g.fillStyle = '#e07a6a'; g.fillRect(back - 2, d - arm - 7, 3, 4);
    },
    armchair(g, w, d) {
      shadow(g, w, d);
      const O = '#4a1f1a', B = '#c0604a', DK = '#9c4a38', LT = '#dd8a6a';
      box(g, 0, 0, w, d, DK, O);
      g.fillStyle = LT; g.fillRect(1, 1, w - 2, 1);
      box(g, 3, Math.round(d * 0.35), w - 6, d - Math.round(d * 0.35) - 1, B, O);
      g.fillStyle = LT; g.fillRect(4, Math.round(d * 0.35) + 1, w - 8, 1);
    },
    coffee(g, w, d) {
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#a86a3c', '#4a2a1a');
      g.fillStyle = '#c98a52'; g.fillRect(1, 1, w - 2, 1);
      g.fillStyle = '#7a4a2a'; g.fillRect(1, d - 2, w - 2, 1);
      g.fillStyle = '#f2ead8'; g.fillRect(w - 6, 2, 3, 3);
      g.fillStyle = '#5a8ac0'; g.fillRect(3, 3, 5, 3);
    },
    dining(g, w, d) {
      const chair = (x, y) => { g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(x + 1, y + 1, 5, 5); box(g, x, y, 5, 5, '#8a5a35', '#3a2214'); g.fillStyle = '#a8744a'; g.fillRect(x + 1, y + 1, 3, 1); };
      const tx = 2, ty = 5, tw = w - 4, th = d - 10;
      chair(Math.round(w * 0.25) - 2, 0); chair(Math.round(w * 0.75) - 3, 0);
      chair(Math.round(w * 0.25) - 2, d - 5); chair(Math.round(w * 0.75) - 3, d - 5);
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(tx + 1, ty + 1, tw, th);
      box(g, tx, ty, tw, th, '#b07a45', '#4a2a1a');
      g.fillStyle = '#cf9a62'; g.fillRect(tx + 1, ty + 1, tw - 2, 1);
      for (let i = 1; i < 4; i++) { g.fillStyle = '#9a6838'; g.fillRect(tx + 1, ty + Math.round(th * i / 4), tw - 2, 1); }
      g.fillStyle = '#e8e0cc'; g.fillRect(Math.round(w / 2) - 2, ty + Math.round(th / 2) - 1, 4, 3);
    },
    plant(g, w, d) {
      const cx = w / 2;
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(2, d - 3, w - 2, 3);
      box(g, Math.round(cx - 3), d - 5, 6, 5, '#c06a45', '#5a2a1a');
      const greens = ['#3a7a3a', '#4f9a4a', '#6dbb5a', '#8fd06a'];
      for (let y = 0; y < d - 3; y++) for (let x = 0; x < w; x++) {
        const dx = x - cx + 0.5, dy = y - (d - 3) / 2;
        if (dx * dx + dy * dy * 1.2 < (w / 2) * (w / 2) * (0.75 + hash(x, y, 5) * 0.4)) {
          g.fillStyle = greens[Math.min(3, Math.floor(hash(x, y, 9) * 3 + (dy < 0 ? 1 : 0)))];
          g.fillRect(x, y, 1, 1);
        }
      }
    },
    rug(g, w, d) {
      box(g, 0, 0, w, d, '#5e8a5a', '#3f6b45');
      g.fillStyle = '#9cc48a'; g.strokeStyle = 'transparent';
      g.fillRect(2, 2, w - 4, 1); g.fillRect(2, d - 3, w - 4, 1); g.fillRect(2, 2, 1, d - 4); g.fillRect(w - 3, 2, 1, d - 4);
      g.fillStyle = '#4c7a4c';
      for (let y = 4; y < d - 4; y++) for (let x = 4; x < w - 4; x++) if ((x + y) % 6 === 0 || (x - y + 600) % 6 === 0) g.fillRect(x, y, 1, 1);
    },
    cabinet(g, w, d) {
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#9a6438', '#3a2214');
      g.fillStyle = '#b87c4a'; g.fillRect(1, 1, w - 2, 2);
      g.fillStyle = '#7a4a2a';
      for (let x = Math.round(w / 3); x < w - 2; x += Math.round(w / 3)) g.fillRect(x, 3, 1, d - 4);
      g.fillStyle = '#e0c070';
      for (let x = Math.round(w / 6); x < w; x += Math.round(w / 3)) g.fillRect(x, d - 3, 1, 1);
      g.fillStyle = '#6a8fc0'; g.fillRect(3, 1, 2, 2); g.fillStyle = '#d0605a'; g.fillRect(6, 1, 2, 2);
    },
    bed(g, w, d) {
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#7a4a2a', '#3a2214');
      box(g, 1, 1, w - 2, d - 2, '#e8e2d6', '#9a948a');
      g.fillStyle = '#ffffff'; g.fillRect(3, 3, Math.round(w / 2) - 4, 4); g.fillRect(Math.round(w / 2) + 1, 3, Math.round(w / 2) - 4, 4);
      box(g, 1, Math.round(d * 0.35), w - 2, d - Math.round(d * 0.35) - 1, '#5a7ab0', '#334a78');
      g.fillStyle = '#7a9ad0'; g.fillRect(2, Math.round(d * 0.35) + 1, w - 4, 1);
    },
  };

  function drawItem(g, it) {
    const def = ITEMS[it.type]; if (!def) return;
    const sprite = SPRITES[it.type];
    rotated(g, it.x, it.y, it.w || def.w, it.d || def.d, it.dir == null ? 90 : it.dir,
      it.flip ? (c, w, d) => { c.translate(w, 0); c.scale(-1, 1); sprite(c, w, d); } : sprite);
  }

  function drawTv(g, tv) {
    // Tv-meubel achter de tv, dan de tv zelf
    rotated(g, tv.x, tv.y, tv.w + 0.4, 0.45, tv.dir, (c, w, d) => {
      shadow(c, w, d);
      box(c, 0, 0, w, d, '#6b4430', '#2a160e');
      c.fillStyle = '#86583e'; c.fillRect(1, 1, w - 2, 1);
      c.fillStyle = '#4e301f'; c.fillRect(Math.round(w / 2), 2, 1, d - 3);
    });
    rotated(g, tv.x, tv.y, tv.w, 0.12, tv.dir, (c, w, d) => {
      c.fillStyle = '#0c0c10'; c.fillRect(0, 0, w, d);
      c.fillStyle = '#34344a'; c.fillRect(1, 0, w - 2, 1);
    });
  }

  function lampFixture(g, l, color) {
    const x = Math.round((l.x + 1) * PPM), y = Math.round((l.y + 1) * PPM);
    if (l.shape === 'rod') {
      // staande staaf: voetje op de vloer, lichtgevende buis omhoog (3/4-aanzicht)
      g.fillStyle = 'rgba(30,10,30,.35)'; g.fillRect(x - 3, y, 8, 3);
      g.fillStyle = '#2a1a2e'; g.fillRect(x - 3, y - 1, 7, 3); g.fillRect(x - 2, y - 20, 5, 20);
      g.fillStyle = '#4a3a50'; g.fillRect(x - 2, y, 5, 1);
      g.fillStyle = color; g.fillRect(x - 1, y - 19, 3, 18);
      g.fillStyle = 'rgba(255,255,255,.45)'; g.fillRect(x - 1, y - 19, 1, 18);
      return;
    }
    if (l.height === 'ceiling') {
      g.fillStyle = '#2a1a2e'; g.fillRect(x - 2, y - 3, 5, 7); g.fillRect(x - 3, y - 2, 7, 5);
      g.fillStyle = '#d8d2c4'; g.fillRect(x - 2, y - 2, 5, 5);
      g.fillStyle = color; g.fillRect(x - 1, y - 1, 3, 3);
    } else if (l.height === 'table') {
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(x - 2, y - 1, 6, 5);
      g.fillStyle = '#2a1a2e'; g.fillRect(x - 3, y - 4, 7, 6);
      g.fillStyle = '#efe0c0'; g.fillRect(x - 2, y - 3, 5, 4);
      g.fillStyle = color; g.fillRect(x - 1, y - 2, 3, 2);
      g.fillStyle = '#6b4430'; g.fillRect(x - 1, y + 2, 3, 2);
    } else {
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(x - 3, y - 2, 8, 8);
      g.fillStyle = '#2a1a2e'; g.fillRect(x - 4, y - 4, 9, 9);
      g.fillStyle = '#efe0c0'; g.fillRect(x - 3, y - 3, 7, 7);
      g.fillStyle = color; g.fillRect(x - 1, y - 1, 3, 3);
    }
  }

  /** Per frame: basis + (bij sync) donkere kamer met gekleurd licht. */
  function frame(baseCanvas, L, lamps, live, sync, top) {
    const c = document.createElement('canvas'); c.width = baseCanvas.width; c.height = baseCanvas.height;
    const g = c.getContext('2d');
    g.imageSmoothingEnabled = false;
    g.drawImage(baseCanvas, 0, 0);
    if (sync) { g.fillStyle = 'rgba(16,6,28,.55)'; g.fillRect(0, 0, c.width, c.height); }
    g.globalCompositeOperation = 'lighter';
    if (sync && top && top.length && L.tv) {
      const tv = L.tv, w = Math.round(tv.w * PPM), seg = w / top.length;
      const q = ((Math.round((tv.dir - 90) / 90) % 4) + 4) % 4;
      g.save(); g.translate(Math.round((tv.x + 1) * PPM), Math.round((tv.y + 1) * PPM)); g.rotate(q * Math.PI / 2);
      top.forEach((col, i) => {
        const x0 = -w / 2 + i * seg;
        for (const [len, a] of [[52, 0.05], [34, 0.07], [18, 0.09]]) {
          g.globalAlpha = a; g.fillStyle = col;
          g.beginPath(); g.moveTo(x0, 2); g.lineTo(x0 + seg, 2);
          g.lineTo(x0 + seg + (i - top.length / 2 + 1) * len * 0.06 + len * 0.15, len);
          g.lineTo(x0 + (i - top.length / 2) * len * 0.06 - len * 0.15, len); g.closePath(); g.fill();
        }
      });
      g.restore();
    }
    for (const l of lamps) {
      if (l.x < 0) continue;
      const lv = live[l.id] || {};
      if (!lv.online) continue;
      const col = sync ? (lv.color || '#ffd696') : '#ffd696';
      const x = Math.round((l.x + 1) * PPM), y = Math.round((l.y + 1) * PPM);
      const big = l.height === 'ceiling' || l.shape === 'rod' ? 1 : 0.75;
      const rings = sync ? [[30, 0.06], [22, 0.07], [14, 0.09], [7, 0.12]] : [[14, 0.05], [7, 0.06]];
      if (l.shape === 'rod') {
        // langgerekte gloed langs de staaf
        for (const [r, a] of rings) { g.globalAlpha = a; g.fillStyle = col; g.beginPath(); g.ellipse(x, y - 9, r * 0.8, r * 1.15, 0, 0, 7); g.fill(); }
      } else {
        for (const [r, a] of rings) { g.globalAlpha = a; g.fillStyle = col; g.beginPath(); g.arc(x, y, r * big, 0, 7); g.fill(); }
      }
    }
    g.globalAlpha = 1; g.globalCompositeOperation = 'source-over';
    for (const l of lamps) {
      if (l.x < 0) continue;
      const lv = live[l.id] || {};
      lampFixture(g, l, !lv.online ? '#55505a' : sync ? (lv.color || '#ffd696') : '#ffe2a8');
    }
    return c;
  }

  /** Schaalt het pixelbeeld scherp naar een zichtbaar canvas. Geeft schaal en verschuiving terug. */
  function present(view, img, maxW, maxH, integer) {
    let s = Math.min(maxW / img.width, maxH / img.height);
    if (integer && s >= 2) s = Math.floor(s);
    const W = Math.round(img.width * s), H = Math.round(img.height * s);
    const dpr = window.devicePixelRatio || 1;
    view.width = W * dpr; view.height = H * dpr; view.style.width = W + 'px'; view.style.height = H + 'px';
    const g = view.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.imageSmoothingEnabled = false;
    g.drawImage(img, 0, 0, W, H);
    return { scale: s * PPM, ox: s * PPM, oy: s * PPM, g, W, H };
  }

  /** Zet een oude indeling (losse bank) om naar de meubellijst. */
  function migrate(L) {
    if (!L.items) {
      L.items = [];
      if (L.couch) L.items.push({ type: 'sofa', x: L.couch.x, y: L.couch.y, dir: L.couch.dir, w: L.couch.w });
    }
    delete L.couch;
    return L;
  }

  return { PPM, ITEMS, SPRITES, FLOORS, floorPixel, base, frame, present, migrate, isFloor, rotated };
})();
