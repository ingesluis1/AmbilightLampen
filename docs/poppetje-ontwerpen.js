/* Kledingontwerpen voor het poppetje (House hub). Nog NIET in de app: elk ontwerp is een functie(ctx) die je later
   1-op-1 in AV_X in index.html kunt zetten. Coördinaten: het kleine raster (hoofd x 3..10, lijf x 3..10 rij 12..19,
   armen x 1-2 en 11-12, benen rij 20..25, schoenen rij 26). Lijf en schoenen worden vóór de nek getekend, hoeden erna. */
const DESIGNS = { tops: [], bottoms: [], hats: [], shoes: [], back: [] };
const def = (kind, id, name, note, fn, opt = {}) => { DESIGNS[kind].push({ id, name, note, ...opt }); AV_X[kind][id] = fn; };

// ---------------- Bovenkleding ----------------
def('tops', 'hoodie', 'Hoodie', 'Capuchon rond de nek, buidelzak en koordjes.', c => {
  const { torso, arms, rect, p, T, Td, Tl, W2 } = c;
  torso(T, Td, 0); arms(T, Td);
  rect(4, 12, 9, 12, Td); p(3, 12, Td); p(10, 12, Td);            // capuchon die achter de nek ligt
  rect(5, 17, 8, 18, Td); p(5, 17, Tl);                            // buidelzak
  p(5, 13, W2); p(5, 14, W2); p(8, 13, W2); p(8, 14, W2);          // koordjes
  rect(1, 18, 2, 18, Td); rect(11, 18, 12, 18, Td);                // boordjes
});
def('tops', 'kersttrui', 'Kersttrui', 'Zigzagrand en een boompje met een gouden piek.', c => {
  const { torso, arms, rect, p, T, Td, W2 } = c, G = '#3e8a3c', Y = '#f2d04a';
  torso(T, Td, 0); arms(T, Td);
  for (let x = 3; x <= 10; x++) { p(x, 13, x % 2 ? W2 : T); }
  for (let x = 1; x <= 12; x++) if (x <= 2 || x >= 11) p(x, 17, W2);
  p(6, 14, Y); p(7, 14, Y); p(6, 15, G); p(7, 15, G); rect(5, 16, 8, 16, G); rect(5, 17, 8, 17, G); p(5, 17, '#e05a5a'); p(8, 16, '#4a8ae0');
  p(6, 18, '#7a4a2a'); p(7, 18, '#7a4a2a');
});
def('tops', 'streep', 'Streepjesshirt', 'Bretons: wit met strepen in je kleur, ook op de mouwen.', c => {
  const { torso, arms, rect, T, W2, W2d } = c;
  torso(W2, W2d, 1); arms(W2, W2d);
  for (const y of [13, 15]) rect(3, y, 10, y, T);
  for (const y of [17, 19]) rect(4, y, 9, y, T);
  for (const y of [14, 16, 18]) { rect(1, y, 2, y, T); rect(11, y, 12, y, T); }
});
def('tops', 'colbert', 'Pak met das', 'Colbert met revers, wit overhemd, rode das en pochet.', c => {
  const { torso, arms, rect, p, T, Td, Tl, W2 } = c, R = '#c0392b', Rd = '#8e2a1f';
  torso(T, Td, 0); arms(T, Td);
  rect(5, 12, 8, 15, W2); p(5, 12, Tl); p(5, 13, Tl); p(8, 12, Tl); p(8, 13, Tl); p(5, 14, T); p(8, 14, T); p(5, 15, T); p(8, 15, T);
  p(6, 13, Rd); p(7, 13, Rd); p(6, 14, R); p(7, 14, R); p(6, 15, R); p(7, 15, R); p(6, 16, Rd);
  p(9, 14, W2); p(7, 17, '#e8c84a'); p(7, 18, '#e8c84a');
  rect(1, 18, 2, 18, W2); rect(11, 18, 12, 18, W2);
});
def('tops', 'voetbal', 'Voetbalshirt', 'Korte mouwen, witte kraag en rugnummer 7 (van voren).', c => {
  const { torso, arms, rect, p, T, Td, S, Sd, W2 } = c;
  torso(T, Td, 0); arms(T, Td);
  rect(1, 16, 2, 18, S); rect(11, 16, 12, 18, S); rect(2, 16, 2, 18, Sd); rect(12, 16, 12, 18, Sd);
  rect(1, 15, 2, 15, W2); rect(11, 15, 12, 15, W2);
  p(5, 12, W2); p(6, 13, W2); p(7, 13, W2); p(8, 12, W2); p(6, 12, c.Sl); p(7, 12, c.Sl);
  rect(5, 14, 8, 14, W2); p(8, 15, W2); p(7, 16, W2); p(7, 17, W2);
});
def('tops', 'badjas', 'Badjas', 'Lange zachte jas met ceintuur; met sloffen erbij.', c => {
  const { torso, arms, rect, p, T, Td, Tl, S, Sd, B } = c;
  torso(T, Td, 0); arms(T, Td);
  for (let y = 20; y <= 23; y++) { rect(3, y, 10, y, T); p(10, y, Td); }
  p(6, 12, c.Sl); p(7, 12, c.Sl); p(5, 12, Tl); p(8, 12, Tl); p(6, 13, Tl); p(7, 14, Tl); p(7, 15, Tl); p(6, 16, Tl);
  rect(4, 18, 9, 18, Td); p(3, 19, Td); p(2, 20, Td);
  rect(1, 18, 2, 18, Tl); rect(11, 18, 12, 18, Tl);
  rect(4, 24, 5, 25, S); rect(8, 24, 9, 25, S); p(9, 24, Sd); p(9, 25, Sd);
  rect(3, 26, 5, 26, B); rect(8, 26, 10, 26, B);
  return 'full';
});
def('tops', 'regenjas', 'Regenjas', 'Lange jas met knopen, kraag en ceintuur.', c => {
  const { torso, arms, rect, p, T, Td, Tl, P, Pd, B, Bd } = c, K = '#2a2a30';
  torso(T, Td, 0); arms(T, Td);
  rect(3, 20, 10, 22, T); rect(10, 20, 10, 22, Td); rect(6, 13, 6, 22, Td);
  p(4, 12, Tl); p(5, 12, Tl); p(8, 12, Tl); p(9, 12, Tl);
  for (const y of [14, 17, 21]) { p(5, y, K); p(8, y, K); }
  rect(4, 19, 9, 19, Td);
  rect(3, 23, 5, 25, P); rect(8, 23, 10, 25, P); rect(10, 23, 10, 25, Pd);
  rect(2, 26, 5, 26, B); rect(8, 26, 11, 26, B); p(2, 26, Bd); p(11, 26, Bd);
  return 'full';
});
def('tops', 'bodywarmer', 'Bodywarmer', 'Gewatteerd vest over een shirt met lange mouwen.', c => {
  const { torso, arms, rect, p, T, Td, Tl, shade } = c, M = shade(T, .55), Md = shade(T, .35);
  torso(T, Td, 0); arms(M, Md);
  p(2, 13, M); p(11, 13, M);
  for (const y of [14, 16, 18]) rect(y < 17 ? 3 : 4, y, y < 17 ? 10 : 9, y, Td);
  rect(6, 12, 6, 19, Tl); rect(4, 12, 5, 12, Tl); rect(7, 12, 9, 12, Tl);
});
def('tops', 'croptop', 'Croptop', 'Kort topje met pofmouwtjes en blote buik.', c => {
  const { rect, p, T, Td, Tl, S, Sd, Sl } = c;
  rect(3, 12, 10, 16, T); rect(9, 13, 10, 16, Td); rect(4, 13, 4, 15, Tl); p(6, 12, Sl); p(7, 12, Sl);
  rect(4, 17, 9, 19, S); rect(8, 17, 9, 19, Sd); p(6, 18, Sd);
  p(2, 13, T); p(11, 13, T); rect(1, 14, 2, 15, T); rect(11, 14, 12, 15, T); rect(12, 14, 12, 15, Td);
  rect(1, 16, 2, 18, S); rect(11, 16, 12, 18, S); rect(2, 16, 2, 18, Sd); rect(12, 16, 12, 18, Sd);
  rect(1, 19, 2, 19, S); rect(11, 19, 12, 19, S);
});
def('tops', 'held', 'Superheldenpak', 'Pak met embleem, gouden riem en laarzen. Mooi met een cape.', c => {
  const { torso, arms, legs, rect, p, T, Td, shade } = c, Y = '#f2c84a', Yd = '#b08a20', R = shade(T, -.45);
  torso(T, Td, 0); arms(T, Td); legs(T, Td);
  p(6, 13, Y); p(7, 13, Y); rect(5, 14, 8, 14, Y); p(6, 15, Y); p(7, 15, Y); p(6, 14, Yd);
  rect(4, 19, 9, 19, Y); p(6, 19, Yd); p(7, 19, Yd);
  rect(1, 18, 2, 19, R); rect(11, 18, 12, 19, R);
  rect(3, 23, 5, 25, R); rect(8, 23, 10, 25, R); rect(2, 26, 5, 26, R); rect(8, 26, 11, 26, R);
  return 'full';
}, { back: 'cape' });
def('tops', 'pyjama', 'Pyjama', 'Jasje en broek met stippen, witte kraag.', c => {
  const { torso, arms, legs, rect, p, T, Td, W2 } = c;
  torso(T, Td, 0); arms(T, Td); legs(T, Td);
  p(5, 12, W2); p(8, 12, W2); p(6, 13, W2); p(7, 13, W2); p(6, 12, c.Sl); p(7, 12, c.Sl);
  for (const [x, y] of [[4, 15], [8, 14], [6, 17], [9, 18], [1, 15], [12, 17], [4, 22], [9, 21], [4, 25], [9, 24]]) p(x, y, W2);
  rect(1, 18, 2, 18, W2); rect(11, 18, 12, 18, W2);
  return 'full';
});
def('tops', 'labjas', 'Labjas', 'Witte jas tot over de knie, pen in het borstzakje.', c => {
  const { torso, arms, rect, p, T, P, Pd, B, Bd, W2, W2d } = c;
  torso(W2, W2d, 0); arms(W2, W2d);
  rect(6, 12, 7, 14, T); p(5, 12, W2d); p(8, 12, W2d);
  rect(3, 20, 10, 23, W2); rect(10, 20, 10, 23, W2d); rect(6, 15, 6, 23, W2d);
  p(9, 15, W2d); p(9, 14, '#3a7ad0'); rect(3, 20, 4, 21, W2d);
  rect(3, 24, 5, 25, P); rect(8, 24, 10, 25, P); rect(10, 24, 10, 25, Pd);
  rect(2, 26, 5, 26, B); rect(8, 26, 11, 26, B); p(2, 26, Bd); p(11, 26, Bd);
  return 'full';
});
def('tops', 'hartjestrui', 'Trui met hart', 'Dikke trui met een groot hart en boordjes.', c => {
  const { torso, arms, rect, p, T, Td, W2 } = c, H = T === '#d86aa0' || T === '#c0392b' ? W2 : '#e05a8a';
  torso(T, Td, 1); arms(T, Td);
  p(4, 14, H); p(5, 14, H); p(8, 14, H); p(9, 14, H); rect(4, 15, 9, 15, H); rect(5, 16, 8, 16, H); p(6, 17, H); p(7, 17, H);
  rect(4, 19, 9, 19, Td); rect(1, 18, 2, 18, Td); rect(11, 18, 12, 18, Td);
});
def('tops', 'bloemblouse', 'Bloemetjesblouse', 'Blouse met kleine bloemetjes, ook op de mouwen.', c => {
  const { torso, arms, p, T, Td } = c, F = '#f6a0c8', Y = '#f6e08a';
  torso(T, Td, 1); arms(T, Td);
  for (const [x, y] of [[4, 14], [8, 13], [9, 17], [5, 18], [1, 16], [12, 15]]) { p(x, y, Y); if (x > 1) p(x - 1, y, F); if (x < 12) p(x + 1, y, F); p(x, y - 1, F); p(x, y + 1, F); }
});

