package com.cardflow.authorization.card;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cardflow.authorization.card.CardDtos.CardResponse;
import com.cardflow.authorization.card.CardDtos.ChangeStatusRequest;
import com.cardflow.authorization.card.CardDtos.CreateCardRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/cards")
public class CardController {

    private final CardService cardService;

    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    @PostMapping
    public ResponseEntity<CardResponse> create(@Valid @RequestBody CreateCardRequest request) {
        CardAccount card = cardService.create(request.creditLimitMinor(), request.currency());
        return ResponseEntity.created(URI.create("/cards/" + card.getId()))
                .body(CardResponse.from(card, card.getCreditLimitMinor()));
    }

    @GetMapping("/{id}")
    public CardResponse get(@PathVariable UUID id) {
        CardAccount card = cardService.get(id);
        return CardResponse.from(card, cardService.availableCreditMinor(card));
    }

    @PutMapping("/{id}/status")
    public CardResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        CardAccount card = cardService.changeStatus(id, request.status());
        return CardResponse.from(card, cardService.availableCreditMinor(card));
    }
}
