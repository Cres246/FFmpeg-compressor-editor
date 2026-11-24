package com.example.domain

import java.util.Locale

data class CustomCommandAdaptations(
    val strippedMarkdownOrProse: Boolean = false,
    val joinedMultiline: Boolean = false,
    val autoBoundInputsCount: Int = 0,
    val autoConfiguredTwoPassLogs: Boolean = false,
    val mappedWindowsNull: Boolean = false,
    val ignoredShellCleanupCount: Int = 0,
    val expandedVariablesCount: Int = 0,
    val autoAddedOutputFileName: String? = null,
    val fixedSyntaxOrTyposCount: Int = 0,
    val adaptedDesktopCodecCount: Int = 0
)

enum class CustomCommandTool {
    FFMPEG,
    FFPROBE
}

data class CustomCommandStep(
    val args: List<String>,
    val outputFileNames: List<String>,
    val isNullOutputOnly: Boolean,
    val expectedDurationSec: Double?,
    val passNumber: Int? = null,
    val tool: CustomCommandTool = CustomCommandTool.FFMPEG
)

data class CustomCommandPlan(
    val steps: List<CustomCommandStep>,
    val consumedIntermediateFiles: Set<String>,
    val adaptations: CustomCommandAdaptations
) {
    val primaryFinalOutputName: String?
        get() = steps
            .flatMap { it.outputFileNames }
            .lastOrNull { it !in consumedIntermediateFiles }
            ?: steps.flatMap { it.outputFileNames }.lastOrNull()

    val isProbeOnly: Boolean
        get() = steps.isNotEmpty() && steps.all { it.tool == CustomCommandTool.FFPROBE }

    val hasProbeStep: Boolean
        get() = steps.any { it.tool == CustomCommandTool.FFPROBE }
}

/**
 * Forgiving, AI-ready Custom FFmpeg Command parser and multi-step execution planner.
 *
 * Designed so users can copy-paste commands directly from ChatGPT, Gemini, Claude, Copilot,
 * DeepSeek, or any tutorial — or lightly edit them on a phone keyboard — without syntax failures:
 * - Strips Markdown code fences (``` / ~~~), inline backtick commands, copy-button chrome
 *   ("bash", "Copy code"), surrounding AI prose, numbered/bulleted prefixes, comments (#, //, REM, ::),
 *   and shell prompts ($ , > , PS> , user@host:~$ ).
 * - Normalizes invisible Unicode chars (zero-width spaces, BOM, NBSP), typographic quotes/dashes,
 *   double-dash typos (`--crf`), stray spaces (`- i`, `- c:v`, `-c: v`), uppercase flags (`-CRF`),
 *   `=value` flags (`-crf=23`), unclosed quotes, Turkish decimal commas (`10,5`), and bitrate units
 *   (`128 kbps` -> `128k`, `2.5 mbps` -> `2.5M`).
 * - Joins multi-line continuations (Unix `\`, Windows `^`, PowerShell `` ` ``, or bare indented flags)
 *   even across stray blank/comment lines, and handles Bash `for ...; do ffmpeg ...; done` loops.
 * - Splits multi-step / 2-pass / palettegen chains across `&&`, `;`, `&`, and newlines while
 *   preserving unquoted filtergraph `;` (`[a];[b]`) and `2>&1` redirections.
 * - Expands Bash, Batch, and PowerShell variables (`IN=...`, `set "V=..."`, `$V = ...`, `$VAR`,
 *   `${VAR}`, `%VAR%`) plus filename-stem placeholders (`${f%.*}`, `%~n1`, `$($_.BaseName)`).
 * - Automatically adapts desktop-only GPU encoders & flags (`h264_nvenc`, `hevc_videotoolbox`,
 *   `h264_qsv`, `h264_amf`, `h264_vaapi`, `libfdk_aac`, `-hwaccel cuda`, `-cq`, NVENC `p1..p7`,
 *   `scale_cuda=`, `hwupload_cuda`) to Android GPL FFmpegKit (`libx264`, `libx265`, `aac`, `libmp3lame`).
 * - Auto-binds missing or external `-i` inputs (including unquoted multi-word paths or omitted `-i`)
 *   to the selected file while preserving multi-step intermediates (`palette.png`), virtual inputs
 *   (`-f lavfi`), and network URIs; infers smart output extensions when omitted; maps Windows `NUL`
 *   to `/dev/null` with `-f null`; and redirects 2-pass logs into writable Android workDir.
 */
object CustomFfmpegParser {

    /** Known FFmpeg boolean (0-argument) flags so output filenames following them are never swallowed. */
    private val ZERO_ARG_FLAGS = setOf(
        "-y", "-n",
        "-an", "-vn", "-sn", "-dn",
        "-re", "-shortest",
        "-accurate_seek", "-noaccurate_seek",
        "-copyts", "-start_at_zero", "-copytb", "-copyinkf", "-copy_unknown",
        "-ignore_unknown", "-fix_sub_duration", "-ignore_editlist",
        "-hide_banner", "-nostdin", "-stats", "-nostats",
        "-benchmark", "-benchmark_all", "-report",
        "-autorotate", "-noautorotate",
        "-xerror", "-dump", "-hex",
        "-bitexact", "-sameq", "-same_quant",
        "-deinterlace", "-psnr", "-pass_1", "-pass_2",
        "-find_stream_info", "-nofind_stream_info",
        "-auto_conversion_filters", "-noauto_conversion_filters",
        "-recast_media"
    )

    /** Flags whose value MUST never be mistaken for an output file even at the end of a command. */
    private val NON_OUTPUT_VALUE_FLAGS = setOf(
        "-i", "-f", "-c", "-c:v", "-c:a", "-c:s", "-c:d",
        "-codec", "-codec:v", "-codec:a", "-codec:s",
        "-vcodec", "-acodec", "-scodec",
        "-vf", "-af", "-filter", "-filter:v", "-filter:a", "-filter_complex", "-lavfi", "-filter_complex_script",
        "-map", "-map_metadata", "-map_chapters", "-map_channel",
        "-pass", "-pass:v", "-passlogfile",
        "-x264-params", "-x265-params", "-svtav1-params", "-aom-params", "-x264opts",
        "-metadata", "-disposition",
        "-bsf", "-bsf:v", "-bsf:a", "-bsf:s",
        "-b", "-b:v", "-b:a", "-vb", "-ab", "-bt", "-maxrate", "-minrate", "-bufsize",
        "-crf", "-crf_max", "-qp", "-cq", "-qmin", "-qmax", "-qcomp", "-qdiff",
        "-preset", "-tune", "-profile", "-profile:v", "-profile:a", "-level", "-pix_fmt",
        "-r", "-s", "-aspect", "-ar", "-ac", "-channel_layout", "-sample_fmt",
        "-ss", "-to", "-t", "-sseof", "-itsoffset", "-timestamp",
        "-g", "-keyint_min", "-sc_threshold", "-bf", "-refs", "-threads", "-filter_threads",
        "-movflags", "-fflags", "-flags", "-flags2", "-err_detect",
        "-tag", "-tag:v", "-tag:a", "-vtag", "-atag",
        "-q", "-q:v", "-q:a", "-qscale", "-qscale:v", "-qscale:a",
        "-frames", "-frames:v", "-frames:a", "-vframes", "-aframes", "-dframes",
        "-loglevel", "-v", "-vsync", "-fps_mode", "-async", "-avoid_negative_ts",
        "-max_muxing_queue_size", "-max_interleave_delta", "-strict", "-f_strict",
        "-hwaccel", "-hwaccel_output_format", "-hwaccel_device", "-init_hw_device",
        "-filter_hw_device", "-vaapi_device", "-qsv_device",
        "-color_primaries", "-color_trc", "-colorspace", "-color_range",
        "-stream_loop", "-loop", "-framerate", "-sws_flags",
        "-abort_on", "-analyzeduration", "-probesize", "-thread_queue_size", "-rtbufsize",
        "-muxdelay", "-muxpreload", "-use_wallclock_as_timestamps",
        "-segment_time", "-segment_format", "-segment_list", "-reset_timestamps",
        "-hls_time", "-hls_list_size", "-hls_playlist_type", "-hls_segment_filename",
        "-compression_level", "-vbr", "-application", "-frame_duration", "-cutoff",
        "-rc", "-rc-lookahead", "-spatial-aq", "-temporal-aq", "-b_ref_mode", "-multipass",
        "-gpu", "-surfaces", "-global_quality", "-quality", "-usage", "-allow_sw", "-realtime", "-prio_speed",
        "-cpu-used", "-row-mt", "-tile-columns", "-tile-rows", "-lag-in-frames",
        "-auto-alt-ref", "-arnr-maxframes", "-arnr-strength", "-aq-mode", "-aq-strength",
        "-deadline", "-speed", "-lossless", "-method", "-final_delay", "-start_number", "-update",
        "-pre", "-vpre", "-apre", "-spre", "-me_method", "-subq", "-trellis", "-coder",
        "-nr", "-deblock", "-partitions", "-direct-pred", "-weightb", "-weightp", "-b_strategy"
    )

    /** Prefixes for FFmpeg stream-specifier flags (e.g. `-c:v:0`, `-metadata:s:a:0`, `-b:v:1`). */
    private val STREAM_SPECIFIER_FLAG_PREFIXES = listOf(
        "-c:", "-codec:", "-b:", "-q:", "-qscale:", "-filter:", "-bsf:", "-tag:",
        "-profile:", "-frames:", "-metadata:", "-disposition:", "-pass:", "-r:", "-ar:", "-ac:", "-t:", "-to:"
    )

    /** Common human / AI flag typos & friendly aliases mapped to canonical FFmpeg / FFprobe flags. */
    private val FLAG_TYPO_ALIASES = mapOf(
        "-v:c" to "-c:v",
        "-a:c" to "-c:a",
        "-s:c" to "-c:s",
        "-cv" to "-c:v",
        "-ca" to "-c:a",
        "-cs" to "-c:s",
        "-bv" to "-b:v",
        "-ba" to "-b:a",
        "-qv" to "-q:v",
        "-qa" to "-q:a",
        "-fps" to "-r",
        "-input" to "-i",
        "-pixfmt" to "-pix_fmt",
        "-pix-fmt" to "-pix_fmt",
        "-movflag" to "-movflags",
        "-filter_v" to "-filter:v",
        "-filter_a" to "-filter:a",
        "-filtercomplex" to "-filter_complex",
        "-presset" to "-preset",
        "-presest" to "-preset",
        "-passlog" to "-passlogfile",
        "-x265_params" to "-x265-params",
        "-x264_params" to "-x264-params",
        "-select-streams" to "-select_streams",
        "-show-entries" to "-show_entries",
        "-show-streams" to "-show_streams",
        "-show-format" to "-show_format",
        "-show-packets" to "-show_packets",
        "-show-frames" to "-show_frames",
        "-print-format" to "-print_format",
        "-output-format" to "-output_format",
        "-read-intervals" to "-read_intervals",
        "-count-frames" to "-count_frames",
        "-count-packets" to "-count_packets"
    )

    /** Known FFprobe 0-argument boolean flags so a trailing input file is never swallowed as a flag value. */
    private val FFPROBE_ZERO_ARG_FLAGS = setOf(
        "-show_format", "-show_streams", "-show_packets", "-show_frames",
        "-show_programs", "-show_chapters", "-show_error", "-show_data",
        "-show_private_data", "-noprivate", "-show_versions", "-show_program_version",
        "-show_library_versions", "-show_pixel_formats", "-count_frames", "-count_packets",
        "-pretty", "-unit", "-prefix", "-byte_binary_prefix", "-sexagesimal",
        "-bitexact", "-hide_banner", "-find_stream_info", "-nofind_stream_info",
        "-sections", "-report"
    )

    /** Known FFprobe 1-argument value flags so flag values are never mistaken for positional input files. */
    private val FFPROBE_VALUE_FLAGS = setOf(
        "-v", "-loglevel", "-f", "-i", "-o",
        "-select_streams", "-show_entries", "-of", "-print_format", "-output_format",
        "-read_intervals", "-show_format_entry", "-show_optional_fields",
        "-show_data_hash", "-show_log", "-analyzeduration", "-probesize", "-fpsprobesize",
        "-fflags", "-flags", "-err_detect", "-threads", "-max_probe_packets",
        "-rw_timeout", "-user_agent", "-headers", "-protocol_whitelist",
        "-c", "-c:v", "-c:a", "-vcodec", "-acodec", "-ss", "-t", "-to", "-export_side_data"
    )

    /** Unambiguously FFprobe-only flags used to recognize flags-only FFprobe pastes. */
    private val FFPROBE_ONLY_FLAGS = setOf(
        "-select_streams", "-show_entries", "-show_streams", "-show_format",
        "-show_packets", "-show_frames", "-show_programs", "-show_chapters",
        "-show_format_entry", "-print_format", "-of", "-output_format",
        "-read_intervals", "-count_frames", "-count_packets",
        "-pretty", "-sexagesimal", "-show_pixel_formats", "-show_private_data", "-noprivate"
    )

    /** Shell post-processing commands stripped when piped after an ffmpeg/ffprobe command (e.g. `| grep ...`). */
    private val SHELL_PIPE_FILTERS = setOf(
        "grep", "egrep", "fgrep", "rg", "awk", "gawk", "sed", "head", "tail",
        "jq", "cut", "tr", "wc", "sort", "uniq", "tee", "less", "more", "cat", "findstr", "select-string"
    )

    /** Stream-specifier base flags where mobile keyboards often insert spaces around `:` (`-c: v`, `-b :a`). */
    private val STREAM_SPEC_BASE_FLAGS = setOf(
        "-c", "-codec", "-b", "-q", "-qscale", "-filter", "-bsf", "-tag",
        "-profile", "-frames", "-pass", "-metadata", "-disposition"
    )

    /** Flags whose values are filters that may be split across unquoted spaces (`scale=720:-2, fps=30`). */
    private val FILTER_FLAGS = setOf("-vf", "-af", "-filter", "-filter:v", "-filter:a", "-filter_complex", "-lavfi")

    /** Flags whose values are timestamps where Turkish decimal comma & stray spaces around `:` should be fixed. */
    private val TIMESTAMP_FLAGS = setOf("-ss", "-to", "-t", "-sseof", "-itsoffset")

