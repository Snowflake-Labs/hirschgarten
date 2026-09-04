# hirschgarten (Snowflake fork) — agent guidance

A Snowflake fork of the JetBrains Bazel plugin for IntelliJ. This file is the canonical guidance for
coding agents; `CLAUDE.md` and `.cursor/rules/development-261-context.mdc` defer to it.

## Branch context — read this first

**Open and read `CONTEXT_DEVELOPMENT_261.md` at the repository root before proposing any change.**

It is the primary handoff document for `development-261` and records:

- current state and the recorded tip commit
- recently merged PRs, with the problem each one solved
- open/pending work, including known bugs whose root cause is still uninvestigated
- decisions taken and later reversed — read these before re-proposing an approach that was rejected
- **fork topology**: which source files are upstream-maintained versus Snowflake-only

Each long-lived development branch carries its own `CONTEXT_DEVELOPMENT_<ijVersion>.md`. If the file
named above is absent on your branch, look for `CONTEXT_DEVELOPMENT_*.md` at the repository root, read
that instead, and update the references in this file, `CLAUDE.md`, and the Cursor rule.

## Before editing any file: check fork topology

Rebase cost is not uniform across this repository, and that should drive how invasive an edit is.

- Some files are **Snowflake-only** — they do not exist upstream. Restructure them freely.
- Others are **upstream-maintained**, actively churned, and in some cases have been relocated to
  different modules upstream, so a rebase means a rename on top of content drift. Keep edits there
  additive and small.

To check a specific file:

```bash
git ls-tree -r --name-only jetbrains/main | grep '/<FileName>.kt$'   # empty ⇒ Snowflake-only
git log --oneline --since=<date> jetbrains/main -- <upstream/path>   # churn
```

The "Fork Topology" section of the context document has the current table. Two rules that follow from
it, and that are easy to get wrong:

- Prefer a **new Snowflake-owned file in the same package** over appending declarations to an upstream
  file. Kotlin allows multiple declarations per file, so a cohesive helper file is idiomatic, not a
  workaround.
- Prefer **wrapping a call site** over re-indenting an upstream function body. Re-indentation
  guarantees a conflict with any upstream edit to that function.

## Workflow

- PRs target `development-261`, not `main`. Push to the `snowflake` remote
  (`github.com/Snowflake-Labs/hirschgarten`).
- Use an `sfc-gh-*` GitHub account for PRs and comments.
- `.editorconfig` sets `ktlint_code_style = ktlint_official` with `max_line_length = 140`.
  `ktlint-baseline.xml` suppresses pre-existing violations only — new ones will surface.
- Match the surrounding code's comment density, naming, and idiom rather than importing a house style.

## Keeping the context document honest

Update `CONTEXT_DEVELOPMENT_261.md` as part of the work, not afterwards: move completed items into
"Recently Completed", revise "Pending", and bump the recorded tip commit.

Do not write "get the PR reviewed/merged" as a next step. The document lives in the repository and is
read post-merge, so those entries are stale on arrival — use `—` when nothing actionable remains.
