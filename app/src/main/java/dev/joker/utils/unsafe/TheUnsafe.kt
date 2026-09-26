@file:SuppressLint("DiscouragedPrivateApi")

package dev.joker.utils.unsafe

import android.annotation.SuppressLint
import dev.joker.reflekt.utils.makeAccessible
import sun.misc.Unsafe

val TheUnsafe by lazy {
    Unsafe::class.java
        .getDeclaredField("theUnsafe")
        .makeAccessible()
        .get(null) as Unsafe
}
