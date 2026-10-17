package com.freshcutz.service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A tiny, dependency-free .xlsx writer used only by the backup e-mail.
 *
 * <p>Why not Apache POI? POI builds the whole workbook as objects in memory
 * (several times the size of the data) — too heavy for Render's small free
 * server. This writer streams each row straight into the zip file and keeps at
 * most ~200 rows of a sheet in memory (only to pick sensible column widths).
 *
 * <p>Every sheet gets: a bold dark header row, the header frozen while you
 * scroll, an auto-filter on the header, and sensible column widths.
 * Text is written as inline strings (no shared-strings table) and numbers as real
 * numbers, so Excel / Google Sheets / LibreOffice can sort and sum them.
 */
final class MiniXlsxWriter implements AutoCloseable {

    private static final int WIDTH_SAMPLE_ROWS = 200;
    private static final int MAX_CELL_CHARS = 32_000; // Excel's hard limit is 32,767

    private final ZipOutputStream zip;
    private final List<String> sheetNames = new ArrayList<>(); // order shown in Excel
    private final List<String> sheetFiles = new ArrayList<>(); // zip entry names
    private int fileCounter = 0;
    private Sheet open;

    MiniXlsxWriter(OutputStream out) {
        this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        this.zip.setLevel(6);
    }

    /** Starts a sheet. {@code first} puts it at the left-most tab (used for the Summary, which is
     *  written last because it needs the row counts of the others). */
    Sheet startSheet(String name, List<String> headers, boolean first) throws IOException {
        if (open != null) throw new IllegalStateException("previous sheet not finished");
        String file = "xl/worksheets/sheet" + (++fileCounter) + ".xml";
        String safe = safeSheetName(name);
        if (first) {
            sheetNames.add(0, safe);
            sheetFiles.add(0, file);
        } else {
            sheetNames.add(safe);
            sheetFiles.add(file);
        }
        zip.putNextEntry(new ZipEntry(file));
        open = new Sheet(headers);
        return open;
    }

    final class Sheet {
        private final List<String> headers;
        private final List<Object[]> pending = new ArrayList<>();
        private boolean headerWritten = false;
        private int rows = 0; // data rows written (excluding header)
        private int[] widths;

        private Sheet(List<String> headers) {
            this.headers = headers;
            this.widths = new int[headers.size()];
            for (int i = 0; i < widths.length; i++) widths[i] = headers.get(i).length() + 3;
        }

        /** Values may be String, Number, Boolean or null. */
        void row(Object... values) throws IOException {
            if (headerWritten) {
                writeRow(rows + 2, values);
            } else {
                pending.add(values);
                for (int i = 0; i < values.length && i < widths.length; i++) {
                    widths[i] = Math.max(widths[i], textLength(values[i]) + 2);
                }
                if (pending.size() >= WIDTH_SAMPLE_ROWS) flushHeaderAndPending();
            }
            rows++;
        }

        int rowCount() {
            return rows;
        }

        void end() throws IOException {
            if (!headerWritten) flushHeaderAndPending();
            StringBuilder sb = new StringBuilder(128);
            sb.append("</sheetData>");
            if (!headers.isEmpty()) {
                sb.append("<autoFilter ref=\"A1:").append(col(headers.size() - 1)).append(Math.max(1, rows + 1)).append("\"/>");
            }
            sb.append("</worksheet>");
            write(sb);
            zip.closeEntry();
            open = null;
        }

