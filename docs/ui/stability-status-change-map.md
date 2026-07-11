# Stability Status UI Change Map

## Approval Gate

- **Gate:** `Pending Approval`
- **Scope:** design and change-location review only
- **Production UI changes:** prohibited until the user approves both this change map and the complete tool-window/settings mockups
- **Mockups:** `docs/images/stability-tool-window-complete.png` and `docs/images/stability-settings-complete.png`
- **Approval record:** pending

Line numbers below are from the stability worktree on 2026-07-11. Symbols are the durable reference if later non-UI work shifts a line.

## Exact Change Locations

### Settings: remove the automatic-refresh control after approval

| File | Current location | Symbol / code | Approved implementation intent |
| --- | ---: | --- | --- |
| `src/main/kotlin/com/nanyin/nacos/search/settings/NacosConfigurable.kt` | 64 | `autoRefreshCheckBox` field | Delete the field. |
| same | 141 | `commitDetailFormToDraft()` | Delete the assignment to `server.autoRefreshOnOpen`. |
| same | 237-240 | `buildComponents()` | Delete checkbox construction, tooltip, and listener. |
| same | 565-569 | `buildDetailPanel()` advanced body | Delete the Auto refresh label and checkbox row; Connection timeout is followed directly by Cross namespace. |
| same | 686 | `loadDraftIntoForm()` | Delete checkbox state loading. |
| same | 739 | `isModified()` | Delete the auto-refresh dirty comparison. |
| `src/main/kotlin/com/nanyin/nacos/search/ui/NacosSearchWindow.kt` | 292-313 | `handleSettingsChanged()` | Remove the `autoRefreshOnOpen` condition at lines 309-312 and do not load configurations automatically after an environment/settings switch. Show cached state or the empty/manual-refresh state. |
| same | 349-360 | `loadInitialData()` | Keep namespace discovery, but remove automatic configuration loading on tool-window open. |
| `src/main/kotlin/com/nanyin/nacos/search/models/NacosServerConfig.kt` | 21, 35 | `autoRefreshOnOpen` and `deepCopy()` | Remove the persisted per-server option in a compatibility-safe migration; an old serialized field must be ignored without blocking settings load. |
| `src/main/kotlin/com/nanyin/nacos/search/settings/NacosSettings.kt` | 164, 178 | legacy `autoRefreshEnabled` bridge | Remove or retain only as a read-and-ignore compatibility field; it must not trigger work. |
| `src/main/kotlin/com/nanyin/nacos/search/settings/NacosSettingsListener.kt` | 13 | settings-change contract comment | Remove `autoRefreshOnOpen` from the documented change set. |
| `src/main/resources/messages/NacosSearchBundle.properties` | 240-241 | `settings.server.auto.refresh*` | Delete the unused English keys after the control is removed. |
| `src/main/resources/messages/NacosSearchBundle_zh_CN.properties` | 240-241 | `settings.server.auto.refresh*` | Delete the unused Chinese keys after the control is removed. |

This removal does not remove explicit refresh. The existing header refresh action remains at `NacosSearchWindow.setupLayout()` lines 117-128 and calls `refreshAll()` at line 884.

### Tool window: compact dataset status

