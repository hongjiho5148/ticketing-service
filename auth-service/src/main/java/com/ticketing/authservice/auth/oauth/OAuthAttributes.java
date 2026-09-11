package com.ticketing.authservice.auth.oauth;

import com.ticketing.authservice.user.AuthProvider;
import java.util.Map;

public record OAuthAttributes(AuthProvider provider, String providerId, String email, String name) {

    public static OAuthAttributes of(String registrationId, Map<String, Object> attributes) {
        if ("kakao".equals(registrationId)) {
            return ofKakao(attributes);
        }
        return ofGoogle(attributes);
    }

    private static OAuthAttributes ofGoogle(Map<String, Object> attributes) {
        return new OAuthAttributes(
                AuthProvider.GOOGLE,
                String.valueOf(attributes.get("sub")),
                (String) attributes.get("email"),
                (String) attributes.get("name"));
    }

    @SuppressWarnings("unchecked")
    private static OAuthAttributes ofKakao(Map<String, Object> attributes) {
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        Map<String, Object> profile = kakaoAccount == null ? null : (Map<String, Object>) kakaoAccount.get("profile");
        String nickname = profile == null ? null : (String) profile.get("nickname");
        return new OAuthAttributes(
                AuthProvider.KAKAO,
                String.valueOf(attributes.get("id")),
                null,
                nickname != null ? nickname : "카카오사용자");
    }
}
