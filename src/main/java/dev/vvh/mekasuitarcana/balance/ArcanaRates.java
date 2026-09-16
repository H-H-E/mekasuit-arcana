package dev.vvh.mekasuitarcana.balance;

import java.util.List;

/**
 * Immutable snapshot of the server-wide balance knobs described in {@code docs/DESIGN.md}.
 *
 * <p>This type deliberately holds no Minecraft types: it is the arithmetic core of the energy
 * economy, so it can be unit tested without a game. The NeoForge config layer builds one of these
 * from the loaded config, and the module code asks it questions instead of hardcoding numbers.</p>
 *
 * <p>These balance rates use FE as the player-facing accounting unit. Mekanism's strict energy
 * container is actually measured in Joules; {@code energy.ArcanaEnergy} is the bridge that converts
 * these rates before consuming carrier energy. Cost methods return the configured amount to charge
 * for a tick or an event; the caller is responsible for converting, consuming it, and going
 * offline when the suit cannot pay.</p>
 *
 * <h2>Rating units - the one thing to get right</h2>
 *
 * <p>Iron's Spellbooks does <em>not</em> use a 0-based percentage for its casting attributes. Every
 * magic attribute is a {@code MagicPercentAttribute} whose base value is 1.0, meaning x1.0 - no
 * bonus at all. {@code max_mana} is the lone exception: a plain ranged attribute with a base of
 * 100.0. A module therefore contributes a <em>delta added to 1.0</em>. This type keeps its
 * server-facing knobs in percent-of-baseline form (+200% reads better in a config file than +2.0)
 * because that is the language the module descriptions and server owners use, and
 * {@link #ratingDelta(double)} is the only place that conversion happens.</p>
 *
 * <p>Cooldown and cast time are not even linear in that rating - Iron's subtracts a soft-capped
 * term rather than a fraction:</p>
 *
 * <pre>
 * softCap(x)       = x &lt;= 1.5 ? x : 2.0 - 0.25 / (x - 1.0)
 * cooldown ticks   = baseCooldown * (2.0 - softCap(1.0 + cooldownRating))
 * cast time ticks  = baseCastTime * (2.0 - softCap(1.0 + castTimeRating))
 * casting movement = impulse      * (0.2 + (CASTING_MOVESPEED - 1.0))
 * </pre>
 *
 * <p>The defaults follow one rule, which is also the design's own wording: a full stack delivers
 * the maximum the design promises, so the per-unit knob is that maximum divided by the unit cap.
 * Four Amplification or Focus Units therefore add a rating delta of 2.0 (+200%), five Cooldown
 * Acceleration Units add 5.0 (+500%), and four Casting Stabilization Units add 2.0 (+200%). The
 * soft cap is what keeps ratings that large from inverting the game: it is Iron's own curve, so the
 * effect a player feels is smaller than the raw rating.</p>
 *
 * <pre>
 * +500% cooldown reduction -&gt; multiplier 0.05   -&gt; cooldowns 20x shorter
 * +200% cast time reduction -&gt; multiplier 0.125  -&gt; casts 8x faster
 * </pre>
 *
 * <p>Those are strong numbers on purpose: the suit is endgame, every second of the effect is paid
 * for in FE, and the per-unit step is exposed in the module tweaker so a server owner can dial it
 * down with the FE cost following. The curve also has a floor - because the soft cap asymptotes
 * towards 2.0, no rating can drive a multiplier to zero and no spell ever becomes free.</p>
 *
 * <p>Provenance: read out of the pinned jar {@code irons_spellbooks-1.21.1-3.16.3} with
 * {@code javap -c -p} on 2026-09-15 - {@code AttributeRegistry} (attribute bases),
 * {@code MagicManager.getEffectiveSpellCooldown}, the {@code AbstractSpell} cast-time accessor,
 * {@code ClientPlayerEvents.onCalculatePlayerSpeed}, and {@code Utils.softCapFormula}. Formula and
 * interface evidence from bytecode, not a runtime observation.</p>
 *
 * @param maxManaByUnits MAX_MANA supplied per installed Mana Conversion Unit; index 0 is unused
 * @param fePerMana FE charged per point of mana restored
 * @param manaFePerTickByPreset per-tick FE ceiling for each conversion-speed preset
 * @param amplificationPercentPerUnit global SPELL_POWER percent added per Amplification Unit
 * @param amplificationFePerCast FE charged for a successful cast with an Amplification Unit active
 * @param focusPercentPerUnit school SPELL_POWER percent added per Focus Unit
 * @param focusFePerCast FE charged for a successful cast of the selected school
 * @param cooldownPercentPerUnit COOLDOWN_REDUCTION percent-of-baseline rating added per unit
 * @param cooldownFePerTickPerSlot FE charged per tick for each spell currently on cooldown
 * @param cooldownSavedTimeFePerTick FE charged per tick, scaled by the fraction of cooldown saved
 * @param castingPercentPerUnit cast-time reduction percent-of-baseline rating added per unit
 * @param castingFePerTick FE charged per tick while casting
 * @param castingSavedTimeFePerTick FE charged per tick while casting, scaled by time saved
 * @param maxUnits hard per-carrier caps, indexed by {@link ModuleKind}
 */
