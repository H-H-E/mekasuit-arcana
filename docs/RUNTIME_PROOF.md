# MekaSuit Arcana runtime proof

**Scope:** Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21 server runtime for
`mekasuitarcana`, with pinned Mekanism 10.7.19.85, Iron's Spellbooks 3.16.3,
and irons_lib 2.1.0.

**Final status:** the isolated minimal server completed integration `PASS` with
all seven AUTO assertions and all seven timing assertions passing. This is
synthetic server-thread gameplay evidence using a FakePlayer. It is not client,
GUI, human-login, multiplayer, or full-pack playtest evidence.

This is a sanitized report of the local verification run; the full raw logs and
disposable game world are retained locally, not published in this repository.
GitHub CI independently checks compilation, unit tests, and release packaging;
it does not run the Minecraft integration fixture.

## Evidence rules

- Boot proves loading and dependency resolution only.
- A timing PASS proves only the named timing/payment assertion.
- AUTO proves the named native module, carrier, FE, mana, attribute, and
  withdrawal assertions listed below.
- FakePlayer evidence is synthetic server-thread integration evidence.
- The native spell attempt path was not exercised by this harness.

## Final measured fixture

Disposable fixture:

```text
<local-test-root>/mekasuit-arcana-runtime-20260915
```

Final evidence directory:

```text
<fixture>/logs/runtime-smoke-20260915-185611
```

The verified `summary.txt` reports `BOOTED`, integration `PASS`, exit code `0`,
elapsed `65.1s`, no fatal marker, and seven mod jars. The server reached
`Done (1.636s)! For help, type "help"`.

Pinned preflight matched Mekanism `Mekanism-1.21.1-10.7.19.85.jar`, Iron's
Spellbooks `irons_spellbooks-1.21.1-3.16.3.jar`, and irons_lib
`irons_lib-1.21.1-2.1.0.jar`.

The final runtime-test jar SHA-256 is:

```text
c34778ab10b23a6d8fca88bd8a71c312874a323c2c8b2ddc9f47226d5598ee69
```

## Run procedure

1. Prepare a disposable copy of the accepted minimal pinned server. Do not
   modify the source fixture, its world, EULA, or server configuration.
2. Build the release and dev-only runtime-test jars:

   ```powershell
   .\gradlew.bat jar runtimeTestJar
   ```

3. Stage only the runtime-test jar into the disposable fixture's `mods`
   directory.
4. Start the server with `runtime-smoke.ps1`, using its redirected
   `ProcessStartInfo` stdin channel for console commands and `stop`.
5. Wait for the `Done (...)! For help, type "help"` gate, dispatch the timing
   and AUTO commands on the server thread, retain stdout/stderr/summary, then
   stop the server.

A clean boot without command assertions is never reported as gameplay proof.

On Windows with Java 21 in `JAVA_HOME`, the explicit integration gate is:

```powershell
.\tools\runtime-smoke.ps1 -Instance '<disposable-server>' -AddonJar '.\build\libs\mekasuit-arcana-0.1.0-runtime-test.jar' -Launch -RequireIntegrationPass
```

## Final timing evidence — 2026-09-15 18:57:12

Source:

```text
<fixture>/logs/runtime-smoke-20260915-185611/stdout.log
```

All seven timing checks reported `PASS`:

1. `focus_school_charge` — matching-school FE `1,600,000 -> 1,599,500`;
   nonmatching unchanged; fire power `1.0 -> 3.0 -> 1.0`; ice unchanged.
2. `cooldown_progress_and_powerloss` — cooldown `100 -> 82`; FE
   `1,600,000 -> 1,599,260`; dry control `82 -> 82`.
3. `cooldown_no_upfront_double_bonus` — native `200`, boosted `10`, restored
   `200`.
4. `canceled_mana_refund` — FE `1,600,000 -> 1,600,000`; mana `0.0`.
5. `casting_native_long_spell` — `irons_spellbooks:magic_arrow`; duration
   `30 -> 23`; FE `1,600,000 -> 1,599,700`; movement `1.8`.
6. `movement_binary` — movement `1.0 -> 1.8/1.8`.
7. `empty_dry` — movement `1.0 -> 1.0`; FE `0`.

## Final AUTO evidence

The AUTO command reported all seven checks true with no command errors:

- Native support: bodyarmor and Meka-Tool accepted; helmet rejected.
- Installation: requested amplification units resolved to body `4` and tool
  `4`; body/tool combined contribution was `8`.
- Powered carrier: max mana remained `1000.0` while mana increased
  `0.0 -> 1000.0` (dry/absent baseline max mana was `100.0`), and FE
  `3,200,000 -> 3,190,000`.
- Per-carrier amplification FE payment passed.
- Server caps passed: `MANA_CONVERSION 4/4`, `AMPLIFICATION 4/4`, `FOCUS 4/4`,
  `COOLDOWN_ACCELERATION 5/5`, and `CASTING_STABILIZATION 4/4`.
- Mana curve passed at `1000/10000`.
- No-dry-suit and absent-module controls passed with max mana `100.0`, spell
  power `1.0`, cooldown `1.0`, and cast time `1.0`.

The support result is now accepted from the clean strict-match run: bodyarmor
and Meka-Tool are supported, while the helmet is rejected.

## Release boundary

- Release jar: 31 production classes, unchanged by the runtime-test harness.
- No runtime harness classes or test-only dependencies leaked into the release
  jar.
- 5 recipes present.
- Release SHA-256:
  `A310EF4ABFCCC815F28E96C56A7E6ADA4FBD24B0860BD143083F0075F8D70B64`.

| Claim | Status |
|---|---|
| Minimal pinned server loads the addon | **PASS** |
| Seven native timing/payment checks | **PASS** |
| Module installation and carrier caps | **PASS** |
| Mana/FE/attribute and dry-suit AUTO assertions | **PASS** |
| Native module-support acceptance | **PASS** — body/tool accepted, helmet rejected |
| Final integration run has no command errors | **PASS** — exit `0`, integration `PASS` |
| Client GUI, rendering, human login, multiplayer behavior | **UNTESTED** |
