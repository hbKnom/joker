package dev.joker.ui.animation.predictiveback

import dev.joker.ui.utils.theme.PageTransitionAnimation
import top.yukonga.miuix.kmp.nav.transition.NavTransition

fun jokerNavTransition(animation: PageTransitionAnimation): NavTransition = when (animation) {
    PageTransitionAnimation.AOSP -> AospNavTransition
    PageTransitionAnimation.MIUIX -> top.yukonga.miuix.kmp.nav.transition.NavTransitions.MiuixDefault
}
