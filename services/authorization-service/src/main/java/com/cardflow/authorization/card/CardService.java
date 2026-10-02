package com.cardflow.authorization.card;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardflow.authorization.common.NotFoundException;

@Service
public class CardService {

    private final CardAccountRepository cards;

    public CardService(CardAccountRepository cards) {
        this.cards = cards;
    }

    @Transactional
    public CardAccount create(long creditLimitMinor, String currency) {
        return cards.save(new CardAccount(creditLimitMinor, currency));
    }

    @Transactional(readOnly = true)
    public CardAccount get(UUID id) {
        return cards.findById(id).orElseThrow(() -> new NotFoundException("Card " + id + " not found"));
    }

    /** Credit limit minus everything approved so far; derived from history, never stored. */
    @Transactional(readOnly = true)
    public long availableCreditMinor(CardAccount card) {
        return card.getCreditLimitMinor() - cards.sumApprovedMinor(card.getId());
    }

    @Transactional
    public CardAccount changeStatus(UUID id, CardStatus status) {
        CardAccount card = get(id);
        card.changeStatus(status);
        return card;
    }
}
