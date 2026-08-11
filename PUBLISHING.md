# Publishing

Everything below is prepared locally; these are the steps that need your
accounts. Nothing here has been run for you.

## 1. Push to GitHub

The repo is initialised with commits on `main`, and the remote is already set
to <https://github.com/zobralini/DrawInBooks>. All that's left is:

```
git push -u origin main
```

This has to run on your machine — the credentials are yours, and were never
needed here.

The included workflow (`.github/workflows/build.yml`) builds and runs the tests
on every push, and uploads the jar as a build artifact.

## 2. Build the release jar

```
gradlew build
```

The file to upload is `build/libs/drawinbooks-0.1.0.jar` — **not** the
`-sources` one.

## 3. Create the Modrinth project

- Project type: **Mod**
- Slug: `draw-in-books` (this is what `fabric.mod.json` links to)
- Summary: *Draw on book and quill pages, in three colors, without touching
  the text.*
- License: **MIT**
- Client side: **required** · Server side: **unsupported**
- Categories: `utility`, `decoration`
- Loader: **Fabric** · Minecraft: **26.2**
- Icon: `src/main/resources/assets/drawinbooks/icon.png`

Suggested description body — the README covers all of it, but Modrinth
readers mostly want the first three sections: what it does, the controls
table, and the "optional mod" paragraph.

### Worth stating on the page

Two things are unusual about this mod and are worth being upfront about,
because they're what a reviewer would ask:

- **It is optional.** Drawings live in vanilla's `custom_data`, so a player
  without the mod sees an ordinary book and cannot be disconnected by it.
- **Multiplayer is limited.** On a vanilla server in survival, the drawing
  stays on your client — the vanilla book packet only carries text. It works
  in singleplayer, in creative, and on servers running the mod.

## 4. Version bumps

`mod_version` lives in `gradle.properties`. Tag releases as `v0.1.0` to match;
the workflow already triggers on `v*` tags.
