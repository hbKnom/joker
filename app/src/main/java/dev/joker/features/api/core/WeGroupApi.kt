package dev.joker.features.api.core

import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexConstructor
import dev.joker.features.api.net.WeNetSceneApi
import dev.joker.features.core.ApiFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.utils.WeLogger
import dev.joker.utils.reflection.BString
import dev.joker.utils.reflection.int

object WeGroupApi : ApiFeature(), IResolveDex {

    override val technicalId = "群聊管理服务"
    override val nameRes = R.string.feature_we_group_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_group_api_description

    private const val TAG = "WeGroupApi"

    // ul.m(String chatRoomName, List<String> members, String sceneNote, Object historyInfo)
    private val ctorAddMember by dexConstructor {
        matcher {
            usingEqStrings("MicroMsg.NetSceneAddChatRoomMember")
            paramCount(4)
            paramTypes(
                BString,
                List::class.java,
                BString,
                Any::class.java
            )
        }
    }

    // ul.p(String chatRoomName, List<String> members, int scene)
    private val ctorDelMember by dexConstructor {
        matcher {
            usingEqStrings("/cgi-bin/micromsg-bin/delchatroommember")
            paramCount(3)
            paramTypes(
                BString,
                List::class.java,
                int
            )
        }
    }

    // ul.x(String chatRoomName, List<String> members, int scene, Object historyInfo)
    private val ctorInviteMember by dexConstructor {
        matcher {
            usingEqStrings("MicroMsg.NetSceneInviteChatRoomMember")
            paramCount(4)
            paramTypes(
                BString,
                List::class.java,
                int,
                Any::class.java
            )
        }
    }

    fun addMember(groupId: String, memberWxId: String) {
        addMembers(groupId, listOf(memberWxId))
    }

    fun addMembers(groupId: String, memberWxIds: List<String>) {
        try {
            val netScene = ctorAddMember.newInstance(groupId, memberWxIds, null, null)
            WeNetSceneApi.sendNetScene(netScene)
            WeLogger.i(TAG, "addMembers sent: group=$groupId count=${memberWxIds.size}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "addMembers failed for $groupId", e)
        }
    }

    fun delMember(groupId: String, memberWxId: String) {
        delMembers(groupId, listOf(memberWxId))
    }

    fun delMembers(groupId: String, memberWxIds: List<String>) {
        try {
            val netScene = ctorDelMember.newInstance(groupId, memberWxIds, 0)
            WeNetSceneApi.sendNetScene(netScene)
            WeLogger.i(TAG, "delMembers sent: group=$groupId count=${memberWxIds.size}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "delMembers failed for $groupId", e)
        }
    }

    fun inviteMember(groupId: String, memberWxId: String) {
        inviteMembers(groupId, listOf(memberWxId))
    }

    fun inviteMembers(groupId: String, memberWxIds: List<String>) {
        try {
            val netScene = ctorInviteMember.newInstance(groupId, memberWxIds, 0, null)
            WeNetSceneApi.sendNetScene(netScene)
            WeLogger.i(TAG, "inviteMembers sent: group=$groupId count=${memberWxIds.size}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "inviteMembers failed for $groupId", e)
        }
    }
}
