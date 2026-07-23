import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.nanyin.nacos.search"
version = "1.3.6"
val ideaLocalPath = providers.environmentVariable("IDEA_LOCAL_PATH")
    .orElse("")
    .get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    intellijPlatform {
        // Build against a recent stable release that is compatible with 2026.1 EAP.
        // Plugin verifier is configured below to test against additional versions.
        create("IC", "2024.3.5")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    implementation("com.google.code.gson:gson:2.10.1")
    // Note: kotlinx-coroutines-core is provided by IntelliJ Platform
    // Note: SLF4J is provided by IntelliJ Platform (version 1.x)

    testImplementation(platform("org.junit:junit-bom:5.10.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    // Note: kotlinx-coroutines-test is provided by IntelliJ Platform
}

intellijPlatform {
    signing {
        // Defaults to PRIVATE_KEY / CERTIFICATE_CHAIN / PRIVATE_KEY_PASSWORD env vars.
        // The signPlugin task is skipped automatically when credentials are absent.
        privateKey = providers.environmentVariable("PRIVATE_KEY").orNull
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN").orNull
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD").orNull
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            // Verify against the build target and recent stable releases.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.5")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            // Verify against the 2026.1 EAP/Beta branch reported in the Marketplace failure.
            // Pre-release builds are not published as installers, so useInstaller must be false.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "261-EAP-SNAPSHOT") {
                useInstaller = false
            }
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    patchPluginXml {
        sinceBuild.set("243")
        untilBuild.set("261.*")

        pluginDescription.set("""
            <p>Nacos Search plugin for IntelliJ IDEA that allows developers to query Nacos configurations
            directly from within the IDE with local caching for improved performance.</p>

            <p>Features:</p>
            <ul>
                <li>Connect to Nacos server via Open API</li>
                <li>Local caching of configuration data</li>
                <li>Search across dataId, group, and content</li>
                <li>Namespace and group filtering</li>
                <li>Seamless IntelliJ IDEA integration</li>
            </ul>
        """.trimIndent())

       changeNotes.set("""
            <h3>1.3.6</h3>
            <ul>
                <li><b>Dual-stack</b>: V1/V3 protocol adapters with AUTO generation resolve, namespace discovery, and connection diagnostics from unsaved settings drafts.</li>
                <li><b>Environments</b>: project-local environment sessions with epoch fencing; History/Diff/publish/gutter follow the project-selected profile (not the app-wide Settings default).</li>
                <li><b>History / Diff</b>: identity-local read-only history browser with embedded split-pane Diff (no merge chevrons).</li>
                <li><b>Publish</b>: opt-in per-profile write intent with controlled CAS/read-back publish path.</li>
                <li><b>Settings</b>: progressive disclosure (Advanced for auth/API/write intent); empty-credential profiles default to ANONYMOUS and upgrade to NACOS_PASSWORD when credentials are filled.</li>
                <li><b>Navigation</b>: when <code>@NacosPropertySource</code> declares a dataId and the cache has that key in that file, gutter/go-to hard-filters to it; otherwise soft-falls back to all hits.</li>
                <li><b>UI</b>: native ActionToolbar chrome; cache confidence status; Settings blue-dot tracks the current project environment.</li>
                <li><b>Reliability</b>: release-gate contract suite; fail-closed capture for deleted profiles; write-intent enforced in PublishController; diagnostics honor locked V1/V3 API policy.</li>
            </ul>
            <h3>1.3.5</h3>
            <ul>
                <li><b>Cache</b>: full-namespace index writes now honor the configured TTL, while expired configuration details remain available for stale-while-revalidate navigation.</li>
                <li><b>Navigation</b>: added fresh, stale, and unresolved gutter states; stale targets remain navigable, and details older than seven days trigger a forced background refresh.</li>
                <li><b>Reliability</b>: added event-driven, single-flight namespace refreshes; only a fresh complete index can prove a dataId absent, while partial or failed refreshes preserve usable stale targets.</li>
                <li><b>Security</b>: navigation and local search caches are isolated by server, authentication mode, user, and namespace.</li>
            </ul>
            <h3>1.3.4</h3>
            <ul>
                <li><b>UI</b>: optimized the Nacos Search sidebar icon for clearer display in the IDE tool window.</li>
                <li><b>Reliability</b>: fixed the persistent placeholder index value format so IntelliJ no longer reports an equals/hashCode contract violation while indexing Java files.</li>
                <li><b>Migration</b>: bumped the placeholder index version so IDEs automatically rebuild data written with the previous empty-marker format.</li>
                <li><b>Tests</b>: added regression coverage for non-empty marker serialization, round-trip identity, and index version invalidation.</li>
            </ul>
            <h3>1.3.3</h3>
            <ul>
                <li>优化侧边栏图标显示。</li>
            </ul>
            <h3>1.3.2</h3>
            <ul>
                <li><b>Navigation</b>: improved @NacosValue gutter navigation with richer cross-namespace target selection, usage labels, and source-location presentation.</li>
                <li><b>Security</b>: Nacos passwords are now stored through IntelliJ PasswordSafe and omitted from persisted settings XML, with migration for legacy plaintext values.</li>
                <li><b>Reliability</b>: Nacos JSON requests now retry transient IO failures and retry with connection settings that avoid malformed chunked responses from some servers and proxies.</li>
                <li><b>UI</b>: cross-namespace config navigation preserves the selected detail view while the namespace list refreshes, and preference-only changes refresh markers without a full cache reload.</li>
                <li><b>Tests</b>: added coverage for popup choice items, usage presentation, credential persistence, resolver behavior, gutter markers, and settings interactions.</li>
            </ul>
            <h3>1.3.1</h3>
            <ul>
                <li><b>Performance</b>: lock-free cache reads, file-based persistence (per-entry files replacing a multi-hundred-MB state XML), background cache loading so IDE startup is no longer blocked, concurrent per-item content fetch (bounded at 8).</li>
                <li><b>Fix</b>: HTTP connection leak in requestPost (try/finally disconnect); login credentials now URL-encoded; PaginationPanel disposes its listener via the Disposable tree; Find Usages honors the cancel signal; minified JSON now parsed; performSearch cancels stale debounced searches; stale tokens evicted on credential switch; NacosSearchPlugin implements Disposable; putNamespaceIndex persists seeded details.</li>
                <li><b>UI</b>: the save button label reads "Save" instead of "Save & Publish".</li>
                <li><b>i18n</b>: fixed untranslated placeholder keys, a config.list.dirty typo, and unified the Chinese bundle encoding to native UTF-8.</li>
            </ul>
            <h3>1.3.0</h3>
            <ul>
                <li>Fix: phantom gutter icon no longer appears when a @NacosPropertySource dataId does not exist in the loaded Nacos namespace.</li>
                <li>Fix: resolved NacosConfigKeyElement now carries the source PSI element so Find Usages and navigation keep working after lazy-load.</li>
                <li>UI: redesigned search bar, clear button, group filter pill and tool-window toolbar.</li>
                <li>Perf: debounced search input and background namespace-index preheat.</li>
            </ul>
            <h3>1.2.0</h3>
            <ul>
                <li>@NacosValue gutter icon with dual-state (resolved/unresolved) and PSI reference navigation</li>
                <li>Reverse Find Usages for Nacos configuration keys</li>
                <li>Fuzzy namespace search support</li>
                <li>Code usage navigation from configuration detail to Java source code</li>
                <li>Persistent placeholder index for fast reverse lookup</li>
                <li>Plugin icon for JetBrains Marketplace</li>
            </ul>
            <h3>1.1.1</h3>
            <ul>
                <li>Multi-server (environment) configuration support with master-detail settings UI</li>
                <li>Redesigned Nacos Search settings page</li>
                <li>UI improvements across search, namespace, config list, and detail panels</li>
                <li>Authentication service improvements for token refresh and hybrid mode</li>
                <li>Reactive UI updates via NacosSettingsListener</li>
            </ul>
            <h3>1.0.0</h3>
            <ul>
                <li>Initial release</li>
                <li>Nacos Open API integration</li>
                <li>Local configuration caching</li>
                <li>Multi-field search functionality</li>
                <li>Tool window with search interface</li>
            </ul>
        """.trimIndent())
    }

    test {
        useJUnitPlatform {
            // JUnit5 @TestApplication disposes the shared IDE application when the
            // Jupiter engine finishes. JUnit4 ApplicationRule tests (vintage) then
            // fail to reconnect in the same JVM with "Already shutdown" on
            // PersistentFS / AppScheduledExecutorService. Keep Jupiter here;
            // vintage runs in an isolated JVM via testVintage (CI unit-tests /
            // local `check` only — not finalizedBy, so filtered smoke jobs stay lean).
            excludeEngines("junit-vintage")
        }
        testLogging {
            events("passed", "skipped", "failed", "standardOut", "standardError")
            showExceptions = true
            showCauses = true
            showStackTraces = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }

    val testVintage by registering(Test::class) {
        group = "verification"
        description =
            "JUnit4 ApplicationRule tests in an isolated JVM (avoids @TestApplication teardown)"
        // Match the IntelliJ Platform test wiring on the primary `test` task so
        // Gradle 9 validation does not flag sandbox/plugin outputs as undeclared
        // (prepareTestSandbox → .intellijPlatform/sandbox/.../plugins-test).
        dependsOn("testClasses", "instrumentTestCode", "prepareTestSandbox", "prepareTest")
        useJUnitPlatform {
            includeEngines("junit-vintage")
        }
        testLogging {
            events("passed", "skipped", "failed", "standardOut", "standardError")
            showExceptions = true
            showCauses = true
            showStackTraces = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = true
        }
        shouldRunAfter("test")
    }

    // IntelliJ Platform attaches the instrumented classpath and module opens onto
    // the primary `test` task; mirror them onto testVintage once that is ready.
    // Accessing those providers at configuration time is incompatible with the
    // configuration cache (ExtractorService). CI test steps pass
    // --no-configuration-cache; see .github/workflows/ci.yml.
    afterEvaluate {
        val mainTest = named<Test>("test").get()
        named<Test>("testVintage").configure {
            testClassesDirs = mainTest.testClassesDirs
            classpath = mainTest.classpath
            systemProperties.putAll(mainTest.systemProperties)
            jvmArgs = buildList {
                mainTest.jvmArgs?.let { addAll(it) }
                mainTest.jvmArgumentProviders.forEach { addAll(it.asArguments()) }
            }
            environment(mainTest.environment)
            // Also inherit any extra task deps the IntelliJ plugin attached to `test`.
            dependsOn(mainTest.taskDependencies.getDependencies(mainTest))
        }
    }

    named("check") {
        dependsOn("testVintage")
    }

    // Ant instrumentIdeaExtensions is not thread-safe; parallel instrumentCode +
    // instrumentTestCode races (CI: "1 >= 1", local: nested element BuildException).
    named("instrumentTestCode") {
        mustRunAfter("instrumentCode")
    }

    runIde {
        jvmArgs("-Xmx2048m")
    }

}
