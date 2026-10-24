# Fork 改动清单（用于跟随上游）

上游基线：`gotify/android` 2.10.1
详细计划与验收：[FORK_PLAN.md](FORK_PLAN.md)

## 新增（上游不会冲突）

| 文件 | 作用 |
|---|---|
| `app/src/main/kotlin/com/github/gotify/fork/MessageStore.kt` | 消息/应用列表 JSON 快照，7 天裁剪，待发送删除队列 |
| `app/src/main/kotlin/com/github/gotify/fork/ForkPrefs.kt` | fork 私有配置（上次可用地址、lastReceivedMessageId、回填时间） |
| `app/src/main/kotlin/com/github/gotify/fork/PendingSync.kt` | 离线删除补发（含 `PendingDelete` 模型） |
| `app/src/main/kotlin/com/github/gotify/fork/Backfill.kt` | 联网后回填最近 7 天消息 |
| `app/src/main/kotlin/com/github/gotify/fork/EndpointResolver.kt` | 内外网选路：探测、持久化、手动/自动切换；`normalizeEndpoint()` 统一校验/补全地址；Wi-Fi/VPN 判定、网络变化广播、Wi-Fi 下主动尝试内网 |
| `app/src/main/kotlin/com/github/gotify/fork/ServerAddressesDialog.kt` | 「公网地址 / 内网地址 / Wi-Fi 优先内网」编辑与连通性测试对话框 |
| `app/src/main/res/layout/dialog_server_addresses.xml` | 上述对话框的布局 |
| `app/src/main/res/values/strings_fork.xml` | 新增文案，key 统一 `fork_` 前缀 |
| `app/src/test/kotlin/com/github/gotify/fork/*.kt` | 存储与选路/地址规范化单测 |

## 修改（升级时重点看，全部带 `// FORK` 注释）

| 文件 | 改动 | 说明 |
|---|---|---|
| `Settings.kt` | `url` getter 委托 `EndpointResolver.current` 并做规范化；新增 `storedUrl`、`hasValidUrl`、`backupUrl`；`url` setter 重置选路 | 所有 URL 调用点无需改动；非法地址不会传给 Retrofit |
| `GotifyApplication.kt` | `EndpointResolver.bootstrap(this)` | 冷启动恢复上次可用地址（自动丢弃非法值） |
| `InitializationActivity.kt` | `FORK-BEGIN/END` 块：有 token 且地址有效才直接进列表，否则回登录；删除旧的 `tryAuthenticate/failed/dialog/authenticated/requestVersion` | 离线可打开的关键；避免空/非法地址启动崩溃 |
| `MessageState.kt` | 新增 `fromCache` | 标记列表来自缓存 |
| `MessageStateHolder.kt` | 新增 `hydrate()`；`newMessages` 按 id 去重并复位 `fromCache` | 缓存水合 |
| `MessageFacade.kt` | 构造参数 `store`（默认 null）；失败时水合；成功时落盘 + 重放删除；网络调用不再持有 `@Synchronized` 锁；`hydrateFromCache()` 首屏先用缓存 | 避免主线程被 HTTP 超时阻塞，首次加载可直接出缓存 |
| `MessageRequester.kt` | `asyncRemoveMessage(messageId, onResult)`；`loadMore/deleteAll` 兜底捕获所有异常 | 支持离线删除入队，异常不再逃逸到协程 |
| `ApplicationHolder.kt` | 构造参数 `store`；成功落盘、失败读缓存；Activity 已销毁时不再回调 UI | 离线侧栏可用 |
| `MessagesModel.kt` | 创建 `MessageStore` 并注入 | — |
| `MessagesActivity.kt` | `FORK-BEGIN/END` 块：启动前校验 token/地址、缓存优先首屏、会话校验、重连只补新消息不清空列表、离线提示、地址切换/编辑入口、网络回调与侧栏地址实时刷新；Activity 销毁后不再更新 UI | 不再因 WebSocket 重连清空历史；Wi-Fi 关闭后侧栏立即回到公网地址 |
| `Utils.kt` | `showSnackBar` 在 Activity 结束/销毁后静默返回 | 避免延迟回调崩溃 |
| `WebSocketService.kt` | 消息/应用落盘、`CONNECTED_BROADCAST`、离线通知文案、失败触发换路、联网回填与删除重放；网络丢失/Wi-Fi↔蜂窝切换回调；无有效地址或初始化失败时安静停止；后台线程异常兜底 | 防止启动阶段进程崩溃，并让选路及时跟随网络 |
| `MissedMessageUtil.kt` | 补消息每页 10 → 100 | 长时间离线后恢复时不再发几十次请求 |
| `SettingsActivity.kt` | 「连接」分类新增「Server addresses」入口，打开对话框；显示当前公网/内网地址；改完立即探测 | 第二个 URL 的配置界面 |
| `root_preferences.xml` | 「连接」分类新增 `server_addresses` 入口（替换裸 `backupUrl` 输入框） | 统一走带校验的对话框 |
| `messages_menu.xml` | 侧栏新增「Server addresses」菜单项 | 打开配置界面更直观 |
| `app/build.gradle.kts` | `testImplementation("junit:junit:4.13.2")` | — |

## 升级流程

1. `git fetch upstream && git merge upstream/master`
2. 冲突只应出现在上表文件；按 `// FORK` / `FORK-BEGIN` 标记重放
3. 跑 `./gradlew build`（含 lintKotlin、Android Lint、单测）
4. 走一遍 [FORK_PLAN.md](FORK_PLAN.md) 中的手工回归清单

## 已知限制

- 离线缓存只包含「App 收到/拉取过」的消息；首次联网会回填最近 7 天（最多 10 页 / 1000 条）。
- 正文图片（markdown 图片、`bigImageUrl`）不做离线；应用图标仍走 Coil 缓存。
- 「删除全部」在离线时排队，联网后重放，会连同离线期间新到的消息一起删除（服务端语义）。
- 未实现多账号；两个地址必须指向同一台 server。

## 上游同步约定

- 新字符串只放 `strings_fork.xml`，避免 `strings.xml` 冲突；
- 永远不改 `client/`（OpenAPI 生成）；
- 不重排既有格式，改动块保持最小。
