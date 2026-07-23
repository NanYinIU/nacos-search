# Nacos V1/V3 双栈与不可变访问上下文设计

- 状态：已完成产品决策，等待最终共享理解确认后进入实施
- 日期：2026-07-22
- 范围：P0-A、P0-B、P0-C 与 P1

## 1. 背景

Nacos Search 当前把环境选择、认证、HTTP 协议、缓存和 UI 会话交织在应用级服务中。这个结构在单项目、Nacos V1 API 和单一凭据下可以工作，但无法安全满足以下目标：

- 标准 Nacos 3.2 已从主发行版移除 V1/V2 HTTP API，插件必须原生调用 V3 Admin API。
- 多个 IntelliJ 项目可同时选择不同环境、Namespace 或身份，彼此不能改写目标。
- 密钥修改、AUTO 世代探测、缓存降级和晚到网络响应不能穿透访问边界。
- 已有发布能力恢复时，必须避免误写目标和自动重放，并正确处理“请求失败但服务端其实已写入”；V3 无 CAS 时不承诺消除并发覆盖。

本设计把“环境档案”“项目会话”“访问身份”“操作上下文”和“凭据快照”拆开，并以协议适配器提供 V1/V3 行为对等。所有术语以仓库根目录的 [`CONTEXT.md`](../../../CONTEXT.md) 为准，细粒度取舍由 [`docs/adr`](../../adr) 中的 ADR 0001–0038 记录。

## 2. 目标与非目标

### 2.1 目标

- Nacos 2.0–2.5 通过 V1 HTTP API 获得一等支持。
- Nacos 3.0–3.2 通过 V3 Admin API 获得一等支持；标准 Nacos 3.2 不安装 legacy adapter 也必须通过。
- 默认 `AUTO`、V3 优先，只在 V3 明确不可用且 V1 正向探测成功时回退；提供 V1/V3 手工锁定。
- 每次正式操作使用不可变目标和不可变凭据快照；项目切换、设置修改或探测变化不能重定向已启动操作。
- 缓存、Token、single-flight、权限阻断和写入结果均按完整访问身份隔离。
- P0 恢复受控的现有配置正文发布；P1 提供身份内只读历史和 IntelliJ Diff。
- 保持 PSI 与 Swing 热路径无网络、磁盘或阻塞等待。

### 2.2 非目标

- 不承诺 Nacos 1.x 回归，只保留 best-effort 兼容。
- 不自动回退到 V3 Console API，也不自动探测多个 context path。
- 不实现认证策略自动降级、交互式浏览器 SSO 或任意自定义 Header。
- 暂不处理自定义 TLS、客户端证书、证书信任或证书固定。
- 不加入配置长轮询、云同步、收藏系统或跨环境 diff。
- P0 不做删除、批量导入、灰度发布、跨环境 promotion、强制覆盖或新建已删除配置。
- P0 不为正文搜索隐式拉取全部详情；全量正文扫描只是未承诺的 post-P0 选项。
- P1 历史不包含恢复、回滚或重新发布。
- 最近搜索本身不属于本次 P0/P1 承诺；若以后加入，其所有权必须是 project workspace，不能恢复旧全局记录。

## 3. 交付分层

| 阶段 | 交付内容 | 完成门槛 |
| --- | --- | --- |
| P0-A 安全底座 | 全局环境档案、项目会话、双 revision、不可变上下文、版本化凭据槽、最小 probe/adapter registry、统一 transport/error、认证会话、新缓存 schema | 身份不会串线；凭据更新可注入崩溃；旧缓存停止读取；并发 fence 测试通过 |
| P0-B 双栈读取 | AUTO/V1/V3、V1/V3 读取适配器、显式认证、手工 Namespace 降级、摘要优先搜索/详情、PSI、隔离诊断 | Nacos 2.5.3 与无 legacy adapter 的标准 3.2.3 live smoke 通过 |
| P0-C 受控发布 | profile 写入开关、绑定草稿、preflight、V1 CAS、V3 无 CAS 发布、确认、单次写入、read-back、元数据验证 | 错目标、跨身份缓存、重复写入或错误回退测试任一失败即阻断发布 |
| P1 只读历史 | V1/V3 各自的历史列表、正文和 IntelliJ Diff；移除过窗的 legacy 迁移代码；按需求评估 OIDC client credentials | 历史不跨身份、不持久化正文、不产生写入命令 |

P0-A 可以先引入类型和基础设施，但不得在身份、probe、凭据与缓存键仍不完整时启用新的运行路径。

## 4. 支持矩阵

| Nacos 服务端 | 默认协议 | 支持等级 | 说明 |
| --- | --- | --- | --- |
| 1.x | V1 | best effort | 不进入发布阻断回归矩阵 |
| 2.0–2.5 | V1 | first class | 使用 legacy V1 HTTP API；默认 Namespace 在线路上编码为空 `tenant` |
| 3.0–3.2 | V3 | first class | 使用 V3 Admin API；默认 Namespace 为 `public` |
| 3.2 标准发行版 | V3 | release gate | 不依赖 legacy adapter |

固定 live matrix：2.0.4、2.2.4、2.5.3、3.0.3、3.1.2、3.2.3。每次提交至少运行 V1/V3 contract tests；CI smoke 固定 2.5.3 与 3.2.3，nightly/release 扩展到全部版本。

## 5. 所有权与状态模型

### 5.1 所有权

