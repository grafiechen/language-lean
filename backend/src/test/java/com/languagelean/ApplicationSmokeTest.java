package com.languagelean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.enabled=false",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__language_configuration.sql,classpath:db/migration/V2__user_accounts.sql"
})
class ApplicationSmokeTest {
    @Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test void languageConfigurationComesFromDatabase() throws Exception {
        var response = get("/api/v1/languages");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("LISTEN_RECALL"));
        assertTrue(response.body().contains("ja-JP"));
    }

    @Test void privateRoutesAreNotPublic() throws Exception {
        var response = get("/api/v1/learning-items");
        assertTrue(response.statusCode() == 401 || response.statusCode() == 403);
        assertEquals(200, get("/actuator/health").statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
