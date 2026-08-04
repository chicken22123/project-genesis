"""Launch Minecraft directly from the Modrinth App's game files.

Blueprint Client never opens the Modrinth App window. Instead this module reads
the Modrinth App data directory - the instance list, version manifests,
libraries, assets, bundled Java runtimes and the signed-in account - and builds
the ``java`` command line itself, the same way the Modrinth App would before it
starts the game.

It can also be run on its own while debugging a machine::

    py modrinth_launcher.py --list
    py modrinth_launcher.py --instance "Fabric 26.2" --dry-run
"""

import hashlib
import json
import os
import platform
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import uuid as uuid_lib
import zipfile

LAUNCHER_NAME = "blueprint-client"
LAUNCHER_VERSION = "1.0.0"

# Modrinth's default when an instance does not override it.
DEFAULT_MAX_MEMORY_MB = 2048


class LaunchError(Exception):
    """Raised when Minecraft cannot be started from the Modrinth files."""


# --------------------------------------------------------------------- system


def _os_name():
    if sys.platform.startswith("win"):
        return "windows"
    if sys.platform == "darwin":
        return "osx"
    return "linux"


def _os_arch():
    machine = platform.machine().lower()
    if machine in ("amd64", "x86_64", "x64"):
        return "x86_64"
    if machine in ("i386", "i486", "i586", "i686", "x86"):
        return "x86"
    if machine in ("arm64", "aarch64"):
        return "arm64"
    return machine


# Manifests are not consistent about how they spell the 64-bit x86 arch.
_ARCH_ALIASES = {
    "x86_64": {"x86_64", "x64", "amd64"},
    "x86": {"x86", "i386", "x32"},
    "arm64": {"arm64", "aarch64"},
}


def _arch_matches(wanted):
    return wanted in _ARCH_ALIASES.get(_os_arch(), {_os_arch()})


def _rule_allows(rules, features=None):
    """Evaluate the ``rules`` block used by libraries and launch arguments."""
    if not rules:
        return True

    features = features or {}
    allowed = False

    for rule in rules:
        applies = True
        os_rule = rule.get("os") or {}

        if "name" in os_rule and os_rule["name"] != _os_name():
            applies = False
        if applies and "arch" in os_rule and not _arch_matches(os_rule["arch"]):
            applies = False
        if applies and "version" in os_rule:
            try:
                if not re.match(os_rule["version"], platform.release()):
                    applies = False
            except re.error:
                applies = False

        if applies:
            for feature, wanted in (rule.get("features") or {}).items():
                if bool(features.get(feature, False)) != bool(wanted):
                    applies = False
                    break

        if applies:
            allowed = rule.get("action", "allow") == "allow"

    return allowed


# ----------------------------------------------------------------- data files


def _candidate_data_dirs():
    home = os.path.expanduser("~")
    names = ["ModrinthApp", "com.modrinth.theseus", "modrinth"]
    roots = []

    if _os_name() == "windows":
        roots.append(os.environ.get("APPDATA", os.path.join(home, "AppData", "Roaming")))
        roots.append(os.environ.get("LOCALAPPDATA", os.path.join(home, "AppData", "Local")))
    elif _os_name() == "osx":
        roots.append(os.path.join(home, "Library", "Application Support"))
    else:
        roots.append(os.environ.get("XDG_DATA_HOME", os.path.join(home, ".local", "share")))
        roots.append(os.path.join(home, ".config"))

    return [os.path.join(root, name) for root in roots if root for name in names]


def find_data_dir(explicit=""):
    """Locate the Modrinth App data directory (the folder holding ``meta/``)."""
    candidates = []
    if explicit:
        candidates.append(os.path.expandvars(os.path.expanduser(explicit)))
    candidates.extend(_candidate_data_dirs())

    for path in candidates:
        if path and os.path.isdir(path) and (
            os.path.isdir(os.path.join(path, "meta"))
            or os.path.isfile(os.path.join(path, "app.db"))
            or os.path.isdir(os.path.join(path, "profiles"))
        ):
            return path

    raise LaunchError(
        "Could not find the Modrinth App data folder. Set \"modrinth_data_dir\" in "
        "blueprint_instance.json to the folder that contains meta\\ and profiles\\ "
        "(normally %APPDATA%\\ModrinthApp)."
    )