    /** Flags whose values are numeric scalars where Turkish decimal comma (`23,5`, `29,97`) should be fixed. */
    private val DECIMAL_SCALAR_FLAGS = setOf("-r", "-framerate", "-crf", "-qp", "-cq", "-q", "-q:v", "-q:a")

    /** Flags whose values are bitrates where `128 kbps`, `2.5 mbps`, `2,5M` should be normalized. */
    private val BITRATE_FLAGS = setOf("-b", "-b:v", "-b:a", "-vb", "-ab", "-maxrate", "-minrate", "-bufsize")

    /** Flags whose values are video encoder names. */
    private val VIDEO_CODEC_FLAGS = setOf("-c:v", "-vcodec", "-codec:v")

    /** Flags whose values are audio encoder names. */
    private val AUDIO_CODEC_FLAGS = setOf("-c:a", "-acodec", "-codec:a")

    /** Desktop hardware encoders remapped to Android GPL FFmpegKit software encoders. */
    private val DESKTOP_GPU_H264_ENCODERS = setOf(
        "h264_nvenc", "nvenc_h264", "nvenc",
        "h264_videotoolbox", "h264_qsv", "h264_amf",
        "h264_vaapi", "h264_mf", "h264_v4l2m2m", "h264_omx", "h264_rkmpp"
    )
    private val DESKTOP_GPU_HEVC_ENCODERS = setOf(
        "hevc_nvenc", "nvenc_hevc",
        "hevc_videotoolbox", "hevc_qsv", "hevc_amf",
        "hevc_vaapi", "hevc_mf", "hevc_v4l2m2m", "hevc_rkmpp"
    )

    /** NVENC preset names mapped to valid libx264/libx265 presets when adapting GPU commands. */
    private val NVENC_PRESET_MAP = mapOf(
        "p1" to "ultrafast",
        "p2" to "superfast",
        "p3" to "veryfast",
        "p4" to "medium",
        "p5" to "slow",
        "p6" to "slower",
        "p7" to "veryslow",
        "hp" to "fast",
        "ll" to "fast",
        "llhp" to "veryfast",
        "hq" to "slow",
        "llhq" to "medium",
        "bd" to "medium",
        "default" to "medium",
        "lossless" to "veryslow",
        "losslesshp" to "fast"
    )

    /** Desktop hardware-only flags stripped when adapting GPU commands to Android software codecs. */
    private val DESKTOP_HW_ONLY_VALUE_FLAGS = setOf(
        "-hwaccel_output_format", "-hwaccel_device", "-init_hw_device",
        "-filter_hw_device", "-vaapi_device", "-qsv_device"
    )
    private val DESKTOP_GPU_ENCODER_ONLY_FLAGS = setOf(
        "-rc", "-rc-lookahead", "-spatial-aq", "-temporal-aq",
        "-b_ref_mode", "-multipass", "-gpu", "-surfaces",
        "-allow_sw", "-realtime", "-prio_speed", "-usage"
    )
    private val DESKTOP_HWACCEL_VALUES = setOf(
        "cuda", "nvdec", "cuvid", "d3d11va", "dxva2", "qsv", "vaapi",
        "videotoolbox", "vdpau", "vulkan", "opencl", "drm", "amf", "auto"
    )

    /** Non-FFmpeg shell commands commonly included by AIs around FFmpeg snippets (e.g. cleanup/install lines). */
    private val IGNORED_SHELL_COMMANDS = setOf(
        "rm", "del", "erase", "rmdir", "rd",
        "mkdir", "md", "cd", "pushd", "popd",
        "echo", "printf", "cls", "clear", "pause",
        "exit", "true", "false", "sleep", "timeout",
        "ls", "dir", "mv", "move", "ren", "rename", "cp", "copy",
        "chmod", "chown", "touch", "cat", "type", "open", "start",
        "unset", "if", "then", "else", "elif", "fi", "for", "do", "done", "while", "read",
        "brew", "apt", "apt-get", "yum", "dnf", "pacman", "pkg", "choco", "winget", "scoop", "pip", "pip3", "npm",
        "winget.exe", "choco.exe", "setlocal", "endlocal", "@echo"
    )

    /** AI chat copy-button & Markdown language header lines that leak into plain-text copies. */
    private val AI_UI_CHROME_LINES = setOf(
        "bash", "sh", "shell", "zsh", "fish", "cmd", "bat", "batch", "dos",
        "powershell", "ps1", "pwsh", "terminal", "console", "plaintext", "text", "code",
        "copy", "copy code", "kopyala", "kodu kopyala", "copied", "kopyalandı"
    )

    /** Shell wrappers stripped from the start of a command segment. */
    private val SHELL_WRAPPERS = setOf("sudo", "time", "nohup", "call", "exec", "env", "do", "then", "else")

    private val MEDIA_OUTPUT_EXT_REGEX = Regex(".*\\.[a-zA-Z0-9]{2,5}$")
    private val VAR_ASSIGN_REGEX = Regex(
        "^(?:(?:export\\s+|set\\s+)?\\\$?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)|set\\s+\"([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)\")$",
        RegexOption.IGNORE_CASE
    )
    private val SHELL_PROMPT_REGEX = Regex(
        "^(?:(?:PS\\s+)?[A-Za-z]:\\\\[^>]*>|[A-Za-z0-9._-]+@[A-Za-z0-9._-]+:[^\\$#>]*[\\$#>]|PS>|\\\$|>)\\s+"
    )
    private val FFMPEG_BIN_REGEX = Regex("^(?:.*/|.*\\\\)?ffmpeg(?:\\.exe)?$", RegexOption.IGNORE_CASE)
    private val FFPROBE_BIN_REGEX = Regex("^(?:.*/|.*\\\\)?ffprobe(?:\\.exe)?$", RegexOption.IGNORE_CASE)
    private val RESERVED_INPUT_VARS = setOf("IN", "INPUT", "INPUT_FILE", "SRC", "SOURCE", "VIDEO", "FILE", "1")
    private val INLINE_ENV_VAR_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")
    private val STEM_PLACEHOLDER_REGEX = Regex(
        "\\\$\\{[A-Za-z0-9_]+%\\.\\*\\}|\\\$\\(\\\$_\\.BaseName\\)|\\\$_\\.BaseName|%~(?:dp)?n[A-Za-z0-9]",
        RegexOption.IGNORE_CASE
    )
    private val UNRESOLVED_OUTPUT_PLACEHOLDER_REGEX = Regex(
        "^(?:\\\$\\{?(?:OUT|OUTPUT|OUTPUT_FILE|DEST|DST|TARGET|RESULT|FINAL)\\}?|%(?:OUT|OUTPUT|OUTPUT_FILE|DEST|TARGET)%|<[^>]+>|\\{[^}]+\\}|\\[[^\\]]+\\])$",
        RegexOption.IGNORE_CASE
    )
    private val PROSE_PREFIX_BEFORE_FFMPEG_REGEX = Regex(
        "^(?:(?:[-•*▶►→]|\\d+[.)]|\\*\\*[^*]+\\*\\*|[A-Za-zÇĞİÖŞÜçğıöşü0-9 _()-]{1,28}:)\\s+)+(?:\\*\\*)?(?=(?:sudo\\s+|time\\s+|nohup\\s+|&\\s+)?(?:\\./|\\.\\\\|/|\\w:\\\\)?(?:ffmpeg|ffprobe)(?:\\.exe)?\\b)",
        RegexOption.IGNORE_CASE
    )
    private val BITRATE_UNIT_REGEX = Regex(
        "^(\\d+(?:[.,]\\d+)?)\\s*(kbps|kb/s|kbit/s|kb|k|mbps|mb/s|mbit/s|mb|m|bps)$",
        RegexOption.IGNORE_CASE
    )
    private val TURKISH_DECIMAL_REGEX = Regex("^(\\d+),(\\d+)$")
    private val TURKISH_TIMESTAMP_DECIMAL_REGEX = Regex("^(-?\\d{1,2}(?::\\d{1,2}){0,2}),(\\d+)$")

    /**
     * Builds a full multi-step execution plan for either live preview (`inputTokenProvider = { "\$IN" }`)
     * or real Android execution (`inputTokenProvider = { FfmpegEngine.resolveInputPath(...) }`).
     */
    fun buildPlan(
        rawCommand: String,
        media: MediaInfo,
        workDirPath: String,
        defaultOutputFileName: String,
        inputTokenProvider: () -> String,
        isPreview: Boolean = false
    ): CustomCommandPlan = buildPlan(
        rawCommand = rawCommand,
        mediaFileName = media.fileName,
        mediaExtension = media.extension,
        mediaDurationSec = media.durationSec,
        workDirPath = workDirPath,
        defaultOutputFileName = defaultOutputFileName,
        inputTokenProvider = inputTokenProvider,
        isPreview = isPreview,
        mediaBaseName = media.baseName,
        mediaAudioCodec = media.audioCodec
    )

