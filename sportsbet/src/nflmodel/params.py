"""The model's tunable settings, kept in one JSON file so a change is a small, reviewable diff.

`params.json` holds four groups: `margin` (ratings and blend of the margin model), `qb_form`
(quarterback form), `totals` (rating systems and ridge of the totals model) and `card` (how much of
the model-minus-line gap is kept when estimating a pick's chance).
"""
from __future__ import annotations

import copy
import hashlib
import json
import os
from importlib import resources
from pathlib import Path


def load(path: str | os.PathLike | None = None) -> dict:
    """The packaged settings, or those in `path` or the NFLMODEL_PARAMS environment variable."""
    path = path or os.environ.get("NFLMODEL_PARAMS")
    text = Path(path).read_text() if path else resources.files("nflmodel").joinpath("params.json").read_text()
    return json.loads(text)


def override(base: dict, changes: dict) -> dict:
    """A copy of `base` with `changes` applied; keys are dotted paths such as "margin.elo_k"."""
    out = copy.deepcopy(base)
    for dotted, value in changes.items():
        node = out
        *parents, leaf = dotted.split(".")
        for key in parents:
            node = node[key]
        if leaf not in node:
            raise KeyError(f"unknown setting: {dotted}")
        node[leaf] = value
    return out


def dumps(params: dict) -> str:
    return json.dumps(params, indent=2, sort_keys=True) + "\n"


def digest(params: dict) -> str:
    """Short fingerprint of a settings dict, recorded with every frozen pick."""
    return hashlib.sha256(json.dumps(params, sort_keys=True).encode()).hexdigest()[:12]


DEFAULT = load()
