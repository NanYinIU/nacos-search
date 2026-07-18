# Cache stale navigation design notes

## Confirmed decisions

### Stale gutter presentation

A stale configuration remains a **缓存导航目标** rather than an **已解析配置引用**. Its gutter marker keeps a solid arrow to communicate that navigation is available, changes to the warning/amber palette, and adds a small clock badge so freshness is not communicated by colour alone.

The tooltip states that cached Nacos content is being used while the namespace refreshes in the background. Fresh targets keep the existing blue solid marker; unconfirmed targets keep the existing gray hollow marker.

### Refresh triggers

Automatic refresh is event-driven rather than periodic. Startup cache completion, namespace switches, and the first stale gutter observation may request a refresh; a manual refresh always forces one. Fresh gutter reads never request remote work.

Automatic requests are coalesced by access identity and namespace through the existing single-flight coordinator. Failed PSI/background refreshes retain stale navigation targets and observe the existing five-minute retry cooldown.

### Persistence across IDE restarts

Configuration details remain persisted after TTL expiry and may be restored as stale navigation targets without an age-based navigation cutoff. Entries older than the fixed seven-day deep-stale threshold remain navigable, but opening them must immediately display the cached content and force an asynchronous single-detail refresh.

The complete Namespace index remains memory-only to avoid duplicating full configuration payloads. After restart, persisted details can provide cache navigation targets, but missing keys and data IDs remain **不可判定配置引用** until a complete namespace refresh succeeds; absence is never inferred from a restored detail subset.

### Deep-stale and missing data

Seven days is a deep-stale refresh threshold rather than a retention or navigation limit. A deep-stale entry keeps the amber stale marker and remains immediately navigable. Its detail view first renders cached content with a refresh status, then requests that exact `dataId + group + namespace` using `forceRefresh=true`. Success replaces the content and restores fresh state; failure leaves the cached view open with a warning that the remote configuration may have changed or been deleted.

A genuinely missing entry has no old navigation target. With a usable data ID hint it may show the unresolved marker and recover through a single-detail request; a key-only reference requires a full Namespace load to rebuild the key-to-configuration index. A complete refresh rebuilds the resolver and restarts daemon highlighting. Automatic failures observe the PSI cooldown, while manual refresh remains available immediately.

### Refresh write semantics

A COMPLETE Namespace result atomically replaces the snapshot for its captured access identity and Namespace; configurations absent from that authoritative result are removed. A PARTIAL result upserts only successfully fetched details with the captured TTL and retains every other stale or deep-stale target because absence cannot be inferred. A FAILED result changes neither cached content nor the last-successful-refresh timestamp.

A successful single-detail refresh replaces only that detail and makes it fresh. Network, authentication, and timeout failures retain the stale target. An explicit remote not-found result removes the target from the navigable cache and restarts gutter analysis; an already-open cached view may remain visible with a remote-deleted warning but cannot be opened again from code.

### Reference-state ownership

Positive navigation state belongs to each cached detail, while only a fresh COMPLETE Namespace snapshot may prove absence. A fresh detail containing the key uses the blue resolved marker and navigates to the key line. A stale or deep-stale detail containing the key uses the amber stale marker, navigates immediately, and refreshes according to its age.

When a data ID is available but the key is not resolved, the gray hollow marker remains actionable. It opens the configuration at the top when no key line exists. If the detail is absent locally, clicking performs a single-detail fetch first. An incomplete, stale, partial, failed, or missing Namespace index must not hide that optimistic marker; only a fresh COMPLETE snapshot that proves the data ID absent may hide it.

### Refresh presentation

The gutter has three stable visual states and no animated or fourth loading marker. Blue means a fresh resolved key, amber means a stale resolved key with cache navigation available, and gray hollow means an actionable data-ID target whose key is unresolved. An in-flight refresh keeps the current marker. Success rebuilds the resolver, restarts daemon analysis, and lets the marker transition naturally; explicit not-found removes it; transient failure retains it and leaves manual refresh available while automatic PSI work observes the five-minute cooldown.
