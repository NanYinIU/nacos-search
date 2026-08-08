package com.nanyin.nacos.search.psi

/**
 * The shared corpus both extraction oracles read.
 *
 * Every input a defect was found on during #166's design is here — the enumerated
 * test class covers the same inputs, and covering them there was not enough: it
 * was entirely green while JSON silently dropped every key declared after an
 * array whose last element was a string, YAML merged a multi-document stream down
 * to one document, and a sequence of nested mappings overwrote its own first
 * element. The point of a shared corpus is that the same body is judged by the
 * runtime as well as by us, so a body nobody thought to assert about still fails
 * the build.
 */
object ExtractionCorpus {

    data class Entry(
        val name: String,
        val format: RuntimeConfigFormat,
        val body: String,
        /** Non-null only where the plugin differs from the runtime on purpose. */
        val divergence: Divergence? = null
    )

    /**
     * A place the plugin's answer differs from the runtime's.
     *
     * Enumerated key-for-key rather than as a blanket exemption, and asserted
     * exactly: an entry that declares a divergence still fails if the difference
     * is not the one declared. Otherwise one documented divergence would become a
     * hiding place for every undiscovered defect on the same body.
     */
    data class Divergence(
        /** Whether the plugin means to differ here, or is simply wrong. */
        val kind: Kind,
        /** Why the difference stands. Read by whoever the test fails on. */
        val why: String,
        /** Keys the runtime resolves that the plugin deliberately does not. */
        val onlyRuntime: Set<String> = emptySet(),
        /** Keys the plugin contributes that the runtime does not resolve. */
        val onlyPlugin: Set<String> = emptySet(),
        /** The runtime's loader gives out on this body rather than loading it. */
        val runtimeRefuses: Boolean = false
    )

    /**
     * The two reasons a difference is recorded here — kept apart because calling
     * a defect intentional is how it stops being fixed.
     */
    enum class Kind {
        /** The plugin answers differently on purpose, and the entry says why. */
        INTENDED,

        /**
         * A defect this oracle found. It is pinned rather than deleted so the
         * exact difference is on record and the day it is fixed the test says so;
         * every one of these names its tracking issue, which the differential
         * test asserts.
         */
        KNOWN_DEFECT
    }

    /**
     * A partial key space the plugin keeps where the runtime keeps none.
     *
     * The extractor's contract is that a malformed body yields the keys read
     * before the fault, because a partial key space is strictly better than none
     * for navigation and the alternative is a marker vanishing from a file the
     * user is halfway through editing. The runtime has no such interest — it
     * fails the application start — so this class of difference is expected
     * wherever the corpus holds a body neither will fully parse.
     */
    private fun partialParse(vararg pluginKeys: String) = Divergence(
        kind = Kind.INTENDED,
        why = "the runtime fails the whole body; the plugin keeps the keys it read " +
            "before the fault, because a partial key space still navigates",
        onlyPlugin = pluginKeys.toSet(),
        runtimeRefuses = true
    )

    /**
     * A key the runtime gives out on, taking the whole body's key space with it.
     *
     * Spring resolves a mapping key by constructing it and then spelling it, and
     * either step can fail: a tag no `SafeConstructor` knows fails the
     * construction, a null key makes `key.toString()` an NPE inside Spring
     * itself, and a non-string key inside a sequence reaches a cast the
     * flattening cannot make. Each of those fails the application start outright.
     *
     * The plugin drops the offending key alone and keeps the rest of the body,
     * for the reason [partialParse] states: a marker vanishing from the file the
     * user is halfway through editing is the worse answer.
     */
    private fun runtimeGivesOutOverOneKey(what: String, vararg pluginKeys: String) = Divergence(
        kind = Kind.INTENDED,
        why = "$what, which fails the runtime's whole load; the plugin drops that key " +
            "alone and keeps the rest of the body, because a partial key space still navigates",
        onlyPlugin = pluginKeys.toSet(),
        runtimeRefuses = true
    )

    val entries: List<Entry> = properties() + yaml() + json()

