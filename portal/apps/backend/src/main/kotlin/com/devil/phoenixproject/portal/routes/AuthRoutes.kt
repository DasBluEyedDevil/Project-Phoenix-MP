package com.devil.phoenixproject.portal.routes

import com.devil.phoenixproject.portal.auth.AuthService
import com.devil.phoenixproject.portal.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(authService: AuthService) {
    route("/api/auth") {
        post("/signup") {
            val request = call.receive<SignupRequest>()
            authService.signup(request).fold(
                onSuccess = { call.respond(HttpStatusCode.Created, it) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Signup failed")) }
            )
        }

        post("/login") {
            val request = call.receive<LoginRequest>()
            authService.login(request).fold(
                onSuccess = { call.respond(it) },
                onFailure = { call.respond(HttpStatusCode.Unauthorized, ErrorResponse(it.message ?: "Login failed")) }
            )
        }

        get("/me") {
            val authHeader = call.request.header("Authorization")
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid token"))
                return@get
            }

            val token = authHeader.removePrefix("Bearer ")
            val userId = authService.verifyToken(token)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
                return@get
            }

            val user = authService.getUser(userId)
            if (user == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@get
            }

            call.respond(user)
        }
    }
}
