package com.iflytek.skillhub.auth.oauth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Provider-specific claims extractor for DingTalk OAuth2.
 * Maps DingTalk's userinfo response to normalized {@link OAuthClaims}.
 */
@Component
public class DingTalkClaimsExtractor implements OAuthClaimsExtractor {

    private final RestClient restClient = RestClient.builder()
        .baseUrl("https://api.dingtalk.com")
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build();

    @Override
    public String getProvider() {
        return "dingtalk";
    }

    @Override
    public OAuthClaims extract(OAuth2UserRequest request, OAuth2User oAuth2User) {
        Map<String, Object> attrs = oAuth2User.getAttributes();

        DingTalkUser userInfo = loadUserInfo(request);
        String openid = userInfo != null ? userInfo.openid() : (String) attrs.get("openid");
        String nick = userInfo != null ? userInfo.nick() : (String) attrs.get("nick");
        String avatarUrl = userInfo != null ? userInfo.avatarUrl() : (String) attrs.get("avatarUrl");
        String email = userInfo != null ? userInfo.email() : (String) attrs.get("email");
        boolean emailVerified = email != null;

        // Merge userInfo into attributes for downstream use
        if (userInfo != null) {
            attrs.put("openid", openid);
            attrs.put("nick", nick);
            attrs.put("avatar_url", avatarUrl);
            attrs.put("email", email);
        }

        return new OAuthClaims(
            "dingtalk",
            openid != null ? openid : String.valueOf(attrs.getOrDefault("unionid", "")),
            email,
            emailVerified,
            nick != null ? nick : "dingtalk_user",
            attrs
        );
    }

    private DingTalkUser loadUserInfo(OAuth2UserRequest request) {
        try {
            DingTalkUserResponse response = restClient.get()
                .uri("/v1.0/contact/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + request.getAccessToken().getTokenValue())
                .retrieve()
                .body(DingTalkUserResponse.class);

            if (response == null) {
                return null;
            }
            return response.node();
        } catch (Exception e) {
            return null;
        }
    }

    private record DingTalkUserResponse(DingTalkUser node) {}

    private record DingTalkUser(
        String openid,
        String nick,
        String avatarUrl,
        String email,
        String mobile,
        String unionid
    ) {}
}
