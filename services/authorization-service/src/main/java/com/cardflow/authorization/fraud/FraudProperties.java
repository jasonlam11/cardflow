package com.cardflow.authorization.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under cardflow.fraud.* */
@ConfigurationProperties("cardflow.fraud")
public record FraudProperties(String url, int connectTimeoutMs, int readTimeoutMs, int circuitWindowSize,
        int circuitMinimumCalls, float circuitFailureRatePercent, long circuitOpenSeconds,
        int circuitHalfOpenCalls) {
}
