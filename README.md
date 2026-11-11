# Pluto Mod (Fabric, 1.21.11)

Open the menu with Right Shift (rebind in Controls).

## Build
1. Install JDK 21.
2. Open https://fabricmc.net/develop, pick 1.21.11, and copy the exact version
   numbers into gradle.properties (yarn, loader, fabric api, loom).
3. Run `./gradlew build` (add the Gradle wrapper with `gradle wrapper` if missing).
4. The jar is in build/libs/. Put it in your mods folder with Fabric API.
