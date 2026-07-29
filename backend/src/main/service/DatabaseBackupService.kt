package com.cs30.server.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Service
class DatabaseBackupService(
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username:}") private val dbUser: String,
    @Value("\${spring.datasource.password:}") private val dbPassword: String,
    @Value("\${backup.directory:/var/backups/cs30-db}") private val backupDir: String,
    @Value("\${backup.retain-days:7}") private val retainDays: Int,
    @Value("\${backup.enabled:true}") private val backupEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(DatabaseBackupService::class.java)
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /**
     * Run database backup daily at 2:00 AM.
     * Cron: second minute hour day-of-month month day-of-week
     */
    @Scheduled(cron = "\${backup.cron:0 0 2 * * *}")
    fun scheduledBackup() {
        if (!backupEnabled) {
            log.info("Database backup is disabled")
            return
        }
        // runBackup() already logs its own failure detail; this result was previously discarded
        // entirely, so a nightly backup could fail silently for days with zero distinct signal.
        // No alerting mechanism exists anywhere in this codebase yet to page anyone on this —
        // that's a real gap, but a bigger undertaking than this fix. At minimum, a scheduled-run
        // failure now gets its own loud, distinctly-labeled ERROR line (separate from a
        // manually-triggered failure) that's easy to grep/alert on externally.
        val result = runBackup()
        if (!result.success) {
            log.error("SCHEDULED DATABASE BACKUP FAILED: ${result.message}")
        }
    }

    /**
     * Run backup manually (can be triggered via API or CLI).
     */
    fun runBackup(): BackupResult {
        val timestamp = LocalDateTime.now().format(timestampFormat)
        log.info("Starting database backup at $timestamp")
        log.info("JDBC URL: $jdbcUrl")

        // Create backup directory
        val backupDirectory = File(backupDir)
        if (!backupDirectory.exists()) {
            backupDirectory.mkdirs()
        }

        return try {
            val backupFile = when {
                jdbcUrl.contains("postgresql") -> backupPostgresql(timestamp)
                jdbcUrl.contains("mysql") || jdbcUrl.contains("mariadb") -> backupMysql(timestamp)
                jdbcUrl.contains("h2") -> backupH2(timestamp)
                jdbcUrl.contains("sqlite") -> backupSqlite(timestamp)
                else -> throw UnsupportedOperationException("Unsupported database type: $jdbcUrl")
            }

            cleanupOldBackups()

            log.info("Backup completed successfully: $backupFile")
            BackupResult(success = true, message = "Backup saved to $backupFile", filePath = backupFile)
        } catch (e: Exception) {
            log.error("Backup failed: ${e.message}", e)
            BackupResult(success = false, message = "Backup failed: ${e.message}", filePath = null)
        }
    }

    private fun backupPostgresql(timestamp: String): String {
        // Parse: jdbc:postgresql://host:port/dbname
        val regex = Regex("""jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)""")
        val match = regex.find(jdbcUrl) ?: throw IllegalArgumentException("Invalid PostgreSQL JDBC URL")

        val host = match.groupValues[1]
        val port = match.groupValues[2].ifEmpty { "5432" }
        val dbName = match.groupValues[3]

        log.info("Backing up PostgreSQL database: $dbName @ $host:$port")

        val backupFile = "$backupDir/postgres_${dbName}_$timestamp.sql.gz"

        // pg_dump | gzip > file
        val process = ProcessBuilder("bash", "-c",
            "PGPASSWORD='$dbPassword' pg_dump -h '$host' -p '$port' -U '$dbUser' '$dbName' | gzip > '$backupFile'"
        ).redirectErrorStream(true).start()

        val completed = process.waitFor(30, TimeUnit.MINUTES)
        if (!completed || process.exitValue() != 0) {
            val error = process.inputStream.bufferedReader().readText()
            throw RuntimeException("pg_dump failed: $error")
        }

        return backupFile
    }

    private fun backupMysql(timestamp: String): String {
        // Parse: jdbc:mysql://host:port/dbname or jdbc:mariadb://host:port/dbname
        val regex = Regex("""jdbc:(?:mysql|mariadb)://([^:/]+)(?::(\d+))?/([^?]+)""")
        val match = regex.find(jdbcUrl) ?: throw IllegalArgumentException("Invalid MySQL/MariaDB JDBC URL")

        val host = match.groupValues[1]
        val port = match.groupValues[2].ifEmpty { "3306" }
        val dbName = match.groupValues[3]

        log.info("Backing up MySQL/MariaDB database: $dbName @ $host:$port")

        val backupFile = "$backupDir/mysql_${dbName}_$timestamp.sql.gz"

        val process = ProcessBuilder("bash", "-c",
            "mysqldump -h '$host' -P '$port' -u '$dbUser' -p'$dbPassword' '$dbName' | gzip > '$backupFile'"
        ).redirectErrorStream(true).start()

        val completed = process.waitFor(30, TimeUnit.MINUTES)
        if (!completed || process.exitValue() != 0) {
            val error = process.inputStream.bufferedReader().readText()
            throw RuntimeException("mysqldump failed: $error")
        }

        return backupFile
    }

    private fun backupH2(timestamp: String): String {
        // Parse: jdbc:h2:file:/path/to/db or jdbc:h2:/path/to/db or jdbc:h2:mem:dbname
        if (jdbcUrl.contains(":mem:")) {
            log.warn("H2 in-memory database - nothing to backup")
            return "in-memory (skipped)"
        }

        val regex = Regex("""jdbc:h2:(?:file:)?([^;]+)""")
        val match = regex.find(jdbcUrl) ?: throw IllegalArgumentException("Invalid H2 JDBC URL")

        val dbPath = match.groupValues[1]
        val dbName = File(dbPath).name

        log.info("Backing up H2 database: $dbPath")

        // H2 stores data in .mv.db file
        var h2File = File("$dbPath.mv.db")
        if (!h2File.exists()) {
            h2File = File("$dbPath.h2.db")
        }
        if (!h2File.exists()) {
            throw RuntimeException("H2 database file not found: $dbPath.mv.db")
        }

        val backupFile = "$backupDir/h2_${dbName}_$timestamp.db.gz"

        val process = ProcessBuilder("bash", "-c",
            "gzip -c '${h2File.absolutePath}' > '$backupFile'"
        ).redirectErrorStream(true).start()

        val completed = process.waitFor(10, TimeUnit.MINUTES)
        if (!completed || process.exitValue() != 0) {
            val error = process.inputStream.bufferedReader().readText()
            throw RuntimeException("H2 backup failed: $error")
        }

        return backupFile
    }

    private fun backupSqlite(timestamp: String): String {
        // Parse: jdbc:sqlite:/path/to/db.sqlite
        val regex = Regex("""jdbc:sqlite:(.+)""")
        val match = regex.find(jdbcUrl) ?: throw IllegalArgumentException("Invalid SQLite JDBC URL")

        val dbPath = match.groupValues[1]
        val dbName = File(dbPath).nameWithoutExtension

        log.info("Backing up SQLite database: $dbPath")

        if (!File(dbPath).exists()) {
            throw RuntimeException("SQLite database file not found: $dbPath")
        }

        val backupFile = "$backupDir/sqlite_${dbName}_$timestamp.sql.gz"

        val process = ProcessBuilder("bash", "-c",
            "sqlite3 '$dbPath' '.dump' | gzip > '$backupFile'"
        ).redirectErrorStream(true).start()

        val completed = process.waitFor(10, TimeUnit.MINUTES)
        if (!completed || process.exitValue() != 0) {
            val error = process.inputStream.bufferedReader().readText()
            throw RuntimeException("sqlite3 dump failed: $error")
        }

        return backupFile
    }

    private fun cleanupOldBackups() {
        log.info("Cleaning up backups older than $retainDays days")

        val cutoffTime = System.currentTimeMillis() - (retainDays * 24 * 60 * 60 * 1000L)
        val backupDirectory = File(backupDir)

        backupDirectory.listFiles { file ->
            file.isFile && (file.name.endsWith(".sql.gz") || file.name.endsWith(".db.gz"))
        }?.filter { it.lastModified() < cutoffTime }?.forEach { file ->
            log.info("Deleting old backup: ${file.name}")
            file.delete()
        }
    }

    data class BackupResult(
        val success: Boolean,
        val message: String,
        val filePath: String?
    )
}