    internal fun buildPlan(
        rawCommand: String,
        mediaFileName: String,
        mediaExtension: String,
        mediaDurationSec: Double?,
        workDirPath: String,
        defaultOutputFileName: String,
        inputTokenProvider: () -> String,
        isPreview: Boolean = false,
        mediaBaseName: String = mediaFileName.substringBeforeLast('.', mediaFileName),
        mediaAudioCodec: String? = null
    ): CustomCommandPlan {
        if (rawCommand.isBlank()) {
            return CustomCommandPlan(emptyList(), emptySet(), CustomCommandAdaptations())
        }

        val safeBaseName = mediaBaseName.ifBlank {
            mediaFileName.substringBeforeLast('.', "video").ifBlank { "video" }
        }
        val prep = preprocessAiPaste(rawCommand, safeBaseName)
        if (prep.commandSegments.isEmpty()) {
            return CustomCommandPlan(
                steps = emptyList(),
                consumedIntermediateFiles = emptySet(),
                adaptations = CustomCommandAdaptations(
                    strippedMarkdownOrProse = prep.strippedMarkdownOrProse,
                    joinedMultiline = prep.joinedMultiline,
                    ignoredShellCleanupCount = prep.ignoredShellCleanupCount,
                    expandedVariablesCount = prep.expandedVariablesCount,
                    fixedSyntaxOrTyposCount = prep.fixedSyntaxCount
                )
            )
        }

        val producedFiles = linkedSetOf<String>()
        val consumedIntermediates = linkedSetOf<String>()
        val resolvedSteps = mutableListOf<CustomCommandStep>()

        var autoBoundInputs = 0
        var autoConfiguredTwoPass = false
        var mappedWinNull = false
        var autoAddedOut: String? = null
        var fixedSyntaxCount = prep.fixedSyntaxCount
        var adaptedDesktopCodecCount = 0

        val inputAlias = if (mediaExtension.isNotBlank()) "input.$mediaExtension" else "input"

        for ((stepIndex, rawTokens) in prep.commandSegments.withIndex()) {
            val strippedTokens = stripShellRedirections(rawTokens).toMutableList()
            if (strippedTokens.isEmpty()) continue

            // Strip leading shell wrappers (`sudo`, `time`, `nohup`, `do`, `then`, `&`, `nice -n 10`, inline `KEY=VAL`)
            // and detect whether this segment invokes `ffmpeg` or `ffprobe`.
            val explicitTool = stripLeadingWrappersAndBinary(strippedTokens)
            if (strippedTokens.isEmpty() && explicitTool != CustomCommandTool.FFPROBE) continue

            val preliminaryIsProbe = explicitTool == CustomCommandTool.FFPROBE ||
                (explicitTool == null && strippedTokens.any {
                    val low = it.lowercase(Locale.US).removePrefix("-").substringBefore('=')
                    "-$low" in FFPROBE_ONLY_FLAGS || FLAG_TYPO_ALIASES["-$low"] in FFPROBE_ONLY_FLAGS
                })

            // Normalize spacing/casing/punctuation/alias typos on tokens before semantic planning
            val (cleanedTokens, tokenFixes) = normalizeStepTokens(strippedTokens, isProbe = preliminaryIsProbe)
            fixedSyntaxCount += tokenFixes
            if (cleanedTokens.isEmpty() && !preliminaryIsProbe) continue

            val stepTool = explicitTool ?: if (cleanedTokens.any { it.lowercase(Locale.US) in FFPROBE_ONLY_FLAGS }) {
                CustomCommandTool.FFPROBE
            } else {
                CustomCommandTool.FFMPEG
            }

            if (stepTool == CustomCommandTool.FFPROBE) {
                val (probeStep, probeBoundInputs, probeSyntaxFixes) = planFfprobeStep(
                    cleanedTokens = cleanedTokens,
                    mediaFileName = mediaFileName,
                    inputAlias = inputAlias,
                    workDirPath = workDirPath,
                    producedFiles = producedFiles,
                    inputTokenProvider = inputTokenProvider,
                    isPreview = isPreview
                )
                autoBoundInputs += probeBoundInputs
                fixedSyntaxCount += probeSyntaxFixes
                resolvedSteps.add(probeStep)
                continue
            }

            // Pre-scan flags & codecs for smart injection, null-sink, extension, and GPU adaptation decisions
            var sawAnyInputFlag = false
            var sawLavfiFormat = false
            var sawOverwriteFlag = false
            var sawPassLogFile = false
            var sawOutputFormatFlag = false
            var sawVnFlag = false
            var sawSingleFrameFlag = false
            var sawPaletteUse = false
            var explicitOutputFormat: String? = null
            var detectedVideoCodec: String? = null
            var detectedAudioCodec: String? = null
            var passNumber: Int? = null
            var adaptedFromDesktopGpu = false
            var adaptedFromFdkAac = false

            var scanIdx = 0
            while (scanIdx < cleanedTokens.size) {
                val t = cleanedTokens[scanIdx]
                val lower = t.lowercase(Locale.US)
                if (lower == "-y" || lower == "-n") sawOverwriteFlag = true
                if (lower == "-passlogfile") sawPassLogFile = true
                if (lower == "-i") sawAnyInputFlag = true
                if (lower == "-vn") sawVnFlag = true
                if ((lower == "-frames:v" || lower == "-vframes") && cleanedTokens.getOrNull(scanIdx + 1) == "1") {
                    sawSingleFrameFlag = true
                }
                if (lower in FILTER_FLAGS && cleanedTokens.getOrNull(scanIdx + 1)?.contains("paletteuse", ignoreCase = true) == true) {
                    sawPaletteUse = true
                }
                if ((lower == "-pass" || lower == "-pass:v") && scanIdx + 1 < cleanedTokens.size) {
                    passNumber = cleanedTokens[scanIdx + 1].toIntOrNull()
                }
                if (lower == "-f" && scanIdx + 1 < cleanedTokens.size) {
                    val fmtVal = cleanedTokens[scanIdx + 1]
                    val nextIsInput = cleanedTokens.getOrNull(scanIdx + 2).equals("-i", ignoreCase = true)
                    if (fmtVal.equals("lavfi", ignoreCase = true)) {
                        sawLavfiFormat = true
                    }
                    if (!nextIsInput) {
                        sawOutputFormatFlag = true
                        explicitOutputFormat = fmtVal.lowercase(Locale.US)
                    }
                }
                if (lower in VIDEO_CODEC_FLAGS && scanIdx + 1 < cleanedTokens.size) {
                    val vCodec = cleanedTokens[scanIdx + 1].lowercase(Locale.US).removePrefix("-")
                    detectedVideoCodec = vCodec
                    if (vCodec in DESKTOP_GPU_H264_ENCODERS || vCodec in DESKTOP_GPU_HEVC_ENCODERS) {
                        adaptedFromDesktopGpu = true
                    }
                }
                if (lower in AUDIO_CODEC_FLAGS && scanIdx + 1 < cleanedTokens.size) {
                    val aCodec = cleanedTokens[scanIdx + 1].lowercase(Locale.US).removePrefix("-")
                    detectedAudioCodec = aCodec
                    if (aCodec == "libfdk_aac" || aCodec == "fdk_aac") {
                        adaptedFromFdkAac = true
                    }
                }
                scanIdx++
            }

            val endsWithNullSink = cleanedTokens.lastOrNull()?.let { last ->
                last == "/dev/null" || isWindowsNullDevice(last) || last == "-"
            } == true

            val outArgs = mutableListOf<String>()
            val stepOutputs = mutableListOf<String>()
            var hasNonNullOutput = false
            var hasAnyOutput = false

            if (!sawOverwriteFlag) {
                outArgs.add("-y")
            }

            var i = 0

            // Handle omitted `-i`: either `ffmpeg input.mp4 -crf 23 out.mp4` OR flags-only `-crf 23`
            if (!sawAnyInputFlag && !sawLavfiFormat) {
                val leadingInputEndIdx = detectOmittedInputPrefixEnd(cleanedTokens)
                if (leadingInputEndIdx > 0) {
                    val omittedRawIn = cleanedTokens.subList(0, leadingInputEndIdx).joinToString(" ")
                    val inBaseName = extractSafeFileName(omittedRawIn)
                    outArgs.add("-i")
                    if (inBaseName != null && inBaseName in producedFiles) {
                        consumedIntermediates.add(inBaseName)
                        outArgs.add(formatWorkPath(workDirPath, inBaseName, isPreview))
                    } else {
                        outArgs.add(inputTokenProvider())
                        autoBoundInputs++
                    }
                    fixedSyntaxCount++
                    i = leadingInputEndIdx
                } else {
                    outArgs.add("-i")
                    outArgs.add(inputTokenProvider())
                    autoBoundInputs++
                }
            }

            // Track expected duration if user specified -ss / -to / -t
            var stepSsSec: Double? = null
            var stepToSec: Double? = null
            var stepTSec: Double? = null
            var pendingInputFormat: String? = null

            while (i < cleanedTokens.size) {
                val tok = cleanedTokens[i]
                val lower = tok.lowercase(Locale.US)

                // Drop stray standalone `-` in the middle of a command (never drop final stdout `-`)
                if (tok == "-" && i < cleanedTokens.lastIndex) {
                    fixedSyntaxCount++
                    i++
                    continue
                }

                // Handle `-i <input>` (tolerant of missing filename `-i -c:v ...`, empty `""`, or unquoted spaces)
                if (lower == "-i") {
                    val nextTok = cleanedTokens.getOrNull(i + 1)
                    if (nextTok == null || isKnownFlagToken(nextTok)) {
                        // User wrote `-i -c:v ...` or trailing `-i`: auto-bind selected media without eating next flag!
                        outArgs.add("-i")
                        outArgs.add(inputTokenProvider())
                        autoBoundInputs++
                        fixedSyntaxCount++
                        pendingInputFormat = null
                        i += 1
                        continue
                    }

                    // Merge accidental unquoted multi-word input filename (`-i my video.mp4 -crf 23 out.mp4`)
                    var endInputIdx = i + 1
                    val rawIn = if (nextTok.isBlank()) {
                        ""
                    } else if (!looksLikeCompleteInputPath(nextTok) && !isVirtualOrNetworkInput(nextTok) &&
                        !pendingInputFormat.equals("lavfi", ignoreCase = true)
                    ) {
                        val merged = StringBuilder(nextTok)
                        var look = i + 2
                        while (look < cleanedTokens.size) {
                            val cand = cleanedTokens[look]
                            if (cand.startsWith("-") || isWindowsNullDevice(cand) || cand == "/dev/null") break
                            // Stop before the final output token if this looks like `ffmpeg -i in out.mp4`
                            if (look == cleanedTokens.lastIndex && looksLikeOutputTarget(cand) &&
                                looksLikeOutputTarget(merged.toString())
                            ) {
                                break
                            }
                            merged.append(' ').append(cand)
                            endInputIdx = look
                            look++
                            if (looksLikeCompleteInputPath(cand)) break
                        }
                        if (endInputIdx > i + 1) fixedSyntaxCount++
                        merged.toString()
                    } else {
                        nextTok
                    }

                    val isSynthetic = pendingInputFormat.equals("lavfi", ignoreCase = true) ||
                        pendingInputFormat.equals("rawvideo", ignoreCase = true) ||
                        isVirtualOrNetworkInput(rawIn)

                    val inBaseName = extractSafeFileName(rawIn)
                    val isProducedIntermediate = !isSynthetic &&
                        inBaseName != null &&
                        inBaseName in producedFiles

                    outArgs.add("-i")
                    when {
                        isSynthetic -> {
                            outArgs.add(rawIn)
                        }
                        isProducedIntermediate -> {
                            consumedIntermediates.add(inBaseName)
                            outArgs.add(formatWorkPath(workDirPath, inBaseName, isPreview))
                        }
                        else -> {
                            val isExplicitPlaceholder = rawIn == "\$IN" ||
                                rawIn == "\${IN}" ||
                                rawIn == mediaFileName ||
                                rawIn.equals(inputAlias, ignoreCase = true)
                            if (!isExplicitPlaceholder) {
                                autoBoundInputs++
                            }
                            outArgs.add(inputTokenProvider())
                        }
                    }
                    pendingInputFormat = null
                    i = endInputIdx + 1
                    continue
                }

                // Strip desktop-only `-hwaccel <cuda|vaapi|videotoolbox|...>` and related HW device flags
                if (lower == "-hwaccel" && i + 1 < cleanedTokens.size) {
                    val hwVal = cleanedTokens[i + 1].lowercase(Locale.US)
                    if (hwVal in DESKTOP_HWACCEL_VALUES) {
                        adaptedDesktopCodecCount++
                        i += 2
                        continue
                    }
                }
                if (lower in DESKTOP_HW_ONLY_VALUE_FLAGS && i + 1 < cleanedTokens.size) {
                    adaptedDesktopCodecCount++
                    i += 2
                    continue
                }
                if (adaptedFromDesktopGpu && lower in DESKTOP_GPU_ENCODER_ONLY_FLAGS && i + 1 < cleanedTokens.size) {
                    adaptedDesktopCodecCount++
                    i += 2
                    continue
                }
                if (adaptedFromDesktopGpu && (lower == "-cq" || lower == "-global_quality") && i + 1 < cleanedTokens.size) {
                    outArgs.add("-crf")
                    outArgs.add(normalizeScalarValue(cleanedTokens[i + 1]))
                    adaptedDesktopCodecCount++
                    i += 2
                    continue
                }
                if (adaptedFromDesktopGpu && lower == "-preset" && i + 1 < cleanedTokens.size) {
                    val rawPreset = cleanedTokens[i + 1]
                    val mapped = NVENC_PRESET_MAP[rawPreset.lowercase(Locale.US)]
                    outArgs.add("-preset")
                    if (mapped != null) {
                        outArgs.add(mapped)
                        adaptedDesktopCodecCount++
                    } else {
                        outArgs.add(rawPreset)
                    }
                    i += 2
                    continue
                }

                // Adapt desktop/alias video codecs (`h264_nvenc`, `hevc_videotoolbox`, `x264`, `h265`, etc.)
                if (lower in VIDEO_CODEC_FLAGS && i + 1 < cleanedTokens.size) {
                    val rawCodec = cleanedTokens[i + 1]
                    val (mappedCodec, didAdaptCodec, didFixTypo) = mapVideoCodecForAndroid(rawCodec)
                    if (didAdaptCodec) adaptedDesktopCodecCount++
                    if (didFixTypo) fixedSyntaxCount++
                    detectedVideoCodec = mappedCodec.lowercase(Locale.US)
                    outArgs.add(lower)
                    outArgs.add(mappedCodec)
                    i += 2
                    continue
                }

                // Adapt unavailable/alias audio codecs (`libfdk_aac` -> `aac`, `mp3` -> `libmp3lame`, etc.)
                if (lower in AUDIO_CODEC_FLAGS && i + 1 < cleanedTokens.size) {
                    val rawCodec = cleanedTokens[i + 1]
                    val (mappedCodec, didAdaptCodec, didFixTypo) = mapAudioCodecForAndroid(rawCodec)
                    if (didAdaptCodec) adaptedDesktopCodecCount++
                    if (didFixTypo) fixedSyntaxCount++
                    detectedAudioCodec = mappedCodec.lowercase(Locale.US)
                    outArgs.add(lower)
                    outArgs.add(mappedCodec)
                    i += 2
                    continue
                }

                // Map `libfdk_aac` `-vbr 1..5` to native `aac` `-b:a <kbps>` so FFmpeg never rejects `-vbr`
                if (adaptedFromFdkAac && lower == "-vbr" && i + 1 < cleanedTokens.size) {
                    val level = cleanedTokens[i + 1].toIntOrNull()
                    val kbps = when (level) {
                        1 -> "64k"
                        2 -> "96k"
                        3 -> "128k"
                        4 -> "192k"
                        5 -> "256k"
                        else -> "160k"
                    }
                    outArgs.add("-b:a")
                    outArgs.add(kbps)
                    adaptedDesktopCodecCount++
                    i += 2
                    continue
                }

                // Handle filter flags (`-vf`, `-af`, `-filter_complex`, etc.):
                // merge unquoted space-split filter fragments (`scale=1280:-2, fps=30`) & adapt GPU filters
                if (lower in FILTER_FLAGS && i + 1 < cleanedTokens.size) {
                    val (mergedFilter, consumedCount, didMerge) = collectFilterArgument(cleanedTokens, i + 1)
                    if (didMerge) fixedSyntaxCount++
                    val (androidFilter, didAdaptFilter) = adaptFilterForAndroid(mergedFilter)
                    if (didAdaptFilter) adaptedDesktopCodecCount++
                    if (androidFilter.isNotBlank()) {
                        outArgs.add(lower)
                        outArgs.add(androidFilter)
                    }
                    i += 1 + consumedCount
                    continue
                }

                // Handle `-f <format>` (remember input format for immediately following `-i`;
                // on `/dev/null` 1st-pass sinks, rewrite seek-requiring container muxers like `-f mp4` to `-f null`)
                if (lower == "-f" && i + 1 < cleanedTokens.size) {
                    val fmt = cleanedTokens[i + 1]
                    val nextAfterFmtIsInput = cleanedTokens.getOrNull(i + 2).equals("-i", ignoreCase = true)
                    val rewriteToNullMuxer = endsWithNullSink && !nextAfterFmtIsInput &&
                        fmt.lowercase(Locale.US) in setOf("mp4", "mov", "mkv", "matroska", "webm", "avi", "flv", "mpegts")
                    pendingInputFormat = if (nextAfterFmtIsInput) fmt else null
                    outArgs.add("-f")
                    if (rewriteToNullMuxer) {
                        outArgs.add("null")
                        autoConfiguredTwoPass = true
                    } else {
                        outArgs.add(fmt)
                    }
                    i += 2
                    continue
                }

                // Skip `-movflags <val>` on `/dev/null` sink passes so FFmpeg never errors on non-seekable /dev/null
                if (lower == "-movflags" && endsWithNullSink && i + 1 < cleanedTokens.size) {
                    autoConfiguredTwoPass = true
                    i += 2
                    continue
                }

                // Handle `-pass <1|2>` and auto-inject writable `-passlogfile` if missing
                if ((lower == "-pass" || lower == "-pass:v") && i + 1 < cleanedTokens.size) {
                    val passVal = cleanedTokens[i + 1]
                    outArgs.add(lower)
                    outArgs.add(passVal)
                    if (!sawPassLogFile) {
                        outArgs.add("-passlogfile")
                        outArgs.add(formatWorkPath(workDirPath, "ffmpeg2pass", isPreview))
                        sawPassLogFile = true
                        autoConfiguredTwoPass = true
                    }
                    i += 2
                    continue
                }

                // Handle `-passlogfile <path>` -> always redirect into writable workDir
                if (lower == "-passlogfile" && i + 1 < cleanedTokens.size) {
                    val rawLog = cleanedTokens[i + 1]
                    val safeLogName = extractSafeFileName(rawLog) ?: "ffmpeg2pass"
                    val formattedLog = formatWorkPath(workDirPath, safeLogName, isPreview)
                    outArgs.add("-passlogfile")
                    outArgs.add(formattedLog)
                    if (rawLog != formattedLog) {
                        autoConfiguredTwoPass = true
                    }
                    i += 2
                    continue
                }

                // Handle `-x265-params <kv:kv>` -> guarantee writable `stats=` when 2-pass is used
                if (lower == "-x265-params" && i + 1 < cleanedTokens.size) {
                    val rawParams = cleanedTokens[i + 1]
                    val (fixedParams, didAdapt) = normalizeX265Params(rawParams, workDirPath, isPreview)
                    if (didAdapt) autoConfiguredTwoPass = true
                    if (passNumber == null) {
                        passNumber = extractX265PassNumber(fixedParams)
                    }
                    outArgs.add("-x265-params")
                    outArgs.add(fixedParams)
                    i += 2
                    continue
                }

                // Track trim/duration flags + normalize Turkish decimal commas and stray spaces around `:`
                if (lower in TIMESTAMP_FLAGS && i + 1 < cleanedTokens.size) {
                    val (tsVal, consumedTs, didFixTs) = collectTimestampValue(cleanedTokens, i + 1)
                    if (didFixTs) fixedSyntaxCount++
                    val sec = FfmpegMath.parseTimestampToSeconds(tsVal)
                    when (lower) {
                        "-ss" -> if (stepSsSec == null) stepSsSec = sec
                        "-to" -> stepToSec = sec
                        "-t" -> stepTSec = sec
                    }
                    outArgs.add(lower)
                    outArgs.add(tsVal)
                    i += 1 + consumedTs
                    continue
                }

                // Normalize bitrate values (`128 kbps`, `2,5 mbps`, `2000 k`)
                if (lower in BITRATE_FLAGS && i + 1 < cleanedTokens.size) {
                    val (brVal, consumedBr, didFixBr) = collectBitrateValue(cleanedTokens, i + 1)
                    if (didFixBr) fixedSyntaxCount++
                    outArgs.add(lower)
                    outArgs.add(brVal)
                    i += 1 + consumedBr
                    continue
                }

                // Normalize Turkish decimal comma on scalar numeric flags (`-crf 23,5`, `-r 29,97`)
                if (lower in DECIMAL_SCALAR_FLAGS && i + 1 < cleanedTokens.size) {
                    val rawVal = cleanedTokens[i + 1]
                    val fixedVal = normalizeScalarValue(rawVal)
                    if (fixedVal != rawVal) fixedSyntaxCount++
                    outArgs.add(lower)
                    outArgs.add(fixedVal)
                    i += 2
                    continue
                }

                // Known 0-argument boolean flag
                if (lower in ZERO_ARG_FLAGS) {
                    outArgs.add(lower)
                    i += 1
                    continue
                }

                // Any other `-option` (takes 1 argument unless next token is a known `-flag` or forced final output)
                if (tok.startsWith("-") && tok.length > 1) {
                    val canonicalFlag = if (isNonOutputValueFlag(lower)) lower else tok
                    val nextTok = cleanedTokens.getOrNull(i + 1)
                    val isKnownValueFlag = isNonOutputValueFlag(lower)
                    val nextIsOption = nextTok != null && when {
                        // Known value-taking flags accept negative values like `-map -0:a`, `-sseof -00:00:10`,
                        // `-movflags -faststart`, `-map_metadata -1` as long as `nextTok` is NOT another known CLI flag.
                        isKnownValueFlag -> isKnownFlagToken(nextTok)
                        else -> nextTok.startsWith("-") && nextTok.length > 1 && nextTok.toDoubleOrNull() == null
                    }
                    val nextIsForcedOutput = nextTok != null &&
                        i + 1 == cleanedTokens.lastIndex &&
                        !isKnownValueFlag &&
                        looksLikeOutputTarget(nextTok)

                    if (nextTok == null || nextIsOption || nextIsForcedOutput) {
                        // Drop dangling known value-flag at very end with missing value so FFmpeg doesn't error
                        if (nextTok == null && isKnownValueFlag && i == cleanedTokens.lastIndex) {
                            fixedSyntaxCount++
                        } else {
                            outArgs.add(canonicalFlag)
                        }
                        i += 1
                    } else {
                        outArgs.add(canonicalFlag)
                        outArgs.add(nextTok)
                        i += 2
                    }
                    continue
                }

                // Unconsumed non-flag token -> OUTPUT target!
                hasAnyOutput = true
                when {
                    isWindowsNullDevice(tok) || tok == "/dev/null" -> {
                        if (!sawOutputFormatFlag) {
                            // Ensure `-f null` is present before `/dev/null` so FFmpeg never fails with
                            // "Unable to find a suitable output format for '/dev/null'".
                            outArgs.add("-f")
                            outArgs.add("null")
                            sawOutputFormatFlag = true
                            autoConfiguredTwoPass = true
                        }
                        if (isWindowsNullDevice(tok)) {
                            mappedWinNull = true
                        }
                        outArgs.add("/dev/null")
                        i += 1
                    }
                    tok == "-" -> {
                        if (!sawOutputFormatFlag) {
                            outArgs.add("-f")
                            outArgs.add("null")
                            sawOutputFormatFlag = true
                            autoConfiguredTwoPass = true
                        }
                        outArgs.add(tok)
                        i += 1
                    }
                    else -> {
                        // Merge unquoted multi-word output filenames (e.g. `my final video.mp4`) when
                        // leading words lack extensions and a following word has a media extension.
                        val (rawOutToken, consumedOutTokens, mergedOut) = collectOutputToken(cleanedTokens, i)
                        if (mergedOut) fixedSyntaxCount++

                        val inferredExt = inferSmartOutputExtension(
                            sawVn = sawVnFlag,
                            sawSingleFrame = sawSingleFrameFlag,
                            sawPaletteUse = sawPaletteUse,
                            explicitOutputFormat = explicitOutputFormat,
                            videoCodec = detectedVideoCodec,
                            audioCodec = detectedAudioCodec,
                            sourceMediaExtension = mediaExtension,
                            sourceAudioCodec = mediaAudioCodec,
                            defaultOutputFileName = defaultOutputFileName
                        )

                        val (safeOutName, didFixOut) = resolveCleanOutputFileName(
                            rawToken = rawOutToken,
                            inferredExt = inferredExt,
                            defaultOutputFileName = defaultOutputFileName,
                            hasExplicitFormat = sawOutputFormatFlag
                        )
                        if (didFixOut) fixedSyntaxCount++

                        val fullOutPath = formatWorkPath(workDirPath, safeOutName, isPreview)
                        outArgs.add(fullOutPath)
                        stepOutputs.add(safeOutName)
                        producedFiles.add(safeOutName)
                        hasNonNullOutput = true
                        i += consumedOutTokens
                    }
                }
            }

            // If user pasted flags only (no output file at all), auto-append a smart output file
            if (!hasAnyOutput) {
                val inferredExt = inferSmartOutputExtension(
                    sawVn = sawVnFlag,
                    sawSingleFrame = sawSingleFrameFlag,
                    sawPaletteUse = sawPaletteUse,
                    explicitOutputFormat = explicitOutputFormat,
                    videoCodec = detectedVideoCodec,
                    audioCodec = detectedAudioCodec,
                    sourceMediaExtension = mediaExtension,
                    sourceAudioCodec = mediaAudioCodec,
                    defaultOutputFileName = defaultOutputFileName
                )
                val baseDefault = defaultOutputFileName.substringBeforeLast('.', defaultOutputFileName)
                    .ifBlank { "output" }
                val smartDefault = "$baseDefault.$inferredExt"
                val fallbackName = if (prep.commandSegments.size > 1 && stepIndex < prep.commandSegments.lastIndex) {
                    "step_${stepIndex + 1}_$smartDefault"
                } else {
                    smartDefault
                }
                outArgs.add(formatWorkPath(workDirPath, fallbackName, isPreview))
                stepOutputs.add(fallbackName)
                producedFiles.add(fallbackName)
                hasNonNullOutput = true
                autoAddedOut = fallbackName
            }

            val expectedDur = resolveStepExpectedDuration(mediaDurationSec, stepSsSec, stepToSec, stepTSec)

            resolvedSteps.add(
                CustomCommandStep(
                    args = outArgs,
                    outputFileNames = stepOutputs,
                    isNullOutputOnly = !hasNonNullOutput,
                    expectedDurationSec = expectedDur,
                    passNumber = passNumber
                )
            )
        }

        return CustomCommandPlan(
            steps = resolvedSteps,
            consumedIntermediateFiles = consumedIntermediates,
            adaptations = CustomCommandAdaptations(
                strippedMarkdownOrProse = prep.strippedMarkdownOrProse,
                joinedMultiline = prep.joinedMultiline,
                autoBoundInputsCount = autoBoundInputs,
                autoConfiguredTwoPassLogs = autoConfiguredTwoPass,
                mappedWindowsNull = mappedWinNull,
                ignoredShellCleanupCount = prep.ignoredShellCleanupCount,
                expandedVariablesCount = prep.expandedVariablesCount,
                autoAddedOutputFileName = autoAddedOut,
                fixedSyntaxOrTyposCount = fixedSyntaxCount,
                adaptedDesktopCodecCount = adaptedDesktopCodecCount
            )
        )
    }

