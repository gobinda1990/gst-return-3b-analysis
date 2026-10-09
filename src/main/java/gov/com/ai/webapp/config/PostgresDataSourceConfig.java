
package gov.com.ai.webapp.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class PostgresDataSourceConfig {

	@Bean(name = "postgresDataSource")
	@ConfigurationProperties(prefix = "app.datasource.postgres")
	public HikariDataSource postgresDataSource() {
		return new HikariDataSource();
	}

	@Bean(name = "postgresJdbcTemplate")
	public JdbcTemplate postgresJdbcTemplate(@Qualifier("postgresDataSource") DataSource ds) {

		JdbcTemplate jdbc = new JdbcTemplate(ds);
		jdbc.setFetchSize(500);
		jdbc.setQueryTimeout(60);
		return jdbc;
	}

	@Bean(name = "postgresNamedJdbcTemplate")
	public NamedParameterJdbcTemplate postgresNamedJdbcTemplate(@Qualifier("postgresJdbcTemplate") JdbcTemplate jdbc) {

		return new NamedParameterJdbcTemplate(jdbc);
	}

	@Bean(name = "postgresTransactionManager")
	public PlatformTransactionManager postgresTransactionManager(@Qualifier("postgresDataSource") DataSource ds) {

		return new DataSourceTransactionManager(ds);
	}
}
