package com.voxelmind.local.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Click targets registered while painting, at exactly the coordinates that were drawn, so drawing and
 * hit-testing can never disagree. The last region drawn is on top and wins.
 */
public final class ClickRegions {
    private record Region(int x1, int y1, int x2, int y2, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x1 && mx < x2 && my >= y1 && my < y2;
        }
    }

    private final List<Region> regions = new ArrayList<>();

    public void clear() {
        regions.clear();
    }

    public void add(int x, int y, int w, int h, Runnable action) {
        regions.add(new Region(x, y, x + w, y + h, action));
    }

    public boolean dispatch(double mouseX, double mouseY) {
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (r.contains(mouseX, mouseY)) {
                r.action().run();
                return true;
            }
        }
        return false;
    }

    public static boolean hover(int x, int y, int w, int h, int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
