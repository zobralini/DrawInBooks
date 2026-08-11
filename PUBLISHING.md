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

## 2. Build the release jars

The mod:

```
gradlew build
```

Upload `build/libs/drawinbooks-0.2.0.jar` — **not** the `-sources` one. The
same jar works on both a client and a Fabric server.

The Paper plugin is a separate Gradle project, because it builds against the
Bukkit API instead of Minecraft. It has its own wrapper, so run it from inside
that folder (PowerShell has no `&&` — use two lines):

```
cd paper
.\gradlew build
```

That produces `paper/build/libs/drawinbooks-paper-0.2.0.jar`. Upload it as a
second file on the same Modrinth version, or as its own project — Modrinth
allows additional files per version, which is the simpler option.

## 3. Create the Modrinth project

Every field value and the full page body are in **[MODRINTH.md](MODRINTH.md)**,
written to be pasted straight in. Short version: name *Draw In Books*, slug
`draw-in-books`, MIT, client required / server optional, Fabric 26.2, upload
`drawinbooks-0.2.0.jar` as a **beta** with Fabric API as a required
dependency, and attach the Paper plugin jar as a second file.

### Prior art

[Scriboodle](https://modrinth.com/mod/scriboodle) already does drawing in
books. That's fine — but it's worth leading the page with what's actually
different here rather than with "draw in books", which is why the copy in
MODRINTH.md opens on the vanilla feel and on the mod being optional.

## 4. Version bumps

`mod_version` lives in `gradle.properties`. Tag releases as `v0.1.0` to match;
the workflow already triggers on `v*` tags.
