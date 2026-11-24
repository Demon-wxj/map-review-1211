"""Build Map Review 1.0.5 compat4 from the original 1.0.5 release JAR."""

import argparse
import hashlib
import io
import json
from pathlib import Path
from zipfile import ZipFile


ORIGINAL_SHA256 = "1ad282c54388569d1547502daad68c4ab47cfcae42902dbe1d4fce9c68d99f86"
METADATA = "fabric.mod.json"
VERSION = "1.0.5+mc1.21.1.compat4"
DEPENDENCIES = {
    "megawalls": "1.18.45-bed-items-mc1.21.1",
    "tabletop": "0.5.14+mc1.21.1",
}


def build(source: Path, output: Path) -> str:
    original = source.read_bytes()
    if hashlib.sha256(original).hexdigest() != ORIGINAL_SHA256:
        raise ValueError("Input must be the original Map Review 1.0.5+mc1.21.1 release JAR")

    buffer = io.BytesIO()
    with ZipFile(io.BytesIO(original)) as before:
        metadata = json.loads(before.read(METADATA))
        metadata["version"] = VERSION
        metadata["depends"].update(DEPENDENCIES)
        updated = (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

        with ZipFile(buffer, "w") as after:
            after.comment = before.comment
            for entry in before.infolist():
                payload = updated if entry.filename == METADATA else before.read(entry)
                after.writestr(entry, payload)

    jar = buffer.getvalue()
    with ZipFile(io.BytesIO(original)) as before, ZipFile(io.BytesIO(jar)) as after:
        names = before.namelist()
        if names != after.namelist() or after.testzip() is not None:
            raise ValueError("Output JAR failed its archive integrity check")
        changed = [name for name in names if before.read(name) != after.read(name)]
        if changed != [METADATA]:
            raise ValueError("Output JAR changed entries other than fabric.mod.json")

    with output.open("xb") as destination:
        destination.write(jar)
    return hashlib.sha256(jar).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Original 1.0.5 release JAR")
    parser.add_argument("output", type=Path, help="Path for the new compat4 JAR")
    args = parser.parse_args()
    try:
        digest = build(args.source, args.output)
    except (OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")
    print(digest)


if __name__ == "__main__":
    main()
