package com.freshcutz.controller;

import com.freshcutz.service.BackupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The admin "Email backup now" button and its status line.
 * Protected by AdminAuthFilter like everything under /api/admin. */
@RestController
@RequestMapping("/api/admin/backup")
public class AdminBackupController {

    private final BackupService backupService;

    public AdminBackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    /** Settings + when the last backup was sent. Touches the database at most
     * once after a server start (to read the "last sent" row), never again. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return backupService.status();
    }

    /** The Customize-tab controls: {"enabled": true/false, "intervalHours": 24, "rangeDays": 0}. Any may be left out (rangeDays 0 = everything, 1 = today, -1 = yesterday). */
    @PutMapping("/settings")
    public Map<String, Object> settings(@RequestBody Map<String, Object> body) {
        Boolean enabled = body.get("enabled") instanceof Boolean ? (Boolean) body.get("enabled") : null;
        Integer hours = body.get("intervalHours") instanceof Number ? ((Number) body.get("intervalHours")).intValue() : null;
        Integer range = body.get("rangeDays") instanceof Number ? ((Number) body.get("rangeDays")).intValue() : null;
        return backupService.updateSettings(enabled, hours, range);
    }

    /** Builds the Excel file and e-mails it right now. */
    @PostMapping("/send")
    public Map<String, Object> send() {
        return backupService.sendNow();
    }
}
