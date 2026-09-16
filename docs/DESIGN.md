# MekaSuit Arcana - design contract

Status: implementation contract, updated after pinned-API inspection. The module
effects and installation rules come from the user's final design.

Targets: Minecraft 1.21.1, NeoForge 21.1.248, Java 21, Mekanism
1.21.1-10.7.19.85, Iron's Spellbooks 1.21.1-3.16.3.

## The one-sentence design

The MekaSuit stays ordinary armor. Its energy pays for, and its modules grant,
the spell attributes Iron's Spellbooks already understands.

## Boundaries - what this mod will never do

- No spell storage on the suit. No `ISpellContainer`, no spell slots, no inscriptions.
- No custom casting: no right-click handling, no cast packets, no spell wheel, no
  custom staff item, no `CASTING_IMPLEMENT` on the suit or the Meka-Tool.
- No second mana pool. The player's Iron's mana is the only mana.
- Iron's Spellbooks stays authoritative over mana, mana regen, cooldowns, cast
  time, spell cost, and spell selection.
- With no modules, or with the suit out of energy, the suit is simply armor and
  the player's normal Iron's casting continues to work unchanged.

## Modules

Six modules. Five are in scope; the sixth is deferred evidence-first.

| Module | Effect | Caps | FE behavior |
|---|---|---|---|
| Mana Conversion Unit | Converts suit FE into the player's existing mana pool, and raises Iron's MAX_MANA per installed unit | 4 units; MAX_MANA 1000 / 4000 / 7000 / 10000 | FE per mana restored, rate limited by a module config preset |
| Amplification Unit | Raises global SPELL_POWER | 4 units; up to +200% total | Fixed FE charge on a successful cast |
| Focus Unit | Raises one selected school's SPELL_POWER; behavior identical to the Amplification Unit | 4 units; up to +200% total | Fixed FE charge when a spell of the selected school is cast |
| Cooldown Acceleration Unit | Raises COOLDOWN_REDUCTION | 5 units; up to +500% rating | Drains FE while spells are on cooldown, relative to time saved |
| Casting Stabilization Unit | Reduces cast time and removes the casting movement penalty | 4 units; up to +200% cast-time rating. One unit removes the entire movement penalty; that part does not scale | Drains FE while casting, plus extra relative to time saved |
| Damage Routing Unit (DEFERRED) | Route Iron's spell damage into the normal MekaSuit damage pathway | n/a | No custom behavior planned |

The Damage Routing Unit is not implemented until a runtime test shows Mekanism
does not already handle Iron's spell damage through the normal suit pathway.
Implementing it without that evidence would be guesswork.

## Installation rules

- Every module is installable only on **MekaSuit Bodyarmor**. Not helmet, not
  leggings, not boots. Unit counts are therefore tracked on one piece, so the
  caps cannot be bypassed by spreading units across armor pieces.
- The **Meka-Tool** additionally accepts Amplification Units and Focus Units,
  with the same configs, caps, FE costs, and attribute effects as the bodyarmor
  versions. Both carriers obey their own local cap; if both are present their
  contributions stack additively.
- The Meka-Tool does not become a casting implement. Its units affect normal
  Iron's staff casting purely through the shared spell-attribute path.

## Mana conversion

```text
installed units    MAX_MANA supplied by the suit
       0           no override - Iron's own value stands (suit is just armor)
       1           1,000
       2           4,000
       3           7,000
       4          10,000
```

The unit count is the only thing that moves MAX_MANA. The module tweaker setting
controls the **conversion speed limit**, exposed as stepped presets
(Low / Normal / High / Maximum) because Mekanism's module GUI supports boolean
and enum settings, not free-form numbers. Each preset bounds how much FE may be
consumed per tick, which is the suit's power load.

Each installed unit multiplies the selected preset's FE/tick ceiling. Thus both
capacity and maximum throughput increase with unit count. Curve values describe
the total for an otherwise unmodified player: the modifier adds the curve minus
base mana capacity, preserving contributions from other equipment.

Restoration rules:

- Restore only up to the player's current MAX_MANA. Never overfill.
- Never create mana for a player who has no Iron's mana capability.
- Stop when FE runs out, and resume automatically when the suit is charged.
- The player's existing mana regen keeps working; this module is a supply, not a
  replacement for Iron's regen.

