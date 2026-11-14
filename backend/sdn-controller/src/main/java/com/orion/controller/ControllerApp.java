package com.orion.controller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the Orion controller. Spring is used only for the REST APIs. */
@SpringBootApplication
public class ControllerApp {

    public static void main(String[] args) {
        SpringApplication.run(ControllerApp.class, args);
    }
}
