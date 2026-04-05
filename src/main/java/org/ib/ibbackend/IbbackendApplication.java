package org.ib.ibbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "org.ib.ibbackend.repository")
public class IbbackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(IbbackendApplication.class, args);
    }

}
