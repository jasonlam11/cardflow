package com.cardflow.authorization.authorization;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardflow.authorization.authorization.AuthorizationDtos.AuthorizationRequest;
import com.cardflow.authorization.authorization.AuthorizationDtos.AuthorizationResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/authorizations")
public class AuthorizationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final AuthorizationService authorizationService;

    public AuthorizationController(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /**
     * 201 approved, 202 held for human review, 200 declined (the request was
     * valid; the answer is "no"). A retry with the same key gets the original
     * status and body back.
     */
    @PostMapping
    public ResponseEntity<AuthorizationResponse> authorize(
            @RequestHeader(IDEMPOTENCY_KEY)
            @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "must be 1-64 characters: letters, digits, - or _")
            String idempotencyKey,
            @Valid @RequestBody AuthorizationRequest request) {
        var result = authorizationService.authorize(idempotencyKey, request);
        Authorization auth = result.authorization();
        HttpStatus status = switch (auth.getStatus()) {
            case APPROVED -> HttpStatus.CREATED;
            case PENDING_REVIEW -> HttpStatus.ACCEPTED;
            case DECLINED -> HttpStatus.OK;
        };
        return ResponseEntity.status(status)
                .location(URI.create("/authorizations/" + auth.getId()))
                .header(REPLAYED, Boolean.toString(result.replayed()))
                .body(AuthorizationResponse.from(auth));
    }

    @GetMapping("/{id}")
    public AuthorizationResponse get(@PathVariable UUID id) {
        return AuthorizationResponse.from(authorizationService.get(id));
    }
}
