# Draw in Books

Fabric mod for Minecraft **26.2** that lets players draw on book & quill pages,
fully independent of the vanilla text layer. Designed to feel like something
that could have shipped in vanilla: no new items, no new blocks, no new
textures, no registry entries — two mixins, a small toolbar, and a fixed-size
bitmap that rides along on the book itself.

**The mod is optional.** Drawings live inside vanilla's `minecraft:custom_data`,
so a player without it sees an ordinary book, and no unknown component id ever
goes over the wire. Two players who both have it see the same drawing on the
same book.

Mostly client-side. The one exception is saving on a server: install the same
jar on a Fabric server, or the **Paper plugin** in [`paper/`](paper), and
drawing works in survival for everyone. Without one of those, a drawing made on
a server stays on the drawer's client — see [Persistence](#how-it-works).

## Building & running

Requires **Java 25** (JDK 25). From the project root:

```
./gradlew build        # builds the mod jar into build/libs/
./gradlew runClient    # launches a dev client
./gradlew test         # runs the format-validation unit tests
./gradlew genSources   # decompiled 26.2 sources for reference
```

> **Note on mappings:** the original spec asked for Yarn, but Yarn ended with
> 1.21.11 — Minecraft 26.x ships unobfuscated and Fabric dropped third-party
> mappings. This project uses the new non-remapping `net.fabricmc.fabric-loom`
> plugin (1.17-SNAPSHOT) with Mojang's own class names (e.g.
> `net.minecraft.client.gui.screens.inventory.BookEditScreen`,
> `net.minecraft.resources.Identifier`).

## How it works

**UX** — Opening a book & quill shows one new UI element: a strip of square
buttons hugging the left edge of the book. `✎` switches to drawing and `A`
switches back to typing; while drawing, a pen `✎` and an eraser `❌` appear
below it, each showing its current brush size, with the selected tool in
yellow. Clicking a tool selects it; held modifiers then act on it:

| Action | Result |
|---|---|
| Ctrl + click tool | brush one step bigger (pen ≤ 5, eraser ≤ 7) |
| Alt + click tool | brush one step smaller (≥ 1) |
| Shift + click pen | flood the whole page with ink |
| Shift + click eraser | wipe the whole page |
| Right mouse button | erase, whichever tool is selected |
| Middle mouse button | take the ink under the cursor as the pen color |
| `█` button | cycle the pen color: red → black → blue → green → yellow |
| `ⓒ` button, or Ctrl + C | copy the whole page you are looking at |
| `ⓟ` button, or Ctrl + V | stamp it onto this page, in this book or another |
| Shift + click `ⓟ` | paste over the page, replacing it completely |
| Ctrl + Z | undo the last stroke or whole-page action (7 deep) |

Colors are per pixel, so one page can hold all five: switching the pen color
only affects what you draw next, never what's already on the page. Undo
snapshots a page before each stroke and flips back to that page if the edit
happened elsewhere; the history is per session and is never stored on the item.

The clipboard holds one page, lives only in memory, and is shared by every
book screen — which is what lets a page be copied out of one book and pasted
into another. Pasting is an **overlay**: only the pixels carrying ink in the
copy are written, so what was already on the page shows through the gaps and
the clipboard works as a stamp — a border, a signature, a template. Shift-click
`ⓟ` to replace the page completely instead. Either way it is a normal undoable
edit. `ⓟ` dims when there is nothing to paste. Ctrl-C and Ctrl-V are only
intercepted in draw mode, so vanilla's own text copy and paste keep working
while typing.

The mode toggle is the one button with a background — a 20×40 sheet at
`assets/drawinbooks/textures/gui/draw_button.png`, normal frame on top and
hovered underneath. Everything below it is frameless, so the strip reads as one
button with a row of icons hanging off it. The symbols themselves are glyphs
from Minecraft's own font rather than part of the texture, because almost none
of them are static: the toggle alternates between two, the swatch is tinted
per ink, the tools carry a brush size that changes under the cursor, and `ⓟ`
dims when the clipboard is empty.

