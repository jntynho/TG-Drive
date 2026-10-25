package app.tgdrive.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Direct finger tracking, animated focal-point double tap, and bounded inertial pan. */
@Composable internal fun GalleryZoomImage(image:Bitmap,modifier:Modifier,onTap:()->Unit,onZoom:(Boolean)->Unit) {
    var scale by remember(image){mutableFloatStateOf(1f)}
    var pan by remember(image){mutableStateOf(Offset.Zero)}
    var viewport by remember{mutableStateOf(IntSize.Zero)}
    val size=IntSize(image.width,image.height)
    val scope=rememberCoroutineScope()
    var motion by remember{mutableStateOf<Job?>(null)}
    val decay=rememberSplineBasedDecay<Offset>()
    val currentTap by rememberUpdatedState(onTap)
    val currentZoom by rememberUpdatedState(onZoom)
    LaunchedEffect(Unit){snapshotFlow{scale>1.01f}.collect{currentZoom(it)}}
    DisposableEffect(Unit){onDispose{currentZoom(false)}}
    LaunchedEffect(viewport){motion?.cancel();pan=boundImagePan(pan,scale,viewport,size)}
    Box(modifier.clipToBounds().onSizeChanged{viewport=it}
        .pointerInput(image,viewport){detectTapGestures(onTap={currentTap()},onDoubleTap={point->
            motion?.cancel()
            val fromScale=scale;val fromPan=pan
            val target=if(scale>1.05f)1f else 2.5f
            val center=Offset(viewport.width/2f,viewport.height/2f)
            val toPan=if(target==1f)Offset.Zero else boundImagePan((pan-(point-center))*(target/scale)+(point-center),target,viewport,size)
            motion=scope.launch{Animatable(0f).animateTo(1f,tween(240,easing=FastOutSlowInEasing)){
                scale=fromScale+(target-fromScale)*value
                pan=boundImagePan(fromPan+(toPan-fromPan)*value,scale,viewport,size)
            }}
        })}
        .pointerInput(image,viewport){awaitEachGesture {
            awaitFirstDown(requireUnconsumed=false)
            motion?.cancel()
            val tracker=VelocityTracker()
            var dragged=false
            var multi=false
            do {
                val event=awaitPointerEvent()
                val fingers=event.changes.count{it.pressed}
                if(fingers>1||scale>1.01f) {
                    val centroid=event.calculateCentroid(useCurrent=true)
                    if(centroid!=Offset.Unspecified) {
                        val delta=event.calculatePan();val old=scale
                        scale=(scale*event.calculateZoom()).coerceIn(1f,6f)
                        val center=Offset(viewport.width/2f,viewport.height/2f)
                        pan=boundImagePan((pan-(centroid-center))*(scale/old)+(centroid-center)+delta,scale,viewport,size)
                        if(fingers>1){multi=true;tracker.resetTracking()}else event.changes.firstOrNull()?.let{tracker.addPosition(it.uptimeMillis,centroid)}
                        if(delta.getDistance()>0.5f)dragged=true
                        // Preserve tap/double-tap up events; only consume actual transforms.
                        if(delta.getDistance()>0f||scale!=old)event.changes.forEach{it.consume()}
                    }
                }
            }while(event.changes.any{it.pressed})
            if(dragged&&!multi&&scale>1.01f) {
                val velocity=tracker.calculateVelocity()
                motion=scope.launch {
                    AnimationState(typeConverter=Offset.VectorConverter,initialValue=pan,initialVelocityVector=AnimationVector2D(velocity.x,velocity.y)).animateDecay(decay){
                        val bounded=boundImagePan(value,scale,viewport,size);pan=bounded
                        if(bounded!=value)cancelAnimation()
                    }
                }
            }
            if(scale<=1.01f){scale=1f;pan=Offset.Zero}
        }},contentAlignment=Alignment.Center) {
        Image(image.asImageBitmap(),"Image preview",Modifier.graphicsLayer{scaleX=scale;scaleY=scale;translationX=pan.x;translationY=pan.y}.then(Modifier.matchParentSize()),contentScale=androidx.compose.ui.layout.ContentScale.Fit)
    }
}