| 作用域 | 拥有的状态 |
| --- | --- |
| Application | 环境档案、profile/access revision、版本化 PasswordSafe 凭据槽、认证会话 registry、正式请求 single-flight、持久缓存、profile tombstone、最近已知世代 |
| Project | 当前 profile、Namespace、搜索与分页、已解析 API 世代、`sessionEpoch`、dirty 编辑会话、一次性升级摘要状态 |
| Operation | `OperationContext`、`CredentialSnapshot`、请求预算、观测序号、取消信号 |
| Diagnostic | 未保存表单的 `DiagnosticSnapshot`、独立 probe、独立临时认证与报告；不加入以上共享状态 |

不同项目可引用同一 profile，但一个项目的 Namespace、AUTO 结果、搜索、草稿或 session epoch 不得改变另一个项目。应用级共享只允许发生在完整安全键一致时。

### 5.2 核心不可变类型

以下为语义草图，不限定最终文件拆分：

```kotlin
enum class ApiGenerationPolicy { AUTO, V1, V3 }
enum class ApiGeneration { V1, V3 }
enum class AuthStrategy { ANONYMOUS, NACOS_PASSWORD, HTTP_BASIC, BEARER_TOKEN }

data class CanonicalEndpoint(
    val origin: Origin,          // scheme + host + port
    val contextPath: String      // default /nacos; / is valid
)

data class EnvironmentProfile(
    val profileId: String,
    val displayName: String,
    val endpoint: CanonicalEndpoint,
    val apiPolicy: ApiGenerationPolicy,
    val authStrategy: AuthStrategy,
    val principal: String,
    val profileRevision: Long,
    val accessRevision: Long,
    val writesEnabled: Boolean,
    val suggestedNamespace: NamespaceId,
    val requestPolicy: RequestPolicy
)

data class ProjectSession(
    val selectedProfileId: String?,
    val namespaceId: NamespaceId,
    val resolvedGeneration: ApiGeneration?,
    val sessionEpoch: Long
)

data class AccessIdentity(
    val profileId: String,
    val accessRevision: Long,
    val endpoint: CanonicalEndpoint,
    val generation: ApiGeneration,
    val authStrategy: AuthStrategy,
    val principal: String
)

data class OperationContext(
    val identity: AccessIdentity,
    val namespaceId: NamespaceId,
    val profileRevision: Long,
    val capturedSessionEpoch: Long,
    val requestPolicy: RequestPolicy
)
```

`CredentialSnapshot` 与 `OperationContext` 分开，只驻内存，不实现有意义的 `toString`，不参与 equality/hash、日志、持久化、缓存键或请求去重。

缓存和已完成认证 Token 使用 `AccessIdentity`；正在执行的普通请求及 login/refresh flight 使用 `ExecutionKey = AccessIdentity + profileRevision（或等价执行策略指纹）+ operation + normalized parameters`。因此 timeout/proxy 等 profile-only 变化不会丢缓存，却也不会让新操作加入使用旧策略的 flight。

### 5.3 捕获流程

```text
EnvironmentProfile + revision-pinned PasswordSafe slot
                         │
                         ▼
                  ProjectSession
                         │ AUTO only
                         ▼
               GenerationProbeContext
                         │ resolved generation
                         ▼
         AccessIdentity + sessionEpoch snapshot
                         │
                         ├── OperationContext（安全目标）
                         └── CredentialSnapshot（秘密）
                                      │
                                      ▼
                           Adapter → Transport
                                      │
                                      ▼
                    typed outcome + observation sequence
                                      │
                   ┌──────────────────┴──────────────────┐
                   ▼                                     ▼
          Cache seam / tombstone                  sessionEpoch gate
                   │                                     │
                   └────────────── UI / PSI ─────────────┘
```

正式操作只有在 profile、对应 revision 的凭据槽、已解析 generation 和项目 session epoch 全部捕获后才开始。后续设置或 UI 选择变化只能使结果失效，不能改写该操作的目标。

## 6. Revision 与会话 fence

| 变化 | `profileRevision` | `accessRevision` | `sessionEpoch` |
| --- | --- | --- | --- |
| endpoint | +1 | +1 | 所有引用项目 +1 |
| 持久化 API policy（AUTO/V1/V3） | +1 | +1 | 所有引用项目 +1 |
| auth strategy / principal / secret | +1 | +1 | 所有引用项目 +1 |
| timeout / proxy / cache policy | +1 | 不变 | 所有引用项目 +1 |
| writes enabled | +1 | 不变 | 所有引用项目 +1 |
| display name / suggested Namespace | 不变 | 不变 | 不变 |
| AUTO resolved generation | 不变 | 不变 | 发生变化的项目 +1 |
| 项目选择 profile / Namespace | 不变 | 不变 | 该项目 +1 |

只有 `accessRevision` 变化会使旧缓存和认证会话立即不可见。普通 operation-affecting 设置变化重新捕获上下文，但不无谓丢弃同身份缓存。AUTO 结果已经直接进入 `AccessIdentity`，不再重复推进持久化 revision。

旧操作在 session epoch 不匹配时不能发布 UI、PSI 状态或后续任务。它最多向自己捕获的旧缓存坐标提交 mutation；如果 profile 已存在 tombstone，则缓存入口仍必须拒绝。

## 7. Endpoint 规范化

