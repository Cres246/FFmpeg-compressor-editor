#!/usr/bin/env bash
# Builds a custom ARM64-v8a FFmpegKit Full-GPL AAR optimized with:
#   - Compiler optimization: -O3 (baseline armv8-a ISA, zero SIGILL on older ARMv8.0 CPUs)
#   - Runtime-dispatched SIMD (getauxval): NEON + DotProd + I8MM in FFmpeg, libx264, and libx265
#   - 16 KB ELF page-size alignment (-Wl,-z,max-page-size=16384) for Android 15/16
#
# Output:
#   app/libs/ffmpeg-kit-custom-arm64-gpl.aar
#   app/libs/ffmpeg-kit-custom-arm64-gpl.aar.info.txt

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${FFMPEGKIT_WORK_DIR:-/tmp/ffmpegkit-arm64-simd-build}"
OUT_DIR="${REPO_ROOT}/app/libs"
OUT_AAR="${OUT_DIR}/ffmpeg-kit-custom-arm64-gpl.aar"
OUT_INFO="${OUT_DIR}/ffmpeg-kit-custom-arm64-gpl.aar.info.txt"
FFMPEGKIT_REPO_URL="${FFMPEGKIT_REPO_URL:-https://github.com/ffmpegkit-maintained/ffmpeg.git}"

echo "=================================================================="
echo " FFmpeg Compressor & Editor — Custom ARM64-O3 SIMD FFmpegKit Build"
echo "=================================================================="

# 1. Resolve Android SDK & NDK
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
if [[ ! -d "${ANDROID_SDK_ROOT}" ]]; then
  echo "[error] ANDROID_SDK_ROOT not found at ${ANDROID_SDK_ROOT}" >&2
  exit 1
fi

if [[ -z "${ANDROID_NDK_ROOT:-}" ]] || [[ ! -d "${ANDROID_NDK_ROOT}" ]]; then
  # Prefer NDK 27.x / 28.x / 26.x already installed on GitHub Actions ubuntu-latest
  DETECTED_NDK="$(ls -d "${ANDROID_SDK_ROOT}"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)"
  if [[ -n "${DETECTED_NDK}" ]] && [[ -d "${DETECTED_NDK}" ]]; then
    export ANDROID_NDK_ROOT="${DETECTED_NDK}"
  else
    echo "[ndk] No preinstalled NDK found; installing ndk;27.2.12479018 via sdkmanager..."
    SDKMANAGER="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
    yes | "${SDKMANAGER}" "ndk;27.2.12479018" >/dev/null
    export ANDROID_NDK_ROOT="${ANDROID_SDK_ROOT}/ndk/27.2.12479018"
  fi
fi

echo "[env] ANDROID_SDK_ROOT = ${ANDROID_SDK_ROOT}"
echo "[env] ANDROID_NDK_ROOT = ${ANDROID_NDK_ROOT}"

# 2. Ensure host build tools are installed (Debian/Ubuntu)
if command -v apt-get >/dev/null 2>&1; then
  echo "[deps] Installing native cross-compilation tools..."
  sudo apt-get update -qq
  sudo apt-get install -y -qq --no-install-recommends \
    autoconf automake autopoint libtool pkg-config curl git cmake ninja-build \
    nasm yasm texinfo meson gperf bison flex python3 unzip zip binutils >/dev/null
fi

# 3. Clone maintained FFmpegKit fork
rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}" "${OUT_DIR}"
echo "[git] Cloning ${FFMPEGKIT_REPO_URL}..."
git clone --depth=1 "${FFMPEGKIT_REPO_URL}" "${WORK_DIR}/ffmpeg-kit"
cd "${WORK_DIR}/ffmpeg-kit"

if [[ ! -f "./android.sh" ]]; then
  echo "[error] android.sh not found in cloned repository!" >&2
  exit 1
fi
chmod +x ./android.sh

# 4. Apply surgical ARM64-O3 + runtime-safe NEON/DotProd/I8MM patches
echo "[patch] Applying ARM64-O3 runtime-dispatched SIMD patches..."
python3 "${REPO_ROOT}/scripts/patch_ffmpegkit_arm64_simd.py" --repo "${WORK_DIR}/ffmpeg-kit"

# 5. Dynamically select supported flags from ./android.sh --help (zero unknown-option crashes)
HELP_TEXT="$(./android.sh --help 2>&1 || true)"
BUILD_FLAGS=()

add_flag_if_supported() {
  local flag="$1"
  local probe="${flag%%=*}"
  if grep -q -- "${probe}" <<< "${HELP_TEXT}"; then
    BUILD_FLAGS+=("${flag}")
  else
    echo "[flags] Skipping unsupported option: ${flag}"
  fi
}

# Target arm64-v8a exclusively for speed + compact APK
add_flag_if_supported "--api-level=24"
add_flag_if_supported "--speed"
add_flag_if_supported "--disable-arm-v7a"
add_flag_if_supported "--disable-arm-v7a-neon"
add_flag_if_supported "--disable-x86"
add_flag_if_supported "--disable-x86-64"

# Core GPL + full codec suite required by all 11 modes + Custom Commands
add_flag_if_supported "--enable-gpl"
add_flag_if_supported "--enable-x264"
add_flag_if_supported "--enable-x265"
add_flag_if_supported "--enable-lame"
add_flag_if_supported "--enable-opus"
add_flag_if_supported "--enable-libvorbis"
add_flag_if_supported "--enable-libvpx"
add_flag_if_supported "--enable-dav1d"
add_flag_if_supported "--enable-libwebp"
add_flag_if_supported "--enable-zimg"
add_flag_if_supported "--enable-android-zlib"
add_flag_if_supported "--enable-android-media-codec"

echo "[build] Running ./android.sh ${BUILD_FLAGS[*]}"
./android.sh "${BUILD_FLAGS[@]}"

# 6. Locate produced AAR
BUILT_AAR="$(find "${WORK_DIR}/ffmpeg-kit/prebuilt" "${WORK_DIR}/ffmpeg-kit/android" \
  -maxdepth 5 -type f -name '*.aar' 2>/dev/null | head -n 1 || true)"

if [[ -z "${BUILT_AAR}" ]] || [[ ! -f "${BUILT_AAR}" ]]; then
  echo "[error] FFmpegKit build finished but no .aar artifact was found!" >&2
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

# Gate 1: Java API classes present
if ! unzip -l "${VERIFY_DIR}/classes.jar" | grep -q "com/arthenica/ffmpegkit/FFmpegKit.class"; then
  echo "[error] classes.jar is missing com/arthenica/ffmpegkit/FFmpegKit.class!" >&2
  exit 1
fi
if ! unzip -l "${VERIFY_DIR}/classes.jar" | grep -q "com/arthenica/ffmpegkit/FFprobeKit.class"; then
  echo "[error] classes.jar is missing com/arthenica/ffmpegkit/FFprobeKit.class!" >&2
  exit 1
fi

# Gate 2: arm64-v8a native libraries present
ARM64_JNI_DIR="${VERIFY_DIR}/jni/arm64-v8a"
if [[ ! -f "${ARM64_JNI_DIR}/libffmpegkit.so" ]] || [[ ! -f "${ARM64_JNI_DIR}/libavcodec.so" ]]; then
  echo "[error] Missing required arm64-v8a .so files in ${ARM64_JNI_DIR}!" >&2
  ls -la "${ARM64_JNI_DIR}" 2>/dev/null || true
  exit 1
fi

# Gate 3: 16 KB ELF page-size alignment check across all bundled .so files
echo "[verify] Checking 16 KB ELF page alignment across arm64-v8a .so libraries..."
python3 - "${ARM64_JNI_DIR}" <<'PYEOF'
import pathlib, subprocess, sys

jni_dir = pathlib.Path(sys.argv[1])
failed = False
for so_file in sorted(jni_dir.glob("*.so")):
    out = subprocess.check_output(["readelf", "-lW", str(so_file)], text=True, errors="replace")
    for line in out.splitlines():
        parts = line.split()
        if parts and parts[0] == "LOAD":
            align_val = int(parts[-1], 16)
            if align_val < 16384 or (align_val % 16384) != 0:
                print(f"[error] {so_file.name} has non-16KB LOAD alignment: {parts[-1]} ({align_val})", file=sys.stderr)
                failed = True
if failed:
    sys.exit(1)
print("[verify] All arm64-v8a .so libraries passed 16 KB ELF page alignment check.")
PYEOF

# Gate 4: Capability report (non-fatal telemetry)
SIMD_REPORT="$(strings "${ARM64_JNI_DIR}"/*.so 2>/dev/null | grep -iE 'DotProd|I8MM|Neon_DotProd|Neon_I8MM|using cpu capabilities' | sort -u | head -n 12 || true)"
AAR_MB="$(awk "BEGIN { printf \"%.2f\", ${AAR_SIZE} / 1048576 }")"

cp "${BUILT_AAR}" "${OUT_AAR}"
{
  echo "engine=ARM64-O3-SIMD"
  echo "aar_size_mb=${AAR_MB}"
  echo "ndk=${ANDROID_NDK_ROOT}"
  echo "flags=${BUILD_FLAGS[*]}"
  echo "simd_symbols_found:"
  echo "${SIMD_REPORT:-"(standard NEON)"}"
} > "${OUT_INFO}"

echo "=================================================================="
echo " SUCCESS: Built & verified ${OUT_AAR} (${AAR_MB} MB)"
cat "${OUT_INFO}"
echo "=================================================================="
