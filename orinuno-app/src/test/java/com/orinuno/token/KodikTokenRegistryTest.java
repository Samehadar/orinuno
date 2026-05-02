package com.orinuno.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orinuno.configuration.OrinunoProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("KodikTokenRegistry — unit tests")
class KodikTokenRegistryTest {

    @TempDir Path tempDir;

    private OrinunoProperties properties;
    private ObjectProvider<KodikTokenAutoDiscovery> noDiscovery;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new OrinunoProperties();
        properties.getKodik().setTokenFile(tempDir.resolve("kodik_tokens.json").toString());
        properties.getKodik().setAutoDiscoveryEnabled(false);
        properties.getKodik().setBootstrapFromEnv(false);
        noDiscovery = (ObjectProvider<KodikTokenAutoDiscovery>) mock(ObjectProvider.class);
        when(noDiscovery.getIfAvailable()).thenReturn(null);
    }

    @Test
    @DisplayName("empty registry throws NoWorkingTokenException")
    void emptyRegistryThrows() {
        KodikTokenRegistry registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();

        assertThatThrownBy(() -> registry.currentToken(KodikFunction.BASE_SEARCH))
                .isInstanceOf(KodikTokenException.NoWorkingTokenException.class);
    }

    @Test
    @DisplayName("bootstraps from KODIK_TOKEN env when file is missing")
    void bootstrapsFromEnvProperty() {
        properties.getKodik().setBootstrapFromEnv(true);
        properties.getKodik().setToken("env-seeded-token");
        KodikTokenRegistry registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();

        assertThat(registry.currentToken(KodikFunction.BASE_SEARCH)).isEqualTo("env-seeded-token");
        assertThat(registry.countFor(KodikTokenTier.STABLE)).isEqualTo(1);
        assertThat(Files.exists(Path.of(properties.getKodik().getTokenFile()))).isTrue();
    }

    @Test
    @DisplayName("currentToken walks STABLE → UNSTABLE → LEGACY")
    void preferenceOrder() throws IOException {
        writeFile(
                "{\"stable\":[{\"value\":\"t-stable\","
                        + "\"functions_availability\":{\"base_search\":true,\"get_link\":false}}],"
                        + "\"unstable\":[{\"value\":\"t-unstable\","
                        + "\"functions_availability\":{\"base_search\":false,\"get_link\":false}}],"
                        + "\"legacy\":[{\"value\":\"t-legacy\","
                        + "\"functions_availability\":{\"get_link\":true}}],"
                        + "\"dead\":[]}");
        KodikTokenRegistry registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();

        assertThat(registry.currentToken(KodikFunction.BASE_SEARCH)).isEqualTo("t-stable");
        assertThat(registry.currentToken(KodikFunction.GET_LINK)).isEqualTo("t-legacy");
    }

    @Test
    @DisplayName("fresh tokens with no availability flags are tried optimistically")
    void freshTokensAreOptimistic() throws IOException {
        writeFile(
                "{\"stable\":[{\"value\":\"fresh\",\"functions_availability\":{}}],"
                        + "\"unstable\":[],\"legacy\":[],\"dead\":[]}");
        KodikTokenRegistry registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();

        for (KodikFunction fn : KodikFunction.values()) {
            assertThat(registry.currentToken(fn)).isEqualTo("fresh");
        }
    }

    @Test
    @DisplayName("markInvalid demotes token to DEAD when every function dies")
    void markInvalidDemotes() throws IOException {
        writeFile(
                "{\"stable\":[{\"value\":\"t-1\","
                        + "\"functions_availability\":{"
                        + "\"base_search\":true,\"base_search_by_id\":true,"
                        + "\"get_list\":true,\"search\":true,\"search_by_id\":true,"
                        + "\"get_info\":true,\"get_link\":true,"
                        + "\"get_m3u8_playlist_link\":true}}],"
                        + "\"unstable\":[],\"legacy\":[],\"dead\":[]}");
        KodikTokenRegistry registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();

        for (KodikFunction fn : KodikFunction.values()) {
            registry.markInvalid("t-1", fn);
        }

        assertThat(registry.countFor(KodikTokenTier.STABLE)).isZero();
        assertThat(registry.countFor(KodikTokenTier.DEAD)).isEqualTo(1);
        assertThatThrownBy(() -> registry.currentToken(KodikFunction.BASE_SEARCH))
                .isInstanceOf(KodikTokenException.NoWorkingTokenException.class);
    }

    @Test
    @DisplayName("markInvalid for a single function skips to the next token in the tier")
    void markInvalidFailsOverWithinTier() throws IOException {
        writeFile(
                "{\"stable\":[{\"value\":\"t-1\","
                        + "\"functions_availability\":{\"base_search\":true,\"get_list\":true}},"
                        + "{\"value\":\"t-2\","
                        + "\"functions_availability\":{\"base_search\":true,\"get_list\":true}}],"
                        + "\"unstable\":[],\"legacy\":[],\"dead\":[]}");
        KodikTokenRegistry registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();

        assertThat(registry.currentToken(KodikFunction.BASE_SEARCH)).isEqualTo("t-1");
        registry.markInvalid("t-1", KodikFunction.BASE_SEARCH);
        assertThat(registry.currentToken(KodikFunction.BASE_SEARCH)).isEqualTo("t-2");
        assertThat(registry.currentToken(KodikFunction.GET_LIST)).isEqualTo("t-1");
    }

    @Test
    @DisplayName("legacy tier without explicit flags only serves get_info / get_link / m3u8")
    void legacyDefaultScope() throws IOException {
        writeFile(
                "{\"stable\":[],\"unstable\":[],"
                        + "\"legacy\":[{\"value\":\"t-legacy\","
                        + "\"functions_availability\":{}}],"
                        + "\"dead\":[]}");
        KodikTokenRegistry registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();

        assertThat(registry.currentToken(KodikFunction.GET_LINK)).isEqualTo("t-legacy");
        assertThat(registry.currentToken(KodikFunction.GET_INFO)).isEqualTo("t-legacy");
        assertThatThrownBy(() -> registry.currentToken(KodikFunction.BASE_SEARCH))
                .isInstanceOf(KodikTokenException.NoWorkingTokenException.class);
    }

    @Test
    @DisplayName("writes are atomic and round-trip through Jackson")
    void atomicWrite() throws IOException {
        KodikTokenRegistry registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();

        registry.register(
                KodikTokenEntry.builder()
                        .value("v-1")
                        .lastChecked(Instant.parse("2026-01-01T00:00:00Z"))
                        .note("unit test")
                        .build(),
                KodikTokenTier.STABLE);

        Path file = Path.of(properties.getKodik().getTokenFile());
        assertThat(Files.exists(file)).isTrue();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ObjectNode tree = (ObjectNode) mapper.readTree(file.toFile());
        assertThat(tree.get("stable").get(0).get("value").asText()).isEqualTo("v-1");
        assertThat(tree.get("stable").get(0).get("note").asText()).isEqualTo("unit test");
        assertThat(tree.get("stable").get(0).get("last_checked").asText())
                .isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("mask hides the raw value")
    void maskHidesValue() {
        assertThat(KodikTokenRegistry.mask("abcdef1234")).isEqualTo("abcd…(10ch)");
        assertThat(KodikTokenRegistry.mask(null)).isEqualTo("<empty>");
        assertThat(KodikTokenRegistry.mask("ab")).isEqualTo("****");
    }

    @Test
    @DisplayName("markValid revives a token from DEAD into UNSTABLE on first success")
    void markValidRevivesFromDead() throws IOException {
        writeFile(
                "{\"stable\":[],\"unstable\":[],\"legacy\":[],"
                        + "\"dead\":[{\"value\":\"phoenix\","
                        + "\"functions_availability\":{"
                        + "\"base_search\":false,\"base_search_by_id\":false,"
                        + "\"get_list\":false,\"search\":false,\"search_by_id\":false,"
                        + "\"get_info\":false,\"get_link\":false,"
                        + "\"get_m3u8_playlist_link\":false}}]}");
        KodikTokenRegistry registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();
        assertThat(registry.tierOf("phoenix")).contains(KodikTokenTier.DEAD);

        registry.markValid("phoenix", KodikFunction.BASE_SEARCH);

        assertThat(registry.tierOf("phoenix")).contains(KodikTokenTier.UNSTABLE);
        assertThat(registry.countFor(KodikTokenTier.DEAD)).isZero();
        assertThat(registry.countFor(KodikTokenTier.UNSTABLE)).isEqualTo(1);
        assertThat(registry.currentToken(KodikFunction.BASE_SEARCH)).isEqualTo("phoenix");
    }

    @Test
    @DisplayName("markValid for a STABLE token does not move it (only updates flags)")
    void markValidKeepsTierForLiveTokens() throws IOException {
        writeFile(
                "{\"stable\":[{\"value\":\"happy\","
                        + "\"functions_availability\":{\"base_search\":true}}],"
                        + "\"unstable\":[],\"legacy\":[],\"dead\":[]}");
        KodikTokenRegistry registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();

        registry.markValid("happy", KodikFunction.GET_LIST);

        assertThat(registry.tierOf("happy")).contains(KodikTokenTier.STABLE);
        assertThat(registry.countFor(KodikTokenTier.UNSTABLE)).isZero();
    }

    @Test
    @DisplayName("tierOf returns empty for unknown tokens")
    void tierOfUnknown() {
        KodikTokenRegistry registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();

        assertThat(registry.tierOf("nope")).isEmpty();
        assertThat(registry.tierOf(null)).isEmpty();
    }

    private void writeFile(String contents) throws IOException {
        Files.writeString(Path.of(properties.getKodik().getTokenFile()), contents);
    }
}
