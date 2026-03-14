// author: kodeholic (powered by Claude)
// SurfaceViewRenderer를 Compose에서 사용하기 위한 AndroidView 래퍼.
// WebRTC SurfaceViewRenderer는 별도 window surface에 렌더링하므로
// AndroidView 래핑 시 성능 오버헤드 없음.
package com.oxlens.demo.ui

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Compose 래퍼 — WebRTC SurfaceViewRenderer.
 *
 * @param eglContext 공유 EGL 컨텍스트 (ViewModel에서 관리)
 * @param videoTrack 바인딩할 비디오 트랙 (null이면 비표시)
 * @param mirror 전면 카메라용 미러링
 * @param scalingType 스케일링 (기본 FILL)
 */
@Composable
fun WebRtcSurface(
    eglContext: EglBase.Context,
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
) {
    // 트랙 바인딩 관리를 위해 이전 트랙 참조 유지
    val currentTrackRef = remember { mutableListOf<VideoTrack?>() }

    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                init(eglContext, null)
                setScalingType(scalingType)
                setEnableHardwareScaler(true)
                setMirror(mirror)
            }
        },
        update = { renderer ->
            renderer.setMirror(mirror)

            // 이전 트랙과 다르면 교체
            val prevTrack = currentTrackRef.firstOrNull()
            if (prevTrack != videoTrack) {
                prevTrack?.removeSink(renderer)
                videoTrack?.addSink(renderer)
                if (currentTrackRef.isEmpty()) currentTrackRef.add(videoTrack)
                else currentTrackRef[0] = videoTrack
            }
        },
        onRelease = { renderer ->
            currentTrackRef.firstOrNull()?.removeSink(renderer)
            currentTrackRef.clear()
            renderer.release()
        },
        modifier = modifier,
    )
}
