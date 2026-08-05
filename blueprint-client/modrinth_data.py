"""The reads the UI needs from the Modrinth App files.

Everything here touches SQLite or the disk, so every function is meant to be
called from a worker thread (see ``BlueprintApp.run_async``) and returns plain
dicts and lists - never the ``ModrinthInstall`` object itself. That object owns
an open sqlite connection and is cached behind a lock, so only this module ever
handles it.
"""

import json
import os
import re
import shutil
import threading
import zipfile

import modrinth_launcher
import recycle
from modrinth_launcher import LaunchError  # re-exported: pages catch it

_lock = threading.RLock()
_install = None
_install_key = None

MOD_SUFFIX = ".jar"
DISABLED_SUFFIX = ".disabled"


def _install_for(config, refresh=False):
    """The cached install, reopened when the configured data folder changes."""
    global _install, _install_key

    key = config.get("modrinth_data_dir", "") or ""
    if _install is None or refresh or key != _install_key:
        _install = modrinth_launcher.ModrinthInstall(modrinth_launcher.find_data_dir(key))
        _install_key = key
    return _install


def invalidate():
    global _install, _install_key
    with _lock:
        _install = None
        _install_key = None


# ------------------------------------------------------------------ instances


def overview(config, refresh=False):
    """Everything the Instances page shows in one read."""
    with _lock:
        install = _install_for(config, refresh)
        data_dir = install.dir
        instances = install.instances()
        account = install.account()
        versions = modrinth_launcher.available_version_ids(install)

    selected_path = _normalised(config.get("instance_path", ""))
    selected_name = (config.get("instance_name", "") or "").strip().lower()

    for index, instance in enumerate(instances):
        path = instance.get("path") or ""
        instance["exists"] = bool(path) and os.path.isdir(path)
        instance["mod_count"] = count_mods(path)
        if selected_path:
            instance["selected"] = _normalised(path) == selected_path
        elif selected_name:
            instance["selected"] = (instance.get("name") or "").strip().lower() == selected_name
        else:
            instance["selected"] = index == 0

    return {
        "data_dir": data_dir,
        "instances": instances,
        "account": account["username"] if account else "",
        "online": bool(account and account.get("online")),
        "versions": versions,
    }


def resolve_instance(config):
    """The instance the launcher would actually use with this config."""
    with _lock:
        install = _install_for(config)
        instance = install.find_instance(
            name=config.get("instance_name", ""), path=config.get("instance_path", "")
        )
    return dict(instance)


def matching_versions(versions, instance):
    """Version ids worth offering for an instance, best guess first.

    The full list on disk can hold dozens of unrelated manifests, so anything
    mentioning the instance's game version or loader is floated to the top.
    """
    game_version = str(instance.get("game_version") or "")
    loader = str(instance.get("loader") or "").lower()

    def rank(version_id):
        lowered = version_id.lower()
        score = 0
        if game_version and game_version in lowered:
            score -= 2
        if loader and loader not in ("vanilla", "none") and loader in lowered:
            score -= 1
        return score

    return sorted(versions, key=lambda version_id: (rank(version_id), version_id.lower()))


# ----------------------------------------------------------------------- mods


def mods_dir(instance_path):
    return os.path.join(instance_path, "mods") if instance_path else ""


def count_mods(instance_path):
    """Enabled mod jars, without opening any of them."""
    folder = mods_dir(instance_path)
    if not folder or not os.path.isdir(folder):
        return 0
    try:
        return sum(1 for name in os.listdir(folder) if name.lower().endswith(MOD_SUFFIX))
    except OSError:
        return 0


def list_mods(instance_path):
    """Every mod jar in the instance, enabled or not, sorted by display name."""
    folder = mods_dir(instance_path)
    if not folder or not os.path.isdir(folder):
        return []

    mods = []
    try:
        names = sorted(os.listdir(folder))
    except OSError:
        return []

    for name in names:
        lowered = name.lower()
        enabled = lowered.endswith(MOD_SUFFIX)
        disabled = lowered.endswith(MOD_SUFFIX + DISABLED_SUFFIX)
        if not enabled and not disabled:
            continue

        path = os.path.join(folder, name)
        if not os.path.isfile(path):
            continue

        info = _mod_info(path, name)
        mods.append(
            {
                "filename": name,
                "path": path,
                "enabled": enabled,
                "name": info["name"],
                "version": info["version"],
                "size": _size_of(path),
            }
        )

    mods.sort(key=lambda mod: (not mod["enabled"], mod["name"].lower()))
    return mods


def install_mods(instance_path, sources, replace=False):
    """Copy jars into the instance's mods folder.

    Returns ``(added, conflicts, errors)`` where conflicts are the source paths
    of mods already installed - the page asks about those and calls again with
    ``replace=True``.
    """
    folder = mods_dir(instance_path)
    if not folder:
        raise LaunchError("No instance is selected, so there is nowhere to put the mods.")

    try:
        os.makedirs(folder, exist_ok=True)
    except OSError as exc:
        raise LaunchError(f"Could not create the mods folder: {exc}") from exc

    added, conflicts, errors = [], [], []

    for source in sources:
        name = os.path.basename(source)
        if not name.lower().endswith(MOD_SUFFIX):
            errors.append((name, "not a .jar file"))
            continue
        if not os.path.isfile(source):
            errors.append((name, "file not found"))
            continue
        if not zipfile.is_zipfile(source):
            # A jar is a zip; anything else would be ignored by the loader.
            errors.append((name, "not a valid jar"))
            continue

        target = os.path.join(folder, name)
        installed = _existing_copy(target)
        if installed and not replace:
            conflicts.append(source)
            continue

        try:
            # Replacing a mod that is currently disabled means removing the
            # .disabled copy too, or the instance would hold both.
            if installed and os.path.normcase(installed) != os.path.normcase(target):
                os.remove(installed)
            shutil.copy2(source, target)
            added.append(name)
        except OSError as exc:
            errors.append((name, str(exc)))

    return added, conflicts, errors


