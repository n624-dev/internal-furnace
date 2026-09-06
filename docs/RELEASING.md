# Releasing and maintenance

[日本語](RELEASING.ja.md) · [Home](../README.md)

This repository is the upstream for Internal Furnace source, issues, builds and GitHub Releases. Deployment projects should consume a named release and verify SHA-256, rather than maintain a second edited copy. No workflow installs or restarts a Minecraft server.

## Prepare a release

1. Make code and paired English/Japanese documentation changes in a branch and open a pull request.
2. Update `mod_version` in `gradle.properties`, both changelogs, and `docs/releases/<version>.md` with English and Japanese notes.
3. Let CI pass. It checks metadata/docs, builds and tests twice in the pinned container, verifies the JAR and compares hashes. Runtime-sensitive changes also require disposable dedicated-server / graphical-client acceptance.
4. Merge the reviewed changes into `main`. Explicitly create and push a matching tag, for example:

```bash
git tag -a v0.1.0-beta.1 -m 'Internal Furnace 0.1.0-beta.1'
git push origin v0.1.0-beta.1
```

Use a new version/tag for later releases; do not move published tags or replace released JARs.

## Actions behavior

Pull requests, main pushes and manual workflow runs build/test only. A `v*` tag additionally runs the Release job **after** the build passes. The tag must exactly match `mod_version` and have a release-notes file. Only the Release job receives `contents: write`; it uses GitHub's built-in token, with no personal token secret needed.

The Release contains one reobfuscated client/server JAR and `SHA256SUMS`. Versions with a hyphen, such as `0.1.0-beta.1`, are marked GitHub Pre-release. The test-driver JAR is never published. If publication fails after a successful build, inspect the job and rerun failed jobs after fixing the cause; do not overwrite an already published release.

Downloaded files can be checked together with `sha256sum --check SHA256SUMS`. Game clients enforce an identical JAR hash during connection.

## CurseForge

CurseForge registration and publication are managed separately by the maintainer. There is no CurseForge token, upload job, project ID or claimed listing in this repository. When a project exists, a later change can add its public link and an upload job that reuses the exact tested GitHub artifact. Keep API credentials in repository secrets, never in source or release notes.