| File | Current location | Symbol / code | Approved implementation intent |
| --- | ---: | --- | --- |
| `src/main/kotlin/com/nanyin/nacos/search/ui/NacosSearchWindow.kt` | 114-160 | `setupLayout()` | Place one compact status presenter between the namespace/search toolbar and the list, visually attached to the list region. It shows source, freshness, completeness, last successful update, and an optional Retry action. |
| same | 527-589 | `setupSearchServiceObservers()` | Map service state to a UI presentation model. Preserve currently displayed configurations when a refresh fails; update status/error separately. Never derive state from localized exception text. |
| same | 610-614 | `setSearching()` | Loading disables only conflicting search/switch actions; current rows remain visible during refresh. |
| same | 809-813 | `showError()` | Replace destructive list-error rendering for refresh failures with a non-destructive status presentation. Initial-load failure may still use the empty/error card. |
| same | 884-899 | `refreshAll()` | Set refreshing state, retain prior rows, and surface typed completion/failure. Retry reuses this explicit path. |
| `src/main/kotlin/com/nanyin/nacos/search/ui/ConfigListPanel.kt` | 42-46 | `loadingLabel`, `statusLabel`, `emptyStatePanel` | Keep list cards for initial loading/empty. Replace the generic footer text with the approved compact presenter, or accept a presenter component supplied by the window. |
| same | 107-132 | `setupLayout()` | Exact insertion point is `BorderLayout.SOUTH`, currently occupied by `statusLabel`; presenter must remain a single compact row and must not resize the result list between states. |
| same | 207-240 | `setConfigurations()`, `setLoading()`, `showError()` | Split data replacement from status rendering. Refresh loading/error must not clear valid rows; true empty initial load still selects the empty card. |
| same | 272-274 | `updateStatus()` | Replace string-only state with a typed, immutable presentation input. `ConfigListPanel` must not call services or own network state. |
| `src/main/kotlin/com/nanyin/nacos/search/services/NacosSearchService.kt` | 126-146 | `SearchState`, `SearchSource` | Replace `SearchSource` with `DatasetState`; expose typed failure and a non-destructive refresh error that can coexist with the last successful result. |
| same | 325-355 | `publishIfCurrent()` | Publish source/freshness/completeness and last successful timestamp atomically with results. A failed current refresh updates error/status but does not discard the last success. Latest-request-wins remains authoritative. |
| `src/main/kotlin/com/nanyin/nacos/search/models/AccessIdentity.kt` | 27-42 | `DataSource`, `DataFreshness`, `DatasetState` | Reuse these domain types; do not create UI-specific duplicate enums. |
| `src/main/kotlin/com/nanyin/nacos/search/models/NamespaceLoadResult.kt` | 5, 13-23 | `DatasetCompleteness`, `NamespaceLoadResult`, `ConfigLoadFailure` | Use loaded/expected/failure counts for partial status and per-item retry input. |
| `src/main/kotlin/com/nanyin/nacos/search/services/NamespaceIndexCoordinator.kt` | 32-37 | `IndexOutcome` | Map `Complete`, `Partial`, `Stale`, and `Failed` directly into search/UI state. |
| `src/main/kotlin/com/nanyin/nacos/search/services/network/NacosRequestError.kt` | 9-17 | typed request errors | Map auth, timeout, rate limit, client/server, connection, and protocol failures without parsing messages. |

### Message bundles

Add matching keys to both `NacosSearchBundle.properties` and `NacosSearchBundle_zh_CN.properties`, adjacent to the current status block at lines 195-203 or the config-list block at lines 77-86:

| Key family | Required values |
| --- | --- |
| `config.list.status.source.*` | remote, cache |
| `config.list.status.freshness.*` | fresh, stale, unknown |
| `config.list.status.completeness.*` | complete, partial (`{0}/{1}`) |
| `config.list.status.updated` | last successful update (`{0}`) |
| `config.list.status.refreshing` | refreshing while current data remains visible |
| `config.list.status.preserved.error` | refresh failed; showing previous data |
| `config.list.status.retry` | retry command |
| `config.list.status.auth` | authentication failed |
| `config.list.status.timeout` | request timed out |
| `config.list.status.overflow` | result count exceeds display/page limit |

Existing generic keys such as `status.loading`, `error.authentication.failed`, and `error.timeout.error` are insufficient because the new component needs contextual, non-destructive messages.

## Data-to-UI Mapping

The presenter receives a typed snapshot owned by `NacosSearchWindow`, conceptually:

| UI field | Domain source | Rule |
| --- | --- | --- |
| Source | `DatasetState.source` | `REMOTE` -> Remote; `CACHE` -> Cache. |
| Freshness | `DatasetState.freshness` | `FRESH`, `STALE`, or `UNKNOWN` displayed independently of source. |
| Completeness | `DatasetState.completeness` | `COMPLETE`; or `PARTIAL` with `loaded/expected`; `FAILED` is rendered as an error state, not as an empty successful dataset. |
| Last update | `DatasetState.fetchedAtMillis` | Show last successful fetch time. Null -> Unknown, never the current clock time. |
| Partial counts | `IndexOutcome.Partial.loaded/expected` | Example: `488/500 loaded`; retain 12 failure identities for retry. |
| Error category | `NacosRequestError` subtype | Auth, connect/read timeout, rate limit, HTTP 4xx/5xx, connection, protocol. No localized string inspection. |
| Existing rows | last successful `SearchState` | Preserved for refresh failures and while refreshing; cleared only after a successful result for a different environment/namespace is accepted. |

The three dataset dimensions remain independent. For example, `CACHE + STALE + COMPLETE` is valid, as is `REMOTE + FRESH + PARTIAL`. The UI must not collapse these into a single “cached” boolean.

