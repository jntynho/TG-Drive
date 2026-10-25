package app.tgdrive.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

/** Shared page motion. Back mirrors forward; system animator duration scale is respected. */
fun <T> AnimatedContentTransitionScope<T>.horizontalNavigation(forward: Boolean): ContentTransform {
    val direction = if (forward) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
    return (slideIntoContainer(direction, tween(280, easing = FastOutSlowInEasing)) togetherWith
        slideOutOfContainer(direction, tween(280, easing = FastOutSlowInEasing)))
        .using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(280) }))
}
