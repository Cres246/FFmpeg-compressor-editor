#!/usr/bin/env bash
# Builds a single official ARM64-v8a Full-GPL AAR from arthenica/ffmpeg-kit-next (FFmpeg 9.0.x)
# using its reproducible Nix build system (./nix-android.sh), enhanced with:
#   - Compiler optimization: -O3 (baseline armv8-a ISA, zero SIGILL on older ARMv8.0 CPUs)
#   - Runtime-dispatched SIMD (getauxval): NEON + DotProd + I8MM in FFmpeg, libx264, and libx265
#   - 16 KB ELF page-size alignment (-Wl,-z,max-page-size=16384) verified across every .so
#   - Strict 4-gate post-build verification (API classes, GPL codecs, 16KB ELF, version/SIMD info)
#
# Output:
#   app/libs/ffmpeg-kit-custom-arm64-gpl.aar
#   app/libs/ffmpeg-kit-custom-arm64-gpl.aar.info.txt

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${FFMPEGKIT_WORK_DIR:-/tmp/ffmpegkit-next-arm64-build}"
OUT_DIR="${REPO_ROOT}/app/libs"
OUT_AAR="${OUT_DIR}/ffmpeg-kit-custom-arm64-gpl.aar"
OUT_INFO="${OUT_DIR}/ffmpeg-kit-custom-arm64-gpl.aar.info.txt"
FFMPEGKIT_REPO_URL="${FFMPEGKIT_REPO_URL:-https://github.com/arthenica/ffmpeg-kit-next.git}"
FFMPEGKIT_REF="${FFMPEGKIT_REF:-}"

echo "=================================================================="
echo " FFmpeg Compressor & Editor — FFmpegKit Next (ARM64-O3 SIMD) Build"
echo "=================================================================="

# 1. Ensure Nix is on PATH (required by arthenica/ffmpeg-kit-next ./nix-android.sh)
if ! command -v nix >/dev/null 2>&1; then
  for profile_script in \
    "/nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh" \
    "${HOME}/.nix-profile/etc/profile.d/nix.sh"; do
    if [[ -f "${profile_script}" ]]; then
      # shellcheck source=/dev/null
      source "${profile_script}"
      break
    fi
  done
fi

if ! command -v nix >/dev/null 2>&1; then
  echo "[error] Nix package manager is required to build arthenica/ffmpeg-kit-next, but 'nix' was not found on PATH." >&2
  exit 1
fi

export NIXPKGS_ALLOW_UNFREE=1
echo "[env] nix = $(nix --version)"
echo "[env] repo = ${FFMPEGKIT_REPO_URL}"

# 2. Clone arthenica/ffmpeg-kit-next
rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}" "${OUT_DIR}"
echo "[git] Cloning ${FFMPEGKIT_REPO_URL}..."
if [[ -n "${FFMPEGKIT_REF}" ]]; then
  git clone --depth=1 --branch "${FFMPEGKIT_REF}" "${FFMPEGKIT_REPO_URL}" "${WORK_DIR}/ffmpeg-kit-next"
else
  git clone --depth=1 "${FFMPEGKIT_REPO_URL}" "${WORK_DIR}/ffmpeg-kit-next"
fi
cd "${WORK_DIR}/ffmpeg-kit-next"

UPSTREAM_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
UPSTREAM_LOG="$(git log -1 --oneline 2>/dev/null || echo unknown)"
echo "[git] Upstream HEAD: ${UPSTREAM_LOG}"

if [[ ! -f "./nix-android.sh" ]]; then
  echo "[error] ./nix-android.sh not found in ${FFMPEGKIT_REPO_URL}!" >&2
  ls -la
  exit 1
fi
chmod +x ./nix-android.sh
[[ -f "./android.sh" ]] && chmod +x ./android.sh

# 3. Apply surgical ARM64-O3 + runtime-dispatched SIMD patches (baseline armv8-a, zero SIGILL)
echo "[patch] Applying ARM64-O3 runtime-dispatched SIMD patches..."
python3 "${REPO_ROOT}/scripts/patch_ffmpegkit_arm64_simd.py" --repo "${WORK_DIR}/ffmpeg-kit-next"

# 4. Auto-discover the newest Android NDK r27+/r28+ Nix profile from ./nix-android.sh --list-profiles
PROFILES_RAW="$(./nix-android.sh --list-profiles 2>&1 || true)"
echo "[nix] Available profiles:"
echo "${PROFILES_RAW}"

if [[ -n "${FFMPEGKIT_NIX_PROFILE:-}" ]]; then
  NIX_PROFILE="${FFMPEGKIT_NIX_PROFILE}"
else
  NIX_PROFILE="$(python3 - "${PROFILES_RAW}" <<'PYEOF'