- profile 将 endpoint 存为 `origin + contextPath`，而不是任意 URL 字符串。
- `origin` 仅允许绝对 HTTP/HTTPS URI 的 scheme、host、port；拒绝 user-info、query 和 fragment。
- scheme 与不经 DNS 解析的 IDNA ASCII host 转小写；HTTP 80/HTTPS 443 默认端口省略，非默认端口保留；IPv6 以带方括号的小写 literal 输出。
- `contextPath` 默认 `/nacos`，允许 `/` 或显式反向代理路径；补一个前导斜杠，除 `/` 外移除尾随斜杠，拒绝空 segment、`.`/`..`、反斜杠、控制字符和编码后的 slash/backslash，并规范 percent hex 与 unreserved 字符。
- V1/V3 adapter 只在 context path 后追加相对协议路径。
- 迁移时，旧 URL 无 path 使用 `/nacos`；已有 path 保留。无法规范化则进入 `ConfigurationRequired`。
- 不自动尝试 `/`、`/nacos` 或其他 path，也不自动跟随任何 3xx。
- `EndpointRedirected` 只暴露清洗后的目标 origin/context path；用户手工确认并修改 profile 后才访问新目标。

本轮不扩展自定义 TLS 或证书管理。TLS 握手失败是不可重试的类型化传输失败。

## 8. API 世代选择

### 8.1 手工策略

`V1` 或 `V3` 直接构造对应 identity，不进行另一世代探测，也不因任何错误切换世代。协议不匹配返回 `GenerationUnsupported` 或 `MalformedResponse`。

### 8.2 AUTO bootstrap

AUTO 在完整 `AccessIdentity` 形成前使用 `GenerationProbeContext`：

1. 捕获 profile ID、两种 revision、endpoint、API policy、auth strategy、principal、request budget 与凭据快照。
2. 使用候选 V3 adapter 请求只读 `GET /v3/admin/core/state`。该接口的成功示例是裸 map，不能套普通 `{code,message,data}` parser。
3. 若代理或自定义部署要求认证，候选 adapter 可在本次 probe 内准备认证；NACOS_PASSWORD 的临时 Token 不进入共享 auth registry。
4. V3 成功或返回可识别的 V3 认证/权限语义时，不回退 V1。
5. 只有 V3 adapter 返回类型化 `GenerationUnsupported`，才运行候选 V1 state probe；`CapabilityUnsupported` 或裸 404 不够。只有 V1 正向成功才解析为 V1。
6. 401/403、timeout、redirect、DNS、TLS、429、5xx、malformed response 都不能触发 V1 回退。
7. 结果写入发起项目的 session；变化时只推进该项目 epoch。

应用可按所有非秘密 probe 输入 single-flight 合并并发正式探测，但结果所有权仍属于各项目。每个消费者在提交 session 或 last-known generation 前独立复核 profile ID、双 revision、session epoch 和 tombstone；隔离诊断不共享该 flight 或临时 Token。

### 8.3 离线启动

成功的正式 AUTO 探测按 `{profileId, accessRevision, canonicalEndpoint}` 保存 `lastKnownGeneration`。它不是 profile 配置，也不是当前探测结果，只可用于：

- 重启离线时重建同一旧 `AccessIdentity`，定位新 schema 缓存；
- 把恢复数据标记为 `CACHE + UNCONFIRMED`。

它不能跳过正式探测、发起网络操作或更新 revision。正式探测若解析成另一世代，项目推进 epoch、切换 identity，并立即隐藏旧世代缓存。

正式探测失败时的 cache matrix：只有 matching last-known generation 存在，并且失败为瞬时 transport、rate-limit 或 server failure，才可显示该旧 identity 缓存并标记 `REFRESH_FAILED`；authentication/permission failure 阻断它，invalid configuration、redirect、malformed response 或 generation failure 不展示它。无 matching last-known 时 AUTO 没有可用 identity，也不展示缓存。

## 9. 认证与凭据

### 9.1 显式策略

| 策略 | 密钥 | 主体 | 行为 |
| --- | --- | --- | --- |
| ANONYMOUS | 无 | `<anonymous>` | 不附加身份材料 |
| NACOS_PASSWORD | password | username | adapter 登录并管理短期 access token |
| HTTP_BASIC | password | username | 发送 Basic，面向代理或自定义部署 |
| BEARER_TOKEN | token | 用户配置的稳定 alias | 发送外部 Bearer；不自动登录或刷新 |

认证策略永不互相 fallback。V3 NACOS_PASSWORD 仅承诺 `nacos` 与 `ldap` auth system；state 表明 OIDC 或其他插件时返回策略不匹配。V1 的原生默认鉴权使用 `accessToken` 参数；V1 BEARER 只承诺代理/自定义鉴权，不宣称 Nacos 2.x 原生能力。

### 9.2 版本化凭据槽

PasswordSafe 使用 `{profileId, accessRevision}` 作为不可变槽键：

1. 安全边界修改计算 `R+1`。
2. 先将新 secret 写入尚未被 profile 引用的 `R+1` 槽。
3. 成功后才发布 revision 为 `R+1` 的 profile。
4. 捕获者只能看到完整旧对或完整新对。
5. 提交前失败保留旧对；未引用槽由有界后台任务清理。
6. 当前 revision 所需槽缺失时进入 `CredentialIncomplete`，隐藏旧身份缓存并 fail closed；不得读取旧槽。
7. secret 改回相同值仍产生新 revision。

匿名或切换到无 secret 策略时，先发布新 revision，再异步清理旧槽。profile 删除先写 tombstone，再清理全部槽。

### 9.3 认证会话与恢复

- 登录 Token registry 为 application-memory-only，键是完整 `AccessIdentity`。
- 同身份登录/刷新 single-flight；不同身份永不共用。
- 临近过期时主动刷新。刷新失败而旧 Token 仍有效时可继续使用；过期则失败。
- 每个 operation 在开始时读取一次 credential snapshot；登录、刷新和一次允许的重放始终使用该快照。
- 仅 NACOS_PASSWORD 对 adapter 语义映射出的“Token invalid/expired”执行一次失效、登录和幂等读重放。
- Nacos V1 可能用 HTTP 403 表示 invalid token，因此不得按裸 HTTP 401/403 决策。
- `PermissionDenied` 永不触发重登录；写操作永不因认证失败自动重放。

