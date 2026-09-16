"""Fetch exact compile-only mod jars; verify SHA-256 before installing."""
import hashlib
import json
from pathlib import Path
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    folder = ROOT / "libs"
    folder.mkdir(exist_ok=True)
    entries = json.loads((ROOT / "tools/dependencies.json").read_text())
    for entry in entries:
        name = entry["file"]
        if Path(name).name != name or not entry["url"].startswith("https://cdn.modrinth.com/"):
            raise ValueError("Invalid dependency manifest entry")
        target = folder / name
        if target.exists():
            if digest(target) != entry["sha256"]:
                raise ValueError(f"Existing {name} has an unexpected hash; move it aside and retry")
            print(f"Verified {name}")
            continue
        temporary = None
        try:
            request = urllib.request.Request(entry["url"], headers={
                "User-Agent": "H-H-E/mekasuit-arcana dependency-bootstrap"
            })
            with tempfile.NamedTemporaryFile(dir=folder, suffix=".download", delete=False) as out:
                temporary = Path(out.name)
                with urllib.request.urlopen(request, timeout=120) as response:
                    while chunk := response.read(1024 * 1024):
                        out.write(chunk)
            if digest(temporary) != entry["sha256"]:
                raise ValueError(f"SHA-256 mismatch: {name}")
            temporary.replace(target)
            print(f"Downloaded and verified {name}")
        finally:
            if temporary is not None and temporary.exists():
                temporary.unlink()


if __name__ == "__main__":
    main()
