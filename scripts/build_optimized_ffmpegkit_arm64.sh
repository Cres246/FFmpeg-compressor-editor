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

# 4b. Upgrade NDK compiler generation to newest official Google NDK r29/r30 (Clang 21) when
#     the Nix profile ships an older NDK (e.g. r27d · Clang 18), matching Termux's Clang 21
#     AArch64 vectorizer/codegen while keeping Nix's reproducible tool dependencies intact.
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
SDKMANAGER="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
NIX_NDK_MAJOR="$(sed -nE 's/^android-r([0-9]+).*/\1/p' <<< "${NIX_PROFILE}" | head -n 1 || true)"
NIX_NDK_MAJOR="${NIX_NDK_MAJOR:-0}"
OVERRIDE_NDK_VER=""

if [[ -z "${FFMPEGKIT_OVERRIDE_NDK_ROOT:-}" ]] && [[ "${FFMPEGKIT_DISABLE_NDK_UPGRADE:-0}" != "1" ]] && (( NIX_NDK_MAJOR < 29 )) && [[ -x "${SDKMANAGER}" ]]; then
  echo "[ndk] Nix profile ${NIX_PROFILE} uses NDK r${NIX_NDK_MAJOR}; probing Google SDK repo for NDK r29/r30 (Clang 20/21)..."
  yes | "${SDKMANAGER}" --licenses >/dev/null 2>&1 || true
  BEST_NDK_PKG="$(python3 - "${SDKMANAGER}" "${ANDROID_SDK_ROOT}" <<'PYEOF'
import pathlib, re, subprocess, sys

sdkmanager = sys.argv[1]
sdk_root = pathlib.Path(sys.argv[2])

candidates = set()
# Include already-installed host NDKs
ndk_dir = sdk_root / "ndk"
if ndk_dir.is_dir():
    for d in ndk_dir.iterdir():
        if d.is_dir() and (d / "source.properties").is_file():
            candidates.add(f"ndk;{d.name}")

# Query remote sdkmanager catalog for newest NDK r28+/r29+/r30+
try:
    out = subprocess.check_output([sdkmanager, "--list"], text=True, errors="replace", timeout=90)
    for m in re.finditer(r"\b(ndk;(\d+)\.(\d+)\.(\d+)(?:-[A-Za-z0-9.]+)?)\b", out):
        candidates.add(m.group(1))
except Exception:
    pass

def parse_ver(pkg: str):
    ver = pkg.split(";", 1)[1]
    # Prefer stable releases over beta/rc unless no stable exists for that major
    is_prerelease = 1 if "-" in ver else 0
    nums = [int(x) for x in re.findall(r"\d+", ver.split("-", 1)[0])]
    while len(nums) < 3:
        nums.append(0)
    return (nums[0], 1 - is_prerelease, nums[1], nums[2], ver)

# Accept NDK r28+ (Clang 19+), strongly preferring r29/r30 (Clang 20/21)
valid = [p for p in candidates if parse_ver(p)[0] >= 28]
if valid:
    best = sorted(valid, key=parse_ver)[-1]
    print(best)
PYEOF
)"
  if [[ -n "${BEST_NDK_PKG}" ]]; then
    BEST_NDK_VER="${BEST_NDK_PKG#ndk;}"
    CANDIDATE_NDK_ROOT="${ANDROID_SDK_ROOT}/ndk/${BEST_NDK_VER}"
    if [[ ! -f "${CANDIDATE_NDK_ROOT}/source.properties" ]]; then
      echo "[ndk] Installing ${BEST_NDK_PKG} via sdkmanager..."
      yes | "${SDKMANAGER}" "${BEST_NDK_PKG}" >/dev/null || true
    fi
    if [[ -f "${CANDIDATE_NDK_ROOT}/source.properties" ]] && [[ -x "${CANDIDATE_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang" ]]; then
      export FFMPEGKIT_OVERRIDE_NDK_ROOT="${CANDIDATE_NDK_ROOT}"
      OVERRIDE_NDK_VER="${BEST_NDK_VER}"
      CLANG_VER_LINE="$("${CANDIDATE_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --version 2>/dev/null | head -n 1 || true)"
      echo "[ndk] Upgraded active compiler to Google Android NDK ${OVERRIDE_NDK_VER}: ${CLANG_VER_LINE}"
    else
      echo "[ndk] WARN: ${BEST_NDK_PKG} installation incomplete; keeping Nix profile ${NIX_PROFILE}."
    fi
  else
    echo "[ndk] INFO: No newer NDK r28+ found in sdkmanager; using Nix profile ${NIX_PROFILE}."
  fi
