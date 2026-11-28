package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.example.domain.EncoderCapabilities
import com.example.domain.LocalAppStrings
import com.example.domain.MediaInfo
import com.example.domain.ModeConfigState
import com.example.domain.ProcessingMode
import com.example.domain.SubtitleCue
import com.example.domain.SubtitleEmbedMode
import com.example.domain.SubtitleTrackInfo
import com.example.domain.TurkishStrings
import com.example.ui.screens.ModeConfigScreen
import com.example.ui.theme.FFLabTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h2400dp-xxhdpi")
class SubtitleStudioRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleMedia() = MediaInfo(
        uri = Uri.parse("content://example/video.mp4"),
        fileName = "sample.mp4",
        baseName = "sample",
        extension = "mp4",
        sizeBytes = 20_000_000L,
        durationSec = 120.0,
        videoCodec = "h264",
        width = 1920,
        height = 1080,
        pixFmt = "yuv420p",
        audioCodec = "aac",
        fps = 30.0,
        audioBitrateKbps = 128,
        audioChannels = 2,
        subtitleTracks = listOf(
            SubtitleTrackInfo(0, 2, "subrip", "tur"),
            SubtitleTrackInfo(1, 3, "ass", "eng")
        )
    )

    @Test
    fun openingEmbedSubtitlesEmptyCuesRendersAllSectionsWithoutCrashing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        val media = sampleMedia()
        val config = ModeConfigState(
            mode = ProcessingMode.EMBED_SUBTITLES,
            subtitleEmbedMode = SubtitleEmbedMode.HARDSUB,
            subtitleCues = emptyList()
        )

        composeRule.setContent {
            FFLabTheme {
                CompositionLocalProvider(LocalAppStrings provides TurkishStrings) {
                    ModeConfigScreen(
                        media = media,
                        config = config,
                        capabilities = EncoderCapabilities(probed = true, libassSupportsHardsub = true),
                        validationError = null,
                        viewModel = vm,
                        isActiveStage = true,
                        onStartClick = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("subtitle_video_stage").assertExists()
        composeRule.onNodeWithTag("infinite_subtitle_ribbon").assertExists()
        composeRule.onNodeWithTag("subtitle_embed_mode_hardsub").assertExists()
        composeRule.onNodeWithTag("subtitle_embed_mode_softsub").assertExists()
        composeRule.onNodeWithTag("subtitle_font_roboto").assertExists()
        composeRule.onNodeWithTag("subtitle_font_montserrat").assertExists()
        composeRule.onNodeWithTag("subtitle_font_pt_serif").assertExists()
        composeRule.onNodeWithTag("subtitle_size_medium").assertExists()
        composeRule.onNodeWithTag("start_encoding_button").assertExists()
    }

    @Test
    fun openingEmbedSubtitlesPopulatedCuesAndClickingCueWorks() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        val media = sampleMedia()
        val config = ModeConfigState(
            mode = ProcessingMode.EMBED_SUBTITLES,
            subtitleEmbedMode = SubtitleEmbedMode.HARDSUB,
            subtitleCues = listOf(
                SubtitleCue(1L, 0.0, 4.0, "Merhaba dünya! Ğğ Şş İı"),
                SubtitleCue(2L, 5.0, 9.5, "İkinci satır testi")
            )
        )

        composeRule.setContent {
            FFLabTheme {
                CompositionLocalProvider(LocalAppStrings provides TurkishStrings) {
                    ModeConfigScreen(
                        media = media,
                        config = config,
                        capabilities = EncoderCapabilities(probed = true, libassSupportsHardsub = true),
                        validationError = null,
                        viewModel = vm,
                        isActiveStage = true,
                        onStartClick = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("subtitle_ribbon_cue_1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("subtitle_inline_editor").assertExists()
    }

    @Test
    fun exoPlayerBuilderAndPrepareSucceedsDirectly() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val player = ExoPlayer.Builder(app).build()
        try {
            player.setMediaItem(MediaItem.fromUri(Uri.parse("content://example/video.mp4")))
            player.prepare()
            player.playWhenReady = false
            val tv = android.view.TextureView(app)
            player.setVideoTextureView(tv)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            player.clearVideoTextureView(tv)
        } finally {
            player.release()
        }
    }

    @Test
    fun openingEmbedSubtitlesWithLiveExoPlayerBranchRendersWithoutCrashing() {
        val avcInfo = org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder()
            .setName("c2.qti.avc.decoder")
            .setIsEncoder(false)
            .setIsHardwareAccelerated(true)
            .setCapabilities(
                org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(android.media.MediaFormat.createVideoFormat("video/avc", 1920, 1080))
                    .setIsEncoder(false)
                    .setColorFormats(intArrayOf(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
                    .build()
            )
            .build()
        val aacInfo = org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder()
            .setName("c2.android.aac.decoder")
            .setIsEncoder(false)
            .setCapabilities(
                org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(android.media.MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2))
                    .setIsEncoder(false)
                    .build()
            )
            .build()
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(avcInfo)
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(aacInfo)

        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        val media = sampleMedia()
        val config = ModeConfigState(
            mode = ProcessingMode.EMBED_SUBTITLES,
            subtitleEmbedMode = SubtitleEmbedMode.HARDSUB,
            subtitleCues = emptyList()
        )

        composeRule.setContent {
            FFLabTheme {
                CompositionLocalProvider(LocalAppStrings provides TurkishStrings) {
                    ModeConfigScreen(
                        media = media,
                        config = config,
                        capabilities = EncoderCapabilities(probed = true, libassSupportsHardsub = true),
                        validationError = null,
                        viewModel = vm,
                        isActiveStage = true,
                        onStartClick = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Advance past the 920ms vertical push transition settle so ExoPlayer + TextureView mount live
        composeRule.mainClock.advanceTimeBy(1_050L)
        composeRule.waitForIdle()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        composeRule.onNodeWithTag("subtitle_video_stage").assertExists()
        composeRule.onNodeWithTag("subtitle_play_pause_button").assertExists()
        composeRule.onNodeWithTag("infinite_subtitle_ribbon").assertExists()
    }

    @Test
    fun earlyPlayTapDuringTransitionAndSoftsubModeDoNotCrash() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        val media = sampleMedia()
        val config = ModeConfigState(
            mode = ProcessingMode.EMBED_SUBTITLES,
            subtitleEmbedMode = SubtitleEmbedMode.SOFTSUB,
            subtitleCues = listOf(
                SubtitleCue(1L, 0.0, 3.0, "Test altyazı satırı")
            )
        )

        composeRule.setContent {
            FFLabTheme {
                CompositionLocalProvider(LocalAppStrings provides TurkishStrings) {
                    ModeConfigScreen(
                        media = media,
                        config = config,
                        capabilities = EncoderCapabilities(probed = true, libassSupportsHardsub = true),
                        validationError = null,
                        viewModel = vm,
                        isActiveStage = true,
                        onStartClick = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Tap Play immediately before the 920ms transition settle finishes
        composeRule.onNodeWithTag("subtitle_play_pause_button").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("subtitle_video_stage").assertExists()
    }
}

