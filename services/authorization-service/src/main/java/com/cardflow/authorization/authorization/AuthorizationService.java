package com.cardflow.authorization.authorization;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardflow.authorization.authorization.AuthorizationDtos.AuthorizationRequest;
import com.cardflow.authorization.common.NotFoundException;

/**
 * Idempotency layer around {@link AuthorizationProcessor}.
 *
 * <ol>
 *   <li>Key seen before with the same request: return the original result (replay).</li>
 *   <li>Key seen before with a different request: reject (422).</li>
 *   <li>New key: process it. If an identical request raced us and committed
 *       first, the unique constraint fails our insert; we then return theirs.</li>
 * </ol>
 * Not transactional itself, so a failed attempt's transaction is fully rolled
 * back before we look up the winner.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final AuthorizationRepository authorizations;
    private final AuthorizationProcessor processor;

    public AuthorizationService(AuthorizationRepository authorizations, AuthorizationProcessor processor) {
        this.authorizations = authorizations;
        this.processor = processor;
    }

    public record Result(Authorization authorization, boolean replayed) {
    }

    public Result authorize(String idempotencyKey, AuthorizationRequest request) {
        String hash = RequestHasher.hash(request);

        Optional<Result> previous = replay(idempotencyKey, hash);
        if (previous.isPresent()) {
            return previous.get();
        }

        try {
            Authorization auth = processor.process(idempotencyKey, hash, request);
            log.info("Authorization {} {} {} {}", auth.getId(), auth.getStatus(),
                    auth.getDeclineReason() == null ? "" : auth.getDeclineReason(), auth.getAmountMinor());
            return new Result(auth, false);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent request using the same key
            return replay(idempotencyKey, hash).orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public Authorization get(UUID id) {
        return authorizations.findById(id)
                .orElseThrow(() -> new NotFoundException("Authorization " + id + " not found"));
    }

    private Optional<Result> replay(String idempotencyKey, String hash) {
        return authorizations.findByIdempotencyKey(idempotencyKey).map(existing -> {
            if (!existing.getRequestHash().equals(hash)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            return new Result(existing, true);
        });
    }
}
