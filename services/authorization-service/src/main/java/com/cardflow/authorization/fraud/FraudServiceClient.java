package com.cardflow.authorization.fraud;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.cardflow.authorization.common.CorrelationIdFilter;
import com.cardflow.authorization.fraud.FraudAssessment.FraudReason;

/**
 * Plain HTTP call to fraud-service with strict timeouts. Throws on any problem;
 * {@link ResilientFraudScorer} decides what to do about it.
 */
@Component
public class FraudServiceClient {

    private final RestClient http;

    public FraudServiceClient(FraudProperties props) {
        // JDK HttpClient pinned to HTTP/1.1: on plain http:// its HTTP/2 default first attempts an
        // h2c upgrade, which fraud-service (uvicorn) doesn't speak
        var jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        var factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
        // Spring Framework's builder (Boot 4's auto-configured one lives in an extra module we don't need)
        this.http = RestClient.builder().baseUrl(props.url()).requestFactory(factory).build();
    }

    record ScoreResponse(String requestId, double score, FraudBand band, List<FraudReason> reasons,
            String modelVersion) {
    }

    public FraudAssessment score(ScoreRequest request) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        ScoreResponse response = http.post().uri("/score")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (correlationId != null) {
                        h.set(CorrelationIdFilter.HEADER, correlationId);
                    }
                })
                .body(request)
                .retrieve()
                .body(ScoreResponse.class);
        if (response == null || response.band() == null) {
            throw new IllegalStateException("Empty response from fraud-service");
        }
        return new FraudAssessment(response.score(), response.band(),
                response.reasons() == null ? List.of() : response.reasons(), ScoredBy.MODEL, response.modelVersion());
    }
}