## 10. 协议适配器

上层只能调用共同领域接口，不能出现 `if (generation == V3)`。adapter 独占 endpoint、参数名、Namespace 编码、Token 位置、响应 envelope、字段默认值和错误映射。

| 操作 | V1 adapter | V3 adapter | 阶段 |
| --- | --- | --- | --- |
| state/probe | legacy state contract | `GET /v3/admin/core/state`，裸 map | P0-A |
| login | `POST /v1/auth/login` | `POST /v3/auth/user/login` | P0-B |
| Namespace discovery | legacy `/v1/console/namespaces` | `GET /v3/admin/core/namespace/list`，通常需管理员权限 | P0-B |
| summary list/search | `/v1/cs/configs` legacy paging/search | `GET /v3/admin/cs/config/list` | P0-B |
| detail | `GET /v1/cs/configs` | `GET /v3/admin/cs/config` | P0-B |
| publish | `POST /v1/cs/configs`，Header `casMd5` | `POST /v3/admin/cs/config`，无公开 CAS 参数 | P0-C |
| history list/detail | `/v1/cs/history...` | `/v3/admin/cs/history...` | P1 |

共同 P0 领域接口至少包括 probe、login、namespace discovery、summary list/search、detail、publish。历史在 P1 作为可选 capability 加入。

契约来源必须区分：V3 表中能力均有公开 Admin API 文档；V1 detail/publish/history 有公开 V1 文档，但 legacy state probe、分页列表/搜索形态并未进入公开 V1 OpenAPI 手册，V1 `casMd5` wire header 则由官方 2.5.3 server source 证实而非公开 HTTP 手册承诺。这三项必须作为 pinned adapter contracts，以固定 request/response fixtures 和 2.0–2.5 live matrix 防漂移，不能在产品文案中宣称为跨版本公开稳定契约。

P0 capability 基线：两边声明 `CONFIG_SUMMARY_LIST`、`CONFIG_DETAIL` 与 `PUBLISH`；Namespace discovery 是权限敏感能力。V3 声明文档化的 `CONTENT_SEARCH`，V1 默认不声明正文服务端搜索，除非整个 first-class matrix 后续证明该 legacy contract。V1 `show=all` 详情元数据同样不是无条件依赖；adapter 必须以 raw content 为稳定基线，将可选元数据能力 contract-gate，并在发布测试中证明遗漏字段不会被清空。

### 10.1 Namespace 边界

领域内只存在 `NamespaceId("public")` 形式的默认 Namespace。null、blank 和精确 `public` 在边界归一；其他 ID 区分大小写。

- V1 wire：canonical public 编码为空或省略 `tenant`。
- V3 wire：发送 `namespaceId=public`。
- Namespace 展示名永不参与请求、缓存或 identity。
- 手工输入必须是 Namespace ID，不是展示名。

### 10.2 响应与错误

- V3 adapter 同时解释 HTTP status 与 JSON `code`；`code=0` 才是统一 envelope 成功，常见的 `10001` 和 `20004` 分别映射访问拒绝与资源不存在。
- V3 state 是已知的裸 map 特例。
- V1 adapter 处理正文直返、legacy JSON 和非 JSON 错误页。
- 404 必须结合 operation 映射：probe unsupported、detail missing、optional capability unsupported 不是同一失败。
- raw body、Header、tokenized URL 和配置正文不得进入错误对象或日志。

## 11. Transport 与请求预算

所有 V1/V3 请求，包括 login 与 publish，都经过同一个可取消 transport seam。adapter 只构造请求、解析响应和映射协议错误。

类型化失败至少包括：

- `InvalidConfiguration`
- `TransportFailure(kind = CONNECT | CONNECT_TIMEOUT | READ_TIMEOUT | DNS | TLS)`
- `EndpointRedirected(sanitizedEndpoint)`
- `AuthenticationFailed`
- `PermissionDenied`
- `GenerationUnsupported`
- `CapabilityUnsupported`
- `ResourceNotFound`
- `RateLimited`
- `ServerFailure`
- `MalformedResponse`
- `WriteConflict`
- `AmbiguousWriteResult`
- `Cancelled`

重试规则：

- 仅幂等读可额外重试一次。
- CONNECT、CONNECT_TIMEOUT、READ_TIMEOUT、5xx 可重试；429 只有 `Retry-After` 能放入剩余预算时可重试。
- DNS、TLS、redirect、其他 4xx、malformed response、login 和 write 不做 transport retry。
- 一次允许的认证恢复与 transport retry 共用原请求预算，不能重置计时。
- 取消停止后续等待和尝试；write 可能已离开客户端后，取消映射为 `AmbiguousWriteResult` 而不是普通 `Cancelled`。

保留旧稳定性设计的默认预算，并由捕获的 profile request policy 在安全范围内覆盖：普通交互默认 connect 3 秒、read 8 秒、总预算 15 秒；后台预热默认 connect 3 秒、read 8 秒且不做 transport retry；显式诊断总预算 30 秒；可重试读使用约 250ms 的单次轻量退避。配置值改变 profile revision，但已启动请求仍使用捕获值。

所有 scope 与监听器继续绑定 IntelliJ `Disposable`。服务、项目或 UI content 销毁后停止所属任务；EDT 仅消费准备好的不可变状态，不执行网络、文件 I/O 或无界模型更新。

