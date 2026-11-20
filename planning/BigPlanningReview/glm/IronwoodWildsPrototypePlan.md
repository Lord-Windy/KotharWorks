# Ironwood Wilds — Prototype Slice Plan (IronwoodWildsPrototypePlan.md)

> The build order: a vertical slice that plays the real loop end-to-end, sized
> in **Fridays** (one working day each — Sam's dedicated day until year end;
> milestones assume ~1 Friday each unless marked, with slack). Everything here
> reuses shapes already proven in ptah, so the risk is scope, not novelty.
> Nothing has been started — this is the plan only. Companion: all six other
> docs.

## 0. Slice definition (what "done" means for the prototype)

One persistent world, one region of generated map, **two households** (you + a
bot), 3 folk each, the full loop:

> land → chop/mine → craft tools → build a hut → skill past a gate → tech unlock
> → crate a shipment → Charter points → buy a 4th person → lose a fight on
> purpose → watch a raid bounce off a palisade

…all observable from a browser tab and replayable from the journal. If that
paragraph works, every system the real game needs has been exercised: procgen,
ECS, lazy time, queues, rules data, events, accounts, persistence, projections,
spectate.

**Non-goals for the slice** (deliberately): balance, art of any kind, real-time
anything, multiple regions, weather, births, trading, WS push, WASM. One region,
one biome, one monster, one raid.

## 1. Milestones

### M0 — Walking skeleton (Friday 1)

samhttp server (`apps/samwilds`), one hardcoded person, `move` + `look` via
`POST /v1/commands`, events endpoint, JSON in/out, per-tick world hash logged,
snapshot save/load to disk (no PG yet), determinism golden test (seeded commands
→ hash) in CTest. **Show:** curl a move, watch the event. **Proves:** the whole
async command → tick → event loop + determinism harness exists.

### M1 — Engine kernel: `libs/samwilds` ECS + lazy time (Fridays 2-3)

Sparse-set ECS, prefab stamping, rules-as-data tier-1 loader (durations, items,
skills), action queue (1 running + 8 pending) with completion heap, two-phase
validation (enqueue reject codes / start fail-soft), needs decay, event stream
with seq. samvillage is the reference implementation of half of this — port the
discipline, not the code. **Show:** golden replay of a scripted household day;
tick cost graph. **Proves:** Architecture §3-§5 core; C1-C6 decisions live.

### M2 — A world worth walking: `libs/samterra` region + real actions (Fridays 4-5)

One 64×64 region: elevation FBM + hydrology + biome (single-biome slice),
blue-noise flora/ore scatter, seeded name grammar; `chop`, `mine`, `craft` (3
recipes), inventory + hauling home; lazy region detail + freeze-on-touch + tile
revisions; map endpoint + ANSI/PNG map render tool. Statistical gates on scatter
densities. **Show:** render 5 seeds of the map; play a gather→craft loop in
curl. **Proves:** Procgen P1-P5 slice; the loop is now *on a map*.

### M3 — Household + web + accounts (Fridays 6-7)

Multi-folk households (3 each), tab-per-person server-rendered pages + the
polling JS island (feed, countdowns, queue reorder), signup/login (Argon2id,
sessions), API keys, PostgreSQL: accounts schema, journal + snapshots + 5-min
cadence, projection → household_points. Deploy candidate: run one weekend on
tinysam behind the proxy. **Show:** phone browser, two tabs, two people, queue
them for the evening; check back after dinner — everything finished while
closed. **Proves:** Web W1-W6, Persistence S1-S7 (slice depth).

### M4 — The game appears (Fridays 8-10)

Tech tree data (12 nodes, 2 eras), construction lifecycle (build/repair/decay),
shipment schedule + Charter points → migrant (migrant arrives by "ship" event),
expedition system with one beast type (deterministic resolver, wounds, one
death-capable encounter), raid #1: scheduled, pre-planned defense object,
auto-resolved report, stuff-not-folk losses. Bot household: `apps/samwilds-bot`
policy v1 (gather→build→ship loop over HTTP only). **Show:** the §0 slice
paragraph, end to end, with the bot as second household. **Proves:** gameplay
doc §4-§8 in miniature.

### M5 — Show-off pass + season dress rehearsal (Fridays 11-12, stretch)

Public spectate page + global feed, chronicle page (grammar-narrated from
journal), timelapse scrubber over snapshot diffs, season lifecycle scripts
(provision → open → close → archive → leaderboard), admin console (world hash
board, tick histogram), balance knob pass with the bot playing both sides.
**Show:** the thing you link people. **Proves:** the content thesis — the world
is watchable and resettable by design.

M4.5 optional interleave (1 Friday): SSE in samhttp if polling chafes during
M3/M4 play weekends — keep it pull-based until it hurts.

## 2. Sequencing logic & dependencies

```
M0 → M1 → M2 → M3 → M4 → M5
      │         │
      │         └─ PG + auth can start after M1 in parallel on any Friday
      └─ samterra M2 needs only samrena/samdata — parallelizable with M1 tail
```

Hard ordering is only M0→M1 (loop before content) and M2 before M3's map pages.
Every milestone ends **showable** — the Stansfield rule: if it can't be shown to
someone, it isn't done.

## 3. Risk register (what kills this, honestly)

| Risk                                               | Mitigation                                                                                                                                       |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| Fridays-only cadence loses thread between sessions | every milestone ends demoable + committed; docs in repo (`planning/`) updated same day; tickets in st (`tech-samwilds` epic) so context survives |
| Scope creep (the DF trap)                          | the non-goals list; tech tree is data so "more content" ≠ "more code"; new ideas go to season-2 params, not v1                                   |
| samhttp limits bite (no chunked, no WS)            | transport ladder is designed around it; SSE/WS are isolated, optional Fridays                                                                    |
| Determinism broken by accident (f32, wall leaks)   | integer-math rule, world-hash alarm in admin console from M0, golden tests from M1                                                               |
| Solo-operator ops burden                           | one process + PG; snapshot/journal crash recovery from M3; no node, no containers                                                                |
| It's not fun / nobody watches                      | that's what M3's weekend deploy and M5's spectate test *are* — the slice exists to falsify the thesis cheaply                                    |

## 4. What the slice deliberately keeps from ptah

samvillage (queue/validation/event/discipline), samgrognard
(records/hash/goldens/gates), samtrader/samtechtree (hexagonal app +
migrations), samgenai-studio (JS island), samstatic (statics), samtasker (the
project management itself). The plan is deliberately conservative: every
architectural move has an in-house precedent. The novelty budget is spent
exactly once — on `samterra` and the lazy multi-household world — where the game
actually lives.

## 5. First three concrete tickets (for when this becomes work)

1. `apps/samwilds` skeleton: samhttp hello + `POST /v1/commands` echo + 1 Hz
   tick thread + world hash log (M0).
2. `libs/samwilds` scaffold: entity/component/spawn + prefab table + arena
   lifecycle + error enum (M1 start).
3. `libs/samterra` field functions: seeded value-noise FBM + golden hash test +
   CLI render of a 64×64 region (M2 start, parallelizable).
