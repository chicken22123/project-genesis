"""Dump what Blueprint Client can see inside the Modrinth App data folder.

Run this when a launch fails and the error is not obvious:

    py inspect_modrinth_db.py
"""

import os

from modrinth_launcher import LaunchError, ModrinthInstall, find_data_dir


def main():
    try:
        data_dir = find_data_dir(os.environ.get("MODRINTH_DATA_DIR", ""))
    except LaunchError as exc:
        print(exc)
        return 1

    install = ModrinthInstall(data_dir)
    print(f"Data folder: {install.dir}")
    print(f"Database:    {'app.db found' if install.db else 'not readable'}")
    print(f"Versions:    {install.versions_dir}")

    print("\nTables:")
    for table in install._tables():
        print(f"  {table}: {', '.join(install._columns(table))}")

    print("\nInstances:")
    for instance in install.instances():
        print(
            f"  {instance.get('name')}\n"
            f"    path: {instance.get('path')}\n"
            f"    game: {instance.get('game_version')} {instance.get('loader') or ''}"
            f" {instance.get('loader_version') or ''}"
        )

    print("\nVersion manifests on disk:")
    if os.path.isdir(install.versions_dir):
        for name in sorted(os.listdir(install.versions_dir)):
            print(f"  {name}")

    print("\nJava runtimes:")
    for major, path in sorted(install.java_installs().items()):
        print(f"  Java {major}: {path}")

    # Tokens are never printed, so this output is safe to share when reporting
    # a launch problem.
    account = install.account()
    print("\nAccount:")
    if account:
        print(f"  {account['username']} ({account['uuid']}) token stored: {account['online']}")
    else:
        print("  none found - the game would start in offline mode")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
