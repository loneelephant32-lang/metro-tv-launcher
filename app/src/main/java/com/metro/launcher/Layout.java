package com.metro.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where every tile sits on Start: a free grid of cells, a fixed number of rows high and as wide as it
 * needs to be. Tiles keep their own cell positions, so a wide tile can move past one small tile at a
 * time, and a new tile drops into the first gap. Pure logic, no views.
 */
final class Layout {
    static final class Tile {
        final String key;
        int c, r, w, h;

        Tile(String key, int c, int r, int w, int h) {
            this.key = key;
            this.c = c;
            this.r = r;
            this.w = w;
            this.h = h;
        }
    }

    final int rows;
    final List<Tile> tiles = new ArrayList<>();

    Layout(int rows) { this.rows = rows; }

    Tile find(String key) {
        for (Tile t : tiles) if (t.key.equals(key)) return t;
        return null;
    }

    static boolean overlaps(Tile a, int c, int r, int w, int h) {
        return a.c < c + w && c < a.c + a.w && a.r < r + h && r < a.r + a.h;
    }

    static boolean overlaps(Tile a, Tile b) { return overlaps(a, b.c, b.r, b.w, b.h); }

    /** True when the area is inside the rows and no tile other than those in {@code ignore} covers it. */
    boolean free(int c, int r, int w, int h, Set<Tile> ignore) {
        if (c < 0 || r < 0 || r + h > rows) return false;
        for (Tile t : tiles) if (!ignore.contains(t) && overlaps(t, c, r, w, h)) return false;
        return true;
    }

    /** The first gap that fits, column by column from the left: new tiles fill holes before growing Start. */
    int[] firstFit(int w, int h) {
        h = Math.min(h, rows);
        for (int c = 0; ; c++) {
            for (int r = 0; r + h <= rows; r++) if (free(c, r, w, h, Collections.emptySet())) return new int[]{c, r};
        }
    }

    Tile add(String key, int w, int h) {
        int[] at = firstFit(w, h);
        Tile t = new Tile(key, at[0], at[1], w, Math.min(h, rows));
        tiles.add(t);
        return t;
    }

    void remove(String key) {
        Tile t = find(key);
        if (t != null) tiles.remove(t);
        compact();
    }

    /**
     * Moves a tile one step. Sideways it trades places with whatever is right next to it in its rows, so
     * a wide tile passes one small tile per press; up or down it swaps rows with what's there. Anything
     * still in the way drops into the nearest gap, or else shifts right. False when nothing changed.
     */
    boolean move(Tile t, int dx, int dy) {
        String before = signature();
        Set<Tile> fixed = new HashSet<>();
        fixed.add(t);
        if (dy != 0) {
            int nr = t.r + dy;
            if (nr < 0 || nr + t.h > rows) return false;
            t.r = nr;
            for (Tile d : new ArrayList<>(tiles)) {
                if (d == t || !overlaps(d, t)) continue;
                int dr = d.r - dy * t.h;
                if (dr >= 0 && dr + d.h <= rows) { d.r = dr; fixed.add(d); }
            }
        } else {
            if (dx < 0 && t.c == 0) return false;
            int col = dx < 0 ? t.c - 1 : t.c + t.w;
            List<Tile> next = new ArrayList<>();
            for (Tile d : tiles) if (d != t && overlaps(d, col, t.r, 1, t.h)) next.add(d);
            if (next.isEmpty()) {
                t.c += dx;
            } else if (dx < 0) {
                int nc = Integer.MAX_VALUE;
                for (Tile d : next) nc = Math.min(nc, d.c);
                for (Tile d : next) { d.c += t.w; fixed.add(d); }
                t.c = nc;
            } else {
                int end = 0;
                for (Tile d : next) end = Math.max(end, d.c + d.w);
                for (Tile d : next) { d.c -= t.w; fixed.add(d); }
                t.c = end - t.w;
            }
        }
        resolve(fixed);
        compact();
        return !signature().equals(before);
    }

    /**
     * Settles overlaps after tiles in {@code fixed} were placed: each tile in their way goes to the
     * nearest free spot close by, or failing that shifts right (and so on down the line).
     */
    void resolve(Set<Tile> fixed) {
        while (true) {
            Tile d = null;
            for (Tile t : tiles) {
                if (fixed.contains(t)) continue;
                for (Tile f : fixed) {
                    if (overlaps(t, f) && (d == null || t.c < d.c || t.c == d.c && t.r < d.r)) { d = t; break; }
                }
            }
            if (d == null) return;
            Set<Tile> self = Collections.singleton(d);
            int bestC = -1, bestR = -1, bestScore = Integer.MAX_VALUE, reach = Math.max(2, d.w) + 1;
            for (int c = Math.max(0, d.c - reach); c <= d.c + reach; c++) {
                for (int r = 0; r + d.h <= rows; r++) {
                    if (!free(c, r, d.w, d.h, self)) continue;
                    int score = Math.abs(c - d.c) * 2 + Math.abs(r - d.r) * 3;
                    if (score < bestScore) { bestScore = score; bestC = c; bestR = r; }
                }
            }
            if (bestC >= 0) {
                d.c = bestC;
                d.r = bestR;
            } else {
                int c = d.c;
                while (true) {
                    boolean clear = true;
                    for (Tile f : fixed) if (overlaps(f, c, d.r, d.w, d.h)) { clear = false; break; }
                    if (clear) break;
                    c++;
                }
                d.c = c;
            }
            fixed.add(d);
        }
    }

    /**
     * Puts things right after tiles changed size: the tile {@code first} keeps its place and the others
     * make room. Tall tiles are kept within the rows.
     */
    void repair(String first) {
        List<Tile> order = new ArrayList<>(tiles);
        Collections.sort(order, (a, b) -> a.c != b.c ? a.c - b.c : a.r - b.r);
        Tile p = first == null ? null : find(first);
        if (p != null) { order.remove(p); order.add(0, p); }
        Set<Tile> fixed = new HashSet<>();
        for (Tile t : order) {
            t.h = Math.min(t.h, rows);
            if (t.r + t.h > rows) t.r = rows - t.h;
            fixed.add(t);
            resolve(fixed);
        }
        compact();
    }

    /** Closes up columns nothing covers, so Start never shows an empty stripe. */
    void compact() {
        int max = 0;
        for (Tile t : tiles) max = Math.max(max, t.c + t.w);
        for (int c = 0; c < max; c++) {
            boolean used = false;
            for (Tile t : tiles) if (t.c <= c && c < t.c + t.w) { used = true; break; }
            if (used) continue;
            for (Tile t : tiles) if (t.c > c) t.c--;
            max--;
            c--;
        }
    }

    /** Tiles left to right, top to bottom: the order Start lists them in. */
    List<Tile> ordered() {
        List<Tile> out = new ArrayList<>(tiles);
        Collections.sort(out, (a, b) -> a.c != b.c ? a.c - b.c : a.r - b.r);
        return out;
    }

    String signature() {
        StringBuilder s = new StringBuilder();
        for (Tile t : ordered()) s.append(t.key).append(':').append(t.c).append(':').append(t.r).append(';');
        return s.toString();
    }

    /** "key:col:row" entries, as stored in prefs. */
    String save() {
        StringBuilder s = new StringBuilder();
        for (Tile t : ordered()) {
            if (s.length() > 0) s.append(',');
            s.append(t.key).append(':').append(t.c).append(':').append(t.r);
        }
        return s.toString();
    }
}
