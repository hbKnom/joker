package dev.joker.features.items.system

import dev.joker.reflekt.reflekt
import dev.joker.reflekt.utils.Modifiers
import dev.joker.R
import dev.joker.BuildConfig
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import java.lang.reflect.Field

object PreventModuleDataDeletion : SwitchFeature(), IResolveDex {

    override val technicalId = "阻止微信清理模块数据"
    override val nameRes = R.string.feature_prevent_module_data_deletion_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_prevent_module_data_deletion_description

    private val methodNativeFileSystemEntryDelete by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("VFS.NativeFileSystem", "Base directory exists but is not a directory, delete and proceed.Base path: ")
            }

            paramTypes(String::class.java)
            returnType = "boolean"

            invokeMethods {
                add {
                    declaredClass = "java.io.File"
                    name = "delete"
                }
            }
        }
    }
    private lateinit var basePathField: Field

    override fun onEnable() {
        methodNativeFileSystemEntryDelete.hookBefore {
            val relPath = args[0] as String
            if (!::basePathField.isInitialized) {
                basePathField = thisObject!!.reflekt()
                    .firstField {
                        type = String::class
                        modifiers(Modifiers.FINAL)
                    }.self
            }
            val basePath = basePathField.get(thisObject) as String

            val path = "$basePath/$relPath"
            if (path.contains(BuildConfig.TAG) || path.contains("Layout Inspect")) {
                result = true
            }
        }
    }
}
