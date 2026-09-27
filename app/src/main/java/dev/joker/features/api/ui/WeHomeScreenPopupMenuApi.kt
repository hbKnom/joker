package dev.joker.features.api.ui

import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.widget.BaseAdapter
import android.widget.ImageView
import androidx.collection.mutableIntObjectMapOf
import androidx.core.util.size
import dev.joker.reflekt.reflekt
import dev.joker.reflekt.utils.createInstance
import dev.joker.reflekt.utils.isSubclassOf
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.data
import dev.joker.dexkit.dsl.dexClass
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.core.ApiFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.utils.HookParam
import dev.joker.utils.WeLogger
import dev.joker.utils.android.runOnUiThread
import dev.joker.utils.hookBeforeDirectly
import dev.joker.utils.reflection.BString
import dev.joker.utils.reflection.bool
import dev.joker.utils.reflection.int
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

object WeHomeScreenPopupMenuApi : ApiFeature(), IResolveDex {

    override val technicalId = "首页菜单服务"
    override val nameRes = R.string.feature_we_home_screen_popup_menu_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_home_screen_popup_menu_api_description

    interface IMenuItemsProvider {
        fun getMenuItems(param: HookParam): List<MenuItem>
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val onClick: () -> Unit
    ) {
        val fakeResId get() = id + text.hashCode()
    }

    private val providers = CopyOnWriteArrayList<IMenuItemsProvider>()

    fun addProvider(provider: IMenuItemsProvider) {
        providers.addIfAbsent(provider)
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        providers.remove(provider)
    }

    private const val TAG = "WeHomeScreenPopupMenuApi"

    private val fakeResIdToResMap = mutableIntObjectMapOf<Drawable>()

    private val methodAddItem by dexMethod {
        searchPackages("com.tencent.mm.ui")
        matcher {
            usingEqStrings(
                "MicroMsg.PlusSubMenuHelper",
                "dyna plus config is null, we use default one"
            )
        }
    }
    private val methodHandleItemClick by dexMethod {
        searchPackages("com.tencent.mm.ui")
        matcher {
            usingEqStrings("MicroMsg.PlusSubMenuHelper", "processOnItemClick")
        }
    }
    private val classMenuItemData by dexClass {
        searchPackages("com.tencent.mm.ui")
        matcher {
            addFieldForType(BString)
            addFieldForType(int)
            addFieldForType(int)
            addFieldForType(int)
            addFieldForType(BString)
            fieldCount(5)
            methods {
                add {
                    usingEqStrings("")
                }
            }
        }
    }
    private val classMenuItemWrapper by dexClass {
        searchPackages("com.tencent.mm.ui")
        matcher {
            addFieldForType(bool)
            addFieldForType(classMenuItemData.data.name)
        }
    }

    // adapter 只有在菜单构建时才能拿到，所以 getView 的 Hook 没法在 onEnable 里注册；
    // 这里按 Method 去重，避免每打开一次菜单就往 getView 上再叠一层 Hook
    // (那会让每次 getView 都反复安装/卸载 N 个全局的 ImageView.setImageResource Hook)
    private val hookedGetViewMethods = ConcurrentHashMap.newKeySet<Method>()

    /** `ImageView.setImageResource` 的解析 Hook 是否已装（全局只装一次，幂等） */
    private val fakeResIdResolverInstalled = AtomicBoolean(false)

    /**
     * 「当前线程正在绑定首页菜单项」的作用域标志。
     *
     * 历史实现是「每次 getView 前装一个全局 `ImageView.setImageResource` Hook、getView 后卸载」：
     * 于是**每绑定一条菜单项**都要做一次反射查找 + 一次全局 Hook 安装 + 一次卸载，滚动菜单时
     * 肉眼可见地卡；更要命的是 `unhook` 是闭包里的单个变量，getView 一旦重入、或者抛异常导致
     * `hookAfter` 不执行，就会把 Hook 卸错对象 / 泄漏一层（越用越慢）。
     *
     * 现在改成：解析 Hook **全局只装一次**，作用域靠这个标志收窄 —— 效果与「只在菜单项 getView
     * 期间生效」完全一致（非菜单绑定时 `get()` 为 false 直接放行，绝不改写宿主真实图片），
     * 但每条 bind 只多一次 ThreadLocal 读 + 一次 map 命中判断。
     */
    private val bindingMenuItems = ThreadLocal.withInitial { false }

