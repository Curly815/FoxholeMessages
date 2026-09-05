#!/usr/bin/env python3
"""Report how much of the app R8 actually renamed, from its mapping file.

Play Console's Android vitals flags apps whose "Obfuscation" coverage sits under
25%, but it only tells you weeks after a release. R8's mapping file already has
the answer at build time: every class it processed appears as

    com.example.Foo -> a.b.c:

so a class was obfuscated when its mapped name differs from its original one.
This prints that percentage plus the packages contributing most of what stayed
unrenamed, which is where any further keep-rule tightening should be aimed.
"""

import collections
import sys

PLAY_THRESHOLD = 25.0


def parse(path):
    """Yield (original, obfuscated) for each class line in an R8 mapping file."""
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            # Member mappings are indented; class mappings are not and end in ':'
            if not line[:1].strip() or line.startswith("#"):
                continue
            line = line.rstrip("\n")
            if not line.endswith(":") or " -> " not in line:
                continue
            original, obfuscated = line[:-1].split(" -> ", 1)
            yield original, obfuscated


def main():
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <mapping.txt>", file=sys.stderr)
        return 2

    total = 0
    renamed = 0
    kept_by_package = collections.Counter()

    for original, obfuscated in parse(sys.argv[1]):
        total += 1
        if original != obfuscated:
            renamed += 1
        else:
            package = original.rsplit(".", 1)[0] if "." in original else "(default)"
            kept_by_package[package] += 1

    if total == 0:
        print("No class mappings found - was this build actually minified?")
        return 1

    percent = renamed / total * 100
    verdict = "at or above" if percent >= PLAY_THRESHOLD else "BELOW"

    print("## Obfuscation coverage")
    print()
    print(f"- Classes processed by R8: **{total}**")
    print(f"- Renamed: **{renamed}**")
    print(f"- Kept under original name: **{total - renamed}**")
    print(f"- Coverage: **{percent:.1f}%** ({verdict} Play's {PLAY_THRESHOLD:.0f}% threshold)")
    print()

    if kept_by_package:
        print("Packages contributing most of the unrenamed classes:")
        print()
        print("| Package | Kept |")
        print("| --- | ---: |")
        for package, count in kept_by_package.most_common(15):
            print(f"| `{package}` | {count} |")
        print()

    if percent < PLAY_THRESHOLD:
        print(
            f"::warning title=Obfuscation below Play threshold::"
            f"{percent:.1f}% of classes renamed, under the {PLAY_THRESHOLD:.0f}% "
            f"Play Console expects.",
            file=sys.stderr,
        )

    return 0


if __name__ == "__main__":
    sys.exit(main())
