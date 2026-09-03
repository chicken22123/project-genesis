# Building and sharing the Blueprint Client mod

The launcher and the mod are two separate things. `blueprint-client/` starts
Minecraft; this project is the mod that changes what Minecraft looks like once
it is running - the title screen, the module menu, the HUD editor. Handing
someone the launcher does not give them any of that.

## Build it

You need **JDK 21**. Get it from [Adoptium](https://adoptium.net) and tick both
"Set JAVA_HOME" and "Add to PATH" during install. Gradle does not need
installing - the wrapper in this folder fetches it.

Then, on Windows:

```cmd
build-mod.cmd
```

or on Linux/macOS:

```sh
./gradlew build
```

The first build takes several minutes: it downloads Gradle, Minecraft 1.21.11
and the Fabric toolchain. Later builds take seconds.

The result lands in `build/libs/`:

- `blueprint-client-mod-1.0.0.jar` - **this is the one to share**
- `blueprint-client-mod-1.0.0-sources.jar` - source code, not needed to play

## What someone needs to run it

The jar on its own does nothing. Minecraft has to be set up to load it:

1. **Minecraft Java Edition 1.21.11** - this mod targets that exact version.
2. **Fabric Loader** 0.19.0 or newer, installed for 1.21.11.
3. **Fabric API** for 1.21.11 - a separate jar, from
   [Modrinth](https://modrinth.com/mod/fabric-api). The mod will not load
   without it.
4. **Java 21** to run the game.

Then drop both jars - Fabric API and `blueprint-client-mod-1.0.0.jar` - into
the instance's `mods` folder.

If they use the Modrinth App, the easy route is: create an instance on 1.21.11
with Fabric as the loader, install Fabric API from inside Modrinth, then put
the Blueprint jar in that instance's `mods` folder.

## So what do you actually send a friend?

Three things:

1. `blueprint-client-mod-1.0.0.jar` - the visuals and modules
2. The Blueprint Client launcher zip - optional, only if they want the launcher
3. A note saying: Minecraft 1.21.11, Fabric loader, and Fabric API first

The mod is the part that changes the game. The launcher is a convenience for
starting instances that the Modrinth App manages.