## 12. 读取、搜索与 PSI

### 12.1 Metadata-first

`ConfigSummary` 与 `ConfigDetail` 分开：

- paging、搜索和 Namespace index 只取得 summary。
- 用户选择、代码导航落点或 publish preflight 才取得 detail。
- detail 失败只改变该详情的 `DetailLoadState`，不把完整 summary index 降为 partial。
- 禁止列表后逐条同步拉正文的 N+1 流程。

### 12.2 搜索覆盖度

- Data ID / Group 搜索只依赖 summary 或 adapter 的服务端搜索。
- 服务端正文搜索仅在 adapter 明确声明 `CONTENT_SEARCH` 时使用。
- 离线正文搜索只检查已经缓存的详情，并显示 `X/Y` 覆盖度。
- 0/Y 不返回“没有匹配”，而是正文搜索不可用或未覆盖。
- regex、case-sensitive 正文搜索不承诺由服务端完整支持。
- 不为一次正文搜索隐式拉取所有详情。

不运行周期性自动全量刷新。summary index 只由用户手工刷新、切换到缺失/过期 Namespace、确实需要 index 的前台搜索或 PSI 去重预热触发。PSI 预热按 `AccessIdentity + Namespace` single-flight，并保留五分钟失败冷却；terminal authentication block 后暂停，直到设置修改、用户手工刷新或真实项目 Reconnect 成功，隔离诊断成功不解除暂停。

### 12.3 PSI

PSI 只读内存快照，不等待网络或磁盘。完整且当前可用的 Namespace summary index 可以证明存在或不存在；partial/failed index 不能得出不存在结论。缓存详情可作为离线导航目标，但不会声称远端当前存在。

既有 `GutterConfig`、`GutterConfigStale`、`GutterConfigUnresolved` 图标资源保持不变。状态差异通过 tooltip、状态文字和 accessible description 表达，不增加图标资产。

## 13. 缓存与可见性

### 13.1 新 schema

新缓存键始终包含完整 identity：

- Namespace index：`identity + namespace`
- list page：`identity + namespace + normalized criteria + page`
- detail：`identity + namespace + dataId + group`

旧 schema 无法证明 profile、access revision、generation 或 canonical Namespace，P0-A 起停止读取且不猜测迁移；有界、幂等清理在后台运行。P1 只移除经过一个兼容发布后不再需要的一次性 reader/migration/cleanup 代码，不推迟 P0 的安全停读。

### 13.2 展示状态的三个维度

| 维度 | 值 | 含义 |
| --- | --- | --- |
| `DataSource` | REMOTE / CACHE | 数据从何处取得 |
| `DatasetConfirmation` | CONFIRMED / UNCONFIRMED / REFRESH_FAILED | 本次运行是否确认、是否尝试刷新失败 |
| `CacheAge` | WITHIN_TTL / STALE / DEEP_STALE | 相对 TTL 与七天阈值的年龄 |

三者正交。重启恢复的 TTL 内缓存是 `CACHE + UNCONFIRMED + WITHIN_TTL`；一次可能已发送的写也会让保留的写前详情从 `CONFIRMED` 变为 `UNCONFIRMED`，直到 reconciliation 产生新观测；刷新失败不修改年龄；时间流逝不清除失败证据。

深度陈旧详情仍可导航，打开时强制单项刷新。年龄不自动删除详情；容量、手工清理、完整权威 index 或明确 not-found 才能移除。列表与详情继续使用文字状态，gutter 复用既有图标。

### 13.3 完整性与覆盖度

- `DatasetCompleteness.COMPLETE`：预期 summary 全部取得。
- `PARTIAL`：summary 分页、解析或预期条目取得不完整。
- `FAILED`：没有可靠 summary 数据集。
- 单项详情状态和正文搜索覆盖度独立，不改变 summary completeness。

完整 index 可以权威删除缺失条目；partial/failed index 永不删除。明确 detail not-found 只删除该坐标。

### 13.4 失败降级与访问阻断

- transport、rate-limit、server、malformed failures 可保留同 identity 缓存，并设 `REFRESH_FAILED`。
- terminal `AuthenticationFailed`（完成一次允许恢复后）阻断整个 identity 的缓存。
- config read `PermissionDenied` 只阻断 `identity + namespace`。
- publish、discovery 或 optional capability 的 permission failure 只影响该能力，不隐藏仍有权限的读缓存。
- invalid config 与 unsupported generation 不使用缓存兜底。
- matching success 或新 access revision 清除对应作用域阻断。

### 13.5 因果顺序

每个远程操作开始时取得进程内单调 `observationSequence`。缓存入口为配置坐标、identity gate、identity+namespace gate，以及 `(identity, capability, optional scope)` gate 分别维护 high-water：

- 旧失败晚到不能覆盖新成功。
- 旧成功不能清除更新的权限阻断。
- complete index 删除条目前必须确认没有更新的 detail 观测。
- publish reconciliation 的每次 read-back 也是普通权威远程观测，按相同 high-water 刷新、删除或设置作用域阻断；不能因为发布尚未 `VERIFIED` 而丢弃。
- 序号不持久化；重启后第一个更新的 matching success 可以清除持久阻断。

### 13.6 Profile 删除

删除前检查所有 open-project consumers；任何 dirty draft 都阻止删除，直到其所有者明确丢弃。

确认删除后按顺序：

