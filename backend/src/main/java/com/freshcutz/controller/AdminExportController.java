package com.freshcutz.controller;

import com.freshcutz.dto.CustomerSalesResponse;
import com.freshcutz.dto.ImportResultResponse;
import com.freshcutz.dto.OrderImportRow;
import com.freshcutz.dto.ProductStatSummaryResponse;
import com.freshcutz.dto.VariantStatResponse;
import com.freshcutz.entity.Order;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.service.CustomerService;
import com.freshcutz.service.ExportService;
import com.freshcutz.service.ImportService;
import com.freshcutz.service.OrderService;
import com.freshcutz.service.ProductStatsService;
import com.freshcutz.service.SalesService;
import com.freshcutz.service.SalesStatusFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** Sales tab "Export this period" / "Export all" (download as .xlsx) and
 * "Import" (re-upload an edited orders file to correct order data).
 *
 * GET /export with no view = Export all: every order, ignoring every
 * filter, in the importable orders layout. With view=day|customer|item it is
 * Export this period: exactly what the matching Sales sub-tab shows for the
 * given from/to (omit both for All time) and status (ALL/NEW/DELIVERED/
 * CANCELLED), all on one sheet. */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminExportController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ExportService exportService;
    private final ImportService importService;
    private final OrderService orderService;
    private final SalesService salesService;
    private final CustomerService customerService;
    private final ProductStatsService productStatsService;

    public AdminExportController(ExportService exportService, ImportService importService,
                                  OrderService orderService, SalesService salesService,
                                  CustomerService customerService, ProductStatsService productStatsService) {
        this.exportService = exportService;
        this.importService = importService;
        this.orderService = orderService;
        this.salesService = salesService;
        this.customerService = customerService;
        this.productStatsService = productStatsService;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String view,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status) throws IOException {

        byte[] file;
        String filename;

        if (view == null || view.isBlank()) {
            // Export all: everything, no filters.
            file = exportService.exportOrders(orderService.listAllEntities());
            filename = "FreshCutz_All_Orders.xlsx";
        } else {
            LocalDate fromDate = parseDate(from, "from");
            LocalDate toDate = parseDate(to, "to");
            if ((fromDate == null) != (toDate == null)) {
                throw new BadRequestException("Send both from and to, or neither for all time");
            }
            SalesStatusFilter filter;
            try {
                filter = SalesStatusFilter.parse(status);
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("status must be ALL, NEW, DELIVERED or CANCELLED");
            }

            switch (view.trim().toLowerCase()) {
                case "day" -> {
                    List<Order> orders = salesService.ordersForExport(fromDate, toDate, filter);
                    file = exportService.exportOrders(orders);
                }
                case "customer" -> {
                    List<CustomerSalesResponse> customers = customerService.listAllTopCustomers(fromDate, toDate, filter);
                    file = exportService.exportCustomers(customers);
                }
                case "item" -> {
                    List<ProductStatSummaryResponse> items = productStatsService.listAllTopProducts(fromDate, toDate, filter);
                    Map<Long, List<VariantStatResponse>> variants = productStatsService.listAllVariants(fromDate, toDate, filter);
                    file = exportService.exportItems(items, variants);
                }
                default -> throw new BadRequestException("view must be day, customer or item");
            }
            filename = "FreshCutz_Export.xlsx"; // the page names the saved file itself
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
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

    private LocalDate parseDate(String raw, String name) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("Invalid " + name + " date \"" + raw + "\" (use YYYY-MM-DD)");
        }
    }
}
