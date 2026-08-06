# Codegraph

Codegraph is the preferred retrieval path for symbols, call paths, and blast-radius when exploring this codebase. Before any `codegraph_*` MCP call (or `codegraph explore` / `query` CLI), the index for **this workspace root** must be ready.

## Workspace root (worktrees)

The index lives at `<workspace>/.codegraph/`. A git **worktree** is a separate directory: the main checkout's index does not cover a worktree, and the reverse is also true.

Always:

1. Resolve the workspace root (`git rev-parse --show-toplevel`, or the path you will pass as `projectPath`).
2. Gate readiness on **that** path only.
3. Pass that same path as `projectPath` on every codegraph tool call.

Do not point tools at a sibling worktree or the main repo when the session's cwd is another worktree.

## Readiness gate

Run before the first codegraph retrieval in a session (and again after large pulls or branch switches if answers look stale).

1. **Probe**: `codegraph status <workspace-root>`.
2. **Branch on status**:
   - **Not initialized** (no `.codegraph/`, status says not initialized): `codegraph init <workspace-root>`. That creates `.codegraph/` and builds the initial index — do not chain a separate `index` unless init failed partway or status still reports no index.
   - **Initialized**: `codegraph sync <workspace-root>` so the index catches up with current tree changes.
   - **Broken after init/sync** (status fails, or known symbols return missing-index / empty in a way that looks like a dead index): `codegraph index <workspace-root>` for a full rebuild.
3. **Query**: call the MCP/CLI with `projectPath` set to that same workspace root.

**Done when**: `codegraph status <workspace-root>` is no longer "Not initialized", and a probe explore/query either hits a known symbol in this tree or cleanly returns empty for a name that does not exist — not a missing-index error.

If the `codegraph` CLI is not installed, fall back to Read/Grep/Glob; do not invent an index.

## Commands

```bash
# Always pass the workspace root (worktree path when inside one)
codegraph status /path/to/workspace
codegraph init   /path/to/workspace   # first time (includes initial index)
codegraph sync   /path/to/workspace   # already initialized — catch up
codegraph index  /path/to/workspace   # full rebuild when sync is not enough
```
