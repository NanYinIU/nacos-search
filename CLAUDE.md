# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is **Nacos Search**, an IntelliJ IDEA platform plugin that lets developers query Nacos configuration center data directly from the IDE. It is written in Kotlin and built with Gradle using the IntelliJ Platform Gradle Plugin.

- **Plugin ID**: `com.nanyin.nacos.search`
- **Version**: sourced from `build.gradle.kts` (currently `1.4.1`) — bump it there and update the `pluginDescription` / `changeNotes` blocks in `build.gradle.kts` (`patchPluginXml`) when releasing. Those are the single source; `META-INF/plugin.xml` does not duplicate them.
- **Target Platform**: IntelliJ IDEA Community Edition (`sinceBuild = 223`, no `untilBuild` — open-ended so Marketplace does not hide the plugin from newer IDEs)
- **JDK**: Java 17
- **Gradle**: 9.0.0
- **Kotlin**: 2.0.21
- **IntelliJ Platform Gradle Plugin**: 2.16.0

The plugin declares a right-side tool window (`Nacos Search`), a settings page under `Tools > Nacos Search`, two menu actions under `Tools` for refreshing/clearing the cache, and a Java PSI integration that provides `@NacosValue` gutter navigation and reverse Find Usages.

## Common Commands

Use the Gradle wrapper for all build operations. AI agents and automation **must invoke `./gradlew`, never the system `gradle` command**. The wrapper pins Gradle 9.0.0; the machine may also have another version installed (for example Homebrew Gradle 9.3.1), and invoking it directly builds with the wrong version and creates version-specific entries under `~/.gradle/caches/` and `~/.gradle/daemon/`.

```bash
# Build the installable plugin distribution
./gradlew buildPlugin

# Compile only
./gradlew compileKotlin compileTestKotlin

# Run a development instance of IntelliJ IDEA with the plugin loaded
./gradlew runIde

# Run plugin verification against the configured IDE version (2022.3.3)
./gradlew verifyPlugin

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.nanyin.nacos.search.services.NacosApiServiceTest"

# Run a single test method
./gradlew test --tests "com.nanyin.nacos.search.services.NacosApiServiceTest.test nacos service initialization"
```

### Stale Kotlin incremental state

After switching branches, syncing source, or changing the IntelliJ build target,
`compileKotlin` can report unresolved references even though the declarations
exist in the current source tree. Confirm the declarations are present before
changing their shape; valid top-level helpers do not need to be moved into named
types to repair compiler state.

Rebuild once from clean outputs, then verify that incremental and test-source
compilation both work:

```bash
./gradlew clean compileKotlin
./gradlew compileKotlin
./gradlew compileTestKotlin
```

After all three commands pass, retry `./gradlew runIde`.

The `build.gradle.kts` resolves the IntelliJ Platform from JetBrains repositories (`create("IC", "2024.3.5")`) rather than a local IDE installation. Make sure you have a network connection and enough disk space for the downloaded IDE artifacts. `IDEA_LOCAL_PATH` can optionally point `runIde` at a local IDE install.

