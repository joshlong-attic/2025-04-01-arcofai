package com.example.service;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@SpringBootApplication
public class ServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceApplication.class, args);
    }

    @Bean
    ToolCallbackProvider toolCallbackProvider(DogScheduler scheduler) {
        return MethodToolCallbackProvider
                .builder()
                .toolObjects(scheduler)
                .build();
    }
}

@Component
class DogScheduler {

    @Tool(description = "schedule an appointment to pickup or " +
            "adopt a dog at a Pooch Palace location")
    String scheduleForPickup(@ToolParam(description = "the id of the dog") int dogId,
                             @ToolParam(description = "the name of the dog") String dogName) {

        System.out.println("scheduling for pickup: " + dogId + " " + dogName);
        return Instant
                .now()
                .plus(3, ChronoUnit.DAYS)
                .toString();
    }
}