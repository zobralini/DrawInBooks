# Minecraft 26.2 Fabric Mod: Book & Quill Drawing

## Goal

Build a Fabric **client mod** for Minecraft 26.2 that lets players draw on
book and quill pages (`writable_book` / `written_book`), fully independent
of the existing vanilla text-writing feature. The design must stay as
close to vanilla as possible — this is intended as a simple, low-risk
feature pitch to Mojang, so prioritize minimalism over feature richness at
every decision point.

## Target environment

- Minecraft: 26.2
- Mod loader: Fabric (Fabric Loader + Fabric API)
- Mappings: Yarn, latest available for 26.2
- Language: Java
- Scope: client-side mod only. No server-side sync mod, no Bukkit/Paper
  plugin. Multiplayer visibility comes purely from the data living on the
  item itself (see Persistence section).

## Core UX

1. Extend/replace the vanilla book-editing screen (`BookEditScreen` or its
   26.2 equivalent — verify the actual class name from decompiled source)
   to add a small toolbar on the side with three tools:
   - **Pointer/mouse** — idle/no-draw state, default when entering the screen
   - **Pen** — draws ink pixels
   - **Eraser** — clears pixels back to blank
2. Add a mode toggle between **Text mode** (existing vanilla text editing,
   untouched) and **Draw mode** (new canvas). These are two fully
   independent layers — writing text never touches the drawing data, and
   vice versa.
3. The vanilla page background/texture must remain completely unmodified
   and vanilla-looking. The ONLY new visible UI element is the side
   toolbar. The drawing itself renders directly onto the existing page
   area, at the same position/size vanilla text occupies.
4. Reference texture: `assets/minecraft/textures/gui/book.png` (256×256
   source texture). Pull the exact pixel offsets/dimensions of the
   writable page area from the decompiled `BookEditScreen` class for
   26.2 — don't guess, verify from source, since these constants shift
   between versions.

## Data model (the most important part — must be exact)

Register a custom `DataComponentType` (e.g. `mymod:page_drawings`)
attached directly to the book `ItemStack` — NOT world/tile data, NOT a
separate save file. This is what makes persistence and trading work
correctly, and it's also the part that needs the strictest constraints
because of a known Minecraft issue: oversized book data can bloat chunk
save/load and cause lag. The design below must not make that problem
easier to trigger.

- **Fixed resolution per page**: 64×80 pixels, 1-bit monochrome (ink or
  blank — no color, no grayscale, no alpha blending). Keeps the feature
  simple, vanilla-feeling, and cheap to store.
- **Fixed size per page**: exactly 640 bytes (64×80÷8), always — never
  variable-length, never compressed with a variable ratio.
- **Max pages**: 100, matching vanilla's existing max page count for
  books. Do not allow more.
- **Worst-case size per item must be a hard, provable constant**
  (~64 KB for a maxed-out book), regardless of how the component was
  produced — through this mod's UI, through `/give`, through hand-edited
  NBT/component data, or through another mod.
- Applies identically whether the book is `writable_book` (unsigned,
  stack size 1) or `written_book` (signed, stack size 16).

### Validation on decode — hard requirement, not optional

When decoding the component (on item load from disk, from network, from
a command, from anywhere):

- Verify every page's byte array is **exactly** 640 bytes and the page
  count is ≤100. If anything doesn't match exactly, reject the ENTIRE
  drawing component for that item — treat it as absent (render blank).
  Do not partially parse, do not clamp/truncate and continue, do not
  crash.
- Do not implement variable-length compression (e.g. RLE) for this data
  unless a hard cap on the pre-decompression (expanded) size is enforced
  before allocating — to prevent decompression-bomb style abuse.
- This validation must be the single point of defense — assume any tool
  other than this mod's own UI (another mod, a command, hand-edited NBT)
  could produce malformed input, and design the codec accordingly.

## Rendering

- Maintain one dynamic texture (`NativeImage` + GPU upload) per open book
  screen instance, representing the current page's bitmap.
- Re-upload the texture only when the bitmap actually changes (on
  stroke), not every frame.
- Draw the texture as a textured quad exactly over the vanilla page's
  text area.

## Input handling (MVP)

- On mouse drag within the canvas bounds while in Draw mode: convert
  screen coordinates to bitmap pixel coordinates, and set that pixel to
  "ink" (pen) or "blank" (eraser) depending on the active tool.
- Nearest-pixel-per-event is sufficient for v1. Line interpolation
  between mouse-move ticks (e.g. Bresenham) is a nice-to-have — don't
  build it until the basic version is done and tested.

## Explicit non-goals for MVP (do not build these)

- No color picker or grayscale — monochrome ink only
- No adjustable brush size
- No undo/redo
- No server-side sync mod or Bukkit/Paper plugin — pure client mod
- No new items or blocks
- No compression beyond the fixed 640-byte-per-page format above

## Persistence & multiplayer behavior (expected outcome — verify with tests)

- If the mod is uninstalled, the client no longer understands the custom
  component — the book renders blank (or text-only). The component data
  itself is NOT deleted, just not rendered.
- If the mod is reinstalled, the existing component data on the item is
  read again and the drawing reappears — this should fall out naturally
  from component-based storage, no special "recovery" logic needed.
- Because the drawing lives on the `ItemStack`, it travels with the item
  through trades, chests, shulker boxes, lecterns, etc. If two players
  both have the mod, one can hand the other a drawn book and the drawing
  is visible to both.
- Stacking: `written_book` items only stack when their component data
  (including the drawing) is byte-for-byte identical. Vanilla stack
  mechanics store component data once per stack, not once per copy, so
  storage should not multiply with stack size (max 16) — confirm this in
  testing rather than assuming it.

## Suggested build order

1. **Project skeleton** — Fabric mod template for 26.2, Fabric API
   dependency, confirm it builds and loads in a dev client.
2. **Data component + codec** — implement `mymod:page_drawings`, the
   fixed-size encode/decode, and the strict validation above. Write unit
   tests for validation (valid data accepted; oversized/malformed data
   rejected without crashing).
3. **Custom screen + toolbar** — copy/extend the vanilla book screen, add
   the three-button toolbar and the text/draw mode toggle. UI shell only,
   no drawing logic yet.
4. **Input + in-memory bitmap editing** — wire mouse drag to bitmap pixel
   writes, visible only in the current session (no persistence yet).
5. **Rendering** — dynamic texture upload and draw-over-vanilla-page.
6. **Wire bitmap to the data component** — save strokes into the
   ItemStack's component; load existing component data when the screen
   opens.
7. **Test checklist**:
   - Save world → disable mod → confirm book shows blank/text-only →
     re-enable mod → confirm drawing restored
   - Manually inject oversized/malformed component data (via
     `/data merge` or an NBT/component editor) → confirm the mod rejects
     it gracefully, no crash, no partial render
   - Trade a drawn book between two players (both with the mod loaded) →
     confirm the drawing is visible to the recipient
   - Stack 16 identical written books with identical drawings → confirm
     no performance or data-duplication issue

Work through these in order, and give a short summary after each phase
before moving to the next — don't build ahead of what's been verified
working.