Brush size shows as a superscript on the tool's glyph (`✎¹`, `❌³`). While the
cursor is over a tool and a modifier is held, that superscript is replaced by
what the modifier would do — `⁺`, `⁻` or `■` — so the shortcuts are
discoverable without a tooltip.

Defaults are a 1×1 pen and a 3×3 eraser. While draw mode is on, the page is a
canvas only — clicks on it no longer move the text cursor and typing no longer
edits text (Escape still closes). The page background stays untouched, and
text and drawings remain independent layers.

Signed books are rendered by a second mixin on `BookViewScreen`, covering both
a book held in the hand and one standing in a lectern. It draws from
`ScreenEvents.afterExtract` — i.e. after everything else, so the drawing sits
*above* the page text exactly as it does while editing. (A widget would be
drawn before the screen paints its own text.)

Alongside the pages sits one clamped byte: the pen color the player last used,
so reopening a book resumes on it. Pixel colors themselves live in the bitmap,
three bits each.

### Settings

The settings icon on the toolbar - or **Ctrl-G** in any book screen, which still works
with the toolbar hidden - opens an in-game settings screen: GUI scale bump,
whether the tools show at all, default pen and eraser sizes, default ink, which
side the toolbar sits on, whether drawings stay visible while writing, and a
debug readout that puts the held item's real serialized size in the action bar
(any item, not just books - which is what makes the size comparisons in this
README checkable in game).

They live in `config/drawinbooks.properties`, deliberately plain text rather
than a config library: a handful of values doesn't justify a dependency, and a
player who manages to lock themselves out of the screen can fix it in a text
editor. Every value is clamped on read, so a hand-edited file can only ever
produce a working brush. Ctrl-G works even with the toolbar hidden, so turning
the tools off is never a one-way door.

Both book screens open **one GUI-scale step larger** than the rest of the
game, since a book at scale 3 is cramped to draw in while scale 4 makes every
other menu oversized. Only the window's live scale is touched — never the
saved option — so nothing can leak into the settings file. It is put back both
when the screen is removed and, as a safety net, on any client tick where no
book screen is open. The HUD isn't drawn while a screen is open, so nothing
else is affected — but note this does scale the book's own Done/Sign buttons
too, since it scales the whole screen.

**Data model** — the drawing rides along on the book `ItemStack` (never world
data, never a separate file) as a single flat byte array, stored inside
**vanilla's `minecraft:custom_data`** — deliberately *not* a data component
registered by this mod. That is what makes the mod optional: a vanilla server
stores and forwards `custom_data` without knowing what's in it, a player
without the mod just doesn't see the drawing, and nobody can be disconnected
by an unknown component id because there isn't one.

The exact path is `custom_data → PublicBukkitValues → "drawinbooks:pages"`,
which is where Bukkit's persistent data container writes. That isn't
incidental: a Paper plugin can only touch item data through the PDC, so
writing there from the Fabric side too means the mod and the plugin read and
write the same bytes in the same place, instead of two formats to keep in
sync. A flat byte array for the same reason — the PDC has no structured
types, so structure would have meant two encodings.

- 114×128 px, **3 bits per pixel** — blank plus five ink colors, so one page
  can mix colors → exactly **5 472 bytes per page**, always. The resolution
  equals the page text area in GUI pixels, so one bitmap pixel is one GUI pixel
  and the canvas fills the page with a perfectly uniform grid.
