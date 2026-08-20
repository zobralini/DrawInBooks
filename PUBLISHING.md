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

Upload `build/libs/drawinbooks-1.3.1.jar` — **not** the `-sources` one. The
same jar works on both a client and a Fabric server.

The Paper plugin is a separate Gradle project, because it builds against the
Bukkit API instead of Minecraft. It has its own wrapper, so run it from inside
that folder (PowerShell has no `&&` — use two lines):

```
cd paper
.\gradlew build
```

That produces `paper/build/libs/drawinbooks-paper-1.3.1.jar`. Upload it as a
second file on the same Modrinth version, or as its own project — Modrinth
allows additional files per version, which is the simpler option.

**When the plugin actually needs rebuilding:** only when something under
`src/main/java/com/drawinbooks/component` or `net` changes — that is the shared
storage format and wire protocol. Everything under `client/` is the client's
business alone, and an older plugin jar keeps working. The version numbers are
kept in lockstep purely so it's obvious which pair belongs together.

**1.1.0 was one of those releases.** The storage format gained two colors and
the wire protocol was cut into chunks, so the plugin had to be rebuilt and
redeployed alongside the mod. A 1.1.0-or-newer client will not save to a 1.0.x
plugin — the channel name changed on purpose, so the mismatch is detected and
logged instead of silently corrupting anything.

**Nothing since is.** 1.2.0, 1.3.0 and 1.3.1 touch neither the format nor the
protocol, so a 1.1.0 plugin still works. Rebuild it anyway to keep the pair
obviously matched.

## 3. Create the Modrinth project

Every field value and the full page body are in **[MODRINTH.md](MODRINTH.md)**,
written to be pasted straight in. Short version: name *Draw In Books*, slug
`draw-in-books`, **LGPL-3.0-only**, client required / server optional, Fabric
26.2, upload `drawinbooks-1.3.1.jar` as a **release** with Fabric API as a
required dependency, and attach the Paper plugin jar as a second file.

On Modrinth, set the Fabric API dependency to **0.157.0 or newer** — that is
what the mod is built against, and an older one crashes.

If the project already exists on Modrinth from an earlier upload, the license
field has to be changed there by hand — Modrinth does not read it from the jar.

### Prior art

[Scriboodle](https://modrinth.com/mod/scriboodle) already does drawing in
books. That's fine — but it's worth leading the page with what's actually
different here rather than with "draw in books", which is why the copy in
MODRINTH.md opens on the vanilla feel and on the mod being optional.

## 4. Version bumps

`mod_version` lives in `gradle.properties`, and the plugin's in
`paper/build.gradle` — keep the two the same. Tag releases to match (`v1.0.0`);
the workflow already triggers on `v*` tags.

## 5. License

The project is **LGPL-3.0-only**. The full texts are in `COPYING` (GPLv3) and
`COPYING.LESSER` (the LGPL's additional permissions), with a plain-language
summary in `LICENSE.md`. Both jars are built with all three inside, which is
what the license actually requires when you distribute a binary.
