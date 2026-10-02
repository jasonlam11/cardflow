"""Loads the trained model and turns features into a score, a band and SHAP contributions."""

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import xgboost as xgb

from .features import FEATURES, to_vector


@dataclass(frozen=True)
class Prediction:
    score: float
    band: str  # LOW, REVIEW or HIGH
    contributions: dict[str, float]  # per-feature SHAP values in log-odds


class FraudModel:
    def __init__(self, booster: xgb.Booster, metadata: dict):
        if metadata["features"] != list(FEATURES):
            # Guards against serving a model trained on a different feature list
            raise ValueError("model features don't match fraud.features.FEATURES")
        self.booster = booster
        self.metadata = metadata
        self.version: str = metadata["model_version"]
        self.review_threshold: float = metadata["thresholds"]["review"]
        self.high_threshold: float = metadata["thresholds"]["high"]

    @classmethod
    def load(cls, model_dir: Path) -> "FraudModel":
        booster = xgb.Booster()
        booster.load_model(model_dir / "model.json")  # XGBoost's JSON format: data only, no code execution
        booster.set_param({"nthread": 1})  # one request = one row; threads only add overhead
        metadata = json.loads((model_dir / "metadata.json").read_text())
        return cls(booster, metadata)

    def band(self, score: float) -> str:
        if score >= self.high_threshold:
            return "HIGH"
        if score >= self.review_threshold:
            return "REVIEW"
        return "LOW"

    def predict(self, features: dict[str, float]) -> Prediction:
        matrix = xgb.DMatrix(np.array([to_vector(features)], dtype=np.float32), feature_names=list(FEATURES))
        score = float(self.booster.predict(matrix)[0])
        # TreeSHAP built into XGBoost: exact SHAP values, last column is the bias term
        contribs = self.booster.predict(matrix, pred_contribs=True)[0]
        return Prediction(score=score, band=self.band(score),
                          contributions={name: float(contribs[i]) for i, name in enumerate(FEATURES)})
