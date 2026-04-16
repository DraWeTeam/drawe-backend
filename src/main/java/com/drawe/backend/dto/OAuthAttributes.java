package com.drawe.backend.dto;

import java.util.Map;

public class OAuthAttributes {

    private Map<String, Object> attributes;
    private final String email;
    private final String nickname;
    private final String picture;
    private final String provider;
    private final String providerId;

    public OAuthAttributes(Map<String, Object> attributes, String email, String nickname, String picture, String provider, String providerId) {
        this.attributes = attributes;
        this.email = email;
        this.nickname = nickname;
        this.picture = picture;
        this.provider = provider;
        this.providerId = providerId;
    }

    // 구글 attributes를 감싸서 반환
    public static OAuthAttributes ofGoogle(Map<String, Object> attributes){
        return new OAuthAttributes(
                attributes,
                (String) attributes.get("email"),
                (String) attributes.get("name"),
                (String) attributes.get("picture"),
                "google",
                (String) attributes.get("sub")
        );
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public String getEmail() {
        return email;
    }

    public String getNickname() {
        return nickname;
    }

    public String getPicture() {
        return picture;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderId() {
        return providerId;
    }
}

