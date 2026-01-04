package com.devil.phoenixproject.portal.models

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class SignupRequest(val email: String, val password: String, val displayName: String?)

@Serializable
data class AuthResponse(val token: String, val user: UserResponse)

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val displayName: String?,
    val isPremium: Boolean
)

@Serializable
data class ErrorResponse(val error: String)
