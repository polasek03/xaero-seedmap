# XaeroSeedMap

A Fabric client-side mod for Minecraft 1.21.8 that overlays **seed-based biome colours** and **structure icons** on [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map).

---

## What it does

- Colours every chunk on the world map based on its biome, even in **unexplored areas**
- Shows icons for structures (villages, strongholds, temples, etc.) at their exact seed-predicted locations
- Works across all three dimensions — Overworld, Nether, and End each have distinct colours
- Fully client-side; works on any server once you know the world seed

## How to get the seed

Use the `/seed` command (requires OP or a server that allows it). The mod also picks it up automatically from the `/seed` command output in chat.

You can also set it manually:
```
/setmapseed <seed>
```

## Commands

| Command | Description |
|---|---|
| `/setmapseed <seed>` | Manually set the world seed |
| `/showstructureonmap <structure> <true\|false>` | Toggle visibility of a structure type on the map |

Supported structure names: `villages`, `strongholds`, `shipwrecks`, `buried_treasures`, `ocean_monuments`, `pillager_outposts`, `woodland_mansions`, `desert_pyramids`, `jungle_temples`, `swamp_huts`, `igloos`, `ruined_portals`, `bastion_remnants`, `nether_complexes`, `end_cities`, `ancient_cities`, `trail_ruins`, `trial_chambers`, and more.

---

## How it works

### Biome colours

When the world map is open, the mod samples the biome at every visible chunk using the world's noise generator. Each biome is assigned a colour (grass tint for overworld biomes, custom colours for nether/end biomes). Results are cached per chunk so zooming in and out doesn't re-generate everything.

If the [cubiomes](#native-library-cubiomes) native library is present, biome sampling is done entirely in C for significantly better performance.

### Structure locations

The mod uses the same seed-based math that Minecraft uses to place structures — iterating the placement grid for each structure type and checking whether the biome at that location actually allows the structure to generate. This eliminates the vast majority of false positives.

With cubiomes, the biome check is done by the native library, which replicates Minecraft's logic with high accuracy.

### Rendering

The biome texture and structure icon overlay are rendered as screen-space layers on top of Xaero's World Map framebuffer using a custom GLSL shader. Structure icons come from a sprite sheet (`structures.png`) and are always drawn at a fixed screen size regardless of zoom level.

---

## Dependencies

- [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map)
- [Fabric API](https://modrinth.com/mod/fabric-api)

---

## Native library (cubiomes)

The mod optionally uses [cubiomes](https://github.com/Cubitect/cubiomes) — a C library that replicates Minecraft's world generation — for faster and more accurate biome sampling and structure finding.

Without it, the mod falls back to using Minecraft's own world generator (requires being on a singleplayer world or a server where you are the host). With it, everything runs in native code and works anywhere.

The pre-built Windows DLL is included in releases. To build it yourself:

### Prerequisites

1. **MSYS2** with the UCRT64 gcc toolchain:
   ```
   winget install MSYS2.MSYS2
   ```
   Then in the **MSYS2 UCRT64** terminal:
   ```
   pacman -S mingw-w64-ucrt-x86_64-gcc
   ```

2. **cubiomes** cloned as a submodule:
   ```
   git submodule update --init
   ```

### Building

Run from the project root in a normal CMD or PowerShell window:
```
.\build_natives.bat
```

This opens a brief MSYS2 build window, compiles `cubiomes.dll`, places it in `src/main/resources/natives/windows/`, then closes automatically.

After building, run the normal Gradle build:
```
gradlew build
```

---

## Building the mod

```
gradlew build
```

The output jar is in `build/libs/`.
