package com.bank.cbs.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.bank.cbs.repository.master",
        entityManagerFactoryRef = "masterEntityManagerFactory",
        transactionManagerRef = "masterTransactionManager"
)
public class PrimaryDataSourceConfig {

    @Primary
    @Bean(name = "masterDataSourceProperties")
    @ConfigurationProperties("spring.datasource.master")
    public DataSourceProperties masterDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean(name = "masterDataSource")
    @ConfigurationProperties("spring.datasource.master.hikari") // without this the hikari.* settings are silently ignored
    public DataSource masterDataSource(
            @Qualifier("masterDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Primary
    @Bean(name = "masterEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean masterEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("masterDataSource") DataSource dataSource,
            @Qualifier("masterDataSourceProperties") DataSourceProperties properties,
            @Autowired(required = false) @Qualifier("masterFlyway") Flyway masterFlyway) {
        Map<String, Object> jpaProperties = new HashMap<>();
        jpaProperties.put("hibernate.hbm2ddl.auto", "none");
        jpaProperties.put("hibernate.format_sql", "false");

        String dialect = resolveDialect(properties.getUrl());
        if (dialect != null) {
            jpaProperties.put("hibernate.dialect", dialect);
        }

        return builder
                .dataSource(dataSource)
                .packages("com.bank.cbs.entity.master")
                .persistenceUnit("masterPU")
                .properties(jpaProperties)
                .build();
    }

    @Primary
    @Bean(name = "masterTransactionManager")
    public PlatformTransactionManager masterTransactionManager(
            @Qualifier("masterEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    private String resolveDialect(String url) {
        if (url == null) return null;
        if (url.contains(":oracle:")) return "org.hibernate.dialect.OracleDialect";
        if (url.contains(":postgresql:")) return "org.hibernate.dialect.PostgreSQLDialect";
        if (url.contains(":sqlserver:")) return "org.hibernate.dialect.SQLServerDialect";
        if (url.contains(":h2:")) return "org.hibernate.dialect.H2Dialect";
        return null;
    }
}
