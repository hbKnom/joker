package dev.joker.features.items.contacts

import android.view.MenuItem
import androidx.activity.ComponentActivity
import dev.joker.R
import dev.joker.reflekt.reflekt
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.api.net.models.protobuf.NearbyFriendProto
import dev.joker.features.api.net.models.protobuf.WeProto
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.utils.reflection.int
import java.util.LinkedList

object AutoAddNearbyFriends : ClickableFeature(), IResolveDex {

    override val technicalId = "自动添加附近的人"
    override val nameRes = R.string.feature_auto_add_nearby_friends_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_auto_add_nearby_friends_description

    private val methodCreateMenu by dexMethod {
        matcher {
            usingEqStrings("NearbyPersonUIC", "showLiveBottomSheet create menu.")
        }
    }

    private val methodMenuOnClick by dexMethod {
        matcher {
            usingEqStrings("com.tencent.mm.plugin.nearby.ui.NearbySayHiListUI")
            name = "onMMMenuItemSelected"
        }
    }

    override fun onEnable() {
        methodCreateMenu.hookBefore {
            args[0]!!.reflekt().firstMethod {
                parameters(int, CharSequence::class)
            }.invoke(6, localizedContactsString(R.string.contacts_auto_add_nearby_menu))
        }

        methodMenuOnClick.hookBefore {
            val menuItem = args[0] as MenuItem
            val itemId = menuItem.itemId
            if (itemId != 6) return@hookBefore

            val controller = thisObject!!.reflekt().firstField().get()!!
            val friends = controller.reflekt().firstField {
                type = List::class
            }.get()!! as LinkedList<*>

            val friendProtos = friends.map {
                WeProto.decode<NearbyFriendProto>(
                    it.reflekt().invokeMethod("toByteArray", superclass = true) as ByteArray
                )
            }

            result = null
        }
    }

    override fun onClick(context: ComponentActivity) {

    }
}
