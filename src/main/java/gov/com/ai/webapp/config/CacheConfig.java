package gov.com.ai.webapp.config;

import com.github.benmanes.caffeine.cache.Cache;
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

    // Existing Cache Constants
    public static final String CACHE_DEFAULTERS_BY_PERIOD = "defaultersByPeriod";
    public static final String CACHE_AUDIT_PIPELINE_BY_PERIOD = "auditPipelineByPeriod";
    public static final String CACHE_GST_ANALYTICS = "gstAnalytics";
    public static final String CACHE_GST_ITC_ANALYTICS = "gstItcAnalyticsCache";

    // Growth Service Cache Constants
    public static final String CACHE_GROWTH_SUMMARY = "growthSummary";
    public static final String CACHE_GROWTH_TREND = "growthTrend";

    @Bean
    public CacheManager cacheManager() {

        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        /*
         * -------------------------------------------------------- DEFAULTERS CACHE
         * --------------------------------------------------------
         */
        Cache<Object, Object> defaultersCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(2))
                .maximumSize(500)
                .recordStats()
                .build();

        /*
         * -------------------------------------------------------- AUDIT PIPELINE CACHE
         * --------------------------------------------------------
         */
        Cache<Object, Object> auditPipelineCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(2))
                .maximumSize(500)
                .recordStats()
                .build();

        /*
         * -------------------------------------------------------- GST ANALYTICS CACHE
         * --------------------------------------------------------
         */
        Cache<Object, Object> gstAnalyticsCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(15))
                .maximumSize(10_000)
                .recordStats()
                .build();

        /*
         * -------------------------------------------------------- GST ITC BULK ANALYTICS CACHE
         * --------------------------------------------------------
         */
        Cache<Object, Object> gstItcAnalyticsCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(4))
                .maximumSize(1_000)
                .recordStats()
                .build();

        /*
         * -------------------------------------------------------- GROWTH CACHES (SUMMARY & TREND)
         * --------------------------------------------------------
         */
        Cache<Object, Object> growthCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(500)
                .recordStats()
                .build();

        /*
         * Register all cache regions.
         */
        cacheManager.registerCustomCache(CACHE_DEFAULTERS_BY_PERIOD, defaultersCache);
        cacheManager.registerCustomCache(CACHE_AUDIT_PIPELINE_BY_PERIOD, auditPipelineCache);
        cacheManager.registerCustomCache(CACHE_GST_ANALYTICS, gstAnalyticsCache);
        cacheManager.registerCustomCache(CACHE_GST_ITC_ANALYTICS, gstItcAnalyticsCache);
        
        // Register growth cache regions using custom cache builder
        cacheManager.registerCustomCache(CACHE_GROWTH_SUMMARY, growthCache);
        cacheManager.registerCustomCache(CACHE_GROWTH_TREND, growthCache);

        /*
         * Prevent null values from being cached.
         */
        cacheManager.setAllowNullValues(false);

        return cacheManager;
    }
}