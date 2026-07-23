# Wire SessionEpoch fencing into production paths

**Closes gap in:** #5
**Blocked by:** none

## Acceptance
- Bump session epoch on profile / namespace / revision / generation changes in the product path.
- OperationFence drops obsolete browse/search/history completions from publishing into the current project session.
- Profile tombstone persists across restarts (or document intentional in-memory scope + tests).
- Late credential/token/probe/cache completions rejected when entombed.
- Deterministic tests for obsolete-after-bump.
