# Verification

[日本語](VERIFICATION.ja.md) · [Development](DEVELOPMENT.md)

## Gameplay baseline — 2026-09-06

The gameplay implementation carried into this first public beta was tested on Minecraft 1.20.1 / Forge 47.4.10 / Java17 with M&S 6.4.7, LoE 2.1.11 and Mowzie's Mobs 1.8.2 plus their dependencies. Testing used a disposable dedicated server and two real graphical Forge clients over loopback, with offline test identities and software rendering. It was not an integrated server or a protocol bot.

| Check | Result and scope |
| --- | --- |
| Core | 95,604 assertions, 53 purchase rows, 20,000 seeded thermal cases |
| Editor / drag model | 583,594 assertions, 20,000 seeded distribution cases |
| Actual Minecraft runtime | 2,489 assertions: purchases using real M&S level/inventory/XP, real recipes, menu handling, item conservation and automation |
| Purchases / recipes | All 53 purchases; real smelting/smoking/blasting; four jobs at 2× consume exactly 800 heat; lava bucket remainder and oversized fuel refusal |
| Automation | Input tiers I–V and fuel tiers I–IV; real M&S common-rarity level-25 gear; protection at every input tier |
| Mouse / keyboard | Drag 12 sand into two stacks of 6; numeric key moves 8 glass and awards exactly 8 XP; named-item confirmation; visual AND/OR send/discard |
| UI | Six tabs in English/Japanese; editor scales 1–4 and draft retention after resize |
| Death | keepInventory=true full NBT retained after respawn; false drops exactly 39 internal items, empties storage/heat and retains upgrades |
| Persistence | Complete fixture NBT matches after reconnect, graceful restart, Nether travel and End return |
| Access / handshake | Second client's read-only menu cannot change owner state; missing or unequal JAR is rejected |

The native and pinned-container artifacts were independently exercised because Java17 compiler revisions can emit different bytecode. The final pre-publication gameplay candidate's pinned-container JAR SHA-256 was `8f0be8d7796a0b1ddc6891bc94f9cd4c062082445e9e8489b40a9388c6b50222`. This is a **baseline hash, not the public beta download hash**. Public hashes are published as Release assets.

## Public packaging verification

The standalone project changes version/license/metadata and build/documentation infrastructure while preserving the gameplay Java source, mod ID and save schema. Public CI executes both test suites and two complete pinned-container builds, compares JAR hashes, checks metadata and ensures the driver/dependencies are absent from the product JAR. Runtime acceptance for the public artifact is recorded in [public-beta-runtime.json](public-beta-runtime.json).

## Limits

Tests establish the listed deterministic and seeded scenarios, not the absence of every possible duplication exploit. Two clients are not a large-player-count soak test. WAN latency, online account authentication, hostile datapacks, exhaustive disk-corruption recovery and unrelated MOD combinations were not exhaustively tested. The exact persistence fixture is paused with zero heat to avoid legitimate online cooling changing a snapshot. Long-term economy and balance need continued playtesting.

Screenshots show the validated gameplay UI; they are not evidence for future versions. The test driver in `src/runtimeTest` provides reproducible test entry points. No deployment files, private world data, third-party JARs or production credentials are included here.

The public artifact also renders the [visual editor](images/public-beta-editor.png) on a real client. All 45 product class entries match the validated baseline byte-for-byte; public runtime assertions and existing-save restoration passed.
