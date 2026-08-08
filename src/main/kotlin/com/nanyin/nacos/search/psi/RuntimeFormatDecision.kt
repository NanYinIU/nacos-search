package com.nanyin.nacos.search.psi

/**
 * Decides a configuration's 运行时格式 — the rules the runtime will read its
 * body under, and therefore the only input key extraction may take.
 *
 * The configuration's 声明格式 participates in no row, because no runtime reads
 * it: Spring Cloud Alibaba takes the extension from its file-extension setting
 * or the data id suffix, `nacos-spring-context` from the type declared at the
 * reference site and then the data id suffix, and Nacos 1.4.x blur search does
 * not even return the field. Where the plugin's judgement and the runtime's
 * would differ, the runtime wins (ADR-0055).
 *
 * The table, in resolution order:
 *
 * | data id                                              | 运行时格式                        |
 * | ---------------------------------------------------- | -------------------------------- |
 * | `*.properties` `*.yaml` `*.yml` `*.json` `*.xml`     | from the suffix                  |
 * | `*.txt` `*.text` `*.html` `*.htm` `*.toml`           | a known non-parsing format       |
 * | no recognizable suffix                               | [RuntimeConfigFormat.UNDETERMINED] |
 *
 * The reference-site declaration (`@NacosPropertySource(type = ...)`) takes a
 * row above these when it lands; it needs a code context, which a data id
 * alone does not carry, so it is an override resolved per reference rather
 * than something the 派生 key 索引 can hold.
 *
 * Its own unit with its own test so that changing one row cannot silently
 * change another, and pure so that the table can be verified without a body.
 */
object RuntimeFormatDecision {

    /**
     * The 运行时格式 [dataId] decides about itself.
     *
     * The last row departs from strict runtime fidelity on purpose, and the
     * departure is the decision rather than an oversight. The runtime falls
     * back to properties for an unrecognized suffix, but reading a YAML body
     * under properties rules yields `name` from a line like `  name: svc` — a
     * well-formed key a real `${name}` placeholder collides with. Following the
     * runtime here manufactures false navigation instead of harmless noise, and
     * since no placeholder would have a true hit either way, refusing loses
     * nothing observable. Do not "fix" this back.
     *
     * It is also the *only* departure. Nothing else here may be more
     * conservative than the runtime: a refusal the runtime would not make
     * costs the user navigation that would have worked.
     */
    fun forDataId(dataId: String): RuntimeConfigFormat =
        // Case-folded because both runtimes fold: Spring Cloud Alibaba matches
        // a loader's extensions with `endsWithIgnoreCase`, and
        // `nacos-spring-context` upper-cases the suffix before looking it up in
        // its file-type enum. `App.YAML` is YAML to both of them, so it is YAML
        // here.
        when (dataId.substringAfterLast('.', "").lowercase()) {
            "properties" -> RuntimeConfigFormat.PROPERTIES
            "yaml", "yml" -> RuntimeConfigFormat.YAML
            "json" -> RuntimeConfigFormat.JSON
            "xml" -> RuntimeConfigFormat.XML
            "txt", "text" -> RuntimeConfigFormat.TEXT
            "html", "htm" -> RuntimeConfigFormat.HTML
            "toml" -> RuntimeConfigFormat.TOML
            else -> RuntimeConfigFormat.UNDETERMINED
        }
}
