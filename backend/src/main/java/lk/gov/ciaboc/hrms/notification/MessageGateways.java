package lk.gov.ciaboc.hrms.notification;

import lk.gov.ciaboc.hrms.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Adapters to the approved CIABOC e-mail and SMS services. When a transport is not configured (development),
 * messages are logged and marked SIMULATED instead of failing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageGateways {
    private final ObjectProvider<JavaMailSender> mailSender;
    private final AppProperties props;

    @Value("${spring.mail.host:}")
    private String mailHost;
    @Value("${app.sms.gateway-url:}")
    private String smsGatewayUrl;
    @Value("${app.sms.api-key:}")
    private String smsApiKey;

    /** @return true if really sent, false if simulated. */
    public boolean send(OutboundMessage m) {
        return switch (m.getChannel()) {
            case "EMAIL" -> sendEmail(m);
            case "SMS" -> sendSms(m);
            default -> throw new IllegalArgumentException("Unknown channel " + m.getChannel());
        };
    }

    private boolean sendEmail(OutboundMessage m) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (mailHost == null || mailHost.isBlank() || sender == null) {
            log.info("[SIMULATED EMAIL] to={} subject={}{}", m.getRecipient(), m.getSubject(),
                    m.isSensitive() ? "" : " body=" + m.getBody());
            return false;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(props.notifications().mailFrom());
        msg.setTo(m.getRecipient());
        msg.setSubject(m.getSubject());
        msg.setText(m.getBody());
        sender.send(msg);
        return true;
    }

    private boolean sendSms(OutboundMessage m) {
        if (smsGatewayUrl == null || smsGatewayUrl.isBlank()) {
            log.info("[SIMULATED SMS] to={}{}", m.getRecipient(), m.isSensitive() ? "" : " body=" + m.getBody());
            return false;
        }
        RestClient.create().post().uri(smsGatewayUrl)
                .header("X-API-Key", smsApiKey)
                .body(Map.of("to", m.getRecipient(), "message", m.getBody()))
                .retrieve().toBodilessEntity();
        return true;
    }
}
