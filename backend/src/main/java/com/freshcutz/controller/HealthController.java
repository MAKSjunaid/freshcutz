package com.freshcutz.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** "Is the server up?" — answers instantly and deliberately never touches the
 * database. Render's health check and the keep-awake cron job point here, so
 * keeping the backend awake no longer keeps Neon's database awake (and
 * burning its free monthly compute hours) as well. */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