def _open_database(db_path):
    """Open ``app.db`` read-only, falling back to a copy if it is locked."""
    if not os.path.isfile(db_path):
        return None

    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        conn.row_factory = sqlite3.Row
        conn.execute("SELECT name FROM sqlite_master LIMIT 1").fetchone()
        return conn
    except sqlite3.Error:
        pass

    # The Modrinth App may be holding the database open; work from a snapshot.
    try:
        temp_dir = tempfile.mkdtemp(prefix="blueprint-db-")
        copy_path = os.path.join(temp_dir, "app.db")
        for suffix in ("", "-wal", "-shm"):
            source = db_path + suffix
            if os.path.isfile(source):
                shutil.copyfile(source, copy_path + suffix)
        conn = sqlite3.connect(copy_path)
        conn.row_factory = sqlite3.Row
        return conn
    except (OSError, sqlite3.Error):
        return None


class ModrinthInstall:
    """Read-only view over one Modrinth App installation on this machine."""

    def __init__(self, data_dir):
        self.dir = data_dir
        self.meta_dir = os.path.join(data_dir, "meta")
        self.profiles_dir = os.path.join(data_dir, "profiles")
        self.versions_dir = os.path.join(self.meta_dir, "versions")
        self.libraries_dir = os.path.join(self.meta_dir, "libraries")
        self.assets_dir = os.path.join(self.meta_dir, "assets")
        self.natives_dir = os.path.join(self.meta_dir, "natives")
        self.java_dir = os.path.join(self.meta_dir, "java_versions")
        self.db = _open_database(os.path.join(data_dir, "app.db"))

    # -- small sqlite helpers; the schema changes between Modrinth releases, so
    # -- every read probes for the columns it wants instead of assuming them.

    def _tables(self):
        if not self.db:
            return []
        rows = self.db.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        ).fetchall()
        return [row["name"] for row in rows]

    def _columns(self, table):
        if not self.db:
            return []
        try:
            return [row["name"] for row in self.db.execute(f"PRAGMA table_info('{table}')")]
        except sqlite3.Error:
            return []

    def _rows(self, table):
        if not self.db or table not in self._tables():
            return []
        try:
            return [dict(row) for row in self.db.execute(f"SELECT * FROM '{table}'")]
        except sqlite3.Error:
            return []

    def _first_table(self, *names):
        tables = self._tables()
        for name in names:
            if name in tables:
                return name
        return ""

    # ------------------------------------------------------------- instances

    def instances(self):
        """Every instance Modrinth knows about, newest first."""
        table = self._first_table("instances", "profiles")
        rows = self._rows(table)

        found = []
        for row in rows:
            path = _pick(row, "path", "install_path", "profile_path") or ""
            found.append(
                {
                    "name": _pick(row, "name", "title") or os.path.basename(path),
                    "path": self._resolve_profile_path(path),
                    "game_version": _pick(row, "game_version", "mc_version", "minecraft_version"),
                    "loader": (_pick(row, "mod_loader", "loader", "modloader") or "").lower(),
                    "loader_version": _pick(row, "mod_loader_version", "loader_version"),
                    "java_path": _pick(row, "java_path", "override_java_path"),
                    "memory_max": _pick_int(row, "override_memory_max", "memory_max", "max_memory"),
                    "extra_args": _pick(row, "override_extra_launch_args", "extra_launch_args"),
                    "env_vars": _pick(row, "override_custom_env_vars", "custom_env_vars"),
                    "width": _pick_int(row, "override_width", "game_resolution_x", "width"),
                    "height": _pick_int(row, "override_height", "game_resolution_y", "height"),
                    "last_played": _pick(row, "last_played") or "",
                }
            )

        if not found and os.path.isdir(self.profiles_dir):
            # No usable database: fall back to the folders on disk.
            for name in sorted(os.listdir(self.profiles_dir)):
                path = os.path.join(self.profiles_dir, name)
                if os.path.isdir(path):
                    found.append({"name": name, "path": path, "last_played": ""})

        found.sort(key=lambda item: str(item.get("last_played") or ""), reverse=True)
        return found

    def _resolve_profile_path(self, path):
        if not path:
            return ""
        path = os.path.expandvars(os.path.expanduser(path))
        if os.path.isabs(path):
            return path
        return os.path.join(self.profiles_dir, path)

    def find_instance(self, name="", path=""):
        instances = self.instances()

        if path:
            wanted = os.path.normcase(os.path.abspath(os.path.expandvars(path)))
            for instance in instances:
                if instance.get("path") and os.path.normcase(
                    os.path.abspath(instance["path"])
                ) == wanted:
                    return instance
            if os.path.isdir(path):
                return {"name": name or os.path.basename(path.rstrip("\\/")), "path": path}

        if name:
            for instance in instances:
                if (instance.get("name") or "").strip().lower() == name.strip().lower():
                    return instance
            guess = os.path.join(self.profiles_dir, name)
            if os.path.isdir(guess):
                return {"name": name, "path": guess}

        if instances:
            return instances[0]

        raise LaunchError(
            "No Minecraft instance was found in the Modrinth App folder. Create the "
            "instance in Modrinth once so its files exist, then launch from here."
        )

    # -------------------------------------------------------------- accounts

    def account(self):
        """The signed-in Minecraft account, if the Modrinth App stored one."""
        table = self._first_table("minecraft_users", "minecraft_user", "accounts", "users")
        rows = self._rows(table)
        if not rows:
            return None

        active = [row for row in rows if row.get("active")]
        row = (active or rows)[0]

        username = _pick(row, "username", "name", "profile_name")
        account_uuid = _pick(row, "uuid", "id", "profile_id")
        token = _pick(row, "access_token", "token", "mc_token") or ""
        if not username or not account_uuid:
            return None

        return {
            "username": username,
            "uuid": str(account_uuid).replace("-", ""),
            "access_token": token,
            "xuid": _pick(row, "xuid", "user_id") or "0",
            "expires": _pick(row, "expires", "expires_at") or "",
            "online": bool(token),
        }

    # ------------------------------------------------------------ java + prefs

    def settings(self):
        table = self._first_table("settings", "app_settings")
        rows = self._rows(table)
        return rows[0] if rows else {}

    def java_installs(self):
        """Java runtimes Modrinth downloaded, keyed by major version."""
        installs = {}

        for row in self._rows(self._first_table("java_versions", "javas", "java")):
            major = _pick_int(row, "major_version", "major", "version")
            path = _pick(row, "path", "java_path", "bin_path")
            if major and path:
                installs.setdefault(major, os.path.expandvars(path))

        if os.path.isdir(self.java_dir):
            for name in os.listdir(self.java_dir):
                home = os.path.join(self.java_dir, name)
                binary = _java_binary(home)
                if not binary:
                    continue
                major = _java_major_version(home) or _major_from_name(name)
                if major:
                    installs.setdefault(major, binary)

        return installs


