# Fork 实施计划：内外网双地址 + 离线查看历史

> 目标：在**尽量少改动上游代码、便于持续同步 upstream** 的前提下，实现
> 1) 同一台 gotify server 通过「外网 / 内网」两个地址访问，失败自动切换；
> 2) 无网络时仍可正常打开 App，查看最近 7 天的历史消息，离线删除联网后补发。

基线：`gotify/android` 2.10.1（本仓库 `app/` + 生成的 `client/`）。

---

## 0. 已确认的前提

| 项 | 结论 |
|---|---|
| 两个域名 | 指向**同一台** server 实例 → token / messageId / appId 完全一致，单账号单库 |
| 内网协议与证书 | https + 公网 CA 证书 → **不需要**改 SSL / 证书配置（`network_security_config` 已信任系统 CA） |
| 离线保留 | 最近 **7 天**（按 `Message.date` 裁剪，另保留最新 20 条防时钟异常） |
| 离线删除 | 本地立即删 + 写入待发送队列，联网后重放 |
| 存储方案 | **JSON 快照**（复用 `Utils.JSON`，Gson 已注册 `OffsetDateTime` adapter），不引入 Room |
| 多账号 | 不做，也不预留 `profileId`（同一实例无必要） |

---

## 1. 设计要点

### 1.1 多域名：把 URL 收口到 `Settings.url` 的 getter

现有 7 处调用点都读 `Settings.url`。因此不改调用点，只改 getter：

```
Settings.url.get() = EndpointResolver.current ?: 已保存的 url
```

于是 `ClientFactory` / `WebSocketConnection` / Coil / 列表页 / 侧栏 header **零改动**自动跟随。

- 选路器 `EndpointResolver`（object）：保存 `active`、失败计数；
- 探测 `GET {base}/version`，2s 超时、3s 总预算，自己构建 `ApiClient`（不动 `ClientFactory`）；
- 归属策略：Wi-Fi 优先内网，蜂窝优先外网；
- 持久化「上次可用地址」，冷启动直接用，避免每次探测；
- 切换后 `startService(WebSocketService)` 即可，`onStartCommand` 本来就会用新 URL 重建连接；
- Activity 侧的 Retrofit 客户端在创建时就绑定了 URL，因此检测到 `settings.url` 变化时
  沿用现有 `refreshAll()` 重启界面（`MessagesModel.clientBaseUrl` 做对比）。

### 1.2 离线：先给界面，再后台校验

`InitializationActivity` 现在是「校验成功才进列表」，改为：

```
token 存在 → 直接进 MessagesActivity + 启动 WebSocketService
           → 后台 currentUser()：401 才回登录；网络错误忽略（离线模式）
```

列表侧：

- `MessageStore` 持久化消息（每个 app 一个 JSON 文件，原子写、带去抖），进程内单例，
  Activity 与 Service 共用，避免两个实例各自持有缓存互相覆盖；
- `MessageFacade.loadMore` 网络失败时 `hydrate` 本地缓存（`loaded=true` / `hasNext=false` / `nextSince=最旧 id`）；
- 应用列表同样缓存，避免离线侧栏空白；
- WebSocket 重连成功广播 `CONNECTED` → 列表清空重拉（补齐离线期间的新消息）；
- 删除走 `PendingSync`：本地删 + 失败入队 + 联网重放。

### 1.3 关键取舍（为什么不是 Room）

- 数据本来就全量在内存，查询需求只有「按 app 分组 + id 倒序」；
- Room 要加 KSP / schema / migration，都会落到上游常改的 `app/build.gradle.kts`；
- 消息存成**不透明 JSON**，上游改 `client` model 时旧缓存照样能读（Gson 容忍缺字段/多字段），这对「跟随上游」是决定性优势；
- 以后要做搜索/多账号，再把快照一次性导入 Room。

---

## 2. 文件清单

### 新增（上游不会冲突）

| 文件 | 作用 | 里程碑 |
|---|---|---|
| `app/src/main/kotlin/com/github/gotify/fork/MessageStore.kt` | 消息/应用列表 JSON 快照、7 天裁剪、待发送删除队列 | M1 |
| `app/src/main/kotlin/com/github/gotify/fork/ForkPrefs.kt` | fork 私有配置（上次可用地址、lastReceivedMessageId、回填时间） | M1 |
| `app/src/main/kotlin/com/github/gotify/fork/PendingSync.kt` | 删除补发 | M3 |
| `app/src/main/kotlin/com/github/gotify/fork/Backfill.kt` | 联网后回填最近 7 天 | M3 |
| `app/src/main/kotlin/com/github/gotify/fork/EndpointResolver.kt` | 内外网选路 + 探测 + 切换 | M4 |
| `app/src/main/res/values/strings_fork.xml` | 新增文案（`fork_` 前缀） | M3 |
| `app/src/test/kotlin/com/github/gotify/fork/*.kt` | 单测（存储/裁剪/选路顺序） | M1/M4 |