// ---------------- Onderkant ----------------
def('bottoms', 'jeans', 'Jeans met scheur', 'Lichte naden, omgeslagen pijpen en scheurtjes bij de knie.', c => {
  const { legs, rect, p, P, Pd, S, W2, shade } = c, Pl = shade(P, .3);
  legs(P, Pd);
  rect(4, 21, 4, 24, Pl); rect(9, 21, 9, 24, Pl); rect(3, 25, 5, 25, Pl); rect(8, 25, 10, 25, Pl);
  p(4, 23, S); p(5, 23, W2); p(9, 22, S); p(8, 22, W2);
});
def('bottoms', 'jogging', 'Joggingbroek', 'Witte strepen opzij, koordje en boordjes.', c => {
  const { legs, rect, p, P, Pd, W2 } = c;
  legs(P, Pd);
  rect(3, 21, 3, 24, W2); rect(10, 21, 10, 24, W2); p(6, 20, W2); p(7, 20, W2);
  rect(3, 25, 5, 25, Pd); rect(8, 25, 10, 25, Pd);
});
def('bottoms', 'plooirok', 'Plooirok', 'Lange rok met plooien tot onder de knie.', c => {
  const { rect, p, P, Pd, S, Sd, B } = c;
  for (let y = 20; y <= 23; y++) { const w = (y - 20) >> 1; for (let x = 3 - w; x <= 10 + w; x++) p(x, y, (x + (y > 20 ? 0 : 1)) % 2 ? P : Pd); }
  rect(3, 20, 10, 20, c.shade(P, -.45));
  rect(4, 24, 5, 25, S); rect(8, 24, 9, 25, S); p(9, 24, Sd); rect(4, 26, 5, 26, B); rect(8, 26, 9, 26, B);
});
def('bottoms', 'tutu', 'Tutu', 'Wijde tule rok met glitters en een maillot.', c => {
  const { rect, p, P, Pd, W2, B, shade } = c, Pl = shade(P, .35), M = '#f2f2f2';
  rect(3, 20, 10, 20, Pd); rect(1, 21, 12, 21, P); rect(0, 22, 13, 22, Pl);
  for (const x of [1, 4, 7, 10, 12]) p(x, 21, W2); for (const x of [2, 5, 9]) p(x, 22, W2);
  rect(4, 23, 5, 25, M); rect(8, 23, 9, 25, M); p(9, 24, '#d8d8e0');
  rect(4, 26, 5, 26, B); rect(8, 26, 9, 26, B);
});
def('bottoms', 'cargo', 'Cargobroek', 'Wijde broek met zakken op de bovenbenen.', c => {
  const { legs, rect, p, P, Pd, shade } = c, Pl = shade(P, .25);
  legs(P, Pd);
  rect(3, 22, 4, 23, Pd); p(3, 22, Pl); rect(9, 22, 10, 23, Pd); p(9, 22, Pl);
  rect(2, 25, 5, 25, P); rect(8, 25, 11, 25, P);
});

