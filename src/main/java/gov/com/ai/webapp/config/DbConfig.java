package gov.com.ai.webapp.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;

@Configuration
@EnableTransactionManagement
@Slf4j
public class DbConfig {

    /**
     * Binds properties starting with 'spring.datasource.hikari' from application.properties.
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariConfig hikariConfig() {
        HikariConfig config = new HikariConfig();

        // -------------------------------------------------------------------
        // Pool & Connection Safety Defaults
        // -------------------------------------------------------------------
        config.setPoolName("GstReturnR1HikariCP");
        config.setConnectionTestQuery("SELECT 1 FROM DUAL"); // Rapid Oracle connection validation
        config.setLeakDetectionThreshold(60000);              // Log warning if connection held > 60s
        config.setAutoCommit(true);                          // Keep true at pool level; use @Transactional for bulk

        // -------------------------------------------------------------------
        // Oracle High-Performance Driver Properties (Optimized for Bulk Ops)
        // -------------------------------------------------------------------
        config.addDataSourceProperty("implicitCachingEnabled", "true"); // Cache prepared statements
        config.addDataSourceProperty("maxStatements", "250");           // Size of statement cache
        config.addDataSourceProperty("defaultBatchValue", "1000");        // Oracle Statement Batching size
        config.addDataSourceProperty("defaultRowPrefetch", "100");        // Network fetch optimization

        return config;
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariConfig config) {
        log.info("Initializing Production HikariCP DataSource [Pool: {}, JDBC URL: {}]", 
                config.getPoolName(), config.getJdbcUrl());
        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        // Optimize fetch size for bulk read operations
        jdbcTemplate.setFetchSize(1000);
        return jdbcTemplate;
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}