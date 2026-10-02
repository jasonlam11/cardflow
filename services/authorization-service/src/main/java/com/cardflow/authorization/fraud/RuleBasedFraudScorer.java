package com.cardflow.authorization.fraud;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.cardflow.authorization.fraud.FraudAssessment.FraudReason;

/**
 * Fallback when fraud-service can't answer. It only knows this request (no
 * history), so it is deliberately conservative: it never declines, it only
 * routes large or high-risk-category charges to human review.
 *
 * Mirrored in fraud-service/training/rules_baseline.py, which measures these
 * rules on the model's test set. Keep the two in sync.
 */
@Component
public class RuleBasedFraudScorer {

    static final long REVIEW_AMOUNT_MINOR = 200_000;            // $2,000 anywhere
    static final long HIGH_RISK_REVIEW_AMOUNT_MINOR = 50_000;   // $500 in a high-risk category
    static final Set<String> HIGH_RISK_MCCS = Set.of("5944", "5999", "7995", "4829", "5732");

    public FraudAssessment score(long amountMinor, String mcc) {
        List<FraudReason> reasons = new ArrayList<>();
        if (amountMinor >= REVIEW_AMOUNT_MINOR) {
            reasons.add(new FraudReason("LARGE_AMOUNT", "Large transaction amount", null));
        }
        if (HIGH_RISK_MCCS.contains(mcc) && amountMinor >= HIGH_RISK_REVIEW_AMOUNT_MINOR) {
            reasons.add(new FraudReason("HIGH_RISK_MERCHANT_CATEGORY",
                    "Merchant category frequently targeted by fraud", null));
        }
        FraudBand band = reasons.isEmpty() ? FraudBand.LOW : FraudBand.REVIEW;
        return new FraudAssessment(null, band, List.copyOf(reasons), ScoredBy.RULES_FALLBACK, null);
    }
}