    /**
     * Plans an `ffprobe` inspection step without injecting `-y` or a dummy output filename:
     * - Recognizes both positional input paths (`ffprobe ... video.mp4`) and `-i <file>`
     * - Merges accidental unquoted multi-word input filenames (`my holiday video.mp4`)
     * - Auto-binds missing inputs (`ffprobe -show_streams`) to the user's selected file
     * - Inspects outputs created by earlier `ffmpeg` steps in the same chain without marking them consumed
     * - Strips `-o <file>` so probe output always renders cleanly on screen
     */
    private fun planFfprobeStep(
        cleanedTokens: List<String>,
        mediaFileName: String,
        inputAlias: String,
        workDirPath: String,
        producedFiles: Set<String>,
        inputTokenProvider: () -> String,
        isPreview: Boolean
    ): Triple<CustomCommandStep, Int, Int> {
        val outArgs = mutableListOf<String>()
        var autoBoundInputs = 0
        var fixedSyntax = 0
        var hasBoundInput = false
        var pendingInputFormat: String? = null

        fun resolveProbeInputToken(rawIn: String): String {
            val isSynthetic = pendingInputFormat.equals("lavfi", ignoreCase = true) ||
                pendingInputFormat.equals("rawvideo", ignoreCase = true) ||
                isVirtualOrNetworkInput(rawIn)
            if (isSynthetic) return rawIn
            val inBaseName = extractSafeFileName(rawIn)
            if (inBaseName != null && inBaseName in producedFiles) {
                return formatWorkPath(workDirPath, inBaseName, isPreview)
            }
            val isExplicitPlaceholder = rawIn == "\$IN" ||
                rawIn == "\${IN}" ||
                rawIn == mediaFileName ||
                rawIn.equals(inputAlias, ignoreCase = true)
            if (!isExplicitPlaceholder) {
                autoBoundInputs++
            }
            return inputTokenProvider()
        }

        var i = 0
        while (i < cleanedTokens.size) {
            val tok = cleanedTokens[i]
            val lower = tok.lowercase(Locale.US)

            // Drop stray standalone `-` in the middle of a probe command
            if (tok == "-") {
                fixedSyntax++
                i++
                continue
            }

            // Skip `-o <file>` so FFprobe output prints to stdout and displays directly in the UI
            if (lower == "-o") {
                fixedSyntax++
                i += if (i + 1 < cleanedTokens.size && !isKnownFlagToken(cleanedTokens[i + 1])) 2 else 1
                continue
            }

            // Handle explicit `-i <input>` (tolerant of missing filename or unquoted spaces)
            if (lower == "-i") {
                val nextTok = cleanedTokens.getOrNull(i + 1)
                if (nextTok == null || isKnownFlagToken(nextTok)) {
                    outArgs.add("-i")
                    outArgs.add(inputTokenProvider())
                    autoBoundInputs++
                    fixedSyntax++
                    hasBoundInput = true
                    pendingInputFormat = null
                    i += 1
                    continue
                }
                var endIdx = i + 1
                val rawIn = if (!looksLikeCompleteInputPath(nextTok) && !isVirtualOrNetworkInput(nextTok) &&
                    !pendingInputFormat.equals("lavfi", ignoreCase = true)
                ) {
                    val merged = StringBuilder(nextTok)
                    var look = i + 2
                    while (look < cleanedTokens.size && !cleanedTokens[look].startsWith("-")) {
                        merged.append(' ').append(cleanedTokens[look])
                        endIdx = look
                        look++
                        if (looksLikeCompleteInputPath(cleanedTokens[endIdx])) break
                    }
                    if (endIdx > i + 1) fixedSyntax++
                    merged.toString()
                } else {
                    nextTok
                }
                outArgs.add("-i")
                outArgs.add(resolveProbeInputToken(rawIn))
                hasBoundInput = true
                pendingInputFormat = null
                i = endIdx + 1
                continue
            }

            // Track `-f <format>` for lavfi/synthetic input detection
            if (lower == "-f" && i + 1 < cleanedTokens.size) {
                val fmt = cleanedTokens[i + 1]
                pendingInputFormat = fmt
                outArgs.add("-f")
                outArgs.add(fmt)
                i += 2
                continue
            }

            // Known 0-argument FFprobe boolean flags (never swallow trailing input filename!)
            if (lower in FFPROBE_ZERO_ARG_FLAGS || lower in ZERO_ARG_FLAGS) {
                outArgs.add(lower)
                i += 1
                continue
            }

            // Known 1-argument FFprobe value flags (never mistake value like `error`, `v:0`, `default=...` for input!)
            if (lower in FFPROBE_VALUE_FLAGS || isNonOutputValueFlag(lower)) {
                val nextTok = cleanedTokens.getOrNull(i + 1)
                if (nextTok == null || isKnownFlagToken(nextTok)) {
                    if (nextTok == null && i == cleanedTokens.lastIndex) {
                        fixedSyntax++
                    } else {
                        outArgs.add(lower)
                    }
                    i += 1
                } else {
                    outArgs.add(lower)
                    outArgs.add(nextTok)
                    i += 2
                }
                continue
            }

            // Any other unknown `-flag`
            if (tok.startsWith("-") && tok.length > 1) {
                val nextTok = cleanedTokens.getOrNull(i + 1)
                val nextIsOption = nextTok != null && nextTok.startsWith("-") && nextTok.length > 1 && nextTok.toDoubleOrNull() == null
                val nextIsTrailingInput = nextTok != null && i + 1 == cleanedTokens.lastIndex &&
                    (looksLikeCompleteInputPath(nextTok) || looksLikeInputPlaceholder(nextTok))
                if (nextTok == null || nextIsOption || nextIsTrailingInput) {
                    outArgs.add(tok)
                    i += 1
                } else {
                    outArgs.add(tok)
                    outArgs.add(nextTok)
                    i += 2
                }
                continue
            }

            // Positional non-flag token -> FFprobe input target (e.g. `video.mp4`, `my clip.mkv`, `$IN`)
            var endIdx = i
            val rawIn = if (!looksLikeCompleteInputPath(tok) && !isVirtualOrNetworkInput(tok) &&
                !looksLikeInputPlaceholder(tok)
            ) {
                val merged = StringBuilder(tok)
                var look = i + 1
                while (look < cleanedTokens.size && !cleanedTokens[look].startsWith("-")) {
                    merged.append(' ').append(cleanedTokens[look])
                    endIdx = look
                    look++
                    if (looksLikeCompleteInputPath(cleanedTokens[endIdx])) break
                }
                if (endIdx > i) fixedSyntax++
                merged.toString()
            } else {
                tok
            }

            if (!hasBoundInput) {
                outArgs.add(resolveProbeInputToken(rawIn))
                hasBoundInput = true
                pendingInputFormat = null
            } else {
                // FFprobe only accepts one input; drop accidental duplicate trailing tokens gracefully
                fixedSyntax++
            }
            i = endIdx + 1
        }

        if (!hasBoundInput) {
            outArgs.add(inputTokenProvider())
            autoBoundInputs++
        }

        val step = CustomCommandStep(
            args = outArgs,
            outputFileNames = emptyList(),
            isNullOutputOnly = true,
            expectedDurationSec = null,
            passNumber = null,
            tool = CustomCommandTool.FFPROBE
        )
        return Triple(step, autoBoundInputs, fixedSyntax)
    }

