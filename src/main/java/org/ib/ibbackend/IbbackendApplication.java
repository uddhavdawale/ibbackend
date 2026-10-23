package org.ib.ibbackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "org.ib.ibbackend.repository")
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class IbbackendApplication {
    private static final Logger log = LoggerFactory.getLogger(IbbackendApplication.class);

    public static void main(String[] args) {
        log.info("Starting IB Backend application");
        SpringApplication.run(IbbackendApplication.class, args);
    }

}
