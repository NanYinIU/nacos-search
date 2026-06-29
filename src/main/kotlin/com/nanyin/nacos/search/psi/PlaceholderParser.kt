package com.nanyin.nacos.search.psi

/**
 * Parses Nacos/Spring property placeholders of the form `${key}` or
 * `${key:default}` found inside `@NacosValue` / `@Value` annotation arguments.
 *
 * Pure, side-effect-free, no PSI dependency — unit tested directly.
 *
 * Grammar accepted:
 *  - `${key}`            -> key="key", default=null
 *  - `${key:default}`    -> key="key", default="default"
 *  - `${a:b:c}`          -> key="a", default="b:c"  (only the first ':' splits)
 *  - `${:x}`             -> null (empty key is not a valid reference target)
 *  - `${}`               -> null
 *  - `bare literal`      -> null (no placeholder wrapping)
 *  - `prefix${key}suffix`-> the first placeholder is returned (mixed literals
 *                           are rare in annotations; we take the first token)
 */
object PlaceholderParser {

    private const val PREFIX = "\${"
    private const val SUFFIX = "}"

    data class Placeholder(val key: String, val default: String?)

    /**
     * Extracts the first `${...}` placeholder from [text], or null when the
     * text contains no usable placeholder.
     */
    fun parse(text: String?): Placeholder? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf(PREFIX)
        if (start < 0) return null
        val end = text.indexOf(SUFFIX, start + PREFIX.length)
        if (end < 0) return null

        val inner = text.substring(start + PREFIX.length, end).trim()
        if (inner.isEmpty()) return null

        // Only the first ':' separates the key from its default; the default
        // value itself may contain colons (e.g. URLs / cron expressions).
        val colon = inner.indexOf(':')
        val key = if (colon >= 0) inner.substring(0, colon).trim() else inner.trim()
        if (key.isEmpty()) return null

        val default = if (colon >= 0) inner.substring(colon + 1) else null
        return Placeholder(key, default)
    }

    /**
     * True when [text] contains at least one `${...}` placeholder with a
     * non-empty key. Used to decide whether a literal expression is a
     * navigation candidate at all.
     */
    fun containsPlaceholder(text: String?): Boolean = parse(text) != null
}
