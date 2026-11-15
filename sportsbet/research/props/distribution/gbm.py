"""A small histogram gradient-boosting regressor in numpy (squared loss), optionally boosting the ridge residual."""
import numpy as np
import common as C


class GBM:
    def __init__(self, n_trees=150, lr=0.08, depth=3, min_leaf=150, nbins=32, on_ridge=True, l2=20.0):
        self.n_trees, self.lr, self.depth, self.min_leaf, self.nbins = n_trees, lr, depth, min_leaf, nbins
        self.on_ridge, self.l2 = on_ridge, l2

    def _bin(self, x):
        return np.column_stack([np.searchsorted(self.cuts[j], x[:, j]) for j in range(x.shape[1])]).astype(np.int64)

    def fit(self, x, y, floor):
        self.floor = floor
        self.base = C.Ridge().fit(x, y, -1e9) if self.on_ridge else None
        f = self.base.predict(x) if self.on_ridge else np.full(len(y), y.mean())
        self.c = y.mean()
        self.cuts = [np.unique(np.quantile(x[:, j], np.linspace(0, 1, self.nbins + 1)[1:-1])) for j in range(x.shape[1])]
        B = self._bin(x)
        n, p = B.shape
        nb = self.nbins
        self.trees = []
        for _ in range(self.n_trees):
            g = y - f
            node = np.zeros(n, dtype=np.int64)
            splits = []                                     # per level: (feature, cut) per node
            for lev in range(self.depth):
                nn = 2 ** lev
                best = np.full(nn, 0.0)
                bf, bc = np.full(nn, -1), np.zeros(nn, dtype=np.int64)
                for j in range(p):
                    key = node * nb + B[:, j]
                    sg = np.bincount(key, weights=g, minlength=nn * nb).reshape(nn, nb)
                    sc = np.bincount(key, minlength=nn * nb).reshape(nn, nb)
                    cg, cc = np.cumsum(sg, 1), np.cumsum(sc, 1)
                    tg, tc = cg[:, -1:], cc[:, -1:]
                    gain = cg ** 2 / (cc + self.l2) + (tg - cg) ** 2 / (tc - cc + self.l2) - tg ** 2 / (tc + self.l2)
                    gain[(cc < self.min_leaf) | (tc - cc < self.min_leaf)] = 0.0
                    k = gain.argmax(1)
                    gk = gain[np.arange(nn), k]
                    up = gk > best
                    best[up], bf[up], bc[up] = gk[up], j, k[up]
                splits.append((bf.copy(), bc.copy()))
                fj = np.where(bf[node] >= 0, bf[node], 0)
                right = (B[np.arange(n), fj] > bc[node]) & (bf[node] >= 0)
                node = node * 2 + right
            nl = 2 ** self.depth
            leaf = self.lr * np.bincount(node, weights=g, minlength=nl) / (np.bincount(node, minlength=nl) + self.l2)
            f = f + leaf[node]
            self.trees.append((splits, leaf))
        return self

    def predict(self, x):
        f = self.base.predict(x) if self.on_ridge else np.full(len(x), self.c)
        B = self._bin(x)
        r = np.arange(len(x))
        for splits, leaf in self.trees:
            node = np.zeros(len(x), dtype=np.int64)
            for bf, bc in splits:
                fj = np.where(bf[node] >= 0, bf[node], 0)
                node = node * 2 + ((B[r, fj] > bc[node]) & (bf[node] >= 0))
            f = f + leaf[node]
        return np.maximum(f, self.floor)
