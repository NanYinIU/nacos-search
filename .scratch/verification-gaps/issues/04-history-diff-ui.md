# Add History + Diff UI in ConfigDetailPanel

**Closes gap in:** #14
**Blocked by:** 01

## Acceptance
- Entry: detail panel action opens History list (paged) for current identity/namespace/coordinate.
- Distinct outcomes: empty / permission / unsupported / body.
- Diff history↔history and history↔current via HistoryDiffPresenter; read-only; no restore/publish.
- Epoch fencing: switch profile/namespace mid-load drops stale results.
- Manual GUI: entry visible, click opens list, select opens body, Diff opens IntelliJ Diff.

**Status: DONE**
