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
    bookshelf:{ name: 'Boekenkast', w: 1.0, d: 0.35 },
    sidetable:{ name: 'Bijzettafel', w: 0.5, d: 0.5 },
    roundtable:{ name: 'Ronde eettafel', w: 1.5, d: 1.5 },
    desk:     { name: 'Bureau',     w: 1.4, d: 1.1 },
    chair:    { name: 'Stoel',      w: 0.5, d: 0.5 },
    wardrobe: { name: 'Kledingkast', w: 1.6, d: 0.6 },
    kitchen:  { name: 'Keukenblok', w: 2.4, d: 0.65 },
    fireplace:{ name: 'Haard',      w: 1.4, d: 0.45 },
    piano:    { name: 'Piano',      w: 1.5, d: 0.6 },
    beanbag:  { name: 'Zitzak',     w: 0.8, d: 0.8 },
    pouf:     { name: 'Poef',       w: 0.55, d: 0.55 },
    // Muuronderdelen: springen naar de dichtstbijzijnde muur (wall), plaatje van voren (thumbD)
    door:     { name: 'Deur',       w: 0.9, d: 0.3, wall: true, thumbD: 1.0 },
    window:   { name: 'Raam',       w: 1.2, d: 0.3, wall: true, thumbD: 0.8 },
    windowsill:{ name: 'Vensterbank', w: 1.2, d: 0.3, wall: true, thumbD: 0.8 },
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
    drawWallItems(g, L);

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
  /** Gevulde ronde vorm (ellips) in pixels, met rand. */
  function disc(g, cx, cy, rx, ry, fill, outline) {
    for (let y = Math.floor(cy - ry); y <= Math.ceil(cy + ry); y++) for (let x = Math.floor(cx - rx); x <= Math.ceil(cx + rx); x++) {
      const v = ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2;
      if (v <= 1) { g.fillStyle = v > 0.72 ? outline : fill; g.fillRect(x, y, 1, 1); }
    }
  }
  /** Stoel van bovenaf met de rugleuning aan de kant van rot (0 = boven). */
  function chairAt(g, x, y, rot) {
    g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(x + 1, y + 1, 5, 5);
    box(g, x, y, 5, 5, '#8a5a35', '#3a2214');
    g.fillStyle = '#5a3820';
    if (rot === 0) g.fillRect(x + 1, y + 1, 3, 1); else if (rot === 1) g.fillRect(x + 3, y + 1, 1, 3);
    else if (rot === 2) g.fillRect(x + 1, y + 3, 3, 1); else g.fillRect(x + 1, y + 1, 1, 3);
  }

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
    bookshelf(g, w, d) {
      // boekenkast van bovenaf: houten bak met gekleurde ruggen van boeken
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#7a4a2a', '#3a2214');
      const cols = ['#c0504a', '#4f7aa8', '#e0b050', '#5a9a5a', '#8a5aa8', '#e8e0cc', '#d07a40'];
      for (let x = 2; x < w - 2;) {
        const bw = 1 + Math.floor(hash(x, d, 3) * 2), bh = Math.max(1, d - 3 - Math.floor(hash(x, d, 4) * 2));
        g.fillStyle = cols[Math.floor(hash(x, w, 7) * cols.length)]; g.fillRect(x, d - 1 - bh, Math.min(bw, w - 2 - x), bh);
        x += bw + (hash(x, 1, 8) > 0.85 ? 1 : 0);
      }
      g.fillStyle = '#9a6438'; g.fillRect(1, 1, w - 2, 1);
    },
    sidetable(g, w, d) {
      g.fillStyle = 'rgba(30,10,30,.3)'; disc(g, w / 2 + 1, d / 2 + 1, w / 2 - 0.5, d / 2 - 0.5, 'rgba(30,10,30,.3)', 'rgba(30,10,30,.3)');
      disc(g, w / 2, d / 2, w / 2 - 0.5, d / 2 - 0.5, '#b07a45', '#4a2a1a');
      g.fillStyle = '#cf9a62'; g.fillRect(Math.round(w / 2) - 2, 2, 3, 1);
      g.fillStyle = '#f2ead8'; g.fillRect(Math.round(w / 2), Math.round(d / 2), 2, 2);
    },
    roundtable(g, w, d) {
      // ronde eettafel met vier stoelen eromheen
      const cx = w / 2, cy = d / 2;
      chairAt(g, Math.round(cx) - 3, 0, 0); chairAt(g, Math.round(cx) - 3, d - 5, 2);
      chairAt(g, 0, Math.round(cy) - 3, 3); chairAt(g, w - 5, Math.round(cy) - 3, 1);
      disc(g, cx + 1, cy + 1, w / 2 - 5, d / 2 - 5, 'rgba(30,10,30,.3)', 'rgba(30,10,30,.3)');
      disc(g, cx, cy, w / 2 - 5, d / 2 - 5, '#b07a45', '#4a2a1a');
      g.fillStyle = '#e8e0cc'; g.fillRect(Math.round(cx) - 2, Math.round(cy) - 1, 4, 3);
    },
    desk(g, w, d) {
      // bureau tegen de muur (achterkant boven), met scherm, toetsenbord en een stoel ervoor
      const td = Math.max(6, Math.round(d * 0.6));
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(1, 1, w, td + 1);
      box(g, 0, 0, w, td, '#c9a06a', '#4a2a1a');
      g.fillStyle = '#e0bc86'; g.fillRect(1, 1, w - 2, 1);
      const mw = Math.max(6, Math.round(w * 0.35)), mx = Math.round((w - mw) / 2);
      g.fillStyle = '#1e1a24'; g.fillRect(mx, 2, mw, 2); g.fillStyle = '#5a8ac0'; g.fillRect(mx + 1, 2, mw - 2, 1);
      g.fillStyle = '#d8d2c4'; g.fillRect(mx + 1, td - 3, mw - 2, 1);
      const cw = 7, ch = Math.max(5, d - td - 1), cx = Math.round((w - cw) / 2);
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(cx + 1, td + 2, cw, ch);
      box(g, cx, td + 1, cw, ch, '#3a3a48', '#16141c'); g.fillStyle = '#5a5a6a'; g.fillRect(cx + 1, td + ch - 1, cw - 2, 1);
    },
    chair(g, w, d) {
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#8a5a35', '#3a2214');
      g.fillStyle = '#5a3820'; g.fillRect(1, 1, w - 2, 2);
      g.fillStyle = '#a8744a'; g.fillRect(2, 4, w - 4, 1);
    },
    wardrobe(g, w, d) {
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#8a5a3a', '#3a2214');
      g.fillStyle = '#a8744a'; g.fillRect(1, 1, w - 2, 1);
      g.fillStyle = '#5a3820'; g.fillRect(Math.round(w / 2), 2, 1, d - 3);
      g.fillStyle = '#e0c070'; g.fillRect(Math.round(w / 2) - 2, d - 3, 1, 1); g.fillRect(Math.round(w / 2) + 2, d - 3, 1, 1);
    },
    kitchen(g, w, d) {
      // keukenblok: aanrecht met spoelbak en kookplaat, kastjes aan de voorkant
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#d8d2c4', '#4a4440');
      g.fillStyle = '#9a6438'; g.fillRect(1, d - 3, w - 2, 2);
      for (let x = Math.round(w / 6); x < w - 2; x += Math.round(w / 6)) { g.fillStyle = '#6a4024'; g.fillRect(x, d - 3, 1, 2); }
      const sx = Math.round(w * 0.12), sw = Math.max(5, Math.round(w * 0.2));
      box(g, sx, 2, sw, d - 6, '#aab4be', '#5a646e'); g.fillStyle = '#7a848e'; g.fillRect(sx + Math.round(sw / 2), 2, 1, 1);
      const kx = Math.round(w * 0.6), kw = Math.max(8, Math.round(w * 0.24));
      box(g, kx, 2, kw, d - 6, '#2a2a30', '#141418');
      g.fillStyle = '#4a4a54';
      for (const [fx, fy] of [[0.28, 0.3], [0.72, 0.3], [0.28, 0.7], [0.72, 0.7]]) g.fillRect(kx + Math.round(kw * fx) - 1, 2 + Math.round((d - 6) * fy) - 1, 2, 2);
    },
    fireplace(g, w, d) {
      // haard tegen de muur: stenen ombouw, vuur in het midden
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#8a8078', '#3a3430');
      for (let y = 1; y < d - 1; y += 2) for (let x = 1 + (y % 4 === 1 ? 0 : 2); x < w - 1; x += 4) { g.fillStyle = '#7a7068'; g.fillRect(x, y, 1, 1); }
      const ow = Math.max(6, Math.round(w * 0.45)), ox = Math.round((w - ow) / 2);
      g.fillStyle = '#1e140f'; g.fillRect(ox, 1, ow, d - 2);
      for (let x = ox + 1; x < ox + ow - 1; x++) {
        const h = 1 + Math.floor(hash(x, 3, 11) * (d - 3));
        g.fillStyle = '#e05a2a'; g.fillRect(x, d - 1 - h, 1, h);
        if (h > 2) { g.fillStyle = '#f2b35a'; g.fillRect(x, d - 1 - Math.floor(h / 2), 1, Math.floor(h / 2)); }
      }
    },
    piano(g, w, d) {
      // piano tegen de muur, toetsen aan de voorkant
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#1e1a20', '#0a080c');
      g.fillStyle = '#3a3440'; g.fillRect(1, 1, w - 2, 1);
      g.fillStyle = '#f2ead8'; g.fillRect(1, d - 4, w - 2, 3);
      g.fillStyle = '#16141a';
      for (let x = 2; x < w - 2; x++) { const k = (x - 2) % 7; if (k === 1 || k === 2 || k === 4 || k === 5 || k === 6) g.fillRect(x, d - 4, 1, 2); }
    },
    beanbag(g, w, d) {
      disc(g, w / 2 + 1, d / 2 + 1, w / 2 - 0.5, d / 2 - 0.5, 'rgba(30,10,30,.3)', 'rgba(30,10,30,.3)');
      disc(g, w / 2, d / 2, w / 2 - 0.5, d / 2 - 0.5, '#d07a40', '#5a2a14');
      disc(g, w / 2 + 1, d / 2 + 1, w / 3, d / 3, '#b8632e', '#b8632e');
      g.fillStyle = '#e89a60'; g.fillRect(Math.round(w / 3), Math.round(d / 4), 3, 1);
    },
    pouf(g, w, d) {
      disc(g, w / 2 + 1, d / 2 + 1, w / 2 - 0.5, d / 2 - 0.5, 'rgba(30,10,30,.3)', 'rgba(30,10,30,.3)');
      disc(g, w / 2, d / 2, w / 2 - 0.5, d / 2 - 0.5, '#c8b08a', '#5a4a30');
      g.fillStyle = '#a8906a'; g.fillRect(Math.round(w / 2), 2, 1, d - 4); g.fillRect(2, Math.round(d / 2), w - 4, 1);
    },
    // Plaatjes voor de keuzelijst: van voren, tegen een stukje behang
    door(g, w, d) { wallBg(g, w, d); doorFront(g, Math.round(w * 0.15), 2, Math.round(w * 0.7), d - 2); },
    window(g, w, d) { wallBg(g, w, d); windowFront(g, 2, 3, w - 4, d - 8, false); },
    windowsill(g, w, d) { wallBg(g, w, d); windowFront(g, 2, 3, w - 4, d - 8, true); },
  };

  function wallBg(g, w, d) { g.fillStyle = '#dca18a'; g.fillRect(0, 0, w, d); g.fillStyle = '#8a5238'; g.fillRect(0, d - 2, w, 2); }
  /** Deur van voren: kozijn, houten deur met twee panelen en een klink. */
  function doorFront(g, x, y, w, h) {
    g.fillStyle = '#3a2214'; g.fillRect(x - 1, y - 1, w + 2, h + 1);
    g.fillStyle = '#a8744a'; g.fillRect(x, y, w, h);
    g.fillStyle = '#c98a52'; g.fillRect(x, y, w, 1);
    const ph = Math.floor((h - 5) / 2);
    g.fillStyle = '#8a5a35'; g.fillRect(x + 2, y + 2, w - 4, ph); g.fillRect(x + 2, y + 3 + ph, w - 4, h - 5 - ph);
    g.fillStyle = '#e0c070'; g.fillRect(x + w - 3, y + Math.floor(h / 2), 2, 1);
  }
  /** Raam van voren: wit kozijn, blauw glas met spijlen, eventueel een vensterbank eronder. */
  function windowFront(g, x, y, w, h, sill) {
    g.fillStyle = '#f2ead8'; g.fillRect(x - 1, y - 1, w + 2, h + 2);
    g.fillStyle = '#7aaed2'; g.fillRect(x, y, w, h);
    g.fillStyle = '#a8d2ee'; for (let i = 0; i < Math.min(w, h); i++) g.fillRect(x + 1 + i, y + h - 2 - i, 1, 1);
    g.fillStyle = '#f2ead8'; g.fillRect(x + Math.floor(w / 2), y, 1, h);
    if (h > 5) g.fillRect(x, y + Math.floor(h / 2), w, 1);
    if (sill) { g.fillStyle = '#e8e0cc'; g.fillRect(x - 2, y + h + 1, w + 4, 2); g.fillStyle = '#9a8a78'; g.fillRect(x - 2, y + h + 3, w + 4, 1); }
  }

  /**
   * Dichtstbijzijnde muur voor een deur of raam: plek op de muurlijn (in meters), welke muur (n = achtermuur, s, w, e)
   * en de draairichting (90 langs x, 0 langs y).
   */
  function wallSnap(L, x, y) {
    let cx = Math.floor(x), cy = Math.floor(y);
    if (!isFloor(L, cx, cy)) {
      let best = null, bd = 1e9;
      for (let yy = 0; yy < L.h; yy++) for (let xx = 0; xx < L.w; xx++)
        if (isFloor(L, xx, yy)) { const dd = Math.hypot(xx + 0.5 - x, yy + 0.5 - y); if (dd < bd) { bd = dd; best = [xx, yy]; } }
      if (!best) return { x, y, wall: 'n', dir: 90 };
      [cx, cy] = best;
    }
    let n = cy; while (isFloor(L, cx, n - 1)) n--;
    let s = cy; while (isFloor(L, cx, s + 1)) s++;
    let w = cx; while (isFloor(L, w - 1, cy)) w--;
    let e = cx; while (isFloor(L, e + 1, cy)) e++;
    const q = v => Math.round(v * 4) / 4, ax = q(Math.min(Math.max(x, w), e + 1)), ay = q(Math.min(Math.max(y, n), s + 1));
    const k = [['n', Math.abs(y - n)], ['s', Math.abs(s + 1 - y)], ['w', Math.abs(x - w)], ['e', Math.abs(e + 1 - x)]].sort((a, b) => a[1] - b[1])[0][0];
    return k === 'n' ? { wall: 'n', x: ax, y: n, dir: 90 } : k === 's' ? { wall: 's', x: ax, y: s + 1, dir: 90 }
      : k === 'w' ? { wall: 'w', x: w, y: ay, dir: 0 } : { wall: 'e', x: e + 1, y: ay, dir: 0 };
  }

  /** Deuren en ramen: op de achtermuur van voren, op de andere muren van bovenaf (in de muurkap). */
  function drawWallItems(g, L) {
    for (const it of (L.items || [])) {
      const def = ITEMS[it.type]; if (!def || !def.wall) continue;
      const s = wallSnap(L, it.x, it.y), len = Math.max(6, Math.round((it.w || def.w) * PPM));
      if (s.wall === 'n') {
        const X = Math.round((s.x + 1) * PPM - len / 2), Y = Math.round(s.y * PPM);   // muurvak direct boven de vloer
        if (it.type === 'door') doorFront(g, X, Y + 2, len, 14);
        else windowFront(g, X, Y + 6, len, 5, it.type === 'windowsill');
        continue;
      }
      // van bovenaf: a = langs de muur, r = de kamer in; B = muurlijn in pixels, de muurkap ligt aan de buitenkant
      const vert = s.wall === 'w' || s.wall === 'e', a = vert ? [0, 1] : [1, 0], r = s.wall === 'w' ? [1, 0] : s.wall === 'e' ? [-1, 0] : [0, -1];
      const B = Math.round(((vert ? s.x : s.y) + 1) * PPM), A = Math.round(((vert ? s.y : s.x) + 1) * PPM - len / 2);
      const px = (along, into) => vert ? [B + r[0] * into, A + along] : [A + along, B + r[1] * into];
      const cap = (into0, into1, col) => { g.fillStyle = col; for (let i = 0; i < len; i++) for (let k = into0; k < into1; k++) { const [x, y] = px(i, k); g.fillRect(x, y, 1, 1); } };
      if (it.type === 'door') {
        cap(-5, 0, '#6a5446');                                                     // deuropening in de muur
        g.fillStyle = '#3a2214'; for (let k = 0; k < len; k++) { const [x, y] = px(0, k); g.fillRect(x, y, 2, 2); }   // deurblad, open
        g.fillStyle = 'rgba(58,34,20,.75)';
        for (let i = 0; i <= 28; i++) { const t = i / 28 * Math.PI / 2, [x, y] = px(Math.round(Math.sin(t) * (len - 1)), Math.round(Math.cos(t) * (len - 1))); g.fillRect(x, y, 1, 1); }
      } else {
        cap(-4, -1, '#7aaed2'); cap(-3, -2, '#a8d2ee');                          // glas in de muur
        g.fillStyle = '#f2ead8'; for (let k = -4; k < -1; k++) { let [x, y] = px(0, k); g.fillRect(x, y, 1, 1); [x, y] = px(len - 1, k); g.fillRect(x, y, 1, 1); }
        if (it.type === 'windowsill') cap(0, 2, '#e8e0cc');                       // vensterbank aan de binnenkant
      }
    }
  }

  function drawItem(g, it) {
    const def = ITEMS[it.type]; if (!def || def.wall) return;
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

  /** Soorten lampen: uiterlijk op de kaart, standaardhoogte en waar het licht vandaan komt (dx, dy in pixels, grootte gloed). */
  const LOOKS = [
    { id: 'spot', label: 'Spot', height: 'ceiling', light: [0, 0, 1] },
    { id: 'pendant', label: 'Hanglamp', height: 'ceiling', light: [0, -2, 1] },
    { id: 'floor', label: 'Staande lamp', height: 'sill', light: [0, -16, 1] },
    { id: 'arc', label: 'Booglamp', height: 'sill', light: [8, -12, 1] },
    { id: 'table', label: 'Tafellamp', height: 'table', light: [0, -5, 0.75] },
    { id: 'globe', label: 'Bollamp', height: 'table', light: [0, -3, 0.75] },
    { id: 'rod', label: 'Staaflamp', height: 'floor', light: [0, -9, 1] },
    { id: 'wall', label: 'Wandlamp', height: 'sill', light: [0, -10, 0.85] },
    { id: 'flush', label: 'Plafondlamp', height: 'ceiling', light: [0, 0, 1.3] },
    { id: 'lightbar', label: 'Lichtbalk', height: 'table', light: [0, -7, 0.8] },
    { id: 'strip', label: 'Ledstrip', height: 'table', light: [0, 0, 0.7] },
    { id: 'desk', label: 'Bureaulamp', height: 'table', light: [6, -6, 0.7] },
    { id: 'wallspot', label: 'Wandspot', height: 'sill', light: [0, -10, 0.8] },
    // Kop van een staande lamp met spots (alleen via een armatuur, niet los te kiezen)
    { id: 'head', label: 'Spotkop', height: 'sill', light: [0, -18, 0.85], hidden: true },
  ];

  /**
   * Armaturen: lampen die samen één lamp vormen. De lampen liggen op een rij door (x, y), gap meter uit elkaar,
   * in de richting rot (graden, in stappen van 45). Bij een staande lamp met spots is (x, y) de voet.
   */
  const FIXTURES = {
    rail: { label: 'Rails', look: 'spot', height: 'ceiling', gap: 0.6 },
    bar: { label: 'Hanglamp-balk', look: 'pendant', height: 'ceiling', gap: 0.6 },
    pole: { label: 'Staande lamp met spots', look: 'head', height: 'sill', gap: 0.5 },
  };
  /** Plek van elke lamp in een armatuur: [[id, x, y], ...] in meters. */
  function fixtureSpots(f) {
    const n = f.lamps.length, a = (f.rot || 0) * Math.PI / 180;
    return f.lamps.map((id, k) => { const o = (k - (n - 1) / 2) * f.gap; return [id, f.x + o * Math.cos(a), f.y + o * Math.sin(a)]; });
  }
  /**
   * Richting van een spot in graden (0 = rechts, 90 = naar voren/omlaag op de kaart, in stappen van 45), of null = recht
   * omlaag. Spots in een rails volgen de rails (f.aim), losse spots en wandspots hun eigen richting (L.aims).
   * Alleen voor de weergave op de kaart; de sync rekent vanaf de plek van de lamp.
   */
  function aimOf(L, l) {
    // Eigen richting van de lamp (-1 = recht omlaag) gaat voor die van de rails
    const own = L && L.aims ? L.aims[l.id] : null;
    if (own != null) return own < 0 ? null : own;
    const f = L && (L.fixtures || []).find(x => x.lamps.includes(l.id));
    const a = f ? f.aim : null;
    return a == null ? (l.look === 'wallspot' ? 90 : null) : a;
  }

  /** Kanteling van een wandspot: 'up', 'down' of '' (recht); bewaard in de kamer (L.tilts). */
  const tiltOf = (L, l) => (l.look === 'wallspot' && L && L.tilts && L.tilts[l.id]) || '';

  /** Lengte (m) en richting (graden) van een ledstrip; bewaard in de kamer (L.strips). */
  const stripOf = (L, l) => Object.assign({ len: 1, rot: 0 }, (L && L.strips && L.strips[l.id]) || {});

  /** Lijn van pixels met dikte w (ook schuin). */
  function line(g, x0, y0, x1, y1, w, col) {
    g.fillStyle = col; const n = Math.max(1, Math.ceil(Math.hypot(x1 - x0, y1 - y0))), o = Math.floor(w / 2);
    for (let i = 0; i <= n; i++) g.fillRect(Math.round(x0 + (x1 - x0) * i / n) - o, Math.round(y0 + (y1 - y0) * i / n) - o, w, w);
  }

  /** Rails, balk of voet en paal van de armaturen (de lampen zelf tekent lampFixture eroverheen). */
  function drawFixtures(g, L, lamps) {
    const O = '#2a1a2e', HI = '#6a5a70';
    for (const f of (L.fixtures || [])) {
      const a = (f.rot || 0) * Math.PI / 180, ca = Math.cos(a), sa = Math.sin(a);
      const fx = (f.x + 1) * PPM, fy = (f.y + 1) * PPM, half = (Math.max(1, f.lamps.length) - 1) / 2 * f.gap * PPM + 5;
      if (f.type === 'rail' || f.type === 'bar') {
        const dy = f.type === 'bar' ? -13 : 0;
        const x0 = fx - ca * half, y0 = fy - sa * half + dy, x1 = fx + ca * half, y1 = fy + sa * half + dy;
        line(g, x0, y0, x1, y1, 3, O); line(g, x0, y0, x1, y1, 1, HI);
        // balk: twee ophangsnoeren naar het plafond
        if (f.type === 'bar') { g.fillStyle = O; g.fillRect(Math.round(x0), Math.round(y0) - 6, 1, 5); g.fillRect(Math.round(x1), Math.round(y1) - 6, 1, 5); }
      } else if (f.type === 'pole') {
        const bx = Math.round(fx), by = Math.round(fy);
        g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(bx - 3, by, 8, 2);
        g.fillStyle = O; g.fillRect(bx - 3, by - 1, 7, 2); g.fillRect(bx, by - 17, 1, 17);
        for (const [id, x, y] of fixtureSpots(f)) {
          const l = lamps.find(q => q.id === id); if (!l || l.x < 0) continue;
          line(g, bx, by - 17, Math.round((x + 1) * PPM), Math.round((y + 1) * PPM) - 19, 1, O);
        }
      }
    }
  }

  const lookOf = l => LOOKS.find(k => k.id === (l.look || (l.shape === 'rod' ? 'rod' : '')));

  /** Pixelrijen tekenen: rows = [[dy, van dx, tot dx], ...] rond (x, y). */
  function rows(g, x, y, list) { for (const [dy, a, b] of list) g.fillRect(x + a, y + dy, b - a + 1, 1); }

  /** Kleur voor de gloed: dezelfde tint op volle sterkte, en een factor (0,45..1) voor hoe fel de lamp brandt. */
  function glowOf(c) {
    if (!/^#[0-9a-f]{6}$/i.test(c)) return [c, 1];
    const v = [1, 3, 5].map(i => parseInt(c.slice(i, i + 2), 16)), m = Math.max(...v);
    if (m === 0) return [c, 0];
    return ['#' + v.map(x => Math.round(x * 255 / m).toString(16).padStart(2, '0')).join(''), 0.45 + 0.55 * m / 255];
  }

  /** Twee #rrggbb-kleuren mengen (f = aandeel van b). */
  function mixHex(a, b, f) {
    const p = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
    if (!/^#[0-9a-f]{6}$/i.test(a) || !/^#[0-9a-f]{6}$/i.test(b)) return a;
    const A = p(a), B = p(b);
    return '#' + A.map((v, i) => Math.round(v * (1 - f) + B[i] * f).toString(16).padStart(2, '0')).join('');
  }

  /**
   * Waar je op de kaart op een lamp kunt tikken, in meters ten opzichte van zijn voet: [links, boven, rechts, onder].
   * Dekt de hele getekende lamp (bij een staande lamp of booglamp dus ook de kap), met wat marge.
   */
  function lampBox(l, L) {
    const look = l.look || '';
    let strip = null;
    if (look === 'strip') { const st = stripOf(L, l), a = st.rot * Math.PI / 180, h = st.len * PPM / 2;
      const dx = Math.abs(Math.cos(a)) * h, dy = Math.abs(Math.sin(a)) * h; strip = [-dx - 2, -dy - 2, dx + 2, dy + 2]; }
    const px = strip ? strip : look === 'wall' ? [-5, -15, 5, 1] : look === 'flush' ? [-6, -5, 6, 5] : look === 'lightbar' ? [-3, -14, 3, 1]
      : look === 'desk' ? [-4, -10, 10, 1] : look === 'head' ? [-4, -23, 4, -17] : look === 'wallspot' ? [-5, -15, 5, -4]
      : look === 'pendant' ? [-5, -12, 5, 2] : look === 'floor' ? [-4, -22, 5, 1] : look === 'arc' ? [-4, -18, 12, 1]
      : look === 'table' ? [-4, -9, 5, 3] : look === 'globe' ? [-4, -8, 5, 3] : look === 'spot' ? [-3, -3, 4, 4]
      : l.shape === 'rod' ? [-3, -20, 5, 3] : l.height === 'ceiling' ? [-3, -3, 4, 4]
      : (l.height === 'table' || l.height === 'sill') ? [-3, -4, 4, 4] : [-4, -4, 5, 5];
    const m = 3;
    return [(px[0] - m) / PPM, (px[1] - m) / PPM, (px[2] + m) / PPM, (px[3] + m) / PPM];
  }

  function lampFixture(g, l, color, L) {
    const x = Math.round((l.x + 1) * PPM), y = Math.round((l.y + 1) * PPM);
    const O = '#2a1a2e', SHADE = mixHex('#efe0c0', color, 0.6), SH = 'rgba(30,10,30,.3)';
    const look = l.look || '';
    if (look === 'pendant') {
      // hanglamp: snoer van boven, koepel, licht uit de onderkant
      g.fillStyle = SH; g.fillRect(x - 3, y + 2, 7, 2);
      g.fillStyle = O; g.fillRect(x, y - 12, 1, 7);
      rows(g, x, y, [[-5, -2, 2], [-4, -4, 4], [-3, -4, 4], [-2, -4, 4]]);
      g.fillStyle = SHADE; rows(g, x, y, [[-4, -3, 3], [-3, -3, 3]]);
      g.fillStyle = color; rows(g, x, y, [[-2, -3, 3]]);
      return;
    }
    if (look === 'floor') {
      // staande lamp: voet, paal, kap met licht onderuit
      g.fillStyle = SH; g.fillRect(x - 3, y, 8, 2);
      g.fillStyle = O; g.fillRect(x - 3, y - 1, 7, 2); g.fillRect(x, y - 15, 1, 15);
      rows(g, x, y, [[-22, -2, 2], [-21, -3, 3], [-20, -3, 3], [-19, -4, 4], [-18, -4, 4], [-17, -4, 4], [-16, -4, 4]]);
      g.fillStyle = SHADE; rows(g, x, y, [[-21, -2, 2], [-20, -2, 2], [-19, -3, 3], [-18, -3, 3], [-17, -3, 3]]);
      g.fillStyle = color; rows(g, x, y, [[-16, -3, 3]]);
      return;
    }
    if (look === 'arc') {
      // booglamp: voet, paal omhoog, boog naar rechts, kap boven de bank
      g.fillStyle = SH; g.fillRect(x - 3, y, 8, 2); g.fillRect(x + 5, y - 1, 7, 2);
      g.fillStyle = O; g.fillRect(x - 3, y - 1, 7, 2); g.fillRect(x, y - 14, 1, 14);
      for (const [dx, dy] of [[1, -15], [2, -16], [3, -17], [4, -18], [5, -18], [6, -18], [7, -18], [8, -17]]) g.fillRect(x + dx, y + dy, 1, 1);
      rows(g, x + 8, y, [[-16, -2, 2], [-15, -3, 3], [-14, -3, 3], [-13, -3, 3]]);
      g.fillStyle = SHADE; rows(g, x + 8, y, [[-15, -2, 2], [-14, -2, 2]]);
      g.fillStyle = color; rows(g, x + 8, y, [[-13, -2, 2]]);
      return;
    }
    if (look === 'table') {
      // tafellamp: houten voetje, steeltje, kap
      g.fillStyle = SH; g.fillRect(x - 3, y + 1, 7, 2);
      g.fillStyle = '#6b4430'; g.fillRect(x - 2, y, 5, 2);
      g.fillStyle = O; g.fillRect(x, y - 3, 1, 3);
      rows(g, x, y, [[-9, -2, 2], [-8, -3, 3], [-7, -3, 3], [-6, -4, 4], [-5, -4, 4], [-4, -4, 4]]);
      g.fillStyle = SHADE; rows(g, x, y, [[-8, -2, 2], [-7, -2, 2], [-6, -3, 3], [-5, -3, 3]]);
      g.fillStyle = color; rows(g, x, y, [[-4, -3, 3]]);
      return;
    }
    if (look === 'globe') {
      // bollamp: glazen bol op een klein voetje
      g.fillStyle = SH; g.fillRect(x - 3, y + 1, 7, 2);
      g.fillStyle = O; g.fillRect(x - 2, y, 5, 2);
      rows(g, x, y, [[-8, -1, 1], [-7, -3, 3], [-6, -4, 4], [-5, -4, 4], [-4, -4, 4], [-3, -4, 4], [-2, -3, 3], [-1, -1, 1]]);
      g.fillStyle = color; rows(g, x, y, [[-7, -1, 1], [-6, -3, 3], [-5, -3, 3], [-4, -3, 3], [-3, -3, 3], [-2, -1, 1]]);
      g.fillStyle = 'rgba(255,255,255,.55)'; g.fillRect(x - 2, y - 6, 1, 2);
      return;
    }
    if (look === 'wall') {
      // wandlamp: plaatje tegen de muur met een kapje ervoor, licht naar boven en onder
      g.fillStyle = O; g.fillRect(x - 4, y - 15, 9, 10);
      g.fillStyle = '#4a3a50'; g.fillRect(x - 3, y - 14, 7, 8);
      g.fillStyle = O; g.fillRect(x - 2, y - 13, 5, 7);
      g.fillStyle = SHADE; g.fillRect(x - 1, y - 12, 3, 5);
      g.fillStyle = color; g.fillRect(x - 1, y - 14, 3, 1); g.fillRect(x - 1, y - 6, 3, 1);
      return;
    }
    if (look === 'flush') {
      // plafondlamp: platte ronde schijf
      g.fillStyle = O; rows(g, x, y, [[-5, -2, 2], [-4, -4, 4], [-3, -5, 5], [-2, -5, 5], [-1, -5, 5], [0, -5, 5], [1, -5, 5], [2, -5, 5], [3, -4, 4], [4, -2, 2]]);
      g.fillStyle = SHADE; rows(g, x, y, [[-4, -2, 2], [-3, -4, 4], [-2, -4, 4], [-1, -4, 4], [0, -4, 4], [1, -4, 4], [2, -4, 4], [3, -2, 2]]);
      g.fillStyle = color; rows(g, x, y, [[-2, -2, 2], [-1, -2, 2], [0, -2, 2], [1, -2, 2]]);
      return;
    }
    if (look === 'lightbar') {
      // lichtbalk (zoals een Hue Play): staande balk op een voetje
      g.fillStyle = SH; g.fillRect(x - 3, y, 7, 2);
      g.fillStyle = O; g.fillRect(x - 2, y - 1, 5, 2); g.fillRect(x - 2, y - 14, 4, 13);
      g.fillStyle = color; g.fillRect(x - 1, y - 13, 2, 11);
      return;
    }
    if (look === 'desk') {
      // bureaulamp: voet, knikarm en kopje
      g.fillStyle = SH; g.fillRect(x - 3, y, 8, 2);
      g.fillStyle = O; g.fillRect(x - 3, y - 1, 7, 2);
      for (const [dx, dy] of [[0, -2], [0, -3], [0, -4], [1, -5], [2, -6], [3, -7], [4, -8], [5, -8]]) g.fillRect(x + dx, y + dy, 1, 1);
      rows(g, x + 7, y, [[-10, -2, 1], [-9, -3, 2], [-8, -3, 2], [-7, -3, 2]]);
      g.fillStyle = SHADE; rows(g, x + 7, y, [[-9, -2, 1], [-8, -2, 1]]);
      g.fillStyle = color; rows(g, x + 7, y, [[-7, -2, 1]]);
      return;
    }
    if (look === 'head') {
      // kop van een staande lamp met spots (voet en paal tekent het armatuur)
      g.fillStyle = O; rows(g, x, y, [[-23, -2, 2], [-22, -3, 3], [-21, -3, 3], [-20, -3, 3], [-19, -2, 2]]);
      g.fillStyle = SHADE; rows(g, x, y, [[-22, -2, 2], [-21, -2, 2]]);
      g.fillStyle = color; rows(g, x, y, [[-20, -2, 2], [-19, -1, 1]]);
      return;
    }
    if (look === 'wallspot') {
      // wandspot: plaatje tegen de muur met een kopje dat de gekozen kant op wijst
      const tl = tiltOf(L, l), a = (aimOf(L, l) ?? 90) * Math.PI / 180, hx = Math.round(x + Math.cos(a) * 3),
        hy = Math.round(y - 10 + Math.sin(a) * 3 + (tl === 'up' ? -2 : tl === 'down' ? 2 : 0));
      g.fillStyle = O; g.fillRect(x - 2, y - 14, 5, 8);
      g.fillStyle = '#4a3a50'; g.fillRect(x - 1, y - 13, 3, 6);
      line(g, x, y - 10, hx, hy, 1, O);
      g.fillStyle = O; g.fillRect(hx - 2, hy - 2, 5, 5);
      g.fillStyle = SHADE; g.fillRect(hx - 1, hy - 1, 3, 3);
      g.fillStyle = color; g.fillRect(Math.round(hx + Math.cos(a) * 2), Math.round(hy + Math.sin(a) * 2), 1, 1);
      return;
    }
    if (look === 'strip') {
      // ledstrip: lichtgevende lijn met een lengte en richting
      const st = stripOf(L, l), a = st.rot * Math.PI / 180, h = st.len * PPM / 2, ca = Math.cos(a) * h, sa = Math.sin(a) * h;
      line(g, x - ca, y - sa, x + ca, y + sa, 3, O); line(g, x - ca, y - sa, x + ca, y + sa, 1, color);
      return;
    }
    if (look === 'spot') {
      g.fillStyle = O; g.fillRect(x - 2, y - 3, 5, 7); g.fillRect(x - 3, y - 2, 7, 5);
      g.fillStyle = SHADE; g.fillRect(x - 2, y - 2, 5, 5);
      g.fillStyle = color; g.fillRect(x - 1, y - 1, 3, 3);
      const aim = aimOf(L, l);
      if (aim != null) { const a = aim * Math.PI / 180, nx = Math.round(x + Math.cos(a) * 4), ny = Math.round(y + Math.sin(a) * 4);
        g.fillStyle = O; g.fillRect(nx - 1, ny - 1, 3, 3); g.fillStyle = color; g.fillRect(nx, ny, 1, 1); }
      return;
    }
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
      g.fillStyle = SHADE; g.fillRect(x - 2, y - 2, 5, 5);
      g.fillStyle = color; g.fillRect(x - 1, y - 1, 3, 3);
    } else if (l.height === 'table' || l.height === 'sill') {
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(x - 2, y - 1, 6, 5);
      g.fillStyle = '#2a1a2e'; g.fillRect(x - 3, y - 4, 7, 6);
      g.fillStyle = SHADE; g.fillRect(x - 2, y - 3, 5, 4);
      g.fillStyle = color; g.fillRect(x - 1, y - 2, 3, 2);
      g.fillStyle = '#6b4430'; g.fillRect(x - 1, y + 2, 3, 2);
    } else {
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(x - 3, y - 2, 8, 8);
      g.fillStyle = '#2a1a2e'; g.fillRect(x - 4, y - 4, 9, 9);
      g.fillStyle = SHADE; g.fillRect(x - 3, y - 3, 7, 7);
      g.fillStyle = color; g.fillRect(x - 1, y - 1, 3, 3);
    }
  }

  /** Per frame: basis + (bij sync) donkere kamer met gekleurd licht. */
  let frameCv = null;
  function frame(baseCanvas, L, lamps, live, sync, top) {
    // Eén canvas hergebruiken: elke poll een nieuw canvas maken kost op de tv onnodig geheugen en tijd
    const c = frameCv || (frameCv = document.createElement('canvas'));
    if (c.width !== baseCanvas.width) c.width = baseCanvas.width;
    if (c.height !== baseCanvas.height) c.height = baseCanvas.height;
    const g = c.getContext('2d');
    g.globalCompositeOperation = 'source-over';
    g.clearRect(0, 0, c.width, c.height);
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
      if ((sync && lv.color === '#555555') || lv.on === false) continue;
      const col = lv.color && lv.color !== '#555555' ? lv.color : '#ffd696';
      // Gloed in de kleur op volle sterkte; hoe zacht de lamp brandt zie je aan de doorzichtigheid.
      // Anders geeft een gedimde lamp (bijvoorbeeld warm oranje op 15%) bijna geen gloed, terwijl hij wel licht geeft.
      const [glow, gk] = glowOf(col);
      const x = Math.round((l.x + 1) * PPM), y = Math.round((l.y + 1) * PPM);
      const lk = l.look ? lookOf(l) : null;
      const big = lk ? lk.light[2] : (l.height === 'ceiling' || l.shape === 'rod' ? 1 : 0.75);
      const rings = sync ? [[30, 0.06], [22, 0.07], [14, 0.09], [7, 0.12]] : [[14, 0.05], [7, 0.06]];
      if (lk && lk.id === 'strip') {
        const st = stripOf(L, l);
        for (const [r, a] of rings) { g.globalAlpha = a * gk; g.fillStyle = glow; g.beginPath(); g.ellipse(x, y, st.len * PPM / 2 + r * 0.5, r * 0.6, st.rot * Math.PI / 180, 0, 7); g.fill(); }
      } else if (lk && lk.id !== 'rod') {
        // gloed vanuit de kap (bij een staande lamp hoger, bij een booglamp boven de bank)
        const gx = x + lk.light[0], gy = y + lk.light[1], aim = (lk.id === 'spot' || lk.id === 'wallspot') ? aimOf(L, l) : null;
        if (aim != null) {
          // Gerichte spot: een lichtbundel van ongeveer 75 graden vanaf de spot (achter de spot geen licht), met een lichtvlek waar hij
          // neerkomt (0,8 m verderop, het punt waar de sync voor deze spot vanaf rekent)
          const tl = tiltOf(L, l), reach = tl === 'up' ? 0.75 : tl === 'down' ? 0.7 : 1;
          const a = aim * Math.PI / 180, sp = 0.65, px = gx + Math.cos(a) * 13 * reach, py = gy + Math.sin(a) * 13 * reach + (tl === 'up' ? -9 : tl === 'down' ? 5 : 0);
          for (const [r, al] of rings) {
            g.globalAlpha = al * gk; g.fillStyle = glow;
            g.beginPath(); g.moveTo(gx, gy); g.arc(gx, gy, r * big * 1.5 * reach, a - sp, a + sp); g.closePath(); g.fill();
            g.beginPath(); g.ellipse(px, py, r * big * 0.55, r * big * 0.4, a, 0, 7); g.fill();
          }
        } else for (const [r, a] of rings) { g.globalAlpha = a * gk; g.fillStyle = glow; g.beginPath(); g.arc(gx, gy, r * big, 0, 7); g.fill(); }
      } else if (l.shape === 'rod') {
        // langgerekte gloed langs de staaf
        for (const [r, a] of rings) { g.globalAlpha = a * gk; g.fillStyle = glow; g.beginPath(); g.ellipse(x, y - 9, r * 0.8, r * 1.15, 0, 0, 7); g.fill(); }
      } else {
        for (const [r, a] of rings) { g.globalAlpha = a * gk; g.fillStyle = glow; g.beginPath(); g.arc(x, y, r * big, 0, 7); g.fill(); }
      }
    }
    g.globalAlpha = 1; g.globalCompositeOperation = 'source-over';
    drawFixtures(g, L, lamps);
    for (const l of lamps) {
      if (l.x < 0) continue;
      const lv = live[l.id] || {};
      // Lamp en kap in dezelfde heldere kleur als de gloed (een gedimde lamp zou er anders uit lijken te staan)
      lampFixture(g, l, !lv.online || lv.on === false || (sync && lv.color === '#555555') ? '#55505a' : lv.color && lv.color !== '#555555' ? glowOf(lv.color)[0] : '#ffe2a8', L);
    }
    return c;
  }

  /** Schaalt het pixelbeeld scherp naar een zichtbaar canvas. Geeft schaal en verschuiving terug. */
  function present(view, img, maxW, maxH, integer) {
    let s = Math.min(maxW / img.width, maxH / img.height);
    if (integer && s >= 2) s = Math.floor(s);
    const W = Math.round(img.width * s), H = Math.round(img.height * s);
    const dpr = window.devicePixelRatio || 1;
    // Maat alleen zetten als hij verandert: zelfs dezelfde maat zetten maakt het canvas opnieuw aan
    if (view.width !== W * dpr) view.width = W * dpr;
    if (view.height !== H * dpr) view.height = H * dpr;
    if (view.style.width !== W + 'px') { view.style.width = W + 'px'; view.style.height = H + 'px'; }
    const g = view.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.imageSmoothingEnabled = false;
    g.clearRect(0, 0, W, H);
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

  /** Kalibratiebanden van onder naar boven: verzadigde kleuren met gelijke stappen op de kleurcirkel. */
  function calibColors(n) {
    return Array.from({ length: n }, (_, i) => `hsl(${Math.round(i * 300 / Math.max(1, n - 1))},100%,50%)`);
  }
  /** Hoogte op het tv-vlak (-1 = onderrand, 1 = bovenrand) die bij band i hoort, en terug. */
  const bandY = (i, n) => -1 + (2 * i + 1) / n;
  /** Vaste hoogte die het dichtst bij een gekalibreerde hoogte ligt (voor de tekening en de uitleg). */
  const presetFor = y => y >= 0.7 ? 'ceiling' : y >= 0.25 ? 'sill' : y >= -0.3 ? 'table' : 'floor';
  // Plek in halve banden (0 = onderste band, 0.5 = tussen de onderste twee), of 'above'/'below'/null
  const bandPos = (y, n) => y == null ? null : y > 1.05 ? 'above' : y < -1.05 ? 'below' : Math.min(n - 1, Math.max(0, Math.round(((y + 1) / 2 * n - 0.5) * 2) / 2));

  return { PPM, ITEMS, SPRITES, FLOORS, LOOKS, lampFixture, floorPixel, base, frame, present, migrate, isFloor, rotated, calibColors, bandY, bandPos, presetFor, lampBox, wallSnap, FIXTURES, fixtureSpots, drawFixtures, stripOf, aimOf, tiltOf };
})();
