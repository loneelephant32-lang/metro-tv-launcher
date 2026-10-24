package com.metro.launcher;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Windows 8 start-screen packing: tiles flow top to bottom through columns that are two medium tiles
 * wide (one wide tile, or a large 2x2).
 */
public class TileGrid extends ViewGroup {
    public static class LP extends ViewGroup.LayoutParams {
        int w, h, col, row;
        LP(int w, int h) { super(0, 0); this.w = w; this.h = h; }
    }

    final int rows, cellW, cellH, gap;
    private int[] colX = new int[0];

    public TileGrid(Context c, int rows, float cellWdp, float cellHdp, float gapDp) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        this.rows = rows;
        cellW = Math.round(cellWdp * d);
        cellH = Math.round(cellHdp * d);
        gap = Math.round(gapDp * d);
        setClipChildren(false);
        setClipToPadding(false);
        setChildrenDrawingOrderEnabled(true);
    }

    /** The focused tile draws last so its enlarged outline lies over its neighbours. */
    @Override
    protected int getChildDrawingOrder(int count, int i) {
        View f = getFocusedChild();
        int fi = f == null ? -1 : indexOfChild(f);
        if (fi < 0) return i;
        if (i == count - 1) return fi;
        return i >= fi ? i + 1 : i;
    }

    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof LP; }
    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() { return new LP(1, 1); }

    /**
     * Where tiles of the given sizes land, in order: {col, row} for each. Kept free of views so a move
     * can try out every position before committing to one.
     */
    static int[][] pack(int[] w, int[] h, int rows) {
        int n = w.length;
        boolean[][] used = new boolean[rows][n * 2 + 4];
        int[][] at = new int[n][];
        for (int i = 0; i < n; i++) {
            place:
            for (int b = 0; ; b++) {
                for (int r = 0; r + h[i] <= rows; r++) {
                    for (int c = b * 2; c + w[i] <= b * 2 + 2; c++) {
                        if (fits(used, r, c, w[i], h[i])) {
                            for (int rr = r; rr < r + h[i]; rr++)
                                for (int cc = c; cc < c + w[i]; cc++) used[rr][cc] = true;
                            at[i] = new int[]{c, r};
                            break place;
                        }
                    }
                }
            }
        }
        return at;
    }

    /** Puts a child at another index without detaching its focus, then lets the next layout glide everything there. */
    void moveChild(View v, int to) {
        int from = indexOfChild(v);
        if (from < 0 || from == to) return;
        snapshot();
        detachViewFromParent(from);
        attachViewToParent(v, to, v.getLayoutParams());
        requestLayout();
        invalidate();
    }

    private int[][] before;

    private void snapshot() {
        before = new int[getChildCount()][];
        for (int i = 0; i < getChildCount(); i++) before[i] = new int[]{getChildAt(i).getLeft(), getChildAt(i).getTop()};
        snapped = new View[getChildCount()];
        for (int i = 0; i < getChildCount(); i++) snapped[i] = getChildAt(i);
    }

    private View[] snapped;

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int n = getChildCount();
        int[] w = new int[n], h = new int[n];
        for (int i = 0; i < n; i++) {
            LP lp = (LP) getChildAt(i).getLayoutParams();
            w[i] = lp.w;
            h[i] = lp.h;
        }
        int[][] at = pack(w, h, rows);
        int maxCol = 0;
        for (int i = 0; i < n; i++) {
            LP lp = (LP) getChildAt(i).getLayoutParams();
            lp.col = at[i][0];
            lp.row = at[i][1];
            maxCol = Math.max(maxCol, lp.col + lp.w);
        }
        colX = new int[maxCol + 1];
        int x = 0;
        for (int c = 0; c <= maxCol; c++) {
            if (c > 0) x += gap;
            colX[c] = x;
            x += cellW;
        }
        for (int i = 0; i < n; i++) {
            View v = getChildAt(i);
            LP lp = (LP) v.getLayoutParams();
            v.measure(MeasureSpec.makeMeasureSpec(lp.w * cellW + (lp.w - 1) * gap, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(lp.h * cellH + (lp.h - 1) * gap, MeasureSpec.EXACTLY));
        }
        int width = maxCol == 0 ? 0 : colX[maxCol - 1] + cellW;
        setMeasuredDimension(width + getPaddingLeft() + getPaddingRight(),
                rows * cellH + (rows - 1) * gap + getPaddingTop() + getPaddingBottom());
    }

    private static boolean fits(boolean[][] used, int r, int c, int w, int h) {
        for (int rr = r; rr < r + h; rr++)
            for (int cc = c; cc < c + w; cc++) if (used[rr][cc]) return false;
        return true;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            LP lp = (LP) v.getLayoutParams();
            int x = getPaddingLeft() + colX[lp.col], y = getPaddingTop() + lp.row * (cellH + gap);
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
        if (snapped != null) {
            // Slide every tile that changed place from where it was, like Windows 8 tiles making room.
            for (int i = 0; i < snapped.length; i++) {
                View v = snapped[i];
                if (v.getParent() != this) continue;
                float dx = before[i][0] - v.getLeft(), dy = before[i][1] - v.getTop();
                if (dx == 0 && dy == 0) continue;
                v.setTranslationX(v.getTranslationX() + dx);
                v.setTranslationY(v.getTranslationY() + dy);
                v.animate().translationX(0).translationY(0).setDuration(220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            }
            snapped = null;
            before = null;
        }
    }
}
