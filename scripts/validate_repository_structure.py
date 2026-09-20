#!/usr/bin/env python3
"""Fast, dependency-free checks for root Gradle graph and retired core surfaces.

This intentionally runs without Gradle/JDK so structural drift is caught before
configuration. The real Gradle build remains authoritative.
"""

from __future__ import annotations

import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / "settings.gradle.kts"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def root_modules() -> set[str]:
    text = SETTINGS.read_text(encoding="utf-8")
    # Root-project module paths are the only quoted values beginning with ':' in
    # this settings file. Composite builds have their own settings files.
    return set(re.findall(r'"(:[A-Za-z0-9_.:-]+)"', text))


def module_dir(module: str) -> Path:
    return ROOT.joinpath(*module.removeprefix(":").split(":"))


def has_nested_settings(path: Path) -> bool:
    for parent in (path, *path.parents):
        if parent == ROOT:
            return False
        if (parent / "settings.gradle.kts").is_file() or (parent / "settings.gradle").is_file():
            return True
    return False


def validate_gradle_graph() -> None:
    modules = root_modules()
    if not modules:
        fail("no root modules parsed from settings.gradle.kts")

    missing = []
    for module in sorted(modules):
        directory = module_dir(module)
        if not directory.is_dir():
            missing.append(f"{module} -> missing directory {directory.relative_to(ROOT)}")
            continue
        if not ((directory / "build.gradle.kts").is_file() or (directory / "build.gradle").is_file()):
            missing.append(f"{module} -> missing Gradle build file")
    if missing:
        fail("declared root modules are invalid:\n  " + "\n  ".join(missing))

    undeclared = []
    build_files = [*ROOT.rglob("build.gradle"), *ROOT.rglob("build.gradle.kts")]
    for build_file in sorted(build_files):
        if build_file.parent == ROOT or "build" in build_file.parts or ".gradle" in build_file.parts:
            continue
        relative = build_file.parent.relative_to(ROOT)
        # Standalone sample builds and checked-in Gradle test fixtures are not root modules.
        if (
            relative.parts[0] == "composite-builds"
            or has_nested_settings(build_file.parent)
            or relative.parts[:2] == ("testing", "resources")
            or ("src" in relative.parts and "test" in relative.parts and "resources" in relative.parts)
        ):
            continue
        module = ":" + ":".join(relative.parts)
        if module not in modules:
            undeclared.append(f"{module} ({build_file.relative_to(ROOT)})")
    if undeclared:
        fail("Gradle projects exist outside the root graph:\n  " + "\n  ".join(undeclared))

    print(f"OK: {len(modules)} declared root modules resolve and no undeclared root projects remain")


def validate_retired_surfaces() -> None:
    retired_paths = (
        ".gitmodules",
        "E2E_TESTING_REPORT.md",
        "app/src/main/java/com/itsaky/androidide/agent",
        "app/src/main/res/drawable/ic_ai.xml",
        "app/src/main/res/layout/fragment_ai_settings.xml",
        "app/src/main/res/layout/layout_ai_disclaimer.xml",
        "app/src/main/res/layout/layout_settings_local_llm.xml",
        "compose-preview",
        "docs/third_party/llama_cpp.version",
        "scripts/llama_cpp_status.sh",
        "scripts/update_llama_cpp.sh",
        "scripts/update_llama_cpp_full.sh",
        "subprojects/llama.cpp",
        "subprojects/project-serial",
        "subprojects/project-serialization",
        "vectormaster",
    )
    present = [path for path in retired_paths if (ROOT / path).exists()]
    if present:
        fail("retired paths returned:\n  " + "\n  ".join(present))

    checks = {
        "settings.gradle.kts": (":compose-preview",),
        "build.gradle.kts": ("LayoutEditor/**/*",),
        "CODEOWNERS": ("./LayoutEditor/",),
        ".githooks/pre-push/0002-architecture-review-nudge": ("LayoutEditor/", "llama\\.cpp"),
    }
    stale = []
    for relative, needles in checks.items():
        text = (ROOT / relative).read_text(encoding="utf-8")
        stale.extend(f"{relative}: {needle}" for needle in needles if needle in text)
    if stale:
        fail("retired references returned:\n  " + "\n  ".join(stale))

    print("OK: retired Compose Preview, AI/llama, Layout Editor, and orphan-project paths stay absent")


def validate_catalog_cleanup() -> None:
    catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))
    retired_versions = {
        "activityKtx",
        "colorpickerview",
        "constraintlayout",
        "coreKtxVersion",
        "monitor",
        "paletteKtx",
        "preferenceKtxVersion",
        "recyclerview",
        "zoomage",
    }
    retired_libraries = {
        "androidx-activity-ktx",
        "androidx-constraintlayout-v214",
        "androidx-core-ktx-v1170",
        "androidx-monitor",
        "androidx-palette-ktx",
        "androidx-preference-ktx",
        "androidx-recyclerview-v132",
        "colorpickerview",
        "common-lsp4j",
        "sora-language-textmate",
        "tests-androidx-test-monitor",
        "tests-androidx-work-testing",
        "xml-jb-annotations",
        "zoomage",
    }
    remaining_versions = retired_versions & catalog.get("versions", {}).keys()
    remaining_libraries = retired_libraries & catalog.get("libraries", {}).keys()
    if remaining_versions or remaining_libraries:
        fail(
            "retired catalog entries returned: "
            f"versions={sorted(remaining_versions)}, libraries={sorted(remaining_libraries)}"
        )
    print("OK: 14 unused aliases and their 9 orphan version keys stay removed")


def main() -> None:
    validate_gradle_graph()
    validate_retired_surfaces()
    validate_catalog_cleanup()


if __name__ == "__main__":
    main()
