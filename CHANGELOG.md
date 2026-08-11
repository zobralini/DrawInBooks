# Changelog

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
