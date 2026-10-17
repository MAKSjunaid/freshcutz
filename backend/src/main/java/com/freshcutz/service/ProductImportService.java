package com.freshcutz.service;

import com.freshcutz.dto.ProductListRow;
import com.freshcutz.entity.Product;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Adds many products at once from an Excel (.xlsx) sheet — the admin Products tab's "Import products".
 *
 * <p>Columns are found by their header names (any order, other columns are ignored):
 * Product Name, Description, Category, Size 1, Price 1, MRP 1, Size 2, Price 2, MRP 2, Show on shop.
 *
 * <p>Gentle on the database: the existing products are read once (names only, never the photos), and
 * the new ones are saved together in ONE transaction, placed after the existing products in the order of
 * the sheet. Products whose name already exists are skipped, so running the same file twice is harmless.
 * Photos are added afterwards by the admin page ("Add photos"), matched to the products by file name.
 */
@Service
public class ProductImportService {

    static final int MAX_ROWS = 500;

    private final ProductRepository productRepository;

    public ProductImportService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** What happened — all plain text/numbers so the admin page can show it as it is. */
    public static final class Result {
        public int created;
        public int skipped;
        public int hidden;
        public final List<String> errors = new ArrayList<>();
        public final List<String> skippedNames = new ArrayList<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("created", created);
            m.put("skipped", skipped);
            m.put("hidden", hidden);
            m.put("errors", errors);
            m.put("skippedNames", skippedNames);
            return m;
        }
    }

    /** The sample row in the downloadable template ("EXAMPLE - Chicken Curry Cut") is never imported. */
    private static final Pattern EXAMPLE_ROW = Pattern.compile("^example\\s*[-:\u2013\u2014]", Pattern.CASE_INSENSITIVE);

    /** A product as read from one sheet row, checked but not saved yet. */
    static final class Row {
        int number;
        String name, description, category, size1, size2;
        BigDecimal price1, mrp1, price2, mrp2;
        Boolean show; // null = the row doesn't say
    }

    /** Parses and checks the sheet without saving anything. Row problems are added to {@code result.errors}. */
    static List<Row> parse(InputStream file, Result result) {
        List<String[]> sheet;
        try {
            sheet = SimpleXlsxReader.readFirstSheet(file);
        } catch (IOException e) {
            throw new BadRequestException("That doesn't look like an Excel (.xlsx) file: " + e.getMessage());
        }
        int headerRow = -1;
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int r = 0; r < Math.min(sheet.size(), 10) && headerRow < 0; r++) {
            Map<String, Integer> found = new LinkedHashMap<>();
            String[] cells = sheet.get(r);
            for (int c = 0; c < cells.length; c++) {
                String key = headerKey(cells[c]);
                if (key != null && !found.containsKey(key)) found.put(key, c);
            }
            if (found.containsKey("name") && found.containsKey("size1") && found.containsKey("price1")) {
                headerRow = r;
                col = found;
            }
        }
        if (headerRow < 0) {
            throw new BadRequestException("The sheet needs a header row with at least: Product Name, Size 1 and Price 1.");
        }

        List<Row> rows = new ArrayList<>();
        for (int r = headerRow + 1; r < sheet.size(); r++) {
            String[] cells = sheet.get(r);
            String name = cell(cells, col.get("name"));
            if (EXAMPLE_ROW.matcher(name).find()) continue; // the template's sample row
            if (name.isEmpty()) {
                boolean blank = true;
                for (String c : cells) if (c != null && !c.isBlank()) blank = false;
                if (!blank) result.errors.add("Row " + (r + 1) + ": the product name is missing.");
                continue;
            }
            if (rows.size() >= MAX_ROWS) {
                result.errors.add("Only the first " + MAX_ROWS + " products are imported at a time — the rest were left out.");
                break;
            }
            Row row = new Row();
            row.number = r + 1;
            row.name = name;
            row.description = cell(cells, col.get("description"));
            row.category = cell(cells, col.get("category"));
            row.size1 = cell(cells, col.get("size1"));
            row.size2 = cell(cells, col.get("size2"));
            String label = "Row " + row.number + " (" + name + "): ";
            try {
                if (name.length() > 120) throw new IllegalArgumentException("the name is longer than 120 characters");
                if (row.description.length() > 500) throw new IllegalArgumentException("the description is longer than 500 characters");
                if (row.category.length() > 60) throw new IllegalArgumentException("the category is longer than 60 characters");
                if (row.size1.isEmpty()) throw new IllegalArgumentException("Size 1 is missing (for example 500 g)");
                if (row.size1.length() > 40 || row.size2.length() > 40) throw new IllegalArgumentException("a size is longer than 40 characters");
                row.price1 = money(cell(cells, col.get("price1")), "Price 1", true);
                row.mrp1 = money(cell(cells, col.get("mrp1")), "MRP 1", false);
                if (!row.size2.isEmpty()) {
                    row.price2 = money(cell(cells, col.get("price2")), "Price 2", true);
                    row.mrp2 = money(cell(cells, col.get("mrp2")), "MRP 2", false);
                }
                row.show = yesNo(cell(cells, col.get("show")));
            } catch (IllegalArgumentException e) {
                result.errors.add(label + e.getMessage() + ".");
                continue;
            }
            rows.add(row);
        }
        return rows;
    }

    /** Saves the products from the sheet. {@code showOnShop}: the admin's tick box; a row that says "No" is hidden anyway. */
    @Transactional
    public Result importProducts(InputStream file, boolean showOnShop) {
        Result result = new Result();
        List<Row> rows = parse(file, result);
        if (rows.isEmpty() && result.errors.isEmpty()) {
            throw new BadRequestException("The sheet has no products in it.");
        }

        Set<String> known = new HashSet<>();
        int maxOrder = -1;
        for (ProductListRow p : productRepository.listViewForAdmin()) {
            known.add(nameKey(p.getName()));
            maxOrder = Math.max(maxOrder, p.getDisplayOrder());
        }

        List<Product> toSave = new ArrayList<>();
        for (Row row : rows) {
            String key = nameKey(row.name);
            if (!known.add(key)) { // already in the shop, or twice in this sheet
                result.skipped++;
                result.skippedNames.add(row.name);
                continue;
            }
            boolean visible = showOnShop && (row.show == null || row.show);
            Product p = new Product();
            p.setName(row.name);
            p.setDescription(row.description.isEmpty() ? null : row.description);
            p.setCategory(row.category.isEmpty() ? null : row.category);
            p.setVariant1Label(row.size1);
            p.setVariant1Price(row.price1);
            p.setVariant1Mrp(row.mrp1);
            if (!row.size2.isEmpty()) {
                p.setVariant2Label(row.size2);
                p.setVariant2Price(row.price2);
                p.setVariant2Mrp(row.mrp2);
            }
            p.setEnabled(visible);
            p.setOutOfStock(false);
            p.setDisplayOrder(++maxOrder);
            if (!visible) result.hidden++;
            toSave.add(p);
        }
        if (!toSave.isEmpty()) productRepository.saveAll(toSave);
        result.created = toSave.size();
        return result;
    }

    // ---- helpers -------------------------------------------------------

    private static String cell(String[] cells, Integer index) {
        if (index == null || index >= cells.length || cells[index] == null) return "";
        return cells[index].replace(' ', ' ').trim();
    }

    static String nameKey(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Header text -> our key, forgiving about case, spaces, "(₹)" and similar. */
    static String headerKey(String header) {
        if (header == null) return null;
        String h = header.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)", "").replaceAll("[^a-z0-9]+", " ").trim();
        switch (h) {
            case "product name": case "name": case "product": case "item": case "item name": return "name";
            case "description": case "details": return "description";
            case "category": return "category";
            case "size 1": case "size1": case "variant 1": case "weight 1": case "pack 1": return "size1";
            case "price 1": case "price1": case "selling price 1": return "price1";
            case "mrp 1": case "mrp1": return "mrp1";
            case "size 2": case "size2": case "variant 2": case "weight 2": case "pack 2": return "size2";
            case "price 2": case "price2": case "selling price 2": return "price2";
            case "mrp 2": case "mrp2": return "mrp2";
            case "show on shop": case "show": case "visible": case "live": case "enabled": return "show";
            default: return null;
        }
    }

    private static BigDecimal money(String text, String what, boolean required) {
        String t = text.replace("₹", "").replace("Rs.", "").replace("Rs", "").replace(",", "").trim();
        if (t.isEmpty()) {
            if (required) throw new IllegalArgumentException(what + " is missing");
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(t);
            if (v.signum() < 0) throw new IllegalArgumentException(what + " can't be negative");
            if (v.scale() > 2) v = v.setScale(2, java.math.RoundingMode.HALF_UP);
            if (v.compareTo(new BigDecimal("99999999")) > 0) throw new IllegalArgumentException(what + " is too large");
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " \"" + text + "\" is not a number");
        }
    }

    private static Boolean yesNo(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        if (t.equals("yes") || t.equals("y") || t.equals("true") || t.equals("1") || t.equals("show") || t.equals("live")) return Boolean.TRUE;
        if (t.equals("no") || t.equals("n") || t.equals("false") || t.equals("0") || t.equals("hide") || t.equals("hidden")) return Boolean.FALSE;
        throw new IllegalArgumentException("Show on shop must be Yes or No, not \"" + text + "\"");
    }
}