## Complete State Catalogue

| State | List content | Compact status | Actions and behavior |
| --- | --- | --- | --- |
| Normal | Successful rows and pagination. | `Remote · Fresh · Complete · Updated 14:32` (or the equivalent cache state). | Refresh remains available. |
| Loading, no prior data | Loading card; stable list-region dimensions. | `Loading configurations…` | Disable conflicting search/switch actions; allow cancellation through normal lifecycle. |
| Refreshing with prior data | Keep rows, selection, detail, and pagination visible. | Spinner + `Refreshing…`; prior status remains readable. | Refresh action disabled until completion; no blanking. |
| Empty | Empty card only after a successful complete response with zero matches, or before any namespace is selected. | `Remote · Fresh · Complete` for a genuine zero result; otherwise `No namespace selected`. | Search criteria remain editable. |
| Partial 488/500 | Show 488 successful rows/metadata; do not publish as a complete namespace index. | Warning: `Partial · 488/500 loaded · Updated 14:32`. | Retry only the 12 failed configurations when failure identities exist; a full manual refresh remains available. |
| Stale | Show the last complete cache while within the seven-day maximum stale window. | Warning: `Cache · Stale · Complete · Updated …`. | Retry performs explicit foreground refresh. Stale data supports offline navigation but does not prove non-existence. |
| Unknown freshness | Show available data without claiming freshness. | Neutral warning: `Cache · Unknown · Complete/Partial · Updated unknown`. | Retry available; no “fresh” styling. |
| Authentication failure | Preserve prior rows if present; otherwise error card. | Error: `Authentication failed`; never include credentials/token/body. | Retry plus Settings shortcut; retry uses current identity only. |
| Timeout | Preserve prior rows if present; otherwise error card. | Error: `Request timed out`; if stale data is used, also show its independent stale status. | Retry starts a new 15-second foreground operation; timed-out work must not later overwrite it. |
| HTTP/server failure | Preserve prior rows if present; otherwise error card. | Error: sanitized category/status, for example `Server error (500)`; do not show raw body. | Retry available according to request policy. |
| Overflow | Show the bounded current page; never allocate one component per unbounded result set. | `Showing {0} of {1}` or a defined result cap warning. | Pagination/filtering remains operable; status row does not wrap or resize. |
| Keyboard focus | Focus ring follows IntelliJ theme on Retry/Settings/status action; status text itself is not focusable. | Same content as underlying state. | Tab order: namespace -> search -> refresh/status action -> list -> pagination -> detail. Enter activates focused action; no focus theft on background completion. |
| Retry | Existing rows remain until retry succeeds for the same identity and namespace. | Changes to `Refreshing…`, then typed outcome. | Single-flight prevents duplicate work; repeated clicks do not start parallel refreshes. |
| Environment switch | Immediately invalidate the visible dataset identity; do not show the previous environment's rows under the new label. | `Cache/Loading/Empty` only for the new `AccessIdentity`; unknown until a valid snapshot is selected. | Cancel/supersede old foreground request. No automatic full refresh. User explicitly refreshes. |
| Namespace switch | Clear prior-namespace selection/detail unless navigation is intentionally pending. Show only same-identity, target-namespace cache if available. | Status belongs to target namespace; never retain the old namespace timestamp. | No periodic refresh. Namespace preheat may run, but foreground UI remains latest-request-wins and manual refresh is explicit. |

## Layout and Ownership Constraints

- The compact presenter is one horizontal row at the bottom edge of the configuration list, replacing the current generic `statusLabel` location. It is not a dialog, banner over the list, or nested card.
- The presenter must fit the realistic narrow IntelliJ tool-window width. Lower-priority timestamp text truncates before source/freshness/completeness or Retry; tooltip may expose the full timestamp.
- `NacosSearchService` and `NamespaceIndexCoordinator` own typed execution state. `NacosSearchWindow` owns mapping and current identity/namespace validation. `ConfigListPanel` only renders the immutable presentation and list cards.
- State completion must not move keyboard focus, list selection, splitter positions, or scroll position unless a successful replacement dataset makes the prior selection invalid.
- No production UI implementation may begin while `Gate` is `Pending Approval`.

## Approval Record

Pending. Approval requires review of this change map plus the complete, realistic-width mockups below:

- `docs/images/stability-tool-window-complete.png`
- `docs/images/stability-settings-complete.png`

After approval, record the exact approval text and date here and change the gate to `Approved` before modifying production UI code.
