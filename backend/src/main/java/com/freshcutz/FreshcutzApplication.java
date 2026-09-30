package com.freshcutz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableAsync lets PushNotificationService's notify methods run on a
// background thread, so sending a Web Push notification never adds
// network-call latency to placing an order or marking one delivered.
// @EnableScheduling turns on SseBroadcastService's periodic heartbeat,
// which keeps the storefront's live product/store-status connections
// from being treated as idle and closed by anything sitting in between.
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class FreshcutzApplication {
    public static void main(String[] args) {
        SpringApplication.run(FreshcutzApplication.class, args);
    }
}
