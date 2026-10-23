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
| `app/src/main/kotlin/com/github/gotify/fork/EndpointResolver.kt` | 内外网选路：探测、持久化、手动/自动切换 |
| `app/src/main/res/values/strings_fork.xml` | 新增文案，key 统一 `fork_` 前缀 |
| `app/src/test/kotlin/com/github/gotify/fork/*.kt` | 存储与选路顺序单测 |

## 修改（升级时重点看，全部带 `// FORK` 注释）

| 文件 | 改动 | 说明 |
|---|---|---|
| `Settings.kt` | `url` getter 委托 `EndpointResolver.current`；新增 `backupUrl`；`url` setter 重置选路 | 所有 URL 调用点无需改动 |
| `GotifyApplication.kt` | `EndpointResolver.bootstrap(this)` | 冷启动恢复上次可用地址 |
| `InitializationActivity.kt` | `FORK-BEGIN/END` 块：有 token 直接进列表；删除旧的 `tryAuthenticate/failed/dialog/authenticated/requestVersion` | 离线可打开的关键 |
| `MessageState.kt` | 新增 `fromCache` | 标记列表来自缓存 |
| `MessageStateHolder.kt` | 新增 `hydrate()`；`newMessages` 按 id 去重并复位 `fromCache` | 缓存水合 |
| `MessageFacade.kt` | 构造参数 `store`（默认 null）；失败时水合；成功时落盘 + 重放删除 | 默认参数保证上游调用点兼容 |
| `MessageRequester.kt` | `asyncRemoveMessage(messageId, onResult)` | 支持离线删除入队 |
| `ApplicationHolder.kt` | 构造参数 `store`；成功落盘、失败读缓存 | 离线侧栏可用 |
| `MessagesModel.kt` | 创建 `MessageStore` 并注入 | — |
| `MessagesActivity.kt` | `FORK-BEGIN/END` 块：会话校验、重连刷新、离线提示、地址切换入口；`updateMessagesAndStopLoading` 增加离线 Snackbar | — |
| `WebSocketService.kt` | 消息/应用落盘、`CONNECTED_BROADCAST`、离线通知文案、失败触发换路、联网回填与删除重放 | — |
| `MissedMessageUtil.kt` | 补消息每页 10 → 100 | 长时间离线后恢复时不再发几十次请求 |
| `SettingsActivity.kt` | 监听 `backupUrl` 变化，重置选路并立即探测 | 改完内网地址立即生效 |
| `root_preferences.xml` | 「连接」分类新增 `backupUrl` 设置项 | — |
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
