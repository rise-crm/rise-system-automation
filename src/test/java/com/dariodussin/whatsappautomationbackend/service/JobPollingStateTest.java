package com.dariodussin.whatsappautomationbackend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class JobPollingStateTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private JobPollingState state;

    @BeforeEach
    void setUp() {
        state = new JobPollingState(redis);
    }

    @Test
    void missingKeyIsEnabled() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(JobPollingState.KEY)).thenReturn(null);

        assertTrue(state.isEnabled());
    }

    @Test
    void falseValueIsDisabled() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(JobPollingState.KEY)).thenReturn("false");

        assertFalse(state.isEnabled());
    }

    @Test
    void redisFailureKeepsPollingEnabled() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(JobPollingState.KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(state.isEnabled());
    }

    @Test
    void toggleReturnsNewStateFromScript() {
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(0L, 1L);

        assertFalse(state.toggle());
        assertTrue(state.toggle());

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redis, times(2)).execute(script.capture(), eq(List.of(JobPollingState.KEY)));
        String source = script.getValue().getScriptAsString();
        assertTrue(source.contains("GET"));
        assertTrue(source.contains("SET"));
    }

    @Test
    void toggleFailurePropagates() {
        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new RedisConnectionFailureException("down"));

        assertThrows(RedisConnectionFailureException.class, state::toggle);
    }
}
