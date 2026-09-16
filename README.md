# MekaSuit Arcana

[![CI](https://github.com/H-H-E/mekasuit-arcana/actions/workflows/ci.yml/badge.svg)](https://github.com/H-H-E/mekasuit-arcana/actions/workflows/ci.yml)

**Early preview.** 34 unit tests and 14 isolated server assertions have passed.
Connected-client GUI and full-modpack playtesting remain unverified.

Minecraft 1.21.1 / NeoForge 21.1.248 integration for Mekanism 10.7.19.85 and
Iron's Spells 'n Spellbooks 3.16.3. Java 21 required.

Five Mekanism modules add powered magic support to MekaSuit Bodyarmor.
Amplification and Focus Units also work on a held Meka-Tool using staff-style
attribute bonuses. Use your existing spellbook and Iron's normal casting controls.

| Unit | Install cap | Maximum contribution per carrier |
| --- | ---: | --- |
| Mana Conversion | 4 | Mana capacity curve 1000 / 4000 / 7000 / 10000 and FE-to-mana conversion |
| Amplification | 4 | +200% global spell-power rating |
| Focus | 4 | +200% spell-power rating in one selected school |
| Cooldown Acceleration | 5 | +500% cooldown-reduction rating |
| Casting Stabilization | 4 | +200% cast-time-reduction rating; full casting movement at one unit |

Iron's applies its own nonlinear reduction formula: a +500% cooldown rating is
not a negative cooldown. With otherwise neutral attributes, maximum cooldown
acceleration is about 20x and maximum long-cast acceleration about 8x.

## Using the modules

Craft the modules, install them through Mekanism's Modification Station, then use
the Module Tweaker to enable them, select a Focus school, adjust output, or limit
mana-conversion power. Helmets, leggings and boots cannot accept these modules.
The Meka-Tool accepts only Amplification and Focus.

Bodyarmor and tool retain independent unit caps, output settings, Focus selections,
and energy accounts. Their eligible attribute contributions add together.

Every restored mana point costs FE. Amplification charges once per Iron's cast
event; Focus charges only on events from its selected school. Iron's channeled
spells emit repeated cast events, so their pulses are billed separately.
Cooldowns consume a fixed load for each active native cooldown entry plus FE for
extra progress. Casting consumes a fixed load plus FE for extra long-cast progress.
Lower output reduces acceleration and its associated energy cost. Stabilization's
movement benefit remains binary and retains its fixed load even at zero time output.

Acceleration is purchased as the native timers advance, so running out of power
stops further acceleration. Casting itself continues. Continuous spells retain
Iron's normal channel duration and pulse spacing; the module improves movement
during those channels.

Mana conversion uses Iron's existing mana pool and ordinary regeneration remains
active. Each installed converter raises the conversion throughput available at a
chosen preset. The mana capacity curve describes an otherwise unmodified player's
capacity; other equipment can still contribute its own modifiers.

## Building

Requires Java 21 and Python 3.11 or newer. Download the two exact, SHA-256-verified
compile-only dependencies; neither is bundled or committed:

    python tools/fetch-dependencies.py
    ./gradlew build runtimeTestJar --console=plain
    python tools/verify-release.py

On Windows replace `./gradlew` with `.\gradlew.bat`.

Output: build/libs/mekasuit-arcana-0.1.0.jar. Install this jar on both server and
client alongside Mekanism, Iron's Spellbooks and their normal dependencies.

Server balance configuration is generated under the world's serverconfig directory
as mekasuitarcana-server.toml. Module output selections are stored on the carrier
through Mekanism's own module config system.

## Verification

Plain JUnit tests cover balance and accounting arithmetic. The separate runtime
harness exercises the production code against the real pinned mods in an isolated
dedicated server. See docs/RUNTIME_PROOF.md for the actual results and remaining
client-side checks. A runtime-harness jar is for development only.

Damage routing uses the mods' existing behavior. No extra ward module is included.

## Project

- [Design and balance](docs/DESIGN.md)
- [Verification and remaining checks](docs/RUNTIME_PROOF.md)
- [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Changelog](CHANGELOG.md)

**All Rights Reserved.** This is a public source-available repository, not an
open-source license grant. See [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md).
