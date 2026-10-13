package com.freshcutz.controller;

import com.freshcutz.dto.AdminStoreResponse;
import com.freshcutz.dto.StoreScheduleDto;
import com.freshcutz.service.StoreSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Lets the admin open/close the shop by hand, or set automatic opening hours.
 * Every change is pushed to open storefront tabs (over /api/store/status/stream)
 * by StoreSettingsService itself. Protected by AdminAuthFilter. */
@RestController
@RequestMapping("/api/admin/store")
public class AdminStoreController {

    private final StoreSettingsService storeSettingsService;

    public AdminStoreController(StoreSettingsService storeSettingsService) {
        this.storeSettingsService = storeSettingsService;
    }

    @GetMapping
    public AdminStoreResponse status() {
        return storeSettingsService.adminView();
    }

    /** The topbar toggle. */
    @PatchMapping("/online")
    public AdminStoreResponse setOnline(@RequestBody Map<String, Boolean> body) {
        return storeSettingsService.setOnline(Boolean.TRUE.equals(body.get("online")));
    }

    /** Saves the automatic opening hours. */
    @PutMapping("/schedule")
    public AdminStoreResponse saveSchedule(@RequestBody StoreScheduleDto schedule) {
        return storeSettingsService.updateSchedule(schedule);
    }

    /** Ends a manual override so the schedule takes over again. */
    @PatchMapping("/follow-schedule")
    public AdminStoreResponse followSchedule() {
        return storeSettingsService.followSchedule();
    }
}
