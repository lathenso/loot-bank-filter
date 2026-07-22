# Loot Bank Filter

[![Support me on Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/lathenso)

Tracks the loot you receive per monster/boss during your session and lets you
filter your bank to show **only the items you looted** — either everything, or
one source at a time.

## Features

**Loot tracking**
- Tracks all loot per source (NPC kills, PvP kills, and event loot such as
  Barrows chests, raid rewards, clue caskets and pickpocketing), including
  loot that never touches the ground (e.g. Doom of Mokhaiotl corpse harvests
  and caught implings).
- Sidebar panel similar to the built-in Loot Tracker: one collapsible box per
  source with kill count, item icons, quantities and GE value, most recent
  kills on top. Hover an item for its GE and High Alchemy value (per item and
  total); untradeables show the High Alchemy value only.
- Optional on-screen overlay with your session totals.

**Bank filtering**
- A `Loot: ON/OFF` button inside the bank toggles filtering by all tracked
  loot.
- Right-click any source box in the panel → **Filter in bank** to show only
  that monster's drops. Works even if the bank is closed — the filter applies
  automatically the next time you open it.
- The filtered view is a flat grid without tab separators and survives
  Withdraw-X, deposits and tab rebuilds until you toggle it off.
- Optional **Show looted quantity** setting: while filtering, bank stacks
  display the amount you looted instead of the amount banked (display only —
  withdrawing always uses your real bank quantities).

**Per-source ignore lists**
- Right-click any item in a source's drop grid → **Remove from filter** to hide
  that item from that specific source's bank filter. The item stays tracked and
  visible in the panel, but its tile is greyed out.
- Right-click a greyed tile → **Add back to filter** to re-enable it.
- Ignored items are subtracted from both the per-source filter and the
  filter-all view. An item only disappears from the filter-all if every source
  that dropped it has ignored it.
- Ignore lists are keyed per source and persist across sessions. Clearing a
  session leaves them intact; **Delete ALL sessions** clears them too.

**Sessions**
- Sessions auto-save on a configurable interval (1–30 minutes, default 1) and
  when the client closes (can be disabled). Save, load, or delete sessions by
  date/time from the panel header's right-click menu.
- Switch the panel between **Current session** and **All sessions (total)**;
  filtering follows whichever view is active.
- Sessions that age out of the saved-session cap are folded into a permanent
  **lifetime archive** first, so the all-time view never loses loot (a chat
  message tells you when this happens). Manually deleted sessions are removed
  for real and are not archived.

## Configuration

| Option | Description |
| --- | --- |
| Minimum item value | Ignore drops below this per-unit GE price |
| Show session loot overlay | Toggle the on-screen totals panel |
| Overlay max items | How many item lines the overlay shows |
| Auto-save session | Save automatically on the interval below and on client close |
| Auto-save interval (min) | How often the session auto-saves, in minutes (1–30, default 1) |
| Max saved sessions | How many saved sessions to keep (oldest fold into the lifetime archive) |
| Show looted quantity | Display looted amounts on bank stacks while filtering |
| Debug logging | Diagnostics in the client log (troubleshooting only) |

## Known limitations

- The bank filter uses the same layout mechanism as Bank Tags tag tabs. Using
  the filter **at the same time as an open Bank Tags tag tab** is not
  supported — close the tag tab first (normal Bank Tags usage is otherwise
  unaffected).
- While the filter is active, the vanilla bank tabs are temporarily pinned to
  the "view all" tab; they return to normal as soon as the filter is off.
- "Show looted quantity" is cosmetic: Withdraw-All and other withdraw options
  always act on your real bank quantities.
