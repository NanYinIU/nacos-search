package com.nanyin.nacos.search.psi

/**
 * 运行时格式 — the format the runtime will read a configuration body as.
 *
 * This is not 声明格式, the type the user picked in the Nacos console: that one
 * is a display fact and no runtime consults it. This one is the only input to
 * key extraction, because a gutter marker asserts that a placeholder resolves
 * at runtime, not that the body looks like a key list.
 *
 * The set is closed and names every format the plugin can be handed, including
 * the ones no runtime parses. Naming them is what lets extraction answer "this
 * format contributes no keys" instead of "this configuration has no keys" —
 * two answers a caller must never confuse ([KeyExtraction]).
 *
 * Who decides which member applies is deliberately not here. In this slice the
 * callers still derive it from the configuration's declared type through
 * [ofTypeName]; the decision table that replaces that derivation (reference-site
 * declaration, then data id suffix) lands separately.
 */
enum class RuntimeConfigFormat {
    /** Parsed by both Spring Cloud Alibaba and `nacos-spring-context`. */
    PROPERTIES,
    YAML,
    JSON,
    XML,

    /** Read as an opaque body by every runtime — no placeholder resolves against it. */
    TEXT,
    HTML,
    TOML,

    /** Nothing available names a format. */
    UNDETERMINED;

    companion object {
        /**
         * Maps a Nacos type name onto the closed set. An unrecognized or absent
         * name is [UNDETERMINED] rather than a guess: extraction under the wrong
         * rules invents keys that fail at runtime.
         */
        fun ofTypeName(typeName: String?): RuntimeConfigFormat =
            when (typeName?.trim()?.lowercase()) {
                "properties" -> PROPERTIES
                "yaml", "yml" -> YAML
                "json" -> JSON
                "xml" -> XML
                "text", "txt" -> TEXT
                "html", "htm" -> HTML
                "toml" -> TOML
                else -> UNDETERMINED
            }
    }
}
