# 如何评估 Nacos Search 插件在 IntelliJ IDEA 中的性能与资源占用

**Date:** 2026-08-06  
**Question:** 如何评估本插件（Nacos Search）在 IntelliJ IDEA 中的性能表现与资源占用？给出可落地的评估方法、指标、工具、场景，并映射到本插件的实际热点路径。  
**Scope:** JetBrains 官方 Plugin SDK / Platform 博客 / Support KB + 本仓库代码与 ADR；不含二手博客汇总。

---

## 执行摘要

评估 IDE 插件性能的主轴不是「算得快不快」，而是 **EDT 是否被阻塞、后台读锁是否饿死写锁、启动与索引是否拖慢 IDE、以及插件自有缓存/网络是否在可控边界内**。JetBrains 官方给出的手段是：Threading Model + SlowOperations、自动 `threadDumps-freeze-*`、Help > Diagnostic Tools（CPU / Async Profiler / Memory / Dump Threads）、`PlatformTestUtil.startPerformanceTest`、FileBasedIndex 索引指标，以及可选的 `ErrorReportSink`/`UnhandledFreezeReport`。对本插件，热点集中在 `ProjectActivity` 启动预热、`CacheService`/`FileCacheStore`、`NamespaceIndexCoordinator`、导航 detail prefetch（并发 4）、以及 `@NacosValue` 的 FileBasedIndex / KeyIndex / LineMarker 路径；仓库已有 `PerformanceBenchmarkTest` 的纯文本微基准，但尚缺对 EDT 冻结、堆占用与端到端延迟的系统化门禁。

---

## 一、官方方法与工具

