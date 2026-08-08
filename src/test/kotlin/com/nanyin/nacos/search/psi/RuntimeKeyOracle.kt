package com.nanyin.nacos.search.psi

import com.alibaba.cloud.nacos.parser.NacosByteArrayResource
import com.alibaba.cloud.nacos.parser.NacosJsonPropertySourceLoader
import org.springframework.boot.env.PropertiesPropertySourceLoader
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.io.ByteArrayResource

/**
 * The differential oracle: the key space the **runtime** resolves from a body.
 *
 * #166 gives the runtime the last word — a gutter marker asserts that a
 * placeholder resolves at runtime, not that the body looks like a key list. That
 * authority is a design principle until something executes it, which is what
 * this is for. It runs the runtime's own `PropertySourceLoader` implementations,
 * not a re-description of what we believe they do:
 *
 * | 运行时格式 | loader | artifact |
 * | --- | --- | --- |
 * | properties | [PropertiesPropertySourceLoader] | `org.springframework.boot:spring-boot` |
 * | yaml | [YamlPropertySourceLoader] | same |
 * | json | [NacosJsonPropertySourceLoader] | `com.alibaba.cloud:spring-alibaba-nacos-config` |
 *
 * Spring Boot ships no JSON property source loader; Spring Cloud Alibaba
 * supplies its own, and that is the one a Nacos JSON configuration is actually
 * read by. Reading JSON through the YAML loader instead — JSON being YAML flow
 * style — would have been an oracle we reasoned our way to rather than the
 * runtime's answer, so it is not what happens here.
 *
 * All three are test-only dependencies. Nothing here reaches the plugin
 * distribution, and #166's budget of exactly one new production dependency is
 * untouched.
 *
 * Which oracle is in force is stated in [IN_FORCE] and asserted by
 * [ExtractionDifferentialTest], because the fallback #170 allows — a corpus of
 * key sets captured from the runtime once and checked in — is strictly weaker,
 * and a weaker oracle must never be able to stand in silently for this one.
 */
object RuntimeKeyOracle {

    /**
     * The oracle in force, in words, quoted into every failure message so a
     * reader knows what disagreed with the plugin.
     *
     * This string is a description, not the mechanism — what actually keeps a
     * weaker oracle from standing in are three things that cannot drift from it
     * silently: [loaderFor] names the loaders at compile time, so a dependency
     * disappearing stops this file compiling rather than degrading it; the
     * canary in `ExtractionDifferentialTest` asserts key sets only a live loader
     * produces; and [keysOf] refuses a format no loader is claimed for outside
     * its catch, so no entry can be compared against nothing.
     *
     * The spike #170 required established that these loaders run on this
     * project's test classpath (recorded on that issue), so the live oracle is in
     * force and the captured-corpus fallback is not built.
     */
    const val IN_FORCE: String =
        "the runtime's own PropertySourceLoader implementations, run live: " +
            "Spring Boot PropertiesPropertySourceLoader and YamlPropertySourceLoader, " +
            "Spring Cloud Alibaba NacosJsonPropertySourceLoader"

    /**
     * What the runtime made of a body — a closed set, for the same reason
     * [KeyExtraction] is one. A loader that gives out has said nothing about
     * which keys the body holds, and folding that into an empty key set would
     * let "the runtime refused this body" pass for "the runtime found no keys".
     */
    sealed interface Keys {
        /** The loader ran. [names] is the whole key space it resolved. */
        data class Loaded(val names: Set<String>) : Keys

        /** The loader gave out. Says nothing about the body's key space. */
        data class Refused(val failure: String) : Keys
    }

    fun keysOf(body: String, format: RuntimeConfigFormat): Keys {
        // Which loader answers is resolved before the catch, so a format none is
        // claimed for fails the test outright instead of arriving as a refusal.
        // Inside the catch it would be indistinguishable from a loader that gave
        // out on the body, and a corpus entry declaring `runtimeRefuses` would
        // then pass having been compared against nothing at all — the silent
        // substitution of a weaker oracle that [IN_FORCE] exists to prevent.
        val loader = loaderFor(format)
        return try {
            Keys.Loaded(
                loader(body.toByteArray())
                    .flatMap { (it as EnumerablePropertySource<*>).propertyNames.asList() }
                    .toSet()
            )
        } catch (t: Throwable) {
            // Throwable, not Exception: a body the plugin walks with a cycle
            // guard can flatten recursively inside the runtime and reach it as a
            // StackOverflowError. That is a refusal like any other, and the
            // corpus records which entries reach it.
            Keys.Refused("${t.javaClass.simpleName}: ${t.message?.lineSequence()?.firstOrNull()}")
        }
    }

    private fun loaderFor(format: RuntimeConfigFormat): (ByteArray) -> List<PropertySource<*>> =
        when (format) {
            RuntimeConfigFormat.PROPERTIES ->
                { bytes -> PropertiesPropertySourceLoader().load(NAME, ByteArrayResource(bytes)) }
            RuntimeConfigFormat.YAML ->
                // One property source per document, so a multi-document stream
                // contributes every document's keys rather than only the last.
                { bytes -> YamlPropertySourceLoader().load(NAME, ByteArrayResource(bytes)) }
            RuntimeConfigFormat.JSON ->
                // The loader refuses any resource that is not its own, and
                // takes the format from the filename.
                { bytes ->
                    NacosJsonPropertySourceLoader()
                        .load(NAME, NacosByteArrayResource(bytes, "$NAME.json"))
                }
            else -> throw IllegalArgumentException(
                "no runtime loader is claimed for $format; this oracle answers only " +
                    "for $PARSED_FORMATS"
            )
        }

    private const val NAME = "corpus"
}