1. 持久化 profile tombstone。
2. 让它成为统一 lifecycle guard，阻止新 context、profile/credential publication、auth-token publication、last-known generation、probe/session commit 和任何 cache mutation；每个异步完成点二次检查。
3. 让引用项目进入 `ProfileUnavailable`、推进 epoch、取消或 detach 旧任务。
4. 删除凭据槽、认证会话和缓存；失败可重试。
5. 清除 new-project default，但不替项目自动选择其他 profile。

任何 tombstone 前启动的晚到 response 都不能复活缓存。

## 14. 连接诊断

诊断从未 Apply 的设置表单捕获 `DiagnosticSnapshot`，按阶段运行：

1. 本地 endpoint、auth 与必填字段校验。
2. 使用隔离 probe context 解析 generation。
3. 在独立临时认证中准备身份。
4. 从用户配置的 Namespace 读取一页、page size 1 的 summary。
5. 单独尝试 Namespace discovery。

前四阶段成功即为连接成功；discovery forbidden 只产生“连接成功 · 使用手工 Namespace · Namespace 发现不可用”。报告可复制，只含阶段、耗时、generation 与清洗失败。

诊断不得写 profile、PasswordSafe、last-known generation、project session、epoch、cache、auth registry 或正式 request flight。只有 Apply、显式 Reconnect 或 IDE 重启使真实项目 session 重探测。

## 15. 设置与升级

### 15.1 旧认证迁移

| 旧状态 | 新策略 |
| --- | --- |
| username/password 都为空 | ANONYMOUS |
| TOKEN | NACOS_PASSWORD |
| BASIC | HTTP_BASIC |
| HYBRID 且旧 token auth 开启 | NACOS_PASSWORD |
| HYBRID 且旧 token auth 关闭 | HTTP_BASIC |
| 只填一半凭据 | 对应策略 + `CredentialIncomplete` |

HYBRID 运行路径删除，不保留运行时 fallback。完整 secret 先写 revision-pinned slot，再发布迁移 profile。

### 15.2 全局到项目

- 全局 profiles 保留稳定 ID；former `activeServerId` 仅成为新项目默认值。
- profile namespace 仅成为 suggested default。
- 项目首次初始化后，selected profile、Namespace、搜索、分页和 UI state 只存在 non-shareable workspace storage。
- 旧全局 recent search/pagination 丢弃；legacy flat fields 只保留一个发布的反序列化能力。
- 迁移幂等；缺失 profile ID 只分配一次。

### 15.3 用户可见状态

- `CredentialIncomplete` 或非法 endpoint：`ConfigurationRequired`，不发请求、不展示旧缓存，提供设置修复入口。
- 旧缓存停读、项目选择初始化、旧 UI 状态丢弃：每项目显示一次可关闭、非 modal 的 `UpgradeCompleted` 摘要。
- discovery 不可用但手工 Namespace 可读：显示发现降级连接，而非空结果或连接失败。
- 沿用现有 banner、label 和 warning style，不增加图标资源，不在 IDE 启动时弹阻塞对话框。

## 16. P0-C 受控发布

### 16.1 开关与编辑绑定

- 所有新建和迁移 profile 的 `writesEnabled=false`。
- 开启仅表达用户意图，不证明服务端写权限，只推进 profile revision。
- V1 与 V3 共用该单一 opt-in；V3 不增加第二确认开关，也不以 CAS parity 作为发布门槛。
- P0 只能编辑已有配置的正文。
- 编辑会话固定绑定 profile ID、AccessIdentity、canonical Namespace、dataId、group、base MD5、base content 和已知 metadata；不持有 secret。
- publish target 只能从该绑定派生，不能读取当前 UI selection。

Dirty draft 会阻止选择另一配置、切换 profile/Namespace、关闭 writes、删除 profile、关闭项目或销毁工具窗口内容，直到用户取消动作或明确丢弃。operation-only profile change 保留草稿但强制重新 preflight；access revision 或 resolved generation 变化使草稿不可发布，只允许复制文字。

### 16.2 状态机

| 状态 | 含义 / 可执行动作 |
| --- | --- |
| `READ_ONLY(reason)` | writes off、permission、identity changed、encrypted config 等明确原因 |
| `DIRTY` | 草稿存在，可继续编辑或丢弃 |
| `PREFLIGHT` | 使用绑定目标和 fresh credential 重读 detail |
| `TARGET_DELETED` | 远端不存在；中止，不自动创建 |
| `REMOTE_CONFLICT` | 远端不等于 base；打开 remote-vs-draft diff，不允许 force overwrite |
| `AWAITING_CONFIRMATION` | 展示最终 diff 与精确 endpoint/profile/Namespace/dataId/group |
| `PUBLISHING` | 单次非重试 write 已开始 |
| `VERIFYING` | 使用原 context 立即 read-back |
| `VERIFIED` | 正文及可验证 metadata 语义相等；允许把命令结果作为当前详情并清 dirty |
| `PERMISSION_DENIED` | 保留草稿；读缓存不受 publish 权限影响 |
| `SERVER_STATE_UNKNOWN` | write 可能已发出但无法确认；保留草稿、把写前详情改为 UNCONFIRMED、阻止再次 publish、强制 read-back |

### 16.3 发布算法

