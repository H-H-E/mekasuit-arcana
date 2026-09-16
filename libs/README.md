# Compile-time integration targets

These JARs are not committed (see ../.gitignore). Run
`python tools/fetch-dependencies.py` to download the pinned versions from
Modrinth and verify SHA-256 against `tools/dependencies.json`.

| File | Distribution |
|---|---|
| Mekanism-1.21.1-10.7.19.85.jar | Modrinth: mekanism |
| irons_spellbooks-1.21.1-3.16.3.jar | Modrinth: irons-spells-n-spellbooks |

Versions must match the dependency manifest, because the addon's
neoforge.mods.toml dependency ranges and its integration points are version
sensitive.

These are used as compileOnly file dependencies only. Neither mod is bundled,
redistributed, or shaded: at runtime the player's own Mekanism and Iron's
Spellbooks provide every one of these classes.
