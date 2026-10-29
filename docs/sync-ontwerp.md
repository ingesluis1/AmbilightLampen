# Tv en Lampen-hub gelijk houden (stap 3)

Wens Inge 29-10: de kamer met de tv en alle eigen scènes zijn op de tv en de hub hetzelfde. Een wijziging op de ene
komt op de andere. Zonder hub werkt de tv-app zoals altijd.

## Wat gaat over en weer

| Wat | Waar het hoort | Over en weer |
|---|---|---|
| Tv-kamer: naam, kleur, plattegrond (`layout`) | kamer met `tv: true` aan beide kanten | ja |
| Lampen in de tv-kamer: alles van `Lamp` (ook sleutel, plaats, instellingen per lamp) | `lamp.room` = tv-kamer | ja |
| Eigen scènes (zelf gemaakt, opgeslagen, uit Hue) | hele huis | ja, ook nieuw, hernoemd, verwijderd |
| Andere kamers en hun lampen | alleen op de hub | nee (de tv kent ze niet) |
| Sync-instellingen, groepen, per-app, tv-koppeling, Hue/IKEA-koppeling | alleen op de tv | nee (later eventueel groepen) |

De tv-kamer heet aan beide kanten in de sync `tv`, ook als de id's verschillen. Verhuist iemand op de hub een lamp naar
een andere kamer, dan zet de tv hem op "zonder kamer" (hij blijft bestaan, terugverhuizen werkt). Een nieuwe lamp op de
tv komt op de hub in de tv-kamer.

## Hoe

- **De tv belt de hub**, niet andersom (de hub hoeft het adres van de tv niet te weten). Gewone HTTP naar de hub, elke
  5 seconden en meteen na een eigen wijziging; geen websocket nodig voor deze snelheid.
- **Volgnummers deelt de hub uit.** Elk ding (`kamer:tv`, `lamp:<id>`, `scene:<id>`) heeft op de hub een volgnummer
  `seq`. Elke geaccepteerde wijziging krijgt het volgende nummer. Laatste wijziging die bij de hub aankomt, wint; de klok
  van de tv telt niet (die klopt niet altijd, zie Mathieu).
- **Verwijderen** = grafsteen: `{ deleted: true, seq }`. Grafstenen blijven 90 dagen bewaard, zodat een tv die lang uit
  stond het verwijderde niet terugzet.
- **Ruil** (`POST /api/sync`, met koppelsleutel in een kopregel):
  ```
  tv  -> hub: { since: <laatste seq die de tv kent>, changes: [ { key: "lamp:abc", data: {...} | deleted: true } ] }
  hub -> tv : { seq: <hoogste seq>, changes: [ { key, seq, data | deleted } ]   // alles met seq > since, behalve wat de tv net zelf stuurde
            }
  ```
  De tv houdt bij welke dingen hij sinds de laatste ruil zelf veranderde (vuil-lijst, ook bewaard bij herstart).
  Lukt de ruil niet (hub uit), dan blijft de lijst staan en gaat de tv gewoon door.
- **Eerste koppeling:** de hub wint, behalve als de hub nog geen tv-kamer met lampen heeft (dan neemt de hub alles van de
  tv over). Zo raakt Inge niets kwijt en hoeft ze niets dubbel in te stellen.

## Koppelen (eenmalig)

1. Op de telefoonpagina van de tv: Instellingen, "Lampen-hub", adres invullen (bv. `192.168.1.177:8790`), Koppelen.
2. Op de hub verschijnt bovenaan "Tv wil koppelen: TPV Philips … (cb728a)  [Toestaan] [Weigeren]".
3. Na Toestaan krijgt de tv een geheime sleutel; alle ruil-verzoeken moeten die meesturen. Ontkoppelen kan aan beide kanten.

Sleutels van lampen gaan alleen over het thuisnetwerk, en alleen naar een hub die je zelf hebt toegestaan.

## Niet in deze stap

- Wie de lampen stuurt als tv en hub tegelijk iets doen (stap 4: lampen claimen, scène van de hub doorgeven aan de tv).
- Andere kamers op de tv tonen.
- Bediening buitenshuis.

## Bouwen

1. core: `SyncState` (seq per ding, vuil-lijst, grafstenen) + omzetten van/naar `Lamp`, tv-kamer en `Scene`; tests met een
   nagebootste hub (wijziging op tv, op hub, tegelijk, verwijderen, hub lang weg).
2. hub: `/api/sync`, koppelverzoeken, sleutels; pagina: koppelverzoek toestaan.
3. tv: ruil-lus in `SyncService`, koppelen in Instellingen, status ("Gekoppeld met hub, laatst bijgewerkt 12:03").
4. Testen met de echte tv en hub.
