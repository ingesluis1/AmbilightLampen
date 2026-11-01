/*
 * Tekenmotor voor de kamer in pixel-art-stijl (1 meter = 16 pixels, daarna scherp opgeschaald).
 * Wordt gebruikt door het tv-scherm (tv.html) en de telefoon-editor (index.html).
 */
const Room = (() => {
  const PPM = 16;

  // cat = groep in de meubelkiezer (zie CATS); top = klein ding dat op een tafel of bureau staat (wordt erbovenop getekend)
  const ITEMS = {
    sofa:     { name: 'Bank',       w: 2.2, d: 0.9, cat: 'living' },
    corner:   { name: 'Hoekbank',   w: 2.6, d: 2.0, cat: 'living' },
    armchair: { name: 'Fauteuil',   w: 0.9, d: 0.9, cat: 'living' },
    coffee:   { name: 'Salontafel', w: 1.0, d: 0.55, cat: 'living' },
    dining:   { name: 'Eettafel',   w: 1.6, d: 1.5, cat: 'living' },
    plant:    { name: 'Plant',      w: 0.5, d: 0.5, cat: 'other' },
    rug:      { name: 'Kleed',      w: 2.4, d: 1.6, cat: 'other' },
    cabinet:  { name: 'Kast',       w: 1.2, d: 0.45, cat: 'other' },
    bed:      { name: 'Bed',        w: 1.6, d: 2.0, cat: 'bedroom' },
    bookshelf:{ name: 'Boekenkast', w: 1.0, d: 0.35, cat: 'living' },
    sidetable:{ name: 'Bijzettafel', w: 0.5, d: 0.5, cat: 'living' },
    roundtable:{ name: 'Ronde eettafel', w: 1.5, d: 1.5, cat: 'living' },
    desk:     { name: 'Bureau',     w: 1.4, d: 1.1, cat: 'work' },
    chair:    { name: 'Stoel',      w: 0.5, d: 0.5, cat: 'other' },
    wardrobe: { name: 'Kledingkast', w: 1.6, d: 0.6, cat: 'bedroom' },
    kitchen:  { name: 'Keukenblok', w: 2.4, d: 0.65, cat: 'kitchen' },
    fireplace:{ name: 'Haard',      w: 1.4, d: 0.45, cat: 'living' },
    piano:    { name: 'Piano',      w: 1.5, d: 0.6, cat: 'living' },
    beanbag:  { name: 'Zitzak',     w: 0.8, d: 0.8, cat: 'living' },
    pouf:     { name: 'Poef',       w: 0.55, d: 0.55, cat: 'living' },
    // keuken (zelfde diepte als het keukenblok, zodat ze er netjes naast passen)
    fridge:   { name: 'Koelkast',   w: 0.6, d: 0.65, cat: 'kitchen' },
    stove:    { name: 'Fornuis',    w: 0.6, d: 0.65, cat: 'kitchen' },
    hob:      { name: 'Kookplaat',  w: 0.6, d: 0.65, cat: 'kitchen' },
    oven:     { name: 'Oven',       w: 0.6, d: 0.65, cat: 'kitchen' },
    sink:     { name: 'Spoelbak',   w: 0.8, d: 0.65, cat: 'kitchen' },
    dishwasher:{ name: 'Vaatwasser', w: 0.6, d: 0.65, cat: 'kitchen' },
    island:   { name: 'Kookeiland', w: 1.8, d: 1.3, cat: 'kitchen' },
    // slaapkamer
    singlebed:{ name: 'Eenpersoonsbed', w: 0.9, d: 2.0, cat: 'bedroom' },
    nightstand:{ name: 'Nachtkastje', w: 0.45, d: 0.4, cat: 'bedroom' },
    vanity:   { name: 'Kaptafel',   w: 1.0, d: 0.8, cat: 'bedroom' },
    // werkplek
    computer: { name: 'Computer',   w: 0.8, d: 0.45, cat: 'work', top: true },
    dualdesk: { name: 'Bureau met twee schermen', w: 1.6, d: 1.2, cat: 'work' },
    laptop:   { name: 'Laptop',     w: 0.35, d: 0.3, cat: 'work', top: true },
    pctower:  { name: 'Pc-kast',    w: 0.25, d: 0.5, cat: 'work' },
    officechair:{ name: 'Bureaustoel', w: 0.6, d: 0.6, cat: 'work' },
    gamingchair:{ name: 'Gamestoel', w: 0.7, d: 0.7, cat: 'work' },
    // gang
    coatrack: { name: 'Kapstok',    w: 1.2, d: 0.45, cat: 'hall' },
    shoerack: { name: 'Schoenenkast', w: 0.8, d: 0.35, cat: 'hall' },
    stairs:   { name: 'Trap',       w: 0.9, d: 2.6, cat: 'hall' },
    // badkamer en wc
    toilet:   { name: 'Wc',         w: 0.4, d: 0.65, cat: 'bath' },
    washbasin:{ name: 'Wastafel',   w: 0.6, d: 0.45, cat: 'bath' },
    shower:   { name: 'Douche',     w: 0.9, d: 0.9, cat: 'bath' },
    bath:     { name: 'Bad',        w: 1.7, d: 0.75, cat: 'bath' },
    washer:   { name: 'Wasmachine', w: 0.6, d: 0.6, cat: 'bath' },
    dryer:    { name: 'Droger',     w: 0.6, d: 0.6, cat: 'bath' },
    towelrail:{ name: 'Handdoekrek', w: 0.6, d: 0.25, cat: 'bath' },
    litterbox:{ name: 'Elektrische kattenbak', w: 0.6, d: 0.65, cat: 'bath' },
    // Muuronderdelen: springen naar de dichtstbijzijnde muur (wall), plaatje van voren (thumbD)
    door:     { name: 'Deur',       w: 0.9, d: 0.3, wall: true, thumbD: 1.0, cat: 'doors' },
    window:   { name: 'Raam',       w: 1.2, d: 0.3, wall: true, thumbD: 0.8, cat: 'doors' },
    windowsill:{ name: 'Vensterbank', w: 1.2, d: 0.3, wall: true, thumbD: 0.8, cat: 'doors' },
  };

  /** Groepen in de meubelkiezer, in deze volgorde. */
  const CATS = [
    { id: 'living', name: 'Woonkamer' }, { id: 'kitchen', name: 'Keuken' }, { id: 'bedroom', name: 'Slaapkamer' },
    { id: 'work', name: 'Werkplek' }, { id: 'bath', name: 'Badkamer' }, { id: 'hall', name: 'Gang' },
    { id: 'other', name: 'Overig' }, { id: 'doors', name: 'Deuren en ramen' },
  ];
  /** Groep die de kiezer als eerste laat zien bij een kamernaam (bijv. "Keuken" → kitchen). */
  function catForRoom(name) {
    const n = String(name || '').toLowerCase();
    return /keuken|kitchen/.test(n) ? 'kitchen' : /slaap|bed/.test(n) ? 'bedroom' : /bad|wc|toilet|douche|bath|shower/.test(n) ? 'bath'
      : /gang|hal\b|hall|entree|overloop|landing/.test(n) ? 'hall' : /\bpc\b|werk|kantoor|studeer|office|game|gaming|computer/.test(n) ? 'work' : 'living';
  }

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
        col = L.planks === 'v' ? floorPixel(mat(L, cx, cy), py, px) : floorPixel(mat(L, cx, cy), px, py);   // staand: patroon een kwartslag
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

    // Kleden eerst, dan meubels van achter naar voren; kleine spullen die op een tafel of bureau staan (top) als laatste
    const onTop = it => (ITEMS[it.type] || {}).top ? 1 : 0;
    const items = (L.items || []).slice().sort((a, b) => (a.type === 'rug' ? -1 : 0) - (b.type === 'rug' ? -1 : 0) || onTop(a) - onTop(b) || a.y - b.y);
    if (VIEW34 && window.Furniture34) { draw34All(g, L, items, lamps); return c; }
    for (const it of items) drawItem(g, it);
    if (L.tv) drawTv(g, L.tv);
    return c;
  }

  function walls(g, L) {
    const TOP = '#3b2240', HI = '#7a4a72', CAP = '#4a2a4f';
    // Behang in de kleur van de kamer (L.wallColor), zacht gemaakt; zonder kleur het oude zalmroze
    const wc = /^#[0-9a-f]{6}$/i.test(L.wallColor || '') ? L.wallColor : null;
    const WP = wc ? mixHex(wc, '#f4e6d4', 0.5) : '#dca18a', WS = wc ? mixHex(wc, '#c8a890', 0.45) : '#cc8d78', WT = wc ? mixHex(wc, '#fbf0e2', 0.65) : '#e7b39c';
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
          g.fillStyle = ((X + i) % 6 === 0) ? WS : WP;
          g.fillRect(X + i, Y + 5, 1, 8);
        }
        g.fillStyle = WT; g.fillRect(X, Y + 5, PPM, 1);
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

  /** Aanrechtkastje zoals het keukenblok: grijs werkblad, houten front met een naad in het midden. */
  function counter(g, w, d) {
    shadow(g, w, d);
    box(g, 0, 0, w, d, '#d8d2c4', '#4a4440');
    g.fillStyle = '#9a6438'; g.fillRect(1, d - 3, w - 2, 2);
    g.fillStyle = '#6a4024'; g.fillRect(Math.round(w / 2), d - 3, 1, 2);
  }
  /** Vier gaspitten (ring met donker hart) in het vlak x, y, w, h. */
  function gasRings(g, x, y, w, h) {
    for (const [fx, fy] of [[0.27, 0.3], [0.73, 0.3], [0.27, 0.74], [0.73, 0.74]]) {
      const cx = x + Math.round(w * fx), cy = y + Math.round(h * fy);
      g.fillStyle = '#6a6a74'; g.fillRect(cx - 1, cy - 1, 3, 3);
      g.fillStyle = '#16141a'; g.fillRect(cx, cy, 1, 1);
    }
  }
  /** Bureaustoel van bovenaf in het vak x, y, w, h: stervoet, zitting, rugleuning achter (boven). */
  function officeChairAt(g, x, y, w, h) {
    g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(x + 2, y + 2, w - 2, h - 2);
    g.fillStyle = '#16141c';
    const cx = x + Math.floor(w / 2), cy = y + Math.floor(h / 2);
    g.fillRect(x, y + h - 1, 2, 1); g.fillRect(x + w - 2, y + h - 1, 2, 1); g.fillRect(x, cy, 1, 2); g.fillRect(x + w - 1, cy, 1, 2); g.fillRect(cx, y + h - 1, 1, 1);
    box(g, x + 1, y + 2, w - 2, h - 3, '#4a4a5a', '#16141c');
    g.fillStyle = '#5a5a6a'; g.fillRect(x + 2, y + h - 3, w - 4, 1);
    g.fillStyle = '#3a3a48'; g.fillRect(x + 2, y + 3, 1, h - 6); g.fillRect(x + w - 3, y + 3, 1, h - 6);
    box(g, x + 2, y, w - 4, 3, '#2a2a36', '#16141c');
    g.fillStyle = '#5a5a6a'; g.fillRect(x + 3, y + 1, w - 6, 1);
  }
  /** Wasmachine of droger: wit, bedieningsstrook achter, deurtje met glas en wasgoed. */
  function laundry(g, w, d, dry) {
    shadow(g, w, d);
    box(g, 0, 0, w, d, '#eef0ee', '#4a4440');
    g.fillStyle = '#d0d4d8'; g.fillRect(1, 1, w - 2, 2);
    g.fillStyle = '#7a848e'; g.fillRect(w - 3, 1, 1, 2);
    g.fillStyle = dry ? '#e0904a' : '#5a8ac0'; g.fillRect(2, 1, Math.max(1, Math.round(w / 3)), 1);
    const r = Math.max(2, Math.min(w, d - 3) / 2 - 0.8), cy = 3 + (d - 3) / 2;
    disc(g, w / 2, cy, r, r, '#aab4be', '#5a646e');
    disc(g, w / 2, cy, r - 1, r - 1, dry ? '#4a4656' : '#5a7ab0', dry ? '#4a4656' : '#5a7ab0');
    g.fillStyle = dry ? '#e0b050' : '#c0604a'; g.fillRect(Math.round(w / 2) - 1, Math.round(cy), 2, 1);
    g.fillStyle = dry ? '#e8e0cc' : '#a8d2ee'; g.fillRect(Math.round(w / 2) - 1, Math.round(cy) - 1, 1, 1);
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
    // ---------- keuken ----------
    fridge(g, w, d) {
      // koelkast: wit, vriesvak bovenin, twee handgrepen
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#eef0ee', '#4a4440');
      g.fillStyle = '#ffffff'; g.fillRect(1, 1, w - 2, 1);
      g.fillStyle = '#c8ccd0'; g.fillRect(w - 2, 2, 1, d - 3);
      const fz = Math.max(3, Math.round(d * 0.32));
      g.fillStyle = '#9aa2aa'; g.fillRect(1, fz, w - 2, 1);
      g.fillStyle = '#7a848e'; g.fillRect(w - 4, 2, 1, fz - 3); g.fillRect(w - 4, fz + 2, 1, Math.max(1, d - fz - 4));
      g.fillStyle = '#e07a6a'; g.fillRect(2, fz + 2, 1, 1); g.fillStyle = '#5a8ac0'; g.fillRect(3, fz + 3, 1, 1);
    },
    stove(g, w, d) {
      // vrijstaand fornuis: gaspitten boven, knoppen en ovenruit aan de voorkant
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#aab4be', '#3a3e44');
      g.fillStyle = '#c8d0d8'; g.fillRect(1, 1, w - 2, 1);
      const th = Math.max(4, d - 6);
      g.fillStyle = '#2a2a30'; g.fillRect(1, 2, w - 2, th);
      gasRings(g, 1, 2, w - 2, th);
      g.fillStyle = '#1e1a20';
      for (let x = 2; x < w - 2; x += 2) g.fillRect(x, th + 2, 1, 1);
      box(g, 1, d - 3, w - 2, 2, '#e0904a', '#2a2a30'); g.fillStyle = '#3a3440'; g.fillRect(2, d - 3, w - 4, 1);
      g.fillStyle = '#e0904a'; g.fillRect(2, d - 2, w - 4, 1);
    },
    hob(g, w, d) {
      // aanrechtkastje met inductiekookplaat (zwart glas met vier kookzones)
      counter(g, w, d);
      const th = Math.max(4, d - 5);
      box(g, 1, 1, w - 2, th, '#2a2a30', '#141418');
      g.fillStyle = '#3a3a44'; g.fillRect(2, 2, w - 4, 1);
      for (const [fx, fy] of [[0.3, 0.32], [0.7, 0.32], [0.3, 0.72], [0.7, 0.72]]) {
        const cx = 1 + Math.round((w - 2) * fx), cy = 1 + Math.round(th * fy);
        g.fillStyle = '#5a5a66'; g.fillRect(cx - 1, cy - 1, 2, 2);
      }
      g.fillStyle = '#d0605a'; g.fillRect(w - 3, th - 1, 1, 1);
    },
    oven(g, w, d) {
      // hoge keukenkast met inbouwoven, van bovenaf (net als de koelkast): houten bovenkant met nerf,
      // aan de voorkant de rvs-rand van de ovendeur met greep en een randje warm licht
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#9a6438', '#3a2214');
      g.fillStyle = '#b87c4a'; g.fillRect(1, 1, w - 2, 1);
      g.fillStyle = '#8a5630';
      for (let y = 3; y < d - 4; y += 3) g.fillRect(2, y, w - 4, 1);
      g.fillStyle = '#aab4be'; g.fillRect(1, d - 3, w - 2, 1);
      g.fillStyle = '#5a646e'; g.fillRect(Math.max(2, Math.round(w * 0.25)), d - 4, Math.max(2, Math.round(w * 0.5)), 1);
      g.fillStyle = '#e0904a'; g.fillRect(2, d - 2, w - 4, 1);
    },
    sink(g, w, d) {
      // aanrechtkastje met spoelbak, kraan aan de achterkant en een afdruiprek
      counter(g, w, d);
      const bw = Math.max(5, Math.round(w * 0.5)), bx = 2;
      box(g, bx, 2, bw, d - 6, '#8a949e', '#5a646e'); g.fillStyle = '#aab4be'; g.fillRect(bx + 1, 2 + 1, bw - 2, 1);
      g.fillStyle = '#5a646e'; g.fillRect(bx + Math.floor(bw / 2), d - 6, 1, 1);
      g.fillStyle = '#7a848e'; g.fillRect(bx + Math.floor(bw / 2), 1, 1, 2); g.fillStyle = '#c8d0d8'; g.fillRect(bx + Math.floor(bw / 2), 1, 1, 1);
      g.fillStyle = '#b8b2a6';
      for (let x = bx + bw + 1; x < w - 2; x += 2) g.fillRect(x, 3, 1, d - 7);
    },
    dishwasher(g, w, d) {
      // aanrechtkastje met vaatwasser: rvs-front met bedieningsstrip en lampje
      counter(g, w, d);
      g.fillStyle = '#aab4be'; g.fillRect(1, d - 3, w - 2, 2);
      g.fillStyle = '#3a3a48'; g.fillRect(1, d - 4, w - 2, 1);
      g.fillStyle = '#6ad06a'; g.fillRect(w - 3, d - 4, 1, 1);
      g.fillStyle = '#7a848e'; g.fillRect(2, d - 2, w - 4, 1);
      g.fillStyle = '#c4beb2'; g.fillRect(2, 3, w - 4, 1); g.fillRect(2, 5, w - 4, 1);
    },
    island(g, w, d) {
      // kookeiland: werkblad met kookplaat en fruitschaal, krukjes aan de voorkant
      const td = Math.max(8, d - 6);
      const n = w >= 26 ? 3 : 2;
      for (let i = 0; i < n; i++) {
        const cx = Math.round(w * (i + 0.5) / n);
        disc(g, cx + 1, td + 3.5, 2.5, 2.5, 'rgba(30,10,30,.3)', 'rgba(30,10,30,.3)');
        disc(g, cx, td + 2.5, 2.5, 2.5, '#8a5a35', '#3a2214');
        g.fillStyle = '#a8744a'; g.fillRect(cx - 1, td + 1, 2, 1);
      }
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(1, 1, w, td + 1);
      box(g, 0, 0, w, td, '#9a6438', '#3a2214');
      box(g, 1, 1, w - 2, td - 3, '#e8e2d6', '#9a948a');
      g.fillStyle = '#f6f2ea'; g.fillRect(2, 2, w - 4, 1);
      g.fillStyle = '#6a4024';
      for (let x = Math.round(w / 4); x < w - 2; x += Math.round(w / 4)) g.fillRect(x, td - 2, 1, 1);
      const kw = Math.max(6, Math.round(w * 0.3)), kh = Math.max(4, td - 7);
      box(g, 3, 3, kw, kh, '#2a2a30', '#141418');
      g.fillStyle = '#4a4a54';
      for (const [fx, fy] of [[0.28, 0.3], [0.72, 0.3], [0.28, 0.72], [0.72, 0.72]]) g.fillRect(3 + Math.round(kw * fx) - 1, 3 + Math.round(kh * fy) - 1, 2, 2);
      const fx0 = w - 9, fy0 = Math.round(td / 2) - 2;
      disc(g, fx0 + 2.5, fy0 + 2, 2.5, 2, '#c98a52', '#7a4a2a');
      g.fillStyle = '#e05a4a'; g.fillRect(fx0 + 1, fy0 + 1, 1, 1); g.fillStyle = '#e8c84a'; g.fillRect(fx0 + 3, fy0 + 1, 1, 1);
      g.fillStyle = '#6dbb5a'; g.fillRect(fx0 + 2, fy0 + 2, 1, 1);
    },
    // ---------- slaapkamer ----------
    singlebed(g, w, d) {
      // eenpersoonsbed: één kussen, groen dekbed
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#7a4a2a', '#3a2214');
      box(g, 1, 1, w - 2, d - 2, '#e8e2d6', '#9a948a');
      g.fillStyle = '#ffffff'; g.fillRect(3, 3, w - 6, 4);
      const t = Math.round(d * 0.35);
      box(g, 1, t, w - 2, d - t - 1, '#5e8a5a', '#3f6b45');
      g.fillStyle = '#7aa874'; g.fillRect(2, t + 1, w - 4, 1);
      g.fillStyle = '#4c7a4c'; g.fillRect(2, t + 4, w - 4, 1);
    },
    nightstand(g, w, d) {
      // nachtkastje: lade met knopje, wekker en boekje erop
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#9a6438', '#3a2214');
      g.fillStyle = '#b87c4a'; g.fillRect(1, 1, w - 2, 1);
      g.fillStyle = '#7a4a2a'; g.fillRect(1, d - 2, w - 2, 1);
      g.fillStyle = '#e0c070'; g.fillRect(Math.floor(w / 2), d - 2, 1, 1);
      g.fillStyle = '#e8e0cc'; g.fillRect(1, 2, 2, 2); g.fillStyle = '#d0605a'; g.fillRect(2, 2, 1, 1);
      if (w > 5) { g.fillStyle = '#4f7aa8'; g.fillRect(w - 3, 2, 2, Math.max(1, d - 5)); }
    },
    vanity(g, w, d) {
      // kaptafel tegen de muur: spiegel, make-up en een krukje ervoor
      const td = Math.max(5, Math.round(d * 0.58));
      const sx = w / 2;
      disc(g, sx + 1, td + (d - td) / 2 + 1, 2.5, (d - td) / 2 - 0.5, 'rgba(30,10,30,.3)', 'rgba(30,10,30,.3)');
      disc(g, sx, td + (d - td) / 2, 2.5, (d - td) / 2 - 0.5, '#e8a0b0', '#7a3a4a');
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(1, 1, w, td + 1);
      box(g, 0, 0, w, td, '#efe6da', '#5a4a40');
      const mw = Math.max(5, Math.round(w * 0.5)), mx = Math.round((w - mw) / 2);
      g.fillStyle = '#e0c070'; g.fillRect(mx - 1, 1, mw + 2, 2);
      g.fillStyle = '#a8d2ee'; g.fillRect(mx, 1, mw, 1); g.fillStyle = '#d8eefa'; g.fillRect(mx + 1, 1, 1, 1);
      g.fillStyle = '#e07a8a'; g.fillRect(2, td - 3, 1, 2);
      g.fillStyle = '#c8a0d8'; g.fillRect(4, td - 3, 2, 2);
      g.fillStyle = '#f2ead8'; g.fillRect(w - 4, td - 3, 2, 1);
      g.fillStyle = '#c8b8a8'; g.fillRect(1, td - 2, w - 2, 1);
      g.fillStyle = '#e0c070'; g.fillRect(Math.round(w / 4), td - 2, 1, 1); g.fillRect(Math.round(w * 3 / 4), td - 2, 1, 1);
    },
    // ---------- werkplek ----------
    computer(g, w, d) {
      // losse computer (op een bureau of tafel zetten): pc-kast links, scherm, toetsenbord en muis
      const tw = Math.max(3, Math.round(w * 0.22));
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(1, 1, tw, d); g.fillRect(tw + 2, 1, w - tw - 2, 3);
      box(g, 0, 0, tw, d, '#2a2a30', '#141418');
      g.fillStyle = '#6ad0e0'; g.fillRect(1, d - 3, tw - 2, 1); g.fillStyle = '#c070e0'; g.fillRect(1, 2, tw - 2, 1);
      const mx = tw + 2, mw = w - tw - 3;
      g.fillStyle = '#1e1a24'; g.fillRect(mx, 0, mw, 3); g.fillStyle = '#5a8ac0'; g.fillRect(mx + 1, 1, mw - 2, 1);
      g.fillStyle = '#3a3a48'; g.fillRect(mx + Math.floor(mw / 2) - 1, 3, 2, 1);
      const kw = Math.max(4, mw - 4);
      g.fillStyle = '#d8d2c4'; g.fillRect(mx + 1, d - 2, kw, 1); g.fillStyle = '#8a8478'; g.fillRect(mx + 1, d - 1, kw, 1);
      g.fillStyle = '#d8d2c4'; g.fillRect(mx + kw + 2, d - 2, 1, 1);
    },
    dualdesk(g, w, d) {
      // bureau met twee schermen, pc-kast eronder en een bureaustoel ervoor
      const td = Math.max(7, Math.round(d * 0.55));
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(1, 1, w, td + 1);
      box(g, 0, 0, w, td, '#c9a06a', '#4a2a1a');
      g.fillStyle = '#e0bc86'; g.fillRect(1, 1, w - 2, 1);
      const mw = Math.max(5, Math.round(w * 0.27)), gap = 1, mx = Math.round(w / 2 - mw - gap / 2);
      for (const x of [mx, mx + mw + gap]) {
        g.fillStyle = '#1e1a24'; g.fillRect(x, 2, mw, 2); g.fillStyle = '#5a8ac0'; g.fillRect(x + 1, 2, mw - 2, 1);
      }
      g.fillStyle = '#7ab0e0'; g.fillRect(mx + 2, 2, 1, 1);
      g.fillStyle = '#d8d2c4'; g.fillRect(Math.round(w / 2) - 4, td - 3, 8, 1); g.fillRect(Math.round(w / 2) + 6, td - 3, 1, 1);
      g.fillStyle = '#e8e0cc'; g.fillRect(3, td - 4, 2, 2);
      box(g, w - 6, 1, 4, td - 2, '#2a2a30', '#141418'); g.fillStyle = '#6ad0e0'; g.fillRect(w - 5, td - 3, 2, 1);
      const cw = 8, ch = Math.max(5, d - td - 1), cx = Math.round((w - cw) / 2);
      officeChairAt(g, cx, td + 1, cw, ch);
    },
    laptop(g, w, d) {
      // opengeklapte laptop: scherm achter, toetsenbord voor
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(1, 1, w, d);
      const sh = Math.max(1, Math.floor(d / 3));
      g.fillStyle = '#1e1a24'; g.fillRect(0, 0, w, sh + 1); g.fillStyle = '#5a8ac0'; g.fillRect(1, 0, w - 2, sh);
      box(g, 0, sh + 1, w, d - sh - 1, '#c8d0d8', '#5a646e');
      g.fillStyle = '#7a848e'; if (d - sh - 1 > 3) g.fillRect(1, sh + 2, w - 2, 1);
    },
    pctower(g, w, d) {
      // pc-kast op de grond: zwarte kast met gekleurde verlichting, voorkant onder
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#2a2a30', '#141418');
      g.fillStyle = '#3a3a48'; g.fillRect(1, 1, w - 2, 1);
      g.fillStyle = '#c070e0'; g.fillRect(1, 2, 1, d - 4);
      g.fillStyle = '#6ad0e0'; g.fillRect(w - 2, 2, 1, d - 4);
      g.fillStyle = '#5a5a6a'; g.fillRect(1, d - 2, w - 2, 1);
    },
    officechair(g, w, d) {
      // bureaustoel: rugleuning achter, stervoet, zitting
      officeChairAt(g, 0, 0, w, d);
    },
    gamingchair(g, w, d) {
      // gamestoel: hoge zwarte rugleuning met rode banen, armleuningen
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(2, 1, w - 2, d);
      g.fillStyle = '#16141c';
      for (const [fx, fy] of [[0, 1], [1, 1], [0.5, 1]]) g.fillRect(Math.round((w - 1) * fx), Math.round((d - 1) * fy), 1, 1);
      const bh = Math.max(3, Math.round(d * 0.38));
      box(g, 1, 0, w - 2, bh, '#2a2632', '#100e14');
      g.fillStyle = '#c0404a'; g.fillRect(3, 1, 1, bh - 2); g.fillRect(w - 4, 1, 1, bh - 2);
      g.fillStyle = '#e8e0cc'; g.fillRect(Math.floor(w / 2), 1, 1, 1);
      box(g, 0, bh - 1, 2, d - bh - 1, '#c0404a', '#100e14'); box(g, w - 2, bh - 1, 2, d - bh - 1, '#c0404a', '#100e14');
      box(g, 2, bh - 1, w - 4, d - bh, '#2a2632', '#100e14');
      g.fillStyle = '#c0404a'; g.fillRect(Math.floor(w / 2), bh, 1, d - bh - 2);
      g.fillStyle = '#4a4656'; g.fillRect(3, d - 2, w - 6, 1);
    },
    // ---------- gang ----------
    coatrack(g, w, d) {
      // kapstok aan de muur, van bovenaf: houten lat met messing haken. Jassen zie je van boven als brede schouders
      // (ongeveer 40 cm) met een kraag in het midden en de mouwen langs de zijkanten; aan één haak een gestreepte sjaal
      const cols = [['#c0504a', '#7a2a2a'], ['#4f7aa8', '#2a3e66'], ['#5a9a5a', '#2f5a3a'], ['#e0b050', '#9a6a20'], ['#8a6a9a', '#4a3456']];
      const cw = Math.max(4, Math.round(0.4 * PPM)), n = Math.max(1, Math.floor((w - 1) / (cw + 1)));
      const gap = Math.max(1, Math.floor((w - n * cw) / (n + 1)));
      g.fillStyle = 'rgba(30,10,30,.28)'; g.fillRect(1, 2, w - 1, d - 1);
      box(g, 0, 0, w, 2, '#8a5a35', '#3a2214');
      g.fillStyle = '#b07a48'; g.fillRect(1, 0, w - 2, 1);
      const h = d - 2;
      for (let i = 0; i < n; i++) {
        const x = gap + i * (cw + gap), mid = x + Math.floor(cw / 2);
        g.fillStyle = '#e0c070'; g.fillRect(mid, 1, 1, 1);   // haak
        if (i === n - 1 && n > 2) {
          // sjaal over de laatste haak: twee gestreepte panden naast elkaar
          for (let y = 2; y < d; y++) { g.fillStyle = y % 2 ? '#e8e0cc' : '#c0504a'; g.fillRect(mid - 1, y, 1, 1); g.fillRect(mid + 1, y, 1, 1) }
          continue;
        }
        const [c, o] = cols[i % cols.length];
        g.fillStyle = o; g.fillRect(x + 1, 2, cw - 2, h); g.fillRect(x, 3, cw, h - 1);            // schouders afgerond
        g.fillStyle = c; g.fillRect(x + 1, 3, cw - 2, h - 2);
        g.fillStyle = o; g.fillRect(x + 1, 4, 1, Math.max(1, h - 3)); g.fillRect(x + cw - 2, 4, 1, Math.max(1, h - 3));   // mouwen
        g.fillStyle = 'rgba(255,255,255,.28)'; g.fillRect(x + 2, 3, Math.max(1, Math.floor(cw / 2) - 2), 1);              // licht op de schouder
        g.fillStyle = o; g.fillRect(mid - 1, 3, 2, 1); g.fillStyle = '#e8e0cc'; g.fillRect(mid, 2, 1, 1);                 // kraag en hangertje
      }
    },
    shoerack(g, w, d) {
      // schoenenkast: lage kast met klepdeurtjes, twee paar schoenen erop
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#9a6438', '#3a2214');
      g.fillStyle = '#b87c4a'; g.fillRect(1, 1, w - 2, 1);
      g.fillStyle = '#7a4a2a'; g.fillRect(Math.round(w / 2), 2, 1, d - 3);
      g.fillStyle = '#e0c070'; g.fillRect(Math.round(w / 4), d - 2, 2, 1); g.fillRect(Math.round(w * 3 / 4) - 1, d - 2, 2, 1);
      const pair = (x, c, s) => { g.fillStyle = c; g.fillRect(x, 2, 1, d - 4); g.fillRect(x + 2, 2, 1, d - 4); g.fillStyle = s; g.fillRect(x, 2, 1, 1); g.fillRect(x + 2, 2, 1, 1); };
      if (d > 4) { pair(2, '#e8e0cc', '#c0504a'); if (w > 10) pair(Math.round(w / 2) + 2, '#5a8ac0', '#334a78'); }
    },
    stairs(g, w, d) {
      // trap die naar achteren (boven) omhoog gaat: treden met lichte neus en donkere stootrand, loper in het midden,
      // bovenaan verdwijnt hij in het donker (het gat naar boven); leuning rechts
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#5a3820', '#3a2214');
      const step = 4, n = Math.max(2, Math.floor((d - 2) / step)), top = d - 1 - n * step;
      const rw = Math.max(2, Math.round((w - 4) * 0.4)), rx = 1 + Math.floor((w - 3 - rw) / 2);
      for (let i = 0; i < n; i++) {
        const y = d - 1 - (i + 1) * step;
        g.fillStyle = '#c9a06a'; g.fillRect(1, y, w - 4, step);
        g.fillStyle = '#e0bc86'; g.fillRect(1, y, w - 4, 1);
        g.fillStyle = '#6a4024'; g.fillRect(1, y + step - 1, w - 4, 1);
        g.fillStyle = '#9a3a4a'; g.fillRect(rx, y, rw, step - 1); g.fillStyle = '#b8505a'; g.fillRect(rx, y, rw, 1);
        g.fillStyle = '#6a2a34'; g.fillRect(rx, y + step - 1, rw, 1);
      }
      if (top > 1) { g.fillStyle = '#1d1124'; g.fillRect(1, 1, w - 4, top - 1); }
      for (let i = 0; i < 3 && i < n; i++) { g.fillStyle = `rgba(29,17,36,${[0.75, 0.5, 0.25][i]})`; g.fillRect(1, top + i * step, w - 4, step); }
      g.fillStyle = '#3a2214'; g.fillRect(w - 3, 1, 2, d - 2); g.fillStyle = '#a8744a'; g.fillRect(w - 3, 1, 1, d - 2);
    },
    // ---------- badkamer ----------
    toilet(g, w, d) {
      // wc: stortbak tegen de muur, pot met bril ervoor
      const th = Math.max(3, Math.round(d * 0.3));
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(1, 1, w, th);
      disc(g, w / 2 + 1, th + (d - th) / 2 + 0.5, w / 2 - 0.3, (d - th) / 2 + 0.5, 'rgba(30,10,30,.3)', 'rgba(30,10,30,.3)');
      disc(g, w / 2, th + (d - th) / 2 - 0.5, w / 2 - 0.3, (d - th) / 2 + 0.5, '#f4f2ec', '#8a8478');
      disc(g, w / 2, th + (d - th) / 2 - 0.5, w / 2 - 1.6, (d - th) / 2 - 1.2, '#a8d2ee', '#d8d2c4');
      box(g, 0, 0, w, th, '#f4f2ec', '#8a8478');
      g.fillStyle = '#ffffff'; g.fillRect(1, 1, w - 2, 1);
      g.fillStyle = '#aab4be'; g.fillRect(Math.floor(w / 2) - (w > 6 ? 1 : 0), 1, w > 6 ? 2 : 1, 1);
    },
    washbasin(g, w, d) {
      // wastafel op een kastje: witte bak, kraan achter, zeeppompje
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#9a6438', '#3a2214');
      box(g, 0, 0, w, d - 2, '#f4f2ec', '#8a8478');
      disc(g, w / 2, (d - 2) / 2 + 0.5, w / 2 - 1.5, (d - 2) / 2 - 1.2, '#d8e4ec', '#b8c4cc');
      g.fillStyle = '#7a848e'; g.fillRect(Math.floor(w / 2), 1, 1, 2); g.fillStyle = '#c8d0d8'; g.fillRect(Math.floor(w / 2), 1, 1, 1);
      g.fillStyle = '#e8a0b0'; g.fillRect(w - 3, 1, 1, 1);
      g.fillStyle = '#e0c070'; g.fillRect(Math.floor(w / 2), d - 2, 1, 1);
    },
    shower(g, w, d) {
      // douchebak met tegeltjes, putje in het midden, douchekop achter en glazen wand voor
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#dfe7f3', '#6f86b4');
      g.fillStyle = '#c4d2e6';
      for (let x = 4; x < w - 1; x += 4) g.fillRect(x, 1, 1, d - 2);
      for (let y = 4; y < d - 1; y += 4) g.fillRect(1, y, w - 2, 1);
      g.fillStyle = '#5a646e'; g.fillRect(Math.floor(w / 2) - 1, Math.floor(d / 2) - 1, 2, 2);
      g.fillStyle = '#7a848e'; g.fillRect(Math.floor(w / 2), 1, 1, 2);
      box(g, Math.floor(w / 2) - 1, 2, 3, 2, '#c8d0d8', '#5a646e');
      g.fillStyle = 'rgba(140,200,240,.55)'; g.fillRect(1, d - 2, w - 2, 1); g.fillRect(w - 2, 1, 1, d - 2);
      g.fillStyle = '#ffffff'; g.fillRect(w - 2, 2, 1, 2); g.fillRect(2, d - 2, 2, 1);
      g.fillStyle = 'rgba(168,210,238,.7)';
      for (const [fx, fy] of [[0.35, 0.45], [0.6, 0.4], [0.45, 0.65], [0.7, 0.62]]) g.fillRect(Math.round(w * fx), Math.round(d * fy), 1, 1);
    },
    bath(g, w, d) {
      // ligbad tegen de muur: brede witte rand, water, kraan aan de linkerkant
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#f4f2ec', '#8a8478');
      g.fillStyle = '#ffffff'; g.fillRect(1, 1, w - 2, 1);
      const ix = 4, iy = 2, iw = w - 6, ih = d - 4;
      g.fillStyle = '#b8c4cc'; g.fillRect(ix + 1, iy, iw - 2, ih); g.fillRect(ix, iy + 1, iw, ih - 2);
      g.fillStyle = '#8ec4e4'; g.fillRect(ix + 1, iy + 1, iw - 2, ih - 2);
      g.fillStyle = '#b8dcf0';
      for (let x = ix + 3; x < ix + iw - 3; x += 5) g.fillRect(x, iy + 2 + (x % 2), 2, 1);
      g.fillStyle = '#6aa8d0'; g.fillRect(ix + 1, iy + ih - 2, iw - 2, 1);
      g.fillStyle = '#7a848e'; g.fillRect(1, Math.floor(d / 2) - 1, 2, 2); g.fillStyle = '#c8d0d8'; g.fillRect(2, Math.floor(d / 2) - 1, 2, 1);
      g.fillStyle = '#5a646e'; g.fillRect(ix + 2, Math.floor(d / 2), 1, 1);
    },
    // Elektrische kattenbak van bovenaf: ronde koepel op een voet, ingang aan de voorkant met grit, lampje bovenop
    litterbox(g, w, d) {
      shadow(g, w, d);
      box(g, 0, 0, w, d, '#cfc8bc', '#4a4440');
      const cx = w / 2, cy = d / 2 - 0.5, r = Math.max(3, Math.min(w, d) / 2 - 1);
      disc(g, cx, cy, r, r, '#f2efe8', '#6a6460');
      g.fillStyle = '#dcd8d0'; g.fillRect(Math.round(cx - r / 2), Math.round(cy - r + 1), Math.max(1, Math.round(r / 2)), 1);
      const ow = Math.max(3, Math.round(r * 1.2)), ox = Math.round(cx - ow / 2), oy = Math.round(cy + r * 0.1);
      g.fillStyle = '#3a3440'; g.fillRect(ox, oy, ow, Math.max(2, d - oy - 1));
      g.fillStyle = '#c8b48a'; g.fillRect(ox + 1, d - 2, Math.max(1, ow - 2), 1);
      g.fillStyle = '#5ac06a'; g.fillRect(Math.round(cx), Math.round(cy - r * 0.55), 1, 1);
    },
    washer(g, w, d) { laundry(g, w, d, false); },
    dryer(g, w, d) { laundry(g, w, d, true); },
    towelrail(g, w, d) {
      // handdoekrek aan de muur: verchroomde stang met een handdoek die naar voren hangt
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(1, 1, w, d);
      g.fillStyle = '#5a646e'; g.fillRect(0, 0, 1, Math.min(2, d)); g.fillRect(w - 1, 0, 1, Math.min(2, d));
      g.fillStyle = '#c8d0d8'; g.fillRect(0, 1, w, 1);
      const tx = Math.max(1, Math.round(w * 0.2)), tw = Math.max(2, w - 2 * tx);
      g.fillStyle = '#b85a4a'; g.fillRect(tx, 1, tw, d - 1);
      g.fillStyle = '#e07a6a'; g.fillRect(tx, 1, tw, Math.max(1, d - 2));
      g.fillStyle = '#f2ead8'; g.fillRect(tx, d - 2, tw, 1);
    },
    // Plaatjes voor de keuzelijst: van voren, tegen een stukje behang
    door(g, w, d) { wallBg(g, w, d); doorFront(g, Math.round(w * 0.15), 2, Math.round(w * 0.7), d - 2); },
    window(g, w, d) { wallBg(g, w, d); windowFront(g, 2, 3, w - 4, d - 8, false); },
    windowsill(g, w, d) { wallBg(g, w, d); windowFront(g, 2, 3, w - 4, d - 8, true); },
  };

  function wallBg(g, w, d) { g.fillStyle = '#dca18a'; g.fillRect(0, 0, w, d); g.fillStyle = '#8a5238'; g.fillRect(0, d - 2, w, 2); }
  /** Deur van voren: kozijn, houten deur met twee panelen en een klink. */
  function doorFront(g, x, y, w, h, hingeRight) {
    g.fillStyle = '#3a2214'; g.fillRect(x - 1, y - 1, w + 2, h + 1);
    g.fillStyle = '#a8744a'; g.fillRect(x, y, w, h);
    g.fillStyle = '#c98a52'; g.fillRect(x, y, w, 1);
    const ph = Math.floor((h - 5) / 2);
    g.fillStyle = '#8a5a35'; g.fillRect(x + 2, y + 2, w - 4, ph); g.fillRect(x + 2, y + 3 + ph, w - 4, h - 5 - ph);
    g.fillStyle = '#e0c070'; g.fillRect(hingeRight ? x + 1 : x + w - 3, y + Math.floor(h / 2), 2, 1);   // klink aan de andere kant dan de scharnieren
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
        if (it.type === 'door') doorFront(g, X, Y + 2, len, 14, (it.door != null ? it.door : it.flip ? 1 : 0) & 1);
        else windowFront(g, X, Y + 6, len, 5, it.type === 'windowsill');
        continue;
      }
      // van bovenaf: a = langs de muur, r = de kamer in; B = muurlijn in pixels, de muurkap ligt aan de buitenkant
      const vert = s.wall === 'w' || s.wall === 'e', a = vert ? [0, 1] : [1, 0], r = s.wall === 'w' ? [1, 0] : s.wall === 'e' ? [-1, 0] : [0, -1];
      const B = Math.round(((vert ? s.x : s.y) + 1) * PPM), A = Math.round(((vert ? s.y : s.x) + 1) * PPM - len / 2);
      // deur gespiegeld (flip): de scharnieren aan de andere kant, dus de deur draait de andere kant op
      const ds = it.type === 'door' ? (it.door != null ? it.door : it.flip ? 1 : 0) : 0, hingeR = ds & 1, outward = ds & 2;
      const px = (along0, into) => { const along = hingeR ? len - 1 - along0 : along0; return vert ? [B + r[0] * into, A + along] : [A + along, B + r[1] * into]; };
      const cap = (into0, into1, col) => { g.fillStyle = col; for (let i = 0; i < len; i++) for (let k = into0; k < into1; k++) { const [x, y] = px(i, k); g.fillRect(x, y, 1, 1); } };
      if (it.type === 'door') {
        cap(-5, 0, '#6a5446');                                                     // deuropening in de muur
        if (outward) { cap(-1, 1, '#3a2214'); continue; }                           // draait naar buiten: alleen het deurblad als streepje in de muur
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

  // ---------- Meubels schuin van voren (Furniture34), naast de oude weergave van boven ----------
  let VIEW34 = false;
  function setView34(v) { VIEW34 = !!v; }
  /** Kijkrichting bij een draaiing: 90 = voorkant naar je toe (zoals de plaatjes van boven, die 90 graden als standaard hebben). */
  function viewOf(dir) { const q = ((Math.round(((dir == null ? 90 : dir) - 90) / 90) % 4) + 4) % 4; return ['S', 'W', 'N', 'E'][q]; }
  /** Teken één meubel schuin van voren op zijn plek: de onderkant van het plaatje is de voorrand van de voetafdruk. */
  function draw34(g, type, x, y, W, D, dir, opt = {}) {
    const v = viewOf(dir), side = v === 'W' || v === 'E', sd = side ? W : D, frontY = (y + 1 + sd / 2) * PPM;
    let r = window.Furniture34.renderItem(type, W, D, v, { hf: type === 'tvstand' ? 0.9 : 0.75, ...opt });
    if (!r) return false;
    // past het niet boven in de kaart (hoge kast tegen de achtermuur): dit meubel iets lager tekenen
    const top = frontY + r.margin - r.canvas.height;
    // (de tv mag net als een deur boven de muurrand uitsteken)
    if (top < 0 && r.height > 0 && type !== 'tvstand') r = window.Furniture34.renderItem(type, W, D, v, { ...opt, hf: Math.max(0.3, 0.75 * (r.height + top) / r.height) });
    g.drawImage(r.canvas, Math.round((x + 1) * PPM - r.fw / 2) - r.margin, Math.round(frontY) - r.canvas.height + r.margin);
    return true;
  }
  /** Apparaten die schuin van voren met de meubels mee op volgorde gaan (niet in frame over alles heen). */
  const dev34 = l => l && l.x >= 0 && (isAircoLook(l.look) || l.look === 'kattenbak');
  function draw34All(g, L, items, lamps) {
    const list = [];
    for (const it of items) { const def = ITEMS[it.type]; if (!def || def.wall) continue;
      const W = it.w || def.w, D = it.d || def.d, v = viewOf(it.dir), sd = (v === 'W' || v === 'E') ? W : D;
      list.push({ it, W, D, front: it.y + sd / 2, rank: it.type === 'rug' ? 0 : def.top ? 2 : 1 }); }
    if (L.tv) list.push({ tv: L.tv, W: L.tv.w + 0.4, D: 0.45, front: L.tv.y + (viewOf(L.tv.dir) === 'W' || viewOf(L.tv.dir) === 'E' ? L.tv.w + 0.4 : 0.45) / 2, rank: 1 });
    for (const l of lamps || []) if (dev34(l)) {
      const mob = isAircoLook(l.look) && !aircoWall(l), D = l.look === 'kattenbak' ? ITEMS.litterbox.d : mob ? 0.4 : 0.3;
      list.push({ dev: l, front: l.y + (aircoWall(l) ? 0.1 : D / 2), rank: 1 });
    }
    list.sort((a, b) => a.rank - b.rank || a.front - b.front);
    for (const e of list) {
      if (e.dev) { const l = e.dev;
        if (l.look === 'kattenbak') draw34(g, 'litterbox', l.x, l.y, ITEMS.litterbox.w, ITEMS.litterbox.d, 90);
        else if (aircoWall(l)) drawAirco(g, l, L);
        else draw34(g, /zwart/.test(l.look) ? 'aircoMobileZwart' : 'aircoMobile', l.x, l.y, 0.45, 0.4, 90);
        continue; }
      if (e.tv) { draw34(g, 'tvstand', e.tv.x, e.tv.y, e.W, e.D, e.tv.dir); continue; }
      const it = e.it, opt = it.type === 'rug' ? { tf: 1 } : ITEMS[it.type].top ? { noShadow: true } : {};
      if (it.flip) opt.flipX = true;
      if (!draw34(g, it.type, it.x, it.y, e.W, e.D, it.dir, opt)) drawItem(g, it);   // onbekend: dan de oude tekening
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
  /** Meubel waar een stekker aan hangt (item.plug = id van de stekker), of undefined. */
  function plugItem(L, l) { return L && ((L.tv && L.tv.plug === l.id && L.tv) || (L.items || []).find(it => it.plug === l.id)); }
  /** Breedte en diepte van een meubel in meters, rekening houdend met draaien. */
  function itemDims(it) {
    // De tv (L.tv, geen type): tv-meubel van tv.w + 0,4 bij 0,45 m
    const def = it.type ? (ITEMS[it.type] || { w: 1, d: 1 }) : { w: (it.w || 1) + 0.4, d: 0.45 };
    const w = it.type ? (it.w || def.w) : def.w, d = it.type ? (it.d || def.d) : def.d;
    return ((it.dir ?? 90) / 90) % 2 === 0 ? [d, w] : [w, d];
  }

  function lampBox(l, L) {
    const look = l.look || '';
    if (isAircoLook(look)) { const b = aircoBox(l, L), m = 2; return [(b[0] - m) / PPM, (b[1] - m) / PPM, (b[2] + m) / PPM, (b[3] + m) / PPM]; }
    if (look === 'kattenbak') return [-0.38, -0.4, 0.38, 0.4];
    if (look === 'stekker') { const it = plugItem(L, l); if (it) { const [w, d] = itemDims(it); return [-w / 2, -d / 2, w / 2, d / 2]; } return [-0.22, -0.22, 0.22, 0.22]; }
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

  /**
   * Airco's: geen lamp, maar wel een apparaat op de kaart. Uiterlijk = look:
   * airco-wand-* hangt tegen de muur (op de achtermuur van voren, op de andere muren van bovenaf),
   * airco-mobiel-* staat op de vloer (van bovenaf, met afvoerslang aan de achterkant; richting in L.aims, 90 = voorkant naar beneden).
   */
  const AIRCO_LOOKS = [
    { id: 'airco-wand-wit', label: 'Hangende airco, wit' }, { id: 'airco-wand-zwart', label: 'Hangende airco, zwart' },
    { id: 'airco-mobiel-wit', label: 'Mobiele airco, wit' }, { id: 'airco-mobiel-zwart', label: 'Mobiele airco, zwart' },
  ];
  const isAircoLook = look => /^airco-/.test(look || '');
  const aircoWall = l => /^airco-wand/.test(l.look || '');
  function aircoPal(look) {
    return /zwart$/.test(look)
      ? { O: '#0e0c12', B: '#3a3842', LT: '#5a5866', SD: '#26242c', SLOT: '#08070a', FLAP: '#4a4854', }
      : { O: '#4a4440', B: '#eeebe4', LT: '#ffffff', SD: '#c9c3b8', SLOT: '#5a544e', FLAP: '#dcd7cc' };
  }
  const AIR_LED = '#5fd8ff';
  /** Aan welke muur een hangende airco zit (n = achtermuur, s, w, e); zonder kamer (plaatje in een knop) de achtermuur. */
  const aircoSide = (L, l) => (L && L.floor ? wallSnap(L, l.x, l.y).wall : 'n');
  /** Richting van een mobiele airco in graden (voorkant), in stappen van 90. */
  const aircoDir = (L, l) => { const a = L && L.aims ? L.aims[l.id] : null; return a == null || a < 0 ? 90 : Math.round(a / 90) * 90 % 360; };
  /** Rechthoeken [x, y, w, h, kleur] om (cx, cy) tekenen, q keer een kwartslag met de klok mee gedraaid. */
  function rects(g, cx, cy, q, list) {
    for (let [x, y, w, h, c] of list) {
      for (let i = 0; i < q; i++) [x, y, w, h] = [-(y + h), x, h, w];
      g.fillStyle = c; g.fillRect(cx + x, cy + y, w, h);
    }
  }
  function drawAirco(g, l, L) {
    const x = Math.round((l.x + 1) * PPM), y = Math.round((l.y + 1) * PPM), p = aircoPal(l.look);
    if (aircoWall(l)) {
      const side = aircoSide(L, l);
      if (side === 'n') {
        // Van voren, hoog op de achtermuur: lange kast met ronde hoeken, displaylampje rechts,
        // onderaan de uitblaasopening met lamellen en de klep eronder
        g.fillStyle = 'rgba(40,14,30,.35)'; g.fillRect(x - 7, y - 3, 16, 1); g.fillRect(x + 9, y - 11, 1, 8);
        g.fillStyle = p.O; g.fillRect(x - 7, y - 12, 15, 9); g.fillRect(x - 8, y - 11, 17, 7);
        g.fillStyle = p.B; g.fillRect(x - 7, y - 11, 15, 7);
        g.fillStyle = p.LT; g.fillRect(x - 7, y - 11, 15, 1);
        g.fillStyle = p.SD; g.fillRect(x - 7, y - 7, 15, 1); g.fillRect(x + 7, y - 10, 1, 3);
        g.fillStyle = AIR_LED; g.fillRect(x + 4, y - 9, 2, 1);
        g.fillStyle = p.SLOT; g.fillRect(x - 6, y - 6, 13, 1);
        g.fillStyle = p.FLAP; for (let i = -5; i <= 5; i += 2) g.fillRect(x + i, y - 6, 1, 1);
        g.fillStyle = p.FLAP; g.fillRect(x - 6, y - 5, 13, 1);
        return;
      }
      // Van bovenaf tegen een zijmuur of de voormuur: smalle kast langs de muur, uitblaasopening aan de kamerkant
      const q = side === 'w' ? 3 : side === 'e' ? 1 : 2;   // eigen tekening: muur boven, kamer onder
      rects(g, x, y, q, [
        [-8, 0, 17, 4, p.O], [-7, 0, 15, 2, p.B], [-7, 0, 15, 1, p.LT], [-6, 2, 13, 1, p.SLOT],
        [-5, 2, 1, 1, p.FLAP], [-2, 2, 1, 1, p.FLAP], [1, 2, 1, 1, p.FLAP], [4, 2, 1, 1, p.FLAP], [5, 1, 2, 1, AIR_LED],
      ]);
      return;
    }
    // Mobiel, van bovenaf: kast met ronde hoeken en wieltjes, rooster bovenop, bedieningsstrook voor, afvoerslang achter
    const q = (((aircoDir(L, l) - 90) / 90) % 4 + 4) % 4, dark = /zwart$/.test(l.look);
    const H1 = dark ? '#5e5c68' : '#808089', H2 = dark ? '#8e8c98' : '#b8b8c0', W = dark ? '#6a6874' : '#16141a';
    g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(x - 4, y - 3, 11, 9);
    rects(g, x, y, q, [
      // slang: ribbels in twee grijstinten, een bocht en het raamstuk
      [-1, -6, 2, 1, H1], [-1, -7, 2, 1, H2], [-1, -8, 2, 1, H1], [-1, -9, 2, 1, H2], [-1, -10, 2, 1, H1],
      [1, -10, 1, 2, H2], [2, -10, 1, 2, H1], [3, -10, 1, 2, H2], [4, -10, 1, 2, H1], [5, -11, 1, 4, dark ? '#2a2830' : '#5a5660'],
      // wieltjes onder de voorkant
      [-4, 5, 2, 1, W], [3, 5, 2, 1, W],
      // kast
      [-5, -4, 11, 8, p.O], [-4, -5, 9, 10, p.O], [-4, -4, 9, 8, p.B], [-4, -4, 9, 1, p.LT],
      // rooster
      [-3, -3, 7, 1, p.SLOT], [-3, -1, 7, 1, p.SLOT], [-3, -2, 7, 1, p.FLAP], [-3, 0, 7, 1, p.FLAP],
      // bedieningsstrook met displaylampje
      [-3, 2, 7, 1, p.SD], [2, 2, 1, 1, AIR_LED],
    ]);
  }
  /** Tikvlak van een airco in pixels t.o.v. zijn plek: [links, boven, rechts, onder]. */
  function aircoBox(l, L) {
    if (!aircoWall(l)) return [-8, -8, 8, 8];
    const side = aircoSide(L, l);
    return side === 'n' ? [-9, -13, 9, -2] : side === 's' ? [-8, -6, 8, 1] : side === 'w' ? [-1, -8, 6, 8] : [-6, -8, 1, 8];
  }

  function lampFixture(g, l, color, L) {
    if (isAircoLook(l.look)) { drawAirco(g, l, L); return; }
    // Kattenbak (apparaat): dezelfde tekening als het meubel, gecentreerd op zijn plek
    if (l.look === 'kattenbak' && VIEW34 && window.Furniture34 && L) { draw34(g, 'litterbox', l.x, l.y, ITEMS.litterbox.w, ITEMS.litterbox.d, 90); return; }
    if (l.look === 'kattenbak') { const w = Math.round(ITEMS.litterbox.w * PPM), d = Math.round(ITEMS.litterbox.d * PPM);
      g.save(); g.translate(Math.round((l.x + 1) * PPM - w / 2), Math.round((l.y + 1) * PPM - d / 2)); SPRITES.litterbox(g, w, d); g.restore(); return; }
    // Slimme stekker (apparaat): wit blokje met twee pennetjes en een lampje (groen = aan)
    if (l.look === 'stekker' && plugItem(L, l)) {
      // Aan een meubel of de tv: heel klein stekkertje dat boven de rechterbovenhoek hangt
      // schuin van voren: rechtsvoor op de vloer naast het meubel (zoals een stopcontact); van boven: rechtsboven
      const it = plugItem(L, l), [w, d] = itemDims(it), x = Math.round((it.x + 1 + w / 2) * PPM) - (VIEW34 ? 1 : 4), y = VIEW34 ? Math.round((it.y + 1 + d / 2) * PPM) - 5 : Math.round((it.y + 1 - d / 2) * PPM) - 5;
      g.fillStyle = '#2a1a2e'; g.fillRect(x, y, 4, 4); g.fillStyle = '#f2efe8'; g.fillRect(x + 1, y + 1, 2, 2);
      g.fillStyle = '#2a1a2e'; g.fillRect(x + 1, y + 4, 1, 1); g.fillRect(x + 2, y + 4, 1, 1); return; }
    if (l.look === 'stekker') { const x = Math.round((l.x + 1) * PPM), y = Math.round((l.y + 1) * PPM);
      g.fillStyle = 'rgba(30,10,30,.3)'; g.fillRect(x - 3, y - 2, 8, 8);
      g.fillStyle = '#2a1a2e'; g.fillRect(x - 4, y - 4, 8, 8);
      g.fillStyle = '#f2efe8'; g.fillRect(x - 3, y - 3, 6, 6);
      g.fillStyle = '#9a948c'; g.fillRect(x - 2, y - 1, 1, 2); g.fillRect(x + 1, y - 1, 1, 2);
      g.fillStyle = '#5ac06a'; g.fillRect(x - 1, y - 3, 2, 1); return; }
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
      if (VIEW34 && dev34(l)) continue;   // al op volgorde met de meubels getekend (base)
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

  return { setView34, isView34: () => VIEW34, viewOf, plugItem, itemDims, PPM, ITEMS, CATS, catForRoom, SPRITES, FLOORS, LOOKS, lampFixture, floorPixel, base, frame, present, migrate, isFloor, rotated, calibColors, bandY, bandPos, presetFor, lampBox, wallSnap, AIRCO_LOOKS, isAircoLook, FIXTURES, fixtureSpots, drawFixtures, stripOf, aimOf, tiltOf };
})();
