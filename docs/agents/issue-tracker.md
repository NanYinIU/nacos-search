# Issue tracker: GitHub

Issues and PRDs for this repo live as GitHub Issues. Use the `gh` CLI for all operations.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --comments`, including labels.
- **List issues**: `gh issue list --state open` with appropriate `--label` filters and JSON fields when structured output is needed.
- **Comment on an issue**: `gh issue comment <number> --body "..."`.
- **Apply / remove labels**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`.
- **Close**: `gh issue close <number> --comment "..."`.

Infer the repository from `git remote -v`; `gh` does this automatically when run inside this clone.

## Pull requests as a triage surface

**PRs as a request surface: yes.** `/triage` processes external PRs with the same labels and states as issues, while leaving collaborators' in-flight PRs alone.

- **Read a PR**: `gh pr view <number> --comments` and `gh pr diff <number>`.
- **List external PRs for triage**: list open PRs and keep only authors whose association is `CONTRIBUTOR`, `FIRST_TIME_CONTRIBUTOR`, or `NONE`; exclude `OWNER`, `MEMBER`, and `COLLABORATOR`.
- **Comment / label / close**: use the corresponding `gh pr` commands.

GitHub shares one number space across issues and PRs. Resolve a bare `#<number>` with `gh pr view <number>` and fall back to `gh issue view <number>`.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <number> --comments`.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a GitHub issue with child issues as tickets.

- **Map**: one issue labelled `wayfinder:map`, holding the Notes / Decisions-so-far / Fog body.
- **Child ticket**: a GitHub sub-issue linked to the map. If sub-issues are unavailable, use a task list in the map body and add `Part of #<map>` to the child. Use `wayfinder:<type>` labels for `research`, `prototype`, `grilling`, and `task`.
- **Blocking**: prefer GitHub native issue dependencies; otherwise use a `Blocked by: #<number>` line in the child body.
- **Frontier**: choose the first open, unblocked, unassigned child in map order.
- **Claim**: assign the ticket to the driving developer before work.
- **Resolve**: comment with the answer, close the ticket, then add a context pointer to the map's Decisions-so-far.
