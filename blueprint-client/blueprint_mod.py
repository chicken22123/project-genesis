"""Building the Blueprint Client mod and getting it into an instance.

The mod lives beside this launcher in the repository as source, not as a jar, so
installing it means building it first. That needs a JDK 21 and Gradle, and the
first build is slow - Gradle fetches Minecraft and the mappings before it can
compile anything. Afterwards the jar is reused, and only rebuilt when a source
file has changed since it was made.
"""

import os
import shutil
import subprocess
import time

MOD_FOLDER = "blueprint-client-mod"
BUILD_TIMEOUT_SECONDS = 30 * 60
# Loom writes more than one jar; these are the ones that are not the mod.
NOT_THE_MOD = ("-sources", "-dev", "-shadow", "-slim")


class BuildError(Exception):
    """A build that did not produce a jar, with enough output to say why."""

    def __init__(self, message, output=""):
        super().__init__(message)
        self.output = output


def source_dir():
    """The mod's folder, beside the launcher in the repository."""
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(os.path.dirname(here), MOD_FOLDER)


def available():
    return os.path.isfile(os.path.join(source_dir(), "build.gradle"))


def built_jar():
    """The newest jar Gradle has produced, or an empty string."""
    libs = os.path.join(source_dir(), "build", "libs")
    if not os.path.isdir(libs):
        return ""

    newest, newest_time = "", 0.0
    for name in os.listdir(libs):
        if not name.endswith(".jar") or any(part in name for part in NOT_THE_MOD):
            continue
        path = os.path.join(libs, name)
        stamp = os.path.getmtime(path)
        if stamp > newest_time:
            newest, newest_time = path, stamp
    return newest


def _newest_source_change():
    """When the mod was last edited: sources, or the build files themselves."""
    latest = 0.0
    root = source_dir()
    for folder in (os.path.join(root, "src"), root):
        for base, _dirs, files in os.walk(folder):
            if os.path.join("build", "libs") in base or base.endswith(".gradle"):
                continue
            for name in files:
                if name.endswith((".java", ".json", ".gradle", ".properties")):
                    latest = max(latest, os.path.getmtime(os.path.join(base, name)))
        if folder == root:
            break
    return latest


def needs_build():
    jar = built_jar()
    if not jar:
        return True
    return _newest_source_change() > os.path.getmtime(jar)


def gradle_command():
    """How to run Gradle here: the wrapper if the repository has one, else Gradle."""
    root = source_dir()
    for wrapper in ("gradlew.bat", "gradlew"):
        path = os.path.join(root, wrapper)
        if os.path.isfile(path):
            # A .bat is not an executable as far as CreateProcess is concerned.
            return ["cmd", "/c", path] if wrapper.endswith(".bat") else [path]

    found = shutil.which("gradle")
    return [found] if found else []


def build():
    """Build the mod and return the jar. Raises {@link BuildError} on failure."""
    command = gradle_command()
    if not command:
        raise BuildError(
            "Gradle is not installed, and the mod has no Gradle wrapper.\n\n"
            "Install Gradle (and a JDK 21) and try again, or open the "
            f"{MOD_FOLDER} folder in IntelliJ IDEA and build it there."
        )

    kwargs = {
        "cwd": source_dir(),
        "stdin": subprocess.DEVNULL,
        "stdout": subprocess.PIPE,
        "stderr": subprocess.STDOUT,
        "text": True,
        "errors": "replace",
    }
    if os.name == "nt":
        # No console window flashing up behind the launcher.
        kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0)

    try:
        finished = subprocess.run(
            command + ["build", "-x", "test"], timeout=BUILD_TIMEOUT_SECONDS, **kwargs
        )
    except subprocess.TimeoutExpired as exc:
        raise BuildError("The build took longer than half an hour and was stopped.") from exc
    except OSError as exc:
        raise BuildError(f"Could not start Gradle: {exc}") from exc

    output = finished.stdout or ""
    if finished.returncode != 0:
        raise BuildError("Gradle could not build the mod.", _tail(output))

    jar = built_jar()
    if not jar:
        raise BuildError("The build reported success but produced no jar.", _tail(output))
    return jar


def ensure_jar():
    """The jar to install, building it first if the source has moved on."""
    if needs_build():
        return build()
    return built_jar()


def describe():
    """A line for the status bar: what would happen if the button were pressed."""
    if not available():
        return f"The {MOD_FOLDER} folder is not next to the launcher."
    jar = built_jar()
    if not jar:
        return "Not built yet - the first build downloads Minecraft and takes a few minutes."
    if needs_build():
        return "The mod has changed since it was last built; it will be rebuilt."
    age = max(0, int((time.time() - os.path.getmtime(jar)) / 60))
    return f"{os.path.basename(jar)}, built {age} minute(s) ago."


def _tail(output, lines=14):
    """The end of a build log, which is where Gradle says what went wrong."""
    kept = [line for line in output.splitlines() if line.strip()]
    return "\n".join(kept[-lines:])
