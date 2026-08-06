# 如何评估 Nacos Search 在 IntelliJ IDEA 中的性能与资源占用

> 日期：2026-08-06  
> 范围：IntelliJ Platform 插件性能评估方法 + 对本仓库热点路径的映射  
> 性质：研究笔记（方法论与可执行 playbook），不是一次实测报告

## 摘要

评估本插件在 IDEA 中的表现，核心不是「CPU 百分比多少算好」，而是：**EDT 是否被阻塞、后台工作是否可取消/有界、缓存与索引是否把磁盘与堆撑大、PSI/索引热路径是否拖慢 highlighting**。JetBrains 官方把 UI 响应（约 16 ms 事件预算、冻结时自动 thread dump）和线程模型（EDT vs BGT、读写锁）放在第一位；本插件已在架构上把网络、磁盘、凭据读出 EDT，并用有界并发与 TTL 约束资源。下文给出官方工具、本插件热点清单、建议指标，以及可逐步执行的评估 playbook。

## 1. 官方评估维度与工具

| 维度 | 关注什么 | 官方手段 | 一手来源 |
| --- | --- | --- | --- |
| UI 冻结 / EDT 阻塞 | EDT 长时间无法处理输入与重绘；写锁等待读锁 | 日志目录下自动 `threadDumps-freeze-*-Nsec`；手动 `jstack` / Ctrl+Break；从 AWT-EventQueue-0 起读 | [Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)、[Investigating UI Freezes](https://blog.jetbrains.com/platform/2025/09/investigating-intellij-platform-ui-freezes/)、[Thread dump KB](https://intellij-support.jetbrains.com/hc/en-us/articles/206544899) |
| SlowOperations | 在 EDT 上做了本该在 BGT 的重活 | EAP / internal / `runIde` 下的 `SlowOperations` 断言 | [Threading Model § Slow Operations](https://plugins.jetbrains.com/docs/intellij/threading-model.html) |
| CPU / 分配 | 哪条调用栈吃 CPU、谁在狂分配 | IntelliJ Profiler（默认 Async Profiler + JFR）；火焰图 / 调用树 | [CPU & allocation profiling](https://www.jetbrains.com/help/idea/cpu-and-allocation-profiling-basic-concepts.html)、[Custom profiler configs](https://www.jetbrains.com/help/idea/custom-profiler-configurations.html) |
| 实时资源曲线 | 堆、非堆、线程数、进程 CPU 随操作变化 | View → Tool Windows → Profiler → CPU and Memory Live Charts | [CPU and memory live charts](https://www.jetbrains.com/help/idea/cpu-and-memory-live-charts.html) |
| 索引成本 | FileBasedIndex 建索引是否拖慢项目打开 | logs 目录下 indexing performance metrics（JSON；2021.1+ 另有 HTML） | [Indexing and PSI Stubs](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html) |
| 自动化性能断言 | 单元/集成层对耗时做机器归一化断言 | `PlatformTestUtil.startPerformanceTest()`；Gradle `intellijPlatformTesting.testIdePerformance` | [Testing FAQ](https://plugins.jetbrains.com/docs/intellij/testing-faq.html)、[Testing Extension](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-testing-extension.html) |
| 冻结上报 | 生产/内部构建把冻结归因到插件 | `UnhandledFreezeReport`（含 thread dumps / attachments） | [Error Reporting](https://plugins.jetbrains.com/docs/intellij/error-reporting.html) |
| 协程调度 | CPU 密集任务是否挤占过多并行度 | `Dispatchers.Default`（按核数限并行）；IO 用 IO dispatcher | [Coroutine Dispatchers](https://plugins.jetbrains.com/docs/intellij/coroutine-dispatchers.html) |

### 1.1 平台硬约束（评估时必须对齐）

- **EDT 事件应尽量在约 16 ms 内完成**，否则无法稳定 60 fps；冻结 = EDT 长时间无法跑事件循环。([Investigating UI Freezes](https://blog.jetbrains.com/platform/2025/09/investigating-intellij-platform-ui-freezes/))
- **BGT 上长时间持有 read lock** 会拖住 EDT 上的 write action，表现为「代码不在 EDT 却仍卡 UI」。([Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html))
- IDE 在 UI 卡住一段时间后会**自动**往日志目录写 `threadDumps-freeze-…-5sec` 一类目录（秒数因版本/配置而异）。([Thread dump KB](https://intellij-support.jetbrains.com/hc/en-us/articles/206544899))
- Linux 上 Async Profiler 可能需要调整 `perf_event_paranoid` / `kptr_restrict`。([Custom profiler configurations](https://www.jetbrains.com/help/idea/custom-profiler-configurations.html))

## 2. 本插件热点路径清单

下列路径是「测什么」的坐标。数字来自当前源码；若与旧文档冲突，以源码为准。

### 2.1 启动（`ProjectActivity`）

`NacosSearchPlugin.execute` → `initializePlugin`（`src/main/kotlin/.../NacosSearchPlugin.kt`）：

1. 校验 `NacosSettings`；失败则早退。
2. **后台**加载持久化缓存（不挡 EDT）。
3. **后台**测连；成功且缓存空/禁用时拉首屏元数据（`listConfigurations` pageSize=200）。
4. `NacosKeyIndexService.ensureIndexBuilt(snapshot)` 预热 `@NacosValue` 键索引。
5. `preheatNamespaceIndex`：后台全量 namespace索引 + 对各打开工程触发 `NavigationDetailPrefetchService.requestIfNeeded`（ADR-0043：预取不依赖索引完成）。

评估点：打开工程后 IDE 是否仍可立即打字/导航；日志里启动协程是否异常刷屏；冷启动 vs 暖缓存差异。

### 2.2 缓存与磁盘

| 组件 | 资源边界 | 路径 |
| --- | --- | --- |
| `CacheService` | 内存三表各最多约 **1000** 条；默认 TTL **5 min**；锁免费读 + 后台回收 | `CacheService.kt` `MAX_CACHE_SIZE` |
| `FileCacheStore` | 每条目一 JSON 文件（SHA-256 名）于 IDE config 下 `nacos-search-cache/{details,listpages}/`；升级采纳旧记录上限 **1000**（ADR-0018） | `FileCacheStore.kt` |
| `NacosApiService` | 会话内按 namespace 的短时响应缓存（架构说明：约 5 min TTL） | `NacosApiService.kt` / `CLAUDE.md` |

评估点：大 namespace加载后堆与 `nacos-search-cache` 目录体积；Clear Cache 后是否回落；Clear 与在途写的竞态（ADR-0045）不在此测正确性，但可观测「清完是否仍有突变写」。

### 2.3 网络与命名空间索引

| 组件 | 行为 | 资源含义 |
| --- | --- | --- |
| `NamespaceIndexCoordinator` | 同 key **单飞**；SEARCH/MANUAL_REFRESH 前端等待约 **15 s**；PSI 失败后 **5 min** 冷却 | 避免雪崩请求与长时间挂起 |
| `NacosApiService.loadNamespace` | **只分页拉 summary**（pageSize=100），不再在索引路径并发拉 detail（ADR-0041） | 大命名空间仍是多次 HTTP，但比「每条再拉 body」轻 |
| `NavigationDetailPrefetchService` | 详情预取并发 **`Semaphore(4)`**；无声明源时预算 `FALLBACK_BUDGET = 32` | 控制导航预热流量 |
| `NacosSearchService` | 输入防抖 **300 ms**；会话世代丢弃过期结果 | 降低连打请求 |

> 注意：`CLAUDE.md` 仍写 `getAllConfigurations`「并发上限 8」；当前树中已无该方法。评估时以 `loadNamespace` + 预取并发 4 为准。

### 2.4 PSI / 索引 / 导航

| 组件 | 热路径特征 |
| --- | --- |
| `NacosPlaceholderIndex` / `NacosDeclaredSourceIndex` | `FileBasedIndex`，仅 Java；Indexer 用**正则扫文本**，避免索引期走 PSI（见类注释） |
| `PerformanceBenchmarkTest` | 微基准：1000 文件×5 引用提取 &lt;100 ms；1 万 placeholder 解析 &lt;50 ms；500-key YAML 提取（断言 &lt;100 ms） |
| `NacosKeyIndexService` / `NacosKeyResolver` | 派生键索引；快照版本变化时后台重建；可见性不兼容时拒服旧索引（issue #126） |
| `NacosValueLineMarkerProvider` | Gutter 三态；应只读缓存快照，重活离 highlighter |
| `NacosConfigKeyReferenceSearcher` | Find Usages 走 FileBasedIndex，再 PSI 校验假阳性 |

评估点：indexing metrics 中本插件两个 index 的耗时占比；打开含大量 `@Value`/`@NacosValue` 的文件时 gutter 是否造成卡顿；Find Usages 是否可取消。

### 2.5 UI / EDT 纪律（设计约束）

- 凭据与 `PasswordSafe` **不得在 EDT 读**（ADR-0039）；UI 消费离线快照。
- 搜索会话在 `ToolWindowSearchController` / `NacosSearchService`，捕获 operation context 在 `Dispatchers.IO`（ADR-0054）。
- 稳定性规格要求：网络、缓存文件 I/O、全量加载、重解析不在 EDT；大结果集更新不做无界 EDT 工作（`docs/superpowers/specs/2026-07-11-stability-and-small-features-design.md`）。

## 3. 建议指标与阈值

标记：

- **[已锚定]**：仓库测试或源码常量已给出数字  
- **[推测]**：工程经验建议，需在本机基线校准后再当门槛  

| 指标 | 建议门槛 | 标记 | 怎么量 |
| --- | --- | --- | --- |
| EDT 卡顿 / UI freeze | 正常操作路径 **无** `threadDumps-freeze-*`；偶发 &lt;1 s 可忽略，≥5 s 必查 | [推测]（平台约 5 s 自动 dump） | 日志目录；操作时连打编辑器 |
| SlowOperations | `runIde` 下本插件栈 **零** 断言 | [已锚定] 平台规则 | 开发实例控制台 |
| Placeholder 索引提取 | 5000 keys / 合成 1000 文件 **&lt;100 ms** avg | [已锚定] `PerformanceBenchmarkTest` | `./gradlew test --tests …PerformanceBenchmarkTest` |
| Placeholder 解析 | 10000 literals **&lt;50 ms** | [已锚定] 同上 | 同上 |
| YAML key 提取 | 500-key 文档 **&lt;100 ms**（注释写 20 ms，断言为 100 ms） | [已锚定] 同上 | 同上 |
| 搜索防抖 | 输入停止后约 **300 ms** 才发搜 | [已锚定] `searchDelayMs` | 日志 / Profiler 墙钟 |
| 命名空间索引等待（搜索/手动刷新） | 前端约 **15 s** 截止；超时应降级而非挂死 UI | [已锚定] coordinator 注释 | 人为慢网 / 断网 |
| 详情预取并发 | ≤ **4** 路并行 HTTP | [已锚定] `PREFETCH_CONCURRENCY` | 抓包或调试计数 |
| 内存缓存条目 | 每表 ≤ **~1000** | [已锚定] `MAX_CACHE_SIZE` | 统计 API / 堆观察 |
| 磁盘缓存体积 | 随条目增长；Clear 后明显下降；升级采纳 ≤1000 | [已锚定] ADR-0018 | 量 `nacos-search-cache` |
| 打开工具窗口首搜（暖缓存） | **[推测]** 列表首屏 &lt;300 ms 感知延迟 | [推测] | 秒表 + Live Charts |
| 冷启动连 Nacos + 大 namespace索引 | **[推测]** 可后台完成；编辑器全程可输入 | [推测] | 手动 + freeze 目录为空 |
| FileBasedIndex 增量 | **[推测]** 本插件两 index 不显著拉长项目 indexing HTML 报告 | [推测] | indexing metrics |

## 4. 可执行评估 Playbook

### 阶段 A — 微基准（无 IDE UI）

```bash
./gradlew test --tests "com.nanyin.nacos.search.psi.PerformanceBenchmarkTest"
```

可选：对关键算法改用官方 `PlatformTestUtil.startPerformanceTest()`，获得**按机器性能归一化**的断言（[Testing FAQ](https://plugins.jetbrains.com/docs/intellij/testing-faq.html)）。本仓库尚未使用该 API。

可选：注册 `intellijPlatformTesting.testIdePerformance { … }`（[Testing Extension](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-testing-extension.html)）。当前 `build.gradle.kts` **未**注册该任务。

### 阶段 B — 开发实例 + 实时资源

1. `./gradlew runIde`（云环境需 `DISPLAY`；先开一个工程，工具窗口才出现）。
2. 在**宿主** IDEA 或对 sandbox JDK 进程：View → Profiler → 选中 sandbox 进程 → **CPU and Memory Live Charts**。
3. 记录基线：空闲堆、线程数。
4. 配置 Nacos（本地 standalone 可用 `ANONYMOUS`），打开 Nacos Search 工具窗口。

### 阶段 C — 场景矩阵（每个场景：Live Charts 截图 + 是否产生 freeze dump）

| # | 场景 | 期望 |
| --- | --- | --- |
| C1 | 冷启动：空缓存，连真实/种子 Nacos，打开工程 | EDT 可编辑；索引在后台；无 freeze 目录 |
| C2 | 暖启动：已有 `nacos-search-cache` | 启动更轻；gutter 更快出现 |
| C3 | 大命名空间（数百～上千 dataId）手动 Refresh | CPU/网络有尖峰但 UI 可点；15 s 策略可感知超时/部分结果 |
| C4 | 工具窗口连打搜索框 | 防抖 300 ms；无请求风暴；切换环境时旧结果不串场 |
| C5 | 含大量 `@NacosValue`/`@Value` 的 Java 工程滚动/打开 | gutter 不造成明显 stutter；indexing metrics 可接受 |
| C6 | Find Usages 于一热门 key | 可取消；结果与索引一致 |
| C7 | 环境切换 / 命名空间切换（含搜索进行中） | 世代丢弃；无 EDT 读 PasswordSafe（ADR-0039） |
| C8 | 脏草稿 + Refresh all / 关工具窗口 | 草稿守卫行为正确；刷新不误清详情（ADR-0027）——功能正确性兼作「无多余重载」 |
| C9 | Clear Cache | 堆与磁盘下降；随后 reload 可再填充 |
| C10 | 可选 LiveSmoke | `NACOS_LIVE_V1_ENDPOINT=… ./gradlew test --tests "…LiveSmokeTest.V1*"` |

### 阶段 D — CPU / 分配剖析（定点）

对 **sandbox IDE 进程** Attach Profiler（宿主 IDEA：Run → Attach Profiler to Process；或 Profiler 工具窗口）：

1. **C3 大命名空间刷新**：看 `NamespaceIndexCoordinator` / `NacosApiService` / JSON 解析是否主导 CPU；分配是否异常。
2. **C5 打开大 Java 文件**：看 `NacosValueLineMarkerProvider`、`NacosKeyResolver`、`FileBasedIndex` 是否进入采样热点。
3. **C4 搜索防抖**：wall-clock 采样可看到 delay 与 IO，而非 EDT 自旋。

Linux 若采样失败，按[官方说明](https://www.jetbrains.com/help/idea/custom-profiler-configurations.html)调整 perf 相关 sysctl。

### 阶段 E — 冻结取证

若 UI 卡住：

1. 立即在日志目录找最新 `threadDumps-freeze-*`（`runIde` sandbox 的 system/log）。
2. 从 **`AWT-EventQueue-0`** 开始；若卡在 write lock upgrade，再查持有 read lock 且未 `checkCanceled` 的 BGT（[Investigating UI Freezes](https://blog.jetbrains.com/platform/2025/09/investigating-intellij-platform-ui-freezes/)）。
3. 本插件嫌疑帧关键词：`com.nanyin.nacos.search`、`PasswordSafe`、`FileCacheStore`、`runReadAction` 长持有。

### 阶段 F — 索引成本

1. 打开/重建索引后，在 sandbox **logs** 中找 indexing performance JSON/HTML（[Indexing docs](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html)）。
2. 对比有/无本插件（或临时 disable）时总 indexing 时间与 `nacos.placeholder.keys` / declared-source index 条目。
3. 确认 Indexer 仍走文本正则而非 PSI（与类注释一致）。

### 阶段 G — 磁盘与堆核对清单

```text
□ IDE config 下 nacos-search-cache 目录大小（加载前/后/Clear 后）
□ Live Charts：Heap 峰值与回收后水位
□ Threads：预取与索引期间是否线程暴涨（预取应被 Semaphore(4) 限制）
□ Non-heap：异常上涨可能与类/元数据相关，通常非本插件主因
```

## 5. 「好」长什么样（对本插件）

结合现有设计与测试，一次合格的性能评估至少应能回答：

1. **交互路径无平台冻结证据**（无本插件栈的 freeze dump；无 SlowOperations）。
2. **启动与刷新不阻塞编辑**（ProjectActivity 只调度后台工作）。
3. **微基准全绿**（`PerformanceBenchmarkTest`）。
4. **资源有上界行为可观察**：缓存条目裁剪、预取并发 4、PSI 冷却、搜索防抖。
5. **索引额外成本可解释**：FileBasedIndex 使用轻量 Indexer；大项目有 before/after 数字。

## 6. 仓库现状缺口

| 缺口 | 说明 |
| --- | --- |
| 无端到端 UI 性能套件 | 未注册 `testIdePerformance`；无 Robot 场景计时 |
| 微基准未用 `startPerformanceTest` | 绝对毫秒阈值会随机器抖动 |
| 无正式内存回归测试 | 未对 `CacheService` / 索引做堆占用断言 |
| 无启动耗时遥测 | `ProjectActivity` 各阶段无结构化 timing 指标导出 |
| `CLAUDE.md` 与实现漂移 | 「getAllConfigurations 并发 8」已过时；评估文档应以 ADR-0041 为准 |
| 未接入 freeze 上报 Sink | 可选 `ErrorReportSink` 收集 `UnhandledFreezeReport`（需产品决策） |

## 7. 来源

### JetBrains / 平台

- [Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [Investigating IntelliJ Platform UI Freezes](https://blog.jetbrains.com/platform/2025/09/investigating-intellij-platform-ui-freezes/)
- [How to get a thread dump when IDE hangs](https://intellij-support.jetbrains.com/hc/en-us/articles/206544899)
- [CPU and allocation profiling](https://www.jetbrains.com/help/idea/cpu-and-allocation-profiling-basic-concepts.html)
- [Custom profiler configurations](https://www.jetbrains.com/help/idea/custom-profiler-configurations.html)
- [CPU and memory live charts](https://www.jetbrains.com/help/idea/cpu-and-memory-live-charts.html)
- [Indexing and PSI Stubs](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html)
- [File-Based Indexes](https://plugins.jetbrains.com/docs/intellij/file-based-indexes.html)
- [Testing FAQ — performance test](https://plugins.jetbrains.com/docs/intellij/testing-faq.html)
- [IntelliJ Platform Testing Extension](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-testing-extension.html)
- [Error Reporting](https://plugins.jetbrains.com/docs/intellij/error-reporting.html)
- [Coroutine Dispatchers](https://plugins.jetbrains.com/docs/intellij/coroutine-dispatchers.html)

### 本仓库

- `src/main/kotlin/com/nanyin/nacos/search/NacosSearchPlugin.kt`
- `src/main/kotlin/com/nanyin/nacos/search/services/CacheService.kt`
- `src/main/kotlin/com/nanyin/nacos/search/services/FileCacheStore.kt`
- `src/main/kotlin/com/nanyin/nacos/search/services/NamespaceIndexCoordinator.kt`
- `src/main/kotlin/com/nanyin/nacos/search/services/NavigationDetailPrefetchService.kt`
- `src/main/kotlin/com/nanyin/nacos/search/services/NacosApiService.kt`（`loadNamespace`）
- `src/main/kotlin/com/nanyin/nacos/search/services/NacosSearchService.kt`（`searchDelayMs = 300`）
- `src/main/kotlin/com/nanyin/nacos/search/psi/NacosPlaceholderIndex.kt`
- `src/test/kotlin/com/nanyin/nacos/search/psi/PerformanceBenchmarkTest.kt`
- ADR-0018、0039、0041、0043、0045、0051、0054；`docs/superpowers/specs/2026-07-11-stability-and-small-features-design.md`
- `CLAUDE.md` / `AGENTS.md`（命令与 LiveSmoke 环境变量）
