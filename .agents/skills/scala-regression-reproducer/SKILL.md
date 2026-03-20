---
name: scala-regression-reproducer
description: Reduce Scala compiler or standard library regressions found in community-build projects to minimal, self-contained scala-cli repros. Use when a Scala nightly breaks a project under community-build3, when Codex needs to auto-discover the matching `scripts/run.sh` project key and baseline Scala version, when the regression must be confirmed with a known-good build and a nightly build, or when a failing build log needs to be minimized into `test.scala` / `test-macro.scala` files.
---

# Scala Regression Reproducer

Use this skill to turn a failing community-build project into a small Scala compiler reproducer with a clear good-vs-bad version boundary.

## Workflow

1. Confirm the regression before reducing it.
   - Run `scripts/confirm_regression.sh [project-key] [known-good-scala] [bad-scala]`.
   - Prefer `SKIP_BUILD_SETUP=1 scripts/confirm_regression.sh ...` once the project already exists in `.github/workflows/buildConfig.json`.
   - Omit `project-key` to auto-discover it from the checked-out repo remote and `.github/workflows/buildConfig.json`.
   - Omit `known-good-scala` to use `publishedScalaVersion` from `buildConfig.json`.
   - Omit `bad-scala` to let `../scripts/run.sh` pick the latest nightly.
   - If the user already has the failing repo checked out and gives an exact `scala-cli` command plus cwd, reproduce that command in that directory before relying on helper artifacts.
   - For Scala `3.8.x` or `3.nightly`, use a current Scala CLI runner (at least `1.12.x`) for direct verification; older pinned runners can obscure the real boundary.
   - If the helper or build-server path is noisy or hangs, confirm the boundary directly in the checked-out repo with `scala-cli compile . -S <version>` and retry with `--server=false` only if needed.
   - `scripts/run.sh` regenerates `.github/workflows/buildConfig.json` for the designated project unless `SKIP_BUILD_SETUP=1` is set.
   - If you need that regeneration because the project entry is missing, restore `.github/workflows/buildConfig.json` afterward with `git restore .github/workflows/buildConfig.json` unless you want to keep the change.
   - `scripts/run.sh` clones into `$PWD/repo` and removes any existing `repo/` directory there before each run; this helper contains that cleanup by running each case inside `out/scala-regression-reproducer/<timestamp>/{good,bad}`.
   - The helper stores separate artifacts under `out/scala-regression-reproducer/<timestamp>/{good,bad}`.

2. Inspect the bad run artifacts.
   - Read `build-status.txt`, `build-summary.txt`, and `build-logs.txt` from the `bad/` directory first.
   - Extract the failing module, source file, exact compiler phase, and full assertion or error text.
   - If the bad run passes or the good run fails, stop and resolve the baseline mismatch before reducing.

3. Reduce aggressively, but preserve the failing compiler shape.
   - Keep the compiler trigger, not the library surface area.
   - Inline only the reflection, inline, macro, or type-level sequence that keeps the same compiler path alive.
   - Remove external dependencies last; if a library macro is the trigger, copy only the relevant local expansion logic into self-contained files.
   - For inline access regressions, first try reducing to just the access shape: a class with the protected/private member, the inline companion logic that calls it, and one top-level trigger expression.
   - Split macro definitions and use sites across files when Scala 3 staging blocks same-file macro calls.
   - If a scalac option is required, encode it as a scala-cli `//> using options ...` directive so the final compile command stays short.

4. Finalize the repro.
   - Write `test.scala` or a very small file set at the top level of the checked-out repo.
   - Prefer a truly self-contained `test.scala` once the failure no longer needs repo-local `//> using file` directives or external dependencies.
   - Re-run the smallest possible compile command on both the good and bad versions.
   - Report the exact commands and the pass/fail boundary.

## Discovery Hints

- Prefer the checked-out repo remote (`git remote get-url origin`) to derive the community-build `project-key`.
- Match that remote against `.github/workflows/buildConfig.json` `repoUrl` entries.
- Use `publishedScalaVersion` as the first default known-good version.
- `build-summary.txt` gives module-level failures quickly; `build-logs.txt` contains the exact compiler output.
- If `publishedScalaVersion` is missing, pass the known-good Scala version explicitly instead of guessing.
- For Scala CLI projects, the repo-root `scala-cli compile . -S <version>` result is often the authoritative signal; use helper outputs mainly to discover versions, project keys, and logs.

## Constraints

- Keep the final reproducer self-contained unless the user explicitly asks for a library-dependent repro.
- Preserve exact error text, relevant scalac options, and concrete version strings.
- Confirm both sides of the regression boundary before claiming success.
