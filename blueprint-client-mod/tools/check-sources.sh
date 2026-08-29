#!/bin/sh
# Looks for calls to things that are not there.
#
# The mod cannot be compiled without Minecraft and the Yarn mappings, so most of
# what javac says here is "cannot find symbol: net.minecraft..." and has to be
# ignored. What must not be ignored is javac saying that about *our own* code: a
# call to a method that was never written, or one left behind by a rename. Those
# errors are the ones whose "location:" is a com.blueprintclient class, and this
# prints exactly those, plus anything that is not a resolution error at all.
#
# Screen classes are left out: they extend Minecraft's Screen, which is not
# there either, so every inherited field they touch looks missing too.
set -e

here=$(dirname "$0")
root=$(cd "$here/.." && pwd)
out=$(mktemp -d)
log="$out/javac.log"
trap 'rm -rf "$out"' EXIT

javac -Xmaxerrs 100000 -d "$out/classes" $(find "$root/src/main/java" -name '*.java') 2>"$log" || true

python3 - "$log" "$root/src/main/java" <<'XPYX'
import glob
import os
import re
import sys

# These extend Minecraft types, so their inherited members cannot resolve here
# either, and every one of them would look like a mistake.
SCREENS = (
    "BlueprintMenuScreen",
    "HudEditorScreen",
    "FlipSettingsScreen",
    "BlueprintTitleScreen",
    "BlueprintWelcomeScreen",
)
# Errors that are only ever Minecraft not being on the classpath.
IGNORED_HEADS = (
    "package ",
    "cannot access",
    "method does not override or implement a method from a supertype",
)

OURS = {os.path.basename(f)[: -len(".java")] for f in glob.glob(sys.argv[2] + "/**/*.java", recursive=True)}
OURS -= set(SCREENS)

blocks, current = [], None
for line in open(sys.argv[1]).read().splitlines():
    if re.match(r"^\S.*:\d+: error: ", line):
        if current:
            blocks.append(current)
        current = [line]
    elif current is not None:
        current.append(line)
if current:
    blocks.append(current)

problems = []
for block in blocks:
    head = block[0].split("error: ", 1)[1].strip()
    text = "\n".join(block).rstrip()
    symbol = next((l.split("symbol:", 1)[1].strip() for l in block if l.strip().startswith("symbol:")), "")
    location = next((l.split("location:", 1)[1].strip() for l in block if l.strip().startswith("location:")), "")
    where = ""
    if location:
        where = re.split(r"[.<]", re.sub(r"^\w+\s+", "", location).replace("com.blueprintclient.", ""))[-1]
    inside_ours = where in OURS

    if head.startswith("cannot find symbol"):
        # A missing Minecraft type is expected here; a missing method or field
        # of ours is a call to something that was never written. Minecraft's
        # classes are capitalised and ours are not, which separates the two.
        kind, _, name = symbol.partition(" ")
        missing_ours = kind == "method" or (kind == "variable" and name.strip()[:1].islower())
        if inside_ours and missing_ours:
            problems.append(text)
        continue

    if not any(head.startswith(i) for i in IGNORED_HEADS):
        problems.append(text)

if problems:
    print("\n\n".join(problems))
    print("\n%d problem(s) in the mod's own code." % len(problems))
    sys.exit(1)
print("No calls to anything missing. (Minecraft's own symbols cannot be checked here.)")
XPYX

# javac can say nothing about calls between our own methods while Minecraft is
# missing, so the source is read for those separately.
python3 "$here/check-calls.py" "$root/src/main/java"