    /**
     * 全局只装一次：把菜单项里的假资源 id 换回我们的 Drawable。
     *
     * 只在 [bindingMenuItems] 为 true 且假 id 命中时才改写（`result = null` 吞掉宿主原本
     * 的 `setImageResource`），否则原样放行。
     */
    private fun installFakeResIdResolverOnce() {
        if (!fakeResIdResolverInstalled.compareAndSet(false, true)) return
        runCatching {
            ImageView::class.reflekt().firstMethod {
                name = "setImageResource"
            }.hookBeforeDirectly {
                if (bindingMenuItems.get() != true) return@hookBeforeDirectly
                val drawable = fakeResIdToResMap[args[0] as Int] ?: return@hookBeforeDirectly
                (thisObject as ImageView).setImageDrawable(drawable)
                result = null
            }
        }.onFailure {
            WeLogger.e(TAG, "failed to install fake res id resolver", it)
            fakeResIdResolverInstalled.set(false)
        }
    }

    private fun hookAdapterGetViewOnce(baseAdapter: BaseAdapter) {
        val getView = baseAdapter.reflekt().firstMethod {
            name = "getView"
        }
        if (!hookedGetViewMethods.add(getView.self)) return

        // 只用两个极轻的标记收窄作用域：装/卸全局 Hook 的重活已经移到 onEnable 一次性完成。
        // 若宿主 getView 抛异常导致 hookAfter 不执行，标志会残留 true —— 但那只会让该线程后续
        // 的 setImageResource 多一次 map 未命中判断（放行），不会再泄漏 Hook，也不再越用越慢。
        getView.hookBefore { bindingMenuItems.set(true) }
        getView.hookAfter { bindingMenuItems.set(false) }
    }

    override fun onEnable() {
        installFakeResIdResolverOnce()

        // WeChat 8.0.70 moved this to com.tencent.mm.ui.HomeUI
        methodAddItem.hookAfter {
            var thisObj = thisObject!!

            if (thisObj.javaClass.simpleName == "HomeUI") {
                thisObj = thisObj.reflekt()
                    .firstField { type = methodHandleItemClick.method.declaringClass }
                    .get()!!
            }

            @Suppress("UNCHECKED_CAST")
            val items = thisObj.reflekt()
                .firstField {
                    type = SparseArray::class
                }
                .get()!! as SparseArray<Any>
            val baseAdapter = thisObj.reflekt()
                .firstField {
                    type { it isSubclassOf BaseAdapter::class }
                }
                .get()!! as BaseAdapter

            hookAdapterGetViewOnce(baseAdapter)

            for (provider in providers) {
                try {
                    for (item in provider.getMenuItems(this)) {
                        fakeResIdToResMap[item.fakeResId] = item.drawable

                        val itemData = classMenuItemData.clazz.createInstance(
                            item.id,
                            item.text,
                            "",
                            item.fakeResId,
                            0
                        )
                        val itemWrapper =
                            classMenuItemWrapper.clazz.createInstance(itemData)
                        items.put(items.size, itemWrapper)

                        runOnUiThread {
                            baseAdapter.notifyDataSetChanged()
                        }
                    }
                } catch (ex: Exception) {
                    WeLogger.e(
                        TAG,
                        "provider ${provider.javaClass.name} threw while providing menu items",
                        ex
                    )
                }
            }

            runOnUiThread {
                baseAdapter.notifyDataSetChanged()
            }
        }

        methodHandleItemClick.hookBefore {
            val thisObj = thisObject!!

            @Suppress("UNCHECKED_CAST")
            val items = thisObj.reflekt()
                .firstField {
                    type = SparseArray::class
                }
                .get()!! as SparseArray<Any>
            val position = args[2] as Int
            val itemWrapper = items.get(position)
            val itemData = itemWrapper.reflekt()
                .firstField { type = classMenuItemData.clazz }.get()!!
            val id = itemData.reflekt()
                .fields { type = Int::class }[1].get()!! as Int

            for (provider in providers) {
                for (item in provider.getMenuItems(this)) {
                    if (item.id == id) {
                        try {
                            item.onClick()
                            return@hookBefore
                        } catch (ex: Exception) {
                            WeLogger.e(
                                TAG,
                                "provider ${provider.javaClass.name} threw while handling click event",
                                ex
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDisable() {
        // getView 与 setImageResource 的 Hook 已被 unhookAll 撤销，重新启用时需要允许再次注册
        hookedGetViewMethods.clear()
        fakeResIdResolverInstalled.set(false)
    }
}
