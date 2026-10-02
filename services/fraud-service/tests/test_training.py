"""
Model quality gate. Needs a generated dataset:
  FRAUD_TRAINING_DATA=path/to/transactions.csv.gz pytest tests/test_training.py
Skipped when the variable isn't set (CI sets it).
"""

import json
import os

import numpy as np
import pytest

from training.train import main, threshold_for_precision, threshold_for_recall

DATA = os.environ.get("FRAUD_TRAINING_DATA")


def test_threshold_helpers():
    y = np.array([0, 0, 0, 1, 1])
    scores = np.array([0.1, 0.2, 0.7, 0.6, 0.9])
    assert threshold_for_recall(y, scores, 1.0) == pytest.approx(0.6)
    assert threshold_for_precision(y, scores, 1.0) == pytest.approx(0.9)


@pytest.mark.skipif(not DATA, reason="set FRAUD_TRAINING_DATA to run the quality gate")
def test_trained_model_meets_quality_gate(tmp_path):
    assert main(["--data", DATA, "--out", str(tmp_path), "--skip-docs"]) == 0
    meta = json.loads((tmp_path / "metadata.json").read_text())
    m = meta["metrics"]
    assert m["pr_auc"] >= 0.75, m["pr_auc"]
    assert m["flagged_review_or_high"]["recall"] >= 0.75
    assert m["auto_declined_high"]["precision"] >= 0.70
    # The model must beat the rule-based fallback it replaces
    assert m["flagged_review_or_high"]["recall"] > m["rules_fallback"]["recall"]
    assert meta["thresholds"]["high"] >= meta["thresholds"]["review"]
    assert (tmp_path / "model.json").exists()
