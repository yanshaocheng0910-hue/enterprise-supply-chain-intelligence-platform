package com.scic.platform.intelligence;

import com.scic.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

import java.util.Map;

@Component
public class AiGateway {
    private final RestClient client;

    public AiGateway(@Value("${scic.ai.base-url}") String baseUrl,
                     @Value("${scic.ai.service-token}") String token,
                     @Value("${scic.ai.connect-timeout-ms}") int connectTimeout,
                     @Value("${scic.ai.read-timeout-ms}") int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("X-Service-Token", token).build();
    }

    public Map<String, Object> parse(Map<String, Object> body, String requestId) {
        return post("/internal/v1/parse", body, requestId);
    }

    public Map<String, Object> forecast(Map<String, Object> body, String requestId) {
        return post("/internal/v1/forecast", body, requestId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body, String requestId) {
        try {
            Map<String, Object> response = client.post().uri(path).header("X-Request-ID", requestId).body(body).retrieve().body(Map.class);
            if (response == null || response.isEmpty()) throw invalidOutput("AI 服务返回空结果，请稍后重试或手工处理");
            return response;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                throw new BusinessException("AI_SERVICE_AUTH_FAILED", "AI 服务鉴权失败，请联系管理员检查服务配置，业务表单仍可手工处理",
                        HttpStatus.BAD_GATEWAY, Map.of("upstreamStatus", ex.getStatusCode().value()));
            }
            HttpStatus status = ex.getStatusCode().is5xxServerError() ? HttpStatus.BAD_GATEWAY : HttpStatus.UNPROCESSABLE_ENTITY;
            throw new BusinessException(status == HttpStatus.BAD_GATEWAY ? "AI_PROVIDER_ERROR" : "AI_OUTPUT_INVALID",
                    "AI 服务拒绝了本次请求", status, Map.of("upstreamStatus", ex.getStatusCode().value()));
        } catch (RestClientException ex) {
            if (ex instanceof UnknownContentTypeException || ex.contains(HttpMessageConversionException.class)) {
                throw invalidOutput("AI 服务返回的数据格式不合法，请稍后重试或手工处理");
            }
            throw unavailable("AI 服务当前不可用，业务表单仍可手工处理");
        }
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException("AI_SERVICE_UNAVAILABLE", message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static BusinessException invalidOutput(String message) {
        return new BusinessException("AI_OUTPUT_INVALID", message, HttpStatus.BAD_GATEWAY);
    }
}
