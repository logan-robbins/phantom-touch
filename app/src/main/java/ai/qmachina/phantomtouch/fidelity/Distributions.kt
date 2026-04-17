package ai.qmachina.phantomtouch.fidelity

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure seeded RNG samplers for gesture and typing humanization.
 *
 * All functions take a [Random] parameter — no hidden state, deterministic
 * when seeded. Use kotlin.random.Random per Android weak-PRNG guidance
 * (not cryptographic; intended for reproducible non-security randomness).
 */
object Distributions {

    /**
     * Lognormal sample in milliseconds, clamped to [minMs, maxMs].
     *
     * Parameterized by the median (easy to reason about) and sigma of the
     * underlying normal in log space. mu = ln(medianMs).
     */
    fun lognormal(
        rng: Random,
        medianMs: Double,
        sigmaLog: Double,
        minMs: Long,
        maxMs: Long,
    ): Long {
        val mu = ln(medianMs)
        val z = gaussian(rng)
        val sample = exp(mu + sigmaLog * z)
        return sample.toLong().coerceIn(minMs, maxMs)
    }

    /**
     * Two independent zero-mean Gaussian samples with standard deviation [sigma].
     * Uses Box–Muller for both outputs in one call (no wasted half-sample).
     */
    fun gaussian2D(rng: Random, sigma: Float): Pair<Float, Float> {
        val u1 = rng.nextDouble().coerceAtLeast(MIN_U)
        val u2 = rng.nextDouble()
        val r = sqrt(-2.0 * ln(u1))
        val theta = 2.0 * Math.PI * u2
        val x = (r * kotlin.math.cos(theta) * sigma).toFloat()
        val y = (r * kotlin.math.sin(theta) * sigma).toFloat()
        return x to y
    }

    /** Uniform double in [lo, hi). */
    fun uniform(rng: Random, lo: Double, hi: Double): Double {
        require(hi > lo) { "hi must be greater than lo" }
        return lo + (hi - lo) * rng.nextDouble()
    }

    /** Single standard-normal sample via Box–Muller (returns Z1; Z2 discarded). */
    private fun gaussian(rng: Random): Double {
        val u1 = rng.nextDouble().coerceAtLeast(MIN_U)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    private const val MIN_U = 1e-12
}
