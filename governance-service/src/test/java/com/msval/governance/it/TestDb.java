package com.msval.governance.it;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msval.governance.config.MsvalProperties;

/**
 * Integration harness against the REAL compose postgres (host.docker.internal:5432/governance,
 * user msval — override with -Dmsval.test.db.url / .user / .password). Flyway clean+migrate
 * once per JVM; per-test truncation keeps the seeded environments (V2).
 */
public final class TestDb {

    private static DataSource ds;

    private TestDb() {
    }

    public static synchronized DataSource dataSource() {
        if (ds == null) {
            PGSimpleDataSource p = new PGSimpleDataSource();
            p.setUrl(System.getProperty("msval.test.db.url",
                    "jdbc:postgresql://host.docker.internal:5432/governance"));
            p.setUser(System.getProperty("msval.test.db.user", "msval"));
            p.setPassword(System.getProperty("msval.test.db.password", "devtest"));
            Flyway flyway = Flyway.configure()
                    .dataSource(p)
                    .cleanDisabled(false)
                    .load();
            flyway.clean();
            flyway.migrate();
            ds = p;
        }
        return ds;
    }

    public static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    public static TransactionTemplate txn() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
    }

    /** F12 defaults, token devtoken; ports shifted to avoid clashing with a running stack. */
    public static MsvalProperties props() {
        return new MsvalProperties(128, 60, 500, 120, 300, 3600, 3, 17402, 17403, "devtoken");
    }

    /** Wipe mutable state; the V2 environment seeds survive. */
    public static void truncate() {
        jdbc().execute("TRUNCATE violations, waivers, evaluations, lifecycle_history, "
                + "lifecycle_states, topology_snapshots, service_versions, services, "
                + "policy_rules, active_bundle, rule_bundles, publish_audit "
                + "RESTART IDENTITY CASCADE");
    }
}
