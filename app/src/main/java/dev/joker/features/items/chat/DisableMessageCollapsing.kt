package dev.joker.features.items.chat

import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.features.core.SwitchFeature
import dev.joker.utils.reflection.bool

object DisableMessageCollapsing : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用消息折叠"
    override val nameRes = R.string.feature_disable_message_collapsing_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_disable_message_collapsing_description

    private val methodFoldMsg by dexMethod {
        matcher {
            usingStrings(".msgsource.sec_msg_node.clip-len")
            paramTypes(null, CharSequence::class.java, null, bool, null, null)
        }
    }

    override fun onEnable() {
        methodFoldMsg.hookBefore {
            result = null
        }
    }
}
