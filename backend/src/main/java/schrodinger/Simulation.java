package schrodinger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one simulation: a Gaussian wave packet in a chosen potential, advanced by one of the solvers, and records
 * FRAMES snapshots together with the quantities that should (or should not) stay constant.
 *
 * <p>Units: hbar = m = 1. The grid covers x from XMIN to XMIN + L. The packet is
 * <pre>  psi(x, 0) = (2 pi sigma^2)^(-1/4) exp(-(x - x0)^2 / (4 sigma^2)) exp(i k0 x)</pre>
 */
public final class Simulation {
    private Simulation() {}

    public static final double XMIN = -40.0, L = 80.0;
    public static final int FRAMES = 240;
    /** Points per frame sent to the page for psi, and bins of the momentum distribution. */
    public static final int DISPLAY_POINTS = 1024, MOMENTUM_BINS = 512;
    /** The absorbing layer starts at |x| = ABSORB_START and grows quadratically to the edge. */
    static final double ABSORB_START = 24.0, ABSORB_STRENGTH = 2.0;
    /** Reflecting edges: a smooth wall V = WALL_HEIGHT u^4, with u running from 0 to 1 over the last 6 units at each end. */
    static final double WALL_START = 34.0, WALL_HEIGHT = 150.0;
    public static final long MAX_WORK = 150_000_000L;       // steps x grid points

    public enum Method {
        SPLIT("split"), CN("cn"), EULER("euler");

        public final String id;

        Method(String id) {
            this.id = id;
        }

        public static Method of(String id) {
            for (Method m : values()) if (m.id.equals(id)) return m;
            return null;
        }
    }

    public record Params(Potential potential, double x0, double k0, double sigma, Method method, int n, double dt,
                         double tmax, boolean absorbing) {}

    public static final class Result {
        public Map<String, Object> meta;
        /** Per frame: density (M), Re psi (M), Im psi (M), momentum density (K), as float32. */
        public float[] data;
    }

