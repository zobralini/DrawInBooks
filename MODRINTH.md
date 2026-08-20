# Modrinth page — ready to paste

## Project settings

| Field | Value |
|---|---|
| Name | **Draw In Books** |
| Slug / URL | `draw-in-books` |
| Project type | Mod |
| Client side | **Required** |
| Server side | **Optional** |
| License | **LGPL-3.0-only** (pick it from Modrinth's list; SPDX id `LGPL-3.0-only`) |
| Categories | Utility, Decoration |
| Icon | `src/main/resources/assets/drawinbooks/icon.png` |
| Source | `https://github.com/zobralini/DrawInBooks` |
| Issues | `https://github.com/zobralini/DrawInBooks/issues` |

## Summary (the one-line description)

> Draw on book and quill pages in five colors, without touching the text. Pure
> vanilla feel, and optional — players without the mod just see a normal book.

## Version upload

| Field | Value |
|---|---|
| File | `build/libs/drawinbooks-1.3.0.jar` (not `-sources`) |
| Additional file | `paper/build/libs/drawinbooks-paper-1.3.0.jar` |
| Version number | `1.3.0` |
| Version name | `1.3.0` |
| Release channel | **Release** |
| Loaders | Fabric |
| Game versions | 26.2 |
| Dependencies | Fabric API — **required** |

Release channel, not beta: every path in the mod has now been used in a real
game — singleplayer, a Fabric server, a Paper server, and alongside Scribble.

Version changelog — paste the `1.3.0` section from `CHANGELOG.md`.

---

## Page body (paste below the summary)

# Draw In Books

Adds a drawing layer to book and quill, sitting alongside the text you can
already write. Open a book, click ✎, and draw on the page. That's the whole
feature.

The design goal was to feel like something that could have shipped in vanilla:
no new items, no new blocks, no new textures. One small toolbar appears next to
the book, and nothing else about the game changes.

## Drawing

- **Pen** and **eraser**, each with its own adjustable brush size
- **Five ink colors** — red, black, blue, green and yellow — mixable on the
  same page
- **Copy and paste** a whole page, into another page or another book — it
  stamps over what's already there rather than wiping it
- **Undo**, 7 steps deep
- Drawings live on the book itself, so they travel with it through chests,
  trades and shulker boxes, and they survive **signing** — a finished book
  shows its drawing when read, in your hands or standing in a **lectern**

| Action | Result |
|---|---|
| Left click / drag | draw with the selected tool |
| Right click | erase, whichever tool is selected |
| Middle click | pick the ink under the cursor |
| Ctrl + click a tool | brush one step bigger |
| Alt + click a tool | brush one step smaller |
| Shift + click the pen | fill the whole page |
| Shift + click the eraser | wipe the whole page |
| Ctrl + Z | undo |
| █ button | switch pen color |
| ⓒ button, or Ctrl + C | copy this page |
| ⓟ button, or Ctrl + V | stamp it onto this page, over what's there |
| Shift + click ⓟ | paste over the page, replacing it |
| ◎ button, or Ctrl + G | settings |

While drawing, the page is a canvas only — clicks don't move the text cursor
and typing doesn't edit the text. Switch back with **A** and the book behaves
exactly like vanilla again.

## Settings

In-game, from the `◎` on the toolbar or **Ctrl-G** in any book screen:

- **Scale up book GUI** — books open one GUI-scale step larger than the rest of
  the game, because a book at scale 3 is cramped to draw in while scale 4 makes
  every other menu oversized. Only the live scale is touched, never your saved
  option.
- **Show editing tools** — hide the toolbar entirely; drawings still show.
  Ctrl-G still opens settings, so this isn't a one-way door.
- **Show drawings while writing** — whether drawings stay visible in text mode.
- **Toolbar side**, **default ink**, **default pen and eraser sizes**.
- **Debug: show item size** — puts the held item's real serialized size in the
  action bar, for any item. Handy for comparing a drawn book against a
  text-filled one, or for checking the numbers below yourself.

## Works with Scribble

[Scribble](https://modrinth.com/mod/scribble) replaces the book screen with its
own, so mods that hook the vanilla one usually vanish when it's installed. This
one attaches to Scribble's screens too, including its two-page view, without
depending on it in any way.

## The mod is optional

Drawings are stored inside vanilla's `minecraft:custom_data`, not in a
component this mod registers. That has a specific consequence worth stating
plainly:

- A player **without** the mod sees an ordinary book. They cannot be
  disconnected by an unknown component, because there isn't one.
- Two players who **both** have it see the same drawing on the same book —
  the data travels with the item.
- A vanilla server stores and forwards it without knowing what it is.

## Servers

Drawings save on a server **if the server has the mod too** — the same jar
works server side, and there's a **plugin** attached to this version for
servers that don't run Fabric. It uses nothing but the Bukkit API, so it runs
on Bukkit, Spigot, Paper and the Paper forks alike. Install either one and
drawing works in survival for everyone, with no permissions to hand out.

**Update both together.** 1.1.0 changed how the drawing reaches the server, so
a 1.1.0-or-newer client will not save to a 1.0.x server or plugin. It says so in
the log rather than failing quietly, but the fix is to update both sides.

Without one of them, on a plain vanilla server in survival, a drawing stays on
your client and disappears on the next inventory sync. That's not something a
mod can fix from the client: vanilla has no packet that lets a survival player
attach data to an item, so being op makes no difference either. Singleplayer
and creative work regardless.

## About size

Books with too much data are a real problem in Minecraft — that's the basis of
the chunk ban exploit. So the format is fixed and bounded rather than
open-ended: every page is exactly 5 472 bytes, a book holds at most 100 pages,
and anything that isn't exactly that is rejected on load as a whole rather
than partially parsed. One book is provably at most ~534 KiB, about 1.78× the
heaviest possible vanilla text book.

There's a full breakdown, including measured compression figures, in the
[README](https://github.com/zobralini/DrawInBooks#size-and-the-chunk-ban-question).

## Requirements

Minecraft 26.2 · Fabric Loader 0.19.3+ · Fabric API · Java 25

Free software under the **LGPL-3.0** — put it in a modpack, or build on it,
without asking. [Source on GitHub](https://github.com/zobralini/DrawInBooks).