// ---------------- Op je hoofd (na de nek getekend; C = gekozen hoedkleur of null) ----------------
def('hats', 'kroon', 'Kroon', 'Gouden kroon met drie punten en edelstenen.', c => {
  const { rect, p } = c, G = '#f2c84a', Gd = '#b08a20', Gl = '#fff0a0';
  rect(3, 0, 10, 1, G); rect(3, 1, 10, 1, Gd);
  for (const x of [3, 10]) { p(x, -1, G); p(x, -2, Gl); } p(6, -1, G); p(7, -1, G); p(6, -2, G); p(7, -2, G); p(6, -3, Gl); p(7, -3, Gl);
  p(5, 0, '#c0392b'); p(8, 0, '#3a7ad0'); p(4, 0, Gl);
});
def('hats', 'cowboy', 'Cowboyhoed', 'Brede rand die aan de zijkanten omhoog krult.', c => {
  const { rect, p, shade } = c, C = c.C || '#8a5a35', Cd = shade(C, -.3), Cl = shade(C, .25);
  rect(0, 3, 13, 3, C); p(-1, 2, C); p(14, 2, C); rect(1, 4, 12, 4, Cd);
  rect(3, -1, 10, 2, C); rect(4, -2, 9, -2, C); p(6, -2, Cd); p(7, -2, Cd); rect(3, 2, 10, 2, Cd); p(4, -1, Cl); p(4, 0, Cl);
});
def('hats', 'bloemenkrans', 'Bloemenkrans', 'Krans van roze, gele en witte bloemetjes.', c => {
  const { p } = c, L = '#4e8a3c', cols = ['#f6a0c8', '#f6e08a', '#ffffff', '#c890f0'];
  for (let x = 2; x <= 11; x++) p(x, 1, L);
  [2, 4, 7, 9, 11].forEach((x, i) => { const F = cols[i % cols.length]; p(x, 0, F); p(x, 1, '#f2c84a'); if (x < 11) p(x + 1, 1, F); });
});
def('hats', 'koptelefoon', 'Koptelefoon', 'Beugel over je hoofd en dikke oorkussens.', c => {
  const { rect, p, shade } = c, C = c.C || '#3a3a44', Cl = shade(C, .3), K = '#2a2a30';
  rect(3, -1, 10, -1, K); p(2, 0, K); p(11, 0, K); rect(1, 1, 1, 4, K); rect(12, 1, 12, 4, K);
  rect(0, 5, 2, 8, C); rect(11, 5, 13, 8, C); p(1, 6, Cl); p(12, 6, Cl);
});
def('hats', 'duivel', 'Duivelshoorntjes', 'Twee rode hoorntjes, voor op een feestje.', c => {
  const { p } = c, R = '#d0303a', Rd = '#8e1a22';
  p(3, 0, R); p(3, -1, R); p(2, -2, R); p(4, 0, Rd);
  p(10, 0, R); p(10, -1, R); p(11, -2, R); p(9, 0, Rd);
});
def('hats', 'vissershoed', 'Vissershoedje', 'Zacht hoedje met schuine rand.', c => {
  const { rect, p, shade } = c, C = c.C || '#e8dcc0', Cd = shade(C, -.25);
  rect(3, -1, 10, 1, C); rect(4, -2, 9, -2, C); rect(1, 2, 12, 3, C); rect(1, 3, 12, 3, Cd); p(4, 0, shade(C, .2));
});
def('hats', 'feestmuts', 'Feestmuts', 'Puntmuts met strepen en een pompon.', c => {
  const { rect, p } = c, C = c.C || '#3a7ad0', Y = '#f2c84a';
  const rows = [[1, 4, 9], [0, 5, 8], [-1, 5, 8], [-2, 6, 7], [-3, 6, 7], [-4, 6, 7]];
  for (const [y, x0, x1] of rows) for (let x = x0; x <= x1; x++) p(x, y, (x - y) % 3 === 0 ? Y : C);
  p(6, -5, '#ffffff'); p(7, -5, '#ffffff'); p(6, -6, '#f6a0c8');
});
def('hats', 'berenmuts', 'Berenmuts', 'Gebreide muts met ronde oortjes.', c => {
  const { rect, p, shade } = c, C = c.C || '#8a5a35', Cd = shade(C, -.3), K = '#f6c0b0';
  rect(4, -1, 9, -1, C); rect(3, 0, 10, 0, C); rect(2, 1, 11, 3, C); rect(2, 4, 11, 4, Cd);
  for (const x of [3, 9]) { rect(x, -3, x + 1, -2, C); }
  p(3, -2, K); p(10, -2, K);
});
def('hats', 'aureool', 'Aureool', 'Gouden ring die boven je hoofd zweeft.', c => {
  const { rect, p } = c, G = '#f6e08a', Gd = '#d8b040';
  rect(4, -5, 9, -5, G); p(3, -4, G); p(10, -4, G); rect(4, -3, 9, -3, Gd);
});
def('hats', 'bandana', 'Bandana', 'Doek om je hoofd met stippen en een knoop opzij.', c => {
  const { rect, p, shade } = c, C = c.C || '#c0392b', Cd = shade(C, -.3);
  rect(3, 1, 10, 2, C); rect(4, 0, 9, 0, C); p(11, 2, Cd); p(12, 1, C); p(12, 3, C); p(13, 3, Cd);
  for (const [x, y] of [[4, 1], [7, 2], [9, 0], [6, 0]]) p(x, y, '#ffffff');
});
def('hats', 'koksmuts', 'Koksmuts', 'Hoge witte muts met een geplooide bol.', c => {
  const { rect, p } = c, W = '#ffffff', Wd = '#d8d8e0';
  rect(3, 0, 10, 1, '#f2f2f2'); rect(3, 1, 10, 1, Wd);
  rect(2, -4, 11, -1, W); rect(3, -5, 10, -5, W); for (const x of [4, 7, 10]) p(x, -2, Wd); p(3, -4, '#f8f8ff');
});

