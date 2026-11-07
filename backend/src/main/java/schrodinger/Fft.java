package schrodinger;

/** Iterative radix-2 fast Fourier transform on separate real and imaginary arrays (length a power of two). */
public final class Fft {
    private final int n;
    private final int[] rev;
    private final double[] cos, sin;

    public Fft(int n) {
        if (n < 2 || (n & (n - 1)) != 0) throw new IllegalArgumentException("length must be a power of two: " + n);
        this.n = n;
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        cos = new double[n / 2];
        sin = new double[n / 2];
        for (int k = 0; k < n / 2; k++) {
            cos[k] = Math.cos(2.0 * Math.PI * k / n);
            sin[k] = Math.sin(2.0 * Math.PI * k / n);
        }
    }

    /** Forward: F_m = sum_j f_j exp(-2 pi i j m / n). Inverse: the same with a plus sign, divided by n. */
    public void transform(double[] re, double[] im, boolean inverse) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1, step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int j = 0, k = 0; j < half; j++, k += step) {
                    double wr = cos[k], wi = inverse ? sin[k] : -sin[k];
                    int a = i + j, b = a + half;
                    double xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
        if (inverse) {
            double s = 1.0 / n;
            for (int i = 0; i < n; i++) {
                re[i] *= s;
                im[i] *= s;
            }
        }
    }
}
