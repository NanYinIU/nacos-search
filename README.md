# Nacos Search Plugin

提供一个方便的界面来搜索 Nacos 配置的 IntelliJ IDEA 插件

## 功能

*   **连接到 Nacos 服务器**: 在插件设置中配置您的 Nacos 服务器地址。
*   **多命名空间支持**: 轻松切换不同的 Nacos 命名空间。
*   **强大的搜索功能**:
    *   按照 `Data ID` 支持实时模糊搜索和精确搜索。
*   **配置管理**:
    *   查看配置列表和详细信息。
    *   （未来的功能可能包括创建、编辑和删除配置）。
*   **缓存机制**: 通过缓存 Nacos 配置来提高性能，并支持自动刷新缓存。
*   **分页**: 支持对搜索结果进行分页浏览。

## 如何使用

1.  **安装插件**: 从 JetBrains Marketplace 安装 `Nacos Search` 插件。
2.  **配置插件**:
    *   打开 `Settings/Preferences` -> `Tools` -> `Nacos Settings`。
    *   输入您的 Nacos 服务器地址（例如 `http://localhost:8848`）。
    *   （可选）配置用户名和密码（如果您的 Nacos 服务器需要认证）。
    *   （可选）启用缓存并设置自动刷新间隔。
3.  **打开工具窗口**: 通过 `View` -> `Tool Windows` -> `Nacos Search` 打开插件窗口。
4.  **开始搜索**:
    *   在工具窗口顶部选择一个命名空间。
    *   在搜索框中输入您的搜索条件。
    *   搜索结果将显示在下方的列表中。
    *   单击列表中的配置项以查看其详细信息。

## 开发

这是一个使用 Kotlin 和 Gradle 构建的 IntelliJ 平台插件。

### 项目结构

*   `src/main/kotlin/com/nanyin/nacos/search`:
    *   `actions`: 包含 UI 动作，例如刷新缓存。
    *   `listeners`: 包含事件监听器。
    *   `managers`: 管理插件的初始化等。
    *   `models`: 数据模型，例如 `NacosConfiguration`。
    *   `services`: 核心服务，包括 `NacosApiService`、`NacosSearchService` 和 `CacheService`。
    *   `settings`: 插件的设置页面。
    *   `ui`: 所有的 UI 组件，例如工具窗口、面板等。

### 构建项目

```bash
gradle buildPlugin
```

## 许可证

[MIT](LICENSE)