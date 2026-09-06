# Contributing

[日本語](CONTRIBUTING.ja.md)

Use this repository's Issues and pull requests. English and Japanese reports are welcome. For bugs, include exact Minecraft/Forge/MOD versions, the JAR hash, reproduction steps, expected/actual behavior and a sanitized log excerpt. Do not upload credentials, full world saves or private server configuration.

Keep changes focused and include appropriate verification. Changes to item movement, purchases, persistence or networking need meaningful regression coverage; GUI changes should include actual screenshots. Update English and Japanese documentation together. Run `python3 scripts/check-project.py`, `bash test-core.sh`, and the Forge build before requesting review.

Keep the server authoritative. Do not add third-party MOD binaries, change dependencies without runtime evidence, silently migrate unknown saved data, or publish a release from a pull-request workflow. Contributions are submitted under this project's MIT license; retain third-party notices where applicable.
