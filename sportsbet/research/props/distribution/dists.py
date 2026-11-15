"""Parametric yardage families (quantile form) used in Q2. All parameters are smooth functions of the
expected value mu, fitted per position by maximum likelihood (Nelder-Mead)."""
import numpy as np
import common as C

# ---- Gamma(k, 1) quantile table: rows = shapes, cols = probability grid
_K = np.exp(np.linspace(np.log(0.12), np.log(80.0), 90))
_V = np.unique(np.concatenate([np.linspace(0, 0.01, 21), np.linspace(0.01, 0.99, 197), np.linspace(0.99, 1, 41)]))
_V[-1] = 1 - 1e-7
_TAB = None


def _gamma_table():
    global _TAB
    if _TAB is None:
        x = np.concatenate([[0.0], np.exp(np.linspace(np.log(1e-12), np.log(260.0), 6000))])
        T = np.zeros((len(_K), len(_V)))
        for i, k in enumerate(_K):
            term = np.ones_like(x)
            s = np.ones_like(x)
            for n in range(1, 700):
                term = term * x / (k + n)
                s += term
            with np.errstate(divide="ignore", invalid="ignore"):
                Pk = np.exp(k * np.log(x) - x - C.lgam(k + 1.0)) * s
            Pk[0] = 0.0
            Pk = np.maximum.accumulate(np.clip(Pk, 0, 1))
            T[i] = np.interp(_V, Pk, x)
        _TAB = T
    return _TAB


def gamma_ppf(k, V):
    """Quantiles of Gamma(shape k_i, scale 1) at probabilities V (n x m)."""
    T = _gamma_table()
    lk = np.log(np.clip(k, _K[0], _K[-1]))
    j = np.clip(np.searchsorted(np.log(_K), lk), 1, len(_K) - 1)
    w = ((lk - np.log(_K[j - 1])) / (np.log(_K[j]) - np.log(_K[j - 1])))[:, None]
    rowtab = (1 - w) * T[j - 1] + w * T[j]                       # n x len(_V)
    V = np.clip(V, 0.0, _V[-1])
    i = np.clip(np.searchsorted(_V, V), 1, len(_V) - 1)
    r = np.arange(len(k))[:, None]
    f = (V - _V[i - 1]) / (_V[i] - _V[i - 1])
    return rowtab[r, i - 1] + f * (rowtab[r, i] - rowtab[r, i - 1])


class ZeroInflated:
    """P(y <= 0) = logistic(a0 + a1 log mu); positive part gamma or lognormal with
    log mean = b0 + b1 log mu and log shape (gamma) / log sigma (lognormal) = c0 + c1 log mu."""

    def __init__(self, family="gamma", maxfit=60000):
        self.family, self.maxfit = family, maxfit

    def fit(self, tr, mu, y):
        self.m = {}
        pos = tr["position"].to_numpy()
        rng = np.random.default_rng(0)
        for p in np.unique(pos):
            a = np.flatnonzero(pos == p)
            if len(a) > self.maxfit:
                a = rng.choice(a, self.maxfit, replace=False)
            lm, yy = np.log(mu[a]), y[a]
            z = (yy <= 0).astype(float)
            a0 = C.logistic_fit(lm[:, None], z, lam=0.0) if z.sum() > 5 else np.array([-9.0, 0.0])
            s = yy > 0
            lmp, yp = lm[s], yy[s]
            if self.family == "gamma":
                def nll(t):
                    m_, k = np.exp(t[0] + t[1] * lmp), np.exp(np.clip(t[2] + t[3] * lmp, -2.0, 4.3))
                    return -np.mean(k * np.log(k / m_) + (k - 1) * np.log(yp) - k * yp / m_ - C.lgam(k))
            else:
                def nll(t):
                    sg = np.exp(np.clip(t[2] + t[3] * lmp, -4, 2))
                    m_ = t[0] + t[1] * lmp - 0.5 * sg ** 2            # log-mean parametrised by the mean
                    return np.mean(np.log(sg) + 0.5 * ((np.log(yp) - m_) / sg) ** 2)
            t, _ = C.nelder_mead(nll, [0.0, 1.0, 0.0, 0.0], iters=600)
            self.m[p] = (a0, t)
        return self

    def predict(self, te, mu):
        Q = np.zeros((len(te), C.NQ))
        pos = te["position"].to_numpy()
        for p in np.unique(pos):
            a = pos == p
            a0, t = self.m[p]
            lm = np.log(mu[a])
            p0 = C.sigmoid(a0[0] + a0[1] * lm)[:, None]
            V = np.clip((C.L[None, :] - p0) / (1 - p0), 0.0, 1.0)
            if self.family == "gamma":
                m_, k = np.exp(t[0] + t[1] * lm), np.exp(np.clip(t[2] + t[3] * lm, -2.0, 4.3))
                q = gamma_ppf(k, V) * (m_ / k)[:, None]
            else:
                sg = np.exp(np.clip(t[2] + t[3] * lm, -4, 2))
                m_ = t[0] + t[1] * lm - 0.5 * sg ** 2
                q = np.exp(m_[:, None] + sg[:, None] * C.norm_ppf(np.clip(V, 1e-9, 1 - 1e-9)))
            Q[a] = np.where(C.L[None, :] <= p0, 0.0, q)
        return Q


class SkewNormal:
    """y = mu + d + w * z, w = exp(c0 + c1 log mu), z ~ skew-normal(shape alpha) (alpha fixed at 0 = normal)."""

    def __init__(self, skew=True):
        self.skew = skew

    def fit(self, tr, mu, y):
        self.m = {}
        pos = tr["position"].to_numpy()
        for p in np.unique(pos):
            a = pos == p
            lm, yy, mm = np.log(mu[a]), y[a], mu[a]

            def nll(t):
                w = np.exp(t[1] + t[2] * (lm - 5.4))
                z = (yy - mm - t[0] * 10) / w
                al = t[3] if self.skew else 0.0
                return np.mean(np.log(w) + 0.5 * z * z - np.log(np.maximum(C.norm_cdf(al * z), 1e-300)))
            t, _ = C.nelder_mead(nll, [0.0, np.log(yy.std()), 0.0, 0.0], iters=600)
            al = t[3] if self.skew else 0.0
            zg = np.linspace(-9, 9, 9001)
            pdf = np.exp(-0.5 * zg ** 2) * C.norm_cdf(al * zg)
            cdf = np.cumsum(pdf)
            cdf /= cdf[-1]
            self.m[p] = (t, np.interp(C.L, cdf, zg))
        return self

    def predict(self, te, mu):
        Q = np.zeros((len(te), C.NQ))
        pos = te["position"].to_numpy()
        for p in np.unique(pos):
            a = pos == p
            t, zq = self.m[p]
            w = np.exp(t[1] + t[2] * (np.log(mu[a]) - 5.4))
            Q[a] = (mu[a] + t[0] * 10)[:, None] + w[:, None] * zq[None, :]
        return Q
