import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.nanyin.nacos.search"
version = "1.3.8"
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
        // Build against the oldest supported release so newer API usage is caught at compile time.
        create("IC", "2022.3.3") {
            useInstaller = false
        }
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    implementation("com.google.code.gson:gson:2.10.1")
    // The library Spring Boot's own YAML property source loader uses. Bundled
    // as a standalone jar under the distribution's lib/, the same arrangement
    // as Gson and deliberately not the platform's own copy; CLAUDE.md records
    // why under ConfigKeyExtractor.
    implementation("org.yaml:snakeyaml:2.6")
    // Note: kotlinx-coroutines-core is provided by IntelliJ Platform
    // Note: SLF4J is provided by IntelliJ Platform (version 1.x)

    testImplementation(platform("org.junit:junit-bom:5.10.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    // Test-only: the runtime's own property source loaders, so extraction
    // correctness is asserted against what Spring will resolve rather than
    // against cases we thought of (#170). Never on the production classpath.
    testImplementation("org.springframework.boot:spring-boot:3.2.12")
    // Spring Cloud Alibaba's own JSON PropertySourceLoader — Spring Boot ships
    // none, and this is the one a Nacos JSON configuration is actually read by.
    // Transitives excluded: only its parser package is wanted, not nacos-client
    // or spring-cloud-context.
    testImplementation("com.alibaba.cloud:spring-alibaba-nacos-config:2023.0.3.4") {
        isTransitive = false
    }
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.15.4")
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
            // Keep the local verification footprint small: verify the oldest supported IDE first.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2022.3.3") {
                useInstaller = false
            }
        }
    }
}

