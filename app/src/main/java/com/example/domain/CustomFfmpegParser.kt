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
    val autoAddedOutputFileName: String? = null
)

data class CustomCommandStep(
    val args: List<String>,
    val outputFileNames: List<String>,
    val isNullOutputOnly: Boolean,
    val expectedDurationSec: Double?,
    val passNumber: Int? = null
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
}

/**
 * Forgiving, AI-ready Custom FFmpeg Command parser and multi-step execution planner.
 *
 * Designed so users can copy-paste commands directly from ChatGPT, Gemini, Claude, Copilot,
 * or any tutorial without manually editing syntax:
 * - Strips Markdown code fences (```bash ... ```), surrounding AI prose, comments (#, //, REM),
 *   and shell prompts ($ , > , PS> ).
 * - Joins multi-line continuations (Unix `\`, Windows `^`, PowerShell `` ` ``).
 * - Splits multi-step / 2-pass / palettegen chains across `&&`, `;`, `&`, and newlines.
 * - Expands simple shell variables (`IN=...`, `BITRATE=2000k`, `$VAR`, `${VAR}`, `%VAR%`).
 * - Ignores trailing shell cleanup commands (`rm`, `del`, `echo`, `mkdir`, etc.).
 * - Automatically binds any external `-i <file>` (e.g. `input.mp4`, `video.mkv`, `"C:\...\a.mov"`,
 *   `"$IN"`) to the user's selected media file while preserving multi-step intermediates (`palette.png`),
 *   virtual inputs (`-f lavfi -i ...`), and network URIs.
 * - Maps Windows `NUL` to `/dev/null`, auto-injects writable `-passlogfile` and x265 `stats=` paths
 *   inside Android's sandbox workDir so 2-pass encodes never fail with read-only `/` errors,
 *   normalizes desktop output paths (`C:\...\out.mp4`, `~/Desktop/out.mp4`) into workDir, and
 *   strips accidental shell redirections (`2>&1`, `>/dev/null`).
 */
object CustomFfmpegParser {

    /** Known FFmpeg boolean (0-argument) flags so output filenames following them are never swallowed. */
    private val ZERO_ARG_FLAGS = setOf(
        "-y", "-n",
        "-an", "-vn", "-sn", "-dn",
        "-re", "-shortest",
        "-accurate_seek", "-noaccurate_seek",
        "-copyts", "-start_at_zero", "-copytb", "-copyinkf",
        "-ignore_unknown", "-fix_sub_duration", "-ignore_editlist",
        "-hide_banner", "-nostdin", "-stats", "-nostats",
        "-benchmark", "-benchmark_all", "-report",
        "-autorotate", "-noautorotate",
        "-xerror", "-abort_on", "-dump", "-hex",
        "-bitexact", "-sameq", "-same_quant",
        "-deinterlace", "-psnr", "-pass_1", "-pass_2",
        "-find_stream_info", "-nofind_stream_info",
        "-auto_conversion_filters", "-noauto_conversion_filters",
        "-recast_media"
    )

    /** Flags whose value MUST never be mistaken for an output file even at the end of a command. */
    private val NON_OUTPUT_VALUE_FLAGS = setOf(
        "-i", "-f", "-c", "-c:v", "-c:a", "-c:s", "-vcodec", "-acodec", "-scodec",
        "-vf", "-af", "-filter:v", "-filter:a", "-filter_complex", "-lavfi", "-filter_complex_script",
        "-map", "-map_metadata", "-map_chapters",
        "-pass", "-pass:v", "-passlogfile",
        "-x264-params", "-x265-params", "-svtav1-params", "-aom-params",
        "-metadata", "-disposition",
        "-bsf", "-bsf:v", "-bsf:a",
        "-b", "-b:v", "-b:a", "-maxrate", "-minrate", "-bufsize",
        "-crf", "-qp", "-cq", "-preset", "-tune", "-profile", "-profile:v", "-level", "-pix_fmt",
        "-r", "-s", "-aspect", "-ar", "-ac", "-ss", "-to", "-t", "-sseof", "-itsoffset",
        "-g", "-keyint_min", "-bf", "-refs", "-threads", "-filter_threads",
        "-movflags", "-fflags", "-flags", "-tag", "-tag:v", "-tag:a",
        "-q", "-q:v", "-q:a", "-qscale", "-qscale:v", "-qscale:a",
        "-frames", "-frames:v", "-frames:a", "-vframes", "-aframes",
        "-loglevel", "-v", "-vsync", "-fps_mode", "-async", "-avoid_negative_ts",
        "-max_muxing_queue_size", "-strict", "-hwaccel", "-hwaccel_output_format",
        "-color_primaries", "-color_trc", "-colorspace", "-color_range",
        "-stream_loop", "-loop", "-framerate"
    )

