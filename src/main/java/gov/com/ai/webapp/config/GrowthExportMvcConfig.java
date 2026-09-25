package gov.com.ai.webapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class GrowthExportMvcConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(
            AsyncSupportConfigurer configurer) {

        ThreadPoolTaskExecutor executor =
                new ThreadPoolTaskExecutor();

        executor.setThreadNamePrefix("growth-export-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.initialize();

        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(360_000L);
    }
}