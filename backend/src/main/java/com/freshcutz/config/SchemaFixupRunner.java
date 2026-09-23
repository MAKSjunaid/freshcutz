package com.freshcutz.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Safety net for anyone (namely: you, testing locally) who ran this app
 * BEFORE product photos moved into the database. The very first time
 * Hibernate added the new image_data column, H2 defaulted it to just 255
 * bytes (an unspecified VARBINARY's default size) — nowhere near a real
 * photo, which is why ImageMigrationRunner failed with "Value too long for
 * column". Product.java now declares the column's real size up front, so
 * this only matters for a database that already has the too-small column
 * from before that fix. It's safe to leave in permanently: widening a
 * column to the size it already is does nothing, every time.
 *
 * @Order(0) makes sure this runs before ImageMigrationRunner, which is the
 * thing that actually needs the wider column to succeed.
 *
 * Postgres/Neon never had this problem — its "bytea" columns have no such
 * size limit — so this is skipped entirely there.
 */
@Slf4j
@Component
@Order(0)
public class SchemaFixupRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final String datasourceUrl;

    public SchemaFixupRunner(JdbcTemplate jdbcTemplate,
                              @Value("${spring.datasource.url}") String datasourceUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void run(String... args) {
        if (!datasourceUrl.startsWith("jdbc:h2:")) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE products ALTER COLUMN image_data VARBINARY(3145728)");
            log.info("Confirmed local H2 image_data column is wide enough for real photos");
        } catch (Exception e) {
            // Table doesn't exist yet on a brand new database, or any other
            // harmless reason — never block startup over this.
            log.debug("Skipped image_data column widen: {}", e.getMessage());
        }
    }
}