    /** Non-FFmpeg shell commands commonly included by AIs around FFmpeg snippets (e.g. cleanup lines). */
    private val IGNORED_SHELL_COMMANDS = setOf(
        "rm", "del", "erase", "rmdir", "rd",
        "mkdir", "md", "cd", "pushd", "popd",
        "echo", "printf", "cls", "clear", "pause",
        "exit", "true", "false", "sleep", "timeout",
        "ls", "dir", "mv", "move", "ren", "rename", "cp", "copy",
        "chmod", "chown", "touch", "cat", "type", "open", "start",
        "unset", "if", "then", "else", "elif", "fi", "for", "do", "done", "while", "read"
    )

    private val MEDIA_OUTPUT_EXT_REGEX = Regex(".*\\.[a-zA-Z0-9]{2,5}$")
    private val VAR_ASSIGN_REGEX = Regex("^(?:export\\s+|set\\s+)?\\$?([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
    private val SHELL_PROMPT_REGEX = Regex("^(?:(?:PS\\s+)?[A-Za-z]:\\\\[^>]*>|\\$|>)\\s+")
    private val FFMPEG_BIN_REGEX = Regex("^(?:.*/|.*\\\\)?ffmpeg(?:\\.exe)?$", RegexOption.IGNORE_CASE)
    private val RESERVED_INPUT_VARS = setOf("IN", "INPUT", "INPUT_FILE", "SRC", "SOURCE", "VIDEO", "FILE", "1")

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
        isPreview = isPreview
    )

