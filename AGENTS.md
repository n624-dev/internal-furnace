# Internal Furnace maintenance

This is the upstream repository for MOD source, tests, bilingual documentation and GitHub Releases. Work on future code and release changes here. Do not edit a deployment repository's duplicate MOD source or deploy/restart game servers as part of a release.

Keep README, guides, changelog and user-facing release notes in English and Japanese. Run `python3 scripts/check-project.py`, `bash test-core.sh` and an appropriate Forge build. Item movement, XP, persistence and network changes require actual dedicated-server/client regression evidence in addition to model tests.

Use `mod_version` in `gradle.properties`; preserve the mod ID and save schema unless migration is deliberate and verified. Publish the same tested JAR to both sides. Never commit dependency JARs, world data, credentials or private deployment information. The opt-in runtime test driver must remain outside release artifacts.

Explicit version tags publish through GitHub Actions. Do not move published tags or replace released artifacts. CurseForge registration/publication is maintained separately until explicitly requested.

このリポジトリで今後のMOD編集・テスト・日英文書・GitHub Releaseを管理します。Releaseに本番配置・サーバー再起動は含めません。認証情報や非公開運用情報、依存MODのJARをコミットしないでください。日英文書を同時更新し、実行時変更は実専用サーバー・実クライアントでも検証してください。
