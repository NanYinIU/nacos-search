# Domain Docs

How the engineering skills should consume this repository's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repository root.
- **`docs/adr/`** — read ADRs that touch the area being changed.

If either location does not exist, proceed silently. Do not suggest creating files upfront; `/domain-modeling` creates them lazily when terms or decisions are actually resolved.

## File structure

This is a single-context repository. There is no `CONTEXT-MAP.md`; root `CONTEXT.md` and `docs/adr/` are authoritative for every module:

```text
/
├── CONTEXT.md
├── docs/adr/
└── src/
```

## Use the glossary's vocabulary

When output names a domain concept in a GitHub issue title, refactor proposal, hypothesis, or test name, use the term as defined in `CONTEXT.md`. Do not drift to synonyms the glossary explicitly avoids.

If a needed concept is absent, either reconsider the invented language or note the real gap for `/domain-modeling`.

## Flag ADR conflicts

If output contradicts an existing ADR, surface it explicitly instead of silently overriding it.
