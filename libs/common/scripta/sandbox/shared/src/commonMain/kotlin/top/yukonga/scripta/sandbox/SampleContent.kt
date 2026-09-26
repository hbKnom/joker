package top.yukonga.scripta.sandbox

import top.yukonga.scripta.editor.EditorLanguage

/** 打开外部文件的字节上限：整篇仍需入内存，超堆容量必 OOM，超此值直接拒绝并提示。 */
internal const val MAX_OPEN_BYTES = 64L * 1024 * 1024

/** 按扩展名判定语言：.yaml/.yml 走 YAML，其余按纯文本。 */
internal fun languageForName(name: String?): EditorLanguage {
    val ext = name?.substringAfterLast('.', "")?.lowercase()
    return if (ext == "yaml" || ext == "yml") EditorLanguage.Yaml else EditorLanguage.PlainText
}

/**
 * 默认示例：一篇覆盖常见 YAML 语法场景的配置，兼作编辑器各能力的演示（标量类型 / 嵌套 / 流式 / 锚点合并 /
 * 多行串 / 国际化 / emoji / 长行）。尾部接一条超长单行（[longLineSample]，网格虚拟化）与几行普通内容。
 */
internal val SAMPLE_YAML = """
    # scripta — YAML 示例（覆盖常见语法场景）
    # 「打开」载入本地文件，或直接编辑；双指缩放、长按选词（弹操作条）、点光标弹粘贴气泡、拖动手柄看放大镜、底部符号条一键插入。
    name: scripta
    version: 0.1.0
    released: 2026-07-06
    stable: true
    maintainer: null                 # 空值：null / ~ / 留空 皆可
    homepage: ~

    # 标量类型
    scalars:
      int: 42
      negative: -17
      float: 3.14159
      scientific: 6.022e23
      hex: 0x1F
      octal: 0o17
      bools: [true, false, yes, no, on, off]   # 流式列表
      timestamp: 2026-07-06T21:00:00Z
      quoted: "含 : 冒号与 # 井号的字符串"
      single: '单引号里用 '' 转义单引号'
      plain: 直接量字符串不需要引号
      escaped: "制表\t 换行\n 转义序列"

    # 嵌套映射 + 块状列表 + 列表套映射
    editor:
      virtualized: true
      language: yaml
      features:
        - 自绘渲染
        - 视口虚拟化
        - 自管 IME(拼音 composing)
        - 软换行
        - 超长行网格化
        - 语法高亮(可插拔, 内置 YAML)
        - 撤销/重做
        - 查找/替换
      gestures: {tap: 落光标, long_press: 选词, pinch: 双指缩放, menu: 选区悬浮条}
      themes:
        - {name: dark,  bg: "#1E1E1E", fg: "#E0E0E0"}
        - {name: light, bg: "#FFFFFF", fg: "#1E1E1E"}

    # 锚点 & 引用 & 合并
    defaults: &defaults
      timeout: 30
      retries: 3
    service_a:
      <<: *defaults
      name: alpha
    service_b:
      <<: *defaults
      name: beta
      retries: 5                     # 覆盖默认值

    # 多行字符串
    literal_block: |
      第一行保留换行
      第二行
        这一层缩进也会保留
    folded_block: >
      这几行会被折叠
      成一行(换行变空格)
      最终是一段文本。

    # 常用符号（底部符号条一键插入）
    symbols: '{ } [ ] ( ) < > : = + - * / \ | & # _'

    # 国际化 / emoji / 长行
    i18n:
      中文: 输入测试(拼音 composing)
      日本語: テスト入力
      한국어: 입력 테스트
      emoji: "🇭🇰 🇯🇵 🇺🇸 😀 👨‍👩‍👧"
      long_line: 这是一行非常非常非常长的文本用来测试横向滚动与自动换行 aaaaaaaa bbbbbbbb cccccccc dddddddd eeeeeeee ffffffff gggggggg hhhhhhhh iiiiiiii jjjjjjjj
""".trimIndent() + "\n" + longLineSample() + "\ntail:\n  a: 1\n  b: 2\n  c: 3\n"

/** 超长行（网格虚拟化）测试用的一条 ~3500 字符单行，纯占位、不含真实数据（模拟压缩/生成代码的单行）。
 *  尾部再接几行普通内容（见上），让网格行位于文档中部，便于交互测试（点按/选词/光标随动）。 */
internal fun longLineSample(): String {
    val body = (0 until 250).joinToString(" ") { "tok$it=val$it" }
    return "minified_one_liner: \"$body\""
}
