# Joker

适用于微信的增强模块，支持 **Xposed**（LSPosed / NPatch）与 **Zygisk**（KernelSU / APatch / Magisk + Zygisk）两种加载方式。

[![CI](https://github.com/hbKnom/joker/actions/workflows/ci.yml/badge.svg)](https://github.com/hbKnom/joker/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/hbKnom/joker?label=release)](https://github.com/hbKnom/joker/releases)

> 本项目是自成一体的独立分支：源码、构建、文档与发布都在本仓库内完成，不依赖任何上游仓库。

## 下载

| 渠道 | 说明 |
| --- | --- |
| [Releases](https://github.com/hbKnom/joker/releases) | **稳定版**。Zygisk 模式取 `Joker-<versionCode>-<commit>-release.zip`，Xposed 模式取 `Joker-<versionCode>.apk` |
| [GitHub Actions](https://github.com/hbKnom/joker/actions/workflows/ci.yml) | **每夜版**。每次推送都会构建，产物保留 90 天 |

没把握时优先用 Releases 里的版本：它对应一个确定的源码提交，附 sha256 与校验说明。

## 安装

完整步骤见 [安装指南](docs/installation.md)，两种模式的要点：

- **Zygisk 模式**：Root 管理器中刷入 zip → **彻底关机再开机**（不是重启）→ 在超级用户里为微信打开开关，并关闭「卸载模块」。
- **Xposed 模式**：安装 APK → 在 LSPosed / NPatch 中启用模块并勾选微信 → 重启微信。

安装后「Joker 设置」里每一项都可以单独开关；改动一般即时生效，涉及通知接管的功能需要重启微信。

## 特性

共 13 个分类、126 篇功能说明（见 [`docs/features/`](docs/features/)），全部可在模块设置内逐项开关：

| 分类 | 篇数 | 分类 | 篇数 |
| --- | --- | --- | --- |
| 聊天 | 39 | 小程序 | 6 |
| 系统与隐私 | 25 | 娱乐 | 4 |
| 联系人与群组 | 11 | 个人资料 | 3 |
| 调试 | 11 | 公众号 | 2 |
| 界面美化 | 9 | 视频号 | 2 |
| 朋友圈 | 7 | 通知 | 1 |
| 红包与支付 | 6 | | |

### 重点功能

- **潜语 · 聊天分析决策**：把会话上下文、消息元数据与多轮推理结果交给模型，产出情绪概率、风险提示、关系趋势，以及一句能直接发出去的回复草稿（可长按复制）。
- **聊天记录分析**：本地统计报告 + AI 总结，支持自定义维度包与分析时间范围；模型配置与其他 AI 功能共用一份。
- **负一屏日历与黄历**：主页侧滑面板内嵌日历，月 / 周 / 日三视图共用同一份黄历数据，显示宜忌、值神、十二时辰与节日；配色跟随模块主题。
- **莫奈引擎**：从壁纸取色并重绘微信界面资源，未读角标、卡片与弹窗配色随之联动。
- **通知增强**：`通知进化` 与 `自定义通知` 两套方案，支持静音时段、按会话覆盖、免打扰与通知内快捷回复（两者互斥，同时开启会都不接管）。
- **常用四件套**：`屏蔽消息`、`聊天功能开关`、`自动通过好友申请`、`自定义通知`。
- **批量与脚本**：批量删除 / 标记 / 发送，Java 与 Python 两套脚本引擎。

### 兼容性说明

- 部分功能存在互斥关系（例如 `通知进化` 与 `自定义通知`），设置页会在冲突时提示。
- 功能是否生效依赖微信版本与混淆结构；模块内置 DexKit 锚点解析，解析失败时会降级为「不生效」而不是崩溃。

## 文档

文档站点源码位于 [`docs/`](docs/)（VitePress），可本地预览或自行部署：

```bash
cd docs
bun install
bun run dev
```

导航：[快速开始](docs/getting-started.md) · [安装指南](docs/installation.md) · [Zygisk 模式](docs/zygisk.md) · [配置指南](docs/configuration.md) · [常见问题](docs/faq.md) · [开发指南](docs/development/index.md)

## 从源码构建

前置：JDK 21、Android SDK / NDK、Rust nightly（Zygisk 部分）。

```bash
# Xposed 模块（Release APK）
./gradlew assembleStandardRelease

# Zygisk 模块包（需要先有上面的 APK）
./x zygisk build --apk-release --release --skip-apk-build \
    --apk app/build/outputs/apk/standard/release/app-standard-release.apk
```

推送后 CI 会自动跑 `build`；Zygisk 包需要在 Actions 页手动触发 `workflow_dispatch`（`build_zygisk`）。发布正式版本见 [VERSIONING.md](VERSIONING.md)。

## 日志

模块日志写在 `/sdcard/Android/data/com.tencent.mm/Joker/logs/`（宿主包名随修补包名变化），单日超过阈值会滚动为 `joker-<日期>.<序号>.log`，只保留最近 3 天。排查问题时请先打开「模块设置 → 调试 → 详细日志」，复现一次后再把日志发出来。

## 致谢

[WAuxiliary](https://github.com/HdShare/WAuxiliary_Public)

[NewMiko](https://github.com/dartcv/NewMiko/blob/archives/)

[QAuxiliary](https://github.com/cinit/QAuxiliary)

[FingerprintPay](https://github.com/eritpchy/FingerprintPay)

[I-Am-Pad](https://github.com/Houvven/I-Am-Pad)

[LSPlant](https://github.com/LSPosed/LSPlant)

## 许可

[GPL-3.0](LICENSE)
