package de.osmtrailzustand;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.h2.jdbcx.JdbcDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

@ApplicationScoped
public class TrailDatabase {
    @Inject
    @ConfigProperty(name = "osm-mtb-trailzustand.db.path")
    String databasePath;

    private final JdbcDataSource dataSource = new JdbcDataSource();

    @PostConstruct
    void initialize() {
        dataSource.setURL("jdbc:h2:file:" + Path.of(databasePath).toAbsolutePath());
    }

    Connection connect() throws SQLException {
        return dataSource.getConnection();
    }
}
