# Joker 翻译贡献指南

Joker 使用英语作为源语言，并维护简体中文和繁体中文翻译。翻译通过仓库的
[Pull Request](https://github.com/hbKnom/joker/pulls) 提交；`main`
分支始终是最终源代码和资源目录的权威来源。

## 支持的语言与文件

| 语言 | 角色 | 文件 |
| --- | --- | --- |
| 英语 | 开发者维护的源语言与 fallback | `app/src/main/res/values/strings.xml` |
| 简体中文 | 翻译目标 | `app/src/main/res/values-zh-rCN/strings.xml` |
| 繁体中文 | 翻译目标 | `app/src/main/res/values-zh-rTW/strings.xml` |

开发者在普通代码 PR 中新增或修改英语源字符串。翻译贡献者直接修改两个中文目标的 XML
并提交 PR。目标目录可以暂时缺少源 key，此时 Android 会显示英语 fallback。

## 字符串规则

- 资源名使用稳定、描述用途的 `snake_case`，不要从译文生成资源名或技术 ID。
- 使用完整句子，避免在代码中拼接可翻译的句子片段。
- 格式参数使用带索引的 Java Formatter 占位符，如 `%1$s`、`%2$d`；译文必须保留相同
  的参数索引和转换类型。
- 数量文本使用 `plurals`。保留源条目的资源类型、markup 标签结构和必要属性。
- 在含义、变量、上下文或长度限制不直观时，为源资源添加简洁的译者注释。
- 仅技术用途的资源在英语源目录标记 `translatable="false"`，不要复制到目标目录。
- 不要使用 Android `tools:ignore` 隐藏真实的占位符或 markup 错误。

以下内容不是可翻译 UI：日志、SQL、协议常量、DexKit/宿主匹配锚点、类名和成员名、微信
原始字符串、偏好键、缓存键、URL、包名和脚本标识符。术语表只规定面向用户的措辞，不授权
翻译同名的技术值。

## 审核流程

1. 贡献者修改 `values-zh-rCN/strings.xml` 或 `values-zh-rTW/strings.xml` 后提交 PR。
2. 对应语言的审核者检查含义、术语、占位符、标点和 UI 长度并批准。
3. 维护者等待 CI 与人工审查通过后合并。

简体和繁体资源独立维护；不得用周期性 OpenCC 转换覆盖人工审核结果。

## 本地校验

```bash
./x i18n-check
./x build
git diff --check
```

`./x i18n-check` 检查资源目录、XML、重复/仅目标 key、资源类型、markup、复数和格式参数。
完整构建必须使用 `./x build`，以同步刷新 Rust native 库和 APK。

## 许可与署名

提交的翻译作为 Joker 项目贡献，按仓库的 GPLv3 许可证发布；本项目不要求单独签署翻译
CLA。已接受贡献的译者可以在 [`TRANSLATORS.md`](TRANSLATORS.md) 中添加自愿公开的名称
和个人主页，不需要提供私人邮箱。通用术语请参阅 [`GLOSSARY.md`](GLOSSARY.md)。
