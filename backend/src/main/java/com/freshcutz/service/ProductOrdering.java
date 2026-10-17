package com.freshcutz.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out the new "display_order" numbers when one product is placed at a position INSIDE its category.
 *
 * <p>Only the products of that one category are looked at. They already own a set of display_order
 * numbers (their "slots"); the same slots are simply handed out again in the new order. So products of
 * other categories are never touched, and the order of the category chips in the shop does not move.
 *
 * <p>Pure logic (no database) so it can be tested on its own.
 */
final class ProductOrdering {

    private ProductOrdering() {}

    /** A product of the category with its current display_order. */
    record Member(long id, int order) {}

    /** What to write: only the products whose number really changes. {@code movedOrder} = the moved product's final number. */
    record Plan(Map<Long, Integer> changes, int movedOrder) {}

    /**
     * @param members  every product of the category INCLUDING the moved one, sorted by (display_order, id)
     * @param movedId  the product that is being placed
     * @param position 1 = first. Null, 0 or negative = last. Bigger than the count = last.
     */
    static Plan plan(List<Member> members, long movedId, Integer position) {
        Member moved = null;
        List<Member> others = new ArrayList<>();
        for (Member m : members) {
            if (m.id() == movedId) moved = m;
            else others.add(m);
        }
        if (moved == null) {
            return new Plan(new LinkedHashMap<>(), 0);
        }

        int[] slots = new int[members.size()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = members.get(i).order();
            if (i > 0 && slots[i] <= slots[i - 1]) slots[i] = slots[i - 1] + 1; // equal numbers would hide a move
        }

        int index = (position == null || position <= 0) ? others.size() : Math.min(position - 1, others.size());
        List<Member> ordered = new ArrayList<>(others);
        ordered.add(index, moved);

        Map<Long, Integer> changes = new LinkedHashMap<>();
        int movedOrder = moved.order();
        for (int i = 0; i < ordered.size(); i++) {
            Member m = ordered.get(i);
            if (m.id() == movedId) movedOrder = slots[i];
            if (m.order() != slots[i]) changes.put(m.id(), slots[i]);
        }
        return new Plan(changes, movedOrder);
    }
}
