"""Shared printing for the experiment scripts: one line per variant, dCRPS% / dMAE% (seasons better)."""
import lib
from nflmodel import props as P

ALL = [p.key for p in P.PROPS]


class Tester:
    def __init__(self, panel, props=None, **kw):
        self.panel, self.props, self.kw = panel, props or ALL, kw
        self.base = lib.evaluate(panel, props=self.props, **kw)
        print(f"{'dCRPS% / dMAE% (CRPS seasons better)':<40}" + " ".join(f"{p:>20}" for p in self.props))

    def run(self, label, extra=None, drop=None, panel=None, base=None, mask=None, **kw):
        res = lib.evaluate(self.panel if panel is None else panel, lib.with_feats(extra or {}, drop),
                           props=self.props, **{**self.kw, **kw})
        c = lib.compare(self.base if base is None else base, res, quiet=True, mask=mask)
        print(f"{label:<40}" + " ".join(
            f"{c.loc[p,'dCRPS%']:+6.2f}/{c.loc[p,'dMAE%']:+6.2f} ({c.loc[p,'crps_seas']:>5})" if p in c.index else " " * 20
            for p in self.props), flush=True)
        return res
