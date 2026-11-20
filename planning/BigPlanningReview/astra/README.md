# Ironwood Wilds — Astra's design proposal

**Build a world you can run twice.** Give two people one axe, one pick and one delivery request. Run the same valley twice: one household sends its first timber home; the other turns it into a packing shed. Who gets a third pair of hands first? When does the shed pay for itself? If you can watch both households and explain the difference from their journals, you already have something worth showing.

My recommendation is **`samwilds`**, a headless C11 simulation, with one authoritative server and a small offline experiment runner. The clock advances by deadlines: a miner who starts at second 45 and takes three minutes finishes at second 225, whether ten people are watching or nobody is. The browser, a scripted bot and eventually a genetic controller all see the world through the same household view and issue the same orders. Ironwood Wilds is the public name; the `samwilds*` names below are proposed Ptah targets.

## Reading map

| If you want to know… | Read |
|---|---|
| What life in this world is like, what persists, and what you can lose | [IronwoodWildsGameplay.md](IronwoodWildsGameplay.md) |
| What you could actually show after a few Fridays | [FirstExpedition.md](FirstExpedition.md) — the playable slice, worked timeline, experiment and build order |
| Where each piece lives in Ptah, and what design patterns look like in C | [ServerArchitecture.md](ServerArchitecture.md) |
| What happens between “queue mine” and ore appearing, including retries and downtime | [CommandsAndTime.md](CommandsAndTime.md) |
| How a seed becomes playable land, and how you add content yourself | [ProceduralWorld.md](ProceduralWorld.md) |
| What players, bot authors and spectators would actually see | [WebInterface.md](WebInterface.md) |
| How accounts, saves, deadlines, schema changes and season resets fit together | [AccountsAndStorage.md](AccountsAndStorage.md) |

For a short read, start with **Gameplay → First Expedition**. The other documents explain how to make the promises in those two hold up.

## Calls I'd make up front

| Decision | Why it matters | When to revisit |
|---|---|---|
| A bounded, connected graph of named places | In text, “the scar is a 45-second walk uphill” is more useful than 10,000 empty tiles. Geography has an economic consequence immediately. | When two distinct routes to a site would support a real player choice. |
| One writer per season; jump to the next due event | Two people can finish at the same instant without spending the same ore twice. Empty queues cost no simulation work. | Measure deadline lag in a busy season before splitting a world across writers. |
| HTTP orders and short polling first | Fits today's `samhttp`. A player can close the page and a bot can play with an ordinary HTTP client. | Add push if polling observably hurts latency or server load. |
| PostgreSQL current state plus an ordered record of inputs and outcomes | You can recover an interrupted delivery **and** explain it later. | Add separate analytics or a streaming service when the journal no longer serves its readers. |
| Private founding claims on shared geography | Another household can help complete a public relief effort, but cannot strip your only ore seam or raid you. | Try voluntary shared works once two-household experiments are interesting. |

**The first design review is a choice, not a feature count:** would you rather send the first two timber home, or keep them? The [two runs](FirstExpedition.md) give that question a price in minutes, people and marks. If neither run makes you curious, change the economics before building a castle system.