    /** Thrown for a request that is valid but too expensive or inconsistent. */
    public static final class SimulationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SimulationException(String message) {
            super(message);
        }
    }

    public static Result run(Params p) {
        final int n = p.n(), frames = FRAMES, m = Math.min(DISPLAY_POINTS, n), mk = MOMENTUM_BINS;
        final double dx = L / n;
        final Potential pot = p.potential();

        int spf = (int) Math.ceil(p.tmax() / (p.dt() * (frames - 1)));
        if (spf < 1) spf = 1;
        final double dtEff = p.tmax() / ((frames - 1.0) * spf);
        long work = (long) spf * (frames - 1) * n;
        if (work > MAX_WORK) {
            throw new SimulationException("Too much work for one run (" + (long) spf * (frames - 1)
                    + " steps on " + n + " grid points). Shorten the duration, use a larger time step or fewer grid points.");
        }

        // ---- grid, potential, absorbing layer
        double[] x = new double[n], v = new double[n], vShown = new double[n], w = new double[n];
        for (int j = 0; j < n; j++) {
            x[j] = XMIN + j * dx;
            vShown[j] = cellAverage(pot, x[j], dx);
            v[j] = vShown[j];
            double edgeDist = Math.abs(x[j]);
            if (p.absorbing()) {
                if (edgeDist > ABSORB_START) {
                    double u = (edgeDist - ABSORB_START) / (L / 2 - ABSORB_START);
                    w[j] = ABSORB_STRENGTH * u * u;
                }
            } else if (edgeDist > WALL_START) {
                double u = (edgeDist - WALL_START) / (L / 2 - WALL_START);
                v[j] += WALL_HEIGHT * u * u * u * u;
            }
        }

        // ---- initial packet
        double[] re = new double[n], im = new double[n];
        double amp = Math.pow(2 * Math.PI * p.sigma() * p.sigma(), -0.25);
        for (int j = 0; j < n; j++) {
            double g = amp * Math.exp(-(x[j] - p.x0()) * (x[j] - p.x0()) / (4 * p.sigma() * p.sigma()));
            re[j] = g * Math.cos(p.k0() * x[j]);
            im[j] = g * Math.sin(p.k0() * x[j]);
        }

        Solver solver;
        switch (p.method()) {
            case CN -> solver = new CrankNicolson(n, dx, dtEff, v, w);
            case EULER -> solver = new ForwardEuler(n, dx, dtEff, v, w);
            default -> solver = new SplitStep(n, dx, dtEff, v, w);
        }

        // ---- region edges for left / inside / right probabilities
        double[] edges = pot.edges();
        boolean scattering = edges != null;
        double xl = scattering ? edges[0] : 0.0, xr = scattering ? edges[edges.length - 1] : 0.0;

        // ---- time series
        double[] t = new double[frames], normS = new double[frames], accounted = new double[frames],
                energy = new double[frames], xmean = new double[frames], pmean = new double[frames],
                dxS = new double[frames], dpS = new double[frames], pLeft = new double[frames],
                pIn = new double[frames], pRight = new double[frames], xExact = new double[frames];
        float[] data = new float[frames * (3 * m + mk)];
        int stride = n / m;
        if (stride < 1) stride = 1;

        Fft fft = new Fft(n);
        double[] fr = new double[n], fi = new double[n];
        double dk = 2.0 * Math.PI / L;
        int fc = 0;
        Double blewUp = null;
        double denMax = 0, momMax = 0;

        for (int f = 0; f < frames; f++) {
            if (f > 0) solver.advance(re, im, spf);

            // norm, position, potential and probability by region
            double norm = 0, xm = 0, x2 = 0, vs = 0, pl = 0, pi = 0, pr = 0;
            for (int j = 0; j < n; j++) {
                double d = re[j] * re[j] + im[j] * im[j];
                norm += d;
                xm += x[j] * d;
                x2 += x[j] * x[j] * d;
                vs += v[j] * d;
                if (x[j] < xl) pl += d;
                else if (x[j] > xr || (!scattering)) pr += d;
                else pi += d;
            }
            norm *= dx; xm *= dx; x2 *= dx; vs *= dx; pl *= dx; pi *= dx; pr *= dx;
            if (!Double.isFinite(norm) || norm > 1e6) {         // the unstable method has blown up
                blewUp = f * spf * dtEff;
                break;
            }

            // momentum distribution (periodic Fourier transform of psi)
            System.arraycopy(re, 0, fr, 0, n);
            System.arraycopy(im, 0, fi, 0, n);
            fft.transform(fr, fi, false);
            double pn = 0, p1 = 0, p2 = 0, scale = dx * dx / (2 * Math.PI);
            int base = f * (3 * m + mk) + 3 * m;
            for (int b = 0; b < n; b++) {
                double k = dk * (b < n / 2 ? b : b - n);
                double dens = scale * (fr[b] * fr[b] + fi[b] * fi[b]);
                pn += dens;
                p1 += k * dens;
                p2 += k * k * dens;
            }
            for (int b = 0; b < mk; b++) {
                int idx = ((b - mk / 2) % n + n) % n;
                float dens = (float) (scale * (fr[idx] * fr[idx] + fi[idx] * fi[idx]));
                data[base + b] = dens;
                if (norm < 3) momMax = Math.max(momMax, dens);
            }
            pn *= dk; p1 *= dk; p2 *= dk;
            double meanP = p1 / pn, meanP2 = p2 / pn;

            // kinetic energy: spectral for the Fourier method, finite-difference for the others
            double kin;
            if (solver.spectral()) {
                kin = 0.5 * meanP2;
            } else {
                double s = 0;
                for (int j = -1; j < n; j++) {
                    double a1 = j >= 0 ? re[j] : 0.0, b1 = j >= 0 ? im[j] : 0.0;
                    double a2 = j + 1 < n ? re[j + 1] : 0.0, b2 = j + 1 < n ? im[j + 1] : 0.0;
                    s += (a2 - a1) * (a2 - a1) + (b2 - b1) * (b2 - b1);
                }
                kin = s / (2 * dx) / norm;
            }

            t[f] = f * spf * dtEff;
            normS[f] = norm;
            accounted[f] = norm + solver.absorbedLeft + solver.absorbedRight;
            energy[f] = kin + vs / norm;
            xmean[f] = xm / norm;
            pmean[f] = meanP;
            dxS[f] = Math.sqrt(Math.max(0.0, x2 / norm - (xm / norm) * (xm / norm)));
            dpS[f] = Math.sqrt(Math.max(0.0, meanP2 - meanP * meanP));
            pLeft[f] = pl + solver.absorbedLeft;      // absorbed probability is attributed to the side it left from
            pIn[f] = pi;
            pRight[f] = pr + solver.absorbedRight;
            if (pot.kind() == Potential.Kind.HARMONIC) {
                double om = Math.sqrt(2 * pot.value(1.0));
                xExact[f] = p.x0() * Math.cos(om * t[f]) + p.k0() / om * Math.sin(om * t[f]);
            }

            int o = f * (3 * m + mk);
            for (int i = 0; i < m; i++) {
                int j = i * stride;
                float d = (float) (re[j] * re[j] + im[j] * im[j]);
                data[o + i] = d;
                data[o + m + i] = (float) re[j];
                data[o + 2 * m + i] = (float) im[j];
                if (norm < 3) denMax = Math.max(denMax, d);
            }
            fc++;
        }
        if (fc < frames) {
            float[] cut = new float[fc * (3 * m + mk)];
            System.arraycopy(data, 0, cut, 0, cut.length);
            data = cut;
        }

        // ---- exact comparison (piecewise-constant potentials only)
        Map<String, Object> exact = new LinkedHashMap<>();
        boolean applicable = false;
        double tExact = Double.NaN;
        if (scattering) {
            double tt = Exact.packetTransmission(pot.edges(), pot.levels(), p.k0(), p.sigma());
            boolean clear = p.k0() > 0 ? p.x0() + 3 * p.sigma() < xl : p.x0() - 3 * p.sigma() > xr;
            if (!Double.isNaN(tt) && clear) {
                applicable = true;
                tExact = tt;
            }
        }
        exact.put("applicable", applicable);
        exact.put("transmitted", applicable ? tExact : null);
        exact.put("reflected", applicable ? 1.0 - tExact : null);

        // ---- warnings
        List<String> warnings = new ArrayList<>();
        double sk = 1.0 / (2.0 * p.sigma()), kGrid = Math.PI / dx;
        if (Math.abs(p.k0()) + 4 * sk > 0.5 * kGrid) {
            warnings.add("The packet's momentum is close to the limit of this grid (k = " + fmt(kGrid)
                    + "). Use more grid points for accurate results.");
        }
        double edgeStart = p.absorbing() ? ABSORB_START : WALL_START;
        if (Math.abs(p.x0()) + 4 * p.sigma() > edgeStart) {
            warnings.add("The packet starts inside the edge region of the grid (|x| > " + fmt(edgeStart) + ").");
        }
        if (scattering && !applicable) {
            warnings.add("No exact transmission is shown: the packet must start clear of the structure, moving towards it, with a narrow momentum spread.");
        }
        if (pot.minFeature() < 4 * dx) {
            warnings.add("The barrier, well or gap is only a few grid cells wide; use more grid points.");
        }

        // ---- meta
        double e0 = energy[0];
        double vMin = 0, vMax = 0;
        for (double d : vShown) {
            vMin = Math.min(vMin, d);
            vMax = Math.max(vMax, d);
        }
        double vTop = pot.piecewise() || pot.kind() == Potential.Kind.FREE ? Math.max(vMax, e0) * 1.3
                : Math.max(e0 * 2.4, 1e-6);
        if (pot.kind() == Potential.Kind.DOUBLE_WELL) vTop = Math.max(vTop, vMax * 1.3);
        if (vTop <= 0) vTop = 1.0;
        double[] vOut = new double[m];
        for (int i = 0; i < m; i++) vOut[i] = Math.min(vShown[i * stride], vTop * 4);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("method", solver.name());
        meta.put("potential", pot.kind().id);
        meta.put("n", n);
        meta.put("m", m);
        meta.put("mk", mk);
        meta.put("frames", fc);
        meta.put("framesRequested", frames);
        meta.put("tmax", p.tmax());
        meta.put("dtUsed", dtEff);
        meta.put("stepsPerFrame", spf);
        meta.put("xmin", XMIN);
        meta.put("xmax", XMIN + L);
        meta.put("dx", dx);
        meta.put("kmaxView", dk * (mk / 2));
        meta.put("kGrid", kGrid);
        meta.put("absorbing", p.absorbing());
        meta.put("edgeStart", p.absorbing() ? ABSORB_START : WALL_START);
        meta.put("V", vOut);
        meta.put("vMin", vMin);
        meta.put("vTop", vTop);
        meta.put("denMax", denMax);
        meta.put("momMax", momMax);
        meta.put("t", trim(t, fc));
        meta.put("norm", trim(normS, fc));
        meta.put("accounted", trim(accounted, fc));
        meta.put("energy", trim(energy, fc));
        meta.put("xmean", trim(xmean, fc));
        meta.put("pmean", trim(pmean, fc));
        meta.put("dxSpread", trim(dxS, fc));
        meta.put("dpSpread", trim(dpS, fc));
        meta.put("scattering", scattering);
        if (scattering) {
            meta.put("structure", new double[] {xl, xr});
            meta.put("pLeft", trim(pLeft, fc));
            meta.put("pInside", trim(pIn, fc));
            meta.put("pRight", trim(pRight, fc));
        }
        meta.put("exact", exact);
        if (pot.kind() == Potential.Kind.HARMONIC) {
            meta.put("xExact", trim(xExact, fc));
            meta.put("omega", Math.sqrt(2 * pot.value(1.0)));
        }
        meta.put("blewUpAt", blewUp);
        meta.put("warnings", warnings);

        Result r = new Result();
        r.meta = meta;
        r.data = data;
        return r;
    }

    /**
     * V averaged over the grid cell around x. For a potential with sharp edges this puts the edge where it
     * belongs to first order (a barrier of width 1 behaves as width 1, not as 25 or 26 grid cells).
     */
    private static double cellAverage(Potential pot, double x, double dx) {
        if (!pot.piecewise()) return pot.value(x);
        final int sub = 64;
        double sum = 0;
        for (int i = 0; i < sub; i++) sum += pot.value(x - dx / 2 + dx * (i + 0.5) / sub);
        return sum / sub;
    }

    private static double[] trim(double[] a, int len) {
        double[] out = new double[len];
        System.arraycopy(a, 0, out, 0, len);
        return out;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3g", v);
    }
}
