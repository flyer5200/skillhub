package com.iflytek.skillhub.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom token response client for DingTalk that handles the non-standard
 * camelCase token response format (accessToken, refreshToken, expiresIn)
 * instead of the OAuth2-standard snake_case (access_token, refresh_token, expires_in).
 * <p>
 * Also sends the token request as a JSON body (DingTalk's preferred format)
 * rather than form-encoded.
 */
@Component
public class DingTalkTokenResponseClient
        implements OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> {

    private final DefaultAuthorizationCodeTokenResponseClient defaultClient =
            new DefaultAuthorizationCodeTokenResponseClient();

    private final RestOperations restOperations;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DingTalkTokenResponseClient() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            return execution.execute(request, body);
        });
        this.restOperations = restTemplate;
    }

    @Override
    public OAuth2AccessTokenResponse getTokenResponse(OAuth2AuthorizationCodeGrantRequest authorizationCodeGrantRequest)
            throws OAuth2AuthenticationException {
        String registrationId = authorizationCodeGrantRequest.getClientRegistration().getRegistrationId();
        if (!"dingtalk".equals(registrationId)) {
            return defaultClient.getTokenResponse(authorizationCodeGrantRequest);
        }

        String tokenUri = authorizationCodeGrantRequest.getClientRegistration().getProviderDetails().getTokenUri();
        Map<String, Object> requestBody = buildTokenRequestJson(authorizationCodeGrantRequest);

        RequestEntity<Map<String, Object>> requestEntity = new RequestEntity<>(
                requestBody, buildTokenRequestHeaders(),
                HttpMethod.POST, java.net.URI.create(tokenUri));

        try {
            ResponseEntity<String> response = restOperations.exchange(requestEntity, String.class);
            return parseDingTalkTokenResponse(response.getBody());
        } catch (RestClientException ex) {
            OAuth2Error oauth2Error = new OAuth2Error("invalid_token_response",
                    "An error occurred while attempting to retrieve the OAuth 2.0 Access Token Response: " + ex.getMessage(), null);
            throw new OAuth2AuthenticationException(oauth2Error, ex.getMessage(), ex);
        }
    }

    private Map<String, Object> buildTokenRequestJson(OAuth2AuthorizationCodeGrantRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(OAuth2ParameterNames.GRANT_TYPE, request.getGrantType().getValue());
        body.put(OAuth2ParameterNames.CODE, request.getAuthorizationExchange().getAuthorizationResponse().getCode());
        body.put(OAuth2ParameterNames.REDIRECT_URI, request.getAuthorizationExchange().getAuthorizationRequest().getRedirectUri());
        body.put(OAuth2ParameterNames.CLIENT_ID, request.getClientRegistration().getClientId());
        body.put(OAuth2ParameterNames.CLIENT_SECRET, request.getClientRegistration().getClientSecret());
        return body;
    }

    private HttpHeaders buildTokenRequestHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private OAuth2AccessTokenResponse parseDingTalkTokenResponse(String responseBody) {
        try {
            JsonNode json = objectMapper.readTree(responseBody);
            String accessToken = getJsonString(json, "accessToken", "access_token");
            String refreshToken = getJsonString(json, "refreshToken", "refresh_token");

            int expiresIn = 7200;
            if (json.has("expiresIn")) {
                expiresIn = json.get("expiresIn").asInt();
            } else if (json.has("expires_in")) {
                expiresIn = json.get("expires_in").asInt();
            }

            Map<String, Object> additionalParameters = new HashMap<>();
            json.fields().forEachRemaining(entry -> {
                additionalParameters.put(entry.getKey(), entry.getValue().asText());
            });

            return OAuth2AccessTokenResponse.withToken(accessToken)
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .expiresIn(expiresIn)
                    .refreshToken(refreshToken)
                    .additionalParameters(additionalParameters)
                    .build();
        } catch (Exception e) {
            OAuth2Error oauth2Error = new OAuth2Error("invalid_token_response",
                    "Could not parse DingTalk token response: " + e.getMessage(), null);
            throw new OAuth2AuthenticationException(oauth2Error, e.getMessage(), e);
        }
    }

    private String getJsonString(JsonNode json, String... possibleKeys) {
        for (String key : possibleKeys) {
            if (json.has(key) && !json.get(key).isNull()) {
                return json.get(key).asText();
            }
        }
        return null;
    }
}
