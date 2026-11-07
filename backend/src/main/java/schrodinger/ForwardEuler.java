package schrodinger;

/**
 * Forward Euler, psi(t + dt) = psi(t) - i dt H psi(t), included only to show why it must not be used:
 * the amplification factor of each Fourier mode is sqrt(1 + (E dt)^2) > 1 for every dt, so the norm grows
 * without bound and the highest-frequency grid modes explode.
 */
public final class ForwardEuler extends Solver {
    private final double[] tr, ti;
    private final double[] s0 = new double[2], s1 = new double[2];

    public ForwardEuler(int n, double dx, double dt, double[] v, double[] w) {
        super(n, dx, dt, v, w);
        tr = new double[n];
        ti = new double[n];
    }

    @Override
    public String name() {
        return "Forward Euler (unstable)";
    }

    @Override
    public boolean spectral() {
        return false;
    }

    @Override
    public void advance(double[] re, double[] im, int steps) {
        double a = 1.0 / (2.0 * dx * dx);
        if (absorbing) absorbedSums(re, im, s0);
        for (int s = 0; s < steps; s++) {
            for (int j = 0; j < n; j++) {
                double nr = (j > 0 ? re[j - 1] : 0.0) + (j < n - 1 ? re[j + 1] : 0.0);
                double ni = (j > 0 ? im[j - 1] : 0.0) + (j < n - 1 ? im[j + 1] : 0.0);
                double diag = 2.0 * a + v[j];
                double hr = diag * re[j] - a * nr + w[j] * im[j];     // H psi = hr + i hi, with the -iW term included
                double hi = diag * im[j] - a * ni - w[j] * re[j];
                tr[j] = re[j] + dt * hi;
                ti[j] = im[j] - dt * hr;
            }
            System.arraycopy(tr, 0, re, 0, n);
            System.arraycopy(ti, 0, im, 0, n);
            if (absorbing) {
                absorbedSums(re, im, s1);
                absorbedLeft += dt * dx * (s0[0] + s1[0]);
                absorbedRight += dt * dx * (s0[1] + s1[1]);
                s0[0] = s1[0];
                s0[1] = s1[1];
            }
        }
    }
}