    // ----- properties -------------------------------------------------------

    private fun properties(): List<Entry> = listOf(
        Entry("properties/flat", RuntimeConfigFormat.PROPERTIES, "app.name=my-service\napp.port=8080\n"),
        Entry("properties/comments-and-blanks", RuntimeConfigFormat.PROPERTIES, "# a comment\n\n! bang comment\nkey=v\n"),
        Entry("properties/colon-separator", RuntimeConfigFormat.PROPERTIES, "host: localhost\n"),
        Entry("properties/whitespace-separator", RuntimeConfigFormat.PROPERTIES, "app.name my-service\n"),
        Entry(
            "properties/value-containing-a-colon",
            RuntimeConfigFormat.PROPERTIES,
            "url=jdbc:mysql://host:3306/db\nhost:localhost:8080\n"
        ),
        Entry("properties/escaped-separator-in-key", RuntimeConfigFormat.PROPERTIES, "a\\:b=c\n"),
        Entry("properties/repeated-key", RuntimeConfigFormat.PROPERTIES, "k=1\nk=2\n"),
        // The hand-written reader read the continued line on its own and invented
        // `http` out of an indented `http://b`.
        Entry(
            "properties/continuation-line",
            RuntimeConfigFormat.PROPERTIES,
            "urls=http://a,\\\n     http://b\nnext=1\n"
        ),
        Entry("properties/split-key-across-a-continuation", RuntimeConfigFormat.PROPERTIES, "long\\\nkey=v\n"),
        Entry("properties/half-a-split-name-spells-a-real-key", RuntimeConfigFormat.PROPERTIES, "a=2\na\\\nb=1\n"),
        Entry("properties/unicode-escape-in-key-and-value", RuntimeConfigFormat.PROPERTIES, "caf\\u00e9.name=caf\\u00e9\n"),
        Entry("properties/character-escapes", RuntimeConfigFormat.PROPERTIES, "k=a\\tb\nwith\\ space=v\n"),
        Entry("properties/url-fragment-in-value", RuntimeConfigFormat.PROPERTIES, "home=https://example.com/app#dashboard\n"),
        Entry("properties/whitespace-then-hash-in-value", RuntimeConfigFormat.PROPERTIES, "motd=hello # not a comment\n"),
        Entry("properties/comment-with-a-trailing-backslash", RuntimeConfigFormat.PROPERTIES, "# trailing backslash \\\nk=1\n"),
        // The relaxed-binding shape written out longhand: whatever the plugin
        // emits for an indexed leaf has to be a key the runtime also resolves.
        Entry("properties/bracket-form-written-directly", RuntimeConfigFormat.PROPERTIES, "list[0].n=x\nlist[1].n=y\n"),
        Entry("properties/empty-value", RuntimeConfigFormat.PROPERTIES, "a=\nb=1\n"),
        Entry("properties/key-with-no-separator", RuntimeConfigFormat.PROPERTIES, "lonely\nb=1\n"),
        Entry(
            "properties/malformed-unicode-escape",
            RuntimeConfigFormat.PROPERTIES,
            "a=1\nb=\\uZZ\nc=3\n",
            partialParse("a")
        )
    )

    // ----- yaml -------------------------------------------------------------

