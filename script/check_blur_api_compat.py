#!/usr/bin/env python3
"""Reject the API-34-only blur listener in every optimized DEX of a release APK."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

MISSING_TYPE = "Landroid/view/ViewTreeObserver$OnWindowVisibilityChangeListener;"
MISSING_METHODS = {"addOnWindowVisibilityChangeListener", "removeOnWindowVisibilityChangeListener"}


def scan(apk: Path) -> dict:
    hits = []
    dex_files = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not (name.startswith("classes") and name.endswith(".dex")):
                continue
            data = archive.read(name)
            if not data.startswith(b"dex\n"):
                raise ValueError(f"Unsupported DEX: {name}")
            dex_files.append(name)
            count, offset = struct.unpack_from("<II", data, 56)
            strings = []
            for i in range(count):
                pos = struct.unpack_from("<I", data, offset + i * 4)[0]
                while data[pos] & 0x80:
                    pos += 1
                pos += 1
                strings.append(data[pos:data.index(b"\0", pos)].decode("utf-8", errors="replace"))
            count, offset = struct.unpack_from("<II", data, 64)
            types = [strings[struct.unpack_from("<I", data, offset + i * 4)[0]] for i in range(count)]
            if MISSING_TYPE in types:
                hits.append({"dex": name, "type": MISSING_TYPE})
            count, offset = struct.unpack_from("<II", data, 88)
            for i in range(count):
                owner, _, method = struct.unpack_from("<HHI", data, offset + i * 8)
                if types[owner] == "Landroid/view/ViewTreeObserver;" and strings[method] in MISSING_METHODS:
                    hits.append({"dex": name, "method": strings[method]})
    if not dex_files:
        raise ValueError("No DEX files found")
    return {"apk": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "dexFiles": dex_files, "api34ListenerReferences": hits}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = {"candidate": scan(args.apk)}
    if args.baseline:
        result["baseline"] = scan(args.baseline)
        if not result["baseline"]["api34ListenerReferences"]:
            raise SystemExit("Baseline does not reproduce the missing-API reference")
    result["passed"] = not result["candidate"]["api34ListenerReferences"]
    encoded = json.dumps(result, indent=2) + "\n"
    if args.report:
        args.report.write_text(encoded)
    print(encoded)
    if not result["passed"]:
        raise SystemExit("Release still references the API-34-only window listener")


if __name__ == "__main__":
    main()
