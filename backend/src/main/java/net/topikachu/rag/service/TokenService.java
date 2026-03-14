package net.topikachu.rag.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class TokenService {

    @Value("${security.jwt.secret}")
    private String secret;

    @Value("${security.jwt.expiration:3600000}")
    private long accessTokenValidityMillis;

    @Value("${security.jwt.refresh-expiration:86400000}")
    private long refreshTokenValidityMillis;

    public Map<String, String> generateTokens(String username, String role) {
        try {
            JWSSigner signer = new MACSigner(secret.getBytes());

            // 1. Access Token
            JWTClaimsSet accessClaims = new JWTClaimsSet.Builder()
                    .subject(username)
                    .issuer("rag-app")
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plus(accessTokenValidityMillis, ChronoUnit.MILLIS)))
                    .claim("roles", List.of(role))
                    .build();

            SignedJWT signedAccess = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), accessClaims);
            signedAccess.sign(signer);
            String accessToken = signedAccess.serialize();

            // 2. Refresh Token
            JWTClaimsSet refreshClaims = new JWTClaimsSet.Builder()
                    .subject(username)
                    .issuer("rag-app")
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plus(refreshTokenValidityMillis, ChronoUnit.MILLIS)))
                    .claim("type", "refresh")
                    .build();

            SignedJWT signedRefresh = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), refreshClaims);
            signedRefresh.sign(signer);
            String refreshToken = signedRefresh.serialize();

            return Map.of(
                    "access_token", accessToken,
                    "refresh_token", refreshToken);
        } catch (JOSEException e) {
            log.error("Error creating JWT token", e);
            throw new RuntimeException("Error creating JWT token", e);
        }
    }
}