All builds require **Java 17**. Set `JAVA_HOME` if your default JVM is older:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
```

Plugin signing is configured via the `intellijPlatform.signing` block and reads from `PRIVATE_KEY`, `CERTIFICATE_CHAIN`, and `PRIVATE_KEY_PASSWORD` environment variables. The `signPlugin` task is skipped automatically when these are not present. Publishing uses `PUBLISH_TOKEN`.

## High-Level Architecture

### Plugin Lifecycle

`NacosSearchPlugin` is an application-level `@Service`, a `StartupActivity` (registered via the `com.intellij.postStartupActivity` extension point), and a `Disposable`. On project startup it:

1. Validates `NacosSettings`.
2. Kicks off background cache loading via `CacheService` (does not block the EDT).
3. Tests the Nacos connection asynchronously.
4. Loads initial configuration metadata if the cache is empty or disabled, then warms the `NacosKeyIndexService` key index and preheats the full namespace index in the background so the first search and gutter markers are instant.
5. Schedules an automatic cache refresh job when enabled.

`RefreshCacheAction` / `ClearCacheAction` delegate to `NacosSearchPlugin.refreshCache()` / `clearCache()`. The `Disposable` implementation cancels the plugin's `CoroutineScope` and auto-refresh job on shutdown.

### Service Layer

Services are IntelliJ application- or project-level components registered with `@Service`. Important ones:

- `NacosApiService` — HTTP client for the Nacos Open API (`/nacos/v1/cs/configs`, `/nacos/v1/cs/config`, `/nacos/v1/console/namespaces`). It also maintains a short-lived in-memory cache of configuration responses per namespace and handles auth headers. Requests retry transient IO failures.
- `AuthenticationSessionRegistry` — Application-level owner of Nacos-password authentication sessions shared by the V1 and V3 protocol adapters. Completed tokens are keyed by the full access identity, while concurrent login flights also pin execution-policy inputs.
- `NacosSearchService` — Project-level search orchestrator. It **holds the session context** every search targets (see **One held search session** below), exposes search state and pagination state via Kotlin `StateFlow`, and takes intents rather than requests: `search`, `searchAsYouType`, `clearCriteria`, `reload`, `nextPage`, `previousPage`, `changePageSize`. Translates wildcard queries like `*config` into Nacos `blur`/`accurate` search modes.
- `CacheService` — Persistent local cache. See **Cache persistence** below.
- `NamespaceService` — Fetches Namespace discovery options for a supplied project operation context. Each `NacosProjectSession` owns its selected Namespace; discovery keeps options in the invoking project’s panel and publishes no application-wide selection events.
- `EditSessionService` — Project-level owner of the one configuration draft, and of publishing it. See **The edit session lives outside the tool window** below.
- `LanguageService` — Runtime language switching support for the plugin UI.

### Cache persistence (two caches, two layers)

There are **two** caches — do not confuse them:

1. `NacosApiService` keeps an in-memory response cache per namespace (5-minute TTL) to avoid repeated network round-trips within a session.
2. `CacheService` persists configurations across IDE restarts. It holds three `ConcurrentHashMap`s in memory — config details, list pages, and namespace indexes — each entry wrapped with TTL metadata.

The persistent layer sits behind one interface, `CacheStore`, injected into `CacheService` by constructor alongside the clock and the profile-deletion tombstone registry. The store owns **both** halves of the persistence — the entry payloads and the key list that names them — so the two cannot drift apart and leave a payload that no reclamation sweep can see. Two adapters implement it:

- `FileCacheStore` (production) writes one JSON file per entry under `…/nacos-search-cache/{details,listpages}/` beneath the IDE config path, named by the SHA-256 of the storage key. Each file **carries the key it belongs to**, so the key list is the set of payload files rather than a second record: a file that names no readable key is reclaimed on the next scan instead of being orphaned. This replaced a single multi-hundred-MB state XML and fixed slow startup. On first run after upgrade it adopts the previous release's records once, bounded by count (ADR-0018) — payload files whose key lived in `PropertiesComponent` under `nacos.cache.*` are rewritten to name their own key, and those properties are dropped.
- `InMemoryCacheStore` (tests) keeps the same behaviour in memory, so cache tests never share an on-disk directory or an application-properties instance. Both adapters are held to `CacheStoreContractTest`.

Cache loads run in the background; read methods await a `CompletableDeferred` load signal before serving results that depend on the full load, while single-key reads resolve from the store immediately. Enforced limits: max 1,000 entries, 5-minute default TTL, lock-free reads with background expiry reclamation.

#### One gated write entry point

`CacheService` has exactly **one** write method — `applyMutation(mutation: CacheMutation, observation: Long)` (ADR-0044 / ADR-0045). `CacheMutation` is a closed set: write a detail, write a list page, replace a namespace index, mark an index non-authoritative, delete a detail on an authoritative not-found, invalidate a namespace, and clear. Both the cache-entry gate and the profile-deletion tombstone check live inside `applyMutation`, so the gate's scope equals the mutation's coordinate by construction and a new mutation kind cannot be added without passing both. Do not add a second write method — six named writers each calling a private gate has already failed twice in this module. Reads stay named operations returning their own types; the collapse buys unbypassability, which reads do not need.

Both declarations carry the `@CacheWriteAccess` opt-in marker, so naming either one without `@OptIn(CacheWriteAccess::class)` is a **compile error** (ADR-0052). Opting in belongs to the operation layer, in two forms: components that perform remote operations and hold an observation sequence (`CacheServiceOperationCache`, `NamespaceIndexCoordinator`, `NavigationDetailPrefetchService`, `NacosApiService.clearCache`), and recorders that turn a completed operation's observation into a mutation on its caller's behalf (`ObservedDetailRecorder`). The cache module's own tests opt in too. It does not belong in `ui/` or `psi/` — those layers read the cache and never write to it; when they need a mutation they hand what they observed, with its sequence, to `ObservedDetailRecorder`.

`observation` is the sequence the operation took **when it started** (`ObservationSequence.process`, or `OperationGateway.beginObservation()`), never when it writes. A gateway read returns it alongside the payload as `Observed<T>` so painting a result and writing the cache entry derived from the same read are ordered by one number (ADR-0047). `ObservationHighWater` is the single gate implementation, keyed on the complete access identity plus the coordinate; a mutation must outrank every scope on its chain (global → namespace → coordinate) and raises only its own, and only once it has actually landed. `HistoryMemoryCache` holds one for the read-only history path.

#### One versioned snapshot instead of lent-out internals

Callers that cache a derivation of the cache take a `CacheSnapshot` (issue #64). It carries the two facts a derivation needs and nothing else: a `version` that advances when the cache's content changes — an accepted mutation advances it, one the gate drops does not — and an `asOfMillis` that every freshness judgement made from it is against, so one gutter-marker decision cannot straddle a freshness boundary. Taking one is O(1); its payload views are lazy. The cache lends out no modification counter, no clock accessor, and no coroutine scope, and no JVM identity hash serves as a generation token anywhere.

Visibility is the second serve-time rule (issue #126): `KeyIndex.visibilityCompatibleWith(snapshot)` checks both `isAccessBlocked` and `blockedNamespaces`, and `NacosKeyIndexService.currentIndex` refuses to serve the previous index under a different visibility signature — so a coalesced rebuild after a block never hands out a stale derivation as a temporary bypass.

The derived `@NacosValue` key index is `NacosKeyIndexService`, an application service owning its own `CoroutineScope` and `Disposable` lifecycle, so clearing the cache leaves no rebuild running in a scope that no longer makes sense. `NacosKeyResolver` beside it is pure: it derives a `KeyIndex` from a snapshot and ranks hits, holding no state and starting no work. `KeyDefinition` (what the index holds, timeless) is separate from `KeyHit` (judged at one instant) so an unjudged freshness cannot be read by accident. Key extraction stays in `psi/` — it is configuration-format knowledge, and moving it into the cache would make the cache module depend on the code-navigation layer.

Consequences worth knowing:

- Only a read that reaches the **server** takes a sequence. A read served from cache returns `Observed.NO_OBSERVATION`, which can never outrank any mark — so a caller that derives a write from a cache hit is silently dropped instead of restamping the entry it just read and locking out a genuine remote read that started earlier.
- A user's clear is a mutation. A read that started before it is dropped; one started afterwards lands, so clear-then-reload works. It also collapses the per-coordinate marks into the global one, which is what keeps the gate's map from growing for the life of the IDE.
- Reconstituting an entry from the cache's own store is **not** a mutation. It is private behind the read path, takes no lock (a go-to-declaration lookup must not queue behind the background full load), carries no sequence, and is discarded if a clear or invalidation ran while it was reading.
- A complete namespace index reclaims details it no longer lists, but only where no newer detail observation exists. Partial and failed indexes never delete — they only mark the index non-authoritative for absence.
- The tombstone is absolute: no observation sequence, however recent, outranks it.
- The visibility-block gate stays **outside** this module. It orders dataset confirmation states over access identity, capability and optional namespace, not cache entries; do not fold the confirmation-state machine in here.

#### One key space per environment

The resolved API generation is part of the access identity, so a caller that cannot name it addresses a key space of its own. For an `AUTO` profile the generation is not in the profile — it is resolved per operation — and the hot paths must not read a credential to find it (ADR-0039). `ResolvedGenerationLocator` supplies it from the two credential-free sources that already hold it (ADR-0053): the generation this project session resolved, then the persisted last-known value. `NacosSettings.captureAccessIdentity` and `Project.captureSelectedAccessIdentity` apply it, so search, the key-index warm, the detail panel, the clear gesture, and gutter markers all address what the gateway wrote. A locked `V1`/`V3` identity is returned untouched; with neither source the generation stays unknown and the read is a **miss** — do not write into that space to make it look otherwise.

### The edit session lives outside the tool window

Starting an edit creates a session held by `EditSessionService`, a **project-level** service in `services/operations/` (ADR-0046). It is not in the detail panel: ADR-0027 requires a dirty draft to block destroying the tool-window content, and a session that lives inside the thing it must block cannot block it. The panel renders the draft and reports what the user types; it holds no baseline, no dirty flag, and no publish target.

`EditSession` binds an `EditBinding` — profile id, access identity, canonical namespace, data id, group — plus the baseline content, hash and known metadata. It carries **no `OperationTarget` and no credential**. Publishing captures a fresh operation target from `binding.profileId` and `binding.namespaceId` and refuses when that target no longer resolves to the bound access identity, so a selection change after starting a draft cannot retarget where it publishes, and a secret captured minutes ago cannot be reused. Nothing here is persisted: P0 has no draft shelf and never writes configuration content to disk.

Two actions ask before destroying a draft, and each offers exactly two answers — cancel, or discard:

- Selecting another configuration — `NacosSearchWindow.admitRetarget` consults `guardRetarget`, and on cancel `ConfigListPanel.restoreSelection` puts the highlight back **without** re-firing the selection handler.
- Destroying the tool-window content — `NacosSearchToolWindowFactory` vetoes `contentRemoveQuery` via `guardDestroy`. The prompt lives in the factory because a component being disposed cannot veto its own disposal.

`DraftGuard` is a closed set: `Proceed`, `AlreadyEditing` (the view is already showing this draft, so the action must neither prompt nor reload/clear), `ConfirmDiscard`, `RefuseInFlight` (publishing/verifying — no ordinary discard), and `RequireWarnedAbandon` (server-state-unknown — only reconciliation, copy, or warned abandon). Asking never mutates — only `discardDraft()` / `abandonPublish()` throw work away. Every ADR-0027 blocking rule asks this service: retarget, clear (never prompts), destroy tool-window content, environment switch, namespace switch, write-intent off, profile deletion, and project close.

**Prompting is not the answer to every path that would destroy a draft.** Three of them keep the draft and work around it instead, because they are not gestures aimed at the edit:

- **Refresh all** reloads namespaces and the result list but leaves the detail view alone (`refreshAll` runs the same `admitRetarget`, which answers `AlreadyEditing`). Asking for fresh data is not asking to discard an edit.
- **An empty result list**, or losing the namespace selection, does not clear the detail view (`guardClear`, in `updateConfigurationList` and `clearSearchUi`). An empty *filter* says nothing about whether the edited configuration exists, and search is debounced — prompting would raise a dialog mid-keystroke. `guardClear` therefore never returns `ConfirmDiscard`.
- **The detail toolbar's Refresh** is disabled while the draft is dirty. It would do exactly what Revert does, and two buttons for one destructive act is how work gets lost by accident.

A draft that survives stays publishable: its target comes from its binding, not from the list.

`WriteIntent.of(profile)` is the one implementation of the ADR-0026 publish opt-in. The edit action consults it (`beginEdit` refuses with `EditStart.WritesWithheld` rather than letting the user type), and the save path consults the same value bound on the session, so the two cannot disagree. Do not add a second write-intent check.

### `@NacosValue` Navigation & PSI Subsystem

The `psi/` package (registered in `plugin.xml` for `language="JAVA"`) is the plugin's code-intelligence layer. It reads only from `CacheService`, so gutter markers and navigation are only as accurate as the locally cached configs. Components:

- `PlaceholderParser` — parses `${...}` placeholders out of string literals in `@NacosValue` / `@Value` annotations.
- `NacosValueReferenceContributor` + `NacosValueReference` — contribute PSI references powering go-to-declaration.
- `NacosValueLineMarkerProvider` — renders a four-state gutter icon: fresh resolved (blue solid), stale resolved (amber solid with a clock), unresolved (gray hollow), or 格式不参与解析 (gray, **no jump arrow**). Shown only when the key is cached or a dataId context allows remote fallback; only a fresh, complete namespace index may prove that a dataId is absent, and only a body **within its TTL** may prove a key absent from itself — past it the miss is 不可判定, not 未解析, because the body says nothing about a key added since it was read (ADR-0057). A marker that cannot decide — or that resolved against a body which has since aged out — refreshes **one 配置坐标 and nothing else** (`requestCoordinate`, one attempt per coordinate per TTL, claimed before the read so a pass per keystroke is not a request per keystroke). It names that coordinate from its own **top key hit** when it has one, so a placeholder resolved by discovery with no `@NacosPropertySource` still refreshes narrowly; otherwise from what its site declared. `STALE.mayRequestBackgroundRefresh()` is **true** for that reason (issue #145's refusal was about the sweep, not about the read), `RESOLVED` stays false. Only a marker that can name no coordinate at all falls back to `NamespaceIndexRefreshService.requestIfNeeded`, which is now **cold-start-only**: a Namespace already indexed is never re-paged from a gutter pass, however aged — re-paging belongs to manual refresh, Namespace switch and search. Do not restore a TTL-based re-page here; that is the five-minute full re-scan ADR-0057 removed. Clicking navigates, and multiple namespace matches open a chooser — except in the terminal state, which is handed no navigation handler at all, because a click that fetched the body anyway would reopen the leak that state closes. It is the one state with two tooltips and one icon: the user's next action differs between a suffix the plugin reads no keys from and a data id that names no format at all, but the outcome does not, and four gray variants would be indistinguishable at gutter size. The tooltip says "the plugin reads no keys from this format", never "no runtime parses it" — xml reaches this state too, and both runtimes do parse xml. Only a key hit and a visibility block outrank the conclusion; presence does not, because whether the configuration exists changes nothing about whether a placeholder could resolve against it.
- `NacosConfigKeyReferenceSearcher` — reverse **Find Usages**: from a Nacos config key to the Java usages. Honors the search cancel signal.
- `NacosPlaceholderIndex` — a `FileBasedIndex` mapping placeholder keys → `.java` files, for fast reverse lookup without scanning the project.
- `NacosKeyIndexService` — application service holding the one derived `KeyIndex` and the scope its rebuilds run in. Rebuilds lazily and off the calling thread when the snapshot's version moves; serves the previous index for the same access identity meanwhile only when its visibility signature matches the snapshot, and never one built for a different identity.
- `NacosKeyResolver` — pure derivation and ranking beside it: builds a `KeyIndex` from a `CacheSnapshot`, resolves a placeholder key against one, and orders hits by namespace relevance (active namespace > public > others). Holds no state and starts no work.
- `ConfigKeyExtractor` — key + `KeyLocation` extraction from configuration content, read under an explicitly supplied **运行时格式** (`RuntimeConfigFormat`). It derives no format of its own: the same body is a different key space under different rules, and only the caller knows which rules the runtime will apply. Its result is a closed set (`KeyExtraction`) — a parse, or a refusal carrying its reason — because a caller that flattens the two turns "we do not parse this format" into "this key is absent". That is the same invariant as the one above about data ids: only a successful parse under the 运行时格式 may prove a key absent, so do not add a `keysOrEmpty()` to it.

  Structure always comes from a parser, never from a hand-written scanner. Both parsers are declared dependencies bundled into the distribution as standalone jars under `lib/`, not platform libraries: the target IDE ships none of them that way (2022.3.3 repackages its own snakeyaml into `app.jar`), their versions and packaging changed between supported releases, and the platform documentation forbids depending on them in production. A self-bundled jar behaves identically across supported builds, and the plugin classloader resolves its own jars first, so the platform's copy is not involved.

  - **JSON** — the Gson `JsonReader` (`implementation("com.google.code.gson:gson")`). It supplies no line number, so the key set and the line are derived separately: a locator consumes key positions in document order and reports `LINE_NOT_FOUND` rather than a wrong line, so a positioning defect can never corrupt the key space.
  - **YAML** — snakeyaml (`implementation("org.yaml:snakeyaml")`), the library Spring Boot's own YAML property source loader uses, so the plugin and the runtime cannot disagree about YAML syntax. `composeAll`, not `load`: composing builds the node tree the walk reads, every document in a stream contributes, and each node's mark supplies the line directly — so YAML needs no locator. The one thing constructed out of the body is a mapping **key**, and only ever by a `SafeConstructor`, the family Spring's own loader extends (ADR-0055 / #178). Spring's flattening brackets any key that is not a `CharSequence` and spells it from the key's *resolved* value, so `on:` is `[true]` and `0x10:` is `[16]` — keys the document spelling cannot name. Such a key contributes the bracket form **alone**: the dot alias is a widening made for sequence indexes, and no runtime resolves any dot spelling of a mapping key. A key whose tag `SafeConstructor` refuses, or that resolves to null, contributes nothing rather than falling back to its document spelling, because the runtime fails the whole load over such a key and a fallback name is a marker promising a placeholder that fails. Composing also drops snakeyaml's implicit timestamp tag exactly as Spring's loader does, so a date-shaped key stays a string instead of becoming a `Date` whose bracketed `toString` would depend on the reading JVM's time zone. What remains ours is only the derivation of a key path from the node tree. That is why a mapping's `<<` merges are resolved into its entry set *before* the walk — `<<` is not a path segment, and resolving it by letting a later leaf overwrite an earlier one would get shallow merge wrong (a mapping's own `db: {port}` discards a merged `db` outright; a per-leaf overwrite would keep the merged `db.url` underneath it and invent a key). And it is why an alias that reaches a mapping containing it stops instead of walking a cyclic node graph forever.

  Both share one `PathSeg` and one key renderer, so a leaf is addressed identically whichever format it came from — the bracket form (`list[0].n`) and the dot form (`list.0.n`), so either placeholder style resolves. A path holding a bracketed YAML mapping key is the exception, and loses the dot spelling for the whole key rather than for that one segment: a half-aliased `a.0[0]` is a key nothing resolves either. A container **with contents** contributes no key of its own: a runtime property source flattens to leaves, and `${list}` resolves against none of them. An **empty** container is where the two formats legitimately part company, and each follows the loader that reads it (ADR-0056): Spring Boot's YAML flattening terminates on one, so `list: []` contributes `list`, while Spring Cloud Alibaba's JSON loader drops it, so `{"list":[]}` contributes nothing. Do not make the two agree — the differential oracle holds both directions and the build will say so.
  - `keysUnderRuntimeFormat` is what a caller **holding a body** asks, and `runtimeFormatRefusal` is what a caller holding only a data id asks. Both read one table (`ConfigKeyExtractor.refusalFor` and `extract` share `handlingOf`), so a format cannot be refused to one and parsed for the other. The refusal is what makes the terminal state reachable without a body, and reaching it without one is the point: whether the body is cached, listed, or never fetched changes nothing about whether a placeholder could resolve, and waiting for it is what kept the sweeps coming (#172). Both also take the `ReferenceFormatOverride` a reference site raised, so the format a site declared decides the keys *and* the refusal — which is how a data id with no recognizable suffix stops reaching the terminal state once a site declares a type (#173). `keysUnderRuntimeFormat` still flattens a refusal to an empty map, deliberately — every caller holds a body and none turns the empty map into an absence claim: the 派生 key 索引 contributes nothing, the detail panel's own gutter markers have no lines to mark, and the marker's lazy-load navigation is asking only where to put the caret and already degrades to `LINE_NOT_FOUND`.

  Correctness is measured against the runtime's own parsers rather than against cases we thought of, at three oracles on the one pure seam (`ConfigKeyExtractor.extract`, no platform fixture):

  - **Enumerated** — `ConfigKeyExtractorTest`, one case per known behaviour. Necessary and demonstrably not sufficient: it was entirely green while JSON dropped every key after an array ending in a string, YAML merged a multi-document stream to one, and a sequence of nested mappings overwrote its own first element.
  - **Differential** — `ExtractionDifferentialTest` runs the runtime's own `PropertySourceLoader` implementations over `ExtractionCorpus` and asserts the plugin's key set equals theirs: Spring Boot's `PropertiesPropertySourceLoader` and `YamlPropertySourceLoader`, and Spring Cloud Alibaba's `NacosJsonPropertySourceLoader` (Spring Boot ships no JSON loader, and this is the one a Nacos JSON configuration is read by). All three are `testImplementation` only. The relation is two-sided rather than normalized — nothing the runtime resolves is lost, and nothing beyond the plugin's deliberate dot-form alias is invented — because normalizing both sides is how a real difference gets masked. A difference is declared key-for-key as a `Divergence` on its corpus entry and asserted exactly, so one documented difference cannot become a hiding place for an undiscovered defect. A `Divergence` states which of two things it is — `INTENDED`, or `KNOWN_DEFECT`: a defect this oracle found that its slice did not fix, pinned so the exact difference stays on record and required to name its tracking issue, because calling a defect intentional is how it stops being fixed. `RuntimeKeyOracle.IN_FORCE` states which oracle is running; #170 permits a weaker checked-in captured corpus only if the loaders cannot run, and the spike recorded on that issue established that they can.
  - **Invariant** — `ExtractionInvariantTest` over bodies generated from a known structure (`GeneratedBody`), with adversarial scalars that read like declarations. Every extracted key must name a leaf the body declares, which kills the phantom-key class rather than its known members; and the extracted key count must not depend on where in the document a value sits — asserted by reordering every mapping and sequence at every depth, since a value that swallows what follows it does so wherever it sits — which kills the truncation class. Seeds are fixed so a failure reproduces from the test name. Guards in the same class keep it from passing vacuously — the name alphabet is asserted disjoint from the value pool, the generated bodies are asserted to contain block scalars / continuations / arrays, and every case is asserted to extract something.
- `RuntimeFormatDecision` — the **运行时格式** decision table, and the only thing that decides which `RuntimeConfigFormat` a configuration is read under. Its first row is the type declared at the reference site (`forReference`), which `nacos-spring-context` reads before anything else; every other row is the data id suffix, case-folded as both runtimes fold it. The configuration's **声明格式** (`NacosConfiguration.declaredFormat()`, display-only — see **UI Layer**) participates in no row, because no runtime reads that field (ADR-0056). Its one deliberate departure from the runtime is the last row: an unrecognized suffix with no declaration is `UNDETERMINED` and extracts nothing, where the runtime would fall back to properties — reading a YAML body under properties rules yields a well-formed `name` that a real `${name}` collides with, so following the runtime there manufactures false navigation rather than harmless noise. Do not "fix" it back, and do not add a second departure: a refusal the runtime would not make costs navigation that would have worked. That is also why only the four parseable formats are read off a declaration — a declared `TEXT`/`HTML`/`TOML` falls through to the suffix rows, because nothing has verified that either runtime refuses to parse under one (`nacos-spring-context` registers no parser for them and is understood to fall back to properties), and falling through can only widen what resolves. It is its own unit with its own test so a row can change without silently changing another, and so the table is verified without a configuration body.

  The declaration **cannot enter the 派生 key 索引**: the index is built once per 缓存快照 over every cached configuration and holds no code context, and the same configuration may need extracting two ways for two sites that declare different types. So the index stays built from what a configuration decides about itself, and a declaration becomes a `ReferenceFormatOverride` resolved per reference against the coordinate it names. That coordinate is as narrow as the site wrote it — the data id always, plus the group and Namespace only where declared — and `ReferenceFormatOverride.appliesTo` is the one place the question is answered. Definitions it matches are *replaced*, not added to; everything it does not match keeps what the index holds, including another copy of the same data id in a group or Namespace the site said nothing about, so a declaration that finds nothing under that Data ID is a hard miss (no soft-fall to another configuration; unresolved with no gutter icon when the body is cached without the key — issue #192 hard-filter). It is computed on demand with **no cache** (add one keyed by data id plus 运行时格式 only if it ever measures as a cost), and it reads the cache and writes nothing, so it takes no observation sequence and needs none (ADR-0052). A declaration that agrees with the data id raises no override at all, which is what keeps the ordinary gutter pass off it.
- Supporting: `NacosConfigNavigator` (navigate to the config detail panel), `NacosConfigKeyElement` (PSI element carrying the source element for lazy-load), `NacosPopupChoiceItems` / `NacosUsagePresentation` (popup + Find Usages presentation).

### Authentication Modes

`AuthMode` (in `settings/NacosSettings.kt`) controls how requests are signed:

- `TOKEN` — Login via `/nacos/v1/auth/login` and append `accessToken` as a query parameter.
- `BASIC` — Send an `Authorization: Basic ...` header with the configured username/password.
- `HYBRID` — Try token auth first; fall back to Basic auth if no token is available.

Login form credentials are URL-encoded by the protocol adapters. Nacos-password tokens are cached in the application `AuthenticationSessionRegistry` and refreshed before expiration.

### Multi-Server Environments & Credentials

`NacosSettings` persists one application-wide environment model: `EnvironmentProfile` records plus profile-associated `EnvironmentPreferences`. The Settings dialog edits `ProfileIntent` rows in a `SettingsIntentDraft`; only `EnvironmentProfileStore` publishes them and derives revisions and credential-slot coordinates. `EnvironmentSwitcher` changes the project-local `NacosProjectSession`, not an application-wide active environment.

`servers`, `activeServerId`, and the flat legacy fields on `NacosSettings` (`serverUrl`, `username`, `password`, `namespace`, `authMode`, `enableTokenAuth`) remain public mutable beans only for `XmlSerializer` compatibility. A pre-CURRENT versioned migration may read them and then clears them; CURRENT state ignores stale values, and `getState()` omits them. Runtime, Settings, reset, and selection paths must not read or write them.

Passwords are **not** stored in the settings XML. Current credentials live in immutable, revision-addressed slots in IntelliJ `PasswordSafe`; legacy plaintext and server-id PasswordSafe entries are migration inputs only. Stage a new slot before publishing its profile revision, and never fall back from a missing published slot to legacy storage.

### Settings & Persistence

`NacosSettings` is a `PersistentStateComponent` stored in `nacos-search.xml`. It contains published profiles, environment preferences, the migration-owned project seed, cache settings, and language. `NacosConfigurable` provides the Swing UI for `Settings/Preferences > Tools > Nacos Search` and fires `NacosSettingsListener` events so the tool window reacts to preference-only changes without a full cache reload. Opening, cancelling, or resetting the dialog changes only its intent draft until Apply. Apply publishes intents without rewriting the migration seed; when opened from a project it may realign that project's 项目会话 to the blue-dot (issue #107).

### UI Layer

The tool window is registered in `META-INF/plugin.xml` and created by `NacosSearchToolWindowFactory`, which builds a `NacosSearchWindow`. Major panels:

- `EnvironmentSwitcher` — active-environment switcher in the header.
- `SearchPanel` — search input and options.
- `NamespacePanel` — namespace selector.
- `ConfigListPanel` — list of search results.
- `ConfigDetailPanel` — details of the selected configuration.
- `PaginationPanel` — page navigation.

Selecting a configuration — from the list or from a `@NacosValue` jump — paints what the cache holds and then decides whether to confirm it, in `DetailController.planSelection` and nowhere else. Fresh confirms quietly; **past its TTL it confirms too** (ADR-0057), because painting an aged body and stopping is how the gutter icon beside it turned blue off an unrelated sweep while the content on screen had never been re-read. It confirms one 配置坐标, never a Namespace, and does not force: the gateway's detail cache already treats a stale entry as a miss.

Every format a panel *displays* is the **声明格式**, `NacosConfiguration.declaredFormat()` — one derivation read by all three surfaces (the list badge, the detail panel's format tag and its inline metadata), so the panel cannot print two different format names for one configuration. It must never reach key extraction, which asks `RuntimeFormatDecision` instead (ADR-0056); a badge saying TEXT while the gutter extracts properties is two true answers, not a contradiction to reconcile. Its data-id fallback when the server returns no type is a display convenience and deliberately does **not** share the 运行时格式 table — sharing would let a change to extraction silently move badges. Syntax highlighting (`ConfigDetailPanel.determineFileType`) keeps its own wider vocabulary: it knows sql and html, which will never contribute a key.

Each panel subscribes to `NacosLanguageListener.TOPIC` itself and re-reads the bundle, so labels update when the plugin language is changed via `LanguageService`. There is no fan-out from the window: a component that forgets to subscribe simply does not exist on the topic, which is what the previous manual dispatch (and the `EnvironmentSwitcher` it silently skipped) could not express. Each subscriber anchors its connection on its own `Disposable`, and `NacosSearchWindow` registers all six with `Disposer`.

#### One held search session

The window's search orchestration lives in `ToolWindowSearchController`, which holds no Swing. It does two things: it hands `NacosSearchService` the `SearchSessionContext` to search under whenever the project session changes — capturing the operation context through a function the window supplies on `Dispatchers.IO`, so no Swing handler ever reaches the credential store (ADR-0039 / ADR-0046) — and it turns panel gestures into the service's intents.

The window itself assembles **no** search request. It used to build one in eight places, each deciding again which profile, namespace, and operation context the search targeted; naming a `SearchRequest` or the request-taking `performSearch` now requires `@OptIn(SearchRequestAssembly::class)`, so a panel that tries fails to compile and the eight sites cannot come back one handler at a time. Opting in belongs to the search service and to tests that assert what a request derives.

Consequences worth knowing:

- A search runs against the session held **when it started**. Adopting a different target advances a session generation, so a result from before the switch is dropped rather than attributed to the environment the user moved to. Re-capturing the *same* environment (a fresh credential snapshot, unchanged identity and revisions) is not a switch and leaves the results standing.
- The window does not re-judge what the service publishes. The service already drops superseded requests and superseded sessions, so `SearchState.Success` carries its session epoch and observation sequence as a description of the read, not as something a view has to gate on. The `PresentationGate` still governs the detail view and the history dialog, which read outside the search service.
- Page size is search state, not a widget's state: `changePageSize` publishes it before searching, and every later intent asks for it. `PaginationPanel` renders the published pagination state.

### Internationalization

Message bundles live in `src/main/resources/messages/`:

- `NacosSearchBundle.properties` (English)
- `NacosSearchBundle_zh_CN.properties` (Chinese)

`NacosSearchBundle` (under the `bundle` package) is the `AbstractBundle` accessor used throughout the UI. The Chinese bundle is UTF-8 encoded.

### Actions

Two actions are registered in `plugin.xml` and added to the `ToolsMenu`:

- `RefreshCacheAction` — triggers a cache refresh through `NacosSearchPlugin`.
- `ClearCacheAction` — clears both the persistent cache and the API in-memory cache.

## Important Implementation Details

- `NacosApiService.getConfigurationFromItem()` fetches full configuration content for each item returned by `listConfigurations`. `getAllConfigurations` now fetches these per-item contents concurrently (bounded at 8) rather than sequentially, but loading a large namespace still issues many HTTP calls.
- `NacosSearchService.SearchRequest.isFuzzySearch()` treats `*` and `?` as wildcards. A leading `*` is stripped before calling the Nacos API in `getProcessedDataId()`. Naming a `SearchRequest` requires `@OptIn(SearchRequestAssembly::class)` — see **One held search session**.
- Settings UI is built with **Kotlin UI DSL Version 2** (`com.intellij.ui.dsl.builder.panel`). If you modify `settings/NacosConfigurable.kt`, avoid the deprecated `com.intellij.ui.layout` DSL and `titledRow`.
- The plugin targets **2022.3.3** as the build platform with `sinceBuild = 223` and no `untilBuild`, so Marketplace offers it to later IDEs. Keep API usage limited to what's available in build 223 if you want broad compatibility.
- Searchable-options generation is disabled because IntelliJ Platform Gradle Plugin 2.16 refuses that task below build 233; this keeps `buildPlugin` and `verifyPlugin` executable against the build 223 baseline.
- Startup uses **`StartupActivity`** with a `postStartupActivity` extension in `plugin.xml` so the entry point remains available on the minimum supported build 223.
- Tool window icons are SVGs under `src/main/resources/icons/` (`nacosSearch.svg`, `nacosSearch_dark.svg`, `nacosSearch_20.svg`, `nacosSearch_20_dark.svg`). `plugin.xml` references `/icons/nacosSearch_20.svg`; IntelliJ automatically picks the `_dark` variant in dark themes.
- The `.claude/settings.local.json` already allows `./gradlew:*` commands, so Gradle tasks should run without permission prompts.
- Some UI tests in `NamespacePanelTest` have pre-existing timing/EDT issues and may fail under the newer test framework; they are not indicative of plugin runtime behavior.

## Agent skills

### Codegraph

Prefer codegraph for symbol, call-path, and blast-radius retrieval. Before the first query in a session, **gate readiness** for **this** workspace root (each git worktree has its own `.codegraph/`): `codegraph init` when missing, `codegraph sync` when present, full `codegraph index` only if the index is broken. Always pass that root as `projectPath`. See `docs/agents/codegraph.md`.

### Issue tracker

Issues and PRDs are tracked in GitHub Issues; external PRs are also a triage surface. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the default five-role GitHub label vocabulary. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository using root `CONTEXT.md` and `docs/adr/`. See `docs/agents/domain.md`.
