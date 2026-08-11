# Draw in Books

Fabric **client mod** for Minecraft **26.2** that lets players draw on book &
quill pages, fully independent of the vanilla text layer. Designed as a
minimal, low-risk feature pitch: no new items, no new textures, no registry
entries, no packets — two mixins and a fixed-size bitmap that rides along on
the book itself.

**The mod is optional.** Drawings live inside vanilla's `minecraft:custom_data`,
so a player without it sees an ordinary book, and nothing unknown ever goes
over the wire. Two players who both have it see the same drawing on the same
book.

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
buttons hugging the left edge of the book, labelled with glyphs from
Minecraft's own font (no texture asset). `✎` switches to drawing and `A`
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
| `█` button | cycle the pen color: red → black → blue |
| Ctrl + Z | undo the last stroke or whole-page action (7 deep) |

Colors are per pixel, so one page can hold all three: switching the pen color
only affects what you draw next, never what's already on the page. Undo
snapshots a page before each stroke and flips back to that page if the edit
happened elsewhere; the history is per session and is never stored on the item.

Brush size shows as a superscript on the tool's glyph (`✎¹`, `❌³`). While the
cursor is over a tool and a modifier is held, that superscript is replaced by
what the modifier would do — `⁺`, `⁻` or `■` — so the shortcuts are
discoverable without a tooltip.

Defaults are a 1×1 pen and a 3×3 eraser. While draw mode is on, the page is a
canvas only — clicks on it no longer move the text cursor and typing no longer
edits text (Escape still closes). The page background stays untouched, and
text and drawings remain independent layers.

Signed books are rendered by a second mixin on `BookViewScreen`, which reads
the drawing off the book in the player's hand and draws it from
`ScreenEvents.afterExtract` — i.e. after everything else, so the drawing sits
*above* the page text exactly as it does while editing. (A widget would be
drawn before the screen paints its own text.)

The ink color is stored as one clamped index alongside the pages rather than
in the bitmap: the pixels stay 1 bit each, so a color costs one byte per item
instead of doubling the page data.

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

- 114×128 px, **2 bits per pixel** — blank plus three ink colors, so one page
  can mix colors → exactly **3 648 bytes per page**, always. The resolution
  equals the page text area in GUI pixels, so one bitmap pixel is one GUI pixel
  and the canvas fills the page with a perfectly uniform grid.
