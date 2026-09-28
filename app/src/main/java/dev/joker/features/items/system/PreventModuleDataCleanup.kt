/*
 * PreventModuleDataCleanup.kt — 阻止清理模块数据 【第 29 轮 WeKit1945 整合】
 *
 * 证据等级：A-（逆向 jadx + 重建版）
 * 来源：
 *   - WeKit 1945 逆向包 `01_逆向源码/system/PreventModuleDataCleanup.kt`（混淆类 qt7）
 *   - `02_原始反编译/defpackage/PreventModuleDataCleanup.jadx.java`
 *   - `07_反编译产物dump/Lqt7.txt`（清理工具检测反汇编）
 *
 * 对应日志：「尝试修复: 阻止清理模块数据」
 *
 * ★ 行为：
 *   - 微信自身 / 其他清理工具在「清理应用数据」时会扫到 Joker 模块根目录；
 *   - 拦截这类 API（QQCleanMaster / WeChatBridge / 自家清理）让模块根目录不被擦除。
 *
 * ★ 第 29 轮阶段 1 骨架：仅占位 SwitchFeature。
 *   阶段 2 再加 DexMethodDelegate(FileDeleter.delete) + 排除 Joker 模块根目录。
 *
 * ★ 整合铁律：
 *   - 包名 dev.joker.*
 *   - 不引入额外依赖（我方 ModuleLoader / features/core 已有）
 *   - 默认关闭；阶段 2 真生效后再让用户开启
 */
package dev.joker.features.items.system

import dev.joker.R
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature

/**
 * 阻止清理模块数据（1945 新增）
 *
 * 拦截清理工具的"删除应用数据"行为，把 Joker 模块根目录加入排除列表。
 * 阶段 1 骨架：占位。
 */
object PreventModuleDataCleanup : SwitchFeature() {

    override val technicalId: String = "阻止清理模块数据"
    override val nameRes: Int = R.string.feature_system_prevent_module_data_cleanup_name
    override val categoryIds: List<String> = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes: Int = R.string.feature_system_prevent_module_data_cleanup_description

    /** 默认关闭 —— 等阶段 2 hook 真生效后再允许用户开启。 */
    override val defaultEnabled: Boolean = false

    override fun onEnable() {
        // 阶段 1 骨架：不挂 hook。
        // 阶段 2 实装：
        //   1. 监听 QQCleanMaster / WeChatBridge / 自家清理 API
        //   2. 排除模块根目录（WeLogger 模块数据目录）
        //   3. 阶段 1 上线了，阶段 2 加 FileDeleter.delete 反汇编 hook
    }

    override fun onDisable() {
        // 阶段 2 移除排除；阶段 1 无操作。
    }
}