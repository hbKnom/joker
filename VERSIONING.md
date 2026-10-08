# 版本与发布

Joker 不使用语义化版本。版本号完全由 git 推导，构建时写进 `module.prop`、`BuildConfig` 与产物文件名，没有手工改号的环节，也没有发布分支。

## 版本号从哪来

两个值都在 `app/build.gradle.kts` 里算：

| 字段 | 来源 | 示例 |
| --- | --- | --- |
| `versionCode` | `git rev-list --count HEAD`（可达提交总数） | `82` |
| `versionName` | `"git+"` + `git rev-parse --short=8 HEAD` | `git+c09a4f02` |

- `versionCode` 随每次提交单调递增，可直接当发布序号使用（Release tag 就是 `v<versionCode>`）。
- `versionName` 唯一标识构建所用的提交。
- 本地工作区可能是浅克隆，`git rev-list --count HEAD` 会偏小；**发布号一律以 CI 产物内 `module.prop` 的 `versionCode` 为准**。

构建还会把这些写进 `BuildConfig`：`COMMIT_HASH`（短哈希）、`TAG`（固定为 `"Joker"`）、`BUILD_TIMESTAMP`。

## 发布通道

| 通道 | 内容 | 触发 | 保留 |
| --- | --- | --- | --- |
| [GitHub Releases](https://github.com/hbKnom/joker/releases) | tag `v<versionCode>`，资源为 Zygisk 包 `Joker-<versionCode>-<short8>-release.zip`，说明里带 sha256 与来源 CI run | 手动 dispatch `Release` 工作流 | 永久 |
| GitHub Actions artifacts | `joker-apk`、`joker-mapping`、`joker-zygisk` | 每次 push（`joker-zygisk` 需手动 dispatch） | 90 天 |

Release 是稳定通道，Actions artifacts 是每夜通道。两者都由同一次 CI 构建产出，可相互追溯。

## 发布流程

1. 改完代码，push 到 `main`，等 CI 的 `build` 通过（这是编译门禁）。
2. 在 Actions 页手动 dispatch `CI`（ref 选 `main`）—— 只有 `workflow_dispatch` 会跑 `build_zygisk`，它负责产出 Zygisk 包。
3. 等 `build` + `build_zygisk` 双绿后，手动 dispatch `Release` 工作流：
   - `tag`：填 `v<versionCode>`，例如 `v82`
   - `source_run`：留空表示取本分支最近一次成功的 `workflow_dispatch` 运行；也可以显式指定 run id
4. 工作流会自行校验后再发布：包顶层必须有 `module.prop` 与 `META-INF/com/google/android/update-binary`、文件名里的 `versionCode` 必须与 `module.prop` 一致、产物短哈希必须等于来源 run 的 `headSha`。任一不满足就失败，不会发出对不上号的包。

**为什么不用 tag push 触发发布**：`build_zygisk` 只在 `workflow_dispatch` 下运行，tag push 不会产出 Zygisk 包。所以发布是「先构建、再发布」两个显式动作，而不是推 tag 就出包。

## 命名约定

- Release tag：`v<versionCode>`
- Release 资源：`Joker-<versionCode>-<short8>-release.zip`
  xtask 原始名字里带 `git+`（`Joker-82-git+c09a4f02-release.zip`），发布时会替换成 `-`：Release 资源名经 URL query 传递，`+` 会被解析成空格。
- Xposed APK：`app-<flavor>-release.apk`，在 CI artifact `joker-apk` 里（本仓库当前 flavor 为 `standard`）。

## 构建签名

CI 在**未配置** `KEYSTORE_BASE64` secret 时会降级为 debug 签名（见 `app/build.gradle.kts` 的 `foundKeystore` 分支）。GitHub runner 不保存 `~/.android`，debug keystore 随构建环境重建，**签名密钥不保证跨构建一致** —— 这会让 APK 之间无法覆盖安装。

要长期分发 APK，请在仓库 Secrets 里配置：

| Secret | 用途 |
| --- | --- |
| `KEYSTORE_BASE64` | keystore 文件的 base64 |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥口令 |

配好之后 `HAS_KEYSTORE=true`，CI 自动走 release 签名分支。Zygisk 包不受这条影响：用户刷入的是模块 zip，不是这个 APK。

## 内置更新检查

`dev.joker.utils.AppUpdater` 目前 `BASE_URL` 为空，即**更新检查已停用**，不会请求任何外部地址。如果将来要启用自有更新源，需要同时对齐三处：

1. 约定文件名 `Joker-<versionCode>-git+<short8>-release.zip`（`AppUpdater.zygiskModuleFileName` 用的就是这个格式，和上面的 Release 资源命名差一个 `git+`）；
2. `update.json`（字段 `versionCode` / `versionName`）的托管位置；
3. `BASE_URL` 指向的发布源。
