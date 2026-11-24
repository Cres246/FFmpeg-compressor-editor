package com.example.data

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.example.domain.DeviceCodecSnapshot
import java.util.Locale

/**
 * Queries and caches the current Android device's real [MediaCodecList] decoder/encoder
 * capabilities so [com.example.domain.Media3CompatibilityAdvisor] knows for certain whether
 * optional codecs (e.g. AC-3, E-AC-3, AV1, VP9, Dolby Vision, HEVC Main10 / HDR editing)
 * are available on this exact phone.
 */
object DeviceCodecInventory {

    @Volatile
    private var cachedSnapshot: DeviceCodecSnapshot? = null

    fun queryCurrentDevice(): DeviceCodecSnapshot {
        cachedSnapshot?.let { return it }
        val snapshot = runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val decoders = mutableSetOf<String>()
            val encoders = mutableSetOf<String>()
            var hevc10Encode = false
            var hevcHdrEdit = false

            for (info in codecList.codecInfos) {
                val types = info.supportedTypes ?: continue
                for (rawType in types) {
                    val mime = rawType.lowercase(Locale.US)
                    if (info.isEncoder) {
                        encoders.add(mime)
                        if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                            val isSw = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly) ||
                                info.name.startsWith("OMX.google.", ignoreCase = true) ||
                                info.name.startsWith("c2.android.", ignoreCase = true)
                            if (!isSw) {
                                runCatching {
                                    val caps = info.getCapabilitiesForType(rawType)
                                    val hasMain10 = caps?.profileLevels?.any {
                                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
                                    } == true
                                    if (hasMain10) hevc10Encode = true
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && caps != null) {
                                        val hdrFeat = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)
                                        val has10BitSurface = caps.colorFormats?.contains(
                                            MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010
                                        ) == true
                                        if (hasMain10 && hdrFeat && has10BitSurface) {
                                            hevcHdrEdit = true
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        decoders.add(mime)
                    }
                }
            }

            DeviceCodecSnapshot(
                decoderMimes = decoders,
                encoderMimes = encoders,
                supportsHevc10BitEncode = hevc10Encode,
                supportsHevcHdrEditing = hevcHdrEdit,
                sdkInt = Build.VERSION.SDK_INT,
                probed = decoders.isNotEmpty()
            )
        }.getOrElse {
            DeviceCodecSnapshot(sdkInt = Build.VERSION.SDK_INT, probed = false)
        }
        if (snapshot.probed) {
            cachedSnapshot = snapshot
        }
        return snapshot
    }
}
