package com.ticketing.backend.auth.oauth;

import com.ticketing.backend.user.User;
import com.ticketing.backend.user.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    public CustomOAuth2UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        OAuthAttributes oAuthAttributes = OAuthAttributes.of(registrationId, oAuth2User.getAttributes());

        User user = findOrCreateUser(oAuthAttributes);

        Map<String, Object> attributes = new HashMap<>(oAuth2User.getAttributes());
        attributes.put("internalUserId", String.valueOf(user.getId()));

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "internalUserId");
    }

    @Transactional
    protected User findOrCreateUser(OAuthAttributes attrs) {
        return userRepository
                .findByProviderAndProviderId(attrs.provider(), attrs.providerId())
                .orElseGet(() -> userRepository.save(
                        User.oauthSignup(attrs.provider(), attrs.providerId(), attrs.email(), attrs.name())));
    }
}
