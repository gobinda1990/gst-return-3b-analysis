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

    // Analytics Cache Names
    public static final String CACHE_DEFAULTERS_BY_PERIOD = "defaultersByPeriod";
    public static final String CACHE_AUDIT_PIPELINE_BY_PERIOD = "auditPipelineByPeriod";
    public static final String CACHE_GST_ANALYTICS = "gstAnalytics";
    public static final String CACHE_GST_ITC_ANALYTICS = "gstItcAnalyticsCache";

    // Growth Service Cache Names
    public static final String CACHE_GROWTH_SUMMARY = "growthSummary";
    public static final String CACHE_GROWTH_TREND = "growthTrend";

    // Revenue Service Cache Names
    public static final String CACHE_OFFICE_REVENUE_SUMMARY = "officeRevenueSummary";
    public static final String CACHE_OFFICE_REVENUE_TREND = "officeRevenueTrend";
    public static final String CACHE_OFFICE_REVENUE_PERIODS = "officeRevenuePeriods";
    public static final String CACHE_OFFICE_REVENUE_OFFICES = "officeRevenueOffices";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        
        // Prevent caching null values globally across all cache regions
        cacheManager.setAllowNullValues(false);

        // ---------------------------------------------------------------------
        // 1. ANALYTICS & DEFAULTERS CACHES
        // ---------------------------------------------------------------------
        cacheManager.registerCustomCache(
            CACHE_DEFAULTERS_BY_PERIOD,
            buildCaffeineCache(Duration.ofHours(2), 500)
        );

        cacheManager.registerCustomCache(
            CACHE_AUDIT_PIPELINE_BY_PERIOD,
            buildCaffeineCache(Duration.ofHours(2), 500)
        );

        cacheManager.registerCustomCache(
            CACHE_GST_ANALYTICS,
            buildCaffeineCache(Duration.ofMinutes(15), 10_000)
        );

        cacheManager.registerCustomCache(
            CACHE_GST_ITC_ANALYTICS,
            buildCaffeineCache(Duration.ofHours(4), 1_000)
        );

        // ---------------------------------------------------------------------
        // 2. GROWTH SERVICE CACHES
        // ---------------------------------------------------------------------
        // Using distinct cache instances prevents key collisions between summary and trend
        cacheManager.registerCustomCache(
            CACHE_GROWTH_SUMMARY,
            buildCaffeineCache(Duration.ofMinutes(5), 500)
        );

        cacheManager.registerCustomCache(
            CACHE_GROWTH_TREND,
            buildCaffeineCache(Duration.ofMinutes(5), 500)
        );

        // ---------------------------------------------------------------------
        // 3. REVENUE SERVICE CACHES
        // ---------------------------------------------------------------------
        cacheManager.registerCustomCache(
            CACHE_OFFICE_REVENUE_SUMMARY,
            buildCaffeineCache(Duration.ofMinutes(5), 2_000)
        );

        cacheManager.registerCustomCache(
            CACHE_OFFICE_REVENUE_TREND,
            buildCaffeineCache(Duration.ofMinutes(5), 2_000)
        );

        cacheManager.registerCustomCache(
            CACHE_OFFICE_REVENUE_PERIODS,
            buildCaffeineCache(Duration.ofMinutes(30), 10)
        );

        cacheManager.registerCustomCache(
            CACHE_OFFICE_REVENUE_OFFICES,
            buildCaffeineCache(Duration.ofMinutes(15), 1_000)
        );

        return cacheManager;
    }

    /**
     * Helper method to generate Caffeine cache instances with metrics tracking enabled.
     */
    private com.github.benmanes.caffeine.cache.Cache<Object, Object> buildCaffeineCache(Duration ttl, long maxSize) {
        return Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .recordStats() // Required for Spring Boot Actuator metrics monitoring
                .build();
    }
}