def _pick(row, *keys):
    for key in keys:
        value = row.get(key)
        if value not in (None, ""):
            return value
    return None


def _pick_int(row, *keys):
    value = _pick(row, *keys)
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


# ------------------------------------------------------------------- version


def _read_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def _version_json_path(versions_dir, version_id):
    """Modrinth stores manifests as ``versions/<id>/<id>.json``."""
    nested = os.path.join(versions_dir, version_id, version_id + ".json")
    if os.path.isfile(nested):
        return nested
    flat = os.path.join(versions_dir, version_id + ".json")
    if os.path.isfile(flat):
        return flat
    return ""


def _all_version_manifests(versions_dir):
    """Every manifest on disk as ``(id, path, mtime)``, newest first."""
    manifests = []
    if not os.path.isdir(versions_dir):
        return manifests

    for entry in os.listdir(versions_dir):
        full = os.path.join(versions_dir, entry)
        if os.path.isdir(full):
            path = os.path.join(full, entry + ".json")
            if not os.path.isfile(path):
                jsons = [f for f in os.listdir(full) if f.endswith(".json")]
                path = os.path.join(full, jsons[0]) if jsons else ""
        elif entry.endswith(".json"):
            path = full
        else:
            continue

        if path and os.path.isfile(path):
            manifests.append((os.path.splitext(os.path.basename(path))[0], path, os.path.getmtime(path)))

    manifests.sort(key=lambda item: item[2], reverse=True)
    return manifests


