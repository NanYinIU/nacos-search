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
 * | data id                                              | type declared at the reference site | 运行时格式                        |
 * | ---------------------------------------------------- | ----------------------------------- | -------------------------------- |
 * | any                                                  | `properties` `yaml` `json` `xml`    | that format                      |
 * | `*.properties` `*.yaml` `*.yml` `*.json` `*.xml`     | absent or `UNSET`                   | from the suffix                  |
 * | `*.txt` `*.text` `*.html` `*.htm` `*.toml`           | absent or `UNSET`                   | a known non-parsing format       |
 * | no recognizable suffix                               | absent or `UNSET`                   | [RuntimeConfigFormat.UNDETERMINED] |
 *
 * The first row needs a code context, which a data id alone does not carry, so
 * it is reached through [forReference] only — an override resolved per
 * reference against the one configuration coordinate the site names, never
 * something the 派生 key 索引 can hold (#173).
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

    /**
     * The 运行时格式 for [dataId] at a reference site that declares
     * [declaredType] — `@NacosPropertySource(type = ConfigType.JSON)`.
     *
     * `nacos-spring-context` reads that declaration before anything else and
     * falls back to the data id suffix only when it is unset, so under
     * ADR-0055's authority the declaration takes the table's first row.
     *
     * It is also the one thing that rescues a data id with no recognizable
     * suffix from [RuntimeConfigFormat.UNDETERMINED], which is the whole reason
     * a developer would write one.
     *
     * Only the four formats a runtime parses are read off a declaration. A
     * declared `TEXT` / `HTML` / `TOML` falls through to the suffix rows
     * instead, and deliberately: the plugin has not verified that either
     * runtime refuses to parse under one — `nacos-spring-context` has no parser
     * registered for them and is understood to fall back to properties — and
     * this table may not be more conservative than the runtime anywhere but its
     * one recorded departure (ADR-0055). Falling through can only widen what
     * resolves, so it cannot cost navigation that would have worked. If the
     * runtime is ever shown to honour such a declaration strictly, this is the
     * row to change, and it will be a change of fact rather than of taste.
     */
    fun forReference(dataId: String, declaredType: String?): RuntimeConfigFormat =
        // `UNSET` is the annotation attribute's own default, so it and an
        // absent declaration are the same answer: this site declares nothing.
        // So is a name no `ConfigType` carries — PSI hands back whatever is
        // written, and reading it as a refusal would cost navigation the suffix
        // already earns.
        when (declaredType?.trim()?.lowercase()) {
            "properties" -> RuntimeConfigFormat.PROPERTIES
            "yaml" -> RuntimeConfigFormat.YAML
            "json" -> RuntimeConfigFormat.JSON
            "xml" -> RuntimeConfigFormat.XML
            else -> forDataId(dataId)
        }
}