1. 用 edit binding 重读 detail；not-found 进入 `TARGET_DELETED`。
2. 比较 base content/MD5；不一致进入 `REMOTE_CONFLICT`。
3. 从刚读取的远端 detail 生成 `PublishCommand`，只替换正文，保留可读 metadata。
4. `encryptedDataKey` 非空时保持只读。
5. 展示最终 diff 和精确目标，等待明确确认。
6. V1 发送一次 publish，并通过 Header `casMd5` 使用服务端 CAS；CAS false 映射 `WriteConflict`，不重放。
7. V3 发送一次 publish；公开 Admin API 无 CAS，preflight 只能观察当时状态，不能阻止随后发生的删除或并发更新。V3 仍保留普通 publish，不引入新的“乐观发布”产品名称。
8. write 不做 transport 或 auth auto-replay。
9. 请求可能离开客户端后，timeout、disconnect 或 cancel 都进入 `SERVER_STATE_UNKNOWN`，并立即把保留的写前详情从 `CONFIRMED` 改为 `UNCONFIRMED`。
10. 立即用原 `OperationContext + CredentialSnapshot` read-back。每次 read-back 先作为带 observation sequence 的权威远程观测按 cache high-water 生效；正文与 adapter 定义的可回读、应保留 metadata 全部语义相等，才进入 `VERIFIED`。
11. read-back 等于 preflight 基线只表示命令结果当前未呈现，不能证明写从未应用；保留草稿、回到 dirty 并要求新 preflight。第三种值或已删除表示冲突并展示 diff；失败继续 unknown，并按通常的类型化失败矩阵把可保留详情设为 `REFRESH_FAILED` 或施加相应作用域阻断，且只允许再次 reconciliation。任何非 verified 结果都保留草稿。

`PUBLISHING` / `VERIFYING` 期间禁止普通 discard 或破坏目标的切换。`SERVER_STATE_UNKNOWN` 下只允许 reconciliation、复制草稿，或通过明确警告“服务端可能已改变”的 abandon 动作结束本地会话；abandon 永不修改缓存。

只有 `VERIFIED` 可以把发布命令的结果作为当前详情并清 dirty；reconciliation 的任何 read-back 仍独立更新、删除或阻断缓存。这里的 V3 `VERIFIED` 只是内部状态，表示 read-back 当时观察到正文与保留元数据等于命令，不表示 preflight 与 POST 之间没有覆盖并发更新。对用户仍称普通 V3 发布，不新增独立风险确认或“已验证的乐观发布”等产品名称；严格要求 CAS 的用户应保持该 profile writes disabled。

V3 的 `appName`、`desc`、`configTags`、`type`、`encryptedDataKey` 在协议上可选，但插件将“重发已知值”作为防丢失产品策略。`tag` 与 `configTags` 不混用。server-owned 或不可回读字段由 adapter 明确排除；关键字段无法保存或验证时阻止发布。

## 17. P1 只读历史

- V1/V3 adapter 各自在当前 identity、Namespace 和 coordinate 内提供相同用户流程。
- 支持历史分页、查看正文、两历史版本 diff、历史与当前 detail diff。
- `CapabilityUnsupported`、`PermissionDenied`、authoritative empty 分开呈现。
- 历史 metadata/body 只放在按完整 identity 隔离的有界内存 cache；不持久化，不承诺离线。
- 历史操作 epoch-gated，不跨 profile、generation、principal 或 Namespace 聚合。
- 不产生 EditSession 或 PublishCommand，不提供 restore/rollback。

## 18. 对当前代码的主要落点

| 当前区域 | 目标变化 |
| --- | --- |
| `settings/NacosSettings.kt` | 只保留 application profiles/default；移除全局 active project session 语义；加入 schema/revisions/api policy/write intent/tombstone migration |
| `settings/NacosCredentialStore.kt` | 改为 revision-pinned slots、staging/orphan cleanup、缺失槽 fail-closed |
| `models/AccessIdentity.kt` | 扩展为完整 identity；以 confirmation/age 取代单一 `DataFreshness` |
| `services/NacosApiService.kt` | 收敛为 generation-neutral orchestration；协议细节迁入 V1/V3 adapters |
| `services/NacosAuthService.kt` | 替换为按完整 identity 的 memory registry 与 semantic recovery |
| `services/network/*` | 统一 transport kind、budget、redirect policy 与 sanitized failure mapping |
| `services/CacheService.kt` | 新 schema、identity key、confirmation/age、observation high-water、visibility gates、tombstone |
| `services/NacosSearchService.kt` | summary-first、coverage/completeness 分离、session epoch gate |
| `services/NamespaceService.kt` | 项目级选择与 manual/discovery 分离 |
| `ui/*` | project session、diagnostic/migration/degraded states、publish state machine；复用既有图标 |
| `psi/*` | 只消费 identity-scoped memory snapshots，不把缓存导航等同远端存在 |

建议新增清晰的 package seam，而不是继续扩张单一 `NacosApiService`：`profiles`、`session`、`protocol`、`transport`、`auth`、`cache`、`diagnostics`、`publishing`。具体包名可在实施计划中微调，但依赖方向必须由 session/orchestration 指向 domain interfaces，再指向 adapter/transport。

## 19. 测试与验收

### 19.1 Adapter contracts

- V1/V3 每个 operation 的 request method/path/parameter/header/envelope fixture。
- V1 legacy state、分页/搜索和 `casMd5` wire 作为 pinned contracts，在每个 first-class 2.x 版本运行 live gate。
- V1 public → empty tenant；V3 public → `namespaceId=public`。
- V3 state raw map；V3 status + JSON code 双重错误映射。
- V1 invalid-token 403 → semantic AuthenticationFailed；真实 PermissionDenied 不刷新。
- V1 publish 携带 `casMd5`；V3 publish 不伪造 CAS。
- Unsupported capability 与 auth/network failures 不混淆。

### 19.2 身份与并发

