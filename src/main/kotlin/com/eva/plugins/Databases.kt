package com.eva.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig

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
        maximumPoolSize   = config.property("maxPoolSize").getString().toInt()
        isAutoCommit      = false
        poolName          = "EVA-HikariPool"
        connectionInitSql = "SET search_path TO public"
        validate()
    }

    val dataSource = HikariDataSource(hikariConfig)
    // Не устанавливаем defaultIsolationLevel: Exposed не будет вызывать setTransactionIsolation()
    // на соединениях HikariCP (autoCommit=false), что вызывало PSQLException.
    // PostgreSQL-дефолт READ COMMITTED достаточен для всех операций.
    Database.connect(dataSource, databaseConfig = DatabaseConfig { })

    log.info("Database connected and migrations applied: $jdbcUrl")
}