### 修改（升级时重点看，全部用 `FORK` 注释标记）

| 文件 | 改动 | 风险 |
|---|---|---|
| `Settings.kt` | `url` getter 委托 resolver；新增 `backupUrl` | 低 |
| `GotifyApplication.kt` | 一行 `EndpointResolver.bootstrap(this)` | 低 |
| `InitializationActivity.kt` | 启动改为离线优先，删除旧鉴权流程 | **中** |
| `MessageState.kt` | 新增 `fromCache` | 低 |
| `MessageStateHolder.kt` | 新增 `hydrate()`；`newMessages` 按 id 去重 | 低 |
| `MessageFacade.kt` | 水合 / 落盘 / 删除补发 | 中 |
| `MessageRequester.kt` | `asyncRemoveMessage` 改为按 id + 回调 | 低 |
| `ApplicationHolder.kt` | 应用列表缓存 | 低 |
| `MessagesModel.kt` | 创建 `MessageStore` 并注入 | 低 |
| `MessagesActivity.kt` | 会话校验 / 重连刷新 / 离线提示 / 地址切换入口 | 低 |
| `WebSocketService.kt` | 落盘 / 离线通知 / 失败触发换路 / 连接广播 | 低 |
| `MissedMessageUtil.kt` | 补消息分页 10 → 100 | 低 |
| `SettingsActivity.kt` | `backupUrl` 变更后重新探测 | 低 |
| `root_preferences.xml` | 「备用地址（内网）」设置项 | 低 |
| `app/build.gradle.kts` | 一行 `testImplementation(junit)` | 低 |

---

## 3. 里程碑与验收

### M1 存储层（可独立验证）

- [x] `ForkPrefs`
- [x] `MessageStore`（原子写、去抖、7 天裁剪、按 id 去重）
- [x] 单测：往返 / 去重 / 裁剪 / 损坏文件恢复

验收：单测通过；缓存文件位于 `filesDir/offline/`。

### M2 离线打开与列表水合

- [x] 启动改造（离线优先）
- [x] `MessageStateHolder.hydrate` + 去重
- [x] `MessageFacade` 水合
- [x] 应用列表缓存
- [x] 会话校验下沉到 `MessagesActivity`（401 才回登录）

验收：飞行模式冷启动可进入列表并看到历史；杀进程重开仍在；恢复网络后自动补齐。

### M3 删除补发与回填

- [x] 待发送删除队列（本地删 + 联网重放）
- [x] WebSocket 重连广播 → 列表刷新
- [x] 回填最近 7 天（最多 10 页）
- [x] 离线提示（Snackbar）/ 前台通知文案
- [x] `lastReceivedMessageId` 持久化，避免重复通知

验收：离线删除后联网服务端确实删除；离线期间通知不重复。

### M4 多域名选路

- [x] `EndpointResolver`（探测 + 持久化 + 手动切换）
- [x] `Settings.url` 委托 + `backupUrl`
- [x] 设置项「备用地址（内网）」
- [x] 侧栏点击地址手动切换

验收：手动切换到内网可用；地址显示随实际连接变化。

### M5 自动回退

- [x] 请求失败累计触发探测
- [x] WebSocket 失败触发探测
- [x] 切换后重启 Service
- [x] 网络变化（Wi-Fi/蜂窝）重新选路

验收：断开外网 ≤5s 自动切内网并重连；回外网后切回。

### M6 收尾

- [x] `FORK.md` 变更清单（升级用）
- [ ] 构建验证（当前开发环境无网络、无法下载 Gradle 8.14.2 发行版且 `~/.gradle` 不可写，
      需在有 Android SDK 的机器执行下方命令）
- [ ] 手工回归（见下）

> 已实现的代码在 `app/src/main/kotlin/com/github/gotify/fork/` 与上表修改文件中，
> 全部通过 `// FORK` 注释标记。首次构建若有编译问题，优先检查
> `EndpointResolver.kt` 与 `MessagesActivity.kt` 的 FORK 块。

---

## 4. 本机验证命令

```bash
./gradlew :app:testDevelopmentUnitTest :app:assembleDevelopment
# 或一次跑完 CI 会跑的内容（含 lintKotlin、Android Lint、单测）
./gradlew build
```

手工回归：

1. 飞行模式冷启动 → 显示缓存、无阻断弹窗；
2. 杀进程重开 → 缓存仍在；
3. 离线删除 → 联网后服务端确认删除；
4. 断外网 → 自动切内网（观察侧栏地址与前台通知）；
5. 恢复外网 → 切回；
6. 双域名切换期间不出现重复/漏消息。

---

## 5. 上游同步约定

1. 所有改动包在 `// FORK-BEGIN` / `// FORK-END` 之间；
2. 新字符串只放 `strings_fork.xml`，key 统一 `fork_` 前缀；
3. 永远不改 `client/`（OpenAPI 生成）；
4. 升级流程：`git merge upstream/master` → 只应在上表文件冲突 → 按 `FORK` 标记重放 → 跑单测与手工清单。
