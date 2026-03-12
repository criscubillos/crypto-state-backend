package com.cryptostate.backend.auth.service;

import com.cryptostate.backend.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final AppProperties appProperties;
    private final WebClient.Builder webClientBuilder;

    public void sendVerificationEmail(String toEmail, String name, String verificationToken) {
        String verificationUrl = appProperties.getAllowedOrigins().get(0)
                + "/verify-email?token=" + verificationToken;

        sendEmail(toEmail, "Verifica tu cuenta en CryptoState",
                buildVerificationHtml(name, verificationUrl));
    }

    private void sendEmail(String to, String subject, String html) {
        try {
            webClientBuilder.build()
                    .post()
                    .uri("https://api.resend.com/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + appProperties.getResend().getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "from", appProperties.getResend().getFrom(),
                            "to", new String[]{to},
                            "subject", subject,
                            "html", html
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            response -> log.info("Email enviado a {}", to),
                            error -> log.error("Error enviando email a {}: {}", to, error.getMessage())
                    );
        } catch (Exception e) {
            log.error("Error enviando email: {}", e.getMessage());
        }
    }

    private String buildVerificationHtml(String name, String url) {
        return """
            <div style="font-family: sans-serif; max-width: 600px; margin: 0 auto;">
              <h2>¡Bienvenido a CryptoState, %s!</h2>
              <p>Verifica tu dirección de email para activar tu cuenta.</p>
              <a href="%s" style="background: #3B82F6; color: white; padding: 12px 24px;
                 text-decoration: none; border-radius: 6px; display: inline-block;">
                Verificar email
              </a>
              <p style="color: #6B7280; font-size: 12px; margin-top: 24px;">
                Este enlace expira en 24 horas.
              </p>
            </div>
            """.formatted(name, url);
    }
}
