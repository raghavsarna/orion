package com.orion.network;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the Switch Network container. Spring is used only for the REST APIs. */
@SpringBootApplication
public class SwitchNetworkApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwitchNetworkApplication.class, args);
    }
}