// ---------------- Schoenen (overschrijven rij 24..26) ----------------
const shoe = (id, name, note, fn) => def('shoes', id, name, note, fn);
shoe('sneakers', 'Sneakers', 'Wit met een streep in je schoenkleur.', c => {
  const { rect, p, B, W2, W2d } = c;
  rect(2, 26, 5, 26, W2); rect(8, 26, 11, 26, W2); p(4, 26, B); p(9, 26, B); p(2, 26, W2d); p(11, 26, W2d);
});
shoe('laarzen', 'Laarzen', 'Hoge laarzen tot halverwege je been.', c => {
  const { rect, B, Bd, shade } = c, Bl = shade(B, .25);
  rect(3, 23, 5, 25, B); rect(8, 23, 10, 25, B); rect(3, 23, 5, 23, Bl); rect(8, 23, 10, 23, Bl); rect(10, 24, 10, 25, Bd);
  rect(2, 26, 5, 26, Bd); rect(8, 26, 11, 26, Bd);
});
shoe('sloffen', 'Pluizige sloffen', 'Dikke roze sloffen met een pompon.', c => {
  const { rect, p } = c, F = '#f6b0cc', Fd = '#d880a8';
  rect(1, 26, 5, 26, F); rect(8, 26, 12, 26, F); p(1, 26, Fd); p(12, 26, Fd); p(3, 25, '#ffffff'); p(10, 25, '#ffffff');
});
shoe('hakken', 'Hakken', 'Smalle schoentjes met een hakje.', c => {
  const { rect, p, B, Bd, S } = c;
  rect(2, 26, 5, 26, null); rect(8, 26, 11, 26, null);
  rect(3, 26, 5, 26, B); rect(8, 26, 10, 26, B); p(5, 27, Bd); p(8, 27, Bd); p(4, 26, S);
});
shoe('klompen', 'Klompen', 'Houten klompen met een rood tulpje.', c => {
  const { rect, p } = c, K = '#f0cc70', Kd = '#c89a3a';
  rect(2, 25, 5, 26, K); rect(8, 25, 11, 26, K); rect(2, 26, 5, 26, Kd); rect(8, 26, 11, 26, Kd); p(1, 26, Kd); p(12, 26, Kd);
  p(3, 25, '#d0303a'); p(10, 25, '#d0303a');
});

