package dev.proofpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableJpaRepositories(considerNestedRepositories = true)
public class ProofpayBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProofpayBackendApplication.class, args);
    }
}
