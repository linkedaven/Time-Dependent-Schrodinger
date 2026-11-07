package schrodinger;

/**
 * Exact scattering of plane waves from a piecewise-constant potential (matching psi and psi' at every edge),
 * and the transmission probability of a Gaussian wave packet obtained by averaging over its momentum distribution.
 * The program uses this to check the numerical solvers.
 */
public final class Exact {
    private Exact() {}

    /** Minimal complex number. */
    private record Cx(double re, double im) {
        static final Cx ONE = new Cx(1, 0), ZERO = new Cx(0, 0);

        Cx add(Cx o) { return new Cx(re + o.re, im + o.im); }
        Cx sub(Cx o) { return new Cx(re - o.re, im - o.im); }
        Cx mul(Cx o) { return new Cx(re * o.re - im * o.im, re * o.im + im * o.re); }
        Cx scale(double s) { return new Cx(re * s, im * s); }
        Cx neg() { return new Cx(-re, -im); }
        double abs2() { return re * re + im * im; }

        Cx div(Cx o) {
            double d = o.abs2();
            return new Cx((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d);
        }

        /** exp(i z) for complex z. */
        static Cx expI(Cx z) {
            double m = Math.exp(-z.im);
            return new Cx(m * Math.cos(z.re), m * Math.sin(z.re));
        }
    }

    private static final Cx I = new Cx(0, 1);

    /** The matrix whose columns are the right- and left-moving waves exp(+-ikx) and their derivatives, at x. */
    private static Cx[][] waves(Cx k, double x) {
        Cx kx = k.scale(x);
        Cx p = Cx.expI(kx), m = Cx.expI(kx.neg());
        Cx ik = I.mul(k);
        return new Cx[][] {{p, m}, {ik.mul(p), ik.mul(m).neg()}};
    }

    private static Cx[][] mul(Cx[][] a, Cx[][] b) {
        Cx[][] c = new Cx[2][2];
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++) c[i][j] = a[i][0].mul(b[0][j]).add(a[i][1].mul(b[1][j]));
        return c;
    }

    private static Cx[][] inverse(Cx[][] a) {
        Cx det = a[0][0].mul(a[1][1]).sub(a[0][1].mul(a[1][0]));
        return new Cx[][] {
            {a[1][1].div(det), a[0][1].neg().div(det)},
            {a[1][0].neg().div(det), a[0][0].div(det)}};
    }

    /**
     * Probability that a plane wave with wave number k in the first region is transmitted through the structure
     * (flux ratio), for levels.length regions separated by edges.length edges. The wave comes in from the left.
     */
    public static double transmission(double[] edges, double[] levels, double k) {
        int m = levels.length;
        double energy = 0.5 * k * k + levels[0];
        Cx[] kk = new Cx[m];
        for (int j = 0; j < m; j++) {
            double d = energy - levels[j];
            if (Math.abs(d) < 1e-12) d = 1e-12;
            kk[j] = d > 0 ? new Cx(Math.sqrt(2 * d), 0) : new Cx(0, Math.sqrt(-2 * d));
        }
        Cx[][] total = {{Cx.ONE, Cx.ZERO}, {Cx.ZERO, Cx.ONE}};
        for (int j = 0; j < m - 1; j++) {
            Cx[][] left = waves(kk[j], edges[j]), right = waves(kk[j + 1], edges[j]);
            total = mul(total, mul(inverse(left), right));
        }
        Cx a = Cx.ONE.div(total[0][0]);            // amplitude of the outgoing wave in the last region
        Cx last = kk[m - 1];
        if (last.im != 0.0) return 0.0;             // evanescent beyond the structure: nothing gets through
        return last.re / kk[0].re * a.abs2();
    }

    /**
     * Transmission probability of a Gaussian packet with mean wave number k0 and width sigma (position),
     * i.e. the average of {@link #transmission} over its momentum distribution. NaN when the packet has a
     * noticeable part moving the other way, so the plane-wave picture does not apply.
     */
    public static double packetTransmission(double[] edges, double[] levels, double k0, double sigma) {
        double[] e = edges.clone(), v = levels.clone();
        if (k0 < 0) {                               // mirror the structure so the packet comes in from the left
            int m = e.length;
            for (int i = 0; i < m; i++) e[i] = -edges[m - 1 - i];
            for (int i = 0; i < v.length; i++) v[i] = levels[v.length - 1 - i];
        }
        double kc = Math.abs(k0), sk = 1.0 / (2.0 * sigma);
        if (kc < 2.5 * sk) return Double.NaN;
        double lo = Math.max(1e-6, kc - 6 * sk), hi = kc + 6 * sk;
        int steps = 1200;
        double num = 0, den = 0;
        for (int i = 0; i <= steps; i++) {
            double k = lo + (hi - lo) * i / steps;
            double w = Math.exp(-(k - kc) * (k - kc) / (2 * sk * sk));
            double c = (i == 0 || i == steps) ? 0.5 : 1.0;
            num += c * w * transmission(e, v, k);
            den += c * w;
        }
        return num / den;
    }
}
