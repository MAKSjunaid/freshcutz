package com.freshcutz.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the first sheet of an .xlsx file into rows of text — nothing but the JDK (no Apache POI), so it
 * is light on memory and quick to start. Understands what Excel, Google Sheets, LibreOffice and
 * the backup writer produce: shared strings, inline strings and plain numbers. Formulas are read as
 * their last calculated value. Empty cells come back as "".
 */
final class SimpleXlsxReader {

    private static final long MAX_PART_BYTES = 30L * 1024 * 1024; // protection against "zip bombs"
    private static final int MAX_ROWS = 5000;
    private static final int MAX_COLS = 60;

    private SimpleXlsxReader() {
    }

    static List<String[]> readFirstSheet(InputStream in) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (name.equals("xl/sharedStrings.xml") || name.equals("xl/workbook.xml")
                        || name.equals("xl/_rels/workbook.xml.rels")
                        || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))) {
                    parts.put(name, readLimited(zip));
                }
            }
        }
        if (parts.isEmpty()) throw new IOException("not an .xlsx file");
        String sheetPath = firstSheetPath(parts);
        if (sheetPath == null || !parts.containsKey(sheetPath)) throw new IOException("no sheet found in the file");
        try {
            List<String> shared = parts.containsKey("xl/sharedStrings.xml")
                    ? readSharedStrings(parts.get("xl/sharedStrings.xml")) : new ArrayList<>();
            return readSheet(parts.get(sheetPath), shared);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("could not read the sheet: " + ex.getMessage(), ex);
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_PART_BYTES) throw new IOException("the file is too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static DocumentBuilder docBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setNamespaceAware(false);
        return f.newDocumentBuilder();
    }

    private static SAXParser saxParser() throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newSAXParser();
    }

    /** The workbook's first sheet: xl/workbook.xml says which relationship it is, the .rels file where it lives. */
    private static String firstSheetPath(Map<String, byte[]> parts) {
        try {
            byte[] wb = parts.get("xl/workbook.xml");
            byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
            if (wb != null && rels != null) {
                Document d = docBuilder().parse(new ByteArrayInputStream(wb));
                NodeList sheets = d.getElementsByTagName("sheet");
                if (sheets.getLength() > 0) {
                    String rid = ((Element) sheets.item(0)).getAttribute("r:id");
                    Document r = docBuilder().parse(new ByteArrayInputStream(rels));
                    NodeList list = r.getElementsByTagName("Relationship");
                    for (int i = 0; i < list.getLength(); i++) {
                        Element el = (Element) list.item(i);
                        if (rid.equals(el.getAttribute("Id"))) {
                            String target = el.getAttribute("Target");
                            if (target.startsWith("/")) target = target.substring(1);
                            else if (!target.startsWith("xl/")) target = "xl/" + target;
                            return target;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fall back below
        }
        String best = null;
        for (String name : parts.keySet()) {
            if (name.startsWith("xl/worksheets/sheet") && (best == null || name.compareTo(best) < 0)) best = name;
        }
        return best;
    }

    private static List<String> readSharedStrings(byte[] xml) throws Exception {
        List<String> out = new ArrayList<>();
        saxParser().parse(new InputSource(new ByteArrayInputStream(xml)), new DefaultHandler() {
            StringBuilder cur;
            boolean inT;
            boolean inRPh; // phonetic hints — not part of the text

            @Override
            public void startElement(String uri, String ln, String qn, Attributes a) {
                if (qn.equals("si")) cur = new StringBuilder();
                else if (qn.equals("rPh")) inRPh = true;
                else if (qn.equals("t") && !inRPh) inT = true;
            }

            @Override
            public void characters(char[] ch, int s, int l) {
                if (inT && cur != null) cur.append(ch, s, l);
            }

            @Override
            public void endElement(String uri, String ln, String qn) {
                if (qn.equals("t")) inT = false;
                else if (qn.equals("rPh")) inRPh = false;
                else if (qn.equals("si")) {
                    out.add(cur == null ? "" : cur.toString());
                    cur = null;
                }
            }
        });
        return out;
    }

    private static List<String[]> readSheet(byte[] xml, List<String> shared) throws Exception {
        List<String[]> rows = new ArrayList<>();
        saxParser().parse(new InputSource(new ByteArrayInputStream(xml)), new DefaultHandler() {
            String[] row;
            int col;
            int rowIndex;
            String type;
            StringBuilder text;
            boolean inV, inT;
            int maxCol;

            @Override
            public void startElement(String uri, String ln, String qn, Attributes a) {
                switch (qn) {
                    case "row" -> {
                        String r = a.getValue("r");
                        rowIndex = r != null ? Integer.parseInt(r) - 1 : rows.size();
                        row = new String[MAX_COLS];
                        maxCol = -1;
                        col = 0;
                    }
                    case "c" -> {
                        String ref = a.getValue("r");
                        if (ref != null) col = columnIndex(ref);
                        type = a.getValue("t");
                        text = new StringBuilder();
                    }
                    case "v" -> inV = true;
                    case "t" -> inT = true; // <is><t> of inline strings
                    default -> {
                    }
                }
            }

            @Override
            public void characters(char[] ch, int s, int l) {
                if ((inV || inT) && text != null) text.append(ch, s, l);
            }

            @Override
            public void endElement(String uri, String ln, String qn) {
                switch (qn) {
                    case "v" -> inV = false;
                    case "t" -> inT = false;
                    case "c" -> {
                        if (row != null && col >= 0 && col < MAX_COLS && text != null) {
                            String v = text.toString();
                            if ("s".equals(type)) {
                                try {
                                    int idx = Integer.parseInt(v.trim());
                                    v = idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
                                } catch (NumberFormatException ex) {
                                    v = "";
                                }
                            } else if ("b".equals(type)) {
                                v = "1".equals(v.trim()) ? "TRUE" : "FALSE";
                            }
                            row[col] = v;
                            if (col > maxCol) maxCol = col;
                        }
                        text = null;
                    }
                    case "row" -> {
                        if (row != null && rowIndex < MAX_ROWS) {
                            while (rows.size() < rowIndex) rows.add(new String[0]);
                            String[] cells = new String[maxCol + 1];
                            for (int i = 0; i <= maxCol; i++) cells[i] = row[i] == null ? "" : row[i];
                            rows.add(cells);
                        }
                        row = null;
                    }
                    default -> {
                    }
                }
            }
        });
        return rows;
    }

    private static int columnIndex(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') n = n * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') n = n * 26 + (c - 'a' + 1);
            else break;
        }
        return n - 1;
    }
}