def _existing_copy(target):
    """The installed file for this mod, enabled or disabled, if there is one."""
    if os.path.exists(target):
        return target
    disabled = target + DISABLED_SUFFIX
    return disabled if os.path.exists(disabled) else ""


def remove_mods(paths):
    """Delete mods, preferring the Recycle Bin. Returns ``(removed, errors)``."""
    paths = [path for path in paths if path]
    if not paths:
        return [], []

    if recycle.send_to_recycle_bin(paths):
        return [os.path.basename(path) for path in paths], []

    removed, errors = [], []
    for path in paths:
        try:
            os.remove(path)
            removed.append(os.path.basename(path))
        except OSError as exc:
            errors.append((os.path.basename(path), str(exc)))
    return removed, errors


def set_mod_enabled(path, enabled):
    """Toggle a mod by adding or removing the ``.disabled`` suffix.

    This is the same convention the Modrinth App and Prism use, so a mod turned
    off here stays off when the instance is opened elsewhere.
    """
    if not os.path.isfile(path):
        raise LaunchError(f"{os.path.basename(path)} is no longer in the mods folder.")

    if enabled:
        if not path.lower().endswith(DISABLED_SUFFIX):
            return path
        target = path[: -len(DISABLED_SUFFIX)]
    else:
        if path.lower().endswith(DISABLED_SUFFIX):
            return path
        target = path + DISABLED_SUFFIX

    if os.path.exists(target):
        raise LaunchError(f"{os.path.basename(target)} already exists in the mods folder.")

    try:
        os.rename(path, target)
    except OSError as exc:
        raise LaunchError(f"Could not rename {os.path.basename(path)}: {exc}") from exc
    return target


# ------------------------------------------------------------- mod metadata

_META_CACHE = {}


def _mod_info(path, filename):
    """Display name and version from inside the jar, cached per file."""
    try:
        stat = os.stat(path)
        key = (path, stat.st_mtime_ns, stat.st_size)
    except OSError:
        key = (path, 0, 0)

    if key in _META_CACHE:
        return _META_CACHE[key]

    info = _read_jar_metadata(path) or {"name": _name_from_filename(filename), "version": ""}
    _META_CACHE[key] = info
    return info


def _read_jar_metadata(path):
    try:
        with zipfile.ZipFile(path) as jar:
            names = set(jar.namelist())

            if "fabric.mod.json" in names:
                data = _read_json_member(jar, "fabric.mod.json")
                if data:
                    return {
                        "name": str(data.get("name") or data.get("id") or ""),
                        "version": str(data.get("version") or ""),
                    }

            if "quilt.mod.json" in names:
                data = _read_json_member(jar, "quilt.mod.json")
                loader = (data or {}).get("quilt_loader") or {}
                metadata = loader.get("metadata") or {}
                if metadata or loader:
                    return {
                        "name": str(metadata.get("name") or loader.get("id") or ""),
                        "version": str(loader.get("version") or ""),
                    }

            for member in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
                if member in names:
                    return _parse_mods_toml(jar.read(member).decode("utf-8", "replace"))
    except (OSError, zipfile.BadZipFile, ValueError):
        return None
    return None


def _read_json_member(jar, member):
    try:
        return json.loads(jar.read(member).decode("utf-8", "replace"))
    except ValueError:
        return None


def _parse_mods_toml(body):
    """Forge/NeoForge metadata, read with regexes rather than a TOML parser.

    Only two values are needed, and mods.toml files in the wild often contain
    template placeholders that a strict parser rejects.
    """
    name = re.search(r'^\s*displayName\s*=\s*["\'](.+?)["\']', body, re.MULTILINE)
    version = re.search(r'^\s*version\s*=\s*["\'](.+?)["\']', body, re.MULTILINE)
    found_version = version.group(1) if version else ""
    if "${" in found_version:  # unresolved gradle placeholder
        found_version = ""
    return {"name": name.group(1) if name else "", "version": found_version}


def _name_from_filename(filename):
    stem = filename
    for suffix in (DISABLED_SUFFIX, MOD_SUFFIX):
        if stem.lower().endswith(suffix):
            stem = stem[: -len(suffix)]
    stem = re.sub(r"[-_+]?\d[\w.+]*$", "", stem)  # trailing version number
    stem = stem.replace("-", " ").replace("_", " ").strip()
    return stem or filename


# -------------------------------------------------------------------- helpers


def _normalised(path):
    if not path:
        return ""
    return os.path.normcase(os.path.abspath(os.path.expandvars(path)))


def _size_of(path):
    try:
        return os.path.getsize(path)
    except OSError:
        return 0


def human_size(size):
    if size >= 1024 * 1024:
        return f"{size / (1024 * 1024):.1f} MB"
    if size >= 1024:
        return f"{size / 1024:.0f} KB"
    return f"{size} B"
