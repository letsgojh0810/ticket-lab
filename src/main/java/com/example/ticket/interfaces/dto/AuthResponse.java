package com.example.ticket.interfaces.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthResponse {

    private Long userId;
    private String email;
    private String message;
    private String accessToken;
    private String tokenType;

    public static AuthResponse ofSignup(String email) {
        return AuthResponse.builder()
                .email(email)
                .message("회원가입 성공")
                .build();
    }

    public static AuthResponse ofLogin(Long userId, String accessToken) {
        return AuthResponse.builder()
                .userId(userId)
                .accessToken(accessToken)
                .tokenType("Bearer")
                .build();
    }
}
