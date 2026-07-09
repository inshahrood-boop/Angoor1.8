#!/usr/bin/env python3
"""Static validation for the Angoor Android repository (standard library only)."""

from __future__ import annotations

import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def fail(message: str) -> None:
    print(f"[FAIL] {message}", file=sys.stderr)
    raise SystemExit(1)


def ok(message: str) -> None:
    print(f"[PASS] {message}")


def check_required_files() -> None:
    required = [
        "settings.gradle",
        "build.gradle",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
        "app/build.gradle",
        "app/proguard-rules.pro",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/assets/index.html",
        ".github/workflows/build-apk.yml",
    ]
    missing = [name for name in required if not (ROOT / name).is_file()]
    if missing:
        fail("Missing required files: " + ", ".join(missing))
    ok(f"Required project files are present ({len(required)})")


def check_xml_and_manifest() -> None:
    xml_files = sorted((ROOT / "app/src/main").rglob("*.xml"))
    for path in xml_files:
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            fail(f"Invalid XML in {path.relative_to(ROOT)}: {exc}")
    ok(f"Android XML files parsed successfully ({len(xml_files)})")

    manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
    manifest = ET.parse(manifest_path).getroot()
    declared: list[str] = []
    for tag in ("activity", "receiver", "provider", "service"):
        for element in manifest.findall(f".//{tag}"):
            name = element.attrib.get(ANDROID_NS + "name", "")
            if name.startswith("."):
                declared.append(name[1:])
    missing_classes = [
        name
        for name in declared
        if not (ROOT / "app/src/main/java/ir/angoor/app" / f"{name}.java").is_file()
    ]
    if missing_classes:
        fail("Manifest classes without Java source: " + ", ".join(missing_classes))
    ok(f"Manifest component classes are present ({len(declared)})")


def check_html_and_javascript() -> None:
    html_path = ROOT / "app/src/main/assets/index.html"
    html = html_path.read_text(encoding="utf-8")
    required_markers = [
        "window.AngoorAndroid",
        "syncNativeReminders",
        "onAngoorBiometricResult",
        "onAngoorSpeechResult",
        "lockType:'none'",
        "theme:'light'",
        "VOICE_COMMAND_WAIT_MS=10000",
        "VOICE_REVIEW_WAIT_MS=20000",
        'id="barChart"',
    ]
    missing = [marker for marker in required_markers if marker not in html]
    if missing:
        fail("HTML is missing Android integration markers: " + ", ".join(missing))

    scripts = re.findall(r"<script(?:\s[^>]*)?>(.*?)</script>", html, flags=re.I | re.S)
    if not scripts:
        fail("No inline JavaScript block found in index.html")

    node = shutil.which("node")
    if node:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".js", delete=False) as handle:
            handle.write("\n;\n".join(scripts))
            temp_path = Path(handle.name)
        try:
            result = subprocess.run(
                [node, "--check", str(temp_path)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            if result.returncode != 0:
                fail("JavaScript syntax check failed:\n" + result.stderr.strip())
        finally:
            temp_path.unlink(missing_ok=True)
        ok("Inline JavaScript syntax passed node --check")
    else:
        print("[SKIP] Node.js is not installed; JavaScript syntax check skipped")

    ok("HTML Android bridge markers and default unlocked state are present")


def check_native_bridge() -> None:
    bridge = (ROOT / "app/src/main/java/ir/angoor/app/AndroidBridge.java").read_text(encoding="utf-8")
    activity = (ROOT / "app/src/main/java/ir/angoor/app/MainActivity.java").read_text(encoding="utf-8")
    required_methods = [
        "scheduleReminder",
        "cancelReminder",
        "syncReminders",
        "authenticateBiometric",
        "startSpeechRecognition",
        "saveFile",
        "shareFile",
        "setSystemTheme",
    ]
    missing = [name for name in required_methods if not re.search(rf"\b{name}\s*\(", bridge)]
    if missing:
        fail("AndroidBridge is missing methods: " + ", ".join(missing))
    if "WindowInsetsCompat.Type.systemBars()" not in activity:
        fail("MainActivity does not apply Android system bar insets")
    if "canUseFullScreenIntent" not in activity:
        fail("MainActivity does not handle full-screen alarm access")
    for marker in ("TRANSACTION_SPEECH_WINDOW_MS = 10_000L", "REVIEW_SPEECH_WINDOW_MS = 20_000L", "onFileSaved"):
        if marker not in activity:
            fail(f"MainActivity is missing Android integration marker: {marker}")
    strings = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
    if '<string name="app_name">Angoor</string>' not in strings:
        fail("Launcher app name must be Angoor")
    ok("Native Android bridge, insets, speech windows, downloads and alarm permission flow are present")


def png_size(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        fail(f"Invalid PNG file: {path.relative_to(ROOT)}")
    return struct.unpack(">II", data[16:24])


def check_icons_and_wrapper() -> None:
    icon = ROOT / "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
    foreground = ROOT / "app/src/main/res/drawable-nodpi/ic_launcher_foreground.png"
    if png_size(icon) != (192, 192):
        fail("xxxhdpi launcher icon must be 192x192")
    if png_size(foreground) != (432, 432):
        fail("Adaptive icon foreground must be 432x432")
    ok("Launcher and adaptive icon dimensions are valid")

    wrapper = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    try:
        with zipfile.ZipFile(wrapper) as archive:
            names = set(archive.namelist())
            if "org/gradle/wrapper/GradleWrapperMain.class" not in names:
                fail("Gradle wrapper JAR is missing GradleWrapperMain")
    except zipfile.BadZipFile as exc:
        fail(f"Invalid Gradle wrapper JAR: {exc}")
    ok("Gradle wrapper JAR is structurally valid")


def check_branding() -> None:
    searchable_suffixes = {".java", ".xml", ".gradle", ".properties", ".html", ".md", ".yml"}
    legacy_hits: list[str] = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in searchable_suffixes:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        if re.search(r"dantal|دانطل", text, re.I):
            legacy_hits.append(str(path.relative_to(ROOT)))
    if legacy_hits:
        fail("Legacy Dantal branding remains in: " + ", ".join(legacy_hits))
    ok("No legacy Dantal branding remains")


def main() -> None:
    check_required_files()
    check_xml_and_manifest()
    check_html_and_javascript()
    check_native_bridge()
    check_icons_and_wrapper()
    check_branding()
    print("\nAngoor static validation completed successfully.")


if __name__ == "__main__":
    main()
