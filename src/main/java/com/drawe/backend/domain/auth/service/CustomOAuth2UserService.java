package com.drawe.backend.domain.auth.service;

import com.drawe.backend.domain.User;
import com.drawe.backend.domain.auth.dto.OAuthAttributes;
import com.drawe.backend.domain.auth.repository.UserRepository;
import com.drawe.backend.global.security.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        OAuth2User oAuth2User = delegate.loadUser(userRequest);

        OAuthAttributes attributes = OAuthAttributes.ofGoogle(oAuth2User.getAttributes());

        if (attributes.getEmail() == null || attributes.getEmail().isBlank()) {
            throw new OAuth2AuthenticationException("Email not found from OAuth provider");
        }

        User user = userRepository.findByEmail(attributes.getEmail())
                .map(existingUser -> {
                    existingUser.updateProfile(
                            attributes.getNickname() != null ? attributes.getNickname() : existingUser.getNickname(),
                            attributes.getPicture()
                    );
                    existingUser.updateOAuthInfo(attributes.getProvider(), attributes.getProviderId());
                    return existingUser;
                })
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .email(attributes.getEmail())
                                .password(null)
                                .nickname(attributes.getNickname())
                                .picture(attributes.getPicture())
                                .provider(attributes.getProvider())
                                .providerId(attributes.getProviderId())
                                .build()
                ));

        log.info("OAuth login success: email={}, provider={}, providerId={}",
                user.getEmail(), user.getProvider(), user.getProviderId());

        return new PrincipalDetails(user, attributes.getAttributes());

    }
}
