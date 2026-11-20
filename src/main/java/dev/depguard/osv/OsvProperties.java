package dev.depguard.osv;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under "depguard.osv" in application.yml. Timeouts stop a slow OSV from hanging our API. */
@ConfigurationProperties(prefix = "depguard.osv")
public record OsvProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
}
