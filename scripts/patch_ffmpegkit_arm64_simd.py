#!/usr/bin/env python3
"""
Surgical ARM64-O3 + LTO-O3 + Zero-PLT + Runtime-Dispatched SIMD (NEON / DotProd / I8MM)
+ NDK Override + ffkitsaf 256KB Read Buffer patcher for arthenica/ffmpeg-kit-next (FFmpeg 9.0.x).

Safety invariants (ZERO SIGILL on older ARMv8.0 devices, ZERO encoding quality loss):
  - Global CFLAGS/CXXFLAGS remain strictly baseline `armv8-a` (never global `+dotprod`/`+i8mm`),
    upgraded from BOTH `-Os` and `-O2` (used by `--speed`) to `-O3` for maximum throughput.
  - LLD LTO backend codegen is explicitly upgraded to `-Wl,--lto-O3` (fixing ld.lld's default
    `--lto-O2` fallback that previously left libavcodec/libswscale C decode loops at `-O2`!),
    while stripping premature `-flto`/`-fuse-ld=lld` from initial CFLAGS so FFmpeg `./configure`
    compiler/assembler probes run cleanly before `--enable-lto` takes over.
  - Shared-library call overhead is minimized via `-fno-semantic-interposition`,
    `-fomit-frame-pointer`, and `-Wl,-Bsymbolic-functions` across ALL arm64-v8a libraries
    (including `libavcodec`, `libswscale`, `libffmpegkit` fftools, `libx264`, `libx265`,
    `libdav1d`, and `libvpx`).
  - Advanced SIMD instructions (`DotProd`, `I8MM`) are enabled ONLY via runtime-dispatched
    per-function assembly (`.arch_extension dotprod` / `.arch_extension i8mm`) or per-file
    CMake targets guarded by Linux/Android `getauxval(AT_HWCAP / AT_HWCAP2)`.
  - `dav1d` enables full ARM64 NEON/DotProd/I8MM assembly (`-Denable_asm=true` on arm64-v8a
    instead of upstream's `-Denable_asm=false`), and `libvpx` drops `--enable-small`.
  - `ffkitsaf:` non-streamed reads use 256 KB AVIO read blocks (matching writes) to cut
    Android SAF/FUSE `read()` kernel transitions by 8x.
  - `android.sh` honors `FFMPEGKIT_OVERRIDE_NDK_ROOT` (after Nix shellHook) so CI can compile
    with official Google Android NDK r29/r30 (Clang 21, matching Termux) while keeping Nix's
    reproducible build tools intact.
  - 16 KB ELF page-size alignment (`-Wl,-z,max-page-size=16384`) is verified/enforced for
    Android 15/16 compliance.
  - All existing 10-bit HEVC (`HIGH_BIT_DEPTH`/`MAIN10`) flags in upstream scripts are preserved.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


# Reusable bash snippet injected right before ./configure, cmake, or meson inside arm64-v8a
# builds so even flags exported outside function-android.sh are upgraded cleanly to -O3 + zero-PLT.
ARM64_RUNTIME_FLAG_SANITIZER_TEMPLATE = """
# {marker}
if [[ "${{ARCH:-}}" == "arm64-v8a" || "${{FFMPEG_KIT_ARCH:-}}" == "arm64-v8a" ]]; then
  if [[ -n "${{CFLAGS:-}}" ]]; then
    CFLAGS="$(printf '%s' "${{CFLAGS}}" | sed -E 's/(^|[[:space:]])-O[s2]([[:space:]]|$)/\\1-O3\\2/g')"
  fi
  case " ${{CFLAGS:-}} " in
    *" -O3 "*) ;;
    *) CFLAGS="${{CFLAGS:+${{CFLAGS}} }}-O3" ;;
  esac
  case " ${{CFLAGS:-}} " in
    *" -fno-semantic-interposition "*) ;;
    *) CFLAGS="${{CFLAGS}} -fno-semantic-interposition" ;;
  esac
  case " ${{CFLAGS:-}} " in
    *" -fomit-frame-pointer "*) ;;
    *) CFLAGS="${{CFLAGS}} -fomit-frame-pointer" ;;
  esac
  export CFLAGS

  if [[ -n "${{CXXFLAGS:-}}" ]]; then
    CXXFLAGS="$(printf '%s' "${{CXXFLAGS}}" | sed -E 's/(^|[[:space:]])-O[s2]([[:space:]]|$)/\\1-O3\\2/g')"
    case " ${{CXXFLAGS}} " in
      *" -O3 "*) ;;
      *) CXXFLAGS="${{CXXFLAGS}} -O3" ;;
    esac
    case " ${{CXXFLAGS}} " in
      *" -fno-semantic-interposition "*) ;;
      *) CXXFLAGS="${{CXXFLAGS}} -fno-semantic-interposition" ;;
    esac
    case " ${{CXXFLAGS}} " in
      *" -fomit-frame-pointer "*) ;;
      *) CXXFLAGS="${{CXXFLAGS}} -fomit-frame-pointer" ;;
    esac
    export CXXFLAGS
  fi

  case " ${{LDFLAGS:-}} " in
    *" -Wl,-Bsymbolic-functions "*) ;;
    *) LDFLAGS="${{LDFLAGS:+${{LDFLAGS}} }}-Wl,-Bsymbolic-functions" ;;
  esac
  export LDFLAGS
  echo "[{tag}] Effective arm64-v8a CFLAGS=${{CFLAGS}}"
  echo "[{tag}] Effective arm64-v8a LDFLAGS=${{LDFLAGS}}"
