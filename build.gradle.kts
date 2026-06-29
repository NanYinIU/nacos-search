import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.nanyin.nacos.search"
 version = "1.3.0"
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
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed", "standardOut", "standardError")
            showExceptions = true
            showCauses = true
            showStackTraces = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }

    runIde {
        jvmArgs("-Xmx2048m")
    }

}
