package dev.depguard.security;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.Parameter;

/**
 * Loads secrets from AWS SSM Parameter Store before the app starts, when {@code DEPGUARD_SSM_PATH} is set (Terraform
 * sets it on Lambda). Every parameter under the path becomes a property:
 * {@code /depguard/langfuse/secret-key} → {@code depguard.langfuse.secret-key}.
 *
 * <p>Why Parameter Store: SecureString parameters are KMS-encrypted and IAM-controlled like Secrets Manager, but free
 * (Secrets Manager is $0.40 per secret per month). The values never appear in the Lambda configuration or in
 * Terraform state: scripts/deploy.sh writes them straight from .env with the AWS CLI.
 *
 * <p>SnapStart note: parameters are read once, when the snapshot is taken. After changing a secret, redeploy (a new
 * version) so the new value is used.
 */
public class SsmParametersPostProcessor implements EnvironmentPostProcessor {

    static final String PATH_VARIABLE = "DEPGUARD_SSM_PATH";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String path = environment.getProperty(PATH_VARIABLE, "");
        if (path.isBlank()) {
            return; // locally: secrets come from .env
        }
        String region = environment.getProperty("AWS_REGION", "us-east-1");
        Map<String, Object> properties = new HashMap<>();
        try (SsmClient ssm = SsmClient.builder().region(Region.of(region)).build()) {
            String nextToken = null;
            do { // results come in pages of up to 10
                var response = ssm.getParametersByPath(GetParametersByPathRequest.builder()
                        .path(path).recursive(true).withDecryption(true).nextToken(nextToken).build());
                for (Parameter parameter : response.parameters()) {
                    properties.put(propertyName(path, parameter.name()), parameter.value());
                }
                nextToken = response.nextToken();
            } while (nextToken != null);
        }
        // First in line: these values win over application.yml and environment variables.
        environment.getPropertySources().addFirst(new MapPropertySource("ssm-parameters", properties));
    }

    /** "/depguard/" + "/depguard/github/token" → "depguard.github.token". */
    static String propertyName(String path, String parameterName) {
        String relative = parameterName.substring(path.endsWith("/") ? path.length() : path.length() + 1);
        return "depguard." + relative.replace('/', '.');
    }
}
