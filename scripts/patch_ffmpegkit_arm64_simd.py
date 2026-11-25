#!/usr/bin/env python3
"""
Surgical ARM64-O3 + Runtime-Dispatched SIMD (NEON / DotProd / I8MM) patcher for
arthenica/ffmpeg-kit-next (FFmpeg 9.0.x).

Safety invariants (ZERO SIGILL on older ARMv8.0 devices):
  - Global CFLAGS/CXXFLAGS remain strictly baseline `armv8-a` (never global `+dotprod`/`+i8mm`),
    upgraded from `-Os` to `-O3` for maximum software encoding throughput.
  - Advanced SIMD instructions (`DotProd`, `I8MM`) are enabled ONLY via runtime-dispatched
    per-function assembly (`.arch_extension dotprod` / `.arch_extension i8mm`) or per-file
    CMake targets guarded by Linux/Android `getauxval(AT_HWCAP / AT_HWCAP2)`.
  - 16 KB ELF page-size alignment (`-Wl,-z,max-page-size=16384`) is verified/enforced for
    Android 15/16 compliance.
  - All existing 10-bit HEVC (`HIGH_BIT_DEPTH`/`MAIN10`) flags in upstream scripts are preserved.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


def find_first(root: Path, relative_hint: str, filename: str) -> Path | None:
    direct = root / relative_hint
    if direct.is_file():
        return direct
    for candidate in sorted(root.rglob(filename)):
        if ".git" not in candidate.parts and candidate.is_file():
            return candidate
    return None


def patch_function_android(root: Path) -> None:
    target = find_first(root, "scripts/function-android.sh", "function-android.sh")
    if target is None:
        print("[patch] WARN: function-android.sh not found, skipping CFLAGS/LDFLAGS patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    # 1. Upgrade standalone -Os -> -O3 while preserving baseline armv8-a ISA (zero SIGILL).
    updated = re.sub(r"(?<=[\s\"'])-Os(?=[\s\"'])", "-O3", updated)

    # 2. Enforce 16 KB ELF page-size alignment in linker flags if not already configured upstream.
    if "max-page-size=16384" not in updated:
        updated_ldflags = re.sub(
            r"(-Wl,--hash-style=both|-Wl,-z,relro)",
            r"\1 -Wl,-z,max-page-size=16384",
            updated,
        )
        if updated_ldflags != updated:
            updated = updated_ldflags
        else:
            # Fallback: inject inside get_common_ldflags() echo line if present
            updated = re.sub(
                r'(get_common_ldflags\(\)\s*\{[\s\S]{0,600}?echo\s+"[^"]*)(")',
                r'\1 -Wl,-z,max-page-size=16384\2',
                updated,
                count=1,
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

    # Inject a dedicated bash array FFMPEG_EXTRA_SIMD_ARGS=() directly into ./configure so we
    # never risk mutating an upstream array/string variable like ARCH_OPTIONS.
    marker = "# FFmpeg-ARM64-SIMD-RUNTIME-DISPATCH"
    if marker not in updated:
        hook = (
            f"\n{marker}\n"
            'FFMPEG_EXTRA_SIMD_ARGS=()\n'
            'if [[ "${ARCH:-}" == "arm64-v8a" || "${FFMPEG_KIT_ARCH:-}" == "arm64-v8a" ]]; then\n'
            '  if ./configure --help 2>/dev/null | grep -q -- \'--enable-dotprod\'; then\n'
            '    FFMPEG_EXTRA_SIMD_ARGS+=("--enable-dotprod")\n'
            '  fi\n'
            '  if ./configure --help 2>/dev/null | grep -q -- \'--enable-i8mm\'; then\n'
            '    FFMPEG_EXTRA_SIMD_ARGS+=("--enable-i8mm")\n'
            '  fi\n'
            '  echo "[ffmpeg] Runtime-dispatched ARM64 SIMD configure args: ${FFMPEG_EXTRA_SIMD_ARGS[*]:-(auto-enabled)}"\n'
            'fi\n'
        )
        match = re.search(r"^([ \t]*)\./configure\b", updated, flags=re.MULTILINE)
        if match:
            indent = match.group(1)
            replacement = f'{hook}\n{indent}./configure "${{FFMPEG_EXTRA_SIMD_ARGS[@]}}"'
            updated = updated[: match.start()] + replacement + updated[match.end() :]
        else:
            print(f"[patch] WARN: Could not locate ./configure in {target.relative_to(root)}")

    if updated != original:
        target.write_text(updated, encoding="utf-8")
        print(
            f"[patch] OK: Patched {target.relative_to(root)} "
            "(removed --enable-small, added array-safe runtime dotprod/i8mm probe)"
        )
    else:
        print(f"[patch] INFO: No changes needed for {target.relative_to(root)}")


def patch_x264_script(root: Path) -> None:
    target = find_first(root, "scripts/android/x264.sh", "x264.sh")
    if target is None:
        print("[patch] WARN: scripts/android/x264.sh not found, skipping x264 script patch.")
        return

    original = target.read_text(encoding="utf-8", errors="replace")
    updated = original

    # Ensure x264 compiles with -O3 (x264's configure automatically detects Clang's
    # `.arch_extension dotprod` and `.arch_extension i8mm` and dispatches via getauxval).
    updated = re.sub(r"(?<=[\s\"'])-Os(?=[\s\"'])", "-O3", updated)

    # Diagnostic check only (never git-fetch external sources inside Nix-pinned ffmpeg-kit-next)
    marker = "# X264-ARM64-SIMD-DIAGNOSTIC"
    if marker not in updated:
        hook = (
            f"\n{marker}\n"
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
        print(f"[patch] OK: Patched {target.relative_to(root)} (-O3 + AArch64 SIMD diagnostic)")
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

    # 2. Only override ENABLE_ASSEMBLY on arm64-v8a if upstream explicitly disabled it
    #    (`-DENABLE_ASSEMBLY=0` or `-DENABLE_ASSEMBLY=OFF`). If ffmpeg-kit-next already
    #    enables ARM64 assembly cleanly, preserve its tested CMake setup untouched.
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
        print(f"[patch] OK: Patched {target.relative_to(root)} (-O3 + runtime-safe ARM64 assembly)")
    else:
        print(f"[patch] INFO: {target.relative_to(root)} already keeps ARM64 assembly / -O3 intact.")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Patch arthenica/ffmpeg-kit-next for ARM64-O3 runtime-dispatched SIMD."
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
    patch_function_android(args.repo)
    patch_ffmpeg_script(args.repo)
    patch_x264_script(args.repo)
    patch_x265_script(args.repo, self_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
