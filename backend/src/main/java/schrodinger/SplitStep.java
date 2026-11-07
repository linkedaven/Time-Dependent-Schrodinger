package schrodinger;

/**
 * Split-step Fourier method (Strang splitting):
 *
 * <pre>  psi(t + dt) = exp(-i V dt/2) F^-1 exp(-i k^2 dt/2) F exp(-i V dt/2) psi(t)</pre>
 *
 * Each factor is a pure phase, so the method is unitary (it conserves the norm to rounding error) and
 * unconditionally stable. The error per step is O(dt^3); the spatial accuracy is spectral.
 */
public final class SplitStep extends Solver {
    private final Fft fft;
    private final double[] hc, hs, hd, fc, fs, fd, kc, ks;
    private final boolean absorbingLayer;

    public SplitStep(int n, double dx, double dt, double[] v, double[] w) {
        super(n, dx, dt, v, w);
        fft = new Fft(n);
        hc = new double[n]; hs = new double[n]; hd = new double[n];
        fc = new double[n]; fs = new double[n]; fd = new double[n];
        for (int j = 0; j < n; j++) {
            hc[j] = Math.cos(v[j] * dt / 2); hs[j] = Math.sin(v[j] * dt / 2); hd[j] = Math.exp(-w[j] * dt / 2);
            fc[j] = Math.cos(v[j] * dt); fs[j] = Math.sin(v[j] * dt); fd[j] = Math.exp(-w[j] * dt);
        }
        kc = new double[n];
        ks = new double[n];
        double dk = 2.0 * Math.PI / (n * dx);
        for (int m = 0; m < n; m++) {
            double k = dk * (m < n / 2 ? m : m - n);
            kc[m] = Math.cos(k * k * dt / 2);
            ks[m] = Math.sin(k * k * dt / 2);
        }
        absorbingLayer = absorbing;
    }

    @Override
    public String name() {
        return "Split-step Fourier";
    }

    @Override
    public boolean spectral() {
        return true;
    }

    private void potentialPhase(double[] re, double[] im, double[] c, double[] s, double[] d) {
        int half = n / 2;
        for (int j = 0; j < n; j++) {
            double a = re[j], b = im[j], dj = d[j];
            if (absorbingLayer && dj < 1.0) {
                double loss = (a * a + b * b) * (1.0 - dj * dj) * dx;
                if (j < half) absorbedLeft += loss;
                else absorbedRight += loss;
            }
            re[j] = dj * (a * c[j] + b * s[j]);
            im[j] = dj * (b * c[j] - a * s[j]);
        }
    }

    private void kinetic(double[] re, double[] im) {
        fft.transform(re, im, false);
        for (int m = 0; m < n; m++) {
            double a = re[m], b = im[m];
            re[m] = a * kc[m] + b * ks[m];
            im[m] = b * kc[m] - a * ks[m];
        }
        fft.transform(re, im, true);
    }

    @Override
    public void advance(double[] re, double[] im, int steps) {
        potentialPhase(re, im, hc, hs, hd);
        for (int s = 1; s <= steps; s++) {
            kinetic(re, im);
            if (s < steps) potentialPhase(re, im, fc, fs, fd);      // two half steps merged into one
            else potentialPhase(re, im, hc, hs, hd);
        }
    }
}
