package com.freshcutz.service;

import com.freshcutz.dto.ImportResultResponse;
import com.freshcutz.dto.OrderImportRow;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.repository.OrderRepository;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Reads an admin-edited orders .xlsx (same column layout ExportService
 * writes) and applies the corrected values back onto the matching orders,
 * looked up by their Order Code. Line items aren't re-imported — only the
 * customer details, status and total amount can be corrected this way. */
@Service
public class ImportService {

    private final OrderRepository orderRepository;
    private final DataFormatter formatter = new DataFormatter();

    public ImportService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public List<OrderImportRow> parse(InputStream fileStream) throws IOException {
        List<OrderImportRow> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(fileStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String orderCode = text(row, 0);
                if (orderCode.isBlank()) continue;

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
        List<String> errors = new ArrayList<>();

        for (OrderImportRow row : rows) {
            try {
                applyRow(row);
                updated++;
            } catch (Exception ex) {
                errors.add("Row " + row.getRowNumber() + " (" + row.getOrderCode() + "): " + ex.getMessage());
            }
        }

        return ImportResultResponse.builder()
                .updated(updated)
                .skipped(errors.size())
                .errors(errors)
                .build();
    }

    private void applyRow(OrderImportRow row) {
        Order order = orderRepository.findByOrderCode(row.getOrderCode())
                .orElseThrow(() -> new BadRequestException("no order with that code"));

        if (!row.getCustomerName().isBlank()) order.setCustomerName(row.getCustomerName());
        if (!row.getPhone().isBlank()) order.setPhone(row.getPhone());
        if (!row.getFlatNo().isBlank()) order.setFlatNo(row.getFlatNo());
        if (!row.getAddressLine().isBlank()) order.setAddressLine(row.getAddressLine());
        order.setLandmark(row.getLandmark().isBlank() ? null : row.getLandmark());
        order.setNotes(row.getNotes().isBlank() ? null : row.getNotes());

        if (!row.getStatus().isBlank()) {
            try {
                order.setStatus(OrderStatus.valueOf(row.getStatus().trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("unknown status \"" + row.getStatus() + "\"");
            }
        }
        if (row.getTotalAmount() != null) {
            order.setTotalAmount(row.getTotalAmount());
        }

        // saveAndFlush (not just save) so this row's write actually hits the
        // database right away — with a plain save(), Hibernate can defer the
        // write to the very end of the whole import, and one bad row later
        // in the file would then silently roll every earlier row back too.
        orderRepository.saveAndFlush(order);
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
