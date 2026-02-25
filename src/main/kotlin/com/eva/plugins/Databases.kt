package com.eva.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

fun Application.configureDatabases() {
    val config = environment.config.config("database")

    val hikariConfig = HikariConfig().apply {
        driverClassName      = config.property("driver").getString()
        jdbcUrl              = config.property("url").getString()
        username             = config.property("user").getString()
        password             = config.property("password").getString()
        maximumPoolSize      = config.property("maxPoolSize").getString().toInt()
        isAutoCommit         = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        poolName             = "EVA-HikariPool"
        connectionInitSql    = "SET search_path TO public"
        validate()
    }

    val dataSource = HikariDataSource(hikariConfig)
    Database.connect(dataSource)

    transaction {
        exec("SELECT 1") { it.next() }
    }

    log.info("Database connected: ${config.property("url").getString()}")
}