- 两项目共享 profile、不同 Namespace/session 时互不更新。
- endpoint/api policy/auth/principal/secret 的 revision matrix 精确生效。
- AUTO result 只推进发起项目 epoch，不修改 profile revisions。
- old response after profile/Namespace/generation switch 不发布 UI。
- profile-only policy change 后的新请求/login/refresh 不加入旧 execution flight。
- older auth failure after newer success、older success after newer permission failure。
- tombstone 后的 detail/index/shared-flight response 全部被拒绝。
- tombstone 后的 staged credential、Token、probe/session 和 last-known completion 也全部被拒绝。

### 19.3 凭据事务

- 在 staging secret 前后、profile publish 前后注入失败或模拟崩溃。
- 捕获者只能得到完整 old pair 或 complete new pair。
- orphan slot cleanup 可重入；missing current slot fail closed。
- secret 改回原值不复活旧 cache/auth session。

### 19.4 Cache 与搜索

- restart + within TTL、restart + stale、refresh failure + within TTL、deep stale 四组合。
- summary complete + 99 detail failures 仍保持 index COMPLETE。
- summary page failure 为 PARTIAL，不权威删除。
- offline content search 正确报告 X/Y coverage。
- last-known generation 只定位相同新 schema identity，不能发请求。
- AUTO probe 各失败类型与 matching/no-matching last-known 的可见性矩阵。

### 19.5 Publish

- target switch/Namespace switch/profile deletion/close with dirty draft。
- deleted target、remote conflict、V1 CAS conflict、permission denied。
- write 前 cancel → Cancelled；write 可能发送后 cancel → SERVER_STATE_UNKNOWN。
- timeout/connection loss 后不重放，并强制 read-back。
- ambiguous reconciliation 覆盖 draft、baseline、third value、deleted 与 read-back failure 五种结果；baseline 只证明命令结果当前未呈现，不证明写从未应用。
- content 相同但 type/tags/desc 丢失时不得 verified。
- write 结果不确定时旧详情立即变为 UNCONFIRMED；失败 read-back 按类型化失败矩阵进入 REFRESH_FAILED 或作用域阻断。
- verified 之前不得把 draft/command 直接写入 cache，也不清 dirty；每个 reconciliation read-back 仍按 observation high-water 更新 authoritative cache。
- V3 contract 测试必须确认无 CAS Header/参数、无自动重放，并把 `VERIFIED` 限定为 read-back 结果而非并发无覆盖证明。

### 19.6 Live environments

- 每个版本分别配置 anonymous server 与已初始化用户名/密码 auth server；不能假设默认密码。
- 3.2.3 smoke 必须使用标准发行版且不安装 legacy adapter。
- manual Namespace without discovery、read-only publish denial、verified publish 为必测场景。
- LDAP integration 固定在 3.2.x；HTTP Basic 与 Bearer 使用 protocol contracts。

以下任一问题阻断 P0：跨 identity 数据可见、write target 错误、write 自动重放、标准 3.2 依赖 legacy adapter、V3 非确定错误触发 V1 fallback。

### 19.7 IntelliJ 与生命周期

- PSI 高频回调无网络、磁盘和 `runBlocking`。
- 搜索、分页、刷新、profile/Namespace 切换只允许最新 epoch 发布。
- 大 Namespace summary 使用有限并发、批次状态发布与增量 Swing 更新。
- application、project、tool-window content、dialog 销毁后，对应 coroutine、listener 与 popup task 全部结束。
- 自动刷新字段仅为旧设置反序列化保留，不触发周期性全量请求。
- UI 实现前仍提供精确组件落点、键盘/焦点行为、正常/加载/空/失败/降级状态和高保真样例，并取得确认；本设计已确认不新增或替换状态图标资源。

## 20. 与旧设计的关系

本设计部分取代 `2026-07-11-stability-and-small-features-design.md`：

- 继续有效：EDT/PSI 非阻塞、Disposable 生命周期、有界并发、取消传播、原子缓存文件、损坏条目隔离、UI 实现前确认。
- 被取代：旧 access identity、单一 freshness、详情失败导致 PARTIAL、列表后全量正文拉取、七天后禁止导航、P0 不发布、旧诊断副作用、全局 active environment 和旧 rollout。

发生冲突时，以本设计及 ADR 0001–0038 为准。

## 21. 官方依据

- [Nacos 3.2 Upgrade Manual](https://nacos.io/en/docs/latest/manual/admin/upgrading/)：标准 3.2 移除 legacy V1/V2 HTTP API，legacy adapter 仅为临时方案。
- [Nacos V3 Admin API](https://nacos.io/en/docs/latest/manual/admin/admin-api/)：state、Namespace、配置读写、搜索与历史契约。
- [Nacos OpenAPI Overview](https://nacos.io/en/docs/latest/manual/user/overview/api-overview/)：统一响应与错误码语义。
- [Nacos Access Credentials](https://nacos.io/en/docs/latest/manual/user/auth/)：V3 login、Nacos/LDAP/OIDC 边界与 Token 传递。
- [Nacos 2.5 Authorization](https://nacos.io/en/docs/v2.5/manual/admin/auth/)：V1 Token 与 invalid-token 403 行为。
- [Nacos V1 Open API](https://nacos.io/en/docs/v1/open-api/)：V1 配置、Namespace 与历史接口。
- [Nacos Java SDK CAS](https://nacos.io/en/docs/v2.4/manual/user/java-sdk/usage/) 与 [Nacos 2.5.3 ConfigController](https://raw.githubusercontent.com/alibaba/nacos/2.5.3/config/src/main/java/com/alibaba/nacos/config/server/controller/ConfigController.java)：V1 `casMd5` 服务端 CAS。
- [Nacos Release History](https://nacos.io/en/download/release-history/)：release test matrix 的真实版本。
