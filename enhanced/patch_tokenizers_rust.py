#!/usr/bin/env python3
"""Narrow build-time compatibility fix for the pinned tokenizers-cpp submodule.

New Rust compilers deny dangerous_implicit_autorefs. The checked-out upstream
C ABI uses two implicit references to fields of an already dereferenced raw
pointer. Make those field borrows explicit instead of suppressing this lint.

This script patches ONLY the transient CI submodule checkout. It must fail
rather than silently update an unexpectedly changed upstream release.
"""
from pathlib import Path
import sys


def main() -> int:
    filename = Path(
        "app/src/main/cpp/3rdparty/tokenizers-cpp/rust/src/lib.rs"
    )
    if not filename.is_file():
        print("ERROR: pinned tokenizers-cpp Rust source missing", file=sys.stderr)
        return 1

    replacements = {
        "(*handle).decode_str.len()": "(&(*handle).decode_str).len()",
        "(*handle).id_to_token_result.len()": "(&(*handle).id_to_token_result).len()",
    }
    original = filename.read_text(encoding="utf-8")
    updated = original

    for before, after in replacements.items():
        if updated.count(before) != 1:
            print(
                f"ERROR: expected exactly one upstream occurrence: {before}",
                file=sys.stderr,
            )
            return 2
        updated = updated.replace(before, after)

    if updated.count("(&(*handle).decode_str).len()") != 1 or updated.count(
        "(&(*handle).id_to_token_result).len()"
    ) != 1:
        print("ERROR: Rust patch validation failed", file=sys.stderr)
        return 3

    filename.write_text(updated, encoding="utf-8")
    print("Applied exactly two explicit-field-borrow compatibility fixes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
