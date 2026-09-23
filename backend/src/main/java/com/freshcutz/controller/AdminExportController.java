package com.freshcutz.controller;

import com.freshcutz.dto.ImportResultResponse;
import com.freshcutz.dto.OrderImportRow;
import com.freshcutz.entity.Order;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.service.ExportService;
import com.freshcutz.service.ImportService;
import com.freshcutz.service.OrderService;
import com.freshcutz.service.SalesService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Sales tab "Export" (download orders as .xlsx, optionally date-filtered)
 * and "Import" (re-upload an edited copy to correct order data) buttons. */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminExportController {

    private final ExportService exportService;
    private final ImportService importService;
    private final OrderService orderService;
    private final SalesService salesService;

    public AdminExportController(ExportService exportService, ImportService importService,
                                  OrderService orderService, SalesService salesService) {
        this.exportService = exportService;
        this.importService = importService;
        this.orderService = orderService;
        this.salesService = salesService;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) throws IOException {
        List<Order> orders = (from != null && to != null)
                ? salesService.ordersInDateRange(LocalDate.parse(from), LocalDate.parse(to))
                : orderService.listAllEntities();

        byte[] file = exportService.exportOrders(orders);
        String filename = "freshcutz-orders" + (from != null ? "-" + from + "_to_" + to : "-all") + ".xlsx";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(file);
    }

    @PostMapping("/import")
    public ImportResultResponse importOrders(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new BadRequestException("Please choose a .xlsx file to import");
        }
        List<OrderImportRow> rows = importService.parse(file.getInputStream());
        if (rows.isEmpty()) {
            throw new BadRequestException("No rows with an Order Code were found in that file");
        }
        return importService.apply(rows);
    }
}
