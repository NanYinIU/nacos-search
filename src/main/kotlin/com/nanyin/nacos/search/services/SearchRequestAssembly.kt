package com.nanyin.nacos.search.services

/**
 * Marks assembling a search request — [NacosSearchService.SearchRequest] and the
 * request-taking [NacosSearchService.performSearch] — as reachable only from the
 * search service that holds the session context, and from the tests that drive
 * a request shape directly.
 *
 * The tool window used to assemble one in eight places, each deciding again
 * which profile, namespace, and operation context the search targeted, and
 * seven of them from a Swing handler. "The window expresses intent and never
 * names a target" is an architecture-fitness property rather than a behaviour,
 * so the compiler enforces it: a panel that names either declaration fails to
 * compile, and the eight sites cannot quietly come back one handler at a time.
 *
 * Opting in is deliberate and greppable. It belongs to the service that holds
 * the session context — it is the only component that knows the environment a
 * request should name — and to the tests that assert what a request derives.
 *
 * Recorded as ADR-0054; the same instrument as [CacheWriteAccess] (ADR-0052).
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "A search request names the environment it targets, which only the search service " +
        "holds. UI code expresses intent — search this, next page, this page size — and renders " +
        "what the service publishes."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SearchRequestAssembly