- max **100 pages** (vanilla's limit) → hard worst case **547 200 bytes**
  (~534 KiB) per item. See the size note below.
- no compression of any kind (no decompression-bomb surface)
- pixel values need no validation either: three bits leave two unused patterns,
  and `getColor` reports those as blank, so every possible byte array describes
  a drawable page
- **version 1** (2 bits per pixel, three colors) is still read and upgraded on
  load, so books drawn before green and yellow existed keep their drawings

### Size, and the "chunk ban" question

The exploit this worries about is real and well documented: fill a shulker box
with maximum-NBT books, drop it, and the packet the server has to send to
nearby clients gets big enough that encoding fails and everyone in range is
kicked ([Minecraft Wiki: Chunk ban](https://minecraft.wiki/w/Tutorial:Chunk_ban)).
It is a *size* problem, not a *which-component* problem — putting the data in
`custom_data` neither helps nor hurts.

**Vanilla's own ceiling.** A book & quill is capped at 100 pages × 1 024
characters, and NBT strings are modified UTF-8, so a character can cost up to
3 bytes (which is exactly why the exploit pastes high-codepoint characters):

    100 × 1024 × 3 =  307 200 B  ≈ 300 KiB per book

**This mod's ceiling**, from the format constants:

    100 × 5 472     =  547 200 B  ≈ 534 KiB per book   → 1.78× vanilla

So a fully drawn book is about 78 % heavier than the heaviest legitimate
vanilla book. Same order of magnitude, not a new class of risk — but the
margin narrowed when the fourth and fifth inks pushed the format from 2 bits
per pixel to 3. Per shulker (27 slots — neither books nor drawings stack once
their data differs):

| | per book | × 27 |
|---|---|---|
| vanilla text | 300 KiB | 7.9 MiB |
| + drawing | 534 KiB | 14.1 MiB |
| text *and* drawing | 834 KiB | 22.0 MiB |

For reference the protocol ceilings are 8 388 608 B raw / 2 097 152 B
compressed, so those last two rows cross a line vanilla text doesn't quite
reach.

**Where this mod is genuinely worse: effort.** Vanilla makes you paste 1 024
characters into each of 100 pages. Here, *a single pixel on a page costs the
full 5 472 bytes* — size doesn't depend on how much you drew. One dot per page
across 100 pages produces a maxed-out book. Reaching the ceiling is roughly
one click per page instead of one paste per page.

**Text and drawings stack.** A book can carry both, so the true worst case per
item is 307 200 + 547 200 = **854 400 B ≈ 834 KiB, 2.78× vanilla**. At that
size 10 books cross the 8 MiB raw ceiling — a third of a shulker — whereas 27
maxed vanilla text books (7.9 MiB) don't quite reach it.

**Compression is what actually decides it**, and measured with deflate:

| content | raw | compressed | ratio |
|---|---|---|---|
| vanilla text, one repeated character | 300 KiB | 326 B | 942× |
| vanilla text, random characters | 300 KiB | 207 KiB | 1.4× |
| drawing, one dot per page | 534 KiB | 851 B | 643× |
| drawing, 5 % of pixels | 534 KiB | 113 KiB | 4.7× |
| drawing, 60 % of pixels | 534 KiB | 460 KiB | 1.2× |
| drawing, solid fill | 534 KiB | 557 B | 982× |
| drawing, pure noise | 534 KiB | 535 KiB | 1.0× |
| random text + noise drawing | 834 KiB | 742 KiB | 1.1× |

Books needed to reach the 2 MiB compressed ceiling: ~6 430 with repeated
vanilla text, ~9.9 with random vanilla text, ~3.8 with noise drawings, **~2.8
with both**. So a bitmap is inherently closer to incompressible than prose is,
and normal drawings (sparse strokes) sit at 5–20× compression — nowhere near
the raw figure.

**What bounds it:** every page is exactly 5 472 bytes, pages are capped at 100,
and the decoder rejects anything that isn't exactly that. One book is at most
534 KiB *provably* — no input can make it bigger, whether it came from this
mod, a command, or hand-edited NBT. Note also that a modified client can stuff
junk into `custom_data` with or without this mod; the mod doesn't widen that
surface, and validation protects against *rendering* garbage, not against
*storing* it (a huge malformed blob still occupies disk before the decoder
sees it).

If 1.78× vanilla is too much, the levers are, in order: fewer colors (back to
2 bits per pixel → 3 648 B/page, 1.19× vanilla), 1 bit per pixel and one color
per book → 1 824 B/page ≈ 178 KiB (0.59× vanilla), a smaller canvas, or a
lower page cap for drawings. A sparse page format would help far more in
practice and not at all in the worst case, which is why it isn't the first
lever listed.

**Validation on decode** (`DrawingBlob.isValid` → `PageBitmaps.isValid`) is the
single point of defense for data from disk, network, `/give`, hand-edited
components or other mods: any page not exactly 5 472 bytes (or 3 648 for a
version 1 blob), or more than 100 pages, rejects the *entire* drawing — it
decodes as empty and renders blank.
No partial parsing, no clamping, no crash. (The codec deliberately falls back
to empty instead of returning a decode error, because a hard error inside an
item-component codec can make the whole ItemStack unreadable and destroy the
book on chunk load.)

**Lecterns** — `LecternScreen` extends the reading screen, so it gets the same
drawing layer. The difference is where the book comes from: a held book is
found on the player, while a lectern's is on its `LecternMenu`, which does
carry the `ItemStack`. That is matched through the `MenuAccess` interface
rather than the vanilla screen class, so it survives another mod replacing the
lectern screen — which Scribble does, and which is why lecterns worked in a
development client and not in a real one. Because a lectern's book can be swapped while the screen
stays open, the stack is re-checked each frame and re-decoded only when it is
genuinely a different one — a reference comparison per frame, not a parse.

**Rendering** — ink pixels are drawn as plain GUI fills (batched per
horizontal row-run) via the 26.2 `GuiGraphicsExtractor`. The spec suggested a
`NativeImage` dynamic texture; with 26.2's GPU-backend rewrite (Vulkan
migration) in flight, per-run fills are the API-stable choice for an MVP and
cost at most a few hundred quads per frame. Swapping in a dynamic texture
later only touches `DrawCanvasWidget`.

One bitmap pixel is exactly one GUI pixel. That is why the bitmap resolution
was set to the text area's own size: stretching a smaller bitmap across the
page makes cells fractionally wide (64 px over 114 → 1.78), so the grid and the
cursor highlight flip between 1 and 2 px as the mouse moves, and centering a
smaller bitmap wastes page area. Matching the two exactly is the only mapping
that is both uniform and full-page.

**Input** — the canvas polls the GLFW mouse button state each frame rather
than listening for click events, because vanilla `BookEditScreen` handles
clicks on the page area itself (text cursor placement) and consumes them
before child widgets see them. Polling sidesteps dispatch ordering, gives
continuous painting while dragging, and joins consecutive frames with a
Bresenham line so fast strokes don't leave gaps. A stroke may only *begin*
inside the canvas, and the widget never consumes mouse events, so text mode
behaves exactly like vanilla. Left button uses the selected tool; right
button always erases.

**Persistence** — committed both when vanilla saves (`saveChanges`) and when
the screen closes. Both are needed: pressing `Sign` calls `saveChanges`
immediately, *before* you have typed a title and before the book is converted,
so a save that only happened there would be finished and forgotten by the time
the signed book actually appears. Saving again on close covers the whole
signing flow, and matches vanilla, which also saves book text on close.

Signing is the awkward case in general: vanilla builds a *new* `written_book`
and drops empty trailing pages. So at save time any page that has a drawing
but no text gets a single space written into its text page, and the drawing is
re-applied for ~2 s of client ticks afterwards so the signed copy inherits it.

That retry is deliberately narrow, because **a drawing must never land on the
wrong book** — two identical-looking books are still separate items. It is
locked to the inventory slot the book was edited in, and only accepts either
the very same `ItemStack` instance or a `written_book` that replaced it in
that slot (exactly what signing does). Any other book — a duplicate, a
same-titled copy, whatever the player selects a moment later — is not a match.
Being a pure client mod:

| Environment | Result |
|---|---|
| Server running the mod or the Paper plugin | Works in survival, no permissions needed. The client sends the drawing in chunks on the `drawinbooks:draw2` channel; the server reassembles it, validates it and stores it on the book. Both sides must be 1.1.0 or newer. |
| Singleplayer / LAN host | Written straight through to the integrated server's copy; saves with the world. Uninstall → ordinary book, data untouched; reinstall → drawing reappears. |
| Vanilla server, creative | Sent via the creative set-slot packet, which vanilla accepts from creative players with full item data. |
| **Vanilla server, survival** | **Cannot work**, so the tools are not offered. No vanilla packet lets a survival player attach data to an item — the book packet carries text only. This is not a permissions problem: op changes nothing, because vanilla has no such channel at all. See below. |

Trading, chests and shulkers follow from the data being on the item: two
players who both have the mod see the same drawing on the same book.

### Scribble compatibility

[Scribble](https://modrinth.com/mod/scribble) doesn't extend the vanilla book
screen — it *replaces* it with `ScribbleBookEditScreen`. With it installed the
mixins here never run, so before this was handled the drawing layer was simply
absent: no toolbar, and existing drawings invisible.

`ScribbleCompat` attaches the same toolbar and canvas to Scribble's screens
through Fabric's generic screen events, reading its layout by reflection —
two public fields (`currentPage`, `pagesToShow`) and two public methods
(`getBackgroundX/Y`). So this mod neither compiles against nor depends on
Scribble: if it's absent none of that code runs, and if it changes its
internals drawing quietly stops working on its screens instead of crashing.

Its page area is the same 114×128 as vanilla, so the canvas needs no separate
geometry, and its two-page mode is handled by adding one canvas per visible
page. One gap worth knowing: the space-reservation that keeps drawing-only
pages alive through signing needs the screen's page list, which Scribble owns —
on its screens, put a character on a page you only drew on.

### Server side

`DrawingSyncReceiver` (Fabric) and `paper/` (Paper plugin) are two
implementations of the same three lines of logic: receive a blob, validate it,
write it to the book in the sender's hand. Neither renders anything or sends
anything back — the client already knows what it drew.

**The drawing arrives in chunks**, and that is not an optimisation. Vanilla
caps a serverbound custom payload at 32 767 bytes and *disconnects* the sender
when a packet exceeds it — so a drawing past about six pages used to kick the
player, and because the send happens on the book screen's save path, it took
the unsent text with it. The blob is now cut into 16 KiB pieces (34 at most)
that the server reassembles; nothing is applied until the final chunk lands, so
a half-sent drawing can never overwrite a whole one.

The channel is `drawinbooks:draw2` — the `2` exists so that a version mismatch
is harmless rather than dangerous. An older server never announces this
channel, so the client sees `canSend` return false and logs that the drawing
cannot be stored, instead of sending something the other side would misread.

Nothing from a client is trusted. The codec caps each chunk before a byte is
buffered, chunks must arrive in order and every chunk but the last must be
full, a transfer may not exceed the format's maximum, half-finished transfers
are dropped after 10 s, `DrawingBlob.isValid` rejects anything that isn't the
exact fixed format, the target must be a book the sender is actually holding,
and each player is rate limited to one accepted sync per 500 ms. A player can
hold at most one partial drawing in server memory. The plugin duplicates the
validation constants rather than sharing them, because it must compile against
the Bukkit API alone — the cost is two places to change, which is why the
format is deliberately trivial.

**The tools hide themselves where they would not work.** A server announces
every plugin-message channel it listens on when you join, so
`ClientPlayNetworking.canSend` answers "does the other side have this mod or
its plugin" before anything is sent — and because the channel name carries the
protocol version, it also answers "a compatible one". Drawing is therefore
offered in exactly three places: singleplayer or hosting a LAN world, a server
with the mod or the plugin, and creative on any server (where the creative
set-slot packet carries the data instead). Anywhere else the toolbar is absent
and the canvas is read-only — existing drawings still show, they just cannot be
edited.

Silence would be indistinguishable from a broken mod, so the reason is printed
once per server, in chat rather than the action bar: the HUD isn't drawn while
a screen is open, and this is discovered exactly when a book screen opens.
Turning **Hide tools where they can't save** off in the settings restores the
old behaviour, for anyone whose server keeps item data some other way.

The client only sends when the server has announced the channel
(`ClientPlayNetworking.canSend`), so a plain vanilla server is never sent
anything it would only reject.

## Source layout

```
src/main/java/com/drawinbooks/
  DrawInBooks.java                    mod init (registers the sync packet)
  component/PageBitmaps.java          pure-Java format constants + validation + pixel ops
  component/DrawingBlob.java          the one wire/storage format, and its validation
  component/BookDrawingStorage.java   read/write inside vanilla custom_data (Bukkit PDC path)
  net/DrawingSyncPayload.java         client → server packet
  net/DrawingSyncReceiver.java        server side: validate and store
src/client/java/com/drawinbooks/client/
  DrawInBooksClient.java              client entrypoint
  mixin/BookEditScreenMixin.java      toolbar + canvas + input blocking + save hook
  mixin/BookViewScreenMixin.java      renders drawings when reading a signed book
  draw/DrawingSession.java            in-memory working bitmaps, tool & mode state, Bresenham strokes
  draw/DrawCanvasWidget.java          rendering + polled pen/eraser input over the page area
  draw/BookLayout.java                page-area geometry & bitmap ↔ screen mapping
  draw/BookScreenScale.java           +1 GUI scale while a book screen is open
  draw/DrawingPersistence.java        component write-back (SP write-through, creative packet, sign retry)
  draw/Tool.java                      PEN / ERASER and their size limits
  draw/DrawToolbar.java               the toolbar, shared by both screen kinds
  draw/IconButton.java                frameless glyph button
  draw/CanvasRenderer.java            cached run geometry
  compat/ScribbleCompat.java          attaches the same UI to Scribble's screens
  config/DrawConfig.java              settings file
  config/DrawConfigScreen.java        settings screen
  debug/ItemSizeOverlay.java          optional item-size readout
src/test/java/.../PageBitmapsTest.java   JUnit tests for the bitmap contract
src/test/java/.../DrawingBlobTest.java   JUnit tests for the blob contract
paper/                                   standalone Gradle project: the Paper plugin
```

## Verified in-game

Everything below has been checked on a real client, and on a server, before
1.0:

- draw → Done → reopen → drawing persists
- draw → **Sign** directly, without pressing Done first → drawing survives the
  conversion to a written book
- disable the mod → the book shows text only, nothing is lost → re-enable →
  drawing is back
- a malformed blob (wrong page size, or more than 100 pages) → renders blank,
  one warning in the log, no crash
- a drawn book passed between two modded clients → both see the same drawing
- signed books with identical drawings stack; differing drawings don't
- survival on a server, with the mod or the Paper plugin installed → saves;
  without either → the drawing is client-only and says so in the log

## Known limits

- **Five ink colors**, not more. A sixth still fits in three bits, but a
  seventh would mean 4 bits per pixel and another third on top of every book —
  see [Size](#size-and-the-chunk-ban-question).
- **No compression.** Pages are stored flat, so the worst case is provable.
  Sparse pages and delta packets are the obvious next step and are deliberately
  not in 1.0.

## License

[LGPL-3.0-only](LICENSE.md). Use it, ship it in a modpack, or depend on it from
a closed-source mod freely; if you modify *this* mod, your changes are LGPL too
and the source has to be available. Full texts in [`COPYING`](COPYING) and
[`COPYING.LESSER`](COPYING.LESSER).