def _score_manifest(version_id, manifest, game_version, loader):
    """How well a manifest on disk matches the instance we want to launch."""
    lowered = version_id.lower()
    inherits = str(manifest.get("inheritsFrom") or "")
    score = 0

    if game_version:
        if version_id == game_version:
            score += 40 if not loader or loader in ("vanilla", "") else 5
        if inherits == game_version:
            score += 50
        elif game_version in lowered:
            score += 25

    if loader and loader not in ("vanilla", "none"):
        if loader in lowered:
            score += 40
        elif inherits:
            score += 5
        else:
            score -= 20
    else:
        for known in ("fabric", "forge", "quilt", "neoforge"):
            if known in lowered:
                score -= 25

    return score


def resolve_version_id(install, instance):
    """Work out which version manifest this instance launches with."""
    game_version = instance.get("game_version") or ""
    loader = (instance.get("loader") or "").lower()
    loader_version = instance.get("loader_version") or ""

    exact_ids = []
    if loader and loader not in ("vanilla", "none") and loader_version and game_version:
        exact_ids.append(f"{loader}-loader-{loader_version}-{game_version}")
        exact_ids.append(f"{game_version}-{loader}-{loader_version}")
        exact_ids.append(f"{game_version}-{loader}{loader_version}")
    elif game_version:
        exact_ids.append(game_version)

    for version_id in exact_ids:
        if _version_json_path(install.versions_dir, version_id):
            return version_id

    manifests = _all_version_manifests(install.versions_dir)
    if not manifests:
        raise LaunchError(
            "No Minecraft version files were found in "
            f"{install.versions_dir}. Open Modrinth once and let the instance finish "
            "downloading, then launch from Blueprint Client."
        )

    best = None
    best_score = None
    for index, (version_id, path, _mtime) in enumerate(manifests):
        try:
            manifest = _read_json(path)
        except (OSError, ValueError):
            continue
        # Ties break toward the most recently written manifest.
        score = _score_manifest(version_id, manifest, game_version, loader) - index * 0.01
        if best_score is None or score > best_score:
            best, best_score = version_id, score

    if best is None:
        raise LaunchError(f"Could not read any version manifest in {install.versions_dir}.")
    return best


def load_version_manifest(install, version_id):
    """Load a manifest and merge it with everything it inherits from."""
    chain = []
    seen = set()
    current = version_id

    while current and current not in seen:
        seen.add(current)
        path = _version_json_path(install.versions_dir, current)
        if not path:
            raise LaunchError(
                f"Version files for '{current}' are missing from {install.versions_dir}. "
                "Open Modrinth once so it can repair the instance."
            )
        manifest = _read_json(path)
        manifest["_id"] = current
        chain.append(manifest)
        current = manifest.get("inheritsFrom")

    merged = {}
    # Walk parents first so the child's values win.
    for manifest in reversed(chain):
        merged = _merge_manifest(merged, manifest)

    merged["id"] = version_id
    merged["_chain"] = [manifest["_id"] for manifest in chain]
    return merged


def _merge_manifest(parent, child):
    merged = dict(parent)

    for key, value in child.items():
        if key in ("libraries", "arguments", "_id"):
            continue
        merged[key] = value

    # Child libraries take priority, and the child's overrides come first on the
    # classpath so a modded build shadows the vanilla jar of the same artifact.
    merged["libraries"] = list(child.get("libraries") or []) + list(parent.get("libraries") or [])

    arguments = {}
    for section in ("game", "jvm"):
        arguments[section] = list((parent.get("arguments") or {}).get(section) or []) + list(
            (child.get("arguments") or {}).get(section) or []
        )
    merged["arguments"] = arguments

    if child.get("minecraftArguments"):
        merged["minecraftArguments"] = child["minecraftArguments"]

    return merged


# ----------------------------------------------------------------- libraries


def _maven_to_path(name):
    """``group:artifact:version[:classifier][@ext]`` -> relative file path."""
    coordinate, _, extension = name.partition("@")
    extension = extension or "jar"
    parts = coordinate.split(":")
    if len(parts) < 3:
        return ""

    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else ""
    filename = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + f".{extension}"
    return os.path.join(*group.split("."), artifact, version, filename)


def _native_classifier(library):
    natives = library.get("natives") or {}
    classifier = natives.get(_os_name())
    if not classifier:
        return ""
    return classifier.replace("${arch}", "64" if _os_arch() in ("x86_64", "arm64") else "32")