fi

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

# Mandatory GPL + core encoders required by our 11 modes
# (arthenica/ffmpeg-kit-next uses `--enable-lib-<name>` naming)
MISSING_MANDATORY=()
add_flag_if_supported "--enable-gpl" || MISSING_MANDATORY+=("--enable-gpl")
add_first_supported "--enable-lib-x264" "--enable-x264" "--enable-libx264" || MISSING_MANDATORY+=("--enable-lib-x264")
add_first_supported "--enable-lib-x265" "--enable-x265" "--enable-libx265" || MISSING_MANDATORY+=("--enable-lib-x265")
add_first_supported "--enable-lib-lame" "--enable-lame" "--enable-libmp3lame" || MISSING_MANDATORY+=("--enable-lib-lame")

if (( ${#MISSING_MANDATORY[@]} > 0 )); then
  echo "[error] Missing mandatory codec flags in upstream ffmpeg-kit-next: ${MISSING_MANDATORY[*]}" >&2
  echo "[debug] Sample --enable-* tokens found in upstream scripts:" >&2
  grep -oE -- '--enable-[a-zA-Z0-9_-]+' <<< "${HELP_TEXT}" | sort -u | head -n 60 >&2 || true
  exit 1
fi

# Full codec suite required by all 11 modes + Custom Commands
add_first_supported "--enable-lib-android-zlib" "--enable-android-zlib" || true
add_first_supported "--enable-lib-android-media-codec" "--enable-android-media-codec" || true
add_first_supported "--enable-lib-opus" "--enable-opus" "--enable-libopus" || true
add_first_supported "--enable-lib-libvorbis" "--enable-libvorbis" "--enable-vorbis" || true
add_first_supported "--enable-lib-libvpx" "--enable-libvpx" "--enable-vpx" || true
add_first_supported "--enable-lib-dav1d" "--enable-dav1d" "--enable-libdav1d" || true
add_first_supported "--enable-lib-libwebp" "--enable-libwebp" "--enable-webp" || true
add_first_supported "--enable-lib-zimg" "--enable-zimg" "--enable-libzimg" || true

echo "[flags] Final build flags (${#BUILD_FLAGS[@]}): ${BUILD_FLAGS[*]}"

# Proactively ensure host Android SDK licenses & required compileSdk/buildTools exist
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
if [[ -d "${ANDROID_SDK_ROOT}" ]]; then
  echo "[sdk] Host ANDROID_SDK_ROOT = ${ANDROID_SDK_ROOT}"
  SDKMANAGER="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
  if [[ -x "${SDKMANAGER}" ]]; then
    yes | "${SDKMANAGER}" --licenses >/dev/null 2>&1 || true
    REQ_SDK_PKGS="$(python3 - "${WORK_DIR}/ffmpeg-kit-next/android" <<'PYEOF'
import pathlib, re, sys
root = pathlib.Path(sys.argv[1])
if not root.is_dir():
    sys.exit(0)
pkgs = set()
for f in root.rglob("build.gradle*"):
    text = f.read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(r"compileSdk(?:Version)?\s*[= ]+\s*(\d+)", text):
        pkgs.add(f"platforms;android-{m.group(1)}")
    for m in re.finditer(r'buildToolsVersion\s*[= ]+\s*["\']([0-9.]+)["\']', text):
        pkgs.add(f"build-tools;{m.group(1)}")
for p in sorted(pkgs):
    print(p)
PYEOF
)"
    if [[ -n "${REQ_SDK_PKGS}" ]]; then
      while IFS= read -r pkg; do
        [[ -z "${pkg}" ]] && continue
        pkg_rel="${pkg//;//}"
        if [[ ! -d "${ANDROID_SDK_ROOT}/${pkg_rel}" ]]; then
          echo "[sdk] Installing missing Android SDK component: ${pkg}"
          yes | "${SDKMANAGER}" "${pkg}" >/dev/null || true
        fi
      done <<< "${REQ_SDK_PKGS}"
    fi
  fi
fi

run_nix_android_build() {
  set +e
  if [[ "${USE_DOUBLE_DASH}" == "true" ]]; then
    echo "[build] Running ./nix-android.sh -p ${NIX_PROFILE} -- ${BUILD_FLAGS[*]}"
    ./nix-android.sh -p "${NIX_PROFILE}" -- "${BUILD_FLAGS[@]}"
    BUILD_RC=$?
  else
    echo "[build] Running ./nix-android.sh -p ${NIX_PROFILE} ${BUILD_FLAGS[*]}"
    ./nix-android.sh -p "${NIX_PROFILE}" "${BUILD_FLAGS[@]}"
    BUILD_RC=$?
  fi
  set -e
}

run_nix_android_build

# Automatic bulletproof fallback: if the upgraded NDK r29/r30 (Clang 21) build fails before
# compiling native libraries (e.g. a future upstream library incompatibility), cleanly retry
# once with Nix's pinned NDK profile while keeping all -O3 / -Wl,--lto-O3 / SIMD / dav1d patches.
if (( BUILD_RC != 0 )) && [[ -n "${FFMPEGKIT_OVERRIDE_NDK_ROOT:-}" ]]; then
  BUILT_FFMPEGKIT_CHECK="$(find "${WORK_DIR}/ffmpeg-kit-next/prebuilt" "${WORK_DIR}/ffmpeg-kit-next/android" \
    -type f -name 'libffmpegkit.so' 2>/dev/null | head -n 1 || true)"
  BUILT_AVCODEC_CHECK="$(find "${WORK_DIR}/ffmpeg-kit-next/prebuilt" "${WORK_DIR}/ffmpeg-kit-next/android" \
    -type f -name 'libavcodec.so' 2>/dev/null | head -n 1 || true)"
  if [[ -z "${BUILT_FFMPEGKIT_CHECK}" ]] || [[ -z "${BUILT_AVCODEC_CHECK}" ]]; then
    echo "==================================================================" >&2
    echo " [warn] Upgraded NDK (${OVERRIDE_NDK_VER}) build failed (rc=${BUILD_RC})." >&2
    if [[ -f "${WORK_DIR}/ffmpeg-kit-next/build.log" ]]; then
      echo " === NDK override build.log (last 80 lines) ===" >&2
      tail -n 80 "${WORK_DIR}/ffmpeg-kit-next/build.log" >&2 || true
    fi
    echo " [fallback] Cleaning partial artifacts and retrying with Nix profile ${NIX_PROFILE}..." >&2
    echo "==================================================================" >&2
    unset FFMPEGKIT_OVERRIDE_NDK_ROOT
    OVERRIDE_NDK_VER=""
    rm -rf "${WORK_DIR}/ffmpeg-kit-next/prebuilt" "${WORK_DIR}/ffmpeg-kit-next/android/libs" "${WORK_DIR}/ffmpeg-kit-next/android/obj"
    run_nix_android_build
  fi
fi

if (( BUILD_RC != 0 )); then
  echo "==================================================================" >&2
  echo " [warn] ./nix-android.sh exited with code ${BUILD_RC}." >&2
  if [[ -f "${WORK_DIR}/ffmpeg-kit-next/build.log" ]]; then
    echo " === build.log (last 120 lines) ===" >&2
    tail -n 120 "${WORK_DIR}/ffmpeg-kit-next/build.log" >&2 || true
  fi
  echo "==================================================================" >&2

  # Check if all native arm64-v8a .so libraries actually compiled 100% and only Gradle AAR
  # archiving inside the Nix shell failed (common due to read-only /nix/store SDK, aapt2, or JDK).
  BUILT_FFMPEGKIT_SO="$(find "${WORK_DIR}/ffmpeg-kit-next/prebuilt" "${WORK_DIR}/ffmpeg-kit-next/android" \
    -type f -name 'libffmpegkit.so' 2>/dev/null | head -n 1 || true)"
  BUILT_AVCODEC_SO="$(find "${WORK_DIR}/ffmpeg-kit-next/prebuilt" "${WORK_DIR}/ffmpeg-kit-next/android" \
    -type f -name 'libavcodec.so' 2>/dev/null | head -n 1 || true)"

  if [[ -z "${BUILT_FFMPEGKIT_SO}" ]] || [[ -z "${BUILT_AVCODEC_SO}" ]]; then
    echo "[error] Native arm64-v8a libraries did not finish compiling; aborting." >&2
    exit "${BUILD_RC}"
  fi

  echo "[aar-recovery] Native libraries compiled 100%:"
  echo "  - ${BUILT_FFMPEGKIT_SO}"
  echo "  - ${BUILT_AVCODEC_SO}"
  echo "[aar-recovery] Retrying Gradle AAR packaging outside Nix using host Android SDK..."

  ANDROID_GRADLE_DIR="${WORK_DIR}/ffmpeg-kit-next/android"
  if [[ ! -d "${ANDROID_GRADLE_DIR}" ]]; then
    echo "[error] Expected Gradle directory not found at ${ANDROID_GRADLE_DIR}" >&2
    exit "${BUILD_RC}"
  fi

  # Ensure arm64-v8a .so files are present inside the library module's jniLibs/libs directory
  ANDROID_EXISTING_SO="$(find "${ANDROID_GRADLE_DIR}" -type f -name 'libffmpegkit.so' 2>/dev/null | head -n 1 || true)"
  if [[ -z "${ANDROID_EXISTING_SO}" ]]; then
    LIB_MOD_DIR="$(find "${ANDROID_GRADLE_DIR}" -maxdepth 2 -type d -name 'ffmpeg-kit*lib*' 2>/dev/null | head -n 1 || true)"
    if [[ -n "${LIB_MOD_DIR}" ]]; then
      TARGET_JNI_DIR="${LIB_MOD_DIR}/src/main/jniLibs/arm64-v8a"
      if [[ -d "${ANDROID_GRADLE_DIR}/libs" ]] || grep -q '\.\./libs' "${LIB_MOD_DIR}/build.gradle"* 2>/dev/null; then
        TARGET_JNI_DIR="${ANDROID_GRADLE_DIR}/libs/arm64-v8a"
      fi
      mkdir -p "${TARGET_JNI_DIR}"
      echo "[aar-recovery] Staging compiled arm64-v8a .so files into ${TARGET_JNI_DIR}..."
      find "${WORK_DIR}/ffmpeg-kit-next/prebuilt" -type f -name '*.so' -exec cp -f {} "${TARGET_JNI_DIR}/" \;
    fi
  fi

  printf 'sdk.dir=%s\n' "${ANDROID_SDK_ROOT}" > "${ANDROID_GRADLE_DIR}/local.properties"

  # Try host JAVA_HOME first, then fallback to JDK 17 / JDK 11 if upstream Gradle wrapper needs older JDK
  JDK_CANDIDATES=()
  [[ -n "${JAVA_HOME:-}" ]] && JDK_CANDIDATES+=("${JAVA_HOME}")
  [[ -n "${JAVA_HOME_17_X64:-}" ]] && JDK_CANDIDATES+=("${JAVA_HOME_17_X64}")
  [[ -d "/usr/lib/jvm/temurin-17-jdk-amd64" ]] && JDK_CANDIDATES+=("/usr/lib/jvm/temurin-17-jdk-amd64")
  [[ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]] && JDK_CANDIDATES+=("/usr/lib/jvm/java-17-openjdk-amd64")
  [[ -n "${JAVA_HOME_21_X64:-}" ]] && JDK_CANDIDATES+=("${JAVA_HOME_21_X64}")

  GRADLE_OK="false"
  pushd "${ANDROID_GRADLE_DIR}" >/dev/null
  [[ -f "./gradlew" ]] && chmod +x ./gradlew
  for candidate_jdk in "${JDK_CANDIDATES[@]}"; do
    [[ ! -d "${candidate_jdk}" ]] && continue
    echo "[aar-recovery] Running Gradle assembleRelease with JAVA_HOME=${candidate_jdk}..."
    set +e
    if [[ -x "./gradlew" ]]; then
      JAVA_HOME="${candidate_jdk}" PATH="${candidate_jdk}/bin:${PATH}" \
        ./gradlew assembleRelease --no-daemon --stacktrace
      G_RC=$?
    else
      JAVA_HOME="${candidate_jdk}" PATH="${candidate_jdk}/bin:${PATH}" \
        gradle assembleRelease --no-daemon --stacktrace
      G_RC=$?
    fi
    set -e
    if (( G_RC == 0 )); then
      GRADLE_OK="true"
      break
    fi
    echo "[aar-recovery] Gradle failed with JAVA_HOME=${candidate_jdk} (rc=${G_RC}); trying next JDK if available..." >&2
  done
  popd >/dev/null

  if [[ "${GRADLE_OK}" != "true" ]]; then
    echo "[error] Both Nix archive step and host-SDK Gradle AAR packaging failed!" >&2
    exit 1
  fi
fi

# 6. Locate produced AAR (prefer prebuilt/bundle-android-aar, then release AAR under android/)
BUILT_AAR="$(find "${WORK_DIR}/ffmpeg-kit-next/prebuilt" -maxdepth 5 -type f -name '*.aar' 2>/dev/null | head -n 1 || true)"
if [[ -z "${BUILT_AAR}" ]]; then
  BUILT_AAR="$(find "${WORK_DIR}/ffmpeg-kit-next/android" -maxdepth 6 -type f -name '*-release.aar' ! -name '*test*' 2>/dev/null | head -n 1 || true)"
fi
if [[ -z "${BUILT_AAR}" ]]; then
  BUILT_AAR="$(find "${WORK_DIR}/ffmpeg-kit-next/android" -maxdepth 6 -type f -name '*.aar' ! -name '*test*' 2>/dev/null | head -n 1 || true)"
fi

if [[ -z "${BUILT_AAR}" ]] || [[ ! -f "${BUILT_AAR}" ]]; then
  echo "[error] FFmpegKit Next build finished but no .aar artifact was found!" >&2
  find "${WORK_DIR}/ffmpeg-kit-next" -maxdepth 6 -name '*.aar' 2>/dev/null || true
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
    echo "[debug] Sample classes in classes.jar:" >&2
    grep -E '\.class$' <<< "${CLASSES_LIST}" | head -n 30 >&2 || true
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

# Gate 4: Extract compiled FFmpeg version + compiler + configuration + SIMD capability telemetry
if [[ -f "${WORK_DIR}/ffmpeg-kit-next/build.log" ]]; then
  echo "[verify] === Build log FFmpeg/NDK verification lines ==="
  grep -E '\[(ndk-override|ffmpeg|ffmpeg-verify|dav1d|libvpx|x264|x265)\]' "${WORK_DIR}/ffmpeg-kit-next/build.log" | tail -n 50 || true
fi
DETECTED_FFMPEG_VER="$(grep -oE '^[0-9]+\.[0-9]+(\.[0-9]+)?(-[A-Za-z0-9._]+)?$' <<< "$(strings "${ARM64_JNI_DIR}/libavutil.so" 2>/dev/null || true)" | grep -E '^[7-9]\.|^[1-9][0-9]\.' | head -n 1 || true)"
DETECTED_CLANG_VER="$(readelf -p .comment "${ARM64_JNI_DIR}/libavcodec.so" 2>/dev/null | grep -i 'clang' | head -n 1 | sed 's/^[[:space:]]*\[[^]]*\][[:space:]]*//' || true)"
if [[ -z "${DETECTED_CLANG_VER}" ]]; then
  DETECTED_CLANG_VER="$(readelf -p .comment "${ARM64_JNI_DIR}/libffmpegkit.so" 2>/dev/null | grep -i 'clang' | head -n 1 | sed 's/^[[:space:]]*\[[^]]*\][[:space:]]*//' || true)"
fi
DETECTED_FFMPEG_CONF="$(strings "${ARM64_JNI_DIR}/libavutil.so" 2>/dev/null | grep -E '^--' | head -n 1 || true)"
SIMD_REPORT="$(grep -iE 'DotProd|I8MM|Neon_DotProd|Neon_I8MM|using cpu capabilities' <<< "${CODEC_STRINGS}" | sort -u | head -n 12 || true)"
AAR_MB="$(awk "BEGIN { printf \"%.2f\", ${AAR_SIZE} / 1048576 }")"

cp "${BUILT_AAR}" "${OUT_AAR}"
{
  echo "engine=FFmpegKit-Next-ARM64-O3-LTO3-SIMD"
  echo "upstream_repo=${FFMPEGKIT_REPO_URL}"
  echo "upstream_commit=${UPSTREAM_COMMIT}"
  echo "detected_ffmpeg_version=${DETECTED_FFMPEG_VER:-unknown}"
  echo "nix_profile=${NIX_PROFILE}"
  echo "effective_ndk=${OVERRIDE_NDK_VER:-nix-${NIX_PROFILE}}"
  echo "detected_clang=${DETECTED_CLANG_VER:-unknown}"
  echo "aar_size_mb=${AAR_MB}"
  echo "bundles_smart_exception=${BUNDLES_SMART_EXCEPTION}"
  echo "flags=${BUILD_FLAGS[*]}"
  echo "ffmpeg_configuration=${DETECTED_FFMPEG_CONF:-unknown}"
  echo "simd_symbols_found:"
  echo "${SIMD_REPORT:-"(standard NEON)"}"
} > "${OUT_INFO}"

echo "=================================================================="
echo " SUCCESS: Built & verified ${OUT_AAR} (${AAR_MB} MB)"
cat "${OUT_INFO}"
echo "=================================================================="
