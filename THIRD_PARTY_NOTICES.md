# Third-party notices

MekaSuit Arcana is an independent addon, not an official Mekanism, Iron's
Spellbooks, NeoForge, or Mojang project.

- [Mekanism](https://github.com/mekanism/Mekanism) and
  [Iron's Spellbooks](https://github.com/iron431/irons-spells-n-spellbooks)
  are compile-only integration targets. Their jars are downloaded from their
  Modrinth distributions, hash-verified locally, and never committed or bundled.
- [NeoForge](https://github.com/neoforged/NeoForge) and Minecraft development
  artifacts are resolved by ModDevGradle, not redistributed in the addon jar.
- The Gradle wrapper scripts and wrapper jar are from
  [Gradle](https://github.com/gradle/gradle), licensed under Apache-2.0.
  The scripts retain their upstream notices. See
  [Gradle's license](https://github.com/gradle/gradle/blob/master/LICENSE).
- Item models reference Mekanism's existing module model at runtime; no
  Mekanism textures are copied into this repository.

Each dependency remains subject to its own license and distribution terms.
