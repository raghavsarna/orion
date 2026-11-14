package com.orion.switchnetwork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the Switch Network container. Spring is used only for the REST APIs. */
@SpringBootApplication
public class SwitchNetworkApp {

    public static void main(String[] args) {
        SpringApplication.run(SwitchNetworkApp.class, args);
    }
}
