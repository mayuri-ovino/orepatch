# Ore Patch — multi-version builds

This archive contains three separate, independent Gradle projects for
Ore Patch — one per Minecraft/Fabric target. Fabric's toolchain changed
enough across these versions (obfuscated vs. unobfuscated Minecraft)
that a single jar can't span all three; each folder builds its own jar.

| Folder | Minecraft | Notes |
|---|---|---|
| `orepatch-1.21.11/` | 1.21.11 | Last **obfuscated** release. Needs Java 21, Loom 1.14+, official Mojang mappings (or Yarn — see its README) and `modImplementation`/`remapJar`. |
| `orepatch-26.1.2/` | 26.1.2 | First **unobfuscated** release. Needs Java 25, Loom 1.15+, plain `implementation` (no remapping). Unchanged from what you gave me. |
| `orepatch-26.2/` | 26.2 | Also unobfuscated. Same toolchain shape as 26.1.2, just newer Loom/Fabric API versions. |

The Java source (`src/main/java`) is identical across all three — 26.2's
own changes don't touch anything this mod uses, and 1.21.11 uses the same
Mojang class/method names as 26.1.2 under official mappings. Only the
Gradle config, `fabric.mod.json`, and the mixin's remap settings differ.

Each folder has its own README with build/install steps for that version.
