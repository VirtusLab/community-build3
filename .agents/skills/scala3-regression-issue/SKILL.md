---
name: scala3-regression-issue
description: Draft GitHub issues for Scala 3 compiler regressions from minimized repros, version boundaries, bisect results, and community-build failures. Use when Codex needs to turn a confirmed Scala 3 regression into a ready-to-file `scala/scala3` issue title and body that matches existing regression reports.
---

# Scala3 Regression Issue

Use this skill after the regression is already confirmed and minimized. The goal is to draft a `scala/scala3` issue that is concise, reproducible, and formatted like existing regression reports.

## Workflow

1. Gather the inputs.
   - Use the smallest confirmed reproducer, ideally a self-contained `test.scala` or `test-macro.scala` file set.
   - Capture the exact compiler boundary: last good release, first bad release.
   - Capture the bisect result: commit SHA, PR link if known, and the bisect command if available.
   - Capture provenance: affected community-build projects and build-log links, if relevant.

2. Draft the title.
   - Use the pattern ``<symptom>` regression in <construct / usage>``.
   - Keep the title about the compiler behavior, not the discovery project, unless multiple projects were needed to discover the regression.
   - Backtick concrete flags, APIs, or syntax.

3. Draft the body in this order.
   - `Based on the OpenCB failures in:` only when OpenCB context is relevant.
   - `## Compiler version`
   - `## Minimized code`
   - `## Output`
   - `## Expectation`

4. Fill the sections with the right level of detail.
   - In `## Compiler version`, include last good, first bad, bisect commit, PR link if known, and the bisect command if available.
   - In `## Minimized code`, prefer the smallest dependency-free repro that still fails.
   - In `## Output`, include the decisive compile command and the relevant error excerpt, not unrelated warnings.
   - In `## Expectation`, state the expected successful behavior in one sentence.

## Style Rules

- Preserve exact version strings, commit hashes, and error text.
- If build logs exist, include them; if they do not, omit the link rather than inventing one.
- If a bisect commit exists but the PR is unknown, link the commit directly.
- Keep the issue body scannable and avoid narrative filler.
- Prefer fenced `scala`, `bash`, and `text` blocks.

## Template

Use this structure as the default draft:

````markdown
Based on the OpenCB failures in:
- org/repo - [build logs](...)

## Compiler version

Last good release: ...
First bad release: ...
Bisect points to ... / PR $<pr_number>

## Minimized code

```scala
...
```

## Output

```scala
...
```

## Expectation
Should compile without ...
````

Delete the OpenCB section or the bisect command subsection when they are not available.
