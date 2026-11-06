package schrodinger;

/**
 * Crank-Nicolson method with a three-point finite-difference Laplacian and hard walls (psi = 0 outside the grid):
 *
 * <pre>  (1 + i dt H / 2) psi(t + dt) = (1 - i dt H / 2) psi(t)</pre>
 *
 * The update is the Cayley transform of the Hermitian matrix H, so it is exactly unitary (norm conserved to
 * rounding error) and unconditionally stable. It is second order in dt and dx, and each step solves a
 * tridiagonal system with the Thomas algorithm. The matrix never changes, so its elimination is done once.
 */
public final class CrankNicolson extends Solver {
    private final double[] dRe, dIm;        // diagonal of the left matrix, 1 + i dt/2 (2a + V - iW)
    private final double[] invRe, invIm;    // 1 / (pivot) after elimination
    private final double[] cRe, cIm;        // modified upper diagonal
    private final double offIm;             // the off-diagonal entries are purely imaginary: -(i dt a / 2)
    private final double[] yRe, yIm, rRe, rIm;
    private final double[] s0 = new double[2], s1 = new double[2];

    public CrankNicolson(int n, double dx, double dt, double[] v, double[] w) {
        super(n, dx, dt, v, w);
        double a = 1.0 / (2.0 * dx * dx);
        dRe = new double[n]; dIm = new double[n];
        invRe = new double[n]; invIm = new double[n];
        cRe = new double[n]; cIm = new double[n];
        yRe = new double[n]; yIm = new double[n]; rRe = new double[n]; rIm = new double[n];
        offIm = -dt * a / 2.0;                  // o = -(i dt a / 2) = (0, offIm)
        for (int j = 0; j < n; j++) {
            // 1 + (i dt/2)(2a + V - iW) = 1 + dt W/2 + i dt (2a + V)/2
            dRe[j] = 1.0 + dt * w[j] / 2.0;
            dIm[j] = dt * (2.0 * a + v[j]) / 2.0;
        }
        // Thomas elimination: pivot_0 = d_0, pivot_j = d_j - o c'_{j-1}, c'_j = o / pivot_j
        double pRe = dRe[0], pIm = dIm[0];
        for (int j = 0; j < n; j++) {
            if (j > 0) {
                // o * c'_{j-1} with o = (0, offIm)
                double tRe = -offIm * cIm[j - 1], tIm = offIm * cRe[j - 1];
                pRe = dRe[j] - tRe;
                pIm = dIm[j] - tIm;
            }
            double den = pRe * pRe + pIm * pIm;
            invRe[j] = pRe / den;
            invIm[j] = -pIm / den;
            // c'_j = o * inv = (0, offIm) * (invRe, invIm)
            cRe[j] = -offIm * invIm[j];
            cIm[j] = offIm * invRe[j];
        }
    }

    @Override
    public String name() {
        return "Crank-Nicolson";
    }

    @Override
    public boolean spectral() {
        return false;
    }

    private void step(double[] re, double[] im) {
        double a = 1.0 / (2.0 * dx * dx);
        double qIm = dt * a / 2.0;              // alpha a = (0, dt a / 2)
        // right-hand side r = (2 - d) psi + alpha a (psi_- + psi_+)
        for (int j = 0; j < n; j++) {
            double eRe = 2.0 - dRe[j], eIm = -dIm[j];
            double pr = re[j], pi = im[j];
            double nr = (j > 0 ? re[j - 1] : 0.0) + (j < n - 1 ? re[j + 1] : 0.0);
            double ni = (j > 0 ? im[j - 1] : 0.0) + (j < n - 1 ? im[j + 1] : 0.0);
            rRe[j] = eRe * pr - eIm * pi - qIm * ni;
            rIm[j] = eRe * pi + eIm * pr + qIm * nr;
        }
        // forward sweep: y_0 = r_0 / d_0 and y_j = (r_j - o y_{j-1}) / pivot_j
        for (int j = 0; j < n; j++) {
            double tr = rRe[j], ti = rIm[j];
            if (j > 0) {
                tr -= -offIm * yIm[j - 1];
                ti -= offIm * yRe[j - 1];
            }
            yRe[j] = tr * invRe[j] - ti * invIm[j];
            yIm[j] = tr * invIm[j] + ti * invRe[j];
        }
        // back substitution: x_j = y_j - c'_j x_{j+1}
        re[n - 1] = yRe[n - 1];
        im[n - 1] = yIm[n - 1];
        for (int j = n - 2; j >= 0; j--) {
            double xr = re[j + 1], xi = im[j + 1];
            re[j] = yRe[j] - (cRe[j] * xr - cIm[j] * xi);
            im[j] = yIm[j] - (cRe[j] * xi + cIm[j] * xr);
        }
    }

    @Override
    public void advance(double[] re, double[] im, int steps) {
        if (absorbing) absorbedSums(re, im, s0);
        for (int s = 0; s < steps; s++) {
            step(re, im);
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
