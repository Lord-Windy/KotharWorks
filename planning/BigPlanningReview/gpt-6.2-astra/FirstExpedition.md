# The first expedition — “What did you do with the first iron?”

**The demo:** open two person tabs, give both people a three-step plan, close
the browser, and come back to a hold with a decision to make. On the same land,
let a bot make the other decision. Put the two chronicles side by side. That is
a small simulation you could show someone, not a list of subsystems that might
someday make one.

## The reference clearing

This is a proposed **fixture** named `ford-fixture-v1`, deliberately concrete
enough to run on paper. The complete slice must also work on a
[generated clearing](WorldGenerationAndContent.md); fix its first golden seed
only after seeing actual generator output. The
[wire example](TimeAndCommands.md) is the same order Mara receives below.

```text
Alder Stand (s2) ←── 20 s ──→ Landing / hold / home gate (s0) ←── 30 s ──→ Greybank (s1)
   18 timber                      Mara (p1), Kit (p2)                         24 ironstone
```

| Rule in the fixture | Value and consequence                                                                                                                                                                                                                                  |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Charter             | Two adults at the landing, starter field kits usable by either, level-1 skills, three bunks, no banked goods, zero marks. Each adult carries one load; both can act at the same time. The home cache has room for all fixture goods.                   |
| Gather              | At the stand, 60 s → 3 timber. At Greybank, 180 s → 2 ironstone. One work slot per household at each site. Quantities are finite; claim before work and put the output in that person's pack at finish. Returning home banks it.                       |
| Skills and tech     | An iron load earns the worker 10 Mining XP. At 20 XP that person's *next* iron gathering takes 150 s. On first banking 3 cumulative timber, the household permanently learns `tech.field_carpentry`. Spending the timber doesn't erase that knowledge. |
| Build               | `building.haul_shed` at home: Field Carpentry + 3 timber + 2 ironstone, 60 s. One per hold. Each **new** travel leg touching home is 10 s shorter, minimum 1 s. The shed belongs to the household that paid for it.                                    |
| Supply and recruit  | `contract.iron`: ship 2 banked ironstone from home, 30 s → 2 charter marks. `charter.settler`: 4 marks and 1 free bunk, 30 s → 1 new idle adult at the landing. No market price fluctuations in this run.                                              |

## Watch the first four minutes

At second `0`, Mara's tab queues `[travel s1, gather ironstone, travel s0]`;
Kit's queues `[travel s2, gather alder, travel s0]`. Times below are season
time, not when somebody happened to view a page.

| Time        | Mara                                           | Kit                            | What changed at home                                                             |
| ----------- | ---------------------------------------------- | ------------------------------ | -------------------------------------------------------------------------------- |
| 0:00        | Walking to Greybank                            | Walking to Alder Stand         | Both queues accepted; stores empty.                                              |
| 0:20 / 0:30 | Starts mining at 0:30                          | Starts cutting at 0:20         | Materials are claimed at the sites; neither is available to build with.          |
| 1:20        | Mining                                         | Picks up 3 timber; starts home | Still no timber in stores.                                                       |
| 1:40        | Mining                                         | Delivers the timber; waits     | 3 timber available. Field Carpentry unlocks; the shed still lacks 2 ironstone.   |
| 3:30        | Gains 10 Mining XP, carries 2 ore; starts home | Waiting                        | No ore in stores yet.                                                            |
| 4:00        | Delivers ore; waits                            | Waiting                        | 2 ironstone + 3 timber. A shed and a shipment are each affordable, but not both. |

A browser snapshot at **1:50** might look like this:

```text
IRONWOOD WILDS   ·   Ford expedition                     [Watch the valley]
Household: Reed      Stores: 3 timber · 0 ironstone       Charter marks: 0

People   [Mara • working]   [Kit • waiting]               Bunks: 2 / 3 used
Mara · Greybank     Mining 1 · 0/20 XP to level 2         Pack: empty
  Now     Mine ironstone             1:40 left · due 3:30
  Next    Return to Landing          estimate 0:30
  Plan    [Add a step]  [Replace pending]  [Clear pending]

Hold     Field Carpentry learned     Haul shed needs 2 more ironstone
Journal  1:40  Kit banked 3 timber at the Landing.   [Why?]
         1:40  That first load unlocked Field Carpentry.
```

**Fork A — send it home.** At 4:00 Kit starts the shipment while Mara sets out
on the same round trip. At 4:30 the home world has received 2 iron and the
household has 2 marks. Mara's second delivery arrives at 8:00 (the second mining
completion earned level 2, not a discount retroactively). Kit ships again, gets
to 4 marks at 8:30, and uses the spare bunk. At **9:00** there are three people,
zero spendable marks and a record of four ironstone supplied.

**Fork B — build for the valley.** At 4:00 Kit commits the same iron plus the
timber to the haul shed; it opens at **5:00**, with zero marks earned. Have Mara
depart at 5:00: Greybank is now 20 s away, mining still takes 180 s, and the
return takes 20 s. The next delivery arrives at **8:40**. The chronicle can
explain exactly which building saved the 20 s per circuit. A trip already in
progress when the shed opened would retain its old duration. These forks
demonstrate a tradeoff; neither proves which policy wins a season.

## What to build on Fridays, if you choose this plan

These are sequential stopping points for a solo developer, not a promise that
accounts, a database and a UI fit into one afternoon. The local paired run is a
useful year-end deliverable on its own. The online slice has a separate finish
line below.

