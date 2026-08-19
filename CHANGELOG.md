# Changelog

## 1.1.0

**Update the server plugin too.** The wire protocol changed, and a 1.1.0 client
will not save to a 1.0.0 server or plugin. The channel name was changed on
purpose so the mismatch is detected and logged rather than half-working.

### Fixed: writing several pages could kick you off the server and lose them

Vanilla caps a single serverbound packet at 32 767 bytes and *disconnects* the
sender when one is bigger. A drawing is up to 534 KiB, so past roughly six
drawn pages the save kicked you — and because the drawing is sent from the
book's save path, the text you had just typed went with it.

The drawing is now sent as a run of 16 KiB chunks and reassembled server side.
Nothing is applied until the final chunk arrives, so a half-sent drawing can
never overwrite a finished one.

Erasing a drawing completely now syncs as well; before, on a server, the empty
result was never sent and the old drawing came back.

### Green and yellow

Five inks now: red, black, blue, green and yellow, all mixable on one page.

This costs size. A pixel needs three bits instead of two, so a page goes from
3 648 to 5 472 bytes and a fully drawn book from 356 KiB to 534 KiB — 1.78×
the heaviest possible vanilla text book, up from 1.19×. The
[README](README.md#size-and-the-chunk-ban-question) has the re-measured
compression figures.

Books drawn with the old format are read and upgraded automatically, and keep
their colors. Nothing needs converting by hand.

### Copy and paste

`ⓒ` and `ⓟ` on the toolbar, or **Ctrl-C** and **Ctrl-V**, copy the whole
drawing on the page you are looking at and paste it onto another — in the same
book or a different one. The clipboard holds one page and lives only in memory.

Pasting replaces the target page and is undoable like any other edit. `ⓟ` dims
when there is nothing to paste. The shortcuts are only intercepted in draw
mode, so vanilla's text copy and paste still work while typing.

## 1.0.0

First stable release. No behaviour changes since 0.3.1 — this is the same mod,
declared finished after every path in it has been used in a real game:
singleplayer, a Fabric server, a Paper server, and alongside Scribble.

- **Now licensed under the [LGPL-3.0](LICENSE.md)** instead of MIT. What this
  changes in practice: modifications to this mod have to stay free software and
  publish their source, while anything that merely *depends* on it — including
  a closed-source mod, or a modpack — is unaffected. Both jars now ship the
  license texts inside them, as the license requires.
- The Paper plugin moves to 1.0.0 alongside the mod. No code changes; rebuild
  it so the version in `plugin.yml` matches the jar you upload.
- Documentation brought in line with what the mod actually does — the README
  still described the first prototype's single ink color and missing undo.

## 0.3.1

- Fixed: **Show drawings while writing** did nothing. The check let the editing
  screen through before it was ever consulted, so drawings always rendered.
- Brush sizes in settings are **sliders** now instead of click-to-cycle buttons.
- New debug option: **show item size**. With it on, the action bar shows how
  many bytes the item in your hand actually serializes to, and for a book how
  much of that is the drawing. It measures any item, so a drawn book can be
  compared against a maxed-out text book or a full shulker - every size claim
  this mod makes is checkable in game. Green under 8 KiB, yellow under 64 KiB,
  red past that.

## 0.3.0

Settings, and a lighter footprint.

- **In-game settings**, from the settings icon on the toolbar or **Ctrl-G** in any book
  screen: scale up the book GUI, show or hide the editing tools, default pen
  and eraser sizes, default ink, which side the toolbar sits on, and whether
  drawings stay visible while writing text. Saved to
  `config/drawinbooks.properties`, which is plain text and safe to edit by
  hand.
- The book GUI scale bump now applies to **Scribble's** screens as well.
- Toolbar side is configurable because Scribble puts its own controls to the
  left of the book, where the two would otherwise collide.

Performance, all of it in code that ran every frame or every tick:

- Drawing geometry is computed once and replayed until the drawing actually
  changes, instead of decoding 14 592 pixels per page per frame. A page nobody
  is drawing on now costs one integer comparison.
- Blank pages are detected by scanning bytes rather than decoding pixels, and
  each pixel in a run is decoded once instead of twice.
- After saving, the item is only rewritten when it doesn't already carry the
  drawing. The two-second retry window used to deep-copy the item's NBT and the
  blob every tick - up to ~28 MB of copying per save on a full book.
- Toolbar labels are rebuilt only when something they depend on changes,
  instead of allocating a handful of Components every frame.

## 0.2.1

- Fixed: with [Scribble](https://modrinth.com/mod/scribble) installed, none of
  this mod appeared at all — no toolbar, and existing drawings invisible.
  Scribble replaces the vanilla book screen with its own class, so the mixins
  here never ran. The toolbar and canvas now attach to its screens too, by
  reflection, without depending on Scribble in any way. Its two-page mode is
  supported.

## 0.2.0

**Drawings now save on servers.** Until now they only survived in singleplayer
and in creative — on a server in survival a drawing was wiped by the next
inventory sync. That was not a permissions problem: vanilla simply has no
packet that lets a survival player attach data to an item, so op made no
difference. There are now two server-side implementations, and installing
either one makes it work in survival for everyone, with no permissions:

- **Fabric server**: the same jar, installed server side
- **Paper server**: a plugin, in `paper/` — built separately

Neither trusts the client: the payload is length-capped by the codec, the blob
must match the fixed format exactly, the target must be a book the sender is
holding, and each player is rate limited.

Also in this release:

- Storage format changed to a single flat byte array at the Bukkit persistent
  data path inside `custom_data`, so the mod and the plugin read and write the
  same bytes in the same place. **Drawings made with 0.1.x will not show.**
- The mod now loads on servers as well as clients

## 0.1.1

- Fixed: pressing **Sign** straight after drawing lost the drawing, and it only
  survived if you pressed Done first. Vanilla calls its save the moment you
  press Sign — before the title is typed and before the book is converted — so
  the drawing is now committed on screen close as well, which covers the whole
  signing flow.

## 0.1.0

First release. Draw on book & quill pages, independently of the text layer.

- Drawing canvas over the vanilla page area, 114×128 pixels — one bitmap pixel
  per GUI pixel, so the grid is uniform and fills the page
- Pen and eraser with adjustable brush sizes (Ctrl/Alt-click the tool), three
  ink colors mixable on the same page, flood-fill and wipe (Shift-click),
  right mouse button always erases
- Undo, 7 steps deep (Ctrl-Z)
- Drawings persist on the item itself and survive signing; a signed book shows
  its drawing above the text when read
- Book screens open one GUI-scale step larger than the rest of the game
- Stored inside vanilla `minecraft:custom_data`, so the mod is optional:
  players without it simply see no drawing, and nothing unknown goes over the
  wire
- Strict decode validation: pages must be exactly 3 648 bytes and at most 100
  per book, or the whole drawing is rejected as absent — never partially
  parsed, never crashing
