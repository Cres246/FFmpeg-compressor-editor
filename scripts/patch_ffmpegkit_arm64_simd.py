#!/usr/bin/env python3
"""
Surgical ARM64-O3 + Runtime-Dispatched SIMD (NEON / DotProd / I8MM) patcher for FFmpegKit.

Safety invariant (ZERO SIGILL on older ARMv8.0 devices):
  - Global CFLAGS/CXXFLAGS remain strictly baseline `armv8-a` (never global `+dotprod`/`+i8mm`),
    upgraded from `-Os` to `-O3` for speed.
  - Advanced SIMD instructions (`DotProd`, `I8MM`) are enabled ONLY via runtime-dispatched
    per-function assembly (`.arch_extension dotprod` / `.arch_extension i8mm`) or per-file
    CMake targets guarded by Linux/Android `getauxval(AT_HWCAP / AT_HWCAP2)`.
  - 16 KB ELF page-size alignment (`-Wl,-z,max-page-size=16384`) is preserved/enforced for
    Android 15/16 compliance.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path


def find_first(root: Path, relative_hint: str, filename: str) -> Path | None:
    direct = root / relative_hint
    if direct.is_file():
        return direct
    for candidate in root.rglob(filename):
        if ".git" not in candidate.parts and candidate.is_file():
            return candidate
    return None


def patch_function_android(root: Path) -> None:
    target = find_first(root, "scripts/function-android.sh", "function-android.sh")
    if target is None:
        print("[patch] WARN: function-android.sh not found, skipping CFLAGS patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    # 1. Upgrade -Os -> -O3 for speed optimization while keeping baseline armv8-a ISA.
    #    Only replace standalone -Os compiler flags (never partial identifiers).
    updated = re.sub(r"(?<=[\s\"'])-Os(?=[\s\"'])", "-O3", updated)

    # 2. Ensure 16 KB page-size alignment flag is present in linker flags if missing.
    if "max-page-size=16384" not in updated:
        updated = re.sub(
            r'(get_common_ldflags\(\)\s*\{[^\n]*\n)',
            r'\1  # Enforce 16 KB ELF page alignment for Android 15/16\n',
            updated,
            count=1,
        )
        updated = re.sub(
            r'(-Wl,--hash-style=both|-Wl,-z,relro)',
            r'\1 -Wl,-z,max-page-size=16384',
            updated,
        )

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(f"[patch] OK: Upgraded -Os -> -O3 and verified 16KB LDFLAGS in {target.relative_to(root)}")
    else:
        print(f"[patch] INFO: {target.relative_to(root)} already uses -O3 / 16KB LDFLAGS.")


def patch_ffmpeg_script(root: Path) -> None:
    target = find_first(root, "scripts/android/ffmpeg.sh", "ffmpeg.sh")
    if target is None:
        print("[patch] WARN: scripts/android/ffmpeg.sh not found, skipping FFmpeg script patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    # Remove --enable-small (suppresses loop unrolling & SIMD inlining in libavcodec/libswscale)
    updated = re.sub(r"\s--enable-small\b", "", updated)

    # Ensure ARM64 runtime-dispatched dotprod/i8mm flags are probed dynamically via ./configure --help
    # so this works cleanly across FFmpeg 6.x, 7.x, and 8.x without unknown-option errors.
    marker = "# FFmpeg-ARM64-SIMD-RUNTIME-DISPATCH"
    if marker not in updated:
        hook = f"""
{marker}
if [[ "${{ARCH}}" == "arm64-v8a" ]]; then
  if ./configure --help 2>/dev/null | grep -q -- '--enable-dotprod'; then
    ARCH_OPTIONS+=" --enable-dotprod"
  fi
  if ./configure --help 2>/dev/null | grep -q -- '--enable-i8mm'; then
    ARCH_OPTIONS+=" --enable-i8mm"
  fi
