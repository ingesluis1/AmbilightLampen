/*
 * Taal: Nederlands (standaard) of Engels. De Nederlandse tekst is de sleutel.
 * t('tekst', {a: 1}) vertaalt en vult {a} in; I18N.apply() vertaalt vaste tekst op de pagina.
 */
const I18N = (() => {
  let lang = 'nl';
  const EN = {
    // algemeen
    'Ambilight Lampen': 'Ambilight Lamps', 'Instellingen': 'Settings', 'Sync starten': 'Start sync', 'Sync stoppen': 'Stop sync',
    'aan': 'on', 'uit': 'off', 'Uit': 'Off', 'Tv': 'TV', 'Lampen': 'Lamps', 'Taal': 'Language',
    'Sync start...': 'Starting sync...', 'Sync stopt...': 'Stopping sync...',
    'gekoppeld': 'paired', 'niet gekoppeld': 'not paired', 'nog niet gekoppeld': 'not paired yet',
    'bereikbaar': 'reachable', 'niet bereikbaar': 'unreachable',
    'Verbonden': 'Connected', 'Nog niet gemeten': 'Not measured yet', 'Tv niet bereikbaar': 'TV unreachable',
    'Tv nog niet gekoppeld': 'TV not paired yet', 'Gestopt': 'Stopped',
    'Lampen: {a} van {b} bereikbaar': 'Lamps: {a} of {b} reachable',
    'Update naar {v}': 'Update to {v}', 'Update {v}': 'Update {v}', 'Versie {v}': 'Version {v}',
    'Wat is er nieuw in {v}': "What's new in {v}", 'Geen beschrijving.': 'No description.', 'Nu updaten': 'Update now',
    'Nu updaten?': 'Update now?', 'Later': 'Later', '. Kijk op de tv.': '. Check the TV.',
    // tv-scherm
    'Kleurtest (alle lampen na elkaar)': 'Colour test (all lamps one by one)', 'Testbeeld (regenboog)': 'Test pattern (rainbow)',
    'Testbeeld': 'Test pattern', 'Scan met je telefoon om je kamer in te richten en je lampen op te hangen': 'Scan with your phone to set up your room and place your lamps',
    'Nog geen lampen, voeg ze toe via de telefoon': 'No lamps yet, add them with your phone',
    'OK op een lamp laat hem knipperen': 'OK on a lamp makes it blink', ' (nog ophangen)': ' (not placed yet)',
    'Omhoog en omlaag: kiezen. Links en rechts: aanpassen. OK: aan of uit. Terug: sluiten.': 'Up and down: choose. Left and right: adjust. OK: on or off. Back: close.',
    '"{n}" gekozen: pijltjes schuiven hem 25 cm, OK kiest de volgende lamp.': '"{n}" selected: the arrows move it 25 cm, OK selects the next lamp.',
    'Kijk of de kleur in dezelfde richting langs je lampen draait. OK: lamp kiezen om te verschuiven. Links/rechts: snelheid. Terug: stoppen.':
      'Check that the colour moves the same way along your lamps. OK: pick a lamp to move. Left/right: speed. Back: stop.',
    'Eén rondje per {n} seconden': 'One turn every {n} seconds',
    // instellingen
    'Reactiesnelheid': 'Response speed', 'Kleurintensiteit': 'Colour intensity', 'Helderheid': 'Brightness',
    'Licht bij zwart beeld': 'Light on a black screen', 'Hoe breed lampen meekijken': 'How wide lamps look', 'Na stoppen': 'After stopping',
    'Terug naar hoe ze stonden': 'Back to how they were', 'Warm wit': 'Warm white',
    'Automatisch starten als de tv aangaat': 'Start automatically when the TV turns on',
    // editor
    'Tv koppelen': 'Pair TV', 'Koppel tv': 'Pair TV', 'Bevestig': 'Confirm', 'Pincode van de tv': 'PIN code from the TV',
    'De app heeft eenmalig toestemming nodig om de kleuren van het beeld te lezen. Druk op de knop, dan verschijnt er een pincode op de tv.':
      'The app needs permission once to read the colours of the picture. Press the button and a PIN code appears on the TV.',
    'Op de tv zelf laat je 127.0.0.1 staan.': 'On the TV itself, leave this at 127.0.0.1.',
    'Je kamer': 'Your room', 'Vloer': 'Floor', 'Gum': 'Eraser', 'Meubels': 'Furniture',
    'Kies een vloer en veeg over de vakjes.': 'Pick a floor and swipe over the squares.', 'Hele kamer': 'Whole room', 'Kamermaat': 'Room size',
    'Breedte': 'Width', 'Diepte': 'Depth', 'Draai': 'Rotate', 'Spiegel': 'Mirror', 'Weg': 'Remove',
    'Tik waar de tv staat. "Draai" wisselt de kant waar het scherm naartoe kijkt.': 'Tap where the TV is. "Rotate" changes the direction the screen faces.',
    'Kies een meubel en tik op de kaart. Tik op een meubel om het te kiezen, sleep om te verschuiven.': 'Pick a piece of furniture and tap the map. Tap furniture to select it, drag to move it.',
    'Naam': 'Name', 'Knipper': 'Blink', 'Test': 'Test', 'Lamp of spot': 'Lamp or spot', 'Staaflamp': 'Rod lamp',
    'Lagen apart': 'Separate layers', '(voor lampen met meerdere kleurlagen, zoals een Wall Washer)': '(for lamps with several colour layers, like a wall washer)',
    'Elke laag volgt zijn hoogte aan de linkerkant van het beeld': 'Each layer follows its height on the left side of the picture',
    'Elke laag volgt zijn hoogte aan de rechterkant van het beeld': 'Each layer follows its height on the right side of the picture',
    'Kleuren van': 'Colours from', 'Het beeld': 'The picture', 'De Ambilight-leds': 'The Ambilight LEDs',
    'Het beeld (randen van het scherm)': 'The picture (edges of the screen)', 'De Ambilight-leds achter de tv': 'The Ambilight LEDs behind the TV',
    'Kleur': 'Colour', 'Zwakker': 'Weaker', 'Normaal': 'Normal', 'Sterker': 'Stronger', 'Veel sterker': 'Much stronger',
    'Scènes': 'Scenes', 'Scène': 'Scene', 'Scène stoppen': 'Stop scene', 'Huidige kleuren opslaan': 'Save current colours',
    'Naam voor je eigen scène': 'Name for your own scene', 'Geef de scène eerst een naam': 'Give the scene a name first',
    'Scène "{n}" verwijderen?': 'Delete scene "{n}"?',
    'Vaste of langzaam bewegende kleuren, in plaats van de sync. Sync starten stopt de scène.': 'Fixed or slowly moving colours instead of the sync. Starting the sync stops the scene.',
    'Pijltjes: kiezen. OK: aanzetten. Terug: sluiten. Sync starten stopt de scène.': 'Arrows: choose. OK: turn on. Back: close. Starting the sync stops the scene.',
    'Zonsondergang': 'Sunset', 'Roze & blauw': 'Pink & blue', 'Noorderlicht': 'Northern lights', 'Oceaan': 'Ocean', 'Haardvuur': 'Fireplace',
    'Regenboog': 'Rainbow', 'Warme gloed': 'Warm glow', 'Lentebloesem': 'Spring blossom', 'Filmavond': 'Movie night', 'Nachtlampje': 'Night light',
    'Ontspannen': 'Relax', 'Lezen': 'Read', 'Concentratie': 'Concentrate',
    'Bediening': 'Control', 'Kamer': 'Room', 'Status': 'Status', 'Instellingen van deze lamp': 'Settings for this lamp',
    'Tik op de naam van een lamp om hem in te stellen.': 'Tap the name of a lamp to set it up.',
    'Bovenrand': 'Top edge', 'Groep': 'Group', 'Voor': 'For', 'Nieuwe groep': 'New group', 'Verwijderen': 'Delete',
    'Naam van de groep': 'Name of the group', 'Alle lampen (standaard)': 'All lamps (default)', 'Geen (standaard)': 'None (default)',
    'Instellingen voor': 'Settings for', 'Groep {n}': 'Group {n}',
    'Deze instellingen gelden voor de lampen in "{n}". Kies de groep bij een lamp onder Lampen.': 'These settings apply to the lamps in "{n}". Choose the group for a lamp under Lamps.',
    'Lampen zonder groep gebruiken deze instellingen.': 'Lamps without a group use these settings.',
    'Groep "{n}" verwijderen? De lampen erin gaan terug naar de standaardinstellingen.': 'Remove group "{n}"? Its lamps go back to the default settings.', 'Volgt de bovenrand, {s}': 'Follows the top edge, {s}',
    'Lampen terug als de tv uit staat': 'Lamps back to normal when the TV is off',
    'Lampen terug als de tv uit staat (20 seconden zwart beeld)': 'Lamps back to normal when the TV is off (20 seconds of black picture)',
    'Tv lijkt uit': 'TV seems to be off',
    'Vloeiende overgangen': 'Smooth transitions', 'Scènewissels direct': 'Instant scene changes',
    'Scènewissels direct (uit = alles vloeit)': 'Instant scene changes (off = everything fades)', 'Ambilight van de tv volgen': "Follow the TV's Ambilight",
    'Ambilight van de tv volgen (uit = lampen uit)': "Follow the TV's Ambilight (off = lamps off)", 'Ambilight staat uit': 'Ambilight is off',
    'Volgt': 'Follows', 'Automatisch': 'Automatic', 'Hele zijkant': 'Whole side', 'Hele beeld': 'Whole picture',
    'Volgt het hele beeld': 'Follows the whole picture', 'Volgt de hele linkerkant van het beeld': 'Follows the whole left side of the picture',
    'Volgt de hele rechterkant van het beeld': 'Follows the whole right side of the picture',
    'Plafond': 'Ceiling', 'Vensterbank': 'Window sill', 'Tafel': 'Table', 'Van kaart': 'Off the map', 'Doet mee met sync': 'Takes part in sync',
    'Ip-adres': 'IP address', 'wordt vanzelf gevonden': 'found automatically', 'geen ip': 'no IP',
    'Kies een lamp en tik op de kaart om hem op te hangen. Sleep om te verschuiven.': 'Pick a lamp and tap the map to place it. Drag to move it.',
    'Je lampen': 'Your lamps', 'Alle lampen na elkaar testen': 'Test all lamps one by one',
    'Knipper laat een lamp aan en uit gaan zodat je hem vindt. Test laat hem rood, groen, blauw en wit zien en zet hem daarna terug. Met het kleurvakje kies je zelf een kleur.':
      'Blink switches a lamp on and off so you can find it. Test shows red, green, blue and white and then puts it back. Use the colour box to pick your own colour.',
    'Lampen toevoegen': 'Add lamps', 'Nieuwe lampen zoeken': 'Find new lamps', 'Andere sleutels': 'Different keys',
    'Eenmalig: vul de Access ID en het Access Secret in van je project op iot.tuya.com (Cloud, je project, Overview). Ze blijven op de tv.':
      'One time: enter the Access ID and Access Secret of your project on iot.tuya.com (Cloud, your project, Overview). They stay on the TV.',
    'Central Europe (Nederland)': 'Central Europe (Netherlands)', 'Opslaan en zoeken': 'Save and search', 'Zoeken...': 'Searching...',
    'Verwijderde lampen': 'Removed lamps', 'Terugzetten': 'Restore', 'Of: devices.json van tinytuya uploaden': 'Or: upload devices.json from tinytuya',
    'Leg vloer door over de vakjes te vegen. De muren komen er vanzelf omheen. 1 vakje is 1 meter.': 'Lay floor by swiping over the squares. The walls appear around it automatically. 1 square is 1 metre.',
    'Veeg over vakjes om ze weg te halen, bijvoorbeeld voor een L-vormige kamer.': 'Swipe over squares to remove them, for example for an L-shaped room.',
    'Zet je meubels neer zodat je de kamer herkent.': 'Place your furniture so you recognise the room.',
    'Tik waar de tv staat.': 'Tap where the TV is.', 'Kies een lamp en hang hem op.': 'Pick a lamp and place it.',
    'Kies eerst een lamp hieronder': 'Pick a lamp below first', 'Hele vloer aangepast': 'Whole floor changed',
    '{i} inch ({m} m breed)': '{i} inch ({m} m wide)', 'Nog geen lampen. Voeg ze onderaan toe.': 'No lamps yet. Add them at the bottom.',
    'Nog geen lampen.': 'No lamps yet.',
    '"{n}" verwijderen? Je kunt hem later terugzetten onder Lampen toevoegen.': 'Remove "{n}"? You can restore it later under Add lamps.',
    'Nog niet opgehangen': 'Not placed yet',
    'Volgt de linkerkant van het beeld, van onder tot boven': 'Follows the left side of the picture, from bottom to top',
    'Volgt de rechterkant van het beeld, van onder tot boven': 'Follows the right side of the picture, from bottom to top',
    'Volgt het midden van het beeld, van onder tot boven': 'Follows the middle of the picture, from bottom to top',
    'Volgt de linkerkant van het beeld': 'Follows the left side of the picture', 'Volgt de rechterkant van het beeld': 'Follows the right side of the picture',
    'Volgt {e}, {s}': 'Follows {e}, {s}', 'de bovenrand': 'the top edge', 'de zijkanten': 'the sides', 'de onderkant': 'the bottom',
    'linkerdeel': 'left part', 'rechterdeel': 'right part', 'midden': 'middle',
    'opslaan...': 'saving...', 'opgeslagen': 'saved', 'niet opgeslagen': 'not saved',
    // meubels en vloeren
    'Bank': 'Sofa', 'Hoekbank': 'Corner sofa', 'Fauteuil': 'Armchair', 'Salontafel': 'Coffee table', 'Eettafel': 'Dining table',
    'Plant': 'Plant', 'Kleed': 'Rug', 'Kast': 'Cabinet', 'Bed': 'Bed',
    'Eiken': 'Oak', 'Licht hout': 'Light wood', 'Donker hout': 'Dark wood', 'Parket': 'Parquet', 'Blauwe tegels': 'Blue tiles',
    'Zwart-wit': 'Black and white', 'Steen': 'Stone', 'Tapijt': 'Carpet',
  };

  function t(s, v) {
    let r = lang === 'en' && EN[s] !== undefined ? EN[s] : s;
    if (v) for (const k in v) r = r.split('{' + k + '}').join(v[k]);
    return r;
  }

  /** Vertaalt vaste tekst, placeholders en elementen met data-en (voor tekst met opmaak). */
  function apply(root) {
    root = root || document.body;
    document.documentElement.lang = lang;
    if (lang !== 'en') return;
    root.querySelectorAll('[data-en]').forEach(e => { e.innerHTML = e.dataset.en; });
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = []; while (w.nextNode()) nodes.push(w.currentNode);
    for (const n of nodes) {
      const s = n.nodeValue.trim();
      if (s && EN[s] !== undefined) n.nodeValue = n.nodeValue.replace(s, EN[s]);
    }
    root.querySelectorAll('[placeholder]').forEach(e => { const p = e.getAttribute('placeholder'); if (EN[p]) e.setAttribute('placeholder', EN[p]); });
    if (EN[document.title]) document.title = EN[document.title];
  }

  /** Release-notes: alleen de sectie van de gekozen taal (## Nederlands / ## English). */
  function notes(body) {
    const want = lang === 'en' ? /english/i : /nederlands|dutch/i;
    const out = []; let any = false, inSec = false;
    for (const l of (body || '').split(/\r?\n/)) {
      if (/^#+\s*/.test(l)) { any = true; inSec = want.test(l); continue; }
      if (!any || inSec) out.push(l);
    }
    return out.map(l => l.replace(/^[-*]\s*/, '').trim()).filter(Boolean);
  }

  return { t, apply, notes, set(l) { lang = l === 'en' ? 'en' : 'nl'; }, get lang() { return lang; } };
})();
const t = I18N.t;