| 方法 / 工具 | 用途 | 官方来源 | 对本插件的用法 |
| --- | --- | --- | --- |
| **Threading Model + SlowOperations** | EDT 上禁止长操作（VFS/PSI/resolve/index）；`SlowOperations.assertSlowOperationsAreAllowed()` 在 EAP/internal/`runIde` 开发实例中断言违规 | [Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html) | 在 `./gradlew runIde`（开发实例）下打开含大量 `@NacosValue` 的项目，观察 idea.log 是否出现 SlowOperations；核对 line marker / settings / 工具窗是否在 EDT 触达 PasswordSafe（ADR-0039 明确禁止） |
| **非阻塞 / 可取消 Read Action** | 后台长读必须可取消，否则会阻塞 EDT 上的 write action，造成「后台代码导致 UI 冻结」 | Threading Model；[UI Freezes / non-cancellable read actions](https://blog.jetbrains.com/platform/2026/03/ui-freezes-and-the-dangers-of-non-cancellable-read-actions-in-background-threads/) | 审查 `NacosConfigKeyReferenceSearcher` 等处的 `ReadAction.compute`：短路径可接受；长扫描必须改 `ReadAction.nonBlocking` / coroutine `readAction`，并频繁 `checkCanceled` |
| **自动 freeze thread dump** | IDE 检测到 UI 锁定一段时间后，向 logs 目录写入 `threadDumps-freeze-<ts>-…-<N>sec` | [How to get a thread dump…](https://intellij-support.jetbrains.com/hc/en-us/articles/206544899)；日志示例中常见 `…-5sec`（约 5s 量级，以文件夹后缀为准） | 复现大命名空间加载 / 环境切换 / 搜索时，检查 sandbox 的 `log/threadDumps-freeze-*` 与 `idea.log` 中 `PerformanceWatcher` 的 “UI was frozen for …ms” |
| **Thread dump 分析（从 EDT 入手）** | 冻结时先看 `AWT-EventQueue-0`；若卡在 write lock，再搜 `readAction`；还要查 Default dispatcher 饥饿、`runBlocking`、service init 死锁 | [Investigating IntelliJ Platform UI Freezes](https://blog.jetbrains.com/platform/2025/09/investigating-intellij-platform-ui-freezes/) | 分析 dump 中是否出现 `com.nanyin.nacos.search.*`、`NamespaceIndexCoordinator`、`NavigationDetailPrefetchService`、`NacosKeyIndexService`、PasswordSafe / 网络栈 |
| **Help \| Diagnostic Tools \| Dump Threads** | 手动抓线程 + **协程 dump** | [Coroutine Dumps](https://plugins.jetbrains.com/docs/intellij/coroutine-dumps.html)；[Thread dumps (IDEA Help)](https://www.jetbrains.com/help/idea/thread-dumps.html) | 本插件大量使用 `Dispatchers.IO` / 应用级 `CoroutineScope`；协程 dump 比纯 Java dump 更能看到挂起的 prefetch / index flight |
| **Start / Stop CPU Usage Profiling** | 可交互时录制 CPU 快照（需重启注入 agent；CE 2025.x 可能需装 *Async Profiler for IDE Performance Testing*） | [Reporting performance problems](https://intellij-support.jetbrains.com/hc/en-us/articles/207241235) | 在 `runIde` 沙箱 IDE 中：Start → 执行下述压力场景 → Stop；快照落在用户 home，扩展名 `.zip` |
| **Async Profiler / JFR（IDEA Profiler）** | CPU + allocation；默认可并行跑 Async Profiler 与 JFR；Linux 需调 `perf_event_paranoid` / `kptr_restrict` | [Introduction to profiling](https://www.jetbrains.com/help/idea/profiler-intro.html)；[Custom profiler configurations](https://www.jetbrains.com/help/idea/custom-profiler-configurations.html) | 对沙箱 IDE 进程做 attach / 或对插件单元测试 JVM 做采样；关注 `FileCacheStore`、`ConfigKeyExtractor`、`Indexer.extractPlaceholderKeys`、Gson 反序列化 |
| **Capture Memory Snapshot / HeapDumpOnOutOfMemoryError** | 瞬时堆快照；OOM 自动 dump | 同上 Support KB | 大命名空间冷加载后抓堆，按 `com.nanyin.nacos.search` 与 `nacos-search-cache` 相关对象排序；同时用 `du` 看磁盘缓存目录 |
| **Profile Slow Startup / Profile Indexing** | 启动与项目索引专项快照（2023.2+ 常依赖 Marketplace 上的 YourKit/Async Profiler 配套插件） | Support KB 207241235 | 评估 `postStartupActivity`（`NacosSearchPlugin`）与 `NacosPlaceholderIndex` / `NacosDeclaredSourceIndex` 对 IDE 启动与 Java 索引的增量成本 |
| **`PlatformTestUtil.startPerformanceTest()`** | 单元/集成测试中断言**按机器校准**的性能指标 | [Testing FAQ](https://plugins.jetbrains.com/docs/intellij/testing-faq.html) | 当前仓库微基准用裸 `nanoTime`；长期门禁应迁移到该 API，避免 CI 机器差异误杀 |
| **FileBasedIndex + 索引性能指标** | 索引 JSON/HTML 指标写入 logs；实现侧避免在 indexer 中建完整 PSI AST | [Indexing and PSI Stubs](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html)；[File-Based Indexes](https://plugins.jetbrains.com/docs/intellij/file-based-indexes.html)；[PSI Performance](https://plugins.jetbrains.com/docs/intellij/psi-performance.html) | 本插件的 `NacosPlaceholderIndex` 已用文本 regex、不走 PSI（与官方「Avoid Using AST」一致）；用 Invalidate Caches 后对比 indexing metrics HTML |
| **ErrorReportSink / UnhandledFreezeReport** | 平台把归因到本插件的未处理异常与 **UI freeze**（含 duration、attachments、thread dumps）异步推给插件 | [Error Reporting](https://plugins.jetbrains.com/docs/intellij/error-reporting.html) | **本仓库尚未注册** `com.intellij.errorReportSink`；若要做生产可观测性可接入，但须自备去重与限流（平台不保证投递，且每会话最多转发约 1 万条异常） |
| **Collect Logs and Diagnostic Data** | 打包 idea.log + 自动 freeze dump | [Locating IDE log files](https://intellij-support.jetbrains.com/hc/en-us/articles/207241085) | 每次性能回归工单附带该 zip |

**关于「约 5 秒自动 dump」：** Support 文章写明 IDE 在 UI 被锁定「一段时间」后自动落盘，文件夹名形如 `threadDumps-freeze-…-5sec`；社区日志中亦常见 `UI was frozen for 5xxxms …-5sec`。这是 **PerformanceWatcher 的观测产物命名**，不是 Plugin SDK 中可调用的稳定 API。不要把「5 秒」当成硬编码公共常量去依赖；以实际 log 目录后缀与冻结毫秒数为准。

**未在一手资料中确认、本文不臆造的内容：** 不存在名为「Plugin Performance Test Framework」的单一官方 SDK 任务专用于 Marketplace 插件端到端 SLA；Support 上的 *Performance testing plugin* 文章描述的是 IDE Diagnostic 菜单与性能脚本命令（如 `%startProfile`），属于诊断辅助而非本仓库已集成的 Gradle 任务。

---

## 二、对 IDE 插件真正重要的指标

| 指标 | 含义 | 怎么量 | 「差」的信号 |
| --- | --- | --- | --- |
| **UI freeze / EDT stall** | EDT 无法处理输入与重绘；官方提醒事件宜在 ~16ms 内处理以维持约 60fps | `threadDumps-freeze-*`、`PerformanceWatcher` 日志、手动 Dump Threads | 出现 ≥数秒的 freeze 文件夹；EDT 栈在插件代码、网络 I/O、PasswordSafe、或被长 read action 饿死 |
| **Write-lock starvation** | 后台不可取消的 `ReadAction.compute` 持锁过久 | freeze dump 中 EDT 在 `upgradeWritePermit` / `runWriteAction`，BGT 在 `runReadAction`/`ReadAction.compute` | 用户打字卡顿，即使「重活」不在 EDT |
| **Startup impact** | `ProjectActivity` / 服务初始化是否阻塞打开项目 | Profile Slow Startup；对比启用/禁用插件的冷启动到可输入时间 | 打开项目后长时间无法输入；`CacheService` 构造若同步读盘会拖死 EDT（当前实现已后台加载） |
| **Memory retained** | 堆内缓存 + 派生索引 + Gson 中间对象 | Memory Snapshot；live charts | 大命名空间后堆持续上升不回落；`KeyIndex` 与 detail 映射重复持有大字符串 |
| **Disk cache size** | `FileCacheStore` 在 IDE config 路径下的 `nacos-search-cache/{details,listpages}/` | `du -sh` 该目录；条目数相对 `MAX_CACHE_SIZE=1000` | 远超预期的 MB/GB；升级遗留 schema 未回收（ADR-0018） |
| **Background CPU / IO 争用** | 与索引、高亮、Gradle 同步抢核与磁盘 | CPU snapshot；系统 `iotop`/`perf`（非 JetBrains API） | 空闲编辑时插件线程仍占高 CPU；磁盘写放大 |
| **Indexing / FileBasedIndex cost** | 每文件 indexer 时间与索引体积 | logs 目录 indexing metrics JSON/HTML；Profile Indexing | Java 文件保存后索引明显变慢；indexer 内触发 PSI |
| **PSI / Line marker / Find Usages 延迟** | gutter 与反向查找体感 | 手动秒表 + CPU snapshot；微基准覆盖提取路径 | 打开大 Java 文件时 gutter 数秒才出；Find Usages 无进度/不可取消 |
| **Network concurrency** | 同时打向 Nacos 的 HTTP 数 | 服务端 access log / 代理；代码内 Semaphore | 打满网关、触发限流；与 IDE 其他网络任务互相饿死 |

---

## 三、本插件热点路径清单

下列路径来自 `CLAUDE.md`、ADR 与当前源码（以代码为准；changelog 中的历史说法单独标注）。

### 1. 启动：`NacosSearchPlugin`（`ProjectActivity`）

路径：`src/main/kotlin/com/nanyin/nacos/search/NacosSearchPlugin.kt`

- `execute` → `initializePlugin`：校验设置；若开启缓存则在 `Dispatchers.IO` 上 `getAllCachedConfigurations`；后台 `testConnection`；缓存空则 `loadInitialData`（`listConfigurations` 首页 pageSize=200）→ `NacosKeyIndexService.ensureIndexBuilt` → `preheatNamespaceIndex`。
- Preheat：对每个打开的非 default 项目调用 `NavigationDetailPrefetchService.requestIfNeeded`（**不等待** namespace index 完成，ADR-0043）；同时 `NamespaceIndexCoordinator.requestStartupNamespaceIndex`。
- 约束：启动不得被遗留 schema 迁移阻塞（ADR-0018）；凭证不得在 EDT 读取（ADR-0039）。

**评估焦点：** 打开项目后 0–30s 的 CPU、IO、是否产生 freeze dump；对比 cache 冷/热。

### 2. 持久缓存：`CacheService` + `FileCacheStore`

- 内存三张表：detail / list page / namespace index；硬上限 `MAX_CACHE_SIZE = 1000`（另有 `CLEANUP_BUFFER = 100` 触发清扫）；TTL 默认来自 `NacosSettings.cacheTtlMinutes = 5` → `getCacheTtlMillis()`。
- 磁盘：`…/nacos-search-cache/{details,listpages}/`，按 key 的 SHA-256 分文件；后台 `loadCacheFromPersistence`，构造立即返回，读路径 await `loadCompleted`。
- 唯一写入口：`applyMutation`（ADR-0044/0045/0052）；观测序门禁防止陈旧写覆盖。

**评估焦点：** 1000 条大 content 后的堆与目录体积；clear 后是否释放；升级后 adopt ≤1000 条（`MAX_ADOPTED_RECORDS`）。

### 3. 远程读写与「会话缓存」

- 列表/详情经 `OperationGateway`；生产适配器 `CacheServiceOperationCache` 把 summary/detail 写入 **同一套** `CacheService`（TTL = settings），而不是独立的第二套长期内存表。测试可用 `InMemoryOperationCache`。
- 命名空间全量：`NacosApiService.loadNamespace` 分页 **pageSize=100**，只拉 **summary 元数据**（ADR-0016），完整性不依赖 detail。
- **历史 changelog**（`plugin.xml` change-notes）仍写着 `getAllConfigurations`「并发 bounded at 8」——这是旧 N+1 detail 拉取路径的性能说明；当前架构已禁止列表路径隐式拉正文（ADR-0016/0041）。**评估时不要再按「bound=8 的 getAllConfigurations」测，应按 `loadNamespace` + prefetch 测。**

### 4. 命名空间索引：`NamespaceIndexCoordinator`

- 同 identity+namespace **单飞（single-flight）**。
- PSI 触发失败后 **5 分钟冷却**（`psiCooldownMs`）。
- SEARCH / MANUAL_REFRESH：**15s** 前端 cutoff（`withTimeoutOrNull(15_000)`）；超时 → `IndexOutcome.Stale`。
- 传输重试集中在 ADR-0021 策略（interactive：连接/读超时与有限次重试）。

**评估焦点：** 超大命名空间下 SEARCH 是否在 15s 内给出可用/降级态；并发多次 refresh 是否只打一发网络。

### 5. 导航 Detail Prefetch：`NavigationDetailPrefetchService`

- `PREFETCH_CONCURRENCY = 4`（`Semaphore`）；无声明源时 `FALLBACK_BUDGET = 32`。
- 工作集来自 `NacosDeclaredSourceIndex`（FileBasedIndex），成本跟项目声明的 dataId 走，不跟命名空间膨胀（ADR-0041）。
- 与 namespace index **独立飞行**（ADR-0043）。

**评估焦点：** 声明 0 / 数十 / 数百 dataId 时的 HTTP 并发峰值与完成时间；关闭 prefetch 开关后 gutter 覆盖率下降是否符合预期。

### 6. PSI / 派生索引

| 组件 | 角色 | 性能相关事实 |
| --- | --- | --- |
| `NacosPlaceholderIndex` | FileBasedIndex：`${…}` → Java 文件 | indexer 用 regex，避免 PSI；`getVersion()=2` |
| `NacosDeclaredSourceIndex` | 声明的配置源 dataId | 供 prefetch 工作集 |
| `NacosKeyIndexService` / `NacosKeyResolver` | 从 `CacheSnapshot` 派生 key→定义；自有 scope | 版本驱动重建；可见性签名不一致则拒服旧索引（ADR-0051 / #126） |
| `NacosValueLineMarkerProvider` | gutter 三态 | 在 marker 路径上 `snapshot` + resolve；未解析时后台 refresh；**不得**在 EDT 读 PasswordSafe（ADR-0039） |
| `NacosConfigKeyReferenceSearcher` | Find Usages | 先 FileBasedIndex，再 `ReadAction.compute` 做 PSI 校验 |

### 7. 工具窗搜索 UI

- `NacosSearchService.searchDelayMs = 300` 防抖。
- 会话世代：环境切换丢弃旧结果（ADR-0010 / 搜索 session 模型）。
- 凭证捕获走 `Dispatchers.IO`，Swing 处理器不碰 PasswordSafe（ADR-0039 / ADR-0046）。
- 脏草稿时 refresh-all **不**丢 draft（ADR-0027）；评估时勿把「未清 detail」误判为泄漏。

### 8. 已有微基准

`src/test/kotlin/com/nanyin/nacos/search/psi/PerformanceBenchmarkTest.kt`：

| 用例 | 门槛（仓库已编码） |
| --- | --- |
| 1000 文件文本中提取 ≥5000 placeholder | 平均 **&lt; 100ms** |
| 10000 次 `PlaceholderParser.parse` | **&lt; 50ms** |
| 500 段 YAML `ConfigKeyExtractor` | **&lt; 100ms**（注释写 20ms，断言为 100ms） |

这些是 **纯 JVM 字符串路径**，不覆盖 EDT、索引、网络或堆。

### 9. 相关 ADR（性能行为约束）

| ADR | 与性能/资源的关系 |
| --- | --- |
| 0016 | 列表/索引只拉 metadata → 避免 N×detail HTTP |
| 0018 | 遗留 cache 有界后台回收，不堵启动 |
| 0021 | 统一可取消传输与有界重试 |
| 0039 | 凭证离开 EDT；AUTO 走 gateway |
| 0041 | prefetch 与 namespace index 分离；prefetch 按声明源缩放 |
| 0043 | prefetch 独立触发，不被 index TTL 绑架 |
| 0051 | KeyIndex 生命周期离开 Cache；版本化 snapshot，避免无意义重建 |

---

## 四、建议指标与通过线

标签说明：**[已编码]** = 仓库测试/常量已写死；**[官方平台]** = JetBrains 产品行为；**[推测]** = 建议工程门槛，需用本机基线校准后再固化。

| 场景 | 指标 | 建议通过线 |
| --- | --- | --- |
| Indexer 文本提取 | 平均耗时 | **&lt; 100ms**（5000 keys） **[已编码]** |
| Placeholder 解析 | 10000 次 | **&lt; 50ms** **[已编码]** |
| YAML key 提取 | 500 段配置 | **&lt; 100ms** **[已编码]** |
| UI freeze | 自动 dump | 压力场景下 **0** 个含 `com.nanyin.nacos.search` 栈的 `threadDumps-freeze-*` **[推测]**；平台仍可能为其他插件/平台自身落盘 **[官方平台]** |
| SlowOperations | idea.log | `runIde` 开发实例下本插件路径 **0** 条新断言 **[推测]** |
| 搜索防抖 | 行为 | 连续输入只发一发网络（300ms） **[已编码行为]** |
| Namespace index（交互） | 前端等待 | SEARCH/手动刷新 **≤ 15s** 给出 Complete/Partial/Stale，不无限挂起 **[已编码]** |
| Prefetch 并发 | 在途 HTTP | ≤ **4** **[已编码]**；无声明源时目标 ≤ **32** **[已编码]** |
| 缓存条目 | 内存 map | 稳态 ≤ **1000**/类（+cleanup buffer） **[已编码]** |
| TTL | 默认 | **5 min**（可配置） **[已编码]** |
| 打开工具窗首搜（热缓存） | 体感 | **[推测]** &lt; 1s 出列表（依赖本地缓存命中与网络无关） |
| 冷启动大命名空间（仅 metadata） | 完成时间 | **[推测]** 与页数×RTT 线性相关；应无 EDT freeze；可用 15s Stale 降级 |
| Gutter 首次亮起（已预热 KeyIndex） | 打开 Java 文件 | **[推测]** 高亮周期内可见；不触发 EDT 上的网络/PasswordSafe |
| Find Usages | 可取消性 | **[推测]** 取消搜索后 CPU 回落；索引查询路径占主导 |
| 磁盘缓存 | `nacos-search-cache` | **[推测]** 与条目数×平均 content 体积同阶；clear cache 后目录显著下降 |
| Live smoke | 契约 | `NACOS_LIVE_V1_ENDPOINT` / `V3` 下现有 `LiveSmokeTest` 通过（正确性门禁，非性能 SLA） **[已编码跳过逻辑]** |

---

## 五、可落地评估剧本（Playbook）

### 步骤 0 — 准备

1. JDK 17 工具链（Gradle 已配置）；`./gradlew test --tests "com.nanyin.nacos.search.psi.PerformanceBenchmarkTest"` 先拿到微基准基线。
2. 可选真实 Nacos：standalone + `ANONYMOUS`；或 `NACOS_LIVE_V1_ENDPOINT=… ./gradlew test --tests "…LiveSmokeTest…"`。
3. 记下 sandbox 路径：`runIde` 的 `idea-sandbox` / log / config（Gradle IntelliJ Platform 插件惯例）；缓存目录为 config 下 `nacos-search-cache/`。

### 步骤 1 — 微基准（单元）

```bash
./gradlew test --tests "com.nanyin.nacos.search.psi.PerformanceBenchmarkTest"
```

- **已有：** 文本索引提取、placeholder、YAML key。
- **建议补（未实现，标为缺口）：**
  - `NacosKeyResolver` 在 N 个 detail × M key 下的重建耗时（绑定 `CacheSnapshot.version`）。
  - `FileCacheStore` 读写 N 个大 payload 的吞吐（临时目录）。
  - 使用 `PlatformTestUtil.startPerformanceTest` 替换裸阈值，减少 CI 抖动。

### 步骤 2 — `runIde` + CPU / Async Profiler

```bash
./gradlew runIde
```

在沙箱 IDE 中：

1. 安装/确认 CE 所需的 **Async Profiler for IDE Performance Testing**（若 Help 菜单无 CPU 项——见 Support KB 207241235）。
2. **Help → Diagnostic Tools → Start CPU Usage Profiling**（首次可能要求重启）。
3. 执行步骤 5 的场景清单（每个场景重复 2–3 次）。
4. **Stop CPU Usage Profiling**，保存 `.zip` 快照。
5. 在宿主 IDEA 中打开快照，过滤 `com.nanyin.nacos.search`，记录 Top 方法。

Linux 上若用 IDEA 内置 Async Profiler attach 其他进程，按 [Custom profiler configurations](https://www.jetbrains.com/help/idea/custom-profiler-configurations.html) 调整 `perf_event_paranoid` / `kptr_restrict`。

### 步骤 3 — 冻结检测

1. 复现可疑操作期间与之后检查：
   - sandbox `log/threadDumps-freeze-*`
   - `idea.log` 中 `PerformanceWatcher` / `UI was frozen`
2. 用 **Code → Analyze Stack Trace or Thread Dump**（或 Search Everywhere）粘贴完整 dump。
3. 按官方博客顺序：`AWT-EventQueue-0` → 是否 write lock → 搜 `readAction` → 协程树中的 `BlockingCoroutine` / `Cancelling` → 是否 `com.nanyin.nacos.search`。
4. 主动抓：**Help → Diagnostic Tools → Dump Threads**（含协程）。

### 步骤 4 — 内存与磁盘

1. 大命名空间加载 + prefetch 完成后：**Help → Diagnostic Tools → Capture Memory Snapshot**。
2. 按 package 查看 `CacheService` 条目、字符串 content、`KeyIndex`。
3. `du -sh <config>/nacos-search-cache`；执行 Tools → Clear Cache 后再测一次。
4. 可选：vmoptions 加 `-XX:+HeapDumpOnOutOfMemoryError`（Support KB）。

### 步骤 5 — Nacos Search 专用压力场景

| # | 场景 | 观察点 |
| --- | --- | --- |
| A | **大命名空间冷缓存**：数千 config，仅 metadata index | HTTP 页数、15s 是否 Stale、有无 freeze、CPU 是否在 IO dispatcher |
| B | **热缓存重启 IDE**：已有 `nacos-search-cache` | 启动是否仍响应；后台 load 完成前后 gutter 行为 |
| C | **大量 `@NacosValue` / `@Value`**：千级 Java 文件 | 索引时间、gutter 延迟、Find Usages；indexing metrics |
| D | **搜索中途切换环境** | 旧请求结果不得画到新环境；CPU/网络是否被取消 |
| E | **脏草稿 + Refresh All** | UI 不丢 draft；detail 不误清；无多余 detail HTTP |
| F | **声明源 prefetch on/off** | 并发 ≤4；fallback 32；覆盖率文案 |
| G | **PSI 触发 index 失败后 5min** | 冷却期内不狂打网络 |
| H | **Clear Cache 后立刻搜索/导航** | 观测序门禁：旧 in-flight 写被丢；随后 reload 成功 |

### 步骤 6 — 可选 Live Smoke

```bash
NACOS_LIVE_V1_ENDPOINT=http://localhost:8848 ./gradlew test --tests "com.nanyin.nacos.search.services.operations.LiveSmokeTest.V1*"
```

用于正确性与真实 RTT 基线，**不**替代 freeze/CPU 评估。

### 步骤 7 — 记录模板（建议）

每次跑完整剧本归档：IDE 版本、插件版本、命名空间规模、场景 ID、是否有 freeze dump、CPU zip 路径、堆快照路径、`du` 结果、微基准输出、结论（通过/回归）。

---

## 六、缺口（本仓库尚未系统测量的部分）

1. **无自动化 EDT/freeze 门禁**：没有测试去断言「场景 X 不产生 PerformanceWatcher freeze」。
2. **微基准未使用 `PlatformTestUtil.startPerformanceTest`**：机器校准缺失，CI 可能抖。
3. **无堆/磁盘回归测试**：`MAX_CACHE_SIZE` 与 FileCacheStore 体积靠人工。
4. **未注册 `ErrorReportSink`**：生产环境归因到本插件的 `UnhandledFreezeReport` 不会进入自有观测。
5. **端到端 UI 延迟未量化**：工具窗搜索、gutter、Find Usages 无官方推荐之外的录制脚本（Support 的 performance script 命令集存在，但未接入本构建）。
6. **changelog 与架构漂移**：`getAllConfigurations` bound=8 已过时；文档/评估若不更新会测错对象。
7. **`ReadAction.compute` 仍用于 Find Usages 校验路径**：短则无妨；若未来在 read action 内做重活，需按 2026-03 博客改为可取消 API——目前缺少专门的冻结回归用例盯住这一点。
8. **索引成本**：有 FileBasedIndex 实现与文本提取基准，但未保存/对比 IDE indexing metrics HTML 作为发布检查项。

---

## 七、Sources

### JetBrains 官方 / 一手

1. IntelliJ Platform Plugin SDK — [Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)（EDT、SlowOperations、NBRA、Avoiding UI Freezes）  
2. JetBrains Platform Blog — [Investigating IntelliJ Platform UI Freezes](https://blog.jetbrains.com/platform/2025/09/investigating-intellij-platform-ui-freezes/)（thread dump 调查流程）  
3. JetBrains Platform Blog — [UI Freezes and Non-Cancellable Read Actions](https://blog.jetbrains.com/platform/2026/03/ui-freezes-and-the-dangers-of-non-cancellable-read-actions-in-background-threads/)  
4. IntelliJ Support — [How to get a thread dump when IDE hangs](https://intellij-support.jetbrains.com/hc/en-us/articles/206544899)（`threadDumps-freeze-*`）  
5. IntelliJ Support — [Reporting performance problems](https://intellij-support.jetbrains.com/hc/en-us/articles/207241235)（Diagnostic Tools：CPU / Memory / Slow Startup / Indexing；Async Profiler 插件说明）  
6. IntelliJ Support — [Locating IDE log files](https://intellij-support.jetbrains.com/hc/en-us/articles/207241085)  
7. IntelliJ IDEA Help — [Introduction to profiling](https://www.jetbrains.com/help/idea/profiler-intro.html)  
8. IntelliJ IDEA Help — [Custom profiler configurations](https://www.jetbrains.com/help/idea/custom-profiler-configurations.html)（Async Profiler / JFR）  
9. IntelliJ IDEA Help — [Thread dumps](https://www.jetbrains.com/help/idea/thread-dumps.html)  
10. Plugin SDK — [Coroutine Dumps](https://plugins.jetbrains.com/docs/intellij/coroutine-dumps.html)（Help → Diagnostic Tools → Dump Threads）  
11. Plugin SDK — [Error Reporting](https://plugins.jetbrains.com/docs/intellij/error-reporting.html)（`UnhandledFreezeReport` / `ErrorReportSink`）  
12. Plugin SDK — [Testing FAQ — performance test](https://plugins.jetbrains.com/docs/intellij/testing-faq.html)（`PlatformTestUtil.startPerformanceTest`）  
13. Plugin SDK — [File-Based Indexes](https://plugins.jetbrains.com/docs/intellij/file-based-indexes.html)  
14. Plugin SDK — [Indexing and PSI Stubs](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html)（indexing metrics、Avoid AST）  
15. Plugin SDK — [PSI Performance](https://plugins.jetbrains.com/docs/intellij/psi-performance.html)  

### 本仓库

16. `CLAUDE.md` / `AGENTS.md` — 架构与命令总览  
17. `src/main/kotlin/com/nanyin/nacos/search/NacosSearchPlugin.kt` — 启动与预热  
18. `src/main/kotlin/com/nanyin/nacos/search/services/CacheService.kt` — `MAX_CACHE_SIZE=1000`、后台 load  
19. `src/main/kotlin/com/nanyin/nacos/search/services/FileCacheStore.kt` — 磁盘布局与 adopt 上限  
20. `src/main/kotlin/com/nanyin/nacos/search/services/NamespaceIndexCoordinator.kt` — 单飞、15s、PSI 5min cooldown  
21. `src/main/kotlin/com/nanyin/nacos/search/services/NavigationDetailPrefetchService.kt` — 并发 4、fallback 32  
22. `src/main/kotlin/com/nanyin/nacos/search/services/NacosSearchService.kt` — `searchDelayMs = 300`  
23. `src/main/kotlin/com/nanyin/nacos/search/settings/NacosSettings.kt` — `cacheTtlMinutes = 5`  
24. `src/main/kotlin/com/nanyin/nacos/search/psi/NacosPlaceholderIndex.kt`、`PerformanceBenchmarkTest.kt`  
25. ADRs：`docs/adr/0016-*.md`、`0018-*.md`、`0021-*.md`、`0039-*.md`、`0041-*.md`、`0043-*.md`、`0051-*.md`  
26. `src/main/resources/META-INF/plugin.xml` — 历史 change-notes（bound at 8；评估时需对照现行 ADR-0016）  

---

*本文仅记录已核实的一手来源与仓库事实；标注 [推测] 的阈值在固化进 CI 前应用本机/CI 基线重新校准。*
