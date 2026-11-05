package com.dariodussin.whatsappautomationbackend.security;

import com.dariodussin.whatsappautomationbackend.config.WebhookRateLimiter;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionInstance;
import com.dariodussin.whatsappautomationbackend.service.EvolutionApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InstanceTokenValidatorTest {

    private static final long SECOND = 1_000_000_000L;
    private static final String INSTANCE = "guardiao";
    private static final String TOKEN = "TOKEN-1";

    private EvolutionApiService evolution;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        evolution = mock(EvolutionApiService.class);
        clock = new AtomicLong();
        when(evolution.fetchInstance(INSTANCE)).thenReturn(instance(TOKEN));
    }

    @Test
    void cachedTokenIsReusedWithinTtl() {
        InstanceTokenValidator validator = validator(600);

        assertTrue(validator.isValid(INSTANCE, TOKEN));
        clock.addAndGet(599 * SECOND);
        assertTrue(validator.isValid(INSTANCE, TOKEN));
        assertFalse(validator.isValid(INSTANCE, "wrong"));

        verify(evolution, times(1)).fetchInstance(INSTANCE);
    }

    @Test
    void tokenIsRefetchedAfterTtl() {
        InstanceTokenValidator validator = validator(600);

        assertTrue(validator.isValid(INSTANCE, TOKEN));
        clock.addAndGet(600 * SECOND);
        when(evolution.fetchInstance(INSTANCE)).thenReturn(instance("TOKEN-2"));

        assertFalse(validator.isValid(INSTANCE, TOKEN));
        assertTrue(validator.isValid(INSTANCE, "TOKEN-2"));
        verify(evolution, times(2)).fetchInstance(INSTANCE);
    }

    @Test
    void zeroTtlDisablesCache() {
        InstanceTokenValidator validator = validator(0);

        assertTrue(validator.isValid(INSTANCE, TOKEN));
        assertTrue(validator.isValid(INSTANCE, TOKEN));
        verify(evolution, times(2)).fetchInstance(INSTANCE);
    }

    @Test
    void unknownInstanceIsNotCached() {
        InstanceTokenValidator validator = validator(600);

        assertFalse(validator.isValid("missing", TOKEN));
        when(evolution.fetchInstance("missing")).thenReturn(instance(TOKEN));
        assertTrue(validator.isValid("missing", TOKEN));
    }

    @Test
    void evolutionFailureIsRejectedAndNotCached() {
        InstanceTokenValidator validator = validator(600);
        when(evolution.fetchInstance(INSTANCE)).thenThrow(new RuntimeException("down"));

        assertFalse(validator.isValid(INSTANCE, TOKEN));

        doReturn(instance(TOKEN)).when(evolution).fetchInstance(INSTANCE);
        assertTrue(validator.isValid(INSTANCE, TOKEN));
    }

    @Test
    void blankInputsAreRejectedWithoutCallingEvolution() {
        InstanceTokenValidator validator = validator(600);

        assertFalse(validator.isValid(" ", TOKEN));
        assertFalse(validator.isValid(INSTANCE, null));
        verify(evolution, never()).fetchInstance(any());
    }

    @Test
    void negativeTtlIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> validator(-1));
    }

    @Test
    void lookupsBeyondBurstAreRejectedWithoutCallingEvolution() {
        InstanceTokenValidator validator = new InstanceTokenValidator(evolution, 600,
                new WebhookRateLimiter(1, 2, clock::get), clock::get);

        assertFalse(validator.isValid("random-1", TOKEN));
        assertFalse(validator.isValid("random-2", TOKEN));
        assertFalse(validator.isValid(INSTANCE, TOKEN));

        verify(evolution, never()).fetchInstance(INSTANCE);
        verify(evolution, times(2)).fetchInstance(any());
    }

    @Test
    void cachedTokensAreNotThrottled() {
        InstanceTokenValidator validator = new InstanceTokenValidator(evolution, 600,
                new WebhookRateLimiter(1, 1, clock::get), clock::get);

        assertTrue(validator.isValid(INSTANCE, TOKEN));
        assertFalse(validator.isValid("random-1", TOKEN));
        assertTrue(validator.isValid(INSTANCE, TOKEN));

        verify(evolution, times(1)).fetchInstance(INSTANCE);
        verify(evolution, never()).fetchInstance("random-1");
    }

    @Test
    void throttledLookupsRecoverAfterRefill() {
        InstanceTokenValidator validator = new InstanceTokenValidator(evolution, 600,
                new WebhookRateLimiter(1, 1, clock::get), clock::get);

        assertFalse(validator.isValid("random-1", TOKEN));
        assertFalse(validator.isValid(INSTANCE, TOKEN));
        clock.addAndGet(SECOND);
        assertTrue(validator.isValid(INSTANCE, TOKEN));
    }

    private InstanceTokenValidator validator(long ttlSeconds) {
        return new InstanceTokenValidator(evolution, ttlSeconds,
                new WebhookRateLimiter(1000, 1000, clock::get), clock::get);
    }

    private static EvolutionInstance instance(String token) {
        return new EvolutionInstance("968f508e", INSTANCE, "open", null, token);
    }
}