public record ArcanaRates(
        List<Integer> maxManaByUnits,
        double fePerMana,
        List<Integer> manaFePerTickByPreset,
        double amplificationPercentPerUnit,
        int amplificationFePerCast,
        double focusPercentPerUnit,
        int focusFePerCast,
        double cooldownPercentPerUnit,
        int cooldownFePerTickPerSlot,
        double cooldownSavedTimeFePerTick,
        double castingPercentPerUnit,
        int castingFePerTick,
        double castingSavedTimeFePerTick,
        List<Integer> maxUnits) {

    /** Conversion-speed presets available in the module tweaker. */
    public enum SpeedPreset {
        LOW,
        NORMAL,
        HIGH,
        MAXIMUM
    }

    /**
     * The five in-scope modules, in a stable order that matches {@code maxUnits}. Keeping this
     * enum as the address of a cap stops callers from passing a raw magic index.
     */
    public enum ModuleKind {
        MANA_CONVERSION,
        AMPLIFICATION,
        FOCUS,
        COOLDOWN_ACCELERATION,
        CASTING_STABILIZATION
    }

    public ArcanaRates {
        maxManaByUnits = List.copyOf(maxManaByUnits);
        manaFePerTickByPreset = List.copyOf(manaFePerTickByPreset);
        maxUnits = List.copyOf(maxUnits);
        if (maxUnits.size() != ModuleKind.values().length) {
            throw new IllegalArgumentException(
                    "maxUnits must have one entry per module kind, got " + maxUnits.size());
        }
        if (manaFePerTickByPreset.size() != SpeedPreset.values().length) {
            throw new IllegalArgumentException(
                    "manaFePerTickByPreset must have one entry per speed preset, got "
                            + manaFePerTickByPreset.size());
        }
        // A table shorter than the cap would make maxManaForUnits silently answer "no override",
        // which reads to a server owner as "the module does nothing" rather than "the table is
        // wrong". Reject it at construction time instead.
        int manaLimit = maxUnits.get(ModuleKind.MANA_CONVERSION.ordinal());
        if (maxManaByUnits.size() <= manaLimit) {
            throw new IllegalArgumentException(
                    "maxManaByUnits must cover every legal Mana Conversion Unit count, need at least "
                            + (manaLimit + 1) + " entries, got " + maxManaByUnits.size());
        }
        for (int index = 0; index < maxManaByUnits.size(); index++) {
            int value = maxManaByUnits.get(index);
            if (value < 0) {
                throw new IllegalArgumentException(
                        "maxManaByUnits[" + index + "] must not be negative, got " + value);
            }
        }
    }

    /**
     * The shipped defaults: each percent knob is its design maximum divided by its unit cap, so a
     * full stack means exactly what the module description says. FE costs are deliberately not
     * trivial - an unpowered suit is useless for magic, and casting through the suit has to be
     * paid for in reactor output.
     */
    public static ArcanaRates defaults() {
        return new ArcanaRates(
                List.of(0, 1_000, 4_000, 7_000, 10_000),
                10.0D,
                List.of(100, 400, 1_000, 2_500),
                50.0D,
                500,
                50.0D,
                500,
                100.0D,
                20,
                40.0D,
                50.0D,
                20,
                40.0D,
                List.of(4, 4, 4, 5, 4));
    }

    /** Hard cap on how many units of this kind one carrier may hold. */
    public int maxUnits(ModuleKind kind) {
        return maxUnits.get(kind.ordinal());
    }

    /** Clamps an observed unit count into the legal range for the kind. */
    public int clampUnits(ModuleKind kind, int units) {
        return Math.max(0, Math.min(units, maxUnits(kind)));
    }

    /** Highest legal unit count across all kinds, used to size the MAX_MANA table defensively. */
    public int largestMaxUnits() {
        return maxUnits.stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /**
     * MAX_MANA supplied by the suit for a given Mana Conversion Unit count. Zero means "no
     * override": the suit is ordinary armor and Iron's own value stands.
     */
    public int maxManaForUnits(int units) {
        int clamped = clampUnits(ModuleKind.MANA_CONVERSION, units);
        if (clamped <= 0 || clamped >= maxManaByUnits.size()) {
            return 0;
        }
        return Math.max(0, maxManaByUnits.get(clamped));
    }

    /** Per-tick FE ceiling for the selected conversion-speed preset. */
    public int manaConversionFePerTick(SpeedPreset preset) {
        return Math.max(0, manaFePerTickByPreset.get(preset.ordinal()));
    }

    /** FE owed for restoring {@code mana} points, before the per-tick ceiling is applied. */
    public double feForMana(int mana) {
        return mana <= 0 ? 0.0D : mana * Math.max(0.0D, fePerMana);
    }

    /**
     * How much mana the suit may restore this tick for a given FE budget, never exceeding the
     * player's headroom. A non-positive ratio (or budget) yields nothing rather than dividing by
     * zero or producing mana from nothing.
     */
    public int manaRestorableForFe(double feBudget, int manaHeadroom) {
        if (manaHeadroom <= 0 || feBudget <= 0.0D || fePerMana <= 0.0D) {
            return 0;
        }
        int affordable = (int) Math.floor(feBudget / fePerMana);
        return Math.max(0, Math.min(affordable, manaHeadroom));
    }

    /** Global SPELL_POWER percent for a unit count, clamped to the cap. */
    public double amplificationPercent(int units) {
        return clampUnits(ModuleKind.AMPLIFICATION, units) * Math.max(0.0D, amplificationPercentPerUnit);
    }

    /** School SPELL_POWER percent for a unit count, clamped to the cap. */
    public double focusPercent(int units) {
        return clampUnits(ModuleKind.FOCUS, units) * Math.max(0.0D, focusPercentPerUnit);
    }

    /** COOLDOWN_REDUCTION percent rating for a unit count, clamped to the cap. */
    public double cooldownReductionPercent(int units) {
        return clampUnits(ModuleKind.COOLDOWN_ACCELERATION, units)
                * Math.max(0.0D, cooldownPercentPerUnit);
    }

    /** Cast-time reduction percent rating for a unit count, clamped to the cap. */
    public double castTimeReductionPercent(int units) {
        return clampUnits(ModuleKind.CASTING_STABILIZATION, units)
                * Math.max(0.0D, castingPercentPerUnit);
    }

    /**
     * Whether the movement penalty is removed. One unit is enough; extra units do not add speed.
     */
    public boolean removesCastingMovementPenalty(int units) {
        return clampUnits(ModuleKind.CASTING_STABILIZATION, units) >= 1;
    }

    /**
     * CASTING_MOVESPEED percent-of-baseline rating contributed by the stabilization units.
     *
     * <p>Iron's applies {@code impulse * (0.2 + (CASTING_MOVESPEED - 1.0))}, so a delta of 0.8 -
     * +80% - restores the full multiplier and cancels the penalty exactly. One unit is therefore
     * worth the whole benefit and extra units contribute nothing; scaling it further would make
     * casting faster than walking, which the design rules out.</p>
     */
    public double castingMovespeedPercent(int units) {
        return removesCastingMovementPenalty(units) ? 80.0D : 0.0D;
    }

    /** Converts a percent-of-baseline knob into the delta Iron's adds to its 1.0-based attributes. */
    public static double ratingDelta(double percent) {
        if (Double.isNaN(percent)) {
            return 0.0D;
        }
        return percent / 100.0D;
    }

    /**
     * Iron's own soft cap on reduction attributes, reproduced here so the FE economy and the module
     * descriptions can talk about the effect a player feels instead of a raw rating. Verified from
     * {@code Utils.softCapFormula} by bytecode inspection of the pinned 3.16.3 jar.
     */
    public static double ironSoftCap(double rating) {
        if (Double.isNaN(rating)) {
            return 0.0D;
        }
        return rating <= 1.5D ? rating : 2.0D - 0.25D / (rating - 1.0D);
    }

    /**
     * Multiplier Iron's applies to a spell's base cooldown for the rating this many units add.
     * 1.0 means no change and smaller means faster; a suit with no cooldown units returns exactly
     * 1.0 rather than relying on the formula's degenerate branch.
     */
    public double cooldownReductionMultiplier(int units) {
        return reductionMultiplier(ratingDelta(cooldownReductionPercent(units)));
    }

    /** Multiplier Iron's applies to a spell's base cast time for the rating this many units add. */
    public double castTimeReductionMultiplier(int units) {
        return reductionMultiplier(ratingDelta(castTimeReductionPercent(units)));
    }

    /**
     * Fraction of a duration this many units actually remove, in [0, 1). The FE "time saved" terms
     * are priced against this, not against the raw rating, so paying more FE only ever buys the
     * reduction Iron's is willing to honour.
     */
    public static double durationSavedFraction(double reductionMultiplier) {
        return clampFraction(1.0D - reductionMultiplier);
    }

    /** Iron's multiply for a given rating delta: {@code 1.0 + delta} enters the soft cap. */
    private static double reductionMultiplier(double ratingDelta) {
        double delta = Math.max(0.0D, ratingDelta);
        return Math.max(0.0D, 2.0D - ironSoftCap(1.0D + delta));
    }

    /**
     * FE owed for this tick of cooldown burden: a fixed cost per spell slot currently on cooldown,
     * plus a term proportional to the fraction of cooldown time the units are saving. Additive by
     * design, so every active cooldown costs something.
     */
    public double cooldownFePerTick(int slotsOnCooldown, double reductionFraction) {
        if (slotsOnCooldown <= 0) {
            return 0.0D;
        }
        double fixed = Math.max(0, cooldownFePerTickPerSlot) * (double) slotsOnCooldown;
        double saved = Math.max(0.0D, cooldownSavedTimeFePerTick) * clampFraction(reductionFraction);
        return fixed + saved;
    }

    /**
     * FE owed for this tick while a cast is in progress, including the extra cost proportional to
     * the cast time the units are saving.
     */
    public double castingFePerTick(double castTimeReductionFraction) {
        double fixed = Math.max(0, castingFePerTick);
        double saved = Math.max(0.0D, castingSavedTimeFePerTick)
                * clampFraction(castTimeReductionFraction);
        return fixed + saved;
    }

    /** FE owed for one successful cast that an Amplification Unit contributed to. */
    public double amplificationCastCost() {
        return Math.max(0, amplificationFePerCast);
    }

    /** FE owed for one successful cast of the selected school while a Focus Unit contributed. */
    public double focusCastCost() {
        return Math.max(0, focusFePerCast);
    }

    /**
     * A reduction fraction is a fraction, not a rating: anything at or above 1.0 saves at most the
     * whole duration, and negatives save nothing. Rating-to-fraction conversion is Iron's job.
     */
    public static double clampFraction(double fraction) {
        if (Double.isNaN(fraction)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, fraction));
    }

    /** Cooldowns and cast times never collapse to zero; one tick is the floor. */
    public static int clampTicks(int ticks) {
        return Math.max(1, ticks);
    }
}
