package com.cardflow.authorization.fraud;

import java.util.List;

/**
 * Fraud verdict for one charge. score and modelVersion are null when the
 * fallback rules (not the model) decided.
 */
public record FraudAssessment(Double score, FraudBand band, List<FraudReason> reasons, ScoredBy scoredBy,
        String modelVersion) {

    public record FraudReason(String code, String description, Double contribution) {
    }

    public static FraudAssessment notScored() {
        return new FraudAssessment(null, FraudBand.LOW, List.of(), ScoredBy.NOT_SCORED, null);
    }
}