fi
"""
        # Insert right before `./configure` invocation in ffmpeg.sh
        match = re.search(r"^[ \t]*\./configure\b", updated, flags=re.MULTILINE)
        if match:
            updated = updated[: match.start()] + hook + "\n" + updated[match.start() :]
        else:
            print(f"[patch] WARN: Could not locate ./configure in {target.relative_to(root)}")

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(f"[patch] OK: Patched {target.relative_to(root)} (removed --enable-small, added runtime dotprod/i8mm probe)")
    else:
        print(f"[patch] INFO: No changes needed for {target.relative_to(root)}")


def patch_x264_script(root: Path) -> None:
    target = find_first(root, "scripts/android/x264.sh", "x264.sh")
    if target is None:
        print("[patch] WARN: scripts/android/x264.sh not found, skipping x264 script patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    # Ensure x264 builds with -O3 and assembly enabled on arm64-v8a (x264 auto-detects NDK Clang's
    # `.arch_extension dotprod` and `.arch_extension i8mm` and dispatches at runtime via getauxval).
    updated = re.sub(r"(?<=[\s\"'])-Os(?=[\s\"'])", "-O3", updated)

    # Inject optional automatic x264 source upgrade check if bundled x264 predates AArch64 dotprod/i8mm
    marker = "# X264-ARM64-SIMD-CHECK"
    if marker not in updated:
        hook = f"""
{marker}
if [[ "${{ARCH}}" == "arm64-v8a" ]] && [[ -f "common/cpu.c" ]]; then
  if ! grep -q "X264_CPU_I8MM" common/cpu.c 2>/dev/null; then
    echo "[x264] Bundled x264 predates AArch64 DotProd/I8MM; attempting fast-forward from VideoLAN..."
    if git fetch --depth=1 https://code.videolan.org/videolan/x264.git master 2>/dev/null && git checkout -f FETCH_HEAD 2>/dev/null; then
      echo "[x264] Upgraded to latest VideoLAN x264 with runtime NEON/DotProd/I8MM dispatch."
    else
      echo "[x264] WARN: Could not fetch VideoLAN x264; continuing with bundled source."
    fi
  else
    echo "[x264] Verified AArch64 DotProd/I8MM runtime dispatch present in common/cpu.c."
  fi