## FE accounting

Each powered module has an explicit cost shape, and every rate is a server-side
config knob with a default. Additional performance consumes energy.

| Trigger | Cost shape |
|---|---|
| Mana restored | FE per mana, capped by the conversion-speed preset |
| Successful cast (Amplification) | fixed FE per cast |
| Successful cast (Focus) | fixed FE per cast of the selected school |
| Any spell on cooldown (Cooldown Acceleration) | per-tick cost per spell on cooldown, plus a term proportional to the cooldown fraction saved |
| While casting (Casting Stabilization) | per-tick cost while casting, plus a term proportional to cast-time fraction saved |

```text
cooldown FE/tick  = sum for each active native cooldown entry:
                    fixedPerSlot * outputFraction + savedTimeRate * extraTicks
casting  FE/tick  = fixedCastingCost
                  + savedTimeRate * extraLongCastTicks
```

Mekanism stores joules internally. The energy bridge converts FE using Mekanism's
configured FE conversion rate. Each carrier pays only from its own energy container.

Iron's normally fixes reduced durations at cast time. To make ongoing FE drain
enforceable, the runtime compensates the addon's upfront cooldown reduction and
suppresses its cast-time contribution during native initiation. It then purchases
extra progress through Iron's public native timer methods each tick. Fractional
progress accumulates, and saved-time FE is charged only for extra ticks executed.
Existing cooldown/casting state remains owned by Iron's. Its native packets sync
progress; this addon registers no new packet types.

Continuous spells preserve native duration and pulse timing; stabilization provides
paid movement freedom while channeling. Iron's raw cast-time rating would otherwise
lengthen continuous spells, unlike its reduction formula for long casts.

If a module cannot pay, it goes offline for that tick: the bonus does not apply,
and the cast still proceeds normally. Losing power must never lock the player out
of casting.

Cooldowns and cast times always clamp to a minimum of one tick, so no amount of
reduction produces a zero or negative duration.

## Attribute contribution model

Both halves of this model were verified against the pinned jars on 2026-09-15, not
assumed: Mekanism's custom-module interface exposes an attribute hook, and Iron's
Spellbooks registers every attribute this design needs as an ordinary entity
attribute (see "Verified API surface"). So the effect half of the design is pure
attribute contribution - no Mixin, and no fork of either mod.

**Mechanism decision.** Contributions are applied as *transient* attribute modifiers,
added and removed by this mod's own server-side player tick loop. Never by writing
modifiers onto the item. Reasoning:

- The design says "only while powered". A modifier attached to the stack (through
  `adjustAttributes`) is collected when NeoForge rebuilds the entity's attribute map, and a
  suit quietly burning FE does not reliably trigger that rebuild. Bonuses that survive on
  a flat suit are a power exploit, because the per-cast charge is then the only thing
  standing between a player and free spell power.
- Owning the tick loop makes both carriers uniform. We read module state from the
  MekaSuit Bodyarmor stack and from Meka-Tool stacks ourselves, instead of depending on
  which slots Mekanism happens to tick a module container in.
- `ICustomModule#adjustAttributes(IModule, ItemAttributeModifierEvent)` does exist and is the
  idiomatic Mekanism hook. It stays deliberately unused so that one code path decides
  bonuses; using both hooks would double-count.

Modifiers are keyed by a stable resource location per (module, carrier, attribute), so
re-applying them every tick is idempotent, and withdrawal on module removal, unequip,
disable, death, dimension change, or logout is exact.

Modules contribute Iron's attributes additively, only while powered:

```text
SPELL_POWER         += amplificationUnits * perUnitPercent(uniform across installed units)
schoolPower(school) += focusUnits * perUnitPercent, for the one selected school
COOLDOWN_REDUCTION  += cooldownUnits * perUnitPercent
CAST_TIME_REDUCTION += castingUnits * perUnitPercent
CASTING_MOVESPEED   += singleStep, only when castingUnits >= 1, no scaling
MAX_MANA            = curve(manaUnits) while at least one unit is installed
```

The Module Tweaker selects 0/25/50/75/100 percent of installed output. At 100%,
four Amplification/Focus units contribute +200%; five Cooldown units +500%; four
Stabilization units +200%. The binary movement benefit remains active at zero cast
time output, with the fixed casting FE load. Movement adds only what is needed to
reach an unpenalized 1.0 input multiplier (native attribute 1.8 at neutral baseline).