    private fun yaml(): List<Entry> = listOf(
        Entry("yaml/nested-mapping", RuntimeConfigFormat.YAML, "app:\n  name: svc\n  port: 8080\n"),
        Entry("yaml/top-level-scalar", RuntimeConfigFormat.YAML, "timeout: 3000\n"),
        Entry("yaml/quoted-value", RuntimeConfigFormat.YAML, "name: \"hello\"\n"),
        Entry("yaml/three-level-nesting", RuntimeConfigFormat.YAML, "sys:\n  audit:\n    switch: true\n"),
        Entry("yaml/sibling-keys", RuntimeConfigFormat.YAML, "server:\n  port: 8080\nlogging:\n  level: INFO\n"),
        Entry("yaml/sequence-of-mappings", RuntimeConfigFormat.YAML, "tabs:\n  - id: 1\n  - id: 2\n"),
        Entry("yaml/scalar-sequence", RuntimeConfigFormat.YAML, "items:\n  - one\n  - two\nreal: yes\n"),
        // The ordinary Spring profile idiom. The indentation scanner merged the
        // documents and kept only the last.
        Entry(
            "yaml/multi-document-stream",
            RuntimeConfigFormat.YAML,
            "spring:\n  profiles: dev\ndb:\n  url: dev-url\n---\nspring:\n  profiles: prod\nextra: true\n"
        ),
        // The scanner closed the running index when an element opened a mapping,
        // so every element collapsed onto index zero.
        Entry(
            "yaml/sequence-elements-opening-nested-mappings",
            RuntimeConfigFormat.YAML,
            "items:\n  - meta:\n      id: 1\n  - meta:\n      id: 2\n"
        ),
        Entry(
            "yaml/sibling-of-a-nested-mapping",
            RuntimeConfigFormat.YAML,
            "items:\n  - name: first\n    nested:\n      x: 1\n    other: last\n"
        ),
        // `items[0]name.first` is a shape Spring's relaxed binding can never match.
        Entry("yaml/index-followed-by-a-segment", RuntimeConfigFormat.YAML, "items:\n  - name:\n      first: a\n"),
        Entry("yaml/flow-style", RuntimeConfigFormat.YAML, "server: {port: 8080, host: localhost}\nhosts: [a, b]\n"),
        Entry(
            "yaml/quoted-and-digit-leading-keys",
            RuntimeConfigFormat.YAML,
            "\"spring.datasource.url\": jdbc:h2:mem\n'single': q\n2fa: enabled\n"
        ),
        // The scanner read `&defaults` as a value, so the children below it were
        // attributed to the document root.
        Entry("yaml/anchored-mapping", RuntimeConfigFormat.YAML, "defaults: &defaults\n  timeout: 30\nservice: *defaults\n"),
        Entry(
            "yaml/merge-key",
            RuntimeConfigFormat.YAML,
            "defaults: &d\n  timeout: 30\n  retries: 3\nservice:\n  <<: *d\n  timeout: 60\n"
        ),
        Entry(
            "yaml/merge-is-shallow",
            RuntimeConfigFormat.YAML,
            "defaults: &d\n  db:\n    url: from-defaults\nservice:\n  <<: *d\n  db:\n    port: 5432\n"
        ),
        Entry(
            "yaml/merge-sequence-of-sources",
            RuntimeConfigFormat.YAML,
            "a: &a\n  k: from-a\nb: &b\n  k: from-b\n  only-b: 1\nc:\n  <<: [*a, *b]\n"
        ),
        // Both contribute `base` and neither contributes `service`: only a literal
        // `{}` terminates the runtime's flattening, and a mapping left empty by a
        // merge is dropped. Pinned because reading emptiness off the node rather
        // than off the merged entry set looks like a shortcut and is not one.
        Entry("yaml/merge-of-an-empty-mapping", RuntimeConfigFormat.YAML, "base: &b {}\nservice:\n  <<: *b\n"),
        // The enumerated tests deliberately left these to this oracle rather than
        // pinning them to a guess, and the oracle's answer was that the plugin was
        // wrong twice over: it lost the `[0]` the runtime resolves and invented an
        // `on` no runtime does. Fixed in #178 by spelling a non-string mapping key
        // the way Spring's flattening does — bracketed, from the key's *resolved*
        // value. They agree now, and the entries stay so that a change to the
        // key-scalar construction says so.
        Entry("yaml/numeric-keys", RuntimeConfigFormat.YAML, "0: zero\n1.5: half\n"),
        Entry("yaml/boolean-like-key", RuntimeConfigFormat.YAML, "on: switched\n"),
        // The one that used to cost a real placeholder: `${a[0]}` is ordinary. The
        // quoted `"0"` stays a string key, so `a.0` is the runtime's answer for
        // that one and not for the plain `0` — two declarations, two keys, which
        // the plugin only tells apart because a mapping's entries are identified
        // by the segment they contribute rather than by their document spelling.
        Entry(
            "yaml/non-string-mapping-keys-nested",
            RuntimeConfigFormat.YAML,
            "a:\n  0: x\n  on: y\n  \"0\": q\n"
        ),
        // The resolved value, not the document spelling: hex and sexagesimal are
        // renumbered, every YAML 1.1 boolean spelling collapses onto two keys, and
        // a float keeps Java's rendering of it. `0o14` is here for the other
        // direction — YAML 1.2 octal is no integer to snakeyaml, so it stays a
        // string key and keeps the dot form.
        Entry(
            "yaml/non-string-key-spellings",
            RuntimeConfigFormat.YAML,
            "0x10: hex\n1:30: sexagesimal\nyes: y\noff: n\n.inf: infinite\n-1: negative\n" +
                "0o14: not-an-int\n123456789012345678901234567890: big\n"
        ),
        // Spring Boot's YAML loader composes with a resolver that drops
        // snakeyaml's implicit timestamp tag. The plugin uses the same one, so a
        // date-shaped key stays a string instead of becoming a `Date` whose
        // bracketed `toString` no placeholder could spell — and whose rendering
        // would depend on the reading JVM's time zone.
        Entry("yaml/timestamp-shaped-key", RuntimeConfigFormat.YAML, "2020-01-01: v\nafter: 1\n"),
        Entry(
            "yaml/non-string-key-with-a-nested-mapping",
            RuntimeConfigFormat.YAML,
            "a:\n  0:\n    b: 1\n"
        ),
        Entry(
            "yaml/non-string-key-with-an-empty-container",
            RuntimeConfigFormat.YAML,
            "a:\n  0: {}\n"
        ),
        // Spring's `asMap` brackets a non-string key only for a mapping it
        // descends into, and it does not descend into a mapping held by a
        // sequence — so the flattening reaches that mapping with an Integer key
        // still in it and fails the cast to String.
        Entry(
            "yaml/non-string-key-inside-a-sequence",
            RuntimeConfigFormat.YAML,
            "a:\n  - 0: x\n",
            runtimeGivesOutOverOneKey(
                "a non-string mapping key inside a sequence reaches a `Map<String, Object>` " +
                    "cast the runtime's flattening cannot make",
                "a[0][0]"
            )
        ),
        Entry(
            "yaml/null-mapping-key",
            RuntimeConfigFormat.YAML,
            "~: v\nafter: 1\n",
            runtimeGivesOutOverOneKey(
                "a null mapping key makes the runtime's own `key.toString()` an NPE",
                "after"
            )
        ),
        Entry(
            "yaml/mapping-key-with-an-unknown-tag",
            RuntimeConfigFormat.YAML,
            "!foo bar: v\nafter: 1\n",
            runtimeGivesOutOverOneKey(
                "no `SafeConstructor` knows the tag `!foo`, so constructing the key fails",
                "after"
            )
        ),
        Entry("yaml/inline-comment", RuntimeConfigFormat.YAML, "app:\n  name: svc # the service name\n  port: 8080\n"),
        // Spring Boot's flattening terminates on an empty container, so these are
        // resolvable keys. The plugin claimed otherwise until this oracle said so.
        Entry("yaml/empty-containers", RuntimeConfigFormat.YAML, "app:\n  name: svc\nempty: {}\nlist: []\n"),
        Entry("yaml/empty-containers-nested", RuntimeConfigFormat.YAML, "a:\n  b: {}\n  c: []\n"),
        Entry("yaml/empty-containers-in-a-sequence", RuntimeConfigFormat.YAML, "s:\n  - {}\n  - []\n  - x\n"),
        Entry("yaml/null-value", RuntimeConfigFormat.YAML, "enabled:\nafter: 1\n"),
        Entry("yaml/deep-nesting-inside-a-sequence", RuntimeConfigFormat.YAML, "a:\n  b:\n    - c:\n        d: 1\n")
    ) + blockScalars() + listOf(
        Entry(
            "yaml/malformed-document-after-a-good-one",
            RuntimeConfigFormat.YAML,
            "a: 1\n---\nb: [unterminated\n",
            partialParse("a")
        ),
        Entry(
            "yaml/malformed-first-document",
            RuntimeConfigFormat.YAML,
            "a: [unterminated\n---\nb: 1\n",
            partialParse()
        )
    )

