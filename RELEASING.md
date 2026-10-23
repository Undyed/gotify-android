# 发版与签名配置说明

本仓库的构建与发布全部由 [.github/workflows/main.yml](.github/workflows/main.yml) 完成。
这份文档说明**一次性配置**（密钥、权限）和**日常发版**怎么做，以及出问题时怎么排查。

> 这是一个 fork。上游（gotify/android）有自己的签名密钥，本仓库必须用自己生成的密钥，
> 因此发布出来的 APK 与官方版**签名不同**，装过官方版的手机无法直接覆盖安装。详见 [签名与应用身份](#签名与应用身份)。

---

## 1. 一次性配置

### 1.1 启用 Actions

fork 出来的仓库默认不执行 workflow：打开仓库的 **Actions** 标签页，点
"I understand my workflows, go ahead and enable them"。

### 1.2 允许 workflow 创建 Release

**Settings → Actions → General → Workflow permissions** 选择 **Read and write permissions**，保存。

上传 Release 资产需要 `contents: write`。workflow 里已经声明了，
但如果仓库这一项是"只读"，GitHub 会把 token 降级，上传时报
`403 Resource not accessible by integration`。

### 1.3 生成签名密钥

`keytool` 在 JDK 的 `bin` 目录里（本机没有 JDK 时先装一个 JDK 17）：

```bash
keytool -genkeypair -v \
  -keystore gotify-release-key.jks \
  -alias gotify-release-key \
  -keyalg RSA -keysize 2048 -validity 10000
```

- **alias 必须是 `gotify-release-key`**：workflow 里 `RELEASE_KEY_ALIAS` 是写死的。
- 会依次要求输入 keystore 密码、key 密码和证书信息，密码请记下来，下一步要用。

把密钥转成单行 base64：

```bash
# Linux
base64 -w0 gotify-release-key.jks > gotify-release-key.b64

# macOS（没有 -w0）
base64 -i gotify-release-key.jks | tr -d '\n' > gotify-release-key.b64
```

```powershell
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("gotify-release-key.jks")) |
  Set-Content -NoNewline gotify-release-key.b64
```

> `.jks` / `.b64` 已在 `.gitignore` 里排除，**不要提交、不要贴到 issue 里**。
> 密钥丢了就只能换新密钥重新发版，且老用户必须卸载重装。

### 1.4 配置三个 Secret

**Settings → Secrets and variables → Actions → New repository secret**：

| 名称          | 内容                                       |
| ------------- | ------------------------------------------ |
| `RELEASE_KEY` | `gotify-release-key.b64` 的完整内容（单行） |
| `STOREPASS`   | keystore 密码                              |
| `KEYPASS`     | key 密码                                   |

注意是 **Secrets**，不是 Variables；名称大小写敏感。

### 签名与应用身份

包名仍然是 `com.github.gotify`（见 [app/build.gradle.kts](app/build.gradle.kts) 的 `applicationId`）。两种做法二选一：

| 方案 | 影响 |
| --- | --- |
| 保持 `com.github.gotify` | 与官方版同包名但签名不同：已装官方版的手机**无法覆盖安装**，必须先卸载官方版 |
| 改成 `com.github.gotify.fork` 之类 | 可与官方版共存、互不影响，但要同步改 `namespace`/`applicationId` 并接受独立的数据目录 |

---

## 2. 日常发版

### 2.1 打 tag 发布

```bash
git tag v2.10.1
git push origin v2.10.1
```

触发顺序：

1. `check` job 跑 `./gradlew build`（lint + 单测），**不通过就不会发版**；
2. `release` job 用 `-Psign` 构建 `assembleRelease`；
3. 校验 APK 签名，生成 `Gotify.apk` 与 `Gotify.apk.sha256`；
4. 创建/更新该 tag 的 GitHub Release 并附上两个文件。

规则：

- 只有 **以 `v` 开头**的 tag 才会触发发布，其它 tag 只跑检查；
- `versionName` 默认取 **tag 去掉开头的 `v`**，`versionCode` 取 `app/build.gradle.kts` 里的值；
- tag 里带 `-`（如 `v2.10.1-fork1`）会自动标成 **prerelease**，并且不设为 latest。

### 2.2 手动重发

上传失败、想换版本号、或需要重跑时，不必删 tag 重推：

**Actions → Build → Run workflow**，填写：

| 输入 | 说明 |
| --- | --- |
| `tag` | 要发布的已存在 tag，例如 `v2.10.1`；留空则只构建不发布 |
| `version_name` | 覆盖 APK 的 versionName，留空则用 tag |
| `version_code` | 覆盖 versionCode，留空则用 gradle 里的值 |

同名 Release 已存在时会覆盖资产，可以直接重跑到成功。

### 2.3 本地构建等价命令

```bash
RELEASE_STORE_FILE="$PWD/gotify-release-key.jks" \
RELEASE_STORE_PASSWORD='keystore 密码' \
RELEASE_KEY_ALIAS=gotify-release-key \
RELEASE_KEY_PASSWORD='key 密码' \
./gradlew -Psign -PversionName=2.10.1 assembleRelease
```

Windows 下把 `./gradlew` 换成 `gradlew.bat`，环境变量用 `$env:RELEASE_STORE_FILE=...` 设置，或直接在 cmd 里 `set`。
产物：`app/build/outputs/apk/release/app-release.apk`。

调试用的普通构建（不签名）：`./gradlew assembleDebug`；开发版本（包名带 `.dev`）：`./gradlew assembleDevelopment`。

---

## 3. 产物说明

| 资产 | 说明 |
| --- | --- |
| `Gotify.apk` | 固定文件名，便于 `releases/latest/download/Gotify.apk` 这种永久链接引用 |
| `Gotify.apk.sha256` | 校验和，`sha256sum -c Gotify.apk.sha256` 验证 |

此外每次运行都会留一个名为 `gotify-apk-<tag>` 的 workflow artifact（默认保留 90 天），
即使没配密钥也能下载到构建结果。

发布前的自动校验：

- `apksigner verify --print-certs` 打印签名证书；
- `aapt2 dump badging` 打印实际 versionName / versionCode；
- 如果**配了签名却检测到 `CN=Android Debug`**，直接让 job 失败，避免把 debug 签名包发出去。

没有配置 `RELEASE_KEY` 时，workflow 会构建一个 debug 签名的 APK，只上传 artifact 并打 warning，
**不会**创建 Release。

---

## 4. 排查

| 现象 | 原因与处理 |
| --- | --- |
| `403 Resource not accessible by integration` | 1.2 的 Workflow permissions 没放开 |
| 日志出现 `RELEASE_KEY is not configured`，Release 被跳过 | Secret 没建或名字拼错（区分大小写） |
| `base64: invalid input` | Secret 里带换行或多余字符；用 `base64 -w0` 重新生成，粘贴时别带 `-----` 说明行 |
| `No release APK was produced` | 变体名或输出路径变了，检查 `app/build/outputs/apk/release/` 下实际文件名 |
| `The release APK is still debug-signed` | 传了 `-Psign` 但别名/密码不对，AGP 回落到 debug 签名；核对 1.3、1.4 |
| tag 推上去没有任何运行 | tag 不以 `v` 开头，或 Actions 仍处于禁用状态 |
| 手机提示 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 与已安装版本签名不同，见 [签名与应用身份](#签名与应用身份) |
| `check` 失败但没有改代码 | 上游同步带来的 lint/单测问题；先本地 `./gradlew build` 复现 |

---

## 5. 首次配置检查清单

- [ ] Actions 已启用
- [ ] Workflow permissions = Read and write
- [ ] `RELEASE_KEY` / `STOREPASS` / `KEYPASS` 三个 Secret 已配置
- [ ] 已决定是否继续使用 `com.github.gotify` 包名
- [ ] 推一个 tag，确认 Release 下有 `Gotify.apk` 和 `Gotify.apk.sha256`
- [ ] 下载 APK 装机验证，`apksigner verify` 或系统设置里的签名信息与本机密钥一致
