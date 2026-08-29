"""
Checks that every method the mod calls on itself actually exists.

The mod cannot be compiled here - Minecraft and the Yarn mappings are not
available - and javac is no help at all for this: with the argument types
unresolved it never tries to resolve the calls either, and reports nothing. A
method that was never written, or one left behind by a rename, therefore sails
straight through a compile check.

So this reads the source instead. For every class of ours it collects what that
class declares, adds what it inherits from another of our classes, and then
looks at every call written without a receiver - `walkSellFlow(client, now)`
rather than `something.walkSellFlow(...)`. Anything called that way and never
declared is a call into thin air.

Classes that extend a Minecraft type are skipped: what they inherit cannot be
known from here.
"""

import glob
import os
import re
import sys

# Extend Minecraft types, so their inherited methods are invisible to this.
SKIP = {
    "BlueprintMenuScreen",
    "HudEditorScreen",
    "FlipSettingsScreen",
    "BlueprintTitleScreen",
    "BlueprintWelcomeScreen",
}

# Words that are followed by a bracket without being a method call.
KEYWORDS = {
    "if", "for", "while", "switch", "catch", "return", "new", "super", "this",
    "do", "else", "synchronized", "try", "assert", "throw", "case", "yield",
    "record", "enum", "class", "interface", "instanceof", "void", "boolean",
    "int", "long", "double", "float", "char", "byte", "short",
}

DECLARATION = re.compile(
    r"^[ \t]*(?:@\w+\s+)*(?:(?:public|private|protected|static|final|abstract|synchronized|default|native)\s+)+"
    r"[\w.<>\[\],?\s]+?\s(\w+)\s*\(",
    re.MULTILINE,
)
COMPACT_DECLARATION = re.compile(r"^[ \t]*(?:private|public|protected)?\s*(\w+)\s*\([^)]*\)\s*\{", re.MULTILINE)
CALL = re.compile(r"(?<![\w.$])(\w+)\s*\(")
EXTENDS = re.compile(r"\b(?:class|interface)\s+(\w+)\s+extends\s+([\w.]+)")


def strip_noise(source):
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    source = re.sub(r"//[^\n]*", " ", source)
    source = re.sub(r'"(?:\\.|[^"\\])*"', '""', source)
    source = re.sub(r"'(?:\\.|[^'\\])*'", "''", source)
    return source


def main(root):
    files = sorted(glob.glob(os.path.join(root, "**", "*.java"), recursive=True))
    declared, parents, calls = {}, {}, {}

    for path in files:
        name = os.path.basename(path)[: -len(".java")]
        source = strip_noise(open(path).read())
        declared[name] = set(DECLARATION.findall(source)) | set(COMPACT_DECLARATION.findall(source))
        # Every class in the file, nested ones included: a nested class
        # extending one of ours inherits its methods too.
        parents[name] = {parent.split(".")[-1] for _, parent in EXTENDS.findall(source)}
        calls[name] = [(path, call) for call in CALL.findall(source)]

    problems = []
    for name in sorted(calls):
        if name in SKIP:
            continue
        known = set(declared[name])
        pending = list(parents.get(name, ()))
        outside = False
        while pending:
            parent = pending.pop()
            if parent not in declared:
                # Extends something outside the mod: unknowable from here.
                outside = True
                continue
            known |= declared[parent]
            pending.extend(parents.get(parent, ()))
        if outside:
            continue

        for path, call in calls[name]:
            if call in KEYWORDS or call in known or call[:1].isupper():
                continue
            problems.append("%s: calls %s(), which nothing declares" % (os.path.basename(path), call))

    for problem in sorted(set(problems)):
        print(problem)
    if problems:
        print("\n%d call(s) into thin air." % len(set(problems)))
        return 1
    print("Every method the mod calls on itself exists.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
