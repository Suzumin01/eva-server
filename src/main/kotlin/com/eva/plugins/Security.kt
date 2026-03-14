package com.eva.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureSecurity() {
    val jwtConfig = environment.config.config("jwt")
    val secret    = jwtConfig.property("secret").getString()
    val issuer    = jwtConfig.property("issuer").getString()
    val audience  = jwtConfig.property("audience").getString()
    val realm     = jwtConfig.property("realm").getString()

    authentication {
        jwt("jwt-auth") {
            this.realm = realm
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withAudience(audience)
                    .withIssuer(issuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("userId").asString() != null) {
                    JWTPrincipal(credential.payload)
                } else null
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Токен недействителен или истёк")
                )
            }
        }
    }
}

fun ApplicationCall.getUserId(): String =
    principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
        ?: error("getUserId() вызван вне authenticate-блока")

fun ApplicationCall.getUserRole(): String =
    principal<JWTPrincipal>()?.payload?.getClaim("role")?.asString()
        ?: error("getUserRole() вызван вне authenticate-блока")