fi
"""


def find_first(root: Path, relative_hint: str, filename: str) -> Path | None:
    direct = root / relative_hint
    if direct.is_file():
        return direct
    for candidate in sorted(root.rglob(filename)):
        if ".git" not in candidate.parts and candidate.is_file():
            return candidate
    return None


def patch_android_sh(root: Path) -> None:
    """
    Allows build_optimized_ffmpegkit_arm64.sh to seamlessly supply a newer Google Android NDK
    (e.g. NDK r29/r30 · Clang 21) via FFMPEGKIT_OVERRIDE_NDK_ROOT while keeping Nix's
    reproducible tool dependencies (bison, meson, ninja, yasm, nasm, pkg-config, etc.).
    Also injects safe Clang 19-21 warning-downgrade flags for legacy C external libraries.
    """
    target = find_first(root, "android.sh", "android.sh")
    if target is None:
        print("[patch] WARN: android.sh not found, skipping NDK override hook.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    marker = "# FFMPEGKIT-OVERRIDE-NDK-HOOK"
    if marker not in updated:
        hook = (
            f"\n{marker}\n"
            'if [[ -n "${FFMPEGKIT_OVERRIDE_NDK_ROOT:-}" && -f "${FFMPEGKIT_OVERRIDE_NDK_ROOT}/source.properties" ]]; then\n'
            '  export ANDROID_NDK_ROOT="${FFMPEGKIT_OVERRIDE_NDK_ROOT}"\n'
            '  export ANDROID_NDK_HOME="${ANDROID_NDK_ROOT}"\n'
            '  export ANDROID_NDK="${ANDROID_NDK_ROOT}"\n'
            '  unset ANDROID_TOOLCHAIN\n'
            '  _CLEAN_PATH=""\n'
            '  IFS=":" read -r -a _PATH_PARTS <<< "${PATH}"\n'
            '  for _P in "${_PATH_PARTS[@]}"; do\n'
            '    case "${_P}" in\n'
            '      */ndk/*/toolchains/llvm/prebuilt/*/bin|*/ndk/[0-9]*) ;;\n'
            '      "") ;;\n'
            '      *) _CLEAN_PATH="${_CLEAN_PATH:+${_CLEAN_PATH}:}${_P}" ;;\n'
            '    esac\n'
            '  done\n'
            '  export PATH="${_CLEAN_PATH}:${ANDROID_NDK_ROOT}:${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64/bin"\n'
            '  echo "[ndk-override] Switched ANDROID_NDK_ROOT -> ${ANDROID_NDK_ROOT}"\n'
            '  echo "[ndk-override] Active compiler: $(command -v aarch64-linux-android24-clang 2>/dev/null || echo not-found)"\n'
            '  aarch64-linux-android24-clang --version 2>/dev/null | head -n 1 || true\n'
            'fi\n'
        )
        m = re.search(r"^[ \t]*export[ \t]+DETECTED_NDK_VERSION=", updated, flags=re.MULTILINE)
        if m:
            updated = updated[: m.start()] + hook + "\n" + updated[m.start() :]
        else:
            m2 = re.search(r"^#!/[^\n]*\n", updated)
            if m2:
                updated = updated[: m2.end()] + hook + "\n" + updated[m2.end() :]

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(f"[patch] OK: Added FFMPEGKIT_OVERRIDE_NDK_ROOT hook to {target.relative_to(root)}")
    else:
        print(f"[patch] INFO: {target.relative_to(root)} already has NDK override hook.")


def patch_function_android(root: Path) -> None:
    target = find_first(root, "scripts/function-android.sh", "function-android.sh")
    if target is None:
        print("[patch] WARN: function-android.sh not found, skipping CFLAGS/LDFLAGS patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    # 1. Upgrade BOTH standalone -Os AND -O2 (used by `--speed`) -> -O3 while preserving
    #    baseline armv8-a ISA (zero SIGILL). Lookbehind/lookahead avoids linker flags like -Wl,-O2.
    updated = re.sub(r"(?<=[\s\"'])-O[s2](?=[\s\"'])", "-O3", updated)

    # 2. Clean arm64-v8a ffmpeg CFLAGS: remove premature ${LINK_TIME_OPTIMIZATION_FLAGS} and
    #    -fuse-ld=lld from get_size_optimization_cflags (FFmpeg's --enable-lto cleanly adds
    #    -flto AFTER configure probes finish, and -fuse-ld=lld is a linker flag, not a compile flag).
    updated = re.sub(
        r'ARCH_OPTIMIZATION="\$\{LINK_TIME_OPTIMIZATION_FLAGS\}\s+-fuse-ld=lld\s+-O3\s+-ffunction-sections\s+-fdata-sections"',
        'ARCH_OPTIMIZATION="-O3 -ffunction-sections -fdata-sections"',
        updated,
    )

    # 3. Upgrade arm64-v8a ffmpeg LDFLAGS so ld.lld runs LTO backend codegen at TRUE -O3
    #    (`-Wl,--lto-O3`) instead of ld.lld's default `--lto-O2`!
    if "--lto-O3" not in updated:
        updated = re.sub(
            r'(-Wl,--gc-sections\s+\$\{LINK_TIME_OPTIMIZATION_FLAGS\}\s+-fuse-ld=lld\s+-O3\b)',
            r'\1 -Wl,--lto-O3',
            updated,
        )

    # 4. Inject -fno-semantic-interposition -fomit-frame-pointer into arm64-v8a flags AND
    #    into Application.mk APP_CFLAGS (so libffmpegkit.so fftools benefits too).
    #    Also add Clang 19-21 C compatibility flags for legacy third-party C libs.
    if "fno-semantic-interposition" not in updated:
        updated_arm64 = re.sub(
            r"(?<=[\s\"'])(-march=armv8-a)(?=[\s\"'])",
            r"\1 -fno-semantic-interposition -fomit-frame-pointer",
            updated,
        )
        if updated_arm64 != updated:
            updated = updated_arm64
        else:
            updated = re.sub(
                r"(?<=[\s\"'])(-fstrict-aliasing)(?=[\s\"'])",
                r"\1 -fno-semantic-interposition -fomit-frame-pointer",
                updated,
            )

    if "APP_CFLAGS :=" in updated and "fno-semantic-interposition" not in updated.split("APP_CFLAGS :=")[1].splitlines()[0]:
        updated = re.sub(
            r"(APP_CFLAGS\s*:=\s*-O3\b)",
            r"\1 -fno-semantic-interposition -fomit-frame-pointer",
            updated,
            count=1,
        )

    # Ensure Clang 19-21 stricter C23/C17 error promotions don't break legacy C external libraries
    # (libmp3lame, libvorbis, etc.) while keeping zero codegen overhead.
    if "-Wno-error=incompatible-pointer-types" not in updated:
        updated = re.sub(
            r"(?<=[\s\"'])(-fstrict-aliasing)(?=[\s\"'])",
            r"\1 -Wno-error=incompatible-pointer-types -Wno-error=implicit-function-declaration -Wno-error=int-conversion",
            updated,
        )

    # 5. Enforce 16 KB ELF page-size alignment in linker flags if not already configured upstream.
    if "max-page-size=16384" not in updated:
        updated_ldflags = re.sub(
            r"(-Wl,--hash-style=both|-Wl,-z,relro)",
            r"\1 -Wl,-z,max-page-size=16384",
            updated,
        )
        if updated_ldflags != updated:
            updated = updated_ldflags
        else:
            updated = re.sub(
                r'(get_common_ldflags\(\)\s*\{[\s\S]{0,2000}?echo\s+"[^"]*)(")',
                r'\1 -Wl,-z,max-page-size=16384\2',
                updated,
                count=1,
            )

    # 6. Add -Wl,-Bsymbolic-functions to BOTH Application.mk (APP_LDFLAGS) AND
    #    get_arch_specific_ldflags() (arm64-v8a) — fixing the previous count=1 bug that only
    #    patched Application.mk and missed get_arch_specific_ldflags()!
    if "-Bsymbolic-functions" not in updated:
        updated = re.sub(
            r"(APP_LDFLAGS\s*:=\s*-Wl,--hash-style=both)",
            r"\1 -Wl,-Bsymbolic-functions",
            updated,
            count=1,
        )
        updated = re.sub(
            r"(-march=armv8-a(?:\s+-fno-semantic-interposition\s+-fomit-frame-pointer)?\s+-Wl,-z,max-page-size=16384)",
            r"\1 -Wl,-Bsymbolic-functions",
            updated,
        )
        # Fallback if upstream layout changed
        if updated.count("-Wl,-Bsymbolic-functions") < 2:
            updated = re.sub(
                r"(-Wl,-z,max-page-size=16384)(?!\s+-Wl,-Bsymbolic-functions)",
                r"\1 -Wl,-Bsymbolic-functions",
                updated,
            )

    # Clean accidental compiler-only flags that regex #4 may have inserted after -march=armv8-a in LDFLAGS
    updated = re.sub(
        r"(get_arch_specific_ldflags\(\)\s*\{[\s\S]{0,4000}?)(?=\n\})",
        lambda m: m.group(1)
        .replace(" -fno-semantic-interposition", "")
        .replace(" -fomit-frame-pointer", ""),
        updated,
        count=1,
    )

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(
            f"[patch] OK: Patched {target.relative_to(root)} "
            "(-O3, -Wl,--lto-O3, clean LTO CFLAGS, zero-PLT on both APP_LDFLAGS & arm64 LDFLAGS, Clang 21 compat)"
        )
    else:
        print(f"[patch] INFO: {target.relative_to(root)} already up to date.")


def patch_ffmpeg_script(root: Path) -> None:
    target = find_first(root, "scripts/android/ffmpeg.sh", "ffmpeg.sh")
    if target is None:
        print("[patch] WARN: scripts/android/ffmpeg.sh not found, skipping FFmpeg script patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    # Remove --enable-small (suppresses loop unrolling & SIMD inlining in libavcodec/libswscale)
    updated = re.sub(r"\s--enable-small\b", "", updated)
    # Upgrade any literal -Os or -O2 in ffmpeg.sh -> -O3
    updated = re.sub(r"(?<=[\s\"'])-O[s2](?=[\s\"'])", "-O3", updated)

    # Inject runtime flag sanitizer + explicit --as / --optflags / --extra-cflags / --extra-ldflags
    # + fixed dotprod/i8mm probe + post-configure config.mak/config.h telemetry.
    marker = "# FFmpeg-ARM64-SIMD-RUNTIME-DISPATCH"
    if marker not in updated:
        sanitizer = ARM64_RUNTIME_FLAG_SANITIZER_TEMPLATE.format(
            marker="FFmpeg-ARM64-O3-ZERO-PLT",
            tag="ffmpeg",
        )
        hook = (
            f"{sanitizer}\n"
            f"{marker}\n"
            'FFMPEG_EXTRA_SIMD_ARGS=()\n'
            'if [[ "${ARCH:-}" == "arm64-v8a" || "${FFMPEG_KIT_ARCH:-}" == "arm64-v8a" ]]; then\n'
            '  # Strip premature -flto/-fuse-ld=lld from env CFLAGS so FFmpeg ./configure compiler\n'
            '  # and inline-asm probes run cleanly on native object files; --enable-lto re-applies\n'
            '  # -flto cleanly after all probes pass, paired with -Wl,--lto-O3 in LDFLAGS.\n'
            '  CFLAGS="$(printf \'%s\' "${CFLAGS:-}" | sed -E \'s/(^|[[:space:]])-flto(=[^[:space:]]*)?([[:space:]]|$)/\\1\\3/g; s/(^|[[:space:]])-fuse-ld=lld([[:space:]]|$)/\\1\\2/g\')"\n'
            '  export CFLAGS\n'
            '  case " ${LDFLAGS:-} " in\n'
            '    *" -Wl,--lto-O3 "*) ;;\n'
            '    *) LDFLAGS="${LDFLAGS:+${LDFLAGS} }-Wl,--lto-O3" ;;\n'
            '  esac\n'
            '  export LDFLAGS\n'
            '  FFMPEG_HELP_OUT="$(./configure --help 2>/dev/null || true)"\n'
            '  FFMPEG_EXTRA_SIMD_ARGS+=("--enable-neon")\n'
            '  # FFmpeg ./configure --help lists --disable-dotprod / --disable-i8mm (not --enable-*)\n'
            '  if grep -qE -- \'--(en|dis)able-dotprod\\b\' <<< "${FFMPEG_HELP_OUT}"; then\n'
            '    FFMPEG_EXTRA_SIMD_ARGS+=("--enable-dotprod")\n'
            '  fi\n'
            '  if grep -qE -- \'--(en|dis)able-i8mm\\b\' <<< "${FFMPEG_HELP_OUT}"; then\n'
            '    FFMPEG_EXTRA_SIMD_ARGS+=("--enable-i8mm")\n'
            '  fi\n'
            '  if [[ "${AS:-}" == *clang* ]]; then\n'
            '    FFMPEG_EXTRA_SIMD_ARGS+=("--as=${AS}")\n'
            '  elif [[ -n "${CC:-}" ]]; then\n'
            '    FFMPEG_EXTRA_SIMD_ARGS+=("--as=${CC}")\n'
            '  fi\n'
            '  FFMPEG_EXTRA_SIMD_ARGS+=("--optflags=-O3 -fno-math-errno -fno-signed-zeros")\n'
            '  FFMPEG_EXTRA_SIMD_ARGS+=("--extra-cflags=-O3 -fno-semantic-interposition -fomit-frame-pointer")\n'
            '  FFMPEG_EXTRA_SIMD_ARGS+=("--extra-ldflags=-Wl,-Bsymbolic-functions -Wl,--lto-O3")\n'
            '  echo "[ffmpeg] Runtime-dispatched ARM64 SIMD & LTO-O3 configure args: ${FFMPEG_EXTRA_SIMD_ARGS[*]}"\n'
            'fi\n'
        )
        match = re.search(r"^([ \t]*)\./configure\b", updated, flags=re.MULTILINE)
        if match:
            indent = match.group(1)
            replacement = (
                f'{hook}\n'
                f'{indent}./configure ${{FFMPEG_EXTRA_SIMD_ARGS[@]+"${{FFMPEG_EXTRA_SIMD_ARGS[@]}}"}}'
            )
            updated = updated[: match.start()] + replacement + updated[match.end() :]
        else:
            print(f"[patch] WARN: Could not locate ./configure in {target.relative_to(root)}")

    # Inject post-configure & post-compile verification logging (visible in CI stdout!)
    diag_marker = "# FFmpeg-ARM64-CONFIG-VERIFY"
    if diag_marker not in updated:
        diag_snippet = (
            f"\n{diag_marker}\n"
            'if [[ "${ARCH:-}" == "arm64-v8a" || "${FFMPEG_KIT_ARCH:-}" == "arm64-v8a" ]] && [[ -f "ffbuild/config.mak" ]]; then\n'
            '  echo "[ffmpeg-verify] === ffbuild/config.mak summary ==="\n'
            '  grep -E "^(CC_IDENT|CC|AS|ARCH|CFLAGS|LDFLAGS|ASFLAGS|SHFLAGS)=" ffbuild/config.mak || true\n'
            '  echo "[ffmpeg-verify] === config.h SIMD/OPT flags ==="\n'
            '  grep -E "^#define (HAVE_ARMV8|HAVE_NEON|HAVE_DOTPROD|HAVE_I8MM|HAVE_ASM|HAVE_INLINE_ASM|CONFIG_SMALL|CONFIG_OPTIMIZATIONS|HAVE_GETAUXVAL) " config.h || true\n'
            'fi\n'
        )
        # Insert right after configure's exit-code check
        cfg_check = re.search(r"(\./configure[\s\S]*?if \[\[ \$\? -ne 0 \]\]; then\s+exit 1\s+fi)", updated)
        if cfg_check:
            updated = updated[: cfg_check.end()] + "\n" + diag_snippet + updated[cfg_check.end() :]

    obj_marker = "# FFmpeg-ARM64-OBJ-VERIFY"
    if obj_marker not in updated:
        obj_snippet = (
            f"\n{obj_marker}\n"
            'if [[ "${ARCH:-}" == "arm64-v8a" || "${FFMPEG_KIT_ARCH:-}" == "arm64-v8a" ]]; then\n'
            '  echo "[ffmpeg-verify] === Compiled AArch64 NEON/SIMD decoder objects ==="\n'
            '  ls -lh libavcodec/aarch64/vp9*neon*.o libavcodec/aarch64/hevc*neon*.o libavcodec/aarch64/h264*neon*.o libswscale/aarch64/*.o 2>/dev/null | head -n 30 || true\n'
            'fi\n'
        )
        install_match = re.search(r"^[ \t]*make install\b", updated, flags=re.MULTILINE)
        if install_match:
            updated = updated[: install_match.start()] + obj_snippet + "\n" + updated[install_match.start() :]

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(
            f"[patch] OK: Patched {target.relative_to(root)} "
            "(fixed dotprod/i8mm probe, explicit --as/--optflags/--extra-cflags/--extra-ldflags/-Wl,--lto-O3, config/object verify)"
        )
    else:
        print(f"[patch] INFO: No changes needed for {target.relative_to(root)}")


def patch_ffkitsaf_protocol(root: Path) -> None:
    """
    Enlarges ffkitsaf: non-streamed AVIO read block size from FFmpeg's 32 KB default to 256 KB
    (matching the 256 KB write buffer already in upstream libavformat_file_ffkitsaf.inc),
    cutting Android SAF/FUSE `read()` kernel transitions by 8x with negligible memory cost.
    """
    target = find_first(root, "tools/protocols/libavformat_file_ffkitsaf.inc", "libavformat_file_ffkitsaf.inc")
    if target is None:
        print("[patch] WARN: tools/protocols/libavformat_file_ffkitsaf.inc not found, skipping read buffer patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    marker = "/* FFKITSAF-256K-READ-BUFFER */"
    if marker not in updated:
        pattern = re.compile(
            r"(if\s*\(\s*!h->is_streamed\s*&&\s*flags\s*&\s*AVIO_FLAG_WRITE\s*\)\s*\n\s*h->min_packet_size\s*=\s*h->max_packet_size\s*=\s*262144;)"
        )
        replacement = (
            r"\1\n"
            "#ifdef AVIO_FLAG_READ\n"
            f"    {marker}\n"
            "    else if (!h->is_streamed && (flags & AVIO_FLAG_READ))\n"
            "        h->max_packet_size = 262144;\n"
            "#endif"
        )
        updated = pattern.sub(replacement, updated, count=1)

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(f"[patch] OK: Enabled 256 KB AVIO read buffer in {target.relative_to(root)} (8x fewer SAF/FUSE read syscalls)")
    else:
        print(f"[patch] INFO: No changes applied to {target.relative_to(root)}")


def patch_dav1d_script(root: Path) -> None:
    """
    Upstream scripts/android/dav1d.sh disables ALL assembly (`-Denable_asm=false`), making AV1
    software decode ~4-6x slower on ARM64. Enable `-Denable_asm=true` on arm64-v8a (dav1d uses
    runtime getauxval dispatch for NEON / DotProd / I8MM -> zero SIGILL on ARMv8.0).
    """
    target = find_first(root, "scripts/android/dav1d.sh", "dav1d.sh")
    if target is None:
        print("[patch] WARN: scripts/android/dav1d.sh not found, skipping dav1d patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    marker = "# DAV1D-ARM64-ASM-O3"
    if marker not in updated and "-Denable_asm=false" in updated:
        sanitizer = ARM64_RUNTIME_FLAG_SANITIZER_TEMPLATE.format(
            marker="DAV1D-ARM64-O3-ZERO-PLT",
            tag="dav1d",
        )
        hook = (
            f"{sanitizer}\n"
            f"{marker}\n"
            'DAV1D_ENABLE_ASM="false"\n'
            'if [[ "${ARCH:-}" == "arm64-v8a" || "${FFMPEG_KIT_ARCH:-}" == "arm64-v8a" ]]; then\n'
            '  DAV1D_ENABLE_ASM="true"\n'
            '  echo "[dav1d] Enabling ARM64 runtime-dispatched NEON/DotProd/I8MM assembly (-Denable_asm=true)."\n'
            'fi\n'
        )
        meson_match = re.search(r'^[ \t]*(?:"\$\{MESON:-meson\}"|meson)(?=\s)', updated, flags=re.MULTILINE)
        if meson_match:
            updated = (
                updated[: meson_match.start()]
                + hook
                + "\n"
                + updated[meson_match.start() :].replace("-Denable_asm=false", '-Denable_asm="${DAV1D_ENABLE_ASM}"', 1)
            )
        else:
            print(f"[patch] WARN: Could not locate meson invocation in {target.relative_to(root)}")

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(f"[patch] OK: Patched {target.relative_to(root)} (enabled arm64-v8a NEON/DotProd/I8MM assembly + -O3)")
    else:
        print(f"[patch] INFO: No changes needed for {target.relative_to(root)}")


def patch_libvpx_script(root: Path) -> None:
    """
    Upstream scripts/android/libvpx.sh hardcodes `--enable-small`, suppressing loop unrolling
    and fast VP8/VP9 paths. Strip `--enable-small` and sanitize flags to -O3 + zero-PLT.
    """
    target = find_first(root, "scripts/android/libvpx.sh", "libvpx.sh")
    if target is None:
        print("[patch] WARN: scripts/android/libvpx.sh not found, skipping libvpx patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    # Remove --enable-small (both standalone line with backslash continuation or inline)
    updated = re.sub(r"^[ \t]*--enable-small[ \t]*\\\s*\n", "", updated, flags=re.MULTILINE)
    updated = re.sub(r"\s--enable-small\b", "", updated)
    updated = re.sub(r"(?<=[\s\"'])-O[s2](?=[\s\"'])", "-O3", updated)

    marker = "# LIBVPX-ARM64-O3-ZERO-PLT"
    if marker not in updated:
        sanitizer = ARM64_RUNTIME_FLAG_SANITIZER_TEMPLATE.format(
            marker="LIBVPX-ARM64-O3-ZERO-PLT",
            tag="libvpx",
        )
        match = re.search(r"^[ \t]*\./configure\b", updated, flags=re.MULTILINE)
        if match:
            updated = updated[: match.start()] + sanitizer + "\n" + updated[match.start() :]

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(f"[patch] OK: Patched {target.relative_to(root)} (removed --enable-small, enforced -O3 + zero-PLT)")
    else:
        print(f"[patch] INFO: No changes needed for {target.relative_to(root)}")


def patch_x264_script(root: Path) -> None:
    target = find_first(root, "scripts/android/x264.sh", "x264.sh")
    if target is None:
        print("[patch] WARN: scripts/android/x264.sh not found, skipping x264 script patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    # Ensure x264 compiles with -O3 (never downgraded by -Os or -O2 from --speed)
    updated = re.sub(r"(?<=[\s\"'])-O[s2](?=[\s\"'])", "-O3", updated)

    # Runtime CFLAGS/LDFLAGS sanitizer + diagnostic check before ./configure
    marker = "# X264-ARM64-SIMD-DIAGNOSTIC"
    if marker not in updated:
        sanitizer = ARM64_RUNTIME_FLAG_SANITIZER_TEMPLATE.format(
            marker="X264-ARM64-O3-ZERO-PLT",
            tag="x264",
        )
        hook = (
            f"{sanitizer}\n"
            f"{marker}\n"
            'if [[ "${ARCH:-}" == "arm64-v8a" || "${FFMPEG_KIT_ARCH:-}" == "arm64-v8a" ]] && [[ -f "common/cpu.c" ]]; then\n'
            '  if grep -q "X264_CPU_I8MM" common/cpu.c 2>/dev/null; then\n'
            '    echo "[x264] Verified AArch64 NEON + DotProd + I8MM runtime dispatch in common/cpu.c."\n'
            '  elif grep -q "X264_CPU_DOTPROD" common/cpu.c 2>/dev/null; then\n'
            '    echo "[x264] Verified AArch64 NEON + DotProd runtime dispatch in common/cpu.c."\n'
            '  else\n'
            '    echo "[x264] INFO: Using baseline AArch64 NEON assembly in common/cpu.c."\n'
            '  fi\n'
            'fi\n'
        )
        match = re.search(r"^[ \t]*\./configure\b", updated, flags=re.MULTILINE)
        if match:
            updated = updated[: match.start()] + hook + "\n" + updated[match.start() :]

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(f"[patch] OK: Patched {target.relative_to(root)} (-O3 + zero-PLT + AArch64 SIMD diagnostic)")
    else:
        print(f"[patch] INFO: No changes needed for {target.relative_to(root)}")


def _locate_x265_source_cmake(x265_hint_dir: Path) -> tuple[Path | None, Path | None]:
    """Resolves x265 source/CMakeLists.txt whether called from repo root or a build subdir."""
    candidates = [
        x265_hint_dir / "source",
        x265_hint_dir.parent / "source",
        x265_hint_dir.parent.parent / "source",
        x265_hint_dir,
    ]
    for src_dir in candidates:
        top_cmake = src_dir / "CMakeLists.txt"
        if top_cmake.is_file() and (src_dir / "common").is_dir():
            common_cmake = src_dir / "common" / "CMakeLists.txt"
            return top_cmake, (common_cmake if common_cmake.is_file() else None)
    return None, None


def inspect_x265_cmake_safety(x265_hint_dir: Path) -> str:
    """
    Inspects the actual x265 CMakeLists.txt to emit the fastest 100%-ARMv8.0-safe CMake flags.

    - Always enables `-DCROSS_COMPILE_ARM64=1 -DENABLE_ASSEMBLY=ON` (baseline NEON is mandatory
      on every 64-bit ARMv8 CPU and gives a major speedup over ENABLE_ASSEMBLY=0).
    - Enables `-DENABLE_NEON_DOTPROD=ON -DENABLE_NEON_I8MM=ON` ONLY when x265 isolates
      `+dotprod`/`+i8mm` to per-file `set_source_files_properties` (never polluting global
      `ARM_ARGS`), guaranteeing zero SIGILL on older ARMv8.0 devices.
    - Keeps SVE/SVE2 off so non-SVE Snapdragon cores never hit unsupported instructions.
    """
    top_cmake, common_cmake = _locate_x265_source_cmake(x265_hint_dir)
    if top_cmake is None:
        return "-DCROSS_COMPILE_ARM64=1 -DENABLE_ASSEMBLY=ON"

    top_text = top_cmake.read_text(encoding="utf-8", errors="replace")
    common_text = common_cmake.read_text(encoding="utf-8", errors="replace") if common_cmake else ""
    combined = top_text + "\n" + common_text

    has_dotprod_opt = "ENABLE_NEON_DOTPROD" in top_text
    has_i8mm_opt = "ENABLE_NEON_I8MM" in top_text
    has_sve_opt = "ENABLE_SVE" in top_text

    uses_per_file_isolation = (
        "set_source_files_properties" in combined
        and ("dotprod" in combined.lower() or "i8mm" in combined.lower())
    )
    pollutes_global_arm_args = bool(
        re.search(r"ARM_ARGS[^\n]*\+dotprod", top_text)
        or re.search(r"ARM_ARGS[^\n]*\+i8mm", top_text)
        or re.search(r"ARM_ARGS[^\n]*armv8\.2-a\+dotprod", top_text)
        or re.search(r"ARM_ARGS[^\n]*armv8\.6-a", top_text)
    )

    flags = ["-DCROSS_COMPILE_ARM64=1", "-DENABLE_ASSEMBLY=ON"]

    if has_dotprod_opt:
        if uses_per_file_isolation and not pollutes_global_arm_args:
            flags.append("-DENABLE_NEON_DOTPROD=ON")
        else:
            flags.append("-DENABLE_NEON_DOTPROD=OFF")

    if has_i8mm_opt:
        if uses_per_file_isolation and not pollutes_global_arm_args:
            flags.append("-DENABLE_NEON_I8MM=ON")
        else:
            flags.append("-DENABLE_NEON_I8MM=OFF")

    if has_sve_opt:
        flags.append("-DENABLE_SVE=OFF")
        if "ENABLE_SVE2" in top_text:
            flags.append("-DENABLE_SVE2=OFF")
        if "ENABLE_SVE2_BITPERM" in top_text:
            flags.append("-DENABLE_SVE2_BITPERM=OFF")

    return " ".join(flags)


def patch_x265_script(root: Path, self_script_path: Path) -> None:
    target = find_first(root, "scripts/android/x265.sh", "x265.sh")
    if target is None:
        print("[patch] WARN: scripts/android/x265.sh not found, skipping x265 script patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    # 1. Upgrade BOTH -Os and -O2 -> -O3
    updated = re.sub(r"(?<=[\s\"'])-O[s2](?=[\s\"'])", "-O3", updated)

    # 2. Enforce -O3 + zero-PLT flags + safe ARM64 DotProd/I8MM CMake probe + 8-bit & 10-bit
    #    multilib libx265 (LINKED_10BIT=ON) so 8-bit keeps 100% native uint8_t speed (0% overhead)
    #    while true 10-bit Main10 (`yuv420p10le`) is compiled with full ARM64 SIMD assembly.
    flag_marker = "# X265-ARM64-O3-ZERO-PLT"
    if flag_marker not in updated:
        sanitizer = ARM64_RUNTIME_FLAG_SANITIZER_TEMPLATE.format(
            marker="X265-ARM64-O3-ZERO-PLT",
            tag="x265",
        )
        safe_extra = (
            'X265_EXTRA_SIMD_FLAGS=""\n'
            'X265_MULTILIB_8BIT_FLAGS=""\n'
            'if [[ "${ARCH:-}" == "arm64-v8a" || "${FFMPEG_KIT_ARCH:-}" == "arm64-v8a" ]]; then\n'
            '  PY_BIN="$(command -v python3 || echo /usr/bin/python3)"\n'
            f'  X265_EXTRA_SIMD_FLAGS="$("${{PY_BIN}}" "{self_script_path.resolve()}" --inspect-x265 "${{BASEDIR}}/src/${{LIB_NAME}}" 2>/dev/null || echo "")"\n'
            '  echo "[x265] Verified runtime-safe ARM64 CMake SIMD flags: ${X265_EXTRA_SIMD_FLAGS:-"(upstream default)"}"\n'
            '  # Build isolated 10-bit Main10 static sub-library (x265_10bit namespace, zero impact on 8-bit)\n'
            '  X265_BUILD_ABS="$(pwd)"\n'
            '  X265_10BIT_DIR="${X265_BUILD_ABS}_10bit"\n'
            '  rm -rf "${X265_10BIT_DIR}"\n'
            '  mkdir -p "${X265_10BIT_DIR}" || return 1\n'
            '  echo "[x265] Building ARM64 10-bit Main10 sub-library (-DHIGH_BIT_DEPTH=ON -DEXPORT_C_API=OFF)..."\n'
            '  (\n'
            '    cd "${X265_10BIT_DIR}" || exit 1\n'
            '    cmake -Wno-dev \\\n'
            '      -DCMAKE_VERBOSE_MAKEFILE=0 \\\n'
            '      -DCMAKE_C_FLAGS="${CFLAGS}" \\\n'
            '      -DCMAKE_CXX_FLAGS="${CXXFLAGS}" \\\n'
            '      -DCMAKE_EXE_LINKER_FLAGS="${LDFLAGS}" \\\n'
            '      -DCMAKE_FIND_ROOT_PATH="${ANDROID_SYSROOT}" \\\n'
            '      -DCMAKE_BUILD_TYPE=Release \\\n'
            '      -DCMAKE_INSTALL_PREFIX="${LIB_INSTALL_PREFIX}" \\\n'
            '      -DCMAKE_SYSTEM_NAME=Android \\\n'
            '      -DCMAKE_CROSSCOMPILING=True \\\n'
            '      -DCMAKE_SYSTEM_VERSION="${API}" \\\n'
            '      -DCMAKE_C_COMPILER="${ANDROID_TOOLCHAIN}/bin/$CC" \\\n'
            '      -DCMAKE_CXX_COMPILER="${ANDROID_TOOLCHAIN}/bin/$CXX" \\\n'
            '      -DCMAKE_LINKER="${ANDROID_TOOLCHAIN}/bin/$LD" \\\n'
            '      -DCMAKE_AR="${ANDROID_TOOLCHAIN}/bin/$AR" \\\n'
            '      -DCMAKE_POSITION_INDEPENDENT_CODE=1 \\\n'
            '      -DSTATIC_LINK_CRT=1 \\\n'
            '      -DENABLE_PIC=1 \\\n'
            '      -DENABLE_CLI=0 \\\n'
            '      -DHIGH_BIT_DEPTH=ON \\\n'
            '      -DMAIN12=OFF \\\n'
            '      -DEXPORT_C_API=OFF \\\n'
            '      ${ASM_OPTIONS} ${X265_EXTRA_SIMD_FLAGS} \\\n'
            '      -DCMAKE_SYSTEM_PROCESSOR="$(get_cmake_system_processor)" \\\n'
            '      -DENABLE_SHARED=0 "${BASEDIR}/src/${LIB_NAME}/source" || exit 1\n'
            '    make -j$(get_cpu_count) || exit 1\n'
            '  ) || return 1\n'
            '  cp -f "${X265_10BIT_DIR}/libx265.a" "${X265_BUILD_ABS}/libx265_main10.a" || return 1\n'
            '  X265_MULTILIB_8BIT_FLAGS="-DEXTRA_LIB=x265_main10.a -DEXTRA_LINK_FLAGS=-L${X265_BUILD_ABS} -DLINKED_10BIT=ON"\n'
            '  echo "[x265] OK: Built 10-bit archive (${X265_BUILD_ABS}/libx265_main10.a)"\n'
            'fi\n'
        )
        cmake_match = re.search(r"^[ \t]*cmake\b", updated, flags=re.MULTILINE)
        if cmake_match:
            updated = (
                updated[: cmake_match.start()]
                + sanitizer
                + "\n"
                + safe_extra
                + "\n"
                + updated[cmake_match.start() :]
            )
            # Append ${X265_EXTRA_SIMD_FLAGS} ${X265_MULTILIB_8BIT_FLAGS} right after ${ASM_OPTIONS}
            # inside the 8-bit cmake call (the second cmake invocation now that 10-bit precedes it).
            asm_matches = list(re.finditer(r"\$\{ASM_OPTIONS\}(?!\s+\$\{X265_EXTRA_SIMD_FLAGS\})", updated))
            if asm_matches:
                m_asm = asm_matches[-1]
                updated = (
                    updated[: m_asm.end()]
                    + " ${X265_EXTRA_SIMD_FLAGS} ${X265_MULTILIB_8BIT_FLAGS}"
                    + updated[m_asm.end() :]
                )

        # Merge 8-bit and 10-bit static archives into installed libx265.a right after `make install`
        merge_marker = "# X265-ARM64-MULTILIB-10BIT-MERGE"
        if merge_marker not in updated:
            merge_hook = (
                f"\n{merge_marker}\n"
                'X265_BUILD_ABS="$(pwd)"\n'
                'if [[ -f "${X265_BUILD_ABS}/libx265_main10.a" && -f "${X265_BUILD_ABS}/libx265.a" ]]; then\n'
                '  cp -f "${X265_BUILD_ABS}/libx265.a" "${X265_BUILD_ABS}/libx265_main8.a" || return 1\n'
                '  if [[ -x "${AR:-}" ]]; then\n'
                '    X265_AR_BIN="${AR}"\n'
                '  elif [[ -x "${ANDROID_TOOLCHAIN}/bin/${AR:-llvm-ar}" ]]; then\n'
                '    X265_AR_BIN="${ANDROID_TOOLCHAIN}/bin/${AR:-llvm-ar}"\n'
                '  else\n'
                '    X265_AR_BIN="$(command -v llvm-ar || command -v ar)"\n'
                '  fi\n'
                '  echo "[x265] Merging 8-bit + 10-bit archives into ${LIB_INSTALL_PREFIX}/lib/libx265.a via ${X265_AR_BIN} -M..."\n'
                '  "${X265_AR_BIN}" -M <<EOF || return 1\n'
                'CREATE ${LIB_INSTALL_PREFIX}/lib/libx265.a\n'
                'ADDLIB ${X265_BUILD_ABS}/libx265_main8.a\n'
                'ADDLIB ${X265_BUILD_ABS}/libx265_main10.a\n'
                'SAVE\n'
                'END\n'
                'EOF\n'
                '  if [[ -x "${RANLIB:-}" ]]; then\n'
                '    "${RANLIB}" "${LIB_INSTALL_PREFIX}/lib/libx265.a" || true\n'
                '  elif [[ -n "${RANLIB:-}" && -x "${ANDROID_TOOLCHAIN}/bin/${RANLIB}" ]]; then\n'
                '    "${ANDROID_TOOLCHAIN}/bin/${RANLIB}" "${LIB_INSTALL_PREFIX}/lib/libx265.a" || true\n'
                '  fi\n'
                '  cp -f "${LIB_INSTALL_PREFIX}/lib/libx265.a" "${X265_BUILD_ABS}/libx265.a" || true\n'
                '  if ! strings "${LIB_INSTALL_PREFIX}/lib/libx265.a" | grep -qE "(8bit\\+10bit|x265_10bit)"; then\n'
                '    echo "[x265] ERROR: Merged libx265.a is missing 10-bit (8bit+10bit / x265_10bit) symbols!" >&2\n'
                '    return 1\n'
                '  fi\n'
                '  echo "[x265] OK: Multi-bitdepth (8-bit + 10-bit Main10) libx265.a verified!"\n'
                'fi\n'
            )
            install_match = re.search(r"^[ \t]*make[ \t]+install[^\n]*$", updated, flags=re.MULTILINE)
            if install_match:
                updated = (
                    updated[: install_match.end()]
                    + merge_hook
                    + updated[install_match.end() :]
                )

    # 3. Also handle legacy scripts that explicitly disabled assembly (`-DENABLE_ASSEMBLY=0/OFF`)
    marker = "# X265-ARM64-SIMD-RUNTIME-SAFE"
    if marker not in updated and re.search(r"-DENABLE_ASSEMBLY=(?:0|OFF)\b", updated):
        arm64_block_re = re.compile(
            r"(arm64-v8a\)\s*\n(?:\s*[^\n]*\n){0,10}?)(?=\s*;;)",
            flags=re.MULTILINE,
        )

        def rewrite_arm64_case(match: re.Match[str]) -> str:
            block = match.group(1)
            if not re.search(r"-DENABLE_ASSEMBLY=(?:0|OFF)\b", block):
                return block
            safe_probe = (
                f"  {marker}\n"
                '  PY_BIN="$(command -v python3 || echo /usr/bin/python3)"\n'
                f'  X265_SAFE_ARM64_FLAGS="$("${{PY_BIN}}" "{self_script_path.resolve()}" --inspect-x265 "$(pwd)" 2>/dev/null || echo "-DCROSS_COMPILE_ARM64=1 -DENABLE_ASSEMBLY=ON")"\n'
                '  echo "[x265] Using runtime-safe ARM64 CMake flags: ${X265_SAFE_ARM64_FLAGS}"\n'
            )
            block = re.sub(
                r"-DENABLE_ASSEMBLY=(?:0|OFF)\b",
                "${X265_SAFE_ARM64_FLAGS}",
                block,
            )
            block = re.sub(r"-DCROSS_COMPILE_ARM=(?:1|ON)\b", "", block)
            return safe_probe + block

        updated = arm64_block_re.sub(rewrite_arm64_case, updated, count=1)

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(f"[patch] OK: Patched {target.relative_to(root)} (-O3 + zero-PLT + runtime-safe ARM64 DotProd/I8MM)")
    else:
        print(f"[patch] INFO: {target.relative_to(root)} already keeps ARM64 assembly / -O3 intact.")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Patch arthenica/ffmpeg-kit-next for ARM64-O3 LTO-O3 zero-PLT + runtime-dispatched SIMD."
    )
    parser.add_argument("--repo", type=Path, help="Path to cloned arthenica/ffmpeg-kit-next repo")
    parser.add_argument("--inspect-x265", type=Path, help="Inspect x265 source/build dir and print safe CMake flags")
    args = parser.parse_args()

    if args.inspect_x265 is not None:
        print(inspect_x265_cmake_safety(args.inspect_x265))
        return 0

    if args.repo is None or not args.repo.is_dir():
        print("[patch] ERROR: --repo must point to an existing ffmpeg-kit-next directory.", file=sys.stderr)
        return 1

    self_path = Path(__file__).resolve()
    patch_android_sh(args.repo)
    patch_function_android(args.repo)
    patch_ffmpeg_script(args.repo)
    patch_ffkitsaf_protocol(args.repo)
    patch_dav1d_script(args.repo)
    patch_libvpx_script(args.repo)
    patch_x264_script(args.repo)
    patch_x265_script(args.repo, self_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
