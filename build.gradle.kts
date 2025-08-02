import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.10"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

// Configure Java toolchain to use Java 17
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

group = "com.github.nacos.search"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        //intellijIdeaCommunity("2023.2.5", useInstaller = false)
        local("/Applications/IntelliJ IDEA CE.app")
        bundledPlugin("com.intellij.java")
        pluginVerifier()
//        zipSigner {
//            // Use default signing configuration
//        }
        instrumentationTools()
        testFramework(TestFrameworkType.Platform)
    }

    implementation("com.google.code.gson:gson:2.10.1")
    // Note: kotlinx-coroutines-core is provided by IntelliJ Platform
    // Note: SLF4J is provided by IntelliJ Platform (version 1.x)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
    testImplementation("org.junit.vintage:junit-vintage-engine:5.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    // Note: kotlinx-coroutines-test is provided by IntelliJ Platform
}

// Configuration moved to intellijPlatform dependencies block above

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    
    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("241.*")
        
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
    
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs += "-Xdiagnostics-log-level=verbose"
            allWarningsAsErrors = false
            verbose = true
        }
    }
    
    runIde {
        jvmArgs("-Xmx2048m")
    }
    
    // Custom task to show detailed compilation errors
    register("showCompilationErrors") {
        dependsOn("compileTestKotlin")
        doLast {
            val compileTask = project.tasks.getByName("compileTestKotlin") as org.jetbrains.kotlin.gradle.tasks.KotlinCompile
            if (compileTask.didWork) {
                println("Compilation succeeded")
            } else {
                println("Compilation failed")
                // Print any available error information
                compileTask.kotlinOptions.allWarningsAsErrors = false
                compileTask.kotlinOptions.suppressWarnings = false
                compileTask.kotlinOptions.verbose = true
            }
        }
    }
}