package com.lockstep.runner.redis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import io.lettuce.core.protocol.ProtocolKeyword;

public record RedisCommand(String name, List<String> args) {
    public RedisCommand {
        name = name == null ? "" : name.toUpperCase();
        args = List.copyOf(args);
    }

    public static RedisCommand parse(String commandLine) {
        List<String> tokens = tokenize(commandLine);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("redis command must not be empty");
        }
        return new RedisCommand(tokens.get(0), tokens.subList(1, tokens.size()));
    }

    public ProtocolKeyword keyword() {
        String upper = name;
        return new ProtocolKeyword() {
            @Override
            public byte[] getBytes() {
                return upper.getBytes(StandardCharsets.US_ASCII);
            }

            @Override
            public String name() {
                return upper;
            }
        };
    }

    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        if (line == null) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"' && i + 1 < line.length()) {
                    current.append(line.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                continue;
            }
            current.append(c);
            inToken = true;
        }
        if (quote != 0) {
            throw new IllegalArgumentException("unterminated quote in redis command: " + line);
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