// ---------------- Op je rug (achter het lijf getekend) ----------------
def('back', 'cape', 'Cape', 'Lange cape die naast je benen uitwaait.', c => {
  const { rect, p, a, shade } = c, C = a.capeColor || '#c0392b', Cd = shade(C, -.3);
  rect(0, 12, 13, 24, C); rect(-1, 20, 14, 25, C); for (const x of [-1, 2, 5, 8, 11, 14]) p(x, 25, Cd); rect(12, 12, 13, 25, Cd);
});
def('back', 'vleugels', 'Engelenvleugels', 'Witte vleugels met veertjes.', c => {
  const { p } = c, W = '#ffffff', D = '#d8dcec';
  const wing = [[0, 12], [-1, 12], [-1, 13], [-2, 13], [-2, 14], [-3, 14], [-3, 15], [-2, 15], [-3, 16], [-2, 16], [-1, 16], [-2, 17], [-1, 17], [0, 17], [-1, 18], [0, 18], [0, 13], [0, 14], [-1, 14], [-1, 15], [0, 15], [0, 16]];
  for (const [x, y] of wing) { p(x, y, (x + y) % 3 === 0 ? D : W); p(13 - x, y, (x + y) % 3 === 0 ? D : W); }
});
def('back', 'vlinder', 'Vlindervleugels', 'Bonte vleugels met stippen.', c => {
  const { p } = c, A = '#e07ad0', Bv = '#f2c84a', K = '#3a2a4a';
  const wing = [[0, 11, A], [-1, 11, A], [-2, 12, A], [-1, 12, Bv], [0, 12, A], [-2, 13, A], [-1, 13, A], [0, 13, A], [-1, 14, K], [0, 15, A], [-1, 15, Bv], [-1, 16, A], [0, 16, A]];
  for (const [x, y, col] of wing) { p(x, y, col); p(13 - x, y, col); }
});
def('back', 'vossenstaart', 'Vossenstaart', 'Dikke oranje staart met een witte punt.', c => {
  const { p } = c, O = '#e07a2a', Od = '#a8541a';
  for (const [x, y] of [[11, 21], [12, 21], [12, 22], [13, 22], [13, 21], [14, 21], [13, 20], [14, 20], [14, 19]]) p(x, y, O);
  p(12, 22, Od); p(14, 18, '#ffffff'); p(15, 19, '#ffffff'); p(15, 18, '#ffffff');
});