import re, sys

text = sys.argv[1]
# Extract tokens like android-r27d, android-r28, android-r27c, android-r26d
profiles = sorted(set(re.findall(r"\bandroid-r\d+[a-z0-9._-]*\b", text)))
if not profiles:
    # Fallback: any token starting with android-
    profiles = sorted(set(re.findall(r"\bandroid-[a-z0-9._-]+\b", text)))

def sort_key(name: str):
    m = re.match(r"android-r(\d+)([a-z]*)", name)
    if m:
        return (2, int(m.group(1)), m.group(2), name)
    return (1, 0, "", name)

if profiles:
    # Prefer r27+ (native 16KB ELF alignment + full ARM64 dotprod/i8mm Clang support), choosing newest
    r27_plus = [p for p in profiles if sort_key(p)[1] >= 27]
    chosen = sorted(r27_plus or profiles, key=sort_key)[-1]
    print(chosen)
PYEOF
)"
fi

if [[ -z "${NIX_PROFILE}" ]]; then
  echo "[error] Could not auto-detect an Android Nix profile from './nix-android.sh --list-profiles'!" >&2
  exit 1
fi
echo "[nix] Selected Android NDK profile: ${NIX_PROFILE}"

# 5. Detect whether ./nix-android.sh uses `--` separator and capture the REAL android.sh --help
#    output from inside Nix (plus scripts/function*.sh where display_help & library options live)
USE_DOUBLE_DASH="$(python3 - "./nix-android.sh" <<'PYEOF'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
has_double_dash = bool(
    re.search(r"^\s*--\)", text, flags=re.MULTILINE)
    or re.search(r'["\']--["\']', text)
)
print("true" if has_double_dash else "false")
PYEOF
)"

HELP_TEXT=""
if [[ "${USE_DOUBLE_DASH}" == "true" ]]; then
  NIX_HELP="$(./nix-android.sh -p "${NIX_PROFILE}" -- --help 2>&1 || true)"
  if grep -q -- "--enable-" <<< "${NIX_HELP}"; then
    HELP_TEXT+="${NIX_HELP}"$'\n'
  else
    ALT_HELP="$(./nix-android.sh -p "${NIX_PROFILE}" --help 2>&1 || true)"
    if grep -q -- "--enable-" <<< "${ALT_HELP}"; then
      USE_DOUBLE_DASH="false"
      HELP_TEXT+="${ALT_HELP}"$'\n'
    fi
  fi
else
  NIX_HELP="$(./nix-android.sh -p "${NIX_PROFILE}" --help 2>&1 || true)"
  if grep -q -- "--enable-" <<< "${NIX_HELP}"; then
    HELP_TEXT+="${NIX_HELP}"$'\n'
  else
    ALT_HELP="$(./nix-android.sh -p "${NIX_PROFILE}" -- --help 2>&1 || true)"
    if grep -q -- "--enable-" <<< "${ALT_HELP}"; then
      USE_DOUBLE_DASH="true"
      HELP_TEXT+="${ALT_HELP}"$'\n'
    fi
  fi
fi

# Also include upstream shell definitions (android.sh + scripts/function*.sh) so library flags
# defined inside helper scripts are always visible.
for helper_file in ./android.sh ./scripts/function-android.sh ./scripts/function.sh ./scripts/function-common.sh; do
  if [[ -f "${helper_file}" ]]; then
    HELP_TEXT+="$(cat "${helper_file}" 2>/dev/null || true)"$'\n'
  fi
done
HELP_TEXT+="$(./nix-android.sh --help 2>&1 || true)"$'\n'

BUILD_FLAGS=()

supports_flag() {
  local flag="$1"
  local probe="${flag%%=*}"
  grep -qF -- "${probe}" <<< "${HELP_TEXT}"
}

add_flag_if_supported() {
  local flag="$1"
  if supports_flag "${flag}"; then
    BUILD_FLAGS+=("${flag}")
    return 0
  else
    echo "[flags] Skipping unsupported option: ${flag}"
    return 1
  fi
}

add_first_supported() {
  local candidate
  for candidate in "$@"; do
    if supports_flag "${candidate}"; then
      BUILD_FLAGS+=("${candidate}")
      return 0
    fi
  done
  return 1
}

# Target arm64-v8a exclusively for fast compile + compact APK
add_flag_if_supported "--api-level=24" || true
add_flag_if_supported "--speed" || true

DISABLED_ABIS=0
for abi in "arm-v7a" "arm-v7a-neon" "x86" "x86-64"; do
  if add_first_supported "--disable-arch-${abi}" "--disable-${abi}"; then
    DISABLED_ABIS=$((DISABLED_ABIS + 1))
  fi
