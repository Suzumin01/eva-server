package com.eva.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import java.sql.Connection

fun Application.configureDatabases() {
    val config = environment.config.config("database")

    val jdbcUrl  = config.property("url").getString()
    val user     = config.property("user").getString()
    val password = config.property("password").getString()

    // Flyway: накатываем миграции до того, как открываем пул соединений
    Flyway.configure()
        .dataSource(jdbcUrl, user, password)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)      // на существующей БД без истории Flyway пометит V1 как применённую
        .baselineVersion("1")
        .load()
        .migrate()

    val hikariConfig = HikariConfig().apply {
        driverClassName   = config.property("driver").getString()
        this.jdbcUrl      = jdbcUrl
        username          = user
        this.password     = password
        maximumPoolSize    = config.property("maxPoolSize").getString().toInt()
        poolName           = "EVA-HikariPool"
        connectionInitSql  = "SET search_path TO public"
        validate()
    }

    val dataSource = HikariDataSource(hikariConfig)
    // defaultIsolationLevel совпадает с transactionIsolation HikariCP (READ_COMMITTED),
    // поэтому Exposed не меняет уровень изоляции в середине транзакции.
    Database.connect(dataSource, databaseConfig = DatabaseConfig {
        defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
    })

    log.info("Database connected and migrations applied: $jdbcUrl")
}