- max **100 pages** (vanilla's limit) → hard worst case **364 800 bytes**
  (~356 KB) per item. See the size note below.
- no compression of any kind (no decompression-bomb surface)
- pixel values need no validation: two bits cannot encode an invalid color

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

    100 × 3 648     =  364 800 B  ≈ 356 KiB per book   → 1.19× vanilla

So a drawn book is about 19 % heavier than the heaviest legitimate vanilla
book. Same order of magnitude, not a new class of risk. Per shulker (27
slots — neither books nor drawings stack once their data differs):

| | per book | × 27 |
|---|---|---|
| vanilla text | 300 KiB | 7.9 MiB |
| + drawing | 356 KiB | 9.4 MiB |

For reference the protocol ceilings are 8 388 608 B raw / 2 097 152 B
compressed, so that last row is the one that crosses a line vanilla text
doesn't quite reach.

**Where this mod is genuinely worse: effort.** Vanilla makes you paste 1 024
characters into each of 100 pages. Here, *a single pixel on a page costs the
full 3 648 bytes* — size doesn't depend on how much you drew. One dot per page
across 100 pages produces a maxed-out book. Reaching the ceiling is roughly
one click per page instead of one paste per page.

**Text and drawings stack.** A book can carry both, so the true worst case per
item is 307 200 + 364 800 = **672 000 B ≈ 656 KiB, 2.19× vanilla**. At that
size 13 books cross the 8 MiB raw ceiling — half a shulker — whereas 27 maxed
vanilla text books (7.9 MiB) don't quite reach it.

**Compression is what actually decides it**, and measured with deflate:

| content | raw | compressed | ratio |
|---|---|---|---|
| vanilla text, one repeated character | 300 KiB | 325 B | 945× |
| vanilla text, random characters | 300 KiB | 235 KiB | 1.3× |
| drawing, one dot per page | 356 KiB | 676 B | 540× |
| drawing, 5 % of pixels | 356 KiB | 90 KiB | 4.0× |
| drawing, 60 % of pixels | 356 KiB | 308 KiB | 1.2× |
| drawing, solid fill | 356 KiB | 375 B | 973× |
| drawing, pure noise | 356 KiB | 356 KiB | 1.0× |
| random text + noise drawing | 656 KiB | 592 KiB | 1.1× |

Books needed to reach the 2 MiB compressed ceiling: ~6 450 with repeated
vanilla text, ~8.7 with random vanilla text, ~5.7 with noise drawings, **~3.5
with both**. So a bitmap is inherently closer to incompressible than prose is,
and normal drawings (sparse strokes) sit at 4–20× compression — nowhere near
the raw figure.

**What bounds it:** every page is exactly 3 648 bytes, pages are capped at 100,
and the decoder rejects anything that isn't exactly that. One book is at most
356 KiB *provably* — no input can make it bigger, whether it came from this
mod, a command, or hand-edited NBT. Note also that a modified client can stuff
junk into `custom_data` with or without this mod; the mod doesn't widen that
surface, and validation protects against *rendering* garbage, not against
*storing* it (a huge malformed blob still occupies disk before the decoder
sees it).

If 1.19× vanilla is too much, the levers are, in order: 1 bit per pixel and
one color per book → 1 824 B/page ≈ 178 KiB (0.59× vanilla), or a smaller
canvas (64×80 at 2 bits ≈ 128 KiB), or a lower page cap for drawings.

**Validation on decode** (`PageDrawings.CODEC` → `PageBitmaps.isValid`) is the
single point of defense for data from disk, network, `/give`, hand-edited
components or other mods: any page not exactly 3 648 bytes, or more than 100
pages, rejects the *entire* drawing — it decodes as empty and renders blank.
No partial parsing, no clamping, no crash. (The codec deliberately falls back
to empty instead of returning a decode error, because a hard error inside an
item-component codec can make the whole ItemStack unreadable and destroy the
book on chunk load.)

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
| Server running the mod or the Paper plugin | Works in survival, no permissions needed. The client sends the drawing on the `drawinbooks:draw` channel; the server validates it and stores it on the book. |
| Singleplayer / LAN host | Written straight through to the integrated server's copy; saves with the world. Uninstall → ordinary book, data untouched; reinstall → drawing reappears. |
| Vanilla server, creative | Sent via the creative set-slot packet, which vanilla accepts from creative players with full item data. |
| **Vanilla server, survival** | **Cannot work.** No vanilla packet lets a survival player attach data to an item — the book packet carries text only. The drawing exists on your client until the next inventory sync overwrites it, and the mod says so once in the log. This is not a permissions problem: op changes nothing, because vanilla has no such channel at all. |

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

Nothing from a client is trusted. The payload codec caps the length before a
byte is buffered, `DrawingBlob.isValid` rejects anything that isn't the exact
fixed format, the target must be a book the sender is actually holding, and
each player is rate limited to one accepted sync per 500 ms. The plugin
duplicates the validation constants rather than sharing them, because it must
compile against the Bukkit API alone — the cost is two places to change, which
is why the format is deliberately trivial.

The client only sends when the server has announced the channel
(`ClientPlayNetworking.canSend`), so a plain vanilla server is never sent
anything it would only reject.

## Source layout

```
src/main/java/com/drawinbooks/
  DrawInBooks.java                    mod init (registers the component)
  component/PageBitmaps.java          pure-Java format constants + validation + pixel ops
  component/DrawingBlob.java          the one wire/storage format, and its validation
  component/BookDrawingStorage.java   read/write inside vanilla custom_data (Bukkit PDC path)
  net/DrawingSyncPayload.java         client → server packet
  net/DrawingSyncReceiver.java        server side: validate and store
src/client/java/com/drawinbooks/client/
  DrawInBooksClient.java              client entrypoint (empty)
  mixin/BookEditScreenMixin.java      toolbar + canvas + input blocking + save hook
  mixin/BookViewScreenMixin.java      renders drawings when reading a signed book
  draw/DrawingSession.java            in-memory working bitmaps, tool & mode state, Bresenham strokes
  draw/DrawCanvasWidget.java          rendering + polled pen/eraser input over the page area
  draw/BookLayout.java                page-area geometry & bitmap ↔ screen mapping
  draw/BookScreenScale.java           +1 GUI scale while a book screen is open
  draw/DrawingPersistence.java        component write-back (SP write-through, creative packet, sign retry)
  draw/Tool.java                      PEN (1x1) / ERASER (3x3)
src/test/java/.../PageBitmapsTest.java   JUnit tests for the bitmap contract
src/test/java/.../DrawingBlobTest.java   JUnit tests for the blob contract
paper/                                   standalone Gradle project: the Paper plugin
```

## Things to verify in-game (not yet verified — no MC runtime here)

1. `./gradlew build` and `runClient` — first compile may surface small 26.2
   naming drift; everything risky is isolated:
   - Page-area constants in `BookLayout` (36/32/114/128 from long-stable
     vanilla layout) — if the canvas is misaligned, fix the constants against
     `genSources`' `BookEditScreen`.
   - `BookEditScreen` shadow/injection targets: `currentPage`, `init`,
     `saveChanges` (verified against mods compiling for 26.1.2/26.2).
2. The spec's test checklist:
   - draw → Done → reopen → drawing persists (singleplayer)
   - save world → disable mod → book shows text only → re-enable → drawing restored
   - `/give @p writable_book[drawinbooks:page_drawings=[[B;...]]]` with wrong
     sizes (≠640 bytes) or >100 pages → renders blank, warning in log, no crash
   - trade a drawn book between two modded clients → drawing visible to both
   - stack 16 identical signed books → they stack; differing drawings → they don't
3. Signing, specifically: the space-reservation runs at `saveChanges` HEAD.
   If vanilla syncs the on-screen text box into `pages` *after* that point,
   the page you were viewing when you pressed Sign could still lose its
   space. Test both "draw on page 2, go back to page 1, Sign" and "draw on
   page 2 and Sign from page 2"; if only the latter fails, the fix is to also
   push the space through the screen's own page-content update.

## Explicit non-goals (MVP)

No multi-color drawings (that would need 2 bits per pixel and double the
worst-case item size — the single ink color is free), no adjustable brush
sizes beyond the fixed pen/eraser, no undo/redo, no server-side companion mod,
no new items/blocks, no compression. Books in lecterns don't show drawings
yet: that screen has no access to the ItemStack, so the stack would have to be
passed down from the lectern block entity.
