
package gov.com.ai.webapp.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class OracleDataSourceConfig {

	@Primary
	@Bean(name = "oracleDataSource")
	@ConfigurationProperties(prefix = "app.datasource.oracle")
	public HikariDataSource oracleDataSource() {
		return new HikariDataSource();
	}

	@Primary
	@Bean(name = "jdbcTemplate")
	public JdbcTemplate oracleJdbcTemplate(@Qualifier("oracleDataSource") DataSource ds) {

		JdbcTemplate jdbc = new JdbcTemplate(ds);
		jdbc.setFetchSize(1000);
		jdbc.setQueryTimeout(120);
		return jdbc;
	}

	@Primary
	@Bean(name = "namedParameterJdbcTemplate")
	public NamedParameterJdbcTemplate oracleNamedJdbcTemplate(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {

		return new NamedParameterJdbcTemplate(jdbc);
	}

	@Primary
	@Bean(name = "transactionManager")
	public PlatformTransactionManager oracleTransactionManager(@Qualifier("oracleDataSource") DataSource ds) {

		return new DataSourceTransactionManager(ds);
	}
}
