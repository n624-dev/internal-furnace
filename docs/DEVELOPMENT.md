# Development

[日本語](DEVELOPMENT.ja.md) · [Home](../README.md)

## Build

The release build runs Gradle 8.1.1 / Java17 in a digest-pinned Docker image. On Linux with Docker, Bash, curl, Python 3.11+ and coreutils:

```bash
bash build.sh
```

The script downloads only the exact M&S 6.4.7 and LoE 2.1.11 compile inputs from official CurseForge endpoints, verifies SHA-256 and keeps them in ignored `.deps/`. If automatic downloads fail, place those exact JARs in `.deps/`; mismatched files are rejected. Third-party JARs are not committed or published in our Release.

The build container mounts only this checkout, uses two CPUs / 3 GiB and does not receive a Docker socket, credentials or a Minecraft world. Allow internet access for Gradle/Forge dependencies. Run builds on a development machine.

Output: `build/libs/internal-furnace-<mod_version>.jar`. `mod_version` lives in [gradle.properties](../gradle.properties). The reobfuscated JAR includes MIT metadata and LICENSE, with no test driver or shaded MOD dependencies.

For a native development build, install Java17 and Python 3.11+:

```bash
bash scripts/fetch-dependencies.sh
./gradlew --no-daemon clean build
bash verify.sh
```

Windows can use `gradlew.bat` after placing the verified inputs in `.deps/`. The wrapper distribution and wrapper JAR are SHA-256 pinned. Different Java17 compiler revisions can emit different bytecode, so native hashes may differ from the pinned-container Release. Both endpoints must install the identical Release JAR.

## Tests

```bash
bash test-core.sh
python3 scripts/check-project.py
./gradlew check
```

`test-core.sh` compiles the actual core/editor/drag classes with Java17 compatibility, `-Xlint:all -Werror`, then executes deterministic and seeded tests. Gradle `check` includes both CoreTests and EditorTests and compiles the runtime driver. `verify.sh` checks JAR structure and dependencies; it is not a connection test.

## Actual Minecraft integration

```bash
./gradlew runtimeTestJar
```

This builds a separate `*-runtime-tests.jar`. Use it **only in a disposable world with disposable players**; it overwrites inventories, XP, M&S levels and furnace data. It never belongs in the released MOD. The server and test clients require `-Dinternalfurnace.disposableTest=true` when the driver is installed.

Install Forge and the exact supported MODs on a dedicated server and actual graphical clients. Never substitute an integrated server for dedicated-server acceptance. Commands require permission level 2:

- `/furnacetest suite`: real purchases, menu handling, recipes and automation assertions.
- `/furnacetest readonly`: requires another connected player named `FurnaceObserver`; attempts read-only admin-menu operations.
- `/furnacetest prepare`: creates paused fixture state containing 39 items. Wait at least one tick, then `/furnacetest snapshot` saves a complete compressed NBT snapshot in the test server directory.
- `/furnacetest verify`: compares the complete state after reconnect, normal restart, dimension travel or keepInventory=true death/respawn. Wait for respawn to finish before running it.
- `/furnacetest deathoff`: after keepInventory=false death, verifies 39 dropped items, empty internal storage, zero heat and retained upgrades. Clear old test drops first and die away from respawn so items are not picked up before checking.

The test-only client helper reads and deletes `furnace-client-command.txt` in its game directory. `language en_us`, `language ja_jp`, or `scale 1` through `scale 4` supports repeatable screenshots. Wait for language resources to finish reloading before inspecting the screen.

Record the exact JAR hash, versions, expected/actual results and whether a test used real input events or direct server assertions. Keep credentials, world files and private deployment data out of reports.
