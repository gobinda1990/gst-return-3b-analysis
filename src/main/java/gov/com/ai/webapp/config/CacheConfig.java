package gov.com.ai.webapp.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

	public static final String CACHE_DEFAULTERS_BY_PERIOD = "defaultersByPeriod";
	public static final String CACHE_AUDIT_PIPELINE_BY_PERIOD = "auditPipelineByPeriod";

	@Bean
	public CacheManager cacheManager() {
		CaffeineCacheManager cacheManager = new CaffeineCacheManager(CACHE_DEFAULTERS_BY_PERIOD,
				CACHE_AUDIT_PIPELINE_BY_PERIOD);
		cacheManager.setCaffeine(caffeineSpec());
		return cacheManager;
	}

	private Caffeine<Object, Object> caffeineSpec() {
		return Caffeine.newBuilder()
				// Evict entries 2 hours after last write to stay updated with periodic batch
				// runs
				.expireAfterWrite(2, TimeUnit.HOURS)
				// Maximum 500 return periods held in memory simultaneously per cache region
				.maximumSize(500)
				// Enables metrics tracking via Spring Actuator / Micrometer
				.recordStats();
	}
}