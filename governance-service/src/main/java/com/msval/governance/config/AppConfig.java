package com.msval.governance.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.msval.governance.engine.Engine;
import com.msval.governance.engine.OperatorRegistry;
import com.msval.governance.registry.RuleCache;

/**
 * Service wiring (Stage 2 conventions): constructor injection only; the engine is one
 * shared, stateless instance (DD-009 threading note); explicit TransactionTemplate for
 * the DD-013 normative transactions.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MsvalProperties.class)
public class AppConfig {

    @Bean
    public Engine engine() {
        return new Engine(OperatorRegistry.standard());
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }

    /** DD-012: warm the rule cache once Flyway has migrated (runner = after context refresh). */
    @Bean
    public ApplicationRunner ruleCacheWarmup(RuleCache ruleCache) {
        return args -> ruleCache.swap();
    }
}
