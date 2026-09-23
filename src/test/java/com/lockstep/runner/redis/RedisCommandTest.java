package com.lockstep.runner.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class RedisCommandTest {
    @Test
    void splitsOnWhitespaceLikeTheReference() {
        RedisCommand command = RedisCommand.parse("SET sess:loadtest ok");
        assertThat(command.name()).isEqualTo("SET");
        assertThat(command.args()).containsExactly("sess:loadtest", "ok");
    }

    @Test
    void namesAreUppercasedSoCaseInConfigsDoesNotMatter() {
        assertThat(RedisCommand.parse("ping").name()).isEqualTo("PING");
        assertThat(RedisCommand.parse("  Get  key ").name()).isEqualTo("GET");
        assertThat(RedisCommand.parse("  Get  key ").args()).containsExactly("key");
    }

    @Test
    void quotedArgumentsStayWhole() {
        assertThat(RedisCommand.parse("SET greeting \"hello world\"").args())
                .containsExactly("greeting", "hello world");
        assertThat(RedisCommand.parse("SET greeting 'hello world'").args())
                .containsExactly("greeting", "hello world");
        assertThat(RedisCommand.parse("SET j \"{\\\"a\\\": 1}\"").args())
                .containsExactly("j", "{\"a\": 1}");
    }

    @Test
    void emptyArgumentsArePreservedWhenQuoted() {
        assertThat(RedisCommand.parse("SET k \"\"").args()).containsExactly("k", "");
    }

    @Test
    void malformedCommandsFailLoudly() {
        assertThatThrownBy(() -> RedisCommand.parse("SET k \"unterminated"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unterminated quote");
        assertThatThrownBy(() -> RedisCommand.parse("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void keywordCarriesTheUppercasedNameOnTheWire() {
        assertThat(new String(RedisCommand.parse("get k").keyword().getBytes())).isEqualTo("GET");
        assertThat(RedisCommand.parse("get k").keyword().name()).isEqualTo("GET");
    }
}
