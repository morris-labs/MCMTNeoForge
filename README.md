# MCMTNeoForge

A fork of [NeoForge](https://github.com/neoforged/NeoForge) for Minecraft 1.21.1 that adds
multi-core parallel tick processing to dedicated servers. World, entity, block-entity, and
chunk ticks are dispatched across a worker pool instead of running sequentially on the main
thread.

**Status:** Validated on a 455-mod ATM10 server. Throughput at 32 workers is roughly 3.5x a
single-threaded baseline under representative load. One known crash - an AE2 multiblock race
under cross-dimension chunk unload - is root-caused and in progress.

---

## Install

**Requirements:** NeoForge 21.1.x. The installer replaces your existing NeoForge installation.

1. Stop your server.
2. Back up your world and your `libraries/` folder.
3. Download the latest `neoforge-21.1.X-mcmt-installer.jar` from [Releases](../../releases).
4. In your server directory, run the installer:

   ```
   java -jar neoforge-21.1.X-mcmt-installer.jar --installServer
   ```

   Replace `21.1.X` with the version you downloaded.

5. Start the server. MCMT is on by default.

To roll back, restore your original NeoForge jar and `libraries/` backup.

### Configuration

MCMT adds `config/mcmt-server.toml` on first launch. The key options are:

| Option | Default | Description |
|---|---|---|
| `disabled` | `false` | Set to `true` to disable all parallelism. The server runs as stock NeoForge. |
| `workerCount` | `-1` (auto) | Number of worker threads. `-1` uses the available processor count minus one. |
| `chunkLockModded` | `true` | Chunk-locks all modded block entities and entities by default. Turn off only for mods you've verified are thread-safe. |
| `vanillaDefault` | `FREE` | How vanilla block entities and entities that aren't on any explicit list run. `FREE` lets them run in parallel; `CHUNK_LOCK` serializes them by chunk. |

The `/mcmt` command exposes runtime stats and config reload without a server restart.

---

## How it works

MCMT adds four parallel dispatch hooks into NeoForge's tick path:

| Hook | What it parallelizes |
|---|---|
| H1 | Per-dimension level ticks - each loaded dimension ticks on its own worker. |
| H2 | Entity ticks within a level - entities tick in parallel across workers. |
| H3 | Block-entity ticks within a level - same as H2 for block entities. |
| H4 | Chunk and environment ticks - `ServerChunkCache.tickChunks` dispatch. |

A `ForkJoinPool` backed by `MCMTWorkerThread` instances runs the work. A `Phaser` barrier
at the end of each level tick ensures all workers finish before the server thread continues.

### Thread-safety model

Vanilla Minecraft is single-threaded. Making ticks run in parallel requires identifying and
serializing every code path that reaches shared mutable state. MCMT handles this at three
levels:

1. **`SerDesRegistry` filters** answer the question "how should this class tick?" for every
   ticking object. Filters are consulted in priority order; the first opinion wins:
   - `PistonFilter`, `HopperFilter`, `ModdedHopperFilter`, `ItemEntityFilter`,
     `ExperienceOrbFilter`, `EntityFilter` - classes known to reach outside themselves.
   - `ConfigFilter` - the server owner's allow and deny lists.
   - `AutoFilter` - classes that have thrown an exception while running in parallel, demoted
     reactively to chunk-locked.
   - `VanillaFilter` - all other `net.minecraft` code, governed by `vanillaDefault`.
   - `DefaultFilter` - modded code of unknown thread-safety; chunk-locked by default.

2. **Serialization pools** decide how a class that can't run free is constrained:
   - `POS_LOCK` - a block-position lock covering a block and its six neighbors. Used for
     hoppers, item entities, and experience orbs, which interact only with adjacent blocks.
   - `CHUNK_LOCK` (radius 1) - serializes a tick with its immediate neighboring chunks.
     Used for most block entities and entities.
   - `SINGLE` - whole-server serialization. Used for the handful of vanilla classes that
     modify global state.

3. **Mixins in `net.neoforged.neoforge.mcmt.modmixins`** fix races in third-party mod code
   that can't be reached by the `SerDesRegistry` filter chain. Each mixin targets one
   confirmed-unsafe class in one mod.

### Mod compatibility

Every modded block entity and entity is chunk-locked by default (`chunkLockModded = true`),
which covers H2 and H3 for mods with no further work. H1 (per-dimension dispatch) is a
different hazard: code that runs as part of a whole dimension's tick body - including
chunk-load and chunk-unload callbacks - can reach shared state that no per-block-entity
filter intercepts. The `modmixins` package addresses found cases of this.

The following mods have confirmed H1 fixes shipped in this fork:

| Mod | Race | Fix |
|---|---|---|
| Oritech | Dimension-shared energy cache | `ThreadLocal` per dimension |
| FTB Chunks | Force-load registry | `synchronized` compound op |
| EnderStorage / DimStorage | Cross-dimension storage map | Concurrent map |
| Integrated Dynamics (family) | Network graph registry | Read-write lock |
| Draconic Evolution | Multiblock cluster state | `volatile` publish-once |
| Waystones | Waystone index | `synchronized` compound op |
| Compact Machines | Room registry | Concurrent map |

If a mod causes a crash that `chunkLockModded` doesn't prevent, add its class to
`blockEntityBlackList` or `entityBlackList` in `mcmt-server.toml` using a wildcard pattern
(`com.example.mod.**`) to route it through `SINGLE` serialization.

---

## Build from source

**Requirements:** Java 21. The `env.sh` file at the workspace root sets `JAVA_HOME` and
`GRADLE_USER_HOME` to a self-contained toolchain; source it before running Gradle.

```
source ../env.sh
./gradlew setup          # decompile Minecraft and apply patches
./gradlew :neoforge:compileJava
```

The following table lists the common build tasks:

| Task | Purpose |
|---|---|
| `./gradlew setup` | Decompile Minecraft and apply patches into `projects/neoforge/src`. Run once after a fresh checkout, or when `patches/` changes. |
| `./gradlew :neoforge:compileJava` | Compile the patched sources and MCMT code. |
| `./gradlew :neoforge:genPatches` | Regenerate `patches/` from the edited `projects/neoforge/src` sources. Run before committing a source change. |
| `./gradlew applyAllFormatting` | Auto-format all sources. Run before `genPatches`. |
| `./gradlew :tests:runGameTestServer` | Run the game test suite. |
| `./gradlew :neoforge:runServer` | Start a development server on an empty world. |

### Source layout

- `projects/neoforge/src/main/java` - decompiled Minecraft with NeoForge's patches applied.
  Edit here. Regenerated from `patches/`; not committed.
- `patches/` - the committed patch files. Regenerated by `genPatches`.
- `src/main/java/net/neoforged/neoforge/mcmt/` - all MCMT code:
  - `mcmt/` - coordination: `MCMT`, `TickBatch`, `MCMTBootstrap`
  - `mcmt/config/` - `ModConfigSpec` toggles
  - `mcmt/parallel/` - thread pool, `MCMTWorkerThread`, chunk locks
  - `mcmt/serdes/` - `SerDesRegistry`, filters, pools
  - `mcmt/modmixins/` - one mixin per confirmed-unsafe third-party class
  - `mcmt/commands/` - `/mcmt` runtime commands

---

## Known issues

**AE2 `MBCalculator` cross-dimension race (crash).** Applied Energistics 2's multiblock
calculator uses a bare unsynchronized static field (`modificationInProgress`) as a
single-thread reentrancy guard. When two dimensions unload chunks at the same time under H1,
two workers can hit this field concurrently, corrupting AE2's `EnergyService` grid state. A
fix that correctly wraps the modification operation with a real lock while preserving AE2's
own recursion-detection semantics is in progress. Until it ships, servers with AE2 Quantum
Bridges can encounter this crash when cross-dimension chunk unloading coincides.

**`runAtm10smoke` dev-run classpath gap.** The `:neoforge:runAtm10smoke` Gradle task may fail
to boot with `NoClassDefFoundError: MixinExtrasBootstrap` when mods that touch MixinExtras
early (such as Ars Nouveau) are loaded. This is a ModDevGradle dev-run classpath limitation
and does not affect a real installer-produced server boot. Production installs are unaffected.

---

## License

LGPL-2.1-only. For details, see the `README-LICENSE.md` file and the `LICENSE-LGPLv2.1` file
at the root of this repository. NeoForge's upstream license terms apply to all code outside
the `src/main/java/net/neoforged/neoforge/mcmt/` package.
