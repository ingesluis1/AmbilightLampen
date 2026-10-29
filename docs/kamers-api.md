# Kamers: API-afspraak (stap 2 Lampen-hub)

Geldt voor de tv (`WebServer.kt`) en de hub (`LampenHub/.../HubWeb.kt`). Ontwerp: prototype
https://claude.ai/artifact/NsGyUgiXpQsDEECX7wN7fa (besloten 29-10).

## Gegevens

- Een **kamer**: `{ id, name, color: "#rrggbb", tv: bool, layout }`. `layout` is precies de huidige indeling
  (`w`, `h`, `floor`, `items`, `fixtures`, `tv`, `aims`, `tilts`). Precies één kamer heeft `tv: true` (ook op de hub).
- Een **lamp** krijgt `room` (id van de kamer, of `""` = zonder kamer). `x`/`y` gelden in de eigen kamer.
- **Overzetten:** een config zonder kamers wordt één kamer `{ id: "woonkamer", name: "Woonkamer", tv: true, layout: <oude layout> }`
  en alle lampen krijgen `room: "woonkamer"`. Is er maar één kamer, dan komen lampen zonder (bekende) kamer vanzelf in die kamer
  (vrienden met alleen een tv merken niets). Met meer kamers komen nieuwe lampen in "zonder kamer".
- **Sync** (alleen tv) gebruikt alleen lampen in de tv-kamer.
- **Scènes** zijn één lijst voor het hele huis, maar lopen **per kamer**: elke kamer kan tegelijk een andere scène hebben.

## `GET /api/state` (nieuw/gewijzigd)

```
rooms: [ { id, name, color, tv, layout, scene: "<scène-id of ''>", sceneName: "<naam of ''>" } ]   // in de volgorde van de gebruiker
lamps[].room: "<kamer-id of ''>"
layout, activeScene, activeName   // blijven: die van de tv-kamer (tv.html en oude pagina's)
liveLamps                          // blijft: alle lampen die nu in een "eigen indeling" (live) staan, over alle kamers
```

## Opdrachten (POST, JSON)

| Route | Body | Wat |
|---|---|---|
| `/api/scene` | `{ id, room? }` | `room` = kamer-id: alleen die kamer. `room: "*"`: alle kamers. Zonder `room`: de tv-kamer (zoals nu). `id: ""` stopt de scène in die kamer(s). |
| `/api/lamps/power` | `{ on, room? }` | `room` = kamer-id: lampen van die kamer (stopt een scène daar; tv-kamer: stopt ook de sync). `room: "*"`: hele huis, maar bij `on: false` blijft de tv-kamer aan als de tv synct of daar een scène loopt (antwoord noemt dat). Zonder `room`: alle lampen (zoals nu). |
| `/api/live` | `{ id, on?, colour?, bright?, effect? }` | Ongewijzigd; de kamer volgt uit de lamp. De eigen indeling loopt in die kamer. |
| `/api/scene/save` | `{ name, room? }` | Huidige kleuren van die kamer opslaan (zonder `room`: tv-kamer). |
| `/api/save` | `{ room?, layout?, lamps?: [{ id, room?, x?, y?, ... }], ... }` | `layout` hoort bij `room` (zonder `room`: tv-kamer). `lamps[].room` verhuist een lamp. |
| `/api/room/save` | `{ id?, name, color?, w?, h?, tv? }` | Zonder `id`: nieuwe kamer (lege indeling met maat `w`×`h`), antwoord `{ ok, message, id }`. Met `id`: hernoemen, kleur, maat, tv-kamer maken. |
| `/api/room/delete` | `{ id }` | Lampen gaan naar `""`, scène daar stopt. De laatste kamer en de tv-kamer kunnen niet weg (eerst een andere kamer tv-kamer maken). |
| `/api/room/order` | `{ ids: [...] }` | Nieuwe volgorde. |
