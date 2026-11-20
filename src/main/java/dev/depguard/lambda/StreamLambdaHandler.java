package dev.depguard.lambda;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequest;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import dev.depguard.DepGuardApplication;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * AWS Lambda entry point. Turns an API Gateway HTTP API event into a normal servlet request, so the
 * same controllers that run locally on Tomcat also run on Lambda. Not used when running locally.
 *
 * <p>Spring starts in the static block, during Lambda's init phase. With SnapStart, Lambda takes a
 * snapshot after init and restores it on cold start, so users don't wait for Spring to boot.
 */
public class StreamLambdaHandler implements RequestStreamHandler {

    private static final SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse> HANDLER;

    static {
        try {
            HANDLER = SpringBootLambdaContainerHandler.getHttpApiV2ProxyHandler(DepGuardApplication.class);
        } catch (ContainerInitializationException e) {
            throw new IllegalStateException("Could not start Spring Boot inside Lambda", e);
        }
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        HANDLER.proxyStream(input, output, context);
    }
}
