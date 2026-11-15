"""Count families for Q3 (pmf form: rows of probabilities on 0..K)."""
import numpy as np
import common as C


def min1d(f, lo, hi, iters=40):
    g = (np.sqrt(5) - 1) / 2
    a, b = lo, hi
    c, d = b - g * (b - a), a + g * (b - a)
    fc, fd = f(c), f(d)
    for _ in range(iters):
        if fc < fd:
            b, d, fd = d, c, fc
            c = b - g * (b - a)
            fc = f(c)
        else:
            a, c, fc = c, d, fd
            d = a + g * (b - a)
            fd = f(d)
    return 0.5 * (a + b)


class _ByPos:
    K = 40

    def __init__(self, K=40, **kw):
        self.K = K
        self.kw = kw

    def fit(self, tr, mu, y):
        pos = tr["position"].to_numpy()
        self.m = {p: self._fit(mu[pos == p], y[pos == p], tr[pos == p]) for p in np.unique(pos)}
        return self

    def predict(self, te, mu):
        pos = te["position"].to_numpy()
        out = np.zeros((len(te), self.K + 1))
        for p in np.unique(pos):
            a = pos == p
            out[a] = self._pmf(self.m[p], mu[a], te[a])
        return out


class Poisson(_ByPos):
    def _fit(self, mu, y, d):
        return None

    def _pmf(self, m, mu, d):
        return C.nb_pmf(mu, 0.0, self.K)


class NBML(_ByPos):
    """Negative binomial, alpha = exp(a + b log mu). mode 'const' fixes b = 0 (continuous ML, not the grid);
    'mu' fits b too (b = -1 is the NB1 form, variance proportional to the mean)."""

    def _fit(self, mu, y, d):
        if self.kw.get("mode", "const") == "const":
            la = min1d(lambda t: -C.nb_ll(y, mu, np.exp(t)).sum(), -9.0, 1.5)
            return np.array([la, 0.0])
        lm = np.log(mu)
        t, _ = C.nelder_mead(lambda t: -C.nb_ll(y, mu, np.exp(np.clip(t[0] + t[1] * lm, -12, 3))).mean(), [-2.0, 0.0])
        return t

    def _pmf(self, m, mu, d):
        return C.nb_pmf(mu, np.exp(np.clip(m[0] + m[1] * np.log(mu), -12, 3)), self.K)


class Binomial(_ByPos):
    """Binomial(n, mu / n): variance mu (1 - mu/n), i.e. under-dispersed. n by ML over integers."""
    grid = (6, 7, 8, 9, 10, 12, 14, 17, 20, 25, 30, 40, 60, 100, 100000)

    def _lp(self, n, mu):
        k = np.arange(self.K + 1)[None, :]
        p = np.clip(mu / n, 1e-6, 0.95)[:, None]
        with np.errstate(invalid="ignore", divide="ignore"):
            lp = C.lgam(n + 1.0) - C.lgam(k + 1.0) - C.lgam(np.maximum(n - k, 0) + 1.0) + k * np.log(p) + (n - k) * np.log1p(-p)
        return np.where(k <= n, lp, -np.inf)

    def _fit(self, mu, y, d):
        yi = np.clip(y.astype(int), 0, self.K)
        r = np.arange(len(y))
        return max(self.grid, key=lambda n: self._lp(n, mu)[r, yi].sum())

    def _pmf(self, m, mu, d):
        p = np.exp(self._lp(m, mu))
        return p / p.sum(1, keepdims=True)


class ComPoisson(_ByPos):
    """Conway-Maxwell-Poisson: p(k) ~ lam^k / (k!)^nu, lam solved so the mean is mu. nu > 1 under-dispersed.
    nu = exp(a + b log mu) (mode 'const': b = 0)."""
    G = np.linspace(-14, 14, 561)

    def _pm(self, mu, nu):
        k = np.arange(self.K + 1)
        lf = C.lgam(k + 1.0)
        out = np.zeros((len(mu), self.K + 1))
        nus = np.round(nu, 3)
        for v in np.unique(nus):
            a = nus == v
            lp = self.G[:, None] * k[None, :] - v * lf[None, :]
            pg = np.exp(lp - lp.max(1, keepdims=True))
            pg /= pg.sum(1, keepdims=True)
            ll = np.interp(mu[a], pg @ k, self.G)
            lp = ll[:, None] * k[None, :] - v * lf[None, :]
            p = np.exp(lp - lp.max(1, keepdims=True))
            out[a] = p / p.sum(1, keepdims=True)
        return out

    def _nu(self, t, mu):
        return np.exp(np.clip(t[0] + t[1] * np.log(mu), -1.5, 1.5))

    def _fit(self, mu, y, d):
        yi = np.clip(y.astype(int), 0, self.K)
        r = np.arange(len(y))
        if self.kw.get("mode", "const") == "const":
            t = min1d(lambda t: -np.log(self._pm(mu, np.full(len(mu), np.exp(t)))[r, yi] + 1e-12).sum(), -1.0, 1.5, iters=25)
            return np.array([t, 0.0])
        # nu rounded to 0.05 steps while fitting to keep the table count small
        def f(t):
            nu = np.round(self._nu(t, mu) / 0.05) * 0.05
            return -np.log(self._pm(mu, nu)[r, yi] + 1e-12).mean()
        t, _ = C.nelder_mead(f, [0.2, 0.0], step=0.2, iters=60, tol=1e-6)
        return t

    def _pmf(self, m, mu, d):
        nu = self._nu(m, mu)
        if m[1] != 0.0:
            nu = np.round(nu / 0.02) * 0.02
        return self._pm(mu, nu)


class EmpAsPmf:
    """The baseline 'yards' treatment of an integer stat, expressed on the integers: P(Y <= k) = F(k + 0.5)."""

    def __init__(self, K=80, **kw):
        self.K, self.e = K, C.EmpRatio(**kw)

    def fit(self, tr, mu, y):
        self.e.fit(tr, mu, y)
        return self

    def predict(self, te, mu):
        Q = self.e.predict(te, mu)
        cdf = np.column_stack([C.cdf_at(Q, k + 0.5) for k in range(self.K + 1)])
        cdf[:, -1] = 1.0
        return np.diff(np.column_stack([np.zeros(len(te)), cdf]), axis=1)


def bb_logpmf(k, t, a, b):
    """log beta-binomial pmf, arrays broadcast; -inf where k > t."""
    with np.errstate(invalid="ignore", divide="ignore"):
        lp = (C.lgam(t + 1.0) - C.lgam(k + 1.0) - C.lgam(np.maximum(t - k, 0) + 1.0)
              + C.lgam(k + a) + C.lgam(np.maximum(t - k, 0) + b) - C.lgam(t + a + b)
              + C.lgam(a + b) - C.lgam(a) - C.lgam(b))
    return np.where(k <= t, lp, -np.inf)


class BBMix:
    """Successes out of an opportunity count: opportunities T ~ NB(mu_T, alpha_T(mu_T)), successes | T ~
    beta-binomial(T, c * phi, (1 - c) * phi) with c = mu / mu_T (so the mean is the stage-1 mean) and one
    phi per position (ML on observed (T, successes) pairs). Needs columns mu_aux (expected T) and y_aux (actual T)."""

    def __init__(self, K=40, T=45, nbmode="mu"):
        self.K, self.T, self.nbmode = K, T, nbmode

    def fit(self, tr, mu, y):
        self.nb = NBML(K=self.T, mode=self.nbmode).fit(tr, tr["mu_aux"].to_numpy(), tr["y_aux"].to_numpy())
        self.phi = {}
        pos = tr["position"].to_numpy()
        for p in np.unique(pos):
            a = pos == p
            t, k = tr["y_aux"].to_numpy()[a], y[a]
            c = np.clip(mu[a] / tr["mu_aux"].to_numpy()[a], 0.03, 0.97)
            ok = t >= 1
            self.phi[p] = np.exp(min1d(lambda l: -bb_logpmf(k[ok], t[ok], c[ok] * np.exp(l), (1 - c[ok]) * np.exp(l)).sum(), 0.0, 9.0))
        return self

    def predict(self, te, mu):
        muT = te["mu_aux"].to_numpy()
        pT = self.nb.predict(te, muT)                                  # n x (T+1)
        c = np.clip(mu / muT, 0.03, 0.97)
        phi = te["position"].map(self.phi).to_numpy(float)
        out = np.zeros((len(te), self.K + 1))
        k = np.arange(self.K + 1)[None, :]
        for t in range(self.T + 1):
            out += pT[:, t:t + 1] * np.exp(bb_logpmf(k, float(t), (c * phi)[:, None], ((1 - c) * phi)[:, None]))
        return out / out.sum(1, keepdims=True)


class NBFromP(_ByPos):
    """Count distribution anchored on a probability of at least one: the 'mu' passed in is p = P(Y >= 1).
    Negative binomial with one alpha per position (ML on the package grid) and mean chosen so that P(0) = 1 - p:
    mean = r * ((1 - p) ** (-1 / r) - 1), r = 1 / alpha  (alpha = 0: Poisson, mean = -log(1 - p))."""
    grid = (0.0, 0.02, 0.05, 0.08, 0.12, 0.17, 0.23, 0.3, 0.4, 0.55, 0.75, 1.0)

    @staticmethod
    def mean(p, a):
        p = np.clip(p, 1e-6, 1 - 1e-6)
        return -np.log(1 - p) if a < 1e-6 else ((1 - p) ** (-a) - 1) / a

    def _fit(self, p, y, d):
        return max(self.grid, key=lambda a: C.nb_ll(y, self.mean(p, a), a).sum())

    def _pmf(self, a, p, d):
        return C.nb_pmf(self.mean(p, a), a, self.K)
