package com.moviepicker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@code @SpringBootApplication} = {@code @Configuration} + {@code @ComponentScan} + {@code @EnableAutoConfiguration}.
 * Auto-configuration looks at the classpath (batch-jdbc starter, H2, JPA, web...) and creates the beans we did not
 * define ourselves (DataSource, EntityManagerFactory, JobRepository, JobOperator, RestClient.Builder, ...).
 *
 * {@code @ConfigurationPropertiesScan} finds our {@code @ConfigurationProperties} records so we do not have to
 * register each one with {@code @EnableConfigurationProperties}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MoviePickerApplication {

	public static void main(String[] args) {
		SpringApplication.run(MoviePickerApplication.class, args);
	}

}