def _library_files(library, libraries_dir):
    """Return ``(classpath_files, native_archives)`` for one library entry."""
    if not _rule_allows(library.get("rules")):
        return [], []

    downloads = library.get("downloads") or {}
    classpath = []
    natives = []

    artifact = downloads.get("artifact")
    if artifact and artifact.get("path"):
        classpath.append(os.path.join(libraries_dir, *artifact["path"].split("/")))
    elif library.get("name") and not library.get("natives"):
        relative = _maven_to_path(library["name"])
        if relative:
            classpath.append(os.path.join(libraries_dir, relative))

    classifier = _native_classifier(library)
    if classifier:
        entry = (downloads.get("classifiers") or {}).get(classifier)
        if entry and entry.get("path"):
            natives.append(os.path.join(libraries_dir, *entry["path"].split("/")))
        elif library.get("name"):
            relative = _maven_to_path(f"{library['name']}:{classifier}")
            if relative:
                natives.append(os.path.join(libraries_dir, relative))

    return classpath, natives


def _library_key(path):
    """Group libraries by artifact so duplicate versions collapse."""
    parts = os.path.normpath(path).split(os.sep)
    return os.sep.join(parts[:-2]).lower() if len(parts) >= 3 else path.lower()


def build_classpath(install, manifest):
    """Classpath entries plus the native archives that need extracting."""
    classpath = []
    natives = []
    missing = []
    seen = set()

    for library in manifest.get("libraries") or []:
        library_paths, native_paths = _library_files(library, install.libraries_dir)

        for path in library_paths:
            key = _library_key(path)
            if key in seen:
                continue
            seen.add(key)
            if os.path.isfile(path):
                classpath.append(path)
            else:
                missing.append(path)

        for path in native_paths:
            if os.path.isfile(path):
                natives.append(path)
            else:
                missing.append(path)

    client_jar = _find_client_jar(install, manifest)
    if client_jar:
        classpath.append(client_jar)
    else:
        missing.append(os.path.join(install.versions_dir, manifest["id"], manifest["id"] + ".jar"))

    return classpath, natives, missing


def _find_client_jar(install, manifest):
    """The game jar, taken from the first version in the inheritance chain."""
    for version_id in manifest.get("_chain") or [manifest["id"]]:
        for candidate in (
            os.path.join(install.versions_dir, version_id, version_id + ".jar"),
            os.path.join(install.versions_dir, version_id + ".jar"),
        ):
            if os.path.isfile(candidate):
                return candidate
    return ""


def extract_natives(archives, target_dir, excludes=None):
    """Unpack legacy native archives; already-extracted files are left alone."""
    if not archives:
        return target_dir

    os.makedirs(target_dir, exist_ok=True)
    excludes = list(excludes or []) + ["META-INF/"]

    for archive in archives:
        try:
            with zipfile.ZipFile(archive) as bundle:
                for entry in bundle.infolist():
                    name = entry.filename
                    if entry.is_dir() or any(name.startswith(rule) for rule in excludes):
                        continue
                    destination = os.path.join(target_dir, *name.split("/"))
                    if os.path.isfile(destination) and os.path.getsize(destination) == entry.file_size:
                        continue
                    os.makedirs(os.path.dirname(destination), exist_ok=True)
                    with bundle.open(entry) as source, open(destination, "wb") as sink:
                        shutil.copyfileobj(source, sink)
        except (OSError, zipfile.BadZipFile) as exc:
            raise LaunchError(f"Could not unpack native library {archive}: {exc}") from exc

    return target_dir


# ---------------------------------------------------------------------- java


def _java_binary(java_home):
    """Accept a JDK folder, a ``bin`` folder or the executable itself."""
    if not java_home:
        return ""
    java_home = os.path.expandvars(java_home)

    if os.path.isfile(java_home):
        return java_home

    names = ["javaw.exe", "java.exe"] if _os_name() == "windows" else ["java"]
    for base in (os.path.join(java_home, "bin"), java_home, os.path.join(java_home, "Contents", "Home", "bin")):
        for name in names:
            candidate = os.path.join(base, name)
            if os.path.isfile(candidate):
                return candidate
    return ""


def _major_from_name(text):
    match = re.search(r"(?:jre|jdk|java|zulu)[-_]?(\d+)", text, re.IGNORECASE)
    if match:
        return int(match.group(1))
    match = re.search(r"\b(\d+)\b", text)
    return int(match.group(1)) if match else 0


