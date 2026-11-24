"""Create a separate, local Apple development checkout from the committed fork."""
import argparse
import json
from pathlib import Path
import re
import struct
import subprocess
import zlib


def icon(size):
    """Three mint sound bars on a dark green field, opaque at every size."""
    def chunk(kind, content):
        return (struct.pack(">I", len(content)) + kind + content
                + struct.pack(">I", zlib.crc32(kind + content)))

    bars = [(0.26, 0.35, 0.17), (0.45, 0.54, 0.32), (0.64, 0.73, 0.23)]
    pixels = bytearray()
    for row in range(size):
        y = abs((row + 0.5) / size - 0.5)
        line = bytearray(b"\x12\x36\x37" * size)
        for left, right, height in bars:
            if y <= height:
                start, end = round(size * left), round(size * right)
                line[3 * start:3 * end] = b"\x9e\xee\xc3" * (end - start)
        pixels.extend(b"\0" + line)
    header = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--bundle", required=True, help="Your own lowercase reverse-DNS app identifier")
    args = parser.parse_args()
    base = args.bundle
    if not re.fullmatch(r"[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*){2,}", base) or base.startswith("app.recly"):
        parser.error("Use a separate reverse-DNS identifier with at least three components")
    dest = args.destination.resolve()
    root = Path(__file__).resolve().parents[2]
    if dest.exists() or root in dest.parents:
        parser.error("Choose a new directory outside the source checkout")
    commit = subprocess.check_output(["git", "-C", root, "rev-parse", "HEAD"], text=True).strip()
    subprocess.run(["git", "clone", "--no-local", "--quiet", str(root), str(dest)], check=True)
    subprocess.run(["git", "-C", dest, "checkout", "--quiet", "--detach", commit], check=True)
    apple = dest / "apple"
    for path in apple.rglob("*"):
        if path.suffix not in {".pbxproj", ".plist", ".entitlements", ".swift", ".xcstrings", ".example"}:
            continue
        old = path.read_text()
        new = old.replace("app.recly", base)
        if path.name == "InfoPlist.xcstrings":
            new = new.replace("Recly", "Field Loom")
        if path.suffix == ".pbxproj":
            new = new.replace("CFBundleDisplayName = Recly;", 'CFBundleDisplayName = "Field Loom";')
            new = new.replace('"Import to Recly"', '"Import to Field Loom"')
        if path.name == "Info.plist":
            new = new.replace("<string>Recly</string>", "<string>Field Loom</string>")
        if path.name == "DesktopUpdater.swift":
            init = "public static let shared: DesktopUpdater? = fromEnvironment()"
            if new.count(init) != 1:
                raise RuntimeError("Review upstream updater before preparing a private build")
            new = new.replace(init, "public static let shared: DesktopUpdater? = nil")
        if old != new:
            path.write_text(new)
    for catalog in apple.rglob("AppIcon.appiconset"):
        for entry in json.loads((catalog / "Contents.json").read_text())["images"]:
            size = int(entry["size"].split("x")[0]) * int(entry.get("scale", "1x")[0])
            (catalog / entry["filename"]).write_bytes(icon(size))
    config = apple / "Config/Local.xcconfig"
    config.write_text(f"RECLY_ICLOUD_CONTAINER = iCloud.{base}\n"
                      "RECLY_PHONE_ENTITLEMENTS = RecPhone/RecPhone-iCloud.entitlements\n"
                      "RECLY_MAC_ENTITLEMENTS[config=Release] = RecMac/RecMac-iCloud.entitlements\n")
    print(f"Prepared {commit} for private device testing: {base} / iCloud.{base}")


if __name__ == "__main__":
    main()
