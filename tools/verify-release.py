"""Check the release excludes dependencies/harness and matches tested classes."""
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
with ZipFile(root / "build/libs/mekasuit-arcana-0.1.0.jar") as release, ZipFile(
    root / "build/libs/mekasuit-arcana-0.1.0-runtime-test.jar"
) as harness:
    classes = [name for name in release.namelist() if name.endswith(".class")]
    if not classes or any(not n.startswith("dev/vvh/mekasuitarcana/") for n in classes):
        raise SystemExit("Unexpected bundled dependency classes or empty release")
    if any("RuntimeVerification" in n or "RuntimeTimingVerification" in n for n in classes):
        raise SystemExit("Runtime harness leaked into release")
    if any(release.read(n) != harness.read(n) for n in classes):
        raise SystemExit("Production bytecode differs between release and harness")
    recipes = [n for n in release.namelist() if n.startswith("data/mekasuitarcana/recipe/") and n.endswith(".json")]
    if len(recipes) != 5:
        raise SystemExit("Expected five recipes")
    print(f"PASS: {len(classes)} production classes, five recipes, no bundled dependencies or harness")
