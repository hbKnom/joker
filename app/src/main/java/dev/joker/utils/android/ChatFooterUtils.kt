package dev.joker.utils.android

import android.content.Context
import android.util.AttributeSet
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.joker.reflekt.reflected.ReflectedConstructor
import dev.joker.reflekt.reflekt
import dev.joker.utils.reflection.int
import kotlin.reflect.KClass

val KClass<ChatFooter>.constructor: ReflectedConstructor<ChatFooter>
    get() {
        return reflekt().firstConstructor {
            parameters(Context::class, AttributeSet::class, int)
        }
    }
