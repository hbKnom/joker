package dev.joker.utils.reflection

import android.content.Context
import dev.joker.reflekt.utils.ReflectionClassLoader
import dev.joker.loader.utils.HybridClassLoader

object ClassLoaders {

    inline val HOST: ClassLoader get() = ReflectionClassLoader.value!!

    inline val MODULE: ClassLoader get() = ClassLoaders.javaClass.classLoader!!

    inline val BOOT: ClassLoader get() = Context::class.java.classLoader!!

    inline val HYBRID: ClassLoader get() = HybridClassLoader
}
