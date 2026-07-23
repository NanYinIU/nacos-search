# AGENTS.md

This repository is **Nacos Search**, an IntelliJ IDEA Community plugin (Kotlin + Gradle). For a full architecture and command overview, see `CLAUDE.md` and `README.md`.

## Cursor Cloud specific instructions

### Services / components
This is a client-side IDE plugin — it has no backend of its own. The only external system it talks to is a **Nacos server**. All standard commands live in `CLAUDE.md` (build/compile/test/`runIde`/`verifyPlugin`); prefer those instead of duplicating them.

### JDK / toolchain (non-obvious)
- The Gradle Kotlin/Java **toolchain targets JDK 17** (`jvmToolchain(17)` in `build.gradle.kts`), but the machine default `java` is JDK 21.
- You do **not** need to set `JAVA_HOME`. Gradle runs its daemon on JDK 21 (fine for Gradle 9) and auto-detects the installed JDK 17 (`/usr/lib/jvm/java-17-openjdk-amd64`) for the toolchain via the `foojay-resolver` plugin. `./gradlew test`/`compileKotlin`/`buildPlugin` all work out of the box.
- JDK 17 is installed in the VM snapshot; it is not (re)installed by the update script.

### Running the app (`./gradlew runIde`)
- Requires a display; the cloud VM provides `DISPLAY=:1`, so `runIde` opens a real sandbox IDE window there.
- First launch shows the **JetBrains User Agreement** and a Data Sharing prompt — accept/dismiss them before you reach the Welcome screen.
- `runIde` starts with **no project open**. The "Nacos Search" tool window only appears inside a project window, so create/open a project first (e.g. an Empty Project).
- `runIde` is long-running; start it in a tmux session and drive the GUI via computer use.

### End-to-end testing against a real Nacos (no Docker needed)
- There is no Docker in this environment. To get a real backend, download the Nacos standalone tarball and run it with the JDK, e.g. `nacos-server-2.5.3` → `bash bin/startup.sh -m standalone` (listens on `http://localhost:8848`). Seed configs via the Open API (`POST /nacos/v1/cs/configs`).
- Standalone Nacos has **auth disabled by default**, so in the plugin's settings (Settings > Tools > Nacos Search) set the server's **Authentication mode to `ANONYMOUS`** (default is `TOKEN`) or connections/reads will fail.
- The gated `LiveSmokeTest` runs against a real server only when `NACOS_LIVE_V1_ENDPOINT` / `NACOS_LIVE_V3_ENDPOINT` are set (otherwise it self-skips): e.g. `NACOS_LIVE_V1_ENDPOINT=http://localhost:8848 ./gradlew test --tests "com.nanyin.nacos.search.services.operations.LiveSmokeTest.V1*"`.
