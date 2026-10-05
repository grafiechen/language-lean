package com.languagelean.audio;

import com.google.auth.oauth2.GoogleCredentials;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Google Chirp 3 HD REST 适配器；ADC 凭据只在服务端使用，不记录令牌或上游正文。 */
@Component
class GoogleSpeechSynthesizer implements SpeechSynthesizer {
    private final boolean enabled;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private GoogleCredentials credentials;
    /** 默认不启用外部付费调用；启用后采用标准 Google Application Default Credentials。 */
    GoogleSpeechSynthesizer(@Value("${app.audio.google-enabled:false}") boolean enabled, ObjectMapper json) {
        this.enabled = enabled; this.json = json;
    }
    @Override public boolean configured() { return enabled; }
    /** 只在需要时加载并刷新 ADC；同步刷新保护多个音频工作线程。 */
    private synchronized String token() throws java.io.IOException {
        if (credentials == null) credentials = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }
    /** 固定访问官方 HTTPS 接口，不允许后台配置任意 URL。 */
    @Override public byte[] synthesize(TtsSettingsService.Profile profile, String text) {
        if (!enabled) throw new IllegalStateException("TTS_NOT_CONFIGURED");
        if (text.getBytes(StandardCharsets.UTF_8).length > 5000) throw new IllegalArgumentException("PRONUNCIATION_TOO_LONG");
        try {
            var body = json.writeValueAsString(Map.of("input", Map.of("text", text),
                    "voice", Map.of("languageCode", profile.locale(), "name", profile.voice()),
                    "audioConfig", Map.of("audioEncoding", "MP3")));
            var request = HttpRequest.newBuilder(URI.create("https://texttospeech.googleapis.com/v1/text:synthesize"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("GOOGLE_TTS_FAILED");
            var audio = json.readTree(response.body()).get("audioContent");
            if (audio == null || !audio.isString()) throw new IllegalStateException("INVALID_AUDIO_RESPONSE");
            var bytes = Base64.getDecoder().decode(audio.asText());
            if (bytes.length == 0 || bytes.length > 5_000_000) throw new IllegalStateException("INVALID_AUDIO_SIZE");
            return bytes;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("TTS_INTERRUPTED");
        } catch (Exception failure) {
            throw new IllegalStateException("GOOGLE_TTS_FAILED");
        }
    }
}
