"""
Trains the fraud model end to end:

  python -m training.train --data ../../simulator/data/transactions.csv.gz

1. Replay the labeled history in time order, computing features with the same
   fraud.features.compute_features() the API uses.
2. Split by TIME (train / validation / test), never randomly, so the model is
   never evaluated on data from before what it trained on.
3. Train XGBoost with early stopping on validation PR-AUC.
4. Pick the REVIEW and HIGH thresholds on validation only.
5. Report metrics on the untouched test period, plus the rule-based fallback
   on the same rows for comparison.
6. Write model/model.json, model/metadata.json, the SHAP summary plot and
   docs/model-card.md.
"""

import argparse
import csv
import gzip
import hashlib
import json
import math
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import xgboost as xgb
from sklearn.metrics import average_precision_score, precision_recall_curve, roc_auc_score

from fraud.features import FEATURES, Txn, compute_features, to_vector
from training.rules_baseline import rules_flag

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
EPOCH = datetime(2026, 1, 1, tzinfo=timezone.utc)

PARAMS = {
    "objective": "binary:logistic",
    "eval_metric": "aucpr",
    "max_depth": 5,
    "eta": 0.08,
    "subsample": 0.9,
    "colsample_bytree": 0.9,
    "min_child_weight": 3,
    "tree_method": "hist",
    "seed": 7,
    "nthread": 4,
}


def load_rows(path: str) -> list[dict]:
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", newline="") as f:
        return list(csv.DictReader(f))


def to_txn(r: dict) -> Txn:
    return Txn(ts=datetime.fromisoformat(r["ts"]), amount_minor=int(r["amount_minor"]), mcc=r["mcc"],
               merchant_id=r["merchant_id"], channel=r["channel"],
               lat=float(r["lat"]) if r["lat"] else None, lon=float(r["lon"]) if r["lon"] else None)


def build_features(rows: list[dict]):
    """Walks rows in time order; each card's history is only what came before it."""
    history: dict[str, list[Txn]] = defaultdict(list)
    X, y, day, pattern, amount, mcc = [], [], [], [], [], []
    for r in rows:
        txn = to_txn(r)
        h = history[r["card_id"]]
        X.append(to_vector(compute_features(h, txn)))
        h.append(txn)
        if len(h) > 400:  # keep memory bounded; features only use the last 100 within 30 days
            del h[:200]
        y.append(int(r["is_fraud"]))
        day.append((txn.ts - EPOCH).days)
        pattern.append(r["fraud_pattern"])
        amount.append(txn.amount_minor)
        mcc.append(txn.mcc)
    return (np.array(X, dtype=np.float32), np.array(y), np.array(day), np.array(pattern), np.array(amount),
            np.array(mcc))


def threshold_for_recall(y, scores, target: float) -> float:
    """Highest threshold whose recall is still >= target."""
    precision, recall, thresholds = precision_recall_curve(y, scores)
    ok = [t for p, r, t in zip(precision[:-1], recall[:-1], thresholds) if r >= target]
    return float(max(ok)) if ok else float(thresholds[0])


def threshold_for_precision(y, scores, target: float) -> float:
    """Lowest threshold whose precision is >= target."""
    precision, recall, thresholds = precision_recall_curve(y, scores)
    ok = [t for p, t in zip(precision[:-1], thresholds) if p >= target]
    return float(min(ok)) if ok else float(thresholds[-1])


def rates(y, flagged) -> dict:
    tp = int(((flagged == 1) & (y == 1)).sum())
    fp = int(((flagged == 1) & (y == 0)).sum())
    fn = int(((flagged == 0) & (y == 1)).sum())
    return {
        "precision": round(tp / (tp + fp), 4) if tp + fp else 0.0,
        "recall": round(tp / (tp + fn), 4) if tp + fn else 0.0,
        "flag_rate": round(float(flagged.mean()), 4),
        "true_positives": tp,
        "false_positives": fp,
        "false_negatives": fn,
    }


