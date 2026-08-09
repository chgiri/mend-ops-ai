package com.giri.ai.mendops.remediation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Real implementation behind RemediationTools.pageOncall. Posts to a
 * Slack-incoming-webhook-style URL ({@code {"text": "..."}} body) configured
 * via {@code mendops.remediation.paging.webhook-url}.
 * <p>
 * pageOncall executes immediately (it's not gated by ApprovalGate - see
 * RemediationTools), so unlike the other two tools this can't fall back to
 * "queued, try again later" if unconfigured. If no webhook URL is set, this
 * logs the page at WARN so it's still visible in the demo/local case rather
 * than silently no-oping, but does not throw - a missing paging integration
 * shouldn't take down the one action path that's supposed to always work.
 */
@Component
public class PagingNotifier {

    private static final Logger log = LoggerFactory.getLogger(PagingNotifier.class);

    private final String webhookUrl;
    private final RestClient restClient;

    public PagingNotifier(RemediationProperties properties) {
        this.webhookUrl = properties.paging() == null ? null : properties.paging().webhookUrl();
        this.restClient = RestClient.create();
    }

    public void page(String summary) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("[PAGE - NOT SENT, no mendops.remediation.paging.webhook-url configured] {}", summary);
            return;
        }

        try {
            restClient.post()
                    .uri(webhookUrl)
                    .body(Map.of("text", ":rotating_light: mend-ops-ai paging on-call: " + summary))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Paged on-call via webhook: {}", summary);
        } catch (Exception e) {
            log.error("Failed to send page via webhook, on-call was NOT notified: {}", e.getMessage());
        }
    }
}
