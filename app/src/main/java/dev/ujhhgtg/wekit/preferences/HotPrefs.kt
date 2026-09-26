package dev.ujhhgtg.wekit.preferences

import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 热路径专用的偏好读取缓存。
 *
 * 为什么需要它：[WePrefs] 的存储是 SQLite（09-19 起由 MMKV 迁移而来），
 * **每一次 `getBoolean` / `getString` 都是一次真正的 `db.query`**（建语句、开游标、加全局锁）。
 * 而聊天气泡颜色、消息时间格式、头像隐藏开关这类偏好项是在
 * **每一条消息 bind / 每一帧触摸** 上读取的 —— 一个 300+ bind/秒的消息列表
 * 会因此产生上千次主线程 SQLite 查询，直接变成可见的掉帧。
 *
 * 语义约定（重要）：
 *  - 读：命中缓存（[TTL_MILLIS] 内）直接返回，否则落回真实偏好存储并刷新缓存；
 *  - 写：**本进程内的写入会立即失效该 key**，所以「写完马上读」永远是刚写入的值；
 *  - 唯一的行为差异：**跨进程**写入（例如另一个微信进程改了同一个 key）最多延迟
 *    [TTL_MILLIS] 才可见。对这里适用的都是渲染/交互类偏好，1 秒的收敛窗口不可感知。
 *
 * 使用范围：只给「每 bind / 每帧 / 每次触摸」的读取用。冷路径（设置对话框、初始化、
 * 启动时加载状态）请继续直接用 [WePrefs]，不要顺手替换。
 */
object HotPrefs {

    /** 缓存有效期：跨进程写入的最坏可见延迟。 */
    private const val TTL_MILLIS = 1000L

    /** 极端兜底：key 数量本该是个位数，超过就整体清一次，避免缓存无限膨胀。 */
    private const val MAX_ENTRIES = 256

    private class Entry(@JvmField val value: Any, @JvmField val expiresAtMillis: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun bool(key: String, defaultValue: Boolean): Boolean {
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now < it.expiresAtMillis) return it.value as? Boolean ?: defaultValue }

        val value = try {
            WePrefs.getBoolOrDef(key, defaultValue)
        } catch (_: Throwable) {
            defaultValue
        }
        put(key, value, now)
        return value
    }

    fun int(key: String, defaultValue: Int): Int {
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now < it.expiresAtMillis) return it.value as? Int ?: defaultValue }

        val value = try {
            WePrefs.getIntOrDef(key, defaultValue)
        } catch (_: Throwable) {
            defaultValue
        }
        put(key, value, now)
        return value
    }

    fun string(key: String, defaultValue: String): String {
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now < it.expiresAtMillis) return it.value as? String ?: defaultValue }

        val value = try {
            WePrefs.getStringOrDef(key, defaultValue)
        } catch (_: Throwable) {
            defaultValue
        }
        put(key, value, now)
        return value
    }

    /** 本进程写入了 [key] 时调用，保证紧随其后的读拿到的就是新值。 */
    fun invalidate(key: String) {
        cache.remove(key)
    }

    fun invalidateAll() {
        cache.clear()
    }

    private fun put(key: String, value: Any, now: Long) {
        if (cache.size >= MAX_ENTRIES && !cache.containsKey(key)) cache.clear()
        cache[key] = Entry(value, now + TTL_MILLIS)
    }
}

/**
 * [WePrefs.prefOption] 的热路径版本：读走 [HotPrefs] 缓存，写仍然直接落库并顺手失效缓存。
 * 只用在每 bind / 每帧都会读的属性上；其余属性保持 `prefOption` 即可。
 */
fun hotPrefOption(key: String, defValue: Boolean): ReadWriteProperty<Any?, Boolean> =
    object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = HotPrefs.bool(key, defValue)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            WePrefs.putBool(key, value)
            HotPrefs.invalidate(key)
        }
    }

fun hotPrefOption(key: String, defValue: Int): ReadWriteProperty<Any?, Int> =
    object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = HotPrefs.int(key, defValue)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            WePrefs.putInt(key, value)
            HotPrefs.invalidate(key)
        }
    }

fun hotPrefOption(key: String, defValue: String): ReadWriteProperty<Any?, String> =
    object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = HotPrefs.string(key, defValue)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            WePrefs.putString(key, value)
            HotPrefs.invalidate(key)
        }
    }