def main(argv=None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--data", default=str(REPO / "simulator/data/transactions.csv.gz"))
    p.add_argument("--train-days", type=int, default=63)
    p.add_argument("--val-days", type=int, default=14)
    p.add_argument("--review-recall", type=float, default=0.90)
    p.add_argument("--high-precision", type=float, default=0.90)
    p.add_argument("--out", default=str(ROOT / "model"))
    p.add_argument("--docs", default=str(REPO / "docs"))
    p.add_argument("--skip-docs", action="store_true", help="skip SHAP plot + model card (fast CI runs)")
    args = p.parse_args(argv)

    t0 = time.time()
    rows = load_rows(args.data)
    X, y, day, pattern, amount, mcc = build_features(rows)
    print(f"features: {X.shape[0]} rows x {X.shape[1]} in {time.time() - t0:.1f}s; fraud rate {y.mean():.4f}")

    train = day < args.train_days
    val = (day >= args.train_days) & (day < args.train_days + args.val_days)
    test = day >= args.train_days + args.val_days
    assert train.any() and val.any() and test.any(), "each split needs rows"
    assert day[train].max() < day[val].min() <= day[val].max() < day[test].min(), "splits must not overlap in time"

    pos, neg = y[train].sum(), (1 - y[train]).sum()
    params = {**PARAMS, "scale_pos_weight": float(neg / pos)}
    dtrain = xgb.DMatrix(X[train], label=y[train], feature_names=list(FEATURES))
    dval = xgb.DMatrix(X[val], label=y[val], feature_names=list(FEATURES))
    dtest = xgb.DMatrix(X[test], label=y[test], feature_names=list(FEATURES))
    booster = xgb.train(params, dtrain, num_boost_round=600, evals=[(dval, "val")],
                        early_stopping_rounds=40, verbose_eval=False)
    print(f"trained {booster.best_iteration + 1} trees")

    val_scores = booster.predict(dval, iteration_range=(0, booster.best_iteration + 1))
    review_t = threshold_for_recall(y[val], val_scores, args.review_recall)
    high_t = max(threshold_for_precision(y[val], val_scores, args.high_precision), review_t)

    test_scores = booster.predict(dtest, iteration_range=(0, booster.best_iteration + 1))
    yt = y[test]
    review_or_high = (test_scores >= review_t).astype(int)
    high = (test_scores >= high_t).astype(int)
    rules = np.array([rules_flag(int(a), m) for a, m in zip(amount[test], mcc[test])], dtype=int)

    by_pattern = {}
    for name in sorted(set(pattern[test]) - {""}):
        mask = pattern[test] == name
        by_pattern[name] = {"count": int(mask.sum()), "model_recall": round(float(review_or_high[mask].mean()), 4),
                            "rules_recall": round(float(rules[mask].mean()), 4)}

    metrics = {
        "test_rows": int(test.sum()),
        "test_fraud": int(yt.sum()),
        "pr_auc": round(float(average_precision_score(yt, test_scores)), 4),
        "roc_auc": round(float(roc_auc_score(yt, test_scores)), 4),
        "flagged_review_or_high": rates(yt, review_or_high),
        "auto_declined_high": rates(yt, high),
        "rules_fallback": rates(yt, rules),
        "recall_by_pattern": by_pattern,
        "baseline_pr_auc_if_random": round(float(yt.mean()), 4),
    }

    # Single-transaction latency: features + prediction + SHAP contributions, like one API call
    sample = [to_txn(r) for r in rows[-2_000:]]
    hist = sample[:100]
    timings = []
    for txn in sample[100:600]:
        s = time.perf_counter()
        vec = np.array([to_vector(compute_features(hist, txn))], dtype=np.float32)
        d = xgb.DMatrix(vec, feature_names=list(FEATURES))
        booster.predict(d, iteration_range=(0, booster.best_iteration + 1))
        booster.predict(d, pred_contribs=True, iteration_range=(0, booster.best_iteration + 1))
        timings.append((time.perf_counter() - s) * 1000)
    metrics["scoring_latency_ms"] = {"p50": round(float(np.percentile(timings, 50)), 2),
                                     "p95": round(float(np.percentile(timings, 95)), 2)}

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    # Keep only the trees early stopping chose, so serving can't accidentally use extra ones
    booster = booster[: booster.best_iteration + 1]
    model_path = out / "model.json"
    booster.save_model(model_path)
    digest = hashlib.sha256(model_path.read_bytes()).hexdigest()[:10]
    metadata = {
        "model_version": f"fraud-xgb-{digest}",
        "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "features": list(FEATURES),
        "thresholds": {"review": round(review_t, 6), "high": round(high_t, 6)},
        "threshold_policy": {"review": f"highest threshold with validation recall >= {args.review_recall}",
                             "high": f"lowest threshold with validation precision >= {args.high_precision}"},
        "split": {"train_days": f"0-{args.train_days - 1}",
                  "validation_days": f"{args.train_days}-{args.train_days + args.val_days - 1}",
                  "test_days": f"{args.train_days + args.val_days}+",
                  "train_rows": int(train.sum()), "validation_rows": int(val.sum())},
        "dataset": {"path": Path(args.data).name, "rows": int(len(y)), "fraud_rate": round(float(y.mean()), 4)},
        "params": {k: v for k, v in params.items() if k != "nthread"},
        "trees": booster.num_boosted_rounds(),
        "metrics": metrics,
    }
    (out / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps({"model_version": metadata["model_version"], "thresholds": metadata["thresholds"],
                      **{k: metrics[k] for k in ("pr_auc", "roc_auc", "flagged_review_or_high",
                                                  "auto_declined_high", "rules_fallback", "scoring_latency_ms")}},
                     indent=2))

    if not args.skip_docs:
        from training.model_card import render_model_card, shap_summary_plot
        image = Path(args.docs) / "images" / "fraud-shap-summary.png"
        shap_summary_plot(booster, X[test][:5_000], image)
        render_model_card(metadata, Path(args.docs) / "model-card.md", image)
        print(f"wrote {image} and model card")
    print(f"done in {time.time() - t0:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
