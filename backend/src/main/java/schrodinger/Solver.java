package schrodinger;

/**
 * A time-stepping method for i dpsi/dt = H psi with H = -1/2 d^2/dx^2 + V(x) - i W(x), where W >= 0 is an
 * optional absorbing layer at the edges of the grid. The grid has n points x_j = xmin + j dx.
 *
 * <p>Besides advancing psi, a solver keeps a running total of the probability the absorbing layer has removed
 * on each side (x < 0 and x >= 0), so that "norm + absorbed = 1" can be checked.
 */
public abstract class Solver {
    protected final int n;
    protected final double dx, dt;
    protected final double[] v, w;
    protected final boolean absorbing;

    /** Probability removed by the absorbing layer so far, for x < 0 and for x >= 0. */
    public double absorbedLeft, absorbedRight;

    protected Solver(int n, double dx, double dt, double[] v, double[] w) {
        this.n = n;
        this.dx = dx;
        this.dt = dt;
        this.v = v;
        this.w = w;
        boolean any = false;
        for (double x : w) if (x > 0) any = true;
        this.absorbing = any;
    }

    public abstract String name();

    /** Advance psi by the given number of steps of size dt. */
    public abstract void advance(double[] re, double[] im, int steps);

    /** True when the solver treats the grid as periodic (Fourier methods), false for hard walls at the ends. */
    public abstract boolean spectral();

    /** Sums of W |psi|^2 over x < 0 (out[0]) and x >= 0 (out[1]). */
    protected void absorbedSums(double[] re, double[] im, double[] out) {
        double l = 0, r = 0;
        int half = n / 2;
        for (int j = 0; j < n; j++) {
            double wj = w[j];
            if (wj == 0.0) continue;
            double p = wj * (re[j] * re[j] + im[j] * im[j]);
            if (j < half) l += p;
            else r += p;
        }
        out[0] = l;
        out[1] = r;
    }
}
