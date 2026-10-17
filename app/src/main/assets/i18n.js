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
    'Licht bij zwart beeld': 'Light on a black screen', 'Blikveld van de lampen': 'Lamp field of view',
    'Smal: elke lamp volgt zijn eigen stukje rand van het beeld. Breed: lampen nemen meer van het hele beeld mee, rustiger en meer één kleur.': 'Narrow: each lamp follows its own part of the picture edge. Wide: lamps take in more of the whole picture, calmer and more one colour.', 'Na stoppen': 'After stopping',
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
    'Kleur': 'Colour', 'Zoeken naar updates': 'Check for updates', 'OK: nu zoeken': 'OK: check now', 'App': 'App', 'Soort lamp': 'Lamp type', 'Spot': 'Spot', 'Hanglamp': 'Pendant', 'Staande lamp': 'Floor lamp', 'Booglamp': 'Arc lamp', 'Tafellamp': 'Table lamp', 'Bollamp': 'Globe lamp', 'Alleen automatisch na zonsondergang': 'Only automatically after sunset',
    'Alleen automatisch syncen na zonsondergang (overdag zet je hem zelf aan)': 'Only sync automatically after sunset (during the day you start it yourself)',
    'Starten': 'Start', 'Plek': 'Place', 'Starten t.o.v. zonsondergang': 'Start relative to sunset', 'bij zonsondergang': 'at sunset',
    '{n} min voor zonsondergang': '{n} min before sunset', '{n} min na zonsondergang': '{n} min after sunset', '{n} min ervoor': '{n} min before', '{n} min erna': '{n} min after',
    'Breedte- en lengtegraad voor de zonsondergang, standaard het midden van Nederland. Een paar tientallen kilometer verschil scheelt maar een paar minuten.': 'Latitude and longitude for the sunset, by default the middle of the Netherlands. A few dozen kilometres only makes a few minutes of difference.',
    'Vandaag: zonsondergang {s}, de sync start om {b}. Zonsopkomst {r}.': 'Today: sunset {s}, the sync starts at {b}. Sunrise {r}.',
    'Zwarte balken opvangen': 'Handle black bars',
    'Zwarte balken opvangen (films in breedbeeld): lampen bij de lege rand nemen de kleur van de randen ernaast over': 'Handle black bars (widescreen films): lamps at the empty edge take the colour of the edges next to it',
    'Film in breedbeeld met zwarte balken. Aan: lampen bij de bovenrand nemen de kleur van de zijkanten over.': 'Widescreen film with black bars. On: lamps at the top edge take the colour of the sides.',
    'Pauzeren op het startscherm': 'Pause on the home screen',
    'Pauzeren op het startscherm (ook bij schermbeveiliging en tv-instellingen); verder zodra je een app opent': 'Pause on the home screen (also screensaver and TV settings); continue as soon as you open an app',
    'Hiervoor heeft de app "Gebruikstoegang" nodig: tv-instellingen, Apps, Speciale app-toegang, Gebruikstoegang, Ambilight Lampen aan.': 'This needs "Usage access": TV settings, Apps, Special app access, Usage access, turn on Ambilight Lampen.',
    'Bewaren en terugzetten': 'Back up and restore', 'Bestand downloaden': 'Download file',
    'Bewaar je kamer, lampen, groepen, kalibraties, scènes en instellingen in een bestand, bijvoorbeeld voor het opnieuw installeren van de app.': 'Save your room, lamps, groups, calibrations, scenes and settings to a file, for example before reinstalling the app.',
    'Met sleutels (lampen, Tuya, Hue, tv-koppeling). Bewaar het bestand dan goed: wie het heeft, kan je lampen bedienen.': 'With keys (lamps, Tuya, Hue, TV pairing). Keep the file safe: whoever has it can control your lamps.',
    'Terugzetten uit een bestand. Sleutels die niet in het bestand staan, blijven zoals ze nu zijn.': 'Restore from a file. Keys that are not in the file stay as they are now.',
    'Alles terugzetten uit "{n}"? Je huidige kamer, lampen en instellingen worden vervangen.': 'Restore everything from "{n}"? Your current room, lamps and settings will be replaced.',
    'Maximale helderheid': 'Maximum brightness', 'Zwakker': 'Weaker', 'Normaal': 'Normal', 'Sterker': 'Stronger', 'Veel sterker': 'Much stronger',
    'Scènes': 'Scenes', 'Scène': 'Scene', 'Scène stoppen': 'Stop scene', 'Huidige kleuren opslaan': 'Save current colours',
    'Naam voor je eigen scène': 'Name for your own scene', 'Geef de scène eerst een naam': 'Give the scene a name first',
    'Scène "{n}" verwijderen?': 'Delete scene "{n}"?',
    'Vaste of langzaam bewegende kleuren, in plaats van de sync. Sync starten stopt de scène.': 'Fixed or slowly moving colours instead of the sync. Starting the sync stops the scene.',
    'Pijltjes: kiezen. OK: aanzetten. Terug: sluiten. Sync starten stopt de scène.': 'Arrows: choose. OK: turn on. Back: close. Starting the sync stops the scene.',
    'Zonsondergang': 'Sunset', 'Roze & blauw': 'Pink & blue', 'Noorderlicht': 'Northern lights', 'Oceaan': 'Ocean', 'Haardvuur': 'Fireplace',
    'Regenboog': 'Rainbow', 'Warme gloed': 'Warm glow', 'Lentebloesem': 'Spring blossom', 'Filmavond': 'Movie night', 'Nachtlampje': 'Night light',
    'Ontspannen': 'Relax', 'Lezen': 'Read', 'Concentratie': 'Concentrate',
    'WiZ-lampen': 'WiZ lamps', 'WiZ-lampen zoeken': 'Find WiZ lamps',
    'Werkt met een Hue Bridge. Lampen van andere merken die aan de Bridge gekoppeld zijn (zoals IKEA) doen ook mee. Laat het ip-adres leeg, dan zoekt de tv de Bridge zelf.': 'Works with a Hue Bridge. Lamps of other brands paired to the Bridge (such as IKEA) join in too. Leave the IP address empty and the TV finds the Bridge itself.',
    'Ip-adres van de Bridge (mag leeg)': 'Bridge IP address (may be empty)', 'Koppelen': 'Pair', 'Bezig...': 'Busy...', 'Ontkoppelen': 'Unpair',
    'Voor de snelste reactie: maak in de Hue-app een Entertainment-ruimte (Instellingen, Entertainment-ruimtes) met je Hue-lampen. Andere lampen krijgen gewone opdrachten, samen ongeveer 10 per seconde.': 'For the fastest response: create an entertainment area in the Hue app (Settings, Entertainment areas) with your Hue lamps. Other lamps get normal commands, about 10 per second together.',
    'Bridge {ip}: {n} lamp(en), waarvan {s} via Entertainment.': 'Bridge {ip}: {n} lamp(s), {s} of them via entertainment.', '{n} lamp(en)': '{n} lamp(s)',
    'Bridge ontkoppelen? De Hue-lampen gaan naar Verwijderde lampen.': 'Unpair the Bridge? The Hue lamps move to Deleted lamps.',
    'Koppel ze eerst in de WiZ-app en zet daar bij Instellingen, Beveiliging "Lokale communicatie" aan. De tv vindt ze dan vanzelf.': 'Pair them in the WiZ app first and turn on "Local communication" under Settings, Security. The TV then finds them by itself.',
    'Met een groep geef je een paar lampen hun eigen instellingen. Kies hier voor welke lampen je instelt, of maak een nieuwe groep.': 'A group gives a few lamps their own settings. Choose which lamps you are setting here, or make a new group.',
    'Sluiten': 'Close', 'Instellingen van groep': 'Group settings', 'Groepen': 'Groups', 'Nog geen groepen.': 'No groups yet.',
    'Nog geen lampen in deze groep': 'No lamps in this group yet', 'Deze instellingen gelden voor de lampen in "{n}".': 'These settings apply to the lamps in "{n}".',
    'Kies voor welke lampen je instelt: alle lampen, of een groep. Groepen maak je onder Lampen.': 'Choose which lamps you are setting: all lamps, or a group. You make groups under Lamps.',
    'Lampen in een groep krijgen samen eigen instellingen, bijvoorbeeld rustiger of feller. Tik de lampen aan die in de groep horen. Een lamp die al in een groep zit, zie je bij de andere groepen niet.': 'Lamps in a group share their own settings, for example calmer or brighter. Tap the lamps that belong in the group. A lamp that is already in a group is not shown in the other groups.',
    'Definitief verwijderen': 'Delete for good',
    'Terugzetten zet de lamp weer in je lijst. Definitief verwijderen haalt hem hier weg; hij komt dan ook niet terug bij nieuwe lampen zoeken.': 'Restore puts the lamp back in your list. Delete for good removes it here; it will not come back when finding new lamps either.',
    '"{n}" definitief verwijderen? Hij komt dan niet meer terug bij zoeken.': 'Delete "{n}" for good? It will not come back when searching.',
    'Alle lampen zitten al in een andere groep.': 'All lamps are already in another group.',
    'Lampen uit': 'Lamps off', 'Lampen aan': 'Lamps on',
    'Bediening': 'Control', 'Kamer': 'Room', 'Status': 'Status', 'Instellingen van deze lamp': 'Settings for this lamp',
    'Tik op de naam van een lamp om hem in te stellen.': 'Tap the name of a lamp to set it up.',
    'Bovenrand': 'Top edge', 'Groep': 'Group', 'Voor': 'For', 'Nieuwe groep': 'New group', 'Verwijderen': 'Delete',
    'Naam van de groep': 'Name of the group', 'Alle lampen (standaard)': 'All lamps (default)', 'Geen (standaard)': 'None (default)',
    'Instellingen voor': 'Settings for', 'Groep {n}': 'Group {n}',
    'Deze instellingen gelden voor de lampen in "{n}". Kies de groep bij een lamp onder Lampen.': 'These settings apply to the lamps in "{n}". Choose the group for a lamp under Lamps.',
    'Geldt voor alle lampen, ook die in een groep. Kies een groep om die apart in te stellen.': 'Applies to all lamps, including those in a group. Choose a group to set it separately.',
    'Groep "{n}" verwijderen? De lampen erin gaan terug naar de standaardinstellingen.': 'Remove group "{n}"? Its lamps go back to the default settings.', 'Volgt de bovenrand, {s}': 'Follows the top edge, {s}',
    'Lampen terug als de tv uit staat': 'Lamps back to normal when the TV is off',
    'Lampen terug als de tv uit staat (20 seconden zwart beeld)': 'Lamps back to normal when the TV is off (20 seconds of black picture)',
    'Tv lijkt uit': 'TV seems to be off', 'Startscherm': 'Home screen',
    'Vloeiende overgangen': 'Smooth transitions', 'Scènewissels direct': 'Instant scene changes',
    'Scènewissels direct (uit = alles vloeit)': 'Instant scene changes (off = everything fades)', 'Ambilight van de tv volgen': "Follow the TV's Ambilight",
    'Ambilight van de tv volgen (uit = lampen uit)': "Follow the TV's Ambilight (off = lamps off)", 'Ambilight staat uit': 'Ambilight is off',
    'Volgt': 'Follows', 'Automatisch': 'Automatic', 'Hele zijkant': 'Whole side', 'Hele beeld': 'Whole picture',
    'Volgt het hele beeld': 'Follows the whole picture', 'Volgt de hele linkerkant van het beeld': 'Follows the whole left side of the picture',
    'Volgt de hele rechterkant van het beeld': 'Follows the whole right side of the picture',
    'Reageert deze lamp niet?': 'Lamp not responding?', 'Opnieuw verbinden': 'Reconnect', 'Bezig, dit duurt een paar seconden...': 'Working, this takes a few seconds...',
    'Kleur bij donker beeld': 'Colour in dark scenes', 'Schermhelft': 'Screen half', 'Dichtstbijzijnde lamp': 'Nearest lamp',
    'Gemiddelde van de schermhelft aan de kant van de lamp': "Average of the screen half on the lamp's side", 'Kleur van de dichtstbijzijnde lamp': 'Colour of the nearest lamp',
    'Kleurcorrectie bij weinig licht': 'Colour correction in low light', 'Instellen': 'Set up', 'Geen correctie': 'No correction',
    'Naar {c}, sterkte {s}%': 'Towards {c}, strength {s}%', 'Grijs': 'Grey', 'Donkerblauw': 'Dark blue',
    'Helderheid voor de test': 'Brightness for the test', 'Tegenkleur': 'Counter colour', 'Sterkte': 'Strength',
    'rood': 'red', 'oranje': 'orange', 'geel': 'yellow', 'groen': 'green', 'cyaan': 'cyan', 'blauw': 'blue', 'paars': 'purple', 'roze': 'pink',
    'Bij Wit wisselt de lamp steeds tussen zijn eigen witte licht en kleurlicht. Schuif de tegenkleur en de sterkte tot je bij het wisselen geen verschil in tint meer ziet. Is het kleurlicht roze, schuif dan naar cyaan of groen. Bij Donkerblauw laat de tv dezelfde kleur zien om mee te vergelijken. De correctie werkt alleen als de lamp zacht brandt.': 'With White the lamp keeps switching between its own white light and colour light. Move the counter colour and strength until you see no difference in tint when it switches. If the colour light is pink, move towards cyan or green. With Dark blue the TV shows the same colour to compare with. The correction only applies when the lamp is dimmed.',
    'Vergelijk de lamp met deze kleur. Let op de tint, niet op hoe fel hij is. Terug: stoppen.': 'Compare the lamp with this colour. Look at the tint, not how bright it is. Back: stop.',
    'De lamp wisselt tussen wit licht en kleurlicht. Schuif op je telefoon tot je geen verschil in tint meer ziet. Terug: stoppen.': 'The lamp switches between white light and colour light. Adjust on your phone until you see no difference in tint. Back: stop.',
    'Witte leds voor wit, geel en donker': 'White LEDs for white, yellow and dark',
    'Witte leds gebruiken voor witte, gele en donkere tinten (beter wit en geel, minder roze; kan bij wisselen kort knipperen)': 'Use white LEDs for white, yellow and dark tints (better white and yellow, less pink; may flicker briefly when switching)',
    'Verander je iets, dan laat de tv een testbeeld zien waarop je ziet wat het met je lampen doet.': 'When you change something, the TV shows a test picture so you can see what it does to your lamps.',
    'Vlammen die snel veranderen. Hoger: de lampen volgen sneller. Lager: rustiger.': 'Quickly changing flames. Higher: the lamps follow faster. Lower: calmer.',
    'Zachte, flauwe kleuren. Hoger: de lampen maken ze voller.': 'Soft, faded colours. Higher: the lamps make them richer.',
    'Het beeld wordt stap voor stap feller. Hoger: de lampen branden feller bij hetzelfde beeld.': 'The picture gets brighter step by step. Higher: the lamps are brighter for the same picture.',
    'Zwart beeld met af en toe een donkere scène. Dit bepaalt hoe fel de lampen blijven bij zwart.': 'Black picture with an occasional dark scene. This sets how bright the lamps stay on black.',
    'Links rood, boven groen, rechts blauw. Smal: elke lamp volgt zijn eigen stukje rand. Breed: lampen nemen meer van het hele beeld mee.': 'Red on the left, green on top, blue on the right. Narrow: each lamp follows its own part of the edge. Wide: lamps take in more of the whole picture.',
    'Donker beeld: links donkerblauw, rechts donkerrood. Schermhelft: elke lamp volgt zijn eigen kant.': 'Dark picture: dark blue on the left, dark red on the right. Screen half: each lamp follows its own side.',
    'Kleine kleurstapjes. Aan: de lampen vloeien mee. Uit: ze springen.': 'Small colour steps. On: the lamps fade along. Off: they jump.',
    'Harde scènewissels. Aan: de lampen springen direct mee. Uit: ze vloeien ook hier.': 'Hard scene changes. On: the lamps jump right away. Off: they fade here too.',
    'Nu: {c}. Aan: wit, grijs, geel en donker met de witte leds; rood en blauw blijven kleurlicht.': 'Now: {c}. On: white, grey, yellow and dark use the white LEDs; red and blue stay colour light.',
    'Warmgeel': 'Warm yellow', 'Donkergrijs': 'Dark grey', 'Rood': 'Red', 'Blauw': 'Blue',
    'Helderheid witte leds': 'White LED brightness',
    'Wisselt tussen lichtgeel (witte leds) en oranje (kleurleds). Zet hem zo dat ze even fel lijken.': 'Switches between light yellow (white LEDs) and orange (colour LEDs). Set it so they look equally bright.',
    'Kalibreren': 'Calibrate', 'Hoogte': 'Height', 'Kleur bij weinig licht': 'Colour in low light', 'Wit': 'White',
    'Hoogte kalibreren': 'Calibrate height', 'Klaar': 'Done', 'Boven de tv': 'Above the TV', 'Onder de tv': 'Below the TV',
    'Of stel de hoogte precies af met kleuren op de tv.': 'Or set the height precisely with colours on the TV.',
    'Gekalibreerd: boven de tv': 'Calibrated: above the TV', 'Gekalibreerd: onder de tv': 'Calibrated: below the TV',
    'Gekalibreerd: band {i} van {n} (van onder)': 'Calibrated: band {i} of {n} (from the bottom)',
    'De tv toont nu gekleurde banden. Tik de kleur die op dezelfde hoogte staat als deze lamp. De lamp neemt die kleur dan over, zo zie je meteen of het klopt.': 'The TV now shows coloured bands. Tap the colour that is at the same height as this lamp. The lamp then takes on that colour, so you can see right away if it is right.',
    'Hoogte kalibreren: kies op je telefoon de kleur die op dezelfde hoogte als de lamp staat. Terug: stoppen.': 'Height calibration: on your phone, pick the colour at the same height as the lamp. Back: stop.',
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
/** Tekst veilig in HTML zetten (namen van lampen en groepen komen van buiten: cloud, Bridge, telefoon). */
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
