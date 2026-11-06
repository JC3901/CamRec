package com.jc.camrec.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.jc.camrec.net.Http

object Players {
    @OptIn(UnstableApi::class)
    fun live(context: Context, url: String, headers: Map<String, String>, params: Map<String, String>): ExoPlayer {
        val upstream = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(Http.userAgent)
            .setDefaultRequestProperties(headers)
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            if (params.isEmpty()) spec else spec.withUri(android.net.Uri.parse(Http.withParams(spec.uri.toString(), params)))
        }
        val source = HlsMediaSource.Factory(resolving)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(url))
        return ExoPlayer.Builder(context).build().apply {
            setMediaSource(source)
            playWhenReady = true
            prepare()
        }
    }

    fun file(context: Context, path: String, startMs: Long): ExoPlayer =
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path))))
            if (startMs > 0) seekTo(startMs)
            playWhenReady = true
            prepare()
        }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Landscape + hidden system bars while a video is fullscreen. */
fun setFullscreen(activity: Activity?, on: Boolean) {
    activity ?: return
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (on) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: ExoPlayer, modifier: Modifier, onFullscreen: (Boolean) -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                this.player = player
                keepScreenOn = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                setFullscreenButtonClickListener { full -> onFullscreen(full) }
            }
        },
        update = { it.player = player },
    )
}
