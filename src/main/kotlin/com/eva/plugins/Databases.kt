package com.eva.plugins

import com.eva.data.tables.RefreshTokensTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
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
        poolName             = "EVA-HikariPool"
        connectionInitSql    = "SET search_path TO public"
        validate()
    }

    val dataSource = HikariDataSource(hikariConfig)
    Database.connect(dataSource, databaseConfig = DatabaseConfig {
        defaultIsolationLevel = java.sql.Connection.TRANSACTION_REPEATABLE_READ
    })

    transaction {
        exec("SELECT 1") { it.next() }
        SchemaUtils.createMissingTablesAndColumns(RefreshTokensTable)
    }

    log.info("Database connected: ${config.property("url").getString()}")
}