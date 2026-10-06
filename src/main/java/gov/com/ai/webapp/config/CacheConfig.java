package gov.com.ai.webapp.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

	// Analytics cache names
	public static final String CACHE_DEFAULTERS_BY_PERIOD = "defaultersByPeriod";
	public static final String CACHE_AUDIT_PIPELINE_BY_PERIOD = "auditPipelineByPeriod";
	public static final String CACHE_GST_ANALYTICS = "gstAnalytics";
	public static final String CACHE_GST_ITC_ANALYTICS = "gstItcAnalyticsCache";

	// Growth service cache names
	public static final String CACHE_GROWTH_SUMMARY = "growthSummary";
	public static final String CACHE_GROWTH_TREND = "growthTrend";
	public static final String CACHE_GROWTH_PERIODS = "growthPeriods";
	public static final String CACHE_GROWTH_OFFICES = "growthOffices";
	public static final String CACHE_GROWTH_TAXPAYERS = "growthTaxpayers";

	// Defaulter dashboard cache names
	public static final String CACHE_DEFAULTER_SUMMARY = "defaulterSummary";
	public static final String CACHE_DEFAULTER_PERIODS = "defaulterPeriods";
	public static final String CACHE_DEFAULTER_OFFICES = "defaulterOffices";
	public static final String CACHE_DEFAULTER_HISTORY = "defaulterHistory";

	// Office revenue service cache names
	public static final String CACHE_OFFICE_REVENUE_SUMMARY = "officeRevenueSummary";
	public static final String CACHE_OFFICE_REVENUE_TREND = "officeRevenueTrend";
	public static final String CACHE_OFFICE_REVENUE_PERIODS = "officeRevenuePeriods";
	public static final String CACHE_OFFICE_REVENUE_OFFICES = "officeRevenueOffices";

	@Bean
	public CacheManager cacheManager() {
		CaffeineCacheManager manager = new CaffeineCacheManager();

		// Must be set BEFORE registering caches. A cached method returning null would
		// otherwise fail with IllegalArgumentException.
		manager.setAllowNullValues(false);

		// 1. Analytics & defaulters
		register(manager, CACHE_DEFAULTERS_BY_PERIOD, Duration.ofHours(2), 500);
		register(manager, CACHE_AUDIT_PIPELINE_BY_PERIOD, Duration.ofHours(2), 500);
		register(manager, CACHE_GST_ANALYTICS, Duration.ofMinutes(15), 10_000);
		register(manager, CACHE_GST_ITC_ANALYTICS, Duration.ofHours(4), 1_000);

		// 2. Growth service (separate instances avoid key collisions between caches)
		register(manager, CACHE_GROWTH_SUMMARY, Duration.ofMinutes(5), 500);
		register(manager, CACHE_GROWTH_TREND, Duration.ofMinutes(5), 500);
		register(manager, CACHE_GROWTH_PERIODS, Duration.ofMinutes(30), 10);
		register(manager, CACHE_GROWTH_OFFICES, Duration.ofMinutes(60), 200);
		register(manager, CACHE_GROWTH_TAXPAYERS, Duration.ofMinutes(15), 2_000);

		// 3. Defaulter dashboard
		register(manager, CACHE_DEFAULTER_SUMMARY, Duration.ofMinutes(5), 1_000);
		register(manager, CACHE_DEFAULTER_PERIODS, Duration.ofMinutes(30), 10);
		register(manager, CACHE_DEFAULTER_OFFICES, Duration.ofMinutes(15), 200);
		register(manager, CACHE_DEFAULTER_HISTORY, Duration.ofMinutes(10), 5_000);

		// 4. Office revenue service
		register(manager, CACHE_OFFICE_REVENUE_SUMMARY, Duration.ofMinutes(5), 2_000);
		register(manager, CACHE_OFFICE_REVENUE_TREND, Duration.ofMinutes(5), 2_000);
		register(manager, CACHE_OFFICE_REVENUE_PERIODS, Duration.ofMinutes(30), 10);
		register(manager, CACHE_OFFICE_REVENUE_OFFICES, Duration.ofMinutes(15), 1_000);

		// OPTIONAL hardening (disabled): makes an unregistered cache name fail fast instead of
		// silently creating an unbounded, never-expiring cache. Enable ONLY after confirming that
		// every @Cacheable/@CacheEvict/@CachePut name in the whole project is registered above,
		// otherwise those calls fail with "Cannot find cache named ...".
		// manager.setCacheNames(java.util.List.of());

		return manager;
	}

	private static void register(CaffeineCacheManager manager, String name, Duration ttl, long maxSize) {
		manager.registerCustomCache(name, Caffeine.newBuilder()
				.expireAfterWrite(ttl)
				.maximumSize(maxSize)
				.recordStats() // enables hit/miss metrics via Spring Boot Actuator
				.build());
	}
}