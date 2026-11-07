package schrodinger;

/**
 * The potentials V(x) the program offers (units hbar = m = 1). The structure is always centred on x = 0.
 *
 * <p>The piecewise-constant ones (step, barrier, well, double barrier) also describe themselves as region edges
 * and levels, which is what {@link Exact} needs to compute the exact transmission.
 */
public final class Potential {
    public enum Kind {
        FREE("free"), STEP("step"), BARRIER("barrier"), WELL("well"), DOUBLE_BARRIER("double_barrier"),
        HARMONIC("harmonic"), DOUBLE_WELL("double_well");

        public final String id;

        Kind(String id) {
            this.id = id;
        }

        public static Kind of(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return null;
        }
    }

    private final Kind kind;
    private final double v0, width, gap, omega;

    /**
     * @param v0    height of a step or barrier, depth of a well, barrier height of the double well
     * @param width width of a barrier or well, or the half-separation d of the double well
     * @param gap   distance between the two barriers of the double barrier
     * @param omega angular frequency of the harmonic oscillator
     */
    public Potential(Kind kind, double v0, double width, double gap, double omega) {
        this.kind = kind;
        this.v0 = v0;
        this.width = width;
        this.gap = gap;
        this.omega = omega;
    }

    public Kind kind() {
        return kind;
    }

    public double value(double x) {
        switch (kind) {
            case STEP:
                return x >= 0 ? v0 : 0.0;
            case BARRIER:
                return Math.abs(x) < width / 2 ? v0 : 0.0;
            case WELL:
                return Math.abs(x) < width / 2 ? -v0 : 0.0;
            case DOUBLE_BARRIER: {
                double a = Math.abs(x);
                return (a >= gap / 2 && a < gap / 2 + width) ? v0 : 0.0;
            }
            case HARMONIC:
                return 0.5 * omega * omega * x * x;
            case DOUBLE_WELL: {
                double u = (x / width) * (x / width) - 1.0;
                return v0 * u * u;
            }
            default:
                return 0.0;
        }
    }

    /** True when V is made of flat pieces, so the exact scattering can be computed. */
    public boolean piecewise() {
        return edges() != null;
    }

    /** Size of the smallest flat feature (barrier, well or gap), used to warn when the grid is too coarse. */
    public double minFeature() {
        switch (kind) {
            case BARRIER:
            case WELL:
                return width;
            case DOUBLE_BARRIER:
                return Math.min(width, gap);
            default:
                return Double.MAX_VALUE;
        }
    }

    /** Positions where V jumps, in increasing order (null if V is not piecewise constant or is free). */
    public double[] edges() {
        switch (kind) {
            case STEP:
                return new double[] {0.0};
            case BARRIER:
            case WELL:
                return new double[] {-width / 2, width / 2};
            case DOUBLE_BARRIER:
                return new double[] {-gap / 2 - width, -gap / 2, gap / 2, gap / 2 + width};
            default:
                return null;
        }
    }

    /** V on each region: levels()[j] holds between edges()[j-1] and edges()[j]. */
    public double[] levels() {
        switch (kind) {
            case STEP:
                return new double[] {0.0, v0};
            case BARRIER:
                return new double[] {0.0, v0, 0.0};
            case WELL:
                return new double[] {0.0, -v0, 0.0};
            case DOUBLE_BARRIER:
                return new double[] {0.0, v0, 0.0, v0, 0.0};
            default:
                return null;
        }
    }
}
