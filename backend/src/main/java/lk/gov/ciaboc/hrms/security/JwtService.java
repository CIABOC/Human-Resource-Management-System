package lk.gov.ciaboc.hrms.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lk.gov.ciaboc.hrms.config.AppProperties;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {
    private final SecretKey key;
    private final String issuer;
    private final int accessMinutes;

    public JwtService(AppProperties props) {
        byte[] bytes = Decoders.BASE64.decode(props.security().jwt().secret());
        if (bytes.length < 32) throw new IllegalStateException("app.security.jwt.secret must be at least 256 bits");
        this.key = Keys.hmacShaKeyFor(bytes);
        this.issuer = props.security().jwt().issuer();
        this.accessMinutes = props.security().jwt().accessTokenMinutes();
    }

    public String issueAccessToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public int accessTokenSeconds() {
        return accessMinutes * 60;
    }

    /** Returns the username if the token is authentic, unexpired and issued by this service. */
    public Optional<String> validate(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(issuer).build().parseSignedClaims(token).getPayload();
            return Optional.ofNullable(c.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