done
echo "[flags] Pruned ${DISABLED_ABIS} non-arm64 ABI(s)."
if (( DISABLED_ABIS < 3 )); then
  echo "[error] Expected to disable arm-v7a, x86, and x86-64, but only matched ${DISABLED_ABIS} disable flags!" >&2
  exit 1
fi

# Mandatory GPL + core encoders required by our 11 modes (supports both short and lib-prefixed names)
MISSING_MANDATORY=()
add_flag_if_supported "--enable-gpl" || MISSING_MANDATORY+=("--enable-gpl")
add_first_supported "--enable-x264" "--enable-libx264" || MISSING_MANDATORY+=("--enable-x264")
add_first_supported "--enable-x265" "--enable-libx265" || MISSING_MANDATORY+=("--enable-x265")
add_first_supported "--enable-lame" "--enable-libmp3lame" || MISSING_MANDATORY+=("--enable-lame")

if (( ${#MISSING_MANDATORY[@]} > 0 )); then
  echo "[error] Missing mandatory codec flags in upstream ffmpeg-kit-next: ${MISSING_MANDATORY[*]}" >&2
  echo "[debug] Sample --enable-* tokens found in upstream scripts:" >&2
  grep -oE -- '--enable-[a-zA-Z0-9_-]+' <<< "${HELP_TEXT}" | sort -u | head -n 60 >&2 || true
  exit 1
fi

# Full codec suite required by all 11 modes + Custom Commands
add_flag_if_supported "--enable-android-zlib" || true
add_flag_if_supported "--enable-android-media-codec" || true
add_first_supported "--enable-opus" "--enable-libopus" || true
add_first_supported "--enable-libvorbis" "--enable-vorbis" || true
add_first_supported "--enable-libvpx" "--enable-vpx" || true
add_first_supported "--enable-dav1d" "--enable-libdav1d" || true
add_first_supported "--enable-libwebp" "--enable-webp" || true
add_first_supported "--enable-zimg" "--enable-libzimg" || true

echo "[flags] Final build flags (${#BUILD_FLAGS[@]}): ${BUILD_FLAGS[*]}"

if [[ "${USE_DOUBLE_DASH}" == "true" ]]; then
  echo "[build] Running ./nix-android.sh -p ${NIX_PROFILE} -- ${BUILD_FLAGS[*]}"
  ./nix-android.sh -p "${NIX_PROFILE}" -- "${BUILD_FLAGS[@]}"
else
  echo "[build] Running ./nix-android.sh -p ${NIX_PROFILE} ${BUILD_FLAGS[*]}"
  ./nix-android.sh -p "${NIX_PROFILE}" "${BUILD_FLAGS[@]}"
fi

# 6. Locate produced AAR (prefer prebuilt/bundle-android-aar)
BUILT_AAR="$(find "${WORK_DIR}/ffmpeg-kit-next/prebuilt" -maxdepth 5 -type f -name '*.aar' 2>/dev/null | head -n 1 || true)"
if [[ -z "${BUILT_AAR}" ]]; then
  BUILT_AAR="$(find "${WORK_DIR}/ffmpeg-kit-next/android" -maxdepth 6 -type f -name '*.aar' ! -name '*test*' 2>/dev/null | head -n 1 || true)"
fi

if [[ -z "${BUILT_AAR}" ]] || [[ ! -f "${BUILT_AAR}" ]]; then
  echo "[error] FFmpegKit Next build finished but no .aar artifact was found!" >&2
  find "${WORK_DIR}/ffmpeg-kit-next" -maxdepth 4 -name '*.aar' 2>/dev/null || true
  exit 1
fi

echo "[verify] Inspecting built AAR: ${BUILT_AAR}"
AAR_SIZE="$(stat -c%s "${BUILT_AAR}")"
if (( AAR_SIZE < 2000000 )); then
  echo "[error] Built AAR is unexpectedly small (${AAR_SIZE} bytes)!" >&2
  exit 1
fi

VERIFY_DIR="${WORK_DIR}/verify-aar"
rm -rf "${VERIFY_DIR}"
mkdir -p "${VERIFY_DIR}"
unzip -q "${BUILT_AAR}" -d "${VERIFY_DIR}"

# Gate 1: Verify all required Java API classes our app imports
CLASSES_LIST="$(unzip -l "${VERIFY_DIR}/classes.jar")"
for req_class in \
  "com/arthenica/ffmpegkit/FFmpegKit.class" \
  "com/arthenica/ffmpegkit/FFprobeKit.class" \
  "com/arthenica/ffmpegkit/FFmpegKitConfig.class" \
  "com/arthenica/ffmpegkit/LogRedirectionStrategy.class" \
  "com/arthenica/ffmpegkit/ReturnCode.class" \
  "com/arthenica/ffmpegkit/StreamInformation.class"; do
  if ! grep -qF "${req_class}" <<< "${CLASSES_LIST}"; then
    echo "[error] classes.jar is missing required API class: ${req_class}!" >&2
    exit 1
  fi
done

BUNDLES_SMART_EXCEPTION="false"
if grep -q "com/arthenica/smartexception/" <<< "${CLASSES_LIST}"; then
  BUNDLES_SMART_EXCEPTION="true"
fi
echo "[verify] Gate 1 passed (all 6 Java API classes present; bundles_smart_exception=${BUNDLES_SMART_EXCEPTION})."

# Gate 2: Verify arm64-v8a native libraries + mandatory GPL encoders (libx264, libx265, libmp3lame)
ARM64_JNI_DIR="${VERIFY_DIR}/jni/arm64-v8a"
if [[ ! -f "${ARM64_JNI_DIR}/libffmpegkit.so" ]] || [[ ! -f "${ARM64_JNI_DIR}/libavcodec.so" ]]; then
  echo "[error] Missing required arm64-v8a .so files in ${ARM64_JNI_DIR}!" >&2
  ls -la "${ARM64_JNI_DIR}" 2>/dev/null || true
  exit 1
fi

CODEC_STRINGS="$(strings "${ARM64_JNI_DIR}"/*.so 2>/dev/null || true)"
for req_codec in "libx264" "libx265" "libmp3lame"; do
  if ! grep -qF "${req_codec}" <<< "${CODEC_STRINGS}"; then
    echo "[error] Compiled arm64-v8a .so libraries do not contain required encoder '${req_codec}'!" >&2
    exit 1
  fi
done
echo "[verify] Gate 2 passed (arm64-v8a .so + libx264, libx265, libmp3lame verified)."

# Gate 3: 16 KB ELF page-size alignment check across EVERY bundled arm64-v8a .so library
echo "[verify] Checking 16 KB ELF page alignment across arm64-v8a .so libraries..."
python3 - "${ARM64_JNI_DIR}" <<'PYEOF'
import pathlib, subprocess, sys

jni_dir = pathlib.Path(sys.argv[1])
failed = False
count = 0
for so_file in sorted(jni_dir.glob("*.so")):
    count += 1
    out = subprocess.check_output(["readelf", "-lW", str(so_file)], text=True, errors="replace")
    for line in out.splitlines():
        parts = line.split()
        if parts and parts[0] == "LOAD":
            align_val = int(parts[-1], 16)
            if align_val < 16384 or (align_val % 16384) != 0:
                print(f"[error] {so_file.name} has non-16KB LOAD alignment: {parts[-1]} ({align_val})", file=sys.stderr)
                failed = True
if failed or count == 0:
    sys.exit(1)
print(f"[verify] Gate 3 passed: all {count} arm64-v8a .so libraries have 16 KB ELF alignment.")
PYEOF

# Gate 4: Extract compiled FFmpeg version + SIMD capability telemetry
DETECTED_FFMPEG_VER="$(grep -oE '^[0-9]+\.[0-9]+(\.[0-9]+)?(-[A-Za-z0-9._]+)?$' <<< "$(strings "${ARM64_JNI_DIR}/libavutil.so" 2>/dev/null || true)" | grep -E '^[7-9]\.|^[1-9][0-9]\.' | head -n 1 || true)"
SIMD_REPORT="$(grep -iE 'DotProd|I8MM|Neon_DotProd|Neon_I8MM|using cpu capabilities' <<< "${CODEC_STRINGS}" | sort -u | head -n 12 || true)"
AAR_MB="$(awk "BEGIN { printf \"%.2f\", ${AAR_SIZE} / 1048576 }")"

cp "${BUILT_AAR}" "${OUT_AAR}"
{
  echo "engine=FFmpegKit-Next-ARM64-O3-SIMD"
  echo "upstream_repo=${FFMPEGKIT_REPO_URL}"
  echo "upstream_commit=${UPSTREAM_COMMIT}"
  echo "detected_ffmpeg_version=${DETECTED_FFMPEG_VER:-unknown}"
  echo "nix_profile=${NIX_PROFILE}"
  echo "aar_size_mb=${AAR_MB}"
  echo "bundles_smart_exception=${BUNDLES_SMART_EXCEPTION}"
  echo "flags=${BUILD_FLAGS[*]}"
  echo "simd_symbols_found:"
  echo "${SIMD_REPORT:-"(standard NEON)"}"
} > "${OUT_INFO}"

echo "=================================================================="
echo " SUCCESS: Built & verified ${OUT_AAR} (${AAR_MB} MB)"
cat "${OUT_INFO}"
echo "=================================================================="
