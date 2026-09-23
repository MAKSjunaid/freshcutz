package com.freshcutz.service;

import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderItem;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/** Builds the .xlsx workbook behind the Sales tab's "Export" button.
 * See ImportService for reading one of these files back in. */
@Service
public class ExportService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm");

    static final String[] HEADERS = {
            "Order Code", "Date/Time", "Customer Name", "Phone", "Flat No",
            "Address", "Landmark", "Notes", "Status", "Total Amount", "Items"
    };

    public byte[] exportOrders(List<Order> orders) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Orders");
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setWrapText(false);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

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
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.setColumnWidth(i, i == 5 || i == 10 ? 42 * 256 : 18 * 256);
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private String itemsSummary(Order order) {
        return order.getItems().stream()
                .map(this::itemLine)
                .collect(Collectors.joining(" | "));
    }

    private String itemLine(OrderItem item) {
        return item.getQuantity() + "x " + item.getProductName() + " (" + item.getVariantLabel() + ")";
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