def _java_major_version(java_home):
    """Read the major version from the runtime's ``release`` file."""
    for base in (java_home, os.path.dirname(java_home), os.path.dirname(os.path.dirname(java_home))):
        release = os.path.join(base, "release")
        if not os.path.isfile(release):
            continue
        try:
            with open(release, "r", encoding="utf-8", errors="ignore") as handle:
                for line in handle:
                    if line.startswith("JAVA_VERSION="):
                        version = line.split("=", 1)[1].strip().strip('"')
                        head = version.split(".")[0]
                        return 8 if head == "1" else int(head)
        except (OSError, ValueError):
            continue
    return 0


def select_java(install, instance, manifest, config):
    """Pick a Java runtime that matches the version the manifest asks for."""
    required = int((manifest.get("javaVersion") or {}).get("majorVersion") or 0)

    for override in (config.get("java_executable"), instance.get("java_path")):
        binary = _java_binary(override or "")
        if binary:
            return binary, required

    installs = install.java_installs()
    if required and required in installs:
        binary = _java_binary(installs[required])
        if binary:
            return binary, required

    settings = install.settings()
    for key in ("java_path", "java_21_path", "java_17_path", "java_8_path"):
        binary = _java_binary(settings.get(key) or "")
        if binary:
            return binary, required

    # Nothing matched exactly: take the newest runtime Modrinth has, then the
    # system Java.
    for major in sorted(installs, reverse=True):
        binary = _java_binary(installs[major])
        if binary and (not required or major >= required):
            return binary, required

    binary = _java_binary(os.environ.get("JAVA_HOME", "")) or shutil.which("javaw") or shutil.which("java")
    if binary:
        return binary, required

    raise LaunchError(
        "No Java runtime was found. Open Modrinth once so it installs Java "
        f"{required or 21}, or set \"java_executable\" in blueprint_instance.json."
    )


# ------------------------------------------------------------------ launching


def _offline_account(username):
    """Offline UUID, derived the same way vanilla derives it."""
    digest = hashlib.md5(f"OfflinePlayer:{username}".encode("utf-8")).digest()
    offline_uuid = uuid_lib.UUID(bytes=digest[:16], version=3)
    return {
        "username": username,
        "uuid": offline_uuid.hex,
        "access_token": "0",
        "xuid": "0",
        "expires": "",
        "online": False,
    }


def _split_args(value):
    if not value:
        return []
    if isinstance(value, list):
        return [str(item) for item in value]
    text = str(value).strip()
    if text.startswith("["):
        try:
            return [str(item) for item in json.loads(text)]
        except ValueError:
            pass
    return text.split()


def _substitute(value, placeholders):
    for key, replacement in placeholders.items():
        value = value.replace("${" + key + "}", str(replacement))
    return value


def _collect_arguments(section, placeholders, features):
    collected = []
    for entry in section or []:
        if isinstance(entry, str):
            collected.append(_substitute(entry, placeholders))
            continue
        if not isinstance(entry, dict) or not _rule_allows(entry.get("rules"), features):
            continue
        value = entry.get("value")
        values = value if isinstance(value, list) else [value]
        collected.extend(_substitute(str(item), placeholders) for item in values if item is not None)
    return collected


class LaunchPlan:
    """Everything needed to start the game, so it can be inspected or run."""

    def __init__(self, command, cwd, env, java, version_id, account, warnings):
        self.command = command
        self.cwd = cwd
        self.env = env
        self.java = java
        self.version_id = version_id
        self.account = account
        self.warnings = warnings

    def as_text(self):
        return " ".join(f'"{part}"' if " " in part else part for part in self.command)


