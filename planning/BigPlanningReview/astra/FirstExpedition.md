# First expedition — “The third chair”

**The five-minute demo:** two browser tabs, two people, one empty chair at the landing. Send one person to the coppice and one to the scar. Spend the first timber on a packing shed. Disconnect. When you return, the ore is home, the shed is standing, and both people are waiting for you. Ship the ore and invite a third person. Then show what happens if you send the timber home instead.

## The entire fixture

`Alder-01` is a proposed hand-checkable test valley, not a claim about the output of an existing generator. The generated version uses the same place and action types. Numbers below are design values, in simulation seconds; the live slice uses one second per real second.

| Thing | Rule in this fixture |
|---|---|
| Household | Mara and Ivo start at **Landing**, rank 1, empty packs and store, zero marks, with basic tools. Each pack holds 4 units. The landing store has room for the entire fixture. |
| Geography | **Landing ↔ Alder Coppice** costs 30 s each way; **Landing ↔ Slate Scar** costs 45 s. Each site starts with 8 harvestable units on the household's claim. Only these two edges are needed. |
| Work | `fell`: 60 s for 2 timber and 1 woodcutting XP. `mine`: 180 s for 2 ore and 1 mining XP. Two mining XP give that individual rank 2; their **next** mining job takes 150 s. Resources go into their pack. Returning to Landing unloads it as part of arrival. |
| Technology and hold | First cumulative deposit of 2 timber unlocks **Timber Framing** once. Building a **packing shed** at Landing takes 2 timber and 60 s. It unlocks the `packed_ore` offer. |
| Home-world offers | `timber_writ`: 2 timber → 3 marks, **once per household per season**. `loose_ore`: 2 ore → 2 marks. `packed_ore`: 2 ore → 3 marks. All take a person at Landing 30 s. A packed load earns the premium because the receiving quartermaster can accept the whole consignment. |
| Recruit | An explicit household order spends 3 marks and immediately adds one unskilled, idle adult at Landing. The starting hold has space. This order does not need a free worker. |

## Run A: keep the timber

| At | Orders or consequences |
|---:|---|
| 0:00 | Mara queues **travel to Scar → mine → return to Landing**. Ivo queues **travel to Coppice → fell → return to Landing**. Only the two journeys have started. |
| 0:30 / 0:45 | Ivo arrives and starts felling; Mara arrives and starts mining. Neither has produced goods yet. |
| 1:30 | Ivo has 2 timber in his pack and starts back. The landing store is still empty. |
| 2:00 | Ivo unloads; Timber Framing unlocks. Order him to build. The 2 timber are now reserved. You can close both tabs. |
| 3:00 | The shed is complete. Ivo has no orders and waits. |
| 3:45 / 4:30 | Mara earns 1 mining XP and puts 2 ore in her pack; at 4:30 she reaches Landing and unloads. Nobody ships it automatically. |
| 4:30 / 5:00 | Reconnect and order Ivo to ship `packed_ore`. At 5:00 the household has **one shed, 3 marks and 2 people**. Issue `household.recruit`: **one shed, 0 marks and 3 people**. A retry of that same order must still leave 3 people. |

**Run B: send the timber.** Keep the seed, people and orders identical up to Ivo's return at 2:00. At that fork, have Ivo fulfil `timber_writ` instead. At **2:30** there are already 3 marks and you can recruit; there is no shed. At 4:30 Mara can ship the ore loose for 2 marks, or the extra worker may already have brought back timber for a shed. Shipping timber wins the race to three people by 2½ minutes; it does not settle which policy supplies home better over half an hour. That's the experiment.

## How to tell whether the slice works

1. **The history explains the goods.** At 1:30, 2 timber are in a pack and zero at home. At 2:00 there is one unlock and one reservation. At 5:00 the packed shipment appears once in the marks ledger. Link the recruit entry back to that credit.
2. **You can walk away.** Stop the process at 2:01 and resume at 4:30. With the same orders, its normalized journal and household state match the continuously running case at 4:30. Opening a thousand views, or none, does not change them.
3. **One set of rules serves everyone.** Run the same timed orders through the lab and the HTTP service with a test clock; compare their ordered domain outcomes. A browser double-submit does not duplicate a shipment. Another account cannot inspect a private pack or command its owner.
4. **You can vary one thing.** Run both policies for 30 simulated minutes on the same set of valley seeds. Record first-recruit time, total marks earned, idle person-minutes and final hold. Compare within each seed, including runs that never recruited. Repeat with only the ore trail length changed. If the result surprises you, link the first different decision or completion. These are proposed measurements, not balance claims.

## A Friday-sized build order

These are stopping points, not deadlines. Each Friday should end with something you can show even if the following one is lost.

| Session | Build after the previous gate | Friday's evidence |
|---|---|---|
| Nov 20 | Headless `samwilds` + `samwilds-lab`, a hand-drawn graph and an injected clock. Travel, mine, return; one running action and a queue. | Advance straight to 224, then 225: ore appears exactly once, in the pack. No sleeping for three minutes to test it. |
| Nov 27 | Add the second person, timber, the two uses for it, one tech, one building and recruitment. Print a household journal. | The two runs above diverge at 2:00. This is the first publishable story, even as plain text. |
| Dec 4 | Put those definitions into one versioned content pack. Generate a bounded valley, validate it, and run the same policies on a seed panel. | Show a different map, the same rules, and a comparison with a replayable seed. |
| Dec 11 | Persist one season with PostgreSQL; add command receipts, due work and recovery. Use two provisioned test households. | Interrupt the shed build, resume, retry the last order; one shed and one debit. Archive one season and open another without losing either account. |
| Dec 18 | Connect one real HTTP bot and the text browser view. Expose the household journal; rehearse the walk-away demo on a private instance. | A human and a bot can each do the run over the wire, and a spectator can read an opted-in chronicle. |
| Dec 25, **if wanted** | Buffer or record a short comparison. | A shareable run and one good question for the next season. |

If a session spills, keep the offline two-policy demo and move the public launch date. Before opening sign-ups, finish the [live identity and restore path](AccountsAndStorage.md). After the slice, add **one** source of pressure at a time: a shared relief project, then hold storage and a nonlethal raid, then an opt-in monster expedition with replacement recruitment. Add genetic policies once a hand-written one has set a baseline worth beating.
