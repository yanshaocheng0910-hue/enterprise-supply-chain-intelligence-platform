package com.scic.platform.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scic.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JwtService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long expirationSeconds;

    public JwtService(ObjectMapper objectMapper,
                      @Value("${scic.jwt.secret}") String secret,
                      @Value("${scic.jwt.expiration-seconds}") long expirationSeconds) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expirationSeconds = expirationSeconds;
    }

    public String issue(AuthUser user) {
        try {
            String header = encode(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", user.username());
            payload.put("uid", user.id());
            payload.put("name", user.displayName());
            payload.put("role", user.role());
            payload.put("supplierId", user.supplierId());
            payload.put("iat", Instant.now().getEpochSecond());
            payload.put("exp", Instant.now().plusSeconds(expirationSeconds).getEpochSecond());
            String body = encode(objectMapper.writeValueAsBytes(payload));
            return header + "." + body + "." + encode(sign(header + "." + body));
        } catch (Exception ex) {
            throw new IllegalStateException("无法签发令牌", ex);
        }
    }

    public AuthUser verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) throw invalid();
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = DECODER.decode(parts[2]);
            if (!java.security.MessageDigest.isEqual(expected, actual)) throw invalid();
            Map<String, Object> payload = objectMapper.readValue(DECODER.decode(parts[1]), new TypeReference<>() {});
            long exp = ((Number) payload.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                throw new BusinessException("TOKEN_EXPIRED", "登录已过期，请重新登录", HttpStatus.UNAUTHORIZED);
            }
            Number supplierId = (Number) payload.get("supplierId");
            return new AuthUser(
                    ((Number) payload.get("uid")).longValue(),
                    (String) payload.get("sub"),
                    (String) payload.get("name"),
                    (String) payload.get("role"),
                    supplierId == null ? null : supplierId.longValue());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    private byte[] sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] value) { return ENCODER.encodeToString(value); }

    private static BusinessException invalid() {
        return new BusinessException("INVALID_TOKEN", "登录凭证无效", HttpStatus.UNAUTHORIZED);
    }
}