tasks {
    // IntelliJ Platform Gradle Plugin 2.16 refuses to execute searchable-options
    // generation below build 233, while the plugin itself targets build 223.
    // The settings page remains available; only its precomputed Settings search
    // index is omitted so buildPlugin/verifyPlugin can run against the oldest IDE.
    named("buildSearchableOptions") {
        enabled = false
    }
    named("prepareJarSearchableOptions") {
        enabled = false
    }
    named("jarSearchableOptions") {
        enabled = false
    }

    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            languageVersion = KotlinVersion.KOTLIN_1_7
            apiVersion = KotlinVersion.KOTLIN_1_7
        }
    }

    patchPluginXml {
        sinceBuild.set("223")
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
            <h3>1.3.8</h3>
            <ul>
                <li><b>Compatibility</b>: the supported IDE range now starts at build 223, so the plugin installs on IntelliJ IDEA 2022.3 and later (previously 2024.3+).</li>
                <li><b>兼容性</b>：支持的 IDE 版本下限下调到 build 223，IntelliJ IDEA 2022.3 及以上均可安装（此前需要 2024.3+）。</li>
                <li><b>Configuration parsing</b>: keys are now extracted with the same parsers the runtime uses — Gson for JSON, snakeyaml for YAML (the library Spring Boot's own loader uses), and the JDK loader for properties — instead of hand-written scanners. Multi-document YAML streams, anchors/merges, nested sequences, block scalars, and arrays followed by more keys all resolve correctly now.</li>
                <li><b>配置解析</b>：配置 key 改用运行时同款解析器提取——JSON 用 Gson、YAML 用 snakeyaml（Spring Boot 自身加载器所用的库）、properties 用 JDK 加载器，不再使用手写扫描。多文档 YAML、锚点与合并、嵌套序列、块标量、数组后续 key 等场景现在都能正确解析。</li>
                <li><b>Runtime format</b>: which format a configuration is read under is decided by the type declared at the reference site first, then by the data id suffix — matching what <code>nacos-spring-context</code> actually does, rather than the server's declared type field. A data id whose format the plugin reads no keys from (for example <code>.xml</code>, or a suffix nothing recognizes) gets its own gray gutter state with an explanation and no jump arrow, instead of a click that silently fetches the body and resolves nothing.</li>
                <li><b>运行时格式</b>：配置按何种格式解析，优先取引用处声明的类型，其次取 Data ID 后缀——与 <code>nacos-spring-context</code> 的实际行为一致，而不是服务端返回的格式字段。插件不解析其 key 的格式（如 <code>.xml</code>，或无法识别的后缀）会显示独立的灰色 gutter 状态并给出说明、不提供跳转箭头，不再点击后静默拉取正文却解析不出任何内容。</li>
                <li><b>Access visibility</b>: cached configurations the server has refused to serve are now hidden and reappear automatically once access returns. An authentication refusal hides the whole environment; a configuration-read permission denial hides only the affected Namespace. Publish, Namespace-discovery, and history refusals never hide readable configuration data. Visibility blocks survive IDE restarts and are removed together with the cached data by <code>Tools &gt; Clear Cache</code>.</li>
                <li><b>访问可见性</b>：当服务器拒绝提供数据后（认证失败或读取权限被收回），插件不再显示之前缓存的配置，访问恢复后会自动重新显示。认证失败会隐藏该环境下的全部配置；配置读取权限被拒只隐藏对应命名空间；发布、命名空间发现、历史记录等权限拒绝不会隐藏仍可读取的配置数据。可见性屏蔽在 IDE 重启后仍然生效，执行 <code>Tools &gt; Clear Cache</code> 清除缓存时随缓存数据一并移除。</li>
                <li><b>Navigation</b>: a declared <code>@NacosPropertySource</code> dataId is a hard filter both ways. Forward: hits under that dataId resolve as before; when the key is absent from the declared body (or the dataId itself is proven absent), the placeholder is unresolved with no gutter icon — never soft-falls to another cached configuration. Reverse Find Usages from a configuration key only reaches usages that are not hard-bound to a different dataId (so a key in <code>test.yaml</code> no longer jumps to a <code>@NacosValue</code> under <code>dataId = "es.properties"</code>). When the declared dataId is not present under the session Namespace (including when it only exists in another Namespace's cache), the gutter stays unmarked instead of painting an optimistic gray icon. A dataId listed by a fresh authoritative Namespace index but not yet in the detail cache remains undecidable (hollow marker; click lazy-loads that dataId).</li>
                <li><b>导航</b>：已声明配置来源的 Data ID 出现在新鲜且权威的 Namespace 索引中、但其正文尚未进入配置详情缓存时，解析为不可判定配置引用（空心标记；点击会懒加载该 Data ID），不再静默回退到其他已缓存配置。声明正文已缓存且不含该 key、或新鲜完整索引证明该 Data ID 不存在时，仍可软发现；无法证明缺席时不再替换为其他 Data ID（issue #192）。</li>
                <li><b>Editing &amp; publishing</b>: saving now shows the diff and the named target for confirmation before the single write, reports publish progress and its outcome, and an unsaved draft is protected — switching environment or Namespace, turning write intent off, deleting the profile, selecting another configuration, closing the tool window, or closing the project all ask first. Refresh keeps the draft instead of discarding it.</li>
                <li><b>编辑与发布</b>：保存前先展示 diff 和目标坐标供确认再执行唯一一次写入，发布过程与结果均有进度反馈；存在未保存草稿时，切换环境或命名空间、关闭写入开关、删除配置档、切换到其他配置、关闭工具窗口、关闭工程都会先行询问；刷新操作保留草稿而不是丢弃。</li>
                <li><b>Settings &amp; environments</b>: the authentication strategy moved onto the primary Settings form and defaults to NACOS_PASSWORD once credentials are filled; Cancel really cancels and Reset really resets; the selected Namespace is now project-local like the environment, so two projects no longer fight over one application-wide selection; deleting a profile is fail-closed and takes its credentials with it.</li>
                <li><b>设置与环境</b>：认证方式移至设置主表单，填入凭据后默认 NACOS_PASSWORD；取消即真正取消、重置即真正重置；所选命名空间与环境一样改为工程级，两个工程不再争抢同一个全局选择；删除配置档采用失败即关闭策略，并一并清除其凭据。</li>
                <li><b>Performance</b>: Namespace indexes now persist across IDE restarts, re-pagination is throttled with backoff and a page cap, and configuration bodies carried by V1 list responses seed the detail cache directly — together this removes a large share of the repeated requests the plugin used to make against the Nacos server.</li>
                <li><b>性能</b>：命名空间索引在 IDE 重启后仍然保留，重新分页带退避与页数上限限流，V1 列表响应自带的配置正文直接回填详情缓存——三者合计显著减少了插件对 Nacos 服务端的重复请求。</li>
                <li><b>Fixes</b>: cleared a Loading overlay that could stick on the first configuration opened; a Namespace display name configured in Settings now resolves to the real Namespace id; switching environments adopts the suggested Namespace; a blank Namespace id is treated as <code>public</code>; gutter icons re-analyze after the key index is published asynchronously instead of staying blank; a deleted profile's cached configurations can no longer be resurrected by a read that lands right after the deletion; the unsaved-edit marker in the result list now carries a "Modified" label instead of a bare dot.</li>
                <li><b>问题修复</b>：修复首次打开配置时加载遮罩卡住；设置中填写的命名空间显示名现在会解析为真实的命名空间 ID；切换环境时会采用建议的命名空间；空白命名空间 ID 按 <code>public</code> 处理；key 索引异步发布完成后 gutter 图标会重新分析，不再保持空白；删除配置档后紧接着的读取不会再把已清除的缓存配置恢复出来；结果列表中的未保存标记从裸圆点改为带「已修改」文字标签。</li>
            </ul>
            <h3>1.3.7</h3>
            <ul>
                <li><b>Compatibility</b>: History Diff loading state uses the public MessageDiffRequest API instead of internal LoadingDiffRequest (Marketplace verifier).</li>
            </ul>
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
        // @TestApplication.close() hard-codes timeoutRunBlocking(20s) around
        // waitForAppLeakingThreads(10s) + disposeTestApplication on the EDT.
        // On constrained CI hosts (Jenkins beijing: 1024m test heap, 2.5G
        // container) that dispose regularly exceeds 20s after a full suite —
        // all tests pass, then "JUnit Jupiter > executionError" fails with
        // TimeoutCancellationException. JetBrains already skips dispose under
        // Bazel for the same reason (JVM exits after the suite). Gate on CI /
        // Jenkins so local runs still exercise dispose + leak checks.
        val onCi = providers.environmentVariable("CI").orElse("")
            .zip(providers.environmentVariable("JENKINS_URL").orElse("")) { ci, jenkins ->
                ci.isNotBlank() || jenkins.isNotBlank()
            }
        systemProperty(
            "intellij.testFramework.junit5.skip.test.application.dispose",
            onCi.map { if (it) "true" else "false" }
        )
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
