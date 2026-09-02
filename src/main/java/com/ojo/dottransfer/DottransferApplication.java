package com.ojo.dottransfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing

public class DottransferApplication {

    public static void main(String[] args) {
        SpringApplication.run(DottransferApplication.class, args);
    }

}
