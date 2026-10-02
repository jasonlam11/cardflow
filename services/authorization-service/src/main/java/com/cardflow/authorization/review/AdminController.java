package com.cardflow.authorization.review;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cardflow.authorization.authorization.AuthorizationStatus;
import com.cardflow.authorization.common.BadRequestException;
import com.cardflow.authorization.common.NotFoundException;
import com.cardflow.authorization.common.PageResponse;
import com.cardflow.authorization.fraud.FraudBand;
import com.cardflow.authorization.review.AdminQueries.AuthorizationRow;
import com.cardflow.authorization.review.AdminQueries.DecisionRow;
import com.cardflow.authorization.review.AdminQueries.Stats;
import com.cardflow.authorization.review.ReviewDecision.Outcome;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Dashboard-facing endpoints. All require X-Admin-Api-Key (see AdminApiKeyFilter). */
@RestController
public class AdminController {

    private final AdminQueries queries;
    private final ReviewService reviews;

    public AdminController(AdminQueries queries, ReviewService reviews) {
        this.queries = queries;
        this.reviews = reviews;
    }

    @GetMapping("/authorizations")
    public PageResponse<AuthorizationRow> list(@RequestParam(required = false) AuthorizationStatus status,
            @RequestParam(required = false) FraudBand band, @RequestParam(required = false) UUID cardId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return queries.list(status == null ? null : status.name(), band == null ? null : band.name(), cardId, page,
                size);
    }

    @GetMapping("/reviews")
    public PageResponse<AuthorizationRow> queue(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return queries.pendingReviews(page, size);
    }

    public record ReviewDetail(AuthorizationRow authorization, List<AuthorizationRow> recentCardActivity) {
    }

    @GetMapping("/reviews/{id}")
    public ReviewDetail detail(@PathVariable UUID id) {
        AuthorizationRow auth = queries.one(id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Authorization " + id + " not found"));
        List<AuthorizationRow> recent = auth.cardId() == null ? List.of() : queries.recentForCard(auth.cardId(), id, 10);
        return new ReviewDetail(auth, recent);
    }

    @GetMapping("/reviews/decisions")
    public PageResponse<DecisionRow> decisions(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return queries.decisions(page, size);
    }

    public record ReviewRequest(
            @NotNull Outcome decision,
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[\\p{L}0-9 .'_-]+$", message = "letters, digits, spaces and . ' _ - only") String analyst,
            @Size(max = 1000) String note) {
    }

    public record ReviewResponse(UUID decisionId, UUID authorizationId, Outcome decision, String analyst,
            AuthorizationStatus newStatus, java.time.Instant decidedAt) {
    }

    @PostMapping("/authorizations/{id}/review")
    public ReviewResponse decide(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        if (request.decision() == Outcome.REJECT && (request.note() == null || request.note().isBlank())) {
            throw new BadRequestException("A note is required when rejecting");
        }
        ReviewDecision d = reviews.decide(id, request.decision(), request.analyst().strip(),
                request.note() == null ? null : request.note().strip());
        return new ReviewResponse(d.getId(), d.getAuthorizationId(), d.getDecision(), d.getAnalyst(), d.getNewStatus(),
                d.getDecidedAt());
    }

    @GetMapping("/stats")
    public Stats stats() {
        return queries.stats();
    }
}