def build_launch_plan(config=None, progress=None):
    """Turn the Modrinth files on disk into a ready-to-run java command."""
    config = config or {}

    def step(percent, message):
        if progress:
            progress(percent, message)

    warnings = []

    step(8, "Finding your Modrinth game files...")
    install = ModrinthInstall(find_data_dir(config.get("modrinth_data_dir", "")))

    step(18, "Reading the instance...")
    instance = install.find_instance(
        name=config.get("instance_name", ""), path=config.get("instance_path", "")
    )
    game_dir = instance.get("path") or ""
    if not game_dir or not os.path.isdir(game_dir):
        raise LaunchError(
            f"The instance folder for '{instance.get('name')}' does not exist "
            f"({game_dir or 'no path recorded'})."
        )

    for key in ("game_version", "loader", "loader_version"):
        if config.get(key):
            instance[key] = config[key]

    step(30, "Reading the version manifest...")
    version_id = config.get("version_id") or resolve_version_id(install, instance)
    manifest = load_version_manifest(install, version_id)

    step(45, "Collecting libraries...")
    classpath, native_archives, missing = build_classpath(install, manifest)
    if missing:
        preview = "\n".join("  " + path for path in missing[:5])
        more = f"\n  ...and {len(missing) - 5} more" if len(missing) > 5 else ""
        raise LaunchError(
            "Some game files are missing from the Modrinth folder:\n"
            f"{preview}{more}\n"
            "Open Modrinth once and let the instance finish downloading, then try again."
        )

    step(58, "Unpacking native libraries...")
    natives_dir = os.path.join(install.natives_dir, version_id)
    extract_natives(native_archives, natives_dir)

    step(68, "Choosing a Java runtime...")
    java, required_major = select_java(install, instance, manifest, config)
    found_major = _java_major_version(os.path.dirname(os.path.dirname(java)))
    if required_major and found_major and found_major < required_major:
        warnings.append(
            f"Java {found_major} is older than the Java {required_major} this version wants."
        )

    step(78, "Checking your Minecraft account...")
    account = install.account()
    if not account:
        account = _offline_account(config.get("offline_username", "Player"))
        warnings.append(
            "No Minecraft account was found in Modrinth, so the game starts in offline "
            "mode (singleplayer only)."
        )
    elif not account.get("access_token"):
        warnings.append("The stored login has no token; online servers will refuse the connection.")

    step(86, "Building the launch command...")
    settings = install.settings()
    memory = (
        config.get("memory_max_mb")
        or instance.get("memory_max")
        or _pick_int(settings, "memory_max", "max_memory", "memory_maximum")
        or DEFAULT_MAX_MEMORY_MB
    )
    width = config.get("width") or instance.get("width") or _pick_int(settings, "game_resolution_x", "width")
    height = config.get("height") or instance.get("height") or _pick_int(settings, "game_resolution_y", "height")

    assets_index = (manifest.get("assetIndex") or {}).get("id") or manifest.get("assets") or "legacy"
    assets_root = install.assets_dir
    if assets_index in ("legacy", "pre-1.6"):
        legacy_assets = os.path.join(install.assets_dir, "virtual", "legacy")
    else:
        legacy_assets = assets_root

    placeholders = {
        "auth_player_name": account["username"],
        "auth_uuid": account["uuid"],
        "auth_access_token": account["access_token"] or "0",
        "auth_session": f"token:{account['access_token'] or '0'}:{account['uuid']}",
        "auth_xuid": account.get("xuid") or "0",
        "clientid": account.get("xuid") or "0",
        "user_type": "msa" if account.get("online") else "legacy",
        "user_properties": "{}",
        "version_name": version_id,
        "version_type": manifest.get("type", "release"),
        "game_directory": game_dir,
        "assets_root": assets_root,
        "assets_index_name": assets_index,
        "game_assets": legacy_assets,
        "natives_directory": natives_dir,
        "launcher_name": LAUNCHER_NAME,
        "launcher_version": LAUNCHER_VERSION,
        "classpath": os.pathsep.join(classpath),
        "classpath_separator": os.pathsep,
        "library_directory": install.libraries_dir,
        "resolution_width": width or 854,
        "resolution_height": height or 480,
    }

    features = {
        "is_demo_user": False,
        "has_custom_resolution": bool(width and height),
        "has_quick_plays_support": False,
        "is_quick_play_singleplayer": False,
        "is_quick_play_multiplayer": False,
        "is_quick_play_realms": False,
    }

    arguments = manifest.get("arguments") or {}
    jvm_args = _collect_arguments(arguments.get("jvm"), placeholders, features)
    if not jvm_args:
        # Pre-1.13 manifests have no jvm section at all.
        jvm_args = [
            f"-Djava.library.path={natives_dir}",
            "-cp",
            placeholders["classpath"],
        ]

    jvm_args = [f"-Xmx{int(memory)}M"] + jvm_args
    jvm_args.extend(_split_args(settings.get("extra_launch_args")))
    jvm_args.extend(_split_args(instance.get("extra_args")))
    jvm_args.extend(_split_args(config.get("extra_java_args")))

    if manifest.get("minecraftArguments"):
        game_args = [_substitute(part, placeholders) for part in manifest["minecraftArguments"].split()]
    else:
        game_args = _collect_arguments(arguments.get("game"), placeholders, features)

    if width and height and "--width" not in game_args:
        game_args.extend(["--width", str(width), "--height", str(height)])

    main_class = manifest.get("mainClass")
    if not main_class:
        raise LaunchError(f"Version '{version_id}' has no mainClass; the manifest looks incomplete.")

    command = [java] + jvm_args + [main_class] + game_args

    env = os.environ.copy()
    for source in (settings.get("custom_env_vars"), instance.get("env_vars")):
        env.update(_parse_env_vars(source))

    step(94, "Starting Minecraft...")
    return LaunchPlan(command, game_dir, env, java, version_id, account, warnings)


