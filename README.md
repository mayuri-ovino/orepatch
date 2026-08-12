# Ore Patch

A Fabric mod for Minecraft 26.2 that hides ores hidden behind only a
single block of air (Anti Anti-Xray)

discord.gg/HAxWvejMUm

## What counts as "exposed"

For every ore block — coal, copper, iron, gold, lapis, redstone, emerald,
diamond (both the stone and deepslate variants), Ancient Debris, and the
two Nether ores (Nether Gold Ore, Nether Quartz Ore) — the mod checks all
six neighboring blocks. If a neighbor is air, and *that* block's neighbor
on the same side is *also* air or empty space, the ore is sitting behind
a wall that's only one block thick. Both the single air block and the
ore get replaced with a patch block.

The patch block depends on the dimension: stone (or deepslate below Y=0)
in the Overworld, netherrack in the Nether — so patched spots blend into
the surrounding terrain instead of leaving an obvious overworld-stone
scar underground.

This deliberately only looks at plain air — water, lava, torches, and
other non-solid blocks are left alone, so flooded caves and decorations
near ore aren't disturbed.

## How it runs

The mod hooks `ServerChunkEvents.CHUNK_LOAD` to queue up newly-loaded
chunks, then drains one chunk from that queue at the end of every server
tick (`ServerTickEvents.END_LEVEL_TICK`). This spreads the scanning work
out so it never causes a noticeable lag spike, even on a chunk-heavy
server. Existing worlds get scanned gradually as players explore (or as
chunks load on server start), without needing a full world rescan up
front.

## Toggling it from chat

| Command | Effect |
|---|---|
| `/orepatch` or `/orepatch status` | Shows whether it's on/off and the current scan rate |
| `/orepatch toggle` | Flips it on ⇄ off |
| `/orepatch on` | Turns scanning on |
| `/orepatch off` | Turns scanning off |
| `/orepatch set <true\|false>` | Same as on/off, but as a single command (handy for command blocks / datapacks) |
| `/orepatch rate <1-64>` | Sets how many chunks get scanned per server tick |

While it's off, no new chunks get queued and the tick handler does nothing
— so toggling it off has zero ongoing cost. Turning it back on resumes
queueing chunks as they load; it does **not** retroactively scan chunks
that loaded while it was off, since those simply weren't queued.

## Project layout

```
orepatch/
├── build.gradle
├── gradle.properties
├── settings.gradle
└── src/main/
    ├── java/com/example/orepatch/
    │   ├── OrePatchMod.java      <- entrypoint, chunk queue, tick draining
    │   ├── OreGapScanner.java    <- the actual gap-detection + patch logic
    │   ├── OrePatchConfig.java   <- runtime on/off + scan-rate state
    │   └── OrePatchCommand.java  <- the /orepatch chat command
    └── resources/
        └── fabric.mod.json
```

## Building

This project targets **Minecraft 26.2**, which — like 26.1.2 — is one of
the unobfuscated releases of the game. The toolchain is unchanged from
the 26.1.2 build of this mod:

- There's no Yarn mappings — code is written directly against Mojang's
  own (public) class names, e.g. `ServerLevel` instead of `ServerWorld`,
  `Level` instead of `World`, `Identifier` instead of `ResourceLocation`.
- The buildscript uses the plain `jar`/`implementation` Gradle tasks
  instead of `remapJar`/`modImplementation`, since there's no remapping
  step on this version.
- You'll need **Java 25** and **Fabric Loom 1.17+** (Gradle 9.5.1+).

None of 26.2's own changes (the `BlockIds`/`ItemIds` split, or the
`Gui`/`Hud` reorganization) touch anything this mod uses, so the Java
source here is identical to the 26.1.2 build — only `gradle.properties`
and `fabric.mod.json` were bumped to 26.2's versions.

To build:

1. Install JDK 25.
2. From the project root, run:
   ```
   ./gradlew build
   ```
   (If you don't have a `gradlew` script yet, open the project in
   IntelliJ IDEA 2025.3+ and let it generate one via Gradle, or run
   `gradle wrapper --gradle-version latest` once you have any Gradle
   installed.)
3. The built mod jar will be in `build/libs/`.

## Installing

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.3 or newer for
   Minecraft 26.2.
2. Download/build **Fabric API** for 26.2 and drop it in your `mods`
   folder — this mod depends on it.
3. Drop the built `orepatch-*.jar` into the same `mods` folder.
4. This mod is server-side logic only (it edits the actual blocks in the
   world), so on a multiplayer server you only need it installed on the
   server, not on clients. Singleplayer needs it installed normally since
   the client also runs an internal server.

## Tuning it

Everything you'd want to tweak lives in `OreGapScanner.java`:

- `FILL_STONE` / `FILL_DEEPSLATE` / `FILL_NETHERRACK` — the patch blocks
  for Overworld-above-Y0, Overworld-below-Y0, and Nether respectively.
  Change any of these (e.g. to blackstone in the Nether) if you'd rather
  patch with something else.
- `fillFor(level, y)` — picks which of the three blocks above to use.
  Add another `if` branch here if you want a fourth dimension-specific
  fill block (e.g. for the End, or a modded dimension).
- `ORE_TAGS` — the list of vanilla ore tags being checked. Add or remove
  entries (e.g. add a modded ore's tag) to control what counts as "ore"
  for this mod. Ancient Debris and the two Nether ores aren't covered by
  any vanilla tag, so they're checked directly in `isOre()` instead.

And in `OrePatchConfig.java`:

- `DEFAULT_CHUNKS_PER_TICK` — the starting scan rate before anyone runs
  `/orepatch rate`. The live value is changed at runtime via that command
  instead of needing a rebuild.

Note that `enabled` and the scan rate reset to their defaults on server
restart, since this config is in-memory only and isn't written to disk.