    fun formatPreview(plan: CustomCommandPlan): String {
        if (plan.steps.isEmpty()) return ""
        return plan.steps.joinToString("\n&& ") { step ->
            val binName = if (step.tool == CustomCommandTool.FFPROBE) "ffprobe" else "ffmpeg"
            "$binName " + step.args.joinToString(" ") { arg ->
                if (arg.any { it.isWhitespace() } || arg.contains(';') || arg.contains('&')) {
                    "\"$arg\""
                } else {
                    arg
                }
            }
        }
    }

    private data class PreprocessedResult(
        val commandSegments: List<List<String>>,
        val strippedMarkdownOrProse: Boolean,
        val joinedMultiline: Boolean,
        val ignoredShellCleanupCount: Int,
        val expandedVariablesCount: Int,
        val fixedSyntaxCount: Int
    )

    private fun preprocessAiPaste(raw: String, mediaBaseName: String): PreprocessedResult {
        var strippedMarkdownOrProse = false
        var joinedMultiline = false
        var ignoredShellCleanup = 0
        var expandedVars = 0
        var fixedSyntax = 0

        // 1. Normalize line endings, invisible zero-width chars, exotic Unicode spaces, and smart quotes
        var text = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[\u200B\u200C\u200D\u2060\uFEFF]"), "")
            .replace(Regex("[\u00A0\u2000-\u200A\u202F\u205F\u3000]"), " ")
            .replace('“', '"')
            .replace('”', '"')
            .replace('„', '"')
            .replace('‟', '"')
            .replace('«', '"')
            .replace('»', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace('‚', '\'')
            .replace('‛', '\'')

        if (text != raw.replace("\r\n", "\n").replace('\r', '\n')) {
            fixedSyntax++
        }

        // 2. Extract Markdown fenced code blocks (``` or ~~~); fall back cleanly if fenced blocks have no FFmpeg
        if (text.contains("```") || text.contains("~~~")) {
            val extracted = extractMarkdownCodeBlocks(text)
            if (extracted.isNotBlank()) {
                text = extracted
                strippedMarkdownOrProse = true
            }
        } else if (text.contains('`')) {
            // Handle inline backtick commands embedded in prose: e.g. `ffmpeg -i in.mp4 -crf 23 out.mp4`
            val inlineCommands = extractInlineBacktickCommands(text)
            if (inlineCommands.isNotEmpty()) {
                text = inlineCommands.joinToString("\n")
                strippedMarkdownOrProse = true
            }
        }

        // 3. Clean comment/chrome lines & join Unix `\`, Windows `^`, and PowerShell `` ` `` continuations
        val rawLines = text.lines()
        val logicalLines = mutableListOf<String>()
        val pendingLine = StringBuilder()
        var expectingContinuation = false

        for (originalLine in rawLines) {
            val trimmed = originalLine.trim()
            if (trimmed.isEmpty()) {
                // Don't sever an explicit `\` / `^` continuation on an accidental blank line
                if (pendingLine.isNotEmpty() && !expectingContinuation) {
                    logicalLines.add(pendingLine.toString().trim())
                    pendingLine.clear()
                }
                continue
            }

            // Skip AI UI copy-button chrome lines (`bash`, `Copy code`, `Kodu kopyala`)
            if (trimmed.lowercase(Locale.US) in AI_UI_CHROME_LINES) {
                strippedMarkdownOrProse = true
                continue
            }

            // Skip pure comment lines (`# ...`, `// ...`, `REM ...`, `:: ...`)
            if (trimmed.startsWith("#") ||
                trimmed.startsWith("//") ||
                trimmed.startsWith("::") ||
                trimmed.startsWith("REM ", ignoreCase = true) ||
                trimmed.equals("REM", ignoreCase = true)
            ) {
                strippedMarkdownOrProse = true
                continue
            }

            // Strip leading shell prompts, prose prefixes before `ffmpeg`, inline comments, and Markdown bold
            var cleanedLine = SHELL_PROMPT_REGEX.replace(trimmed, "").trim()
            cleanedLine = stripProsePrefixBeforeFfmpeg(cleanedLine)
            cleanedLine = stripInlineComments(cleanedLine).trim()
            if (cleanedLine.startsWith("`") && cleanedLine.endsWith("`") && cleanedLine.length > 2 &&
                cleanedLine.count { it == '`' } == 2
            ) {
                cleanedLine = cleanedLine.substring(1, cleanedLine.length - 1).trim()
            }
            if (cleanedLine != trimmed) {
                strippedMarkdownOrProse = true
            }
            if (cleanedLine.isEmpty()) continue

            // Detect line continuation at end of line (`\`, `^`, or PowerShell trailing `` ` ``)
            val hasContinuation = cleanedLine.endsWith("\\") ||
                cleanedLine.endsWith("^") ||
                (cleanedLine.endsWith("`") && cleanedLine.count { it == '`' } % 2 == 1)

            val piece = if (hasContinuation) {
                joinedMultiline = true
                cleanedLine.dropLast(1).trimEnd()
            } else {
                cleanedLine
            }

            // Attach indented/next-line CLI flags (`-c:v ...`) onto previous line even if user forgot `\`
            // (ignore Markdown bullets like `- Uses H.264` where `-` is followed by whitespace)
            if (pendingLine.isEmpty() &&
                piece.length > 1 &&
                isDashChar(piece[0]) &&
                !piece[1].isWhitespace() &&
                logicalLines.isNotEmpty()
            ) {
                val prev = logicalLines.removeAt(logicalLines.lastIndex)
                joinedMultiline = true
                if (hasContinuation) {
                    pendingLine.append(prev).append(' ').append(piece)
                    expectingContinuation = true
                } else {
                    logicalLines.add("$prev $piece")
                }
                continue
            }

            if (pendingLine.isNotEmpty()) {
                pendingLine.append(' ')
            }
            pendingLine.append(piece)

            if (hasContinuation) {
                expectingContinuation = true
            } else {
                logicalLines.add(pendingLine.toString().trim())
                pendingLine.clear()
                expectingContinuation = false
            }
        }
        if (pendingLine.isNotEmpty()) {
            logicalLines.add(pendingLine.toString().trim())
        }

        // 4. Extract simple shell variable assignments (Bash, Windows Batch, PowerShell)
        val shellVars = linkedMapOf<String, String>()
        val candidateLines = mutableListOf<String>()
        for (line in logicalLines) {
            val assignMatch = VAR_ASSIGN_REGEX.matchEntire(line)
            if (assignMatch != null &&
                !line.contains(" -i ", ignoreCase = true) &&
                !isExplicitFfmpegCommandLine(line)
            ) {
                val varName = assignMatch.groupValues[1].ifEmpty { assignMatch.groupValues[3] }
                val rawGroup = if (assignMatch.groupValues[1].isNotEmpty()) {
                    assignMatch.groupValues[2]
                } else {
                    assignMatch.groupValues[4]
                }
                val rawVal = rawGroup.trim().removeSurrounding("\"").removeSurrounding("'")
                if (varName.uppercase(Locale.US) !in RESERVED_INPUT_VARS || rawVal.isNotBlank()) {
                    if (rawVal != "\$$varName" && rawVal != "\${$varName}" && !rawVal.equals("%$varName%", ignoreCase = true)) {
                        shellVars[varName] = rawVal
                        continue
                    }
                }
            }
            candidateLines.add(line)
        }

        // 5. If any explicit `ffmpeg` command exists, drop surrounding AI prose lines cleanly
        // (even if prose mentions "FFmpeg" without Markdown code fences)
        val hasExplicitFfmpegLine = candidateLines.any { containsExplicitFfmpegInvocation(it) }
        val filteredLines = if (hasExplicitFfmpegLine) {
            candidateLines.filter { line ->
                val firstWord = line.substringBefore(' ').trim()
                val isCliFlagLine = line.length > 1 && isDashChar(line[0]) && !line[1].isWhitespace()
                val keep = containsExplicitFfmpegInvocation(line) ||
                    isCliFlagLine ||
                    firstWord.lowercase(Locale.US) in IGNORED_SHELL_COMMANDS
                if (!keep) strippedMarkdownOrProse = true
                keep
            }
        } else {
            // Even without `ffmpeg` word, drop obvious non-flag prose sentences if CLI flag lines exist
            val hasFlagLines = candidateLines.any { looksLikeFlagsOnlyCommand(it) }
            if (hasFlagLines) {
                candidateLines.filter { line ->
                    val keep = looksLikeFlagsOnlyCommand(line)
                    if (!keep) strippedMarkdownOrProse = true
                    keep
                }
            } else {
                candidateLines
            }
        }

        // 6. Expand variables & filename stems, balance unclosed quotes, split chains, and tokenize
        val segments = mutableListOf<List<String>>()
        for (line in filteredLines) {
            val (expandedLine0, varCount) = expandShellVariables(line, shellVars)
            expandedVars += varCount
            val (expandedLine, stemCount) = expandFilenameStemPlaceholders(expandedLine0, mediaBaseName)
            expandedVars += stemCount

            val (balancedLine, didBalanceQuote) = balanceUnclosedQuotes(expandedLine)
            if (didBalanceQuote) fixedSyntax++

            val splitParts = splitShellCommandLine(balancedLine)
            for (part in splitParts) {
                val unwrappedPart = unwrapPowerShellBlock(part)
                val normalizedPart = normalizeAccidentalUnicodeDashes(unwrappedPart)
                if (normalizedPart != unwrappedPart) fixedSyntax++

                val tokens = tokenizeCommandArgs(normalizedPart).toMutableList()
                if (tokens.isEmpty()) continue

                // Strip shell loop/branch openers (`do`, `then`, `else`) before checking ignored commands
                // so `for f in *.mp4; do ffmpeg -i "$f" ...; done` keeps the `ffmpeg` command intact!
                while (tokens.size > 1 && tokens.first().lowercase(Locale.US) in setOf("do", "then", "else", "{")) {
                    tokens.removeAt(0)
                }
                while (tokens.size > 1 && tokens.last() == "}") {
                    tokens.removeAt(tokens.lastIndex)
                }
                if (tokens.isEmpty()) continue

                val firstLower = tokens.first().lowercase(Locale.US)
                if (firstLower in IGNORED_SHELL_COMMANDS) {
                    ignoredShellCleanup++
                    continue
                }
                // Drop `sudo apt ...` / `sudo brew ...` installation lines
                if (firstLower == "sudo" && tokens.size > 1 &&
                    tokens[1].lowercase(Locale.US) in IGNORED_SHELL_COMMANDS
                ) {
                    ignoredShellCleanup++
                    continue
                }
                segments.add(tokens)
            }
        }

        return PreprocessedResult(
            commandSegments = segments,
            strippedMarkdownOrProse = strippedMarkdownOrProse,
            joinedMultiline = joinedMultiline,
            ignoredShellCleanupCount = ignoredShellCleanup,
            expandedVariablesCount = expandedVars,
            fixedSyntaxCount = fixedSyntax
        )
    }

    private fun isDashChar(ch: Char): Boolean =
        ch == '-' || ch == '–' || ch == '—' || ch == '−' || ch == '‒' || ch == '‑' || ch == '－'

    /** Strips prose labels/numbering/bullets right before `ffmpeg`/`ffprobe` (e.g. `1. ffprobe ...`, `Komut: ffmpeg ...`, `**ffprobe**`). */
    private fun stripProsePrefixBeforeFfmpeg(line: String): String {
        var result = PROSE_PREFIX_BEFORE_FFMPEG_REGEX.replace(line, "")
        // Unwrap Markdown bold `**ffmpeg**` or `**ffprobe**` at the start
        if (result.startsWith("**")) {
            val endBold = result.indexOf("**", 2)
            if (endBold > 2) {
                val boldWord = result.substring(2, endBold).trim()
                if (FFMPEG_BIN_REGEX.matches(boldWord) || FFPROBE_BIN_REGEX.matches(boldWord)) {
                    result = boldWord + result.substring(endBold + 2)
                }
            }
        }
        return result.trim()
    }

    /** Returns true if a line starts with `ffmpeg`/`ffprobe` or contains a `&&`/`do`/`{` segment invoking either tool. */
    private fun containsExplicitFfmpegInvocation(line: String): Boolean {
        if (isExplicitFfmpegCommandLine(line)) return true
        return splitShellCommandLine(line).any { seg ->
            val cleaned = unwrapPowerShellBlock(seg)
                .replace(Regex("^(?:do|then|else)\\s+", RegexOption.IGNORE_CASE), "")
            isExplicitFfmpegCommandLine(cleaned)
        }
    }

    /**
     * Returns true only when a line genuinely invokes `ffmpeg` or `ffprobe` (directly, after a wrapper like
     * `sudo`/`time`/`nohup`/`call`/`&`, or after inline `KEY=VAL` env vars) — preventing plain AI prose
     * such as "İşte FFmpeg komutu:" from ever being mistaken for an executable command.
     */
    private fun isExplicitFfmpegCommandLine(line: String): Boolean {
        val parts = line.trim().split(Regex("\\s+"))
        var idx = 0
        while (idx < parts.size) {
            val token = parts[idx].trim('"', '\'', '`', '*')
            val lower = token.lowercase(Locale.US)
            if (FFMPEG_BIN_REGEX.matches(token) || FFPROBE_BIN_REGEX.matches(token)) return true
            if (lower in SHELL_WRAPPERS || token == "&" || token == "{" || INLINE_ENV_VAR_REGEX.matches(token)) {
                idx++
                continue
            }
            if ((lower == "nice" || lower == "ionice") && idx + 1 < parts.size) {
                idx++
                // Skip `-n 10` or `-c3`
                if (parts[idx].startsWith("-")) {
                    idx++
                    if (idx < parts.size && parts[idx].toIntOrNull() != null) idx++
                }
                continue
            }
            return false
        }
        return false
    }

    /** Heuristic for flags-only pastes (without the `ffmpeg`/`ffprobe` binary name). */
    private fun looksLikeFlagsOnlyCommand(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (isDashChar(trimmed[0]) && trimmed.length > 1 && !trimmed[1].isWhitespace()) return true
        return Regex("(?:^|\\s)(?:--?[a-zA-Z][a-zA-Z0-9_:-]*)(?:=|\\s)").containsMatchIn(trimmed) &&
            Regex("-(?:i|c:v|c:a|vcodec|acodec|vf|af|crf|preset|b:v|b:a|ss|to|t|vn|an|r|s|f|map|filter_complex|select_streams|show_entries|show_streams|show_format|show_packets|show_frames|print_format|of|read_intervals|count_frames|count_packets)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(trimmed)
    }

    /**
     * Extracts fenced Markdown blocks (``` or ~~~). If multiple blocks exist (e.g. an install block
     * followed by the real FFmpeg command), prefers only blocks containing FFmpeg/shell commands.
     */
    private fun extractMarkdownCodeBlocks(text: String): String {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        var activeFence: String? = null

        for (line in text.lines()) {
            val trimmed = line.trim()
            val fence = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (fence != null) {
                if (activeFence == null) {
                    activeFence = fence
                    current.clear()
                } else if (fence == activeFence) {
                    val block = current.toString().trim()
                    if (block.isNotEmpty()) blocks.add(block)
                    current.clear()
                    activeFence = null
                } else {
                    current.append(line).append('\n')
                }
                continue
            }
            if (activeFence != null) {
                current.append(line).append('\n')
            }
        }
        // Tolerate unclosed Markdown fence (user forgot the closing ``` when copying)
        if (activeFence != null) {
            val trailing = current.toString().trim()
            if (trailing.isNotEmpty()) blocks.add(trailing)
        }

        if (blocks.isEmpty()) return ""

        val ffmpegBlocks = blocks.filter { block ->
            block.lines().any { l ->
                val t = SHELL_PROMPT_REGEX.replace(l.trim(), "").trim()
                containsExplicitFfmpegInvocation(t) || looksLikeFlagsOnlyCommand(t)
            }
        }
        return if (ffmpegBlocks.isNotEmpty()) {
            ffmpegBlocks.joinToString("\n")
        } else {
            // None of the fenced blocks looked like FFmpeg -> let full-text parser inspect outside blocks
            ""
        }
    }

    /** Extracts inline backtick commands (e.g. ``Run `ffmpeg -i in.mp4 -crf 23 out.mp4` ``). */
    private fun extractInlineBacktickCommands(text: String): List<String> {
        val matches = Regex("`([^`\n]+)`").findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { snippet ->
                val cleaned = SHELL_PROMPT_REGEX.replace(snippet, "").trim()
                containsExplicitFfmpegInvocation(cleaned) || looksLikeFlagsOnlyCommand(cleaned)
            }
            .toList()
        return matches
    }

    private fun unwrapPowerShellBlock(segment: String): String {
        val trimmed = segment.trim()
        val braceIdx = trimmed.indexOf('{')
        if (braceIdx > 0 && trimmed.endsWith("}")) {
            val before = trimmed.substring(0, braceIdx).lowercase(Locale.US)
            if (before.contains("foreach") || before.contains("get-childitem") || before.contains("gci") || before.contains("where")) {
                return trimmed.substring(braceIdx + 1, trimmed.length - 1).trim()
            }
        }
        return trimmed
    }

    private fun expandShellVariables(line: String, vars: Map<String, String>): Pair<String, Int> {
        if (vars.isEmpty()) return line to 0
        var result = line
        var count = 0
        for ((name, value) in vars) {
            val patterns = listOf(
                Regex("\\$\\{${Regex.escape(name)}\\}"),
                Regex("\\$${Regex.escape(name)}(?![A-Za-z0-9_])"),
                Regex("%${Regex.escape(name)}%", RegexOption.IGNORE_CASE)
            )
            for (regex in patterns) {
                if (regex.containsMatchIn(result)) {
                    result = regex.replace(result, Regex.escapeReplacement(value))
                    count++
                }
            }
        }
        return result to count
    }

    /** Expands Bash/Batch/PowerShell stem placeholders (`${f%.*}`, `%~n1`, `$($_.BaseName)`) to [mediaBaseName]. */
    private fun expandFilenameStemPlaceholders(line: String, mediaBaseName: String): Pair<String, Int> {
        var count = 0
        val replaced = STEM_PLACEHOLDER_REGEX.replace(line) {
            count++
            mediaBaseName
        }
        return replaced to count
    }

    /**
     * Repairs accidentally unclosed quotes (e.g. `-vf "scale=1280:-2 -c:v libx264 out.mp4`) without
     * ever altering properly balanced quoted strings.
     */
    private fun balanceUnclosedQuotes(line: String): Pair<String, Boolean> {
        var inSingle = false
        var inDouble = false
        var openIdx = -1
        var openChar = '"'
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '\\' && !inSingle && i + 1 < line.length -> {
                    val next = line[i + 1]
                    if (!inDouble || next == '"' || next == '\\' || next == '$') {
                        i += 2
                        continue
                    }
                }
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    if (inSingle) {
                        openIdx = i
                        openChar = '\''
                    } else {
                        openIdx = -1
                    }
                }
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    if (inDouble) {
                        openIdx = i
                        openChar = '"'
                    } else {
                        openIdx = -1
                    }
                }
            }
            i++
        }

        if (openIdx < 0) return line to false

        val afterOpen = line.substring(openIdx + 1)
        // Prefer closing right before the next ` -knownFlag` or ` && ` or ` ; `
        val boundaryRegex = Regex("\\s+(?:&&|\\|\\||-(?:i|f|c|c:[vas]|vcodec|acodec|vf|af|filter_complex|crf|qp|cq|preset|b:[va]|r|s|ss|to|t|an|vn|sn|dn|y|pass|passlogfile|pix_fmt|movflags|map|threads|x264-params|x265-params|tag:[va]|profile:[va]|level|ar|ac|q:[va]|frames:[va]|select_streams|show_entries|show_streams|show_format|print_format|of|read_intervals)(?:\\b|=))", RegexOption.IGNORE_CASE)
        val flagMatch = boundaryRegex.find(afterOpen)
        if (flagMatch != null) {
            val insertPos = openIdx + 1 + flagMatch.range.first
            return (line.substring(0, insertPos) + openChar + line.substring(insertPos)) to true
        }

        // Otherwise, if there's a trailing output filename after whitespace (`-vf "scale=720:-2 out.mp4`), close before it
        val lastSpace = afterOpen.lastIndexOf(' ')
        if (lastSpace > 0) {
            val tail = afterOpen.substring(lastSpace + 1).trim()
            if (looksLikeOutputTarget(tail)) {
                val insertPos = openIdx + 1 + lastSpace
                return (line.substring(0, insertPos) + openChar + line.substring(insertPos)) to true
            }
        }

        return (line + openChar) to true
    }

    /**
     * Splits a command line on `&&`, `||`, `;`, or standalone `&` outside quotes while preserving
     * `2>&1` / `&>` redirections AND unquoted filtergraph semicolons (`[a];[b]`).
     */
    private fun splitShellCommandLine(line: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '\\' && !inSingle && i + 1 < line.length -> {
                    current.append(ch).append(line[i + 1])
                    i += 2
                    continue
                }
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    current.append(ch)
                }
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    current.append(ch)
                }
                !inSingle && !inDouble && ch == '&' -> {
                    val prev = line.getOrNull(i - 1)
                    val next = line.getOrNull(i + 1)
                    if (prev == '>' || next == '>') {
                        current.append(ch)
                    } else if (next == '&') {
                        segments.add(current.toString().trim())
                        current.clear()
                        i++
                    } else {
                        // Ignore PowerShell leading `& ffmpeg` call operator (empty before `&`)
                        if (current.toString().isBlank()) {
                            current.append(ch)
                        } else {
                            // Windows single `&` command chaining (`ffmpeg ... NUL & ffmpeg ... out.mp4`)
                            segments.add(current.toString().trim())
                            current.clear()
                        }
                    }
                }
                !inSingle && !inDouble && ch == '|' && line.getOrNull(i + 1) == '|' -> {
                    segments.add(current.toString().trim())
                    current.clear()
                    i++
                }
                !inSingle && !inDouble && ch == ';' -> {
                    val left = current.toString()
                    val right = line.substring(i + 1).trimStart()
                    val looksLikeFilterGraphSemicolon = isUnquotedFiltergraphSemicolon(left, right)
                    if (looksLikeFilterGraphSemicolon) {
                        current.append(ch)
                    } else {
                        segments.add(left.trim())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) segments.add(tail)
        return segments.filter { it.isNotEmpty() }
    }

    /** Keeps unquoted `;` inside `-filter_complex` / `-lavfi` chains (`[x];[x]paletteuse`) intact. */
    private fun isUnquotedFiltergraphSemicolon(left: String, right: String): Boolean {
        if (right.isEmpty()) return false
        if (right.startsWith("[")) return true
        if (left.trimEnd().endsWith("]")) return true
        val hasFilterFlag = Regex("(?:^|\\s)-(?:filter_complex|lavfi|vf|af)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(left)
        if (!hasFilterFlag) return false
        val firstRightWord = right.substringBefore(' ').trim().lowercase(Locale.US)
        val rightStartsNewCommand = FFMPEG_BIN_REGEX.matches(firstRightWord) ||
            FFPROBE_BIN_REGEX.matches(firstRightWord) ||
            firstRightWord in IGNORED_SHELL_COMMANDS ||
            firstRightWord in SHELL_WRAPPERS
        return !rightStartsNewCommand
    }

    /** Replaces accidental typographic/Unicode dashes (`–crf`, `—preset`, `−i`, `－vf`) and glued flag+quote (`-i"in.mp4"`). */
    private fun normalizeAccidentalUnicodeDashes(segment: String): String {
        return segment
            .replace(Regex("(^|\\s)[–—−‒‑－]+([A-Za-z])"), "$1-$2")
            .replace(Regex("(^|\\s)[–—−‒‑－]+(?=\\s)"), "$1-")
            .replace(Regex("(^|\\s)(-(?:i|vf|af|filter_complex|lavfi|c:[vas]|vcodec|acodec|x264-params|x265-params|metadata|show_entries|of|print_format))([\"'])", RegexOption.IGNORE_CASE), "$1$2 $3")
    }

    /** Strips trailing `# comment` and ` // comment` outside quotes only when preceded by whitespace. */
    private fun stripInlineComments(line: String): String {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '\\' && !inSingle && i + 1 < line.length -> {
                    val next = line[i + 1]
                    if (!inDouble || next == '"' || next == '\\' || next == '$') {
                        i += 2
                        continue
                    }
                }
                ch == '\'' && !inDouble -> inSingle = !inSingle
                ch == '"' && !inSingle -> inDouble = !inDouble
                !inSingle && !inDouble && ch == '#' && i > 0 && line[i - 1].isWhitespace() -> {
                    return line.substring(0, i).trimEnd()
                }
                !inSingle && !inDouble && ch == '/' && line.getOrNull(i + 1) == '/' &&
                    i > 0 && line[i - 1].isWhitespace() -> {
                    return line.substring(0, i).trimEnd()
                }
            }
            i++
        }
        return line
    }

    /**
     * POSIX + Windows-path-safe argument tokenizer:
     * Preserves backslashes for Windows paths (`"C:\Users\Ali\out.mp4"` AND unquoted `C:\Users\Ali\out.mp4`)
     * and FFmpeg filter escapes (`12\:30`) while unescaping shell whitespace/quotes (`\ `, `\"`, `\'`, `\\`, `\$`).
     */
    private fun tokenizeCommandArgs(command: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var hasContent = false
        var i = 0
        while (i < command.length) {
            val ch = command[i]
            when {
                ch == '\\' && !inSingle && i + 1 < command.length -> {
                    val next = command[i + 1]
                    val shouldUnescape = if (inDouble) {
                        next == '"' || next == '\\' || next == '$'
                    } else {
                        next.isWhitespace() || next == '"' || next == '\'' || next == '\\' ||
                            next == '$' || next == ';' || next == '&'
                    }
                    if (!shouldUnescape) {
                        current.append(ch)
                        hasContent = true
                    } else {
                        current.append(next)
                        hasContent = true
                        i++
                    }
                }
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    hasContent = true
                }
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    hasContent = true
                }
                ch.isWhitespace() && !inSingle && !inDouble -> {
                    if (hasContent) {
                        result.add(current.toString())
                        current.clear()
                        hasContent = false
                    }
                }
                else -> {
                    current.append(ch)
                    hasContent = true
                }
            }
            i++
        }
        if (hasContent) result.add(current.toString())
        return result
    }

    /**
     * Strips shell wrappers (`sudo`, `time`, `nice`, `nohup`, `&`, inline `KEY=VAL`) and `ffmpeg`/`ffprobe`
     * binary name(s), returning the detected tool if an explicit binary was present.
     */
    private fun stripLeadingWrappersAndBinary(tokens: MutableList<String>): CustomCommandTool? {
        while (tokens.isNotEmpty()) {
            val first = tokens.first()
            val lower = first.lowercase(Locale.US)
            when {
                lower in SHELL_WRAPPERS || first == "&" || first == "{" -> {
                    tokens.removeAt(0)
                }
                INLINE_ENV_VAR_REGEX.matches(first) -> {
                    tokens.removeAt(0)
                }
                lower == "nice" || lower == "ionice" -> {
                    tokens.removeAt(0)
                    if (tokens.isNotEmpty() && tokens.first().startsWith("-")) {
                        tokens.removeAt(0)
                        if (tokens.isNotEmpty() && tokens.first().toIntOrNull() != null) {
                            tokens.removeAt(0)
                        }
                    }
                }
                lower == "cmd" && tokens.size > 1 && tokens[1].equals("/c", ignoreCase = true) -> {
                    tokens.removeAt(0)
                    tokens.removeAt(0)
                }
                else -> break
            }
        }
        var detected: CustomCommandTool? = null
        // `while` handles accidental double paste `ffmpeg ffmpeg -i ...` or `ffprobe ffprobe ...`
        while (tokens.isNotEmpty()) {
            val head = tokens.first()
            when {
                FFPROBE_BIN_REGEX.matches(head) -> {
                    detected = CustomCommandTool.FFPROBE
                    tokens.removeAt(0)
                }
                FFMPEG_BIN_REGEX.matches(head) -> {
                    detected = CustomCommandTool.FFMPEG
                    tokens.removeAt(0)
                }
                else -> break
            }
        }
        return detected
    }

    /**
     * Forgiving token normalizer fixing common human & AI spacing/casing/punctuation typos:
     * - `- i` -> `-i`, `- crf` -> `-crf`
     * - `-c: v` / `-c :v` / `-c : v` -> `-c:v`
     * - `--crf` -> `-crf`, `-CRF` -> `-crf`, `--show_entries` -> `-show_entries`
     * - `-crf=23` -> `-crf`, `23`, `-of=default=...` -> `-of`, `default=...`
     * - `-fps` -> `-r`, `-v:c` -> `-c:v`, `-faststart` -> `-movflags +faststart`, `-o out.mp4` -> `out.mp4`
     */
    private fun normalizeStepTokens(raw: List<String>, isProbe: Boolean = false): Pair<List<String>, Int> {
        val out = mutableListOf<String>()
        var fixes = 0
        var i = 0
        while (i < raw.size) {
            var tok = raw[i]

            // 1. Accidental space right after `-` (`- i`, `- crf`, `- c:v`)
            if (tok == "-" && i + 1 < raw.size) {
                val candidate = "-${raw[i + 1]}"
                val candLower = candidate.lowercase(Locale.US)
                if (isKnownFlagToken(candidate) || candLower in FLAG_TYPO_ALIASES ||
                    candLower == "-faststart" || candLower == "-o" || candLower == "-output" ||
                    candLower.substringBefore('=') in NON_OUTPUT_VALUE_FLAGS ||
                    candLower.substringBefore('=') in FFPROBE_VALUE_FLAGS ||
                    candLower.substringBefore(':') in STREAM_SPEC_BASE_FLAGS
                ) {
                    tok = candidate
                    i += 1
                    fixes++
                }
            }

            // 2. Accidental double dash on FFmpeg/FFprobe flags (`--crf`, `--c:v`, `--preset=fast`, `--show_entries`)
            if (tok.startsWith("--") && tok.length > 2 && tok[2].isLetter()) {
                tok = tok.substring(1)
                fixes++
            }

            // 3. Accidental spaces around `:` in stream specifiers (`-c: v`, `-c :v`, `-c : v`, `-b: a`)
            val lowerNow = tok.lowercase(Locale.US)
            if (lowerNow.endsWith(":") && lowerNow.dropLast(1) in STREAM_SPEC_BASE_FLAGS && i + 1 < raw.size) {
                val spec = raw[i + 1]
                if (Regex("^[vasd](?::\\d+)?$", RegexOption.IGNORE_CASE).matches(spec)) {
                    tok = "$lowerNow${spec.lowercase(Locale.US)}"
                    i += 1
                    fixes++
                }
            } else if (lowerNow in STREAM_SPEC_BASE_FLAGS && i + 1 < raw.size) {
                val next = raw[i + 1]
                if (next.startsWith(":") && Regex("^:[vasd](?::\\d+)?$", RegexOption.IGNORE_CASE).matches(next)) {
                    tok = "$lowerNow${next.lowercase(Locale.US)}"
                    i += 1
                    fixes++
                } else if (next == ":" && i + 2 < raw.size &&
                    Regex("^[vasd](?::\\d+)?$", RegexOption.IGNORE_CASE).matches(raw[i + 2])
                ) {
                    tok = "$lowerNow:${raw[i + 2].lowercase(Locale.US)}"
                    i += 2
                    fixes++
                }
            }

            // 4. Split `-flag=value` into `["-flag", "value"]` on known FFmpeg/FFprobe flags
            if (tok.startsWith("-") && tok.contains('=')) {
                val eqIdx = tok.indexOf('=')
                val flagPart = tok.substring(0, eqIdx)
                val valPart = tok.substring(eqIdx + 1)
                val flagLower = flagPart.lowercase(Locale.US)
                val mappedFlag = FLAG_TYPO_ALIASES[flagLower] ?: flagLower
                if (isKnownFlagToken(mappedFlag) || flagLower == "-o" || flagLower == "-output") {
                    fixes++
                    if (!isProbe && (flagLower == "-o" || flagLower == "-output")) {
                        if (valPart.isNotEmpty()) out.add(valPart)
                    } else {
                        out.add(mappedFlag)
                        if (valPart.isNotEmpty()) out.add(valPart)
                    }
                    i++
                    continue
                }
            }

            val finalLower = tok.lowercase(Locale.US)

            // 5. Standalone `-faststart` typo -> `-movflags +faststart`
            // (Skip when immediately following `-movflags` so `-movflags -faststart` stays intact!)
            if (!isProbe && finalLower == "-faststart" && !out.lastOrNull().equals("-movflags", ignoreCase = true)) {
                out.add("-movflags")
                out.add("+faststart")
                fixes++
                i++
                continue
            }

            // 6. Beginner `-o out.mp4` / `-output out.mp4` -> drop the `-o` flag so `out.mp4` is the output
            // (Keep `-o` on FFprobe so planFfprobeStep can strip both `-o` and its output file cleanly.)
            if (!isProbe && (finalLower == "-o" || finalLower == "-output")) {
                fixes++
                i++
                continue
            }

            // 7. Known flag typo aliases (`-fps` -> `-r`, `-v:c` -> `-c:v`, `-pixfmt` -> `-pix_fmt`, etc.)
            val alias = FLAG_TYPO_ALIASES[finalLower]
            if (alias != null) {
                out.add(alias)
                fixes++
                i++
                continue
            }

            // 8. Uppercase/mixed-case known FFmpeg/FFprobe flags (`-I` -> `-i`, `-CRF` -> `-crf`, `-V` -> `-v`)
            if (tok.startsWith("-") && tok != finalLower && isKnownFlagToken(finalLower)) {
                out.add(finalLower)
                fixes++
                i++
                continue
            }

            out.add(tok)
            i++
        }
        return out to fixes
    }

    /**
     * Detects an omitted `-i` input prefix such as `ffmpeg input.mp4 -c:v libx264 out.mp4` or
     * `ffmpeg my video.mp4 -crf 23 out.mp4`. Returns the exclusive end index of the input tokens, or 0.
     */
    private fun detectOmittedInputPrefixEnd(tokens: List<String>): Int {
        if (tokens.size < 2) return 0
        if (tokens.first().startsWith("-")) return 0

        // Find the first flag token
        val firstFlagIdx = tokens.indexOfFirst { it.startsWith("-") && it.length > 1 }
        if (firstFlagIdx > 0) {
            // Non-flag prefix before flags (`ffmpeg input.mp4 -crf 23 out.mp4` or `ffmpeg my video.mp4 -crf 23 out.mp4`)
            val prefix = tokens.subList(0, firstFlagIdx).joinToString(" ")
            if (looksLikeCompleteInputPath(prefix) || looksLikeInputPlaceholder(prefix)) {
                return firstFlagIdx
            }
            return 0
        }

        // No flags at all: `ffmpeg input.mp4 output.mp4` (2+ media-like tokens -> first is input, rest is output)
        if (tokens.size == 2 &&
            (looksLikeCompleteInputPath(tokens[0]) || looksLikeInputPlaceholder(tokens[0])) &&
            looksLikeOutputTarget(tokens[1])
        ) {
            return 1
        }
        return 0
    }

    private fun looksLikeCompleteInputPath(tok: String): Boolean {
        val cleaned = tok.trim().trim('"', '\'', '<', '>', '[', ']', ',', ';')
        if (cleaned.isEmpty()) return false
        return MEDIA_OUTPUT_EXT_REGEX.matches(cleaned)
    }

    private fun looksLikeInputPlaceholder(tok: String): Boolean {
        val cleaned = tok.trim().trim('"', '\'')
        return cleaned == "\$IN" || cleaned == "\${IN}" ||
            cleaned.equals("input", ignoreCase = true) ||
            cleaned.startsWith("<") && cleaned.endsWith(">") ||
            cleaned.startsWith("[") && cleaned.endsWith("]") ||
            cleaned.startsWith("$") ||
            (cleaned.startsWith("%") && cleaned.endsWith("%"))
    }

    /** Collects a filter argument, seamlessly rejoining unquoted fragments like `scale=1280:-2, fps=30`. */
    private fun collectFilterArgument(tokens: List<String>, startIdx: Int): Triple<String, Int, Boolean> {
        val first = tokens[startIdx]
        val sb = StringBuilder(first)
        var consumed = 1
        var merged = false

        while (startIdx + consumed < tokens.size) {
            val next = tokens[startIdx + consumed]
            if (isKnownFlagToken(next)) break
            if (next == "/dev/null" || isWindowsNullDevice(next) || next == "-") break

            val curStr = sb.toString()
            val curNeedsContinuation = curStr.endsWith(",") || curStr.endsWith(";") ||
                curStr.endsWith("=") || curStr.endsWith(":") || curStr.endsWith("]")
            val nextIsConnector = next == "=" || next == ":" || next == "," || next == ";" ||
                next.startsWith("=") || next.startsWith(":") || next.startsWith(",") || next.startsWith("[")

            if (!curNeedsContinuation && !nextIsConnector) {
                break
            }
            // Don't swallow final output file if current filter didn't explicitly ask for continuation
            if (!curNeedsContinuation && startIdx + consumed == tokens.lastIndex && looksLikeOutputTarget(next)) {
                break
            }
            sb.append(next)
            consumed++
            merged = true
        }
        return Triple(sb.toString(), consumed, merged)
    }

    /** Rewrites desktop GPU filter names (`scale_cuda=`, `scale_vaapi=`, `hwupload_cuda`, `hwdownload`) to CPU filters. */
    private fun adaptFilterForAndroid(filter: String): Pair<String, Boolean> {
        var result = filter
        var adapted = false

        val gpuScaleRegex = Regex("\\bscale_(?:cuda|npp|vaapi|qsv|opencl|vulkan|vt)=", RegexOption.IGNORE_CASE)
        if (gpuScaleRegex.containsMatchIn(result)) {
            result = gpuScaleRegex.replace(result, "scale=")
            adapted = true
        }
        val gpuFpsRegex = Regex("\\bfps_(?:cuda|vaapi|qsv)=", RegexOption.IGNORE_CASE)
        if (gpuFpsRegex.containsMatchIn(result)) {
            result = gpuFpsRegex.replace(result, "fps=")
            adapted = true
        }
        val hwTransferRegex = Regex("\\b(?:hwupload_cuda|hwupload|hwdownload)(?:=[^,;\\s]+)?", RegexOption.IGNORE_CASE)
        if (hwTransferRegex.containsMatchIn(result)) {
            result = hwTransferRegex.replace(result, "")
            // Clean up any leftover double/leading/trailing commas around removed hwupload/hwdownload stages
            result = result
                .replace(Regex(",{2,}"), ",")
                .replace(Regex("^,+|,+$"), "")
                .replace(Regex(",(?=;)"), "")
                .replace(Regex("(?<=;),"), "")
                .replace(Regex("(?<=\\]),"), "")
                .replace(Regex(",(?=\\[)"), "")
            adapted = true
        }
        return result.trim() to adapted
    }

    /** Maps desktop/alias video codecs (`h264_nvenc`, `hevc_videotoolbox`, `x264`, `h265`) to Android GPL encoders. */
    private fun mapVideoCodecForAndroid(rawCodec: String): Triple<String, Boolean, Boolean> {
        val stripped = rawCodec.removePrefix("-")
        val fixedLeadingDash = stripped != rawCodec
        val lower = stripped.lowercase(Locale.US)
        return when {
            lower in DESKTOP_GPU_H264_ENCODERS -> Triple("libx264", true, fixedLeadingDash)
            lower in DESKTOP_GPU_HEVC_ENCODERS -> Triple("libx265", true, fixedLeadingDash)
            lower == "x264" || lower == "h264" || lower == "avc" || lower == "h.264" ->
                Triple("libx264", false, true)
            lower == "x265" || lower == "h265" || lower == "hevc" || lower == "h.265" ->
                Triple("libx265", false, true)
            lower == "vp9" || lower == "vp9_vaapi" || lower == "vp9_qsv" ->
                Triple("libvpx-vp9", lower.contains('_'), !lower.contains('_') || fixedLeadingDash)
            lower == "vp8" || lower == "vp8_vaapi" ->
                Triple("libvpx", lower.contains('_'), !lower.contains('_') || fixedLeadingDash)
            else -> Triple(stripped, false, fixedLeadingDash)
        }
    }

    /** Maps unavailable/alias audio codecs (`libfdk_aac` -> `aac`, `mp3` -> `libmp3lame`, etc.) to Android GPL encoders. */
    private fun mapAudioCodecForAndroid(rawCodec: String): Triple<String, Boolean, Boolean> {
        val stripped = rawCodec.removePrefix("-")
        val fixedLeadingDash = stripped != rawCodec
        val lower = stripped.lowercase(Locale.US)
        return when (lower) {
            "libfdk_aac", "fdk_aac", "aac_at", "aac_mf" -> Triple("aac", true, fixedLeadingDash)
            "mp3", "mp3_mf" -> Triple("libmp3lame", false, true)
            "opus" -> Triple("libopus", false, true)
            "vorbis" -> Triple("libvorbis", false, true)
            else -> Triple(stripped, false, fixedLeadingDash)
        }
    }

    /** Collects & normalizes timestamps (`10,5` -> `10.5`, `00: 01: 30` -> `00:01:30`). */
    private fun collectTimestampValue(tokens: List<String>, startIdx: Int): Triple<String, Int, Boolean> {
        val sb = StringBuilder(tokens[startIdx])
        var consumed = 1
        var fixed = false

        // Join space-separated colon parts (`00:`, `01:`, `30` -> `00:01:30`)
        while (startIdx + consumed < tokens.size && sb.endsWith(":")) {
            val next = tokens[startIdx + consumed]
            if (Regex("^\\d{1,2}(?:[.,]\\d+)?:?$").matches(next)) {
                sb.append(next)
                consumed++
                fixed = true
            } else {
                break
            }
        }

        var value = sb.toString()
        val commaMatch = TURKISH_TIMESTAMP_DECIMAL_REGEX.matchEntire(value)
        if (commaMatch != null) {
            value = "${commaMatch.groupValues[1]}.${commaMatch.groupValues[2]}"
            fixed = true
        }
        return Triple(value, consumed, fixed)
    }

    /** Collects & normalizes bitrate arguments (`128 kbps` -> `128k`, `2,5 mbps` -> `2.5M`, `2000 k` -> `2000k`). */
    private fun collectBitrateValue(tokens: List<String>, startIdx: Int): Triple<String, Int, Boolean> {
        var raw = tokens[startIdx]
        var consumed = 1
        var fixed = false

        // Combine separated unit token (`-b:a 128 kbps`, `-b:v 2.5 M`)
        if (startIdx + 1 < tokens.size && Regex("^\\d+(?:[.,]\\d+)?$").matches(raw)) {
            val unitCand = tokens[startIdx + 1].lowercase(Locale.US)
            if (unitCand in setOf("k", "kb", "kbps", "kb/s", "kbit/s", "m", "mb", "mbps", "mb/s", "mbit/s", "bps")) {
                raw = "$raw$unitCand"
                consumed = 2
                fixed = true
            }
        }

        val m = BITRATE_UNIT_REGEX.matchEntire(raw)
        if (m != null) {
            val num = m.groupValues[1].replace(',', '.')
            val unitLower = m.groupValues[2].lowercase(Locale.US)
            val normalizedUnit = when {
                unitLower.startsWith("m") -> "M"
                unitLower.startsWith("k") -> "k"
                else -> ""
            }
            val normalized = "$num$normalizedUnit"
            if (normalized != tokens[startIdx]) fixed = true
            return Triple(normalized, consumed, fixed)
        }

        val fixedComma = normalizeScalarValue(raw)
        if (fixedComma != raw) fixed = true
        return Triple(fixedComma, consumed, fixed)
    }

    private fun normalizeScalarValue(raw: String): String {
        val trimmed = raw.trimEnd(',', ';')
        val m = TURKISH_DECIMAL_REGEX.matchEntire(trimmed)
        return if (m != null) "${m.groupValues[1]}.${m.groupValues[2]}" else trimmed
    }

    /** Merges unquoted multi-word output filenames like `my final video.mp4` into a single output name. */
    private fun collectOutputToken(tokens: List<String>, startIdx: Int): Triple<String, Int, Boolean> {
        val first = tokens[startIdx]
        if (looksLikeOutputTarget(first) || UNRESOLVED_OUTPUT_PLACEHOLDER_REGEX.matches(first)) {
            return Triple(first, 1, false)
        }
        // Only merge forward if there are consecutive non-flag tokens culminating in a token with a media extension
        var look = startIdx + 1
        var foundExtIdx = -1
        while (look < tokens.size) {
            val cand = tokens[look]
            if (cand.startsWith("-") || cand == "/dev/null" || isWindowsNullDevice(cand)) break
            if (looksLikeOutputTarget(cand)) {
                foundExtIdx = look
                break
            }
            look++
        }
        return if (foundExtIdx > startIdx) {
            val merged = tokens.subList(startIdx, foundExtIdx + 1).joinToString(" ")
            Triple(merged, foundExtIdx - startIdx + 1, true)
        } else {
            Triple(first, 1, false)
        }
    }

    /** Infers the best container extension from the command's codecs/flags when user omitted one. */
    private fun inferSmartOutputExtension(
        sawVn: Boolean,
        sawSingleFrame: Boolean,
        sawPaletteUse: Boolean,
        explicitOutputFormat: String?,
        videoCodec: String?,
        audioCodec: String?,
        sourceMediaExtension: String,
        sourceAudioCodec: String?,
        defaultOutputFileName: String
    ): String {
        when (explicitOutputFormat) {
            "mp4", "mov", "mkv", "webm", "gif", "mp3", "flac", "wav", "ogg", "opus", "avi", "flv" ->
                return explicitOutputFormat
            "matroska" -> return "mkv"
            "ipod", "adts" -> return "m4a"
        }
        if (sawPaletteUse) return "gif"
        if (sawSingleFrame) {
            return if (videoCodec == "png") "png" else "jpg"
        }
        if (sawVn) {
            return when (audioCodec) {
                "libmp3lame", "mp3" -> "mp3"
                "flac" -> "flac"
                "libopus", "opus" -> "opus"
                "libvorbis", "vorbis" -> "ogg"
                "copy" -> FfmpegMath.resolveOriginalAudioExtension(sourceAudioCodec)
                else -> if (audioCodec?.startsWith("pcm_") == true) "wav" else "m4a"
            }
        }
        if (videoCodec == "libvpx" || videoCodec == "libvpx-vp9") return "webm"
        if (videoCodec == "gif") return "gif"
        if (videoCodec == "copy" && sourceMediaExtension.lowercase(Locale.US) in setOf("mp4", "mkv", "mov", "webm")) {
            return sourceMediaExtension.lowercase(Locale.US)
        }
        return defaultOutputFileName.substringAfterLast('.', "mp4").ifBlank { "mp4" }
    }

    /** Cleans output filenames, resolves placeholders (`$OUT`, `<output>`), and appends an extension if missing. */
    private fun resolveCleanOutputFileName(
        rawToken: String,
        inferredExt: String,
        defaultOutputFileName: String,
        hasExplicitFormat: Boolean
    ): Pair<String, Boolean> {
        val trimmed = rawToken.trim()
        val baseDefault = defaultOutputFileName.substringBeforeLast('.', defaultOutputFileName).ifBlank { "output" }
        val smartDefault = "$baseDefault.$inferredExt"

        // Unresolved variable/template placeholder (`$OUT`, `${OUTPUT}`, `<output>`, `[output]`)
        if (UNRESOLVED_OUTPUT_PLACEHOLDER_REGEX.matches(trimmed)) {
            val inner = extractSafeFileName(trimmed)
            if (inner != null && MEDIA_OUTPUT_EXT_REGEX.matches(inner) && !inner.startsWith("$") && !inner.startsWith("%")) {
                return inner to (inner != trimmed)
            }
            return smartDefault to true
        }

        val cleaned = extractSafeFileName(trimmed) ?: return smartDefault to true
        var fixed = cleaned != trimmed.substringAfterLast('/').substringAfterLast('\\')

        // Append inferred extension if user forgot one (`ffmpeg -i in.mp4 -crf 23 sonuc`) and no `-f` format was forced
        val withExt = if (!hasExplicitFormat && !MEDIA_OUTPUT_EXT_REGEX.matches(cleaned) && !cleaned.contains('.')) {
            fixed = true
            "$cleaned.$inferredExt"
        } else {
            cleaned
        }
        return withExt to fixed
    }

    private fun isNonOutputValueFlag(lowerFlag: String): Boolean {
        if (lowerFlag in NON_OUTPUT_VALUE_FLAGS) return true
        return STREAM_SPECIFIER_FLAG_PREFIXES.any { lowerFlag.startsWith(it) }
    }

    private fun isKnownFlagToken(token: String): Boolean {
        if (!token.startsWith("-") || token.length < 2) return false
        val lower = token.lowercase(Locale.US)
        return lower in ZERO_ARG_FLAGS ||
            isNonOutputValueFlag(lower) ||
            lower in FFPROBE_ZERO_ARG_FLAGS ||
            lower in FFPROBE_VALUE_FLAGS ||
            lower in FLAG_TYPO_ALIASES
    }

    /**
     * Strips shell redirections (`2>&1`, `>/dev/null`, `2>NUL`, `> log.txt`) and trailing shell
     * pipes (`| grep ...`, `| jq ...`, `| head ...`) which are invalid in native `argv[]`.
     */
    private fun stripShellRedirections(tokens: List<String>): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            val lower = tok.lowercase(Locale.US)
            if (tok == "|" && i + 1 < tokens.size) {
                val pipeCmd = tokens[i + 1].lowercase(Locale.US).substringAfterLast('/').substringAfterLast('\\')
                if (pipeCmd in SHELL_PIPE_FILTERS || pipeCmd in IGNORED_SHELL_COMMANDS) {
                    break
                }
            }
            when {
                lower == "2>&1" || lower == "1>&2" ||
                    lower.startsWith("2>/dev/null") || lower.startsWith(">/dev/null") ||
                    lower.startsWith("&>/dev/null") || lower.startsWith("1>/dev/null") ||
                    lower.startsWith("2>nul") || lower.startsWith(">nul") -> {
                    i += 1
                }
                lower == ">" || lower == ">>" || lower == "1>" || lower == "2>" || lower == "&>" -> {
                    i += 2
                }
                else -> {
                    result.add(tok)
                    i += 1
                }
            }
        }
        return result
    }

    private fun isVirtualOrNetworkInput(raw: String): Boolean {
        val lower = raw.trim().lowercase(Locale.US)
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("rtmp://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("udp://") ||
            lower.startsWith("tcp://") ||
            lower.startsWith("pipe:") ||
            lower.startsWith("concat:") ||
            lower.startsWith("lavfi:") ||
            lower.startsWith("anullsrc") ||
            lower.startsWith("color=") ||
            lower.startsWith("testsrc") ||
            lower.startsWith("sine=") ||
            lower.startsWith("aevalsrc=")
    }

    private fun isWindowsNullDevice(tok: String): Boolean {
        val lower = tok.trim().trimEnd('.', ',', ';').lowercase(Locale.US)
        return lower == "nul" || lower == "nul:" || lower == "null:"
    }

    private fun looksLikeOutputTarget(tok: String): Boolean {
        if (tok == "/dev/null" || tok == "-" || isWindowsNullDevice(tok)) return true
        val cleaned = tok.trim()
            .trim('"', '\'', '<', '>', '[', ']', '{', '}', '(', ')')
            .trimEnd('.', ',', ';', ':', '!', '?')
        return MEDIA_OUTPUT_EXT_REGEX.matches(cleaned)
    }

    /**
     * Converts desktop/tutorial paths (`C:\Users\Ali\Desktop\out.mp4`, `~/out.mp4`, `<output.mp4>`,
     * `/path/to/result.mkv`, `out.mp4.`) into a clean filename safe for Android's sandbox workDir.
     */
    private fun extractSafeFileName(raw: String): String? {
        val unwrapped = raw.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removeSurrounding("`")
            .removePrefix("<")
            .removeSuffix(">")
            .removeSurrounding("[", "]")
            .removeSurrounding("{", "}")
            .removeSurrounding("(", ")")
            .trim()
            .trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
            .trim()
        if (unwrapped.isEmpty() || unwrapped == "." || unwrapped == "..") return null
        val afterSlash = unwrapped.substringAfterLast('/').substringAfterLast('\\')
            .trim()
            .trimEnd('.', ',', ';', ':', '!', '?')
        if (afterSlash.isEmpty() || afterSlash == "." || afterSlash == "..") return null
        // Guard against path traversal and Android/FAT illegal filename characters while preserving `%04d`
        val sanitized = afterSlash
            .replace("..", "_")
            .replace(Regex("[:*?\"<>|]"), "_")
            .trim()
        return sanitized.takeIf { it.isNotBlank() && it != "_" }
    }

    private fun formatWorkPath(workDirPath: String, fileName: String, isPreview: Boolean): String {
        return if (isPreview) fileName else "$workDirPath/$fileName"
    }

    private fun normalizeX265Params(
        rawParams: String,
        workDirPath: String,
        isPreview: Boolean
    ): Pair<String, Boolean> {
        val parts = rawParams.split(':').filter { it.isNotBlank() }.toMutableList()
        var adapted = false
        var hasStats = false
        var hasPass = false

        for (idx in parts.indices) {
            val part = parts[idx]
            val lower = part.lowercase(Locale.US)
            if (lower.startsWith("pass=")) {
                hasPass = true
            } else if (lower.startsWith("stats=")) {
                hasStats = true
                val rawStats = part.substringAfter('=')
                val safeName = extractSafeFileName(rawStats) ?: "x265_2pass.log"
                val newPath = formatWorkPath(workDirPath, safeName, isPreview)
                if (rawStats != newPath) {
                    parts[idx] = "stats=$newPath"
                    adapted = true
                }
            }
        }

        if (hasPass && !hasStats) {
            val statsPath = formatWorkPath(workDirPath, "x265_2pass.log", isPreview)
            parts.add("stats=$statsPath")
            adapted = true
        }

        return parts.joinToString(":") to adapted
    }

    private fun extractX265PassNumber(params: String): Int? {
        for (part in params.split(':')) {
            if (part.startsWith("pass=", ignoreCase = true)) {
                return part.substringAfter('=').toIntOrNull()
            }
        }
        return null
    }

    private fun resolveStepExpectedDuration(
        mediaDurationSec: Double?,
        ssSec: Double?,
        toSec: Double?,
        tSec: Double?
    ): Double? {
        if (tSec != null && tSec > 0.0) return tSec
        if (toSec != null && toSec > 0.0) {
            val start = ssSec ?: 0.0
            if (toSec > start) return toSec - start
        }
        if (mediaDurationSec != null && mediaDurationSec > 0.0) {
            if (ssSec != null && ssSec > 0.0 && mediaDurationSec > ssSec) {
                return mediaDurationSec - ssSec
            }
            return mediaDurationSec
        }
        return null
    }
}
