package dev.vvh.mekasuitarcana.balance;

/**
 * Pure timing and energy-cost arithmetic for incremental Arcana acceleration.
 *
 * <p>The rating arguments are native Iron's attribute values: an unmodified attribute is 1.0,
 * and {@code bonusDelta} is only this suit's additive contribution. Keeping the baseline rating
 * explicit makes the suit pay for its marginal benefit even when other gear already supplies a
 * reduction.</p>
 */
public final class ArcanaTiming {

    /** Positive floor that keeps malformed or asymptotic durations finite and usable. */
    public static final double MIN_DURATION_MULTIPLIER = 1.0E-6D;

    private ArcanaTiming() {}

    /**
     * Returns Iron's duration multiplier for a native rating.
     *
     * <p>Iron's formula is {@code 2 - softCap(rating)}. Finite ratings below the native identity
     * are preserved: a debuff changes the baseline and therefore changes the marginal price of
     * this suit's later enhancement. Only {@code NaN} falls back to the identity.</p>
     */
    public static double durationMultiplier(double rating) {
        double safeRating = safeRating(rating);
        if (Double.isInfinite(safeRating)) {
            return MIN_DURATION_MULTIPLIER;
        }
        double multiplier = 2.0D - ArcanaRates.ironSoftCap(safeRating);
        return finitePositive(multiplier) ? Math.max(MIN_DURATION_MULTIPLIER, multiplier)
                : MIN_DURATION_MULTIPLIER;
    }

    /**
     * Extra cooldown progress performed during one real tick by this suit's marginal rating.
     * The ratio is relative to {@code baseRating}, so savings supplied by other gear are excluded.
     */
    public static double extraTicks(double baseRating, double bonusDelta) {
        double safeBonus = safeBonus(bonusDelta);
        if (safeBonus <= 0.0D) {
            return 0.0D;
        }
        double baseline = durationMultiplier(baseRating);
        double enhanced = durationMultiplier(safeAdd(safeRating(baseRating), safeBonus));
        double extra = baseline / enhanced - 1.0D;
        return clampNonNegative(extra);
    }

    /**
     * Fraction of the baseline duration saved by this suit alone, relative to the supplied
     * baseline rating. This is the fraction to use for casting drain, not total savings from all
     * gear combined.
     */
    public static double marginalSavedFraction(double baseRating, double bonusDelta) {
        double safeBonus = safeBonus(bonusDelta);
        if (safeBonus <= 0.0D) {
            return 0.0D;
        }
        double baseline = durationMultiplier(baseRating);
        double enhanced = durationMultiplier(safeAdd(safeRating(baseRating), safeBonus));
        return ArcanaRates.clampFraction(1.0D - enhanced / baseline);
    }

    /** Marginal cooldown/cast ticks saved over a complete baseline duration. */
    public static double marginalSavedTicks(
            double baseDurationTicks, double baseRating, double bonusDelta) {
        double duration = safeNonNegative(baseDurationTicks);
        return saturatedProduct(duration, marginalSavedFraction(baseRating, bonusDelta));
    }

    /**
     * Limits planned fractional extra progress to what remains. The caller owns fractional carry;
     * this method deliberately does not round, so saved-time energy is charged only for progress
     * that is actually executed.
     */
    public static double actualExtraTicks(double remainingTicks, double plannedExtraTicks) {
        double remaining = safeNonNegative(remainingTicks);
        double planned = safeNonNegative(plannedExtraTicks);
        return Math.min(remaining, planned);
    }

    /** Cost for one active spell during one real tick, using already executed extra progress. */
    public static double cooldownCostPerSpell(
            double fixedPerTick, double savedRate, double executedExtraTicks) {
        return saturatedAdd(
                safeNonNegative(fixedPerTick),
                saturatedProduct(safeNonNegative(savedRate), safeNonNegative(executedExtraTicks)));
    }

    /**
     * Cost for one active spell, capping the saved-time term to the progress still remaining.
     */
    public static double cooldownCostPerSpell(
            double fixedPerTick,
            double savedRate,
            double plannedExtraTicks,
            double remainingTicks) {
        return cooldownCostPerSpell(
                fixedPerTick, savedRate, actualExtraTicks(remainingTicks, plannedExtraTicks));
    }

    /** Casting cost with the saved-time term priced against this suit's marginal reduction only. */
    public static double castingCostPerTick(
            double fixedPerTick, double savedRate, double baseRating, double bonusDelta) {
        return saturatedAdd(
                safeNonNegative(fixedPerTick),
                saturatedProduct(
                        safeNonNegative(savedRate),
                        marginalSavedFraction(baseRating, bonusDelta)));
    }

    private static double safeRating(double rating) {
        if (Double.isNaN(rating) || rating == Double.NEGATIVE_INFINITY) {
            return 1.0D;
        }
        return rating;
    }

    private static double safeBonus(double bonusDelta) {
        if (Double.isNaN(bonusDelta) || bonusDelta <= 0.0D) {
            return 0.0D;
        }
        return bonusDelta;
    }

    private static double safeAdd(double left, double right) {
        if (Double.isInfinite(left) || Double.isInfinite(right)) {
            return Double.POSITIVE_INFINITY;
        }
        double sum = left + right;
        return Double.isInfinite(sum) ? Double.POSITIVE_INFINITY : sum;
    }

    private static double safeNonNegative(double value) {
        if (Double.isNaN(value) || value <= 0.0D) {
            return 0.0D;
        }
        return Double.isInfinite(value) ? Double.MAX_VALUE : value;
    }

    private static double clampNonNegative(double value) {
        if (Double.isNaN(value) || value <= 0.0D) {
            return 0.0D;
        }
        return Double.isInfinite(value) ? Double.MAX_VALUE : value;
    }

    private static boolean finitePositive(double value) {
        return value > 0.0D && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static double saturatedProduct(double left, double right) {
        if (left <= 0.0D || right <= 0.0D || Double.isNaN(left) || Double.isNaN(right)) {
            return 0.0D;
        }
        if (left >= Double.MAX_VALUE / right) {
            return Double.MAX_VALUE;
        }
        return left * right;
    }

    private static double saturatedAdd(double left, double right) {
        if (left >= Double.MAX_VALUE - right) {
            return Double.MAX_VALUE;
        }
        return left + right;
    }
}