## Configuration surface

Two layers, kept deliberately separate:

1. **Per-module settings** live in Mekanism's existing Module Tweaker screen via
   the module config API (boolean / enum). This is what the player changes in
   game: the mana conversion speed preset, the amplification and focus step, the
   cooldown and casting steps, and the selected school for the Focus Unit.
2. **Server-wide balance knobs** live in this mod's own NeoForge config
   (FE per mana, per-cast charges, per-tick rates, caps, and whether any module is
   disabled). Server owners tune economy here without touching players' modules.

Note on the school selection: the Focus Unit models "one selected school for the
suit". All installed Focus Units contribute to that one school.

## Balance ledger - the levers worth writing down

FE per mana, conversion-speed preset values, per-cast charges, cooldown
per-tick and saved-time rates, casting per-tick and saved-time rates, per-unit
power steps, and the MAX_MANA curve endpoints. The shape to preserve is that a
spellbook remains competitive, and that the suit's advantage costs energy.

## Verified API surface (inspection evidence, 2026-09-15)

Read out of the pinned jars with `javap`. This is interface-existence evidence: it proves
these methods and attributes are there to be called, not that a feature works. The
behaviour claims are the runtime lane's to make.

| Claim | Verified signature |
|---|---|
| Mekanism offers custom modules an attribute hook | `ICustomModule#adjustAttributes(IModule, ItemAttributeModifierEvent)` |
| Mekanism ticks a module with its container | `ICustomModule#tickServer(IModule, IModuleContainer, ItemStack, Player)` |
| Modules can absorb or route damage | `ICustomModule#getDamageAbsorbInfo(IModule, DamageSource)` |
| Per-module install cap | `ModuleDataBuilder#maxStackSize(int)` |
| Per-module player settings | `ModuleDataBuilder#addConfig(ModuleBooleanConfig)`, `#addConfig(CONFIG, Codec, StreamCodec)` |
| Reading installed units | `IModuleContainer#installedCount(...)`, `#getIfEnabled(...)`, `#hasEnabled(...)` |
| Module energy access | `IModule#hasEnoughEnergy(...)`, `#useEnergy(...)`, `#canUseEnergy(...)` |
| Module installability lookup | `IModuleHelper#supports(Item, IModuleDataProvider)`, `#getSupportedItems(...)`, `#getSupported(Item)` |
| Iron's magic attributes | `AttributeRegistry#MAX_MANA / MANA_REGEN / COOLDOWN_REDUCTION / SPELL_POWER / SPELL_RESIST / CAST_TIME_REDUCTION / SUMMON_DAMAGE / CASTING_MOVESPEED`, plus one `<SCHOOL>_SPELL_POWER` and one `<SCHOOL>_MAGIC_RESIST` per school (fire, ice, lightning, holy, ender, blood, evocation, nature, eldritch) |
| Iron's cast and cooldown hooks | `api.events.SpellPreCastEvent`, `SpellOnCastEvent`, `SpellCooldownAddedEvent.Pre/Post` |
| Iron's cast vocabulary | `api.spells.CastSource`, `CastResult`, `CastType` |

`adjustAttributes` was the one load-bearing unknown. If Mekanism had not exposed it, the
attribute half of the design would have needed a different mechanism entirely. It exists.

The implementation uses Iron's native rating formulas, MagicData mana, and
Mekanism IMC registration. These integration paths have dedicated-server
assertions recorded in [RUNTIME_PROOF.md](RUNTIME_PROOF.md).

## Evidence boundaries

- `gradlew test` runs plain JUnit, and only over code that imports no Minecraft or
  NeoForge types. The NeoForge loader-booted test target is deliberately not enabled: it
  starts FML, which would need the entire runtime closure of Mekanism and Iron's
  Spellbooks on the test classpath, and booting a loader proves nothing about behaviour.
  Loader-level claims belong to the in-game runs (`runServer`, `gameTestServer`, `runClient`)
  against the real pack mods.

- Static evidence (jar class surfaces, compile success) proves an API exists, not
  that a feature works.
- Runtime evidence requires a loaded game with the real pack mods, an installed
  module, energy movement, and an observed effect.
- A green build is a build result, never an acceptance of behavior.
