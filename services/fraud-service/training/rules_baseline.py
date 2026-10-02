"""
Python mirror of authorization-service's RuleBasedFraudScorer (the fallback used
when fraud-service is unavailable). Used only to measure the fallback on the
same test set as the model. Keep the two in sync.

The fallback is deliberately conservative: it never hard-declines, it only
routes suspicious charges to human review.
"""

from fraud.features import HIGH_RISK_MCCS

REVIEW_AMOUNT_MINOR = 200_000          # $2,000 anywhere
HIGH_RISK_REVIEW_AMOUNT_MINOR = 50_000  # $500 in a high-risk category


def rules_flag(amount_minor: int, mcc: str) -> bool:
    if amount_minor >= REVIEW_AMOUNT_MINOR:
        return True
    return mcc in HIGH_RISK_MCCS and amount_minor >= HIGH_RISK_REVIEW_AMOUNT_MINOR
