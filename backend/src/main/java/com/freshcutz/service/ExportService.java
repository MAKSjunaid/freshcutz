package com.freshcutz.service;

import com.freshcutz.dto.CustomerSalesResponse;
import com.freshcutz.dto.ProductStatSummaryResponse;
import com.freshcutz.dto.VariantStatResponse;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderItem;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds the .xlsx workbooks behind the Sales tab's export buttons — each
 * one a single sheet holding everything for what was selected:
 *  - orders (By Day, and "Export all"): one row per order, items included;
 *  - customers (By Customer): one row per customer with the order/amount
 *    split by status;
 *  - items (By Item): one row per product with the quantity/amount split by
 *    status and its variants.
 * The orders layout is also what ImportService reads back in — keep its
 * first eleven columns as they are. */
@Service
public class ExportService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm");

    /** First cell of an orders sheet's header row — ImportService checks it
     * so a customers/items export can't be imported by mistake. */
    public static final String ORDERS_FIRST_HEADER = "Order Code";

    static final String[] HEADERS = {
            "Order Code", "Date/Time", "Customer Name", "Phone", "Flat No",
            "Address", "Landmark", "Notes", "Status", "Total Amount", "Items",
            "Status Date/Time"
    };

    static final String[] CUSTOMER_HEADERS = {
            "Customer", "Phone", "Flat No", "Address", "Landmark",
            "Orders", "Amount",
            "New Orders", "Delivered Orders", "Cancelled Orders",
            "New Amount", "Delivered Amount", "Cancelled Amount"
    };

    static final String[] ITEM_HEADERS = {
            "Item", "Item ID", "Quantity", "Amount",
            "New Qty", "Delivered Qty", "Cancelled Qty",
            "New Amount", "Delivered Amount", "Cancelled Amount",
            "Variants"
    };

    // ---- Orders ------------------------------------------------------------

    public byte[] exportOrders(List<Order> orders) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Orders");
            writeHeader(workbook, sheet, HEADERS);

            int rowNum = 1;
            for (Order order : orders) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(order.getOrderCode());
                row.createCell(1).setCellValue(DATE_FMT.format(order.getCreatedAt().atZone(SHOP_ZONE)));
                row.createCell(2).setCellValue(order.getCustomerName());
                row.createCell(3).setCellValue(order.getPhone());
                row.createCell(4).setCellValue(order.getFlatNo());
                row.createCell(5).setCellValue(order.getAddressLine());
                row.createCell(6).setCellValue(nullToBlank(order.getLandmark()));
                row.createCell(7).setCellValue(nullToBlank(order.getNotes()));
                row.createCell(8).setCellValue(order.getStatus().name());
                row.createCell(9).setCellValue(order.getTotalAmount().doubleValue());
                row.createCell(10).setCellValue(itemsSummary(order));
                row.createCell(11).setCellValue(statusDate(order));
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.setColumnWidth(i, i == 5 || i == 10 ? 42 * 256 : 18 * 256);
            }
            sheet.createFreezePane(0, 1);

            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** When the order reached its current status: delivered time for
     * Delivered, the time it was cancelled for Cancelled, blank for New
     * (its Date/Time column already says when it was placed). */
    private String statusDate(Order order) {
        Instant at = switch (order.getStatus()) {
            case DELIVERED -> order.getDeliveredAt() != null ? order.getDeliveredAt() : order.getStatusUpdatedAt();
            case CANCELLED -> order.getStatusUpdatedAt();
            default -> null;
        };
        return at == null ? "" : DATE_FMT.format(at.atZone(SHOP_ZONE));
    }

    // ---- Customers ---------------------------------------------------------

    public byte[] exportCustomers(List<CustomerSalesResponse> customers) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Customers");
            writeHeader(workbook, sheet, CUSTOMER_HEADERS);
            CellStyle money = moneyStyle(workbook);
            CellStyle bold = boldStyle(workbook);

            int rowNum = 1;
            long orders = 0, newOrders = 0, deliveredOrders = 0, cancelledOrders = 0;
            BigDecimal amount = BigDecimal.ZERO, newAmount = BigDecimal.ZERO,
                    deliveredAmount = BigDecimal.ZERO, cancelledAmount = BigDecimal.ZERO;
            for (CustomerSalesResponse c : customers) {
                int delivered = c.getOrders() - c.getNewOrders() - c.getCancelledOrders();
                BigDecimal deliveredSpent = c.getSpent().subtract(c.getNewSpent()).subtract(c.getCancelledSpent());
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(nullToBlank(c.getName()));
                row.createCell(1).setCellValue(nullToBlank(c.getPhone()));
                row.createCell(2).setCellValue(nullToBlank(c.getFlatNo()));
                row.createCell(3).setCellValue(nullToBlank(c.getAddressLine()));
                row.createCell(4).setCellValue(nullToBlank(c.getLandmark()));
                row.createCell(5).setCellValue(c.getOrders());
                money(row, 6, c.getSpent(), money);
                row.createCell(7).setCellValue(c.getNewOrders());
                row.createCell(8).setCellValue(delivered);
                row.createCell(9).setCellValue(c.getCancelledOrders());
                money(row, 10, c.getNewSpent(), money);
                money(row, 11, deliveredSpent, money);
                money(row, 12, c.getCancelledSpent(), money);

                orders += c.getOrders();
                newOrders += c.getNewOrders();
                deliveredOrders += delivered;
                cancelledOrders += c.getCancelledOrders();
                amount = amount.add(c.getSpent());
                newAmount = newAmount.add(c.getNewSpent());
                deliveredAmount = deliveredAmount.add(deliveredSpent);
                cancelledAmount = cancelledAmount.add(c.getCancelledSpent());
            }

            Row total = sheet.createRow(rowNum);
            Cell label = total.createCell(0);
            label.setCellValue("TOTAL (" + customers.size() + " customers)");
            label.setCellStyle(bold);
            total.createCell(5).setCellValue(orders);
            money(total, 6, amount, money);
            total.createCell(7).setCellValue(newOrders);
            total.createCell(8).setCellValue(deliveredOrders);
            total.createCell(9).setCellValue(cancelledOrders);
            money(total, 10, newAmount, money);
            money(total, 11, deliveredAmount, money);
            money(total, 12, cancelledAmount, money);

            for (int i = 0; i < CUSTOMER_HEADERS.length; i++) {
                sheet.setColumnWidth(i, i == 3 ? 42 * 256 : 16 * 256);
            }
            sheet.createFreezePane(0, 1);

            workbook.write(out);
            return out.toByteArray();
        }
    }

    // ---- Items -------------------------------------------------------------

    public byte[] exportItems(List<ProductStatSummaryResponse> products,
                              Map<Long, List<VariantStatResponse>> variants) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Items");
            writeHeader(workbook, sheet, ITEM_HEADERS);
            CellStyle money = moneyStyle(workbook);
            CellStyle bold = boldStyle(workbook);

            int rowNum = 1;
            long qty = 0, newQty = 0, deliveredQty = 0, cancelledQty = 0;
            BigDecimal amount = BigDecimal.ZERO, newAmount = BigDecimal.ZERO,
                    deliveredAmount = BigDecimal.ZERO, cancelledAmount = BigDecimal.ZERO;
            for (ProductStatSummaryResponse p : products) {
                long delivered = p.getQuantity() - p.getNewQuantity() - p.getCancelledQuantity();
                BigDecimal deliveredRevenue = p.getRevenue().subtract(p.getNewRevenue()).subtract(p.getCancelledRevenue());
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(nullToBlank(p.getProductName()));
                row.createCell(1).setCellValue(p.getProductId());
                row.createCell(2).setCellValue(p.getQuantity());
                money(row, 3, p.getRevenue(), money);
                row.createCell(4).setCellValue(p.getNewQuantity());
                row.createCell(5).setCellValue(delivered);
                row.createCell(6).setCellValue(p.getCancelledQuantity());
                money(row, 7, p.getNewRevenue(), money);
                money(row, 8, deliveredRevenue, money);
                money(row, 9, p.getCancelledRevenue(), money);
                row.createCell(10).setCellValue(variantsSummary(variants.get(p.getProductId())));

                qty += p.getQuantity();
                newQty += p.getNewQuantity();
                deliveredQty += delivered;
                cancelledQty += p.getCancelledQuantity();
                amount = amount.add(p.getRevenue());
                newAmount = newAmount.add(p.getNewRevenue());
                deliveredAmount = deliveredAmount.add(deliveredRevenue);
                cancelledAmount = cancelledAmount.add(p.getCancelledRevenue());
            }

            Row total = sheet.createRow(rowNum);
            Cell label = total.createCell(0);
            label.setCellValue("TOTAL (" + products.size() + " items)");
            label.setCellStyle(bold);
            total.createCell(2).setCellValue(qty);
            money(total, 3, amount, money);
            total.createCell(4).setCellValue(newQty);
            total.createCell(5).setCellValue(deliveredQty);
            total.createCell(6).setCellValue(cancelledQty);
            money(total, 7, newAmount, money);
            money(total, 8, deliveredAmount, money);
            money(total, 9, cancelledAmount, money);

            for (int i = 0; i < ITEM_HEADERS.length; i++) {
                sheet.setColumnWidth(i, i == 0 ? 28 * 256 : i == 10 ? 50 * 256 : 14 * 256);
            }
            sheet.createFreezePane(0, 1);

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private String variantsSummary(List<VariantStatResponse> variants) {
        if (variants == null || variants.isEmpty()) return "";
        return variants.stream()
                .map(v -> v.getVariantLabel() + ": " + v.getQuantity() + " sold, " + v.getRevenue().toPlainString())
                .collect(Collectors.joining(" | "));
    }

    // ---- Shared ------------------------------------------------------------

    private String itemsSummary(Order order) {
        return order.getItems().stream()
                .map(this::itemLine)
                .collect(Collectors.joining(" | "));
    }

    private String itemLine(OrderItem item) {
        return item.getQuantity() + "x " + item.getProductName() + " (" + item.getVariantLabel() + ")";
    }

    private void writeHeader(Workbook workbook, Sheet sheet, String[] headers) {
        CellStyle headerStyle = headerStyle(workbook);
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void money(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(style);
    }

    private CellStyle moneyStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
        return style;
    }

    private CellStyle boldStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
