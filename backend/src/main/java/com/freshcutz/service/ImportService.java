package com.freshcutz.service;

import com.freshcutz.dto.ImportResultResponse;
import com.freshcutz.dto.OrderImportRow;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.repository.CustomerRepository;
import com.freshcutz.repository.OrderRepository;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads an admin-edited orders .xlsx (the layout ExportService writes for
 * By Day / Export all) and applies the corrected values back onto the
 * matching orders, looked up by Order Code. Line items aren't re-imported —
 * only the customer details, status and total amount can be corrected.
 *
 * A change goes through the same bookkeeping as doing it by hand in the
 * Orders tab, so the Customers and Items figures and the Sales dates stay
 * right: Delivered gets a delivery time and is added to the item stats,
 * leaving Delivered backs it out again, and a row that changes nothing is
 * left completely alone. Customer totals are recomputed once at the end
 * if any phone, status or amount changed. */
@Service
public class ImportService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductStatsService productStatsService;
    private final DataFormatter formatter = new DataFormatter();

    public ImportService(OrderRepository orderRepository,
                         CustomerRepository customerRepository,
                         ProductStatsService productStatsService) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.productStatsService = productStatsService;
    }

    public List<OrderImportRow> parse(InputStream fileStream) throws IOException {
        List<OrderImportRow> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(fileStream)) {
            Sheet sheet = workbook.getSheetAt(0);

            Row header = sheet.getRow(0);
            String firstHeader = header == null ? "" : text(header, 0);
            if (!ExportService.ORDERS_FIRST_HEADER.equalsIgnoreCase(firstHeader)) {
                throw new BadRequestException(
                        "This file isn't an orders export, so it can't be imported. Only the By Day export "
                        + "or \"Export all\" files can be imported (the first column must be \"Order Code\"). "
                        + "By Customer and By Item exports are for reading only.");
            }

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String orderCode = text(row, 0);
                if (orderCode.isBlank() || orderCode.equalsIgnoreCase("TOTAL")) continue;

                rows.add(OrderImportRow.builder()
                        .rowNumber(r + 1)
                        .orderCode(orderCode)
                        .customerName(text(row, 2))
                        .phone(text(row, 3))
                        .flatNo(text(row, 4))
                        .addressLine(text(row, 5))
                        .landmark(text(row, 6))
                        .notes(text(row, 7))
                        .status(text(row, 8))
                        .totalAmount(parseAmount(text(row, 9)))
                        .build());
            }
        }
        return rows;
    }

    @Transactional
    public ImportResultResponse apply(List<OrderImportRow> rows) {
        int updated = 0;
        int unchanged = 0;
        boolean statsAffected = false;
        List<String> errors = new ArrayList<>();

        for (OrderImportRow row : rows) {
            try {
                Outcome outcome = applyRow(row);
                if (outcome == Outcome.UNCHANGED) {
                    unchanged++;
                } else {
                    updated++;
                    if (outcome == Outcome.CHANGED_STATS) statsAffected = true;
                }
            } catch (Exception ex) {
                errors.add("Row " + row.getRowNumber() + " (" + row.getOrderCode() + "): " + ex.getMessage());
            }
        }

        // Per-customer order counts / delivered totals are rollup columns;
        // one recompute after the whole file keeps them exact however many
        // phones, statuses and amounts the file changed.
        if (statsAffected) {
            customerRepository.backfillCustomerStats();
        }

        return ImportResultResponse.builder()
                .updated(updated)
                .unchanged(unchanged)
                .skipped(errors.size())
                .errors(errors)
                .build();
    }

    private enum Outcome { UNCHANGED, CHANGED_DETAILS, CHANGED_STATS }

    private Outcome applyRow(OrderImportRow row) {
        Order order = orderRepository.findByOrderCode(row.getOrderCode())
                .orElseThrow(() -> new BadRequestException("no order with that code"));

        // Work out the new values first, so a bad cell rejects the whole row
        // before anything on the order is touched.
        OrderStatus newStatus = order.getStatus();
        if (!row.getStatus().isBlank()) {
            try {
                newStatus = OrderStatus.valueOf(row.getStatus().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("unknown status \"" + row.getStatus()
                        + "\" (use New, Delivered or Cancelled)");
            }
        }
        String newPhone = order.getPhone();
        if (!row.getPhone().isBlank()) {
            newPhone = normalizePhone(row.getPhone());
            if (newPhone.isEmpty()) {
                throw new BadRequestException("phone \"" + row.getPhone() + "\" has no digits");
            }
        }
        BigDecimal newAmount = row.getTotalAmount() != null ? row.getTotalAmount() : order.getTotalAmount();
        if (newAmount != null && newAmount.signum() < 0) {
            throw new BadRequestException("total amount can't be negative");
        }

        boolean detailsChanged = false;
        detailsChanged |= change(order.getCustomerName(), pick(row.getCustomerName(), order.getCustomerName()),
                order::setCustomerName);
        detailsChanged |= change(order.getFlatNo(), pick(row.getFlatNo(), order.getFlatNo()), order::setFlatNo);
        detailsChanged |= change(order.getAddressLine(), pick(row.getAddressLine(), order.getAddressLine()),
                order::setAddressLine);
        detailsChanged |= change(order.getLandmark(), blankToNull(row.getLandmark()), order::setLandmark);
        detailsChanged |= change(order.getNotes(), blankToNull(row.getNotes()), order::setNotes);

        boolean phoneChanged = !Objects.equals(order.getPhone(), newPhone);
        if (phoneChanged) order.setPhone(newPhone);

        boolean amountChanged = newAmount != null
                && (order.getTotalAmount() == null || order.getTotalAmount().compareTo(newAmount) != 0);
        if (amountChanged) order.setTotalAmount(newAmount);

        OrderStatus oldStatus = order.getStatus();
        boolean statusChanged = newStatus != oldStatus;
        if (statusChanged) {
            Instant now = Instant.now();
            Instant previousDeliveredAt = order.getDeliveredAt();
            order.setStatus(newStatus);
            order.setStatusUpdatedAt(now);
            if (newStatus == OrderStatus.DELIVERED) {
                order.setDeliveredAt(now);
            } else {
                order.setDeliveredAt(null);
            }
            // The Items rollup only counts delivered orders, per delivery day.
            if (oldStatus == OrderStatus.DELIVERED) {
                productStatsService.recordOrderUndelivered(order, previousDeliveredAt);
            }
            if (newStatus == OrderStatus.DELIVERED) {
                productStatsService.recordOrderDelivered(order);
            }
        }

        if (!(detailsChanged || phoneChanged || amountChanged || statusChanged)) {
            return Outcome.UNCHANGED;
        }

        // saveAndFlush (not just save) so this row's write actually hits the
        // database right away — one bad row later in the file must not
        // silently roll earlier rows back at the very end.
        orderRepository.saveAndFlush(order);

        return (phoneChanged || amountChanged || statusChanged) ? Outcome.CHANGED_STATS : Outcome.CHANGED_DETAILS;
    }

    /** Sets the value only when it differs; returns whether it did. */
    private boolean change(String current, String next, java.util.function.Consumer<String> setter) {
        if (Objects.equals(current, next)) return false;
        setter.accept(next);
        return true;
    }

    /** Blank cell = keep what's there (these fields are required on an order). */
    private String pick(String cell, String current) {
        return cell == null || cell.isBlank() ? current : cell;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Digits only, so "+91 98765 43210" and 9876543210 match the phone the
     * customer's other orders are stored under (a leading country code 91 on
     * a 12-digit number is dropped). */
    private String normalizePhone(String raw) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        return digits;
    }

    private String text(Row row, int col) {
        Cell cell = row.getCell(col);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