    /**
     * A block or folded scalar's body must stay out of the key space: it is one
     * value, however much a shell script embedded in it looks like a key list.
     * Every chomping form is here because the scanner this replaced leaked all of
     * them, and one indicator behaving is no evidence about the next.
     */
    private fun blockScalars(): List<Entry> =
        listOf("|", "|-", "|+", ">", ">-", ">+").map { indicator ->
            Entry(
                "yaml/block-scalar $indicator",
                RuntimeConfigFormat.YAML,
                "script: $indicator\n  phantom: 1\n  other: 2\nafter: x\n"
            )
        }

    // ----- json -------------------------------------------------------------

    private fun json(): List<Entry> = listOf(
        Entry("json/flat", RuntimeConfigFormat.JSON, "{\n  \"timeout\": 5000,\n  \"name\": \"svc\"\n}\n"),
        Entry("json/nested-object", RuntimeConfigFormat.JSON, "{\n  \"sys\": {\n    \"audit\": {\n      \"switch\": true\n    }\n  }\n}\n"),
        Entry("json/minified", RuntimeConfigFormat.JSON, """{"timeout":5000,"name":"svc"}"""),
        Entry("json/minified-nested", RuntimeConfigFormat.JSON, """{"sys":{"audit":{"switch":true}}}"""),
        // The scanner mis-tracked the closing bracket once a string ended an
        // array and swallowed the whole remainder of the document.
        Entry("json/array-whose-last-element-is-a-string", RuntimeConfigFormat.JSON, """{"a":["x"],"b":1}"""),
        Entry("json/array-of-objects", RuntimeConfigFormat.JSON, """{"list":[{"n":"first"},{"n":"second"}]}"""),
        Entry("json/scalar-array", RuntimeConfigFormat.JSON, """{"hosts":["a","b"]}"""),
        Entry("json/deep-nesting-inside-an-array", RuntimeConfigFormat.JSON, """{"a":{"b":[{"c":{"d":1}}]}}"""),
        Entry("json/escapes-in-values", RuntimeConfigFormat.JSON, """{"name":"a\/b\tc"}"""),
        Entry("json/escaped-key", RuntimeConfigFormat.JSON, "{\"na\\u006de\":\"v\"}"),
        Entry("json/multi-line-value", RuntimeConfigFormat.JSON, "{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": 3\n}\n"),
        Entry("json/value-containing-quotes-and-colons", RuntimeConfigFormat.JSON, "{\n  \"a\": \"he said \\\"b\\\": no\",\n  \"b\": 1\n}"),
        // The other way round from YAML: Spring Cloud Alibaba's JSON loader drops
        // an empty container rather than terminating on it. Holding both here is
        // what stops the two walks being "made consistent" with each other.
        Entry("json/empty-containers", RuntimeConfigFormat.JSON, """{"a":[],"b":{},"c":1}"""),
        Entry("json/empty-containers-nested", RuntimeConfigFormat.JSON, """{"o":{"a":[],"b":{}},"c":1}"""),
        Entry("json/empty-containers-in-an-array", RuntimeConfigFormat.JSON, """{"s":[{},[],"x"]}"""),
        Entry("json/null-value", RuntimeConfigFormat.JSON, """{"a":null,"b":1}"""),
        Entry("json/booleans", RuntimeConfigFormat.JSON, """{"on":true,"off":false}"""),
        Entry("json/nested-array-of-arrays", RuntimeConfigFormat.JSON, """{"m":[[1,2],[3]]}"""),
        Entry(
            "json/syntax-error",
            RuntimeConfigFormat.JSON,
            """{"a":1,"b":}""",
            partialParse("a")
        )
    )
}