    internal fun buildPlan(
        rawCommand: String,
        mediaFileName: String,
        mediaExtension: String,
        mediaDurationSec: Double?,
        workDirPath: String,
        defaultOutputFileName: String,
        inputTokenProvider: () -> String,
        isPreview: Boolean = false
    ): CustomCommandPlan {
        if (rawCommand.isBlank()) {
            return CustomCommandPlan(emptyList(), emptySet(), CustomCommandAdaptations())
        }

        val prep = preprocessAiPaste(rawCommand)
        if (prep.commandSegments.isEmpty()) {
            return CustomCommandPlan(
                steps = emptyList(),
                consumedIntermediateFiles = emptySet(),
                adaptations = CustomCommandAdaptations(
                    strippedMarkdownOrProse = prep.strippedMarkdownOrProse,
                    joinedMultiline = prep.joinedMultiline,
                    ignoredShellCleanupCount = prep.ignoredShellCleanupCount,
                    expandedVariablesCount = prep.expandedVariablesCount
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

        val inputAlias = if (mediaExtension.isNotBlank()) "input.$mediaExtension" else "input"

        for ((stepIndex, rawTokens) in prep.commandSegments.withIndex()) {
            val cleanedTokens = stripShellRedirections(rawTokens).toMutableList()
            if (cleanedTokens.isEmpty()) continue

            // Strip leading shell wrappers (`sudo`, `time`, `nice`, `nohup`) and `ffmpeg` binary
            while (cleanedTokens.isNotEmpty() &&
                cleanedTokens.first().lowercase(Locale.US) in setOf("sudo", "time", "nohup")
            ) {
                cleanedTokens.removeAt(0)
            }
            if (cleanedTokens.isNotEmpty() && FFMPEG_BIN_REGEX.matches(cleanedTokens.first())) {
                cleanedTokens.removeAt(0)
            }
            if (cleanedTokens.isEmpty()) continue

            val outArgs = mutableListOf<String>()
            val stepOutputs = mutableListOf<String>()
            var sawAnyInputFlag = false
            var sawLavfiFormat = false
            var sawOverwriteFlag = false
            var sawPassFlag = false
            var sawPassLogFile = false
            var passNumber: Int? = null
            var hasNonNullOutput = false
            var hasAnyOutput = false

            // Pre-scan for flags that influence injection decisions
            var scanIdx = 0
            while (scanIdx < cleanedTokens.size) {
                val t = cleanedTokens[scanIdx]
                val lower = t.lowercase(Locale.US)
                if (lower == "-y" || lower == "-n") sawOverwriteFlag = true
                if (lower == "-passlogfile") sawPassLogFile = true
                if (lower == "-i") sawAnyInputFlag = true
                if ((lower == "-pass" || lower == "-pass:v") && scanIdx + 1 < cleanedTokens.size) {
                    sawPassFlag = true
                    passNumber = cleanedTokens[scanIdx + 1].toIntOrNull()
                }
                if (lower == "-f" && scanIdx + 1 < cleanedTokens.size &&
                    cleanedTokens[scanIdx + 1].equals("lavfi", ignoreCase = true)
                ) {
                    sawLavfiFormat = true
                }
                scanIdx++
            }
            val endsWithNullSink = cleanedTokens.lastOrNull()?.let { last ->
                last == "/dev/null" || isWindowsNullDevice(last) || last == "-"
            } == true

            if (!sawOverwriteFlag) {
                outArgs.add("-y")
            }

            // If the user pasted only encoder/filter flags without `-i`, auto-attach their selected media
            if (!sawAnyInputFlag && !sawLavfiFormat) {
                outArgs.add("-i")
                outArgs.add(inputTokenProvider())
                autoBoundInputs++
            }

            // Track expected duration if user specified -ss / -to / -t
            var stepSsSec: Double? = null
            var stepToSec: Double? = null
            var stepTSec: Double? = null

            var i = 0
            var pendingInputFormat: String? = null
            while (i < cleanedTokens.size) {
                val tok = cleanedTokens[i]
                val lower = tok.lowercase(Locale.US)

                // Handle `-i <input>`
                if (lower == "-i" && i + 1 < cleanedTokens.size) {
                    val rawIn = cleanedTokens[i + 1]
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
                    i += 2
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
                    outArgs.add(tok)
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
                    outArgs.add(tok)
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
                    outArgs.add(tok)
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
                    outArgs.add(tok)
                    outArgs.add(fixedParams)
                    i += 2
                    continue
                }

                // Track trim/duration flags for accurate live progress bar
                if ((lower == "-ss" || lower == "-to" || lower == "-t") && i + 1 < cleanedTokens.size) {
                    val sec = FfmpegMath.parseTimestampToSeconds(cleanedTokens[i + 1])
                    when (lower) {
                        "-ss" -> if (stepSsSec == null) stepSsSec = sec
                        "-to" -> stepToSec = sec
                        "-t" -> stepTSec = sec
                    }
                    outArgs.add(tok)
                    outArgs.add(cleanedTokens[i + 1])
                    i += 2
                    continue
                }

                // Known 0-argument boolean flag
                if (lower in ZERO_ARG_FLAGS) {
                    outArgs.add(tok)
                    i += 1
                    continue
                }

                // Any other `-option` (takes 1 argument unless next token is another `-flag` or final output)
                if (tok.startsWith("-") && tok.length > 1) {
                    val nextTok = cleanedTokens.getOrNull(i + 1)
                    val nextIsOption = nextTok != null && nextTok.startsWith("-") && nextTok.length > 1 &&
                        nextTok.toDoubleOrNull() == null
                    val nextIsForcedOutput = nextTok != null &&
                        i + 1 == cleanedTokens.lastIndex &&
                        lower !in NON_OUTPUT_VALUE_FLAGS &&
                        looksLikeOutputTarget(nextTok)

                    if (nextTok == null || nextIsOption || nextIsForcedOutput) {
                        outArgs.add(tok)
                        i += 1
                    } else {
                        outArgs.add(tok)
                        outArgs.add(nextTok)
                        i += 2
                    }
                    continue
                }

                // Unconsumed non-flag token -> OUTPUT target!
                hasAnyOutput = true
                when {
                    isWindowsNullDevice(tok) -> {
                        outArgs.add("/dev/null")
                        mappedWinNull = true
                    }
                    tok == "/dev/null" || tok == "-" -> {
                        outArgs.add(tok)
                    }
                    else -> {
                        val safeOutName = extractSafeFileName(tok) ?: defaultOutputFileName
                        val fullOutPath = formatWorkPath(workDirPath, safeOutName, isPreview)
                        outArgs.add(fullOutPath)
                        stepOutputs.add(safeOutName)
                        producedFiles.add(safeOutName)
                        hasNonNullOutput = true
                    }
                }
                i += 1
            }

            // If user pasted flags only (no output file at all), auto-append a sensible output file
            if (!hasAnyOutput) {
                val fallbackName = if (prep.commandSegments.size > 1 && stepIndex < prep.commandSegments.lastIndex) {
                    "step_${stepIndex + 1}_$defaultOutputFileName"
                } else {
                    defaultOutputFileName
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
                autoAddedOutputFileName = autoAddedOut
            )
        )
    }

    fun formatPreview(plan: CustomCommandPlan): String {
        if (plan.steps.isEmpty()) return ""
        return plan.steps.joinToString("\n&& ") { step ->
            "ffmpeg " + step.args.joinToString(" ") { arg ->
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
        val expandedVariablesCount: Int
    )

    private fun preprocessAiPaste(raw: String): PreprocessedResult {
        var strippedMarkdownOrProse = false
        var joinedMultiline = false
        var ignoredShellCleanup = 0
        var expandedVars = 0

        // 1. Normalize line endings, non-breaking spaces, and smart quotes pasted from AI chats
        var text = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u00A0', ' ')
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')

        // 2. If Markdown fenced code blocks (``` ... ```) are present, extract only fenced blocks
        if (text.contains("```")) {
            val extracted = extractMarkdownCodeBlocks(text)
            if (extracted.isNotBlank()) {
                text = extracted
                strippedMarkdownOrProse = true
            }
        }

        // 3. Clean comment lines & join Unix `\`, Windows `^`, and PowerShell `` ` `` line continuations
        val rawLines = text.lines()
        val logicalLines = mutableListOf<String>()
        val pendingLine = StringBuilder()

        for (originalLine in rawLines) {
            val trimmed = originalLine.trim()
            if (trimmed.isEmpty()) {
                if (pendingLine.isNotEmpty()) {
                    logicalLines.add(pendingLine.toString().trim())
                    pendingLine.clear()
                }
                continue
            }

            // Skip pure comment lines (# ..., // ..., REM ...)
            if (trimmed.startsWith("#") ||
                trimmed.startsWith("//") ||
                trimmed.startsWith("REM ", ignoreCase = true) ||
                trimmed.equals("REM", ignoreCase = true)
            ) {
                strippedMarkdownOrProse = true
                continue
            }

            // Strip leading shell prompts (`$ `, `> `, `PS> `, `C:\Users> `) and trailing inline `# comments`
            val withoutPrompt = SHELL_PROMPT_REGEX.replace(trimmed, "").trim()
            val withoutInlineComment = stripInlineShellComment(withoutPrompt).trim()
            if (withoutInlineComment != trimmed) {
                strippedMarkdownOrProse = true
            }
            if (withoutInlineComment.isEmpty()) continue

            // Detect line continuation at end of line (`\`, `^`, `` ` ``)
            val hasContinuation = withoutInlineComment.endsWith("\\") ||
                withoutInlineComment.endsWith("^") ||
                withoutInlineComment.endsWith("`")

            val piece = if (hasContinuation) {
                joinedMultiline = true
                withoutInlineComment.dropLast(1).trimEnd()
            } else {
                withoutInlineComment
            }

            // If user pasted indented flags on the next line without trailing `\`, attach to previous line
            // (ignore Markdown bullets like `- Uses H.264` where `-` is followed by whitespace)
            if (pendingLine.isEmpty() &&
                piece.length > 1 &&
                piece[0] == '-' &&
                !piece[1].isWhitespace() &&
                logicalLines.isNotEmpty()
            ) {
                val prev = logicalLines.removeAt(logicalLines.lastIndex)
                joinedMultiline = true
                if (hasContinuation) {
                    pendingLine.append(prev).append(' ').append(piece)
                } else {
                    logicalLines.add("$prev $piece")
                }
                continue
            }

            if (pendingLine.isNotEmpty()) {
                pendingLine.append(' ')
            }
            pendingLine.append(piece)

            if (!hasContinuation) {
                logicalLines.add(pendingLine.toString().trim())
                pendingLine.clear()
            }
        }
        if (pendingLine.isNotEmpty()) {
            logicalLines.add(pendingLine.toString().trim())
        }

        // 4. Extract simple shell variable assignments & expand variables in command lines
        val shellVars = linkedMapOf<String, String>()
        val candidateLines = mutableListOf<String>()
        for (line in logicalLines) {
            val assignMatch = VAR_ASSIGN_REGEX.matchEntire(line)
            if (assignMatch != null && !line.contains(" -i ") && !line.startsWith("ffmpeg", ignoreCase = true)) {
                val varName = assignMatch.groupValues[1]
                val rawVal = assignMatch.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("'")
                if (varName.uppercase(Locale.US) !in RESERVED_INPUT_VARS || rawVal.isNotBlank()) {
                    // Avoid self-referential `IN=$IN`
                    if (rawVal != "\$$varName" && rawVal != "\${$varName}") {
                        shellVars[varName] = rawVal
                        continue
                    }
                }
            }
            candidateLines.add(line)
        }

        // 5. If any explicit `ffmpeg` command exists, drop surrounding AI prose lines cleanly
        // (even if prose mentions the word "FFmpeg" without Markdown code fences)
        val hasExplicitFfmpegLine = candidateLines.any { isExplicitFfmpegCommandLine(it) }
        val filteredLines = if (hasExplicitFfmpegLine) {
            candidateLines.filter { line ->
                val firstWord = line.substringBefore(' ').trim()
                val isCliFlagLine = line.length > 1 && line[0] == '-' && !line[1].isWhitespace()
                val keep = isExplicitFfmpegCommandLine(line) ||
                    isCliFlagLine ||
                    firstWord.lowercase(Locale.US) in IGNORED_SHELL_COMMANDS
                if (!keep) strippedMarkdownOrProse = true
                keep
            }
        } else {
            candidateLines
        }

        // 6. Split each line on shell command separators (`&&`, `||`, `;`, single `&`) outside quotes
        val segments = mutableListOf<List<String>>()
        for (line in filteredLines) {
            val (expandedLine, count) = expandShellVariables(line, shellVars)
            expandedVars += count
            val splitParts = splitShellCommandLine(expandedLine)
            for (part in splitParts) {
                val normalizedPart = normalizeAccidentalUnicodeDashes(part)
                val tokens = tokenizeCommandArgs(normalizedPart)
                if (tokens.isEmpty()) continue

                val firstLower = tokens.first().lowercase(Locale.US)
                if (firstLower in IGNORED_SHELL_COMMANDS) {
                    ignoredShellCleanup++
                    continue
                }
                // Drop `ffprobe` lines if an AI pasted a probe + encode pair
                if (firstLower == "ffprobe" || firstLower == "ffprobe.exe") {
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
            expandedVariablesCount = expandedVars
        )
    }

    /**
     * Returns true only when a line genuinely invokes `ffmpeg` (directly, after a wrapper like
     * `sudo`/`time`/`nohup`, or after inline `KEY=VAL` env vars) — preventing plain AI prose
     * such as "İşte FFmpeg komutu:" from ever being mistaken for an executable command.
     */
    private fun isExplicitFfmpegCommandLine(line: String): Boolean {
        val parts = line.trim().split(Regex("\\s+"))
        var idx = 0
        while (idx < parts.size) {
            val token = parts[idx]
            val lower = token.lowercase(Locale.US)
            if (FFMPEG_BIN_REGEX.matches(token)) return true
            if (lower in setOf("sudo", "time", "nohup") ||
                Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$").matches(token)
            ) {
                idx++
                continue
            }
            return false
        }
        return false
    }

    private fun extractMarkdownCodeBlocks(text: String): String {
        val result = StringBuilder()
        var inBlock = false
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inBlock = !inBlock
                continue
            }
            if (inBlock) {
                result.append(line).append('\n')
            }
        }
        return result.toString().trim()
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

    /**
     * Splits a command line on `&&`, `||`, `;`, or standalone `&` outside quotes while preserving
     * `2>&1` and `1>&2` redirections so they can be stripped cleanly later.
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
                    if (prev == '>') {
                        // Part of `2>&1` or `>&2` redirection — keep intact
                        current.append(ch)
                    } else if (next == '>') {
                        // Part of `&>/dev/null` redirection — keep intact
                        current.append(ch)
                    } else if (next == '&') {
                        segments.add(current.toString().trim())
                        current.clear()
                        i++
                    } else {
                        // Windows single `&` command chaining (`ffmpeg ... NUL & ffmpeg ... out.mp4`)
                        segments.add(current.toString().trim())
                        current.clear()
                    }
                }
                !inSingle && !inDouble && ch == '|' && line.getOrNull(i + 1) == '|' -> {
                    segments.add(current.toString().trim())
                    current.clear()
                    i++
                }
                !inSingle && !inDouble && ch == ';' -> {
                    segments.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) segments.add(tail)
        return segments.filter { it.isNotEmpty() }
    }

    /** Replaces accidental typographic en/em dashes (`–crf`, `—preset`) pasted from rich text with `-`. */
    private fun normalizeAccidentalUnicodeDashes(segment: String): String {
        return segment
            .replace(Regex("(^|\\s)[–—]([A-Za-z])"), "$1-$2")
    }

    /** Strips trailing `# comment` outside quotes only when preceded by whitespace. */
    private fun stripInlineShellComment(line: String): String {
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
                ch == '#' && !inSingle && !inDouble && i > 0 && line[i - 1].isWhitespace() -> {
                    return line.substring(0, i).trimEnd()
                }
            }
            i++
        }
        return line
    }

    /**
     * POSIX + Windows-path-safe argument tokenizer:
     * Preserves backslashes inside double quotes for Windows paths (e.g. `"C:\Users\Ali\out.mp4"`)
     * unless escaping `"`, `\`, or `$`.
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
                    if (inDouble && next != '"' && next != '\\' && next != '$') {
                        // Preserve Windows path separator inside double quotes
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

    /** Strips shell redirections (`2>&1`, `>/dev/null`, `2>NUL`, `> log.txt`) which are invalid in argv[]. */
    private fun stripShellRedirections(tokens: List<String>): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            val lower = tok.lowercase(Locale.US)
            when {
                lower == "2>&1" || lower == "1>&2" ||
                    lower.startsWith("2>/dev/null") || lower.startsWith(">/dev/null") ||
                    lower.startsWith("&>/dev/null") || lower.startsWith("1>/dev/null") ||
                    lower.startsWith("2>nul") || lower.startsWith(">nul") -> {
                    i += 1
                }
                lower == ">" || lower == ">>" || lower == "1>" || lower == "2>" || lower == "&>" -> {
                    // Skip operator + its redirected log file target
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
        val lower = tok.trim().lowercase(Locale.US)
        return lower == "nul" || lower == "nul:" || lower == "null:"
    }

    private fun looksLikeOutputTarget(tok: String): Boolean {
        if (tok == "/dev/null" || tok == "-" || isWindowsNullDevice(tok)) return true
        val cleaned = tok.trim().trim('<', '>')
        return MEDIA_OUTPUT_EXT_REGEX.matches(cleaned)
    }

    /**
     * Converts desktop/tutorial paths (`C:\Users\Ali\Desktop\out.mp4`, `~/out.mp4`, `<output.mp4>`,
     * `/path/to/result.mkv`) into a clean filename safe for Android's sandbox workDir.
     */
    private fun extractSafeFileName(raw: String): String? {
        val unwrapped = raw.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removePrefix("<")
            .removeSuffix(">")
            .removeSurrounding("[", "]")
            .trim()
        if (unwrapped.isEmpty() || unwrapped == "." || unwrapped == "..") return null
        val afterSlash = unwrapped.substringAfterLast('/').substringAfterLast('\\').trim()
        if (afterSlash.isEmpty() || afterSlash == "." || afterSlash == "..") return null
        // Guard against accidental path traversal characters
        val sanitized = afterSlash.replace("..", "_")
        return sanitized.takeIf { it.isNotBlank() }
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