| Friday / dependency                                            | Work package for that session                                                                                                                             | Runnable or readable evidence to leave with                                                                                                                            |
| -------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **20 Nov — design session**                                    | Walk the fixture, choose the first-iron fork and failure policy. Freeze only the charter, time order, observation and command shapes.                     | The numbers above can be checked with pencil and paper; two different uses of the first ore are both reachable.                                                        |
| **27 Nov — a household in memory** (needs fixture)             | Headless `samwilds` with fake clock, hand-drawn graph, two queues, transfers and the build/ship fork. Tiny scripted lab driver; simple rule rows suffice. | Jump from 0 to 240; get the same cargo, skills and timeline as advancing step by step. Print a text chronicle for each fork.                                           |
| **4 Dec — different land, same rules** (needs working core)    | Lift rule rows into a validated pack, run the small generator, add recruitment and two simple policies. Expose the paired-run summary.                    | Show two seeds with different route costs. On one seed show both policies and a causal line explaining their first divergence. This is the first shareable experiment. |
| **11 Dec — a run that survives you** (needs stable effects)    | PostgreSQL state, deadlines, journal and receipts; private account/household boundary. Verify one interruption before wiring a polished frontend.         | Kill during mining, restore, then lose and retry a shipment reply. Compare timeline and balances with an uninterrupted run.                                            |
| **18 Dec — two clients at the gate** (needs durable admission) | Same-origin person tabs and public watch page, minimal external HTTP bot, sign-in/key issuance and side-by-side run.                                      | One browser household and one bot household on a generated season; stale tabs don't duplicate actions. Capture the two chronicles.                                     |
| **25 Dec — unallocated**                                       | Buffer or holiday. If durability/integration takes longer, carry the online gate forward and show the local experiment.                                   | A useful stopping point without relying on Christmas Day.                                                                                                              |

### What qualifies as the online slice

1. **Causality and determinism:** on the fixture at `t=209` there is zero ore at
   home and in Mara's pack; at `210` she carries two; at `240` the hold has two.
   Tiny time steps, one jump and restart-mid-action give the same ordered
   gameplay facts. Leave both queues empty for another day: neither person
   schedules new work.
2. **Ownership and contention:** two accounts can join one generated world. Each
   can administer only its own people and allotments. Try build and ship on the
   same two ore; exactly one can spend it. A repeated idempotency key after a
   lost reply can't mint goods, XP or marks. Replacing a queue from a stale tab
   cannot erase a newer plan.
3. **Depth in miniature:** show the skill threshold affecting the next mine, the
   timber unlock affecting construction, and the construction affecting the next
   route. Supply twice, recruit once; a new adult appears and waits for orders.
   A human and an external bot can reach these outcomes through the same
   contract.
4. **Legibility:** a spectator can say who produced the iron, where it went, and
   why the next trip was faster or a new person arrived. A reconnecting
   household can fetch current state and resume its feed. A replay of the
   archived inputs reaches the same final state with no live bot.

## The first population experiment

**Question:** With the same two workers, when does spending the first ore on
transport pay back in home-world supply? Run *Ship first* (ship every available
pair) against *Shed first* (build once, then ship). Hold the labour allocator,
initial skills, two-person roster, decision opportunities and rules fixed.
Disable recruitment for **both experiment policies**; otherwise an extra miner
confounds the transport question. Use matched pairs across, for example, 20
research seeds. Each arm gets a private copy of the same founding state; neither
policy sees its seed. Publish the seeds with the results. Include resource-poor
and long-trail cases within the charter bounds; record if a finite allotment
bottoms out.

Record time to first shipment, cumulative units shipped at 10 and 30 simulated
minutes, person-seconds walking/working/waiting, and the first event where one
policy overtakes the other (or “never before horizon”). Keep each pair's
differences, not just the mean. Export the run manifest, policy version, seed,
initial roster, ordered inputs, event chronicle and final-state digest alongside
the summary. Publish one surprising pair and its causal trace. To test a
**population** tweak next, vary the initial skill mix or number of workers while
holding policy and geography fixed. These are proposed experiments, not invented
results.

## After that first demo

| Increment                | Dependencies                                                                       | A convincing next demonstration                                                                                                                                                                                                              |
| ------------------------ | ---------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Cooperative public works | Shipment ledger, shared world state, travel modifiers                              | Some holds contribute timber to a crossing. Once the threshold is met, a new route opens for everyone, including holds that never contributed. Vary the mix of contributing policies; measure when the first non-contributor changes routes. |
| Deeper economy / castle  | Catalog + haul/build ledger                                                        | Two tech branches compete for the same skilled labour. A hold grows from house to workshop to a defensible keep; the chosen floor space or protected stores matter.                                                                          |
| Party PvM                | Person availability, group claims, deterministic encounter rules, replacement path | Commit two people to one expedition, resolve a loss, cancel the dead member's plan, recruit a replacement. Define and test the all-dead relief charter before enabling lethal outings.                                                       |
| Warned hold raids        | Buildings, stored/claimed stock, deadline ordering                                 | Two precommitted defence plans facing the same NPC raid lose different goods. Protected/reserved stock stays accounted for; both rosters survive.                                                                                            |
| Season two               | Cutoff/archival + fresh-world founding                                             | The old timeline stays readable, unfinished orders are closed, and both accounts enter a new seed with fresh people.                                                                                                                         |
| Genetic controllers      | Trustworthy observation/action contract, paired baseline, versioned fitness        | Evolve policies offline, evaluate on held-out lands against the simple bot, then let one play in the ordinary server as a client.                                                                                                            |

If the first two chronicles read identically except for their totals, change the
geography or the decision before adding more systems. The interesting
deliverable is a difference you can explain to someone watching.
