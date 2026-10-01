package com.cardflow.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cardflow.ledger.common.NotFoundException;

/** Fast unit test: the repository is a Mockito fake, so no database or Spring is involved. */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    AccountRepository repository;

    @InjectMocks
    AccountService service;

    @Test
    void createAssignsIdAndSaves() {
        when(repository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account account = service.create("Coffee Shop payable", AccountType.LIABILITY, "USD");

        assertThat(account.getId()).isNotNull();
        assertThat(account.getType()).isEqualTo(AccountType.LIABILITY);
        assertThat(account.getCreatedAt()).isNotNull();
    }

    @Test
    void getThrowsNotFoundForUnknownId() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
