package com.getjobs.application.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSeedInitializationTest {
    private static final Map<String, Integer> EXPECTED_COUNTS = new LinkedHashMap<>();

    static {
        EXPECTED_COUNTS.put("config", 5);
        EXPECTED_COUNTS.put("ai", 1);
        EXPECTED_COUNTS.put("boss_config", 1);
        EXPECTED_COUNTS.put("boss_option", 578);
        EXPECTED_COUNTS.put("liepin_config", 1);
        EXPECTED_COUNTS.put("liepin_option", 14);
        EXPECTED_COUNTS.put("job51_config", 1);
        EXPECTED_COUNTS.put("job51_option", 39);
        EXPECTED_COUNTS.put("zhilian_config", 1);
        EXPECTED_COUNTS.put("zhilian_option", 42);
        EXPECTED_COUNTS.put("boss_blacklist", 0);
    }

    @Test
    void seedsOriginalProjectDefaultsAndRemainsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            ScriptUtils.executeSqlScript(connection, resource("schema.sql"));
            ScriptUtils.executeSqlScript(connection, resource("data.sql"));
            assertSeedCounts(connection);
            assertSafeDefaults(connection);
            assertAiPromptTemplate(connection);

            ScriptUtils.executeSqlScript(connection, resource("data.sql"));
            assertSeedCounts(connection);
            assertSafeDefaults(connection);
            assertAiPromptTemplate(connection);
        }
    }

    private void assertSeedCounts(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (Map.Entry<String, Integer> expected : EXPECTED_COUNTS.entrySet()) {
                try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + expected.getKey())) {
                    assertEquals(expected.getValue(), result.getInt(1), expected.getKey());
                }
            }
        }
    }

    private EncodedResource resource(String name) {
        return new EncodedResource(new ClassPathResource(name), StandardCharsets.UTF_8);
    }

    private void assertSafeDefaults(Connection connection) throws Exception {
        assertConfigValue(connection, "HOOK_URL", "");
        assertConfigValue(connection, "API_KEY", "");
        assertConfigValue(connection, "BASE_URL", "https://api.openai.com");
        assertConfigValue(connection, "MODEL", "gpt-5-nano");
    }

    private void assertConfigValue(Connection connection, String key, String expectedValue) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT config_value FROM config WHERE config_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                assertEquals(expectedValue, result.getString(1), key);
            }
        }
    }

    private void assertAiPromptTemplate(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT prompt FROM ai LIMIT 1")) {
            String prompt = result.getString(1);
            assertTrue(prompt.contains("我期望的的岗位方向是【%s】"));
            assertTrue(prompt.contains("注意是基本符合"));
            assertTrue(prompt.contains("直接返回false给我"));
            assertTrue(prompt.contains("注意只要返回我需要的内容即可"));
            assertEquals(5, prompt.split("%s", -1).length - 1);
        }
    }

    @Test
    void upgradesLegacyLagouConfigTableWithoutFailure() throws Exception {
        String dbUrl = "jdbc:sqlite:file:memdb_legacy_" + System.nanoTime() + "?mode=memory&cache=shared";
        try (Connection keepAlive = DriverManager.getConnection(dbUrl)) {
            ScriptUtils.executeSqlScript(keepAlive, resource("schema.sql"));
            try (Statement statement = keepAlive.createStatement()) {
                statement.execute("DROP TABLE lagou_config");
                statement.execute("CREATE TABLE lagou_config (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "keywords VARCHAR(500), " +
                        "city VARCHAR(100), " +
                        "resume_type VARCHAR(20) DEFAULT 'ONLINE', " +
                        "resume_name VARCHAR(200), " +
                        "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                        "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
            }

            org.sqlite.SQLiteDataSource ds = new org.sqlite.SQLiteDataSource();
            ds.setUrl(dbUrl);

            com.getjobs.application.init.DatabaseSchemaInitializer initializer =
                    new com.getjobs.application.init.DatabaseSchemaInitializer();
            initializer.postProcessAfterInitialization(ds, "dataSource");

            ScriptUtils.executeSqlScript(keepAlive, resource("data.sql"));

            try (Statement statement = keepAlive.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT max_count FROM lagou_config LIMIT 1")) {
                assertTrue(rs.next());
                assertEquals(30, rs.getInt("max_count"));
            }
        }
    }
}
