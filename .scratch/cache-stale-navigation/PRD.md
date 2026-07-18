# Stale-while-revalidate cache navigation

## Goal

Keep Nacos code-navigation targets usable after their configured TTL while accurately communicating freshness, and refresh remote state without blocking IntelliJ gutter analysis.

## Requirements

1. Full Namespace loads use the TTL captured from the active settings.
2. Configuration details survive TTL expiry and IDE restart. Expiry changes them from fresh to stale; it does not remove navigation.
3. Seven days is the deep-stale threshold, not a retention limit. Deep-stale details remain navigable, render cached content immediately, and force an asynchronous single-detail refresh after opening.
4. Namespace indexes remain memory-only. Restored detail subsets cannot prove a configuration absent.
5. Gutter presentation has three stable states:
   - blue solid: fresh key resolved;
   - amber solid with clock: stale key resolved and cache navigation available;
   - gray hollow: data ID is actionable but the key is unresolved.
6. A gray marker remains navigable. It opens the configuration at the top when the key has no matching line and fetches the single detail first when it is absent locally.
7. Only a fresh COMPLETE Namespace result may prove a data ID absent and hide its marker. PARTIAL, FAILED, stale, restored, and missing indexes are non-authoritative for absence.
8. Automatic refresh is event-driven from startup cache completion, Namespace switching, stale gutter observation, and full-index search. It never blocks PSI and uses the existing access-identity + Namespace single-flight. Manual refresh always forces a request.
9. Refresh writes follow these rules:
   - COMPLETE atomically replaces the captured identity + Namespace snapshot and removes authoritative absences;
   - PARTIAL upserts successful details with the captured TTL and preserves every other target;
   - FAILED changes neither cached details nor the last successful refresh time;
   - explicit single-detail not-found removes that navigation target, while transient errors retain it.
10. Refreshing does not add an animated or fourth gutter state. Successful writes rebuild the resolver and restart daemon analysis; transient failures retain the current marker and observe the PSI cooldown.

## Test seams

- `CacheService`: detail freshness, persistence, cleanup/eviction, and atomic Namespace write behavior.
- `NacosKeyResolver`: resolution/freshness state and authoritative absence behavior.
- `NamespaceIndexRequester` / coordinator: single-flight triggers and COMPLETE/PARTIAL/FAILED writes.
- Gutter/detail UI public behavior: marker state, navigation fallback, cached-first display, and asynchronous single-detail refresh.

## Acceptance criteria

- A gutter marker does not disappear merely because its detail TTL elapses.
- A stale cached key remains navigable and is visually distinct from a fresh key.
- A detail older than seven days opens immediately from cache and then requests fresh content without blocking the UI.
- Restarting the IDE restores expired details as cache navigation targets.
- Partial or failed refreshes never erase unrelated stale targets.
- A complete refresh removes configurations deleted remotely.
- All targeted tests, Kotlin compilation, and the full Gradle test suite pass.
