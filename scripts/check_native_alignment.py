#!/usr/bin/env python3
"""Check ELF LOAD alignment inside APKs, including third-party JNI libraries."""
import pathlib
import subprocess
import sys
import tempfile
import zipfile

if len(sys.argv) < 2:
    raise SystemExit("Usage: check_native_alignment.py app.apk [other.apk ...]")
checked = 0
for apk in sys.argv[1:]:
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as directory:
        for name in archive.namelist():
            if not name.startswith("lib/") or not name.endswith(".so"):
                continue
            output = pathlib.Path(directory) / pathlib.Path(name).name
            output.write_bytes(archive.read(name))
            headers = subprocess.check_output(["readelf", "-lW", str(output)], text=True)
            aligns = [int(line.split()[-1], 16) for line in headers.splitlines() if line.strip().startswith("LOAD ")]
            # Android's 16 KB page-size devices use 64-bit ABIs. 32-bit devices
            # still use 4 KB pages; retaining their libraries is supported.
            abi = name.split('/')[1]
            required = 16384 if abi in ('arm64-v8a', 'x86_64') else 4096
            if not aligns or min(aligns) < required:
                raise SystemExit(f"{required}-byte page alignment failed: {apk}:{name}")
            checked += 1
            print(f"OK: {name} (required {required}-byte alignment)")
if not checked:
    raise SystemExit("No native libraries found; TDLib is missing")
print(f"Verified {checked} native libraries")