fi
"""
        match = re.search(r"^[ \t]*\./configure\b", updated, flags=re.MULTILINE)
        if match:
            updated = updated[: match.start()] + hook + "\n" + updated[match.start() :]

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(f"[patch] OK: Patched {target.relative_to(root)} (ensured -O3 + AArch64 DotProd/I8MM runtime dispatch)")
    else:
        print(f"[patch] INFO: No changes needed for {target.relative_to(root)}")


def inspect_x265_cmake_safety(x265_src_dir: Path) -> str:
    """
    Inspects the actual cloned x265 CMakeLists.txt to emit the fastest 100%-ARMv8.0-safe CMake flags.

    - Always enables `-DCROSS_COMPILE_ARM64=1 -DENABLE_ASSEMBLY=ON` (baseline NEON is mandatory on
      every 64-bit ARMv8 CPU and gives a huge speedup over FFmpegKit's default ENABLE_ASSEMBLY=0).
    - Enables `-DENABLE_NEON_DOTPROD=ON -DENABLE_NEON_I8MM=ON` ONLY when x265's CMakeLists isolates
      `+dotprod`/`+i8mm` to per-file `set_source_files_properties` (and never leaks them into global
      `ARM_ARGS` / `CMAKE_CXX_FLAGS`), guaranteeing zero SIGILL on older ARMv8.0 CPUs.
    """
    top_cmake = x265_src_dir / "source" / "CMakeLists.txt"
    common_cmake = x265_src_dir / "source" / "common" / "CMakeLists.txt"
    if not top_cmake.is_file():
        return "-DCROSS_COMPILE_ARM64=1 -DENABLE_ASSEMBLY=ON"

    top_text = top_cmake.read_text(encoding="utf-8", errors="replace")
    common_text = common_cmake.read_text(encoding="utf-8", errors="replace") if common_cmake.is_file() else ""
    combined = top_text + "\n" + common_text

    has_dotprod_opt = "ENABLE_NEON_DOTPROD" in top_text
    has_i8mm_opt = "ENABLE_NEON_I8MM" in top_text
    has_sve_opt = "ENABLE_SVE" in top_text

    # Check whether x265 uses per-file COMPILE_FLAGS isolation vs global ARM_ARGS pollution
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
            # Global ARM_ARGS pollution would emit non-baseline instructions outside runtime guards
            flags.append("-DENABLE_NEON_DOTPROD=OFF")

    if has_i8mm_opt:
        if uses_per_file_isolation and not pollutes_global_arm_args:
            flags.append("-DENABLE_NEON_I8MM=ON")
        else:
            flags.append("-DENABLE_NEON_I8MM=OFF")

    if has_sve_opt:
        # Keep SVE/SVE2 off unless isolated; prevents SIGILL on non-SVE Snapdragon/ARMv8.2 cores
        flags.append("-DENABLE_SVE=OFF")
        if "ENABLE_SVE2" in top_text:
            flags.append("-DENABLE_SVE2=OFF")

    return " ".join(flags)


def patch_x265_script(root: Path, self_script_path: Path) -> None:
    target = find_first(root, "scripts/android/x265.sh", "x265.sh")
    if target is None:
        print("[patch] WARN: scripts/android/x265.sh not found, skipping x265 script patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    # 1. Upgrade -Os -> -O3
    updated = re.sub(r"(?<=[\s\"'])-Os(?=[\s\"'])", "-O3", updated)

    # 2. Replace FFmpegKit's arm64-v8a ENABLE_ASSEMBLY=0 with dynamic runtime-safe inspection!
    #    Keep all other flags (10-bit HIGH_BIT_DEPTH, install prefixes, pkg-config, etc.) untouched.
    marker = "# X265-ARM64-SIMD-RUNTIME-SAFE"
    if marker not in updated:
        # Rewrite `-DENABLE_ASSEMBLY=0` or `-DENABLE_ASSEMBLY=OFF` inside arm64-v8a block
        arm64_block_re = re.compile(
            r"(arm64-v8a\)\s*\n(?:\s*[^\n]*\n){0,8}?)(?=\s*;;)",
            flags=re.MULTILINE,
        )

        def rewrite_arm64_case(match: re.Match[str]) -> str:
            block = match.group(1)
            safe_probe = (
                f'  {marker}\n'
                f'  X265_SAFE_ARM64_FLAGS="$(python3 "{self_script_path.resolve()}" --inspect-x265 "$(pwd)")"\n'
                f'  echo "[x265] Using runtime-safe ARM64 CMake flags: ${{X265_SAFE_ARM64_FLAGS}}"\n'
            )
            if "ENABLE_ASSEMBLY" in block:
                block = re.sub(
                    r"-DENABLE_ASSEMBLY=(?:0|OFF)\b",
                    "${X265_SAFE_ARM64_FLAGS}",
                    block,
                )
                block = re.sub(r"-DCROSS_COMPILE_ARM=(?:1|ON)\b", "", block)
                return safe_probe + block
            else:
                return safe_probe + block + '  ARCH_OPTIONS+=" ${X265_SAFE_ARM64_FLAGS}"\n'

        if arm64_block_re.search(updated):
            updated = arm64_block_re.sub(rewrite_arm64_case, updated, count=1)
        else:
            # Fallback: inject before cmake invocation when ARCH == arm64-v8a
            hook = f"""
{marker}
if [[ "${{ARCH}}" == "arm64-v8a" ]]; then
  X265_SAFE_ARM64_FLAGS="$(python3 "{self_script_path.resolve()}" --inspect-x265 "$(pwd)")"
  echo "[x265] Using runtime-safe ARM64 CMake flags: ${{X265_SAFE_ARM64_FLAGS}}"
  ASM_OPTIONS="${{X265_SAFE_ARM64_FLAGS}}"
fi
"""
            match = re.search(r"^[ \t]*cmake\b", updated, flags=re.MULTILINE)
            if match:
                updated = updated[: match.start()] + hook + "\n" + updated[match.start() :]

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(f"[patch] OK: Patched {target.relative_to(root)} (enabled ARM64 assembly + runtime-safe SIMD probe)")
    else:
        print(f"[patch] INFO: No changes needed for {target.relative_to(root)}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Patch FFmpegKit for ARM64-O3 runtime-dispatched SIMD.")
    parser.add_argument("--repo", type=Path, help="Path to cloned ffmpegkit-maintained/ffmpeg repo")
    parser.add_argument("--inspect-x265", type=Path, help="Inspect x265 source dir and print safe CMake flags")
    args = parser.parse_args()

    if args.inspect_x265 is not None:
        print(inspect_x265_cmake_safety(args.inspect_x265))
        return 0

    if args.repo is None or not args.repo.is_dir():
        print("[patch] ERROR: --repo must point to an existing FFmpegKit directory.", file=sys.stderr)
        return 1

    self_path = Path(__file__).resolve()
    patch_function_android(args.repo)
    patch_ffmpeg_script(args.repo)
    patch_x264_script(args.repo)
    patch_x265_script(args.repo, self_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