        private void flushHeaderAndPending() throws IOException {
            StringBuilder sb = new StringBuilder(1024);
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
            sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
            sb.append("<sheetViews><sheetView workbookViewId=\"0\">");
            sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>");
            sb.append("<selection pane=\"bottomLeft\"/></sheetView></sheetViews>");
            sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>");
            sb.append("<cols>");
            for (int i = 0; i < widths.length; i++) {
                int w = Math.min(55, Math.max(9, widths[i]));
                sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                        .append("\" width=\"").append(w).append("\" customWidth=\"1\"/>");
            }
            sb.append("</cols><sheetData>");
            // header row
            sb.append("<row r=\"1\" ht=\"24\" customHeight=\"1\">");
            for (int i = 0; i < headers.size(); i++) {
                sb.append("<c r=\"").append(col(i)).append("1\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">");
                escape(sb, headers.get(i));
                sb.append("</t></is></c>");
            }
            sb.append("</row>");
            write(sb);
            headerWritten = true;
            int r = 2;
            for (Object[] values : pending) writeRow(r++, values);
            pending.clear();
        }

        private void writeRow(int r, Object[] values) throws IOException {
            StringBuilder sb = new StringBuilder(256);
            sb.append("<row r=\"").append(r).append("\">");
            for (int i = 0; i < values.length; i++) {
                Object v = values[i];
                if (v == null) continue;
                String ref = col(i) + r;
                if (v instanceof Number) {
                    String num = (v instanceof BigDecimal) ? ((BigDecimal) v).toPlainString() : v.toString();
                    if (num.equals("NaN") || num.contains("Infinity")) continue;
                    sb.append("<c r=\"").append(ref).append("\"><v>").append(num).append("</v></c>");
                } else {
                    String s = (v instanceof Boolean) ? (((Boolean) v) ? "Yes" : "No") : v.toString();
                    if (s.isEmpty()) continue;
                    if (s.length() > MAX_CELL_CHARS) s = s.substring(0, MAX_CELL_CHARS);
                    sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">");
                    escape(sb, s);
                    sb.append("</t></is></c>");
                }
            }
            sb.append("</row>");
            write(sb);
        }
    }

    private void write(StringBuilder sb) throws IOException {
        zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        if (open != null) open.end();
        put("[Content_Types].xml", contentTypes());
        put("_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                        + "</Relationships>");
        put("xl/workbook.xml", workbook());
        put("xl/_rels/workbook.xml.rels", workbookRels());
        put("xl/styles.xml", STYLES);
        zip.finish();
        zip.close();
    }

    private void put(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String contentTypes() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        sb.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (String f : sheetFiles) {
            sb.append("<Override PartName=\"/").append(f)
                    .append("\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        sb.append("</Types>");
        return sb.toString();
    }

    private String workbook() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int i = 0; i < sheetNames.size(); i++) {
            sb.append("<sheet name=\"");
            escape(sb, sheetNames.get(i));
            sb.append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        sb.append("</sheets></workbook>");
        return sb.toString();
    }

    private String workbookRels() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheetFiles.size(); i++) {
            sb.append("<Relationship Id=\"rId").append(i + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"")
                    .append(sheetFiles.get(i).substring("xl/".length())).append("\"/>");
        }
        sb.append("<Relationship Id=\"rId").append(sheetFiles.size() + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        sb.append("</Relationships>");
        return sb.toString();
    }

    private static final String STYLES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<fonts count=\"2\">"
                    + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>"
                    + "</fonts>"
                    + "<fills count=\"3\">"
                    + "<fill><patternFill patternType=\"none\"/></fill>"
                    + "<fill><patternFill patternType=\"gray125\"/></fill>"
                    + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F4E78\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                    + "</fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"2\">"
                    + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\">"
                    + "<alignment horizontal=\"left\" vertical=\"center\"/></xf>"
                    + "</cellXfs>"
                    + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                    + "</styleSheet>";

    // ---- helpers -------------------------------------------------------

    private static int textLength(Object v) {
        if (v == null) return 0;
        String s = (v instanceof BigDecimal) ? ((BigDecimal) v).toPlainString() : v.toString();
        return Math.min(s.length(), 60);
    }

    /** 0 -> A, 25 -> Z, 26 -> AA ... */
    static String col(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (true) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
            if (n < 0) break;
        }
        return sb.toString();
    }

    private static String safeSheetName(String name) {
        String s = name.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (s.isEmpty()) s = "Sheet";
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    /** XML-escapes and drops characters XML 1.0 does not allow (they would make Excel report a corrupt file). */
    private static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)) {
                        sb.append(c);
                    } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append(c).append(s.charAt(++i));
                    } else if (c >= 0xE000 && c <= 0xFFFD) {
                        sb.append(c);
                    } // else: invalid -> skipped
            }
        }
    }
}