def _parse_env_vars(value):
    """Modrinth stores env vars as JSON pairs or ``KEY=VALUE`` lines."""
    if not value:
        return {}
    if isinstance(value, dict):
        return {str(key): str(item) for key, item in value.items()}

    text = str(value).strip()
    if text.startswith(("[", "{")):
        try:
            parsed = json.loads(text)
        except ValueError:
            return {}
        if isinstance(parsed, dict):
            return {str(key): str(item) for key, item in parsed.items()}
        pairs = {}
        for entry in parsed:
            if isinstance(entry, (list, tuple)) and len(entry) == 2:
                pairs[str(entry[0])] = str(entry[1])
        return pairs

    pairs = {}
    for line in re.split(r"[\n;]", text):
        key, sep, item = line.partition("=")
        if sep and key.strip():
            pairs[key.strip()] = item.strip()
    return pairs


def launch(plan):
    """Start the game process detached from Blueprint Client."""
    kwargs = {
        "cwd": plan.cwd,
        "env": plan.env,
        "stdin": subprocess.DEVNULL,
        "stdout": subprocess.PIPE,
        "stderr": subprocess.STDOUT,
        "text": True,
        "errors": "replace",
        "bufsize": 1,
    }

    if _os_name() == "windows":
        kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0)

    try:
        return subprocess.Popen(plan.command, **kwargs)
    except OSError as exc:
        raise LaunchError(f"Could not start Java: {exc}") from exc


def launch_minecraft(config=None, progress=None):
    """Build the command and start the game. Returns ``(process, plan)``."""
    plan = build_launch_plan(config, progress)
    return launch(plan), plan


# ----------------------------------------------------------------------- cli


def _load_config(path):
    if path and os.path.isfile(path):
        try:
            with open(path, "r", encoding="utf-8") as handle:
                return json.load(handle)
        except (OSError, ValueError):
            return {}
    return {}


def main(argv=None):
    import argparse

    parser = argparse.ArgumentParser(description="Launch Minecraft from the Modrinth App files.")
    parser.add_argument("--config", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "blueprint_instance.json"))
    parser.add_argument("--instance", default="", help="Instance name to launch")
    parser.add_argument("--data-dir", default="", help="Modrinth App data folder")
    parser.add_argument("--list", action="store_true", help="List instances and exit")
    parser.add_argument("--dry-run", action="store_true", help="Print the command without launching")
    args = parser.parse_args(argv)

    config = _load_config(args.config)
    if args.instance:
        config["instance_name"] = args.instance
        config.pop("instance_path", None)
    if args.data_dir:
        config["modrinth_data_dir"] = args.data_dir

    try:
        if args.list:
            install = ModrinthInstall(find_data_dir(config.get("modrinth_data_dir", "")))
            print(f"Modrinth data folder: {install.dir}")
            account = install.account()
            print(f"Account: {account['username'] if account else 'none found'}")
            for instance in install.instances():
                print(
                    f"  {instance.get('name')}  ->  {instance.get('path')}"
                    f"  [{instance.get('game_version') or '?'} {instance.get('loader') or ''}]"
                )
            return 0

        plan = build_launch_plan(config, progress=lambda pct, msg: print(f"[{pct:3d}%] {msg}"))
        for warning in plan.warnings:
            print(f"warning: {warning}")
        print(f"\nJava:    {plan.java}")
        print(f"Version: {plan.version_id}")
        print(f"Folder:  {plan.cwd}")
        print(f"\n{plan.as_text()}\n")

        if args.dry_run:
            return 0

        process = launch(plan)
        print(f"Minecraft started (pid {process.pid}).")
        return 0
    except LaunchError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
