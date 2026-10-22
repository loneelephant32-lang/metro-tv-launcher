package com.metro.launcher;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Windows 8 start-screen packing: tiles flow top to bottom through columns that are two medium tiles
 * wide (one wide tile, or a large 2x2), and groups are separated by a wider gap.
 */
public class TileGrid extends ViewGroup {
    public static class LP extends ViewGroup.LayoutParams {
        int w, h, col, row;
        boolean newGroup;
        LP(int w, int h) { super(0, 0); this.w = w; this.h = h; }
    }

    final int rows, cellW, cellH, gap, groupGap;
    private int[] colX = new int[0];

    public TileGrid(Context c, int rows, float cellWdp, float cellHdp, float gapDp) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        this.rows = rows;
        cellW = Math.round(cellWdp * d);
        cellH = Math.round(cellHdp * d);
        gap = Math.round(gapDp * d);
        groupGap = Math.round(44 * d);
        setClipChildren(false);
        setClipToPadding(false);
        setChildrenDrawingOrderEnabled(true);
    }

    /** The focused tile draws last so its glow lies over its neighbours. */
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

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int n = getChildCount(), maxCols = n * 2 + 4;
        boolean[][] used = new boolean[rows][maxCols];
        boolean[] groupStart = new boolean[maxCols + 1];
        int block0 = 0, maxCol = 0;
        for (int i = 0; i < n; i++) {
            LP lp = (LP) getChildAt(i).getLayoutParams();
            if (lp.newGroup && i > 0) {
                block0 = (maxCol + 1) / 2;
                groupStart[block0 * 2] = true;
            }
            place:
            for (int b = block0; ; b++) {
                for (int r = 0; r + lp.h <= rows; r++) {
                    for (int c = b * 2; c + lp.w <= b * 2 + 2; c++) {
                        if (fits(used, r, c, lp.w, lp.h)) {
                            for (int rr = r; rr < r + lp.h; rr++)
                                for (int cc = c; cc < c + lp.w; cc++) used[rr][cc] = true;
                            lp.col = c;
                            lp.row = r;
                            maxCol = Math.max(maxCol, c + lp.w);
                            break place;
                        }
                    }
                }
            }
        }
        colX = new int[maxCol + 1];
        int x = 0;
        for (int c = 0; c <= maxCol; c++) {
            if (c > 0) x += groupStart[c] ? groupGap : gap;
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
    }
}
