package com.cardflow.authorization.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.cardflow.authorization.authorization.AuthorizationDtos.AuthorizationRequest;

class RequestHasherTest {

    final UUID card = UUID.randomUUID();
    final AuthorizationRequest base = new AuthorizationRequest(card, "m1", "Coffee", "5814", 4250, "USD");

    @Test
    void sameRequestSameHash() {
        var copy = new AuthorizationRequest(card, "m1", "Coffee", "5814", 4250, "USD");
        assertThat(RequestHasher.hash(copy)).isEqualTo(RequestHasher.hash(base)).hasSize(64);
    }

    @Test
    void anyFieldChangeChangesHash() {
        String h = RequestHasher.hash(base);
        assertThat(RequestHasher.hash(new AuthorizationRequest(card, "m1", "Coffee", "5814", 4251, "USD"))).isNotEqualTo(h);
        assertThat(RequestHasher.hash(new AuthorizationRequest(card, "m2", "Coffee", "5814", 4250, "USD"))).isNotEqualTo(h);
        assertThat(RequestHasher.hash(new AuthorizationRequest(card, "m1", "Coffee", "5812", 4250, "USD"))).isNotEqualTo(h);
        assertThat(RequestHasher.hash(new AuthorizationRequest(UUID.randomUUID(), "m1", "Coffee", "5814", 4250, "USD")))
                .isNotEqualTo(h);
    }

    @Test
    void fieldBoundariesCantBeShifted() {
        // "ab"+"c" must not hash the same as "a"+"bc"
        var a = new AuthorizationRequest(card, "ab", "c", "5814", 1, "USD");
        var b = new AuthorizationRequest(card, "a", "bc", "5814", 1, "USD");
        assertThat(RequestHasher.hash(a)).isNotEqualTo(RequestHasher.hash(b));
    }
}
