package lk.gov.ciaboc.hrms.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Boots the full application against a real (embedded) PostgreSQL with Flyway migrations, demo data and demo users,
 * so tests exercise the same SQL, constraints and triggers as production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public abstract class IntegrationTestBase {
    private static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        Path files = Files.createTempDirectory("hrms-files");
        r.add("app.files.storage-path", files::toString);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;

    /** Performs password + OTP sign-in (OTP is echoed in the dev profile) and returns the bearer token. */
    protected String login(String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn();
        if (r.getResponse().getStatus() != 200) throw new AssertionError("Login failed: " + r.getResponse().getContentAsString());
        JsonNode ch = json.readTree(r.getResponse().getContentAsString());
        MvcResult v = mvc.perform(post("/api/auth/verify-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("challengeId", ch.get("challengeId").asText(),
                                "code", ch.get("devOtp").asText()))))
                .andReturn();
        if (v.getResponse().getStatus() != 200) throw new AssertionError("OTP failed: " + v.getResponse().getContentAsString());
        return "Bearer " + json.readTree(v.getResponse().getContentAsString()).get("accessToken").asText();
    }

    protected JsonNode getJson(String token, String url) throws Exception {
        MvcResult r = mvc.perform(get(url).header("Authorization", token)).andReturn();
        if (r.getResponse().getStatus() != 200)
            throw new AssertionError("GET " + url + " -> " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        return json.readTree(r.getResponse().getContentAsString());
    }

    protected MvcResult postJson(String token, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    protected MvcResult putJson(String token, String url, Object body) throws Exception {
        return mvc.perform(put(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    protected JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    protected static void expectStatus(MvcResult r, int status) throws Exception {
        if (r.getResponse().getStatus() != status)
            throw new AssertionError("Expected " + status + " but got " + r.getResponse().getStatus() + ": "
                    + r.getResponse().getContentAsString());
    }
}
