# Contributing

This project is publicly readable but **All Rights Reserved**, not open source.
Please discuss substantial changes in an issue before preparing a contribution;
no broader license or copyright assignment is implied by this guide.

## Development

Use Java 21 and Python 3. Download the pinned compile-only dependencies, then build:

```sh
python tools/fetch-dependencies.py
./gradlew build runtimeTestJar
```

On Windows use `gradlew.bat`. The normal jar must not contain dependency or
runtime-harness classes. Keep changes scoped and include regression tests.

## Design constraints

- Bodyarmor only; mainhand Meka-Tool accepts Amplification and Focus only.
- Keep per-carrier caps, configuration, and FE accounts independent.
- Use Iron's existing mana, casting, and timers; no custom casting or spell storage.
- Insufficient FE removes bonuses without blocking ordinary casting.
- Do not commit dependency jars, game worlds, logs, tokens, or personal paths.

## Evidence

`gradlew test` checks loader-free logic. It does not boot Minecraft. See
[the runtime report](docs/RUNTIME_PROOF.md) for server integration coverage and
remaining client/full-pack checks. Never describe a compile or boot as gameplay
verification. Include exact mod versions when reporting a compatibility issue.
