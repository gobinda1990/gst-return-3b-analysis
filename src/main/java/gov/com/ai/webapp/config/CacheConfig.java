package gov.com.ai.webapp.config;

import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Configuration
@EnableCaching
public class CacheConfig {

	public static final String CACHE_DEFAULTERS_BY_PERIOD = "defaultersByPeriod";

	public static final String CACHE_AUDIT_PIPELINE_BY_PERIOD = "auditPipelineByPeriod";

	public static final String CACHE_GST_ANALYTICS = "gstAnalytics";

	@Bean
	public CacheManager cacheManager() {

		CaffeineCacheManager cacheManager = new CaffeineCacheManager();

		/*
		 * -------------------------------------------------------- DEFAULTERS CACHE
		 * --------------------------------------------------------
		 */
		Cache<Object, Object> defaultersCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2))
				.maximumSize(500).recordStats().build();

		/*
		 * -------------------------------------------------------- AUDIT PIPELINE CACHE
		 * --------------------------------------------------------
		 */
		Cache<Object, Object> auditPipelineCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2))
				.maximumSize(500).recordStats().build();

		/*
		 * -------------------------------------------------------- GST ANALYTICS CACHE
		 * --------------------------------------------------------
		 *
		 * GSTIN analytics can have many more keys than period-based dashboard caches.
		 */
		Cache<Object, Object> gstAnalyticsCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15))
				.maximumSize(10_000).recordStats().build();

		/*
		 * Register all cache regions.
		 */
		cacheManager.registerCustomCache(CACHE_DEFAULTERS_BY_PERIOD, defaultersCache);

		cacheManager.registerCustomCache(CACHE_AUDIT_PIPELINE_BY_PERIOD, auditPipelineCache);

		cacheManager.registerCustomCache(CACHE_GST_ANALYTICS, gstAnalyticsCache);

		/*
		 * Prevent null values from being cached.
		 */
		cacheManager.setAllowNullValues(false);

		return cacheManager;
	}
}