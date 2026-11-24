"""Verify one received recording folder locally. Writes only to stdout/stderr."""
import argparse
import hashlib
import json
from pathlib import Path
import sys


def verify(folder):
    manifests = list(folder.glob("*.meta.json"))
    if len(manifests) != 1:
        raise ValueError("Expected one final metadata file")
    manifest = manifests[0]
    if manifest.is_symlink():
        raise ValueError("Expected a regular metadata file")
    meta = json.loads(manifest.read_bytes())
    if meta.get("status") != "finalized" or not meta.get("parts"):
        raise ValueError("Recording is unfinished or has no parts")
    expected = set()
    identities = set()
    for part in meta["parts"]:
        name = part["file"]
        identity = (part["part"], part["track"])
        if Path(name).name != name or name in expected or identity in identities:
            raise ValueError("Invalid or duplicate part entry")
        expected.add(name)
        identities.add(identity)
        path = folder / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size != part["bytes"]:
            raise ValueError("Missing part or size mismatch")
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != part["sha256"]:
            raise ValueError("Part SHA-256 mismatch")
    extensions = {Path(name).suffix for name in expected}
    actual = {p.name for p in folder.iterdir() if p.suffix in extensions}
    if actual != expected:
        raise ValueError("Folder has extra audio files")
    return {k: meta[k] for k in ("recordingId", "startedAt", "durationSec", "source")} | {
        "parts": [{"file": p["file"], "sha256": p["sha256"]} for p in meta["parts"]]
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    try:
        print(json.dumps(verify(parser.parse_args().folder), indent=2))
    except (OSError, ValueError, TypeError, KeyError) as error:
        sys.exit(f"Verification failed: {error}")
