# TreeFall

TreeFall is a Paper plugin that makes supported Minecraft trees fall as a visual animation when a player cuts the trunk.

## Supported platform

- Paper 1.21.1+
- Java 21+
- Built against Paper API 1.21.1
- Optional WorldGuard integration
- Optional RealisticSeasons integration

## Main features

- Whole-tree detection using logs and natural leaves.
- Support for normal and 2×2 trunks.
- Shift-click can disable TreeFall for a single action.
- Optional axe requirement for large trees.
- Per-player cooldown and active-tree locking to prevent double processing.
- Protection-aware WorldGuard integration.
- Optional seasonal drop adjustments through RealisticSeasons.
- FallingBlock visual animation with a configurable entity limit.
- Directional falling based on the player's facing direction.
- Higher parts of the tree receive stronger horizontal movement for a more coherent fall.
- Configurable particles, sounds, velocity and random spread.
- The animation limit never discards the detected tree's drops.
- Oversized or suspiciously connected structures are skipped instead of being partially destroyed.
- Tool durability respects Unbreaking and is applied only to the original tool slot.
- World blacklist and bypass permission.

## Commands

`/treefall reload`

Reloads `config.yml`.

## Permissions

- `treefall.use` — allows using TreeFall. Default: true.
- `treefall.bypass` — bypasses the TreeFall mechanic. Default: op.
- `treefall.admin` — allows `/treefall reload`. Default: op.

## Configuration

The generated `config.yml` contains comments and safe defaults. Important settings include:

- `enabled`
- `authorization.require-permission`
- `authorization.bypass-permission`
- `sneak-to-disable`
- `require-axe-for-big`
- `min-trunk-height`
- `max-blocks`
- `cooldown-ms`
- `world-blacklist`
- `animation.max-falling-blocks`
- `animation.blocks-per-tick`
- `animation.tick-delay`
- `animation.timeout-ticks`
- `animation.directional-fall`
- `animation.horizontal-velocity`
- `animation.upward-velocity`
- `animation.random-spread`
- `animation.particles`
- `animation.particle-interval`
- `animation.sounds`
- `animation.sound-interval`
- `drop.chance.stick`
- `drop.chance.sapling`

Existing configurations using the old root-level `require-permission` setting remain compatible.

## Safety behavior

TreeFall does not intentionally partially destroy a tree when its detection limit is reached. The detector retries with larger limits and skips the structure if it still cannot safely determine the complete connected tree.

The animation entity limit affects only visual FallingBlock entities. All detected blocks still contribute to the calculated drops, and non-animated blocks are safely removed as part of the same tree-fall operation.

WorldGuard failures fail closed: if the WorldGuard API cannot be checked, TreeFall does not run for that block instead of bypassing protection.

## Installation

1. Build the project with Maven.
2. Put the resulting JAR into the server's `plugins` directory.
3. Start the Paper server.
4. Edit `plugins/TreeFall/config.yml` if required.
5. Use `/treefall reload` after configuration changes.
