package com.susinstantswap.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.susinstantswap.config.SwapConfigAdapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.Slot;

/**
 * Thin green groove indicators on both sides of the player-inventory
 * rows.  Hovering any groove and holding E swaps that entire row with
 * the hotbar.  Rows are detected dynamically from the menu slot layout.
 */
public class RowArrowWidget {

    public static final int ROW_COUNT = 3;

    private static final int TRIGGER_RIGHT_OFFSET = -1;
    private static final int TRIGGER_RIGHT_W      = 8;
    private static final int TRIGGER_LEFT_OFFSET  = -1;
    private static final int TRIGGER_LEFT_W       = 8;
    private static final int TRIGGER_H            = 17;

    private static final int GROOVE_W      = 1;
    private static final int GROOVE_HEIGHT = 14;
    private static final int GROOVE_Y_OFF  = 1;

    private static final int GROOVE_DEFAULT = 0x70003300;
    private static final int GROOVE_HOVERED = 0xC000FF00;

    public static boolean visible    = false;
    public static int     hoveredRow = -1;

    private static final int[] rowY          = new int[ROW_COUNT];
    private static final int[] rowSlotLeft   = new int[ROW_COUNT];
    private static final int[] rowSlotRight  = new int[ROW_COUNT];
    private static final int[][] rowSlots    = new int[ROW_COUNT][9];
    private static int panelLeft;
    private static boolean rowsDetected = false;

    private static SwapConfigAdapter config;

    public static void init(SwapConfigAdapter cfg) { config = cfg; }

    public static void detectRows(AbstractContainerScreen<?> screen, LocalPlayer player) {
        rowsDetected = false;

        panelLeft  = screen.getLeftPos();
        int top    = screen.getTopPos();

        // Standard detection: containerSlot [9,36) from player.getInventory()
        Map<Integer, Integer> slotToMenu = new HashMap<>();
        Map<Integer, List<Slot>> byY = new LinkedHashMap<>();
        int menuIdx = 0;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == player.getInventory()
                    && slot.getContainerSlot() >= 9
                    && slot.getContainerSlot() < 36) {
                slotToMenu.put(slot.getContainerSlot(), menuIdx);
                byY.computeIfAbsent(slot.y, k -> new ArrayList<>()).add(slot);
            }
            menuIdx++;
        }

        // Position-based fallback for backpack mods
        boolean positionBased = false;
        if (byY.isEmpty() && BackpackScreenMatcher.needsPositionBasedRows(screen)) {
            byY.clear();
            for (Slot slot : screen.getMenu().slots) {
                byY.computeIfAbsent(slot.y, k -> new ArrayList<>()).add(slot);
            }
            List<List<Slot>> qualifying = new ArrayList<>();
            for (List<Slot> row : byY.values()) {
                if (row.size() == 9) qualifying.add(row);
            }
            qualifying.sort((a, b) -> Integer.compare(b.get(0).y, a.get(0).y));
            byY.clear();
            int start = qualifying.size() > ROW_COUNT ? 1 : 0;
            int end = Math.min(start + ROW_COUNT, qualifying.size());
            for (int i = start; i < end; i++) {
                List<Slot> row = qualifying.get(i);
                byY.put(row.get(0).y, row);
            }
            positionBased = true;
        }

        int idx = 0;
        for (List<Slot> row : byY.values()) {
            if (row.size() != 9 || idx >= ROW_COUNT) continue;

            row.sort((a, b) -> Integer.compare(a.getContainerSlot(), b.getContainerSlot()));

            int minX = Integer.MAX_VALUE, maxX = 0;
            boolean rowValid = true;
            for (int c = 0; c < 9; c++) {
                Slot s = row.get(c);
                if (s.x < minX) minX = s.x;
                if (s.x > maxX) maxX = s.x;
                Integer menuI = slotToMenu.get(s.getContainerSlot());
                if (menuI == null) {
                    menuI = positionBased ? s.index : null;
                }
                if (menuI == null) { rowValid = false; break; }
                rowSlots[idx][c] = menuI;
            }
            if (!rowValid) continue;

            rowY[idx]          = top + row.get(0).y;
            rowSlotLeft[idx]   = panelLeft + minX;
            rowSlotRight[idx]  = panelLeft + maxX + 18;
            idx++;
        }
        rowsDetected = (idx == ROW_COUNT);
    }

    /** Menu slot index for a given row + column. */
    public static int rowSlotIndex(int row, int col) {
        return rowsDetected ? rowSlots[row][col] : 9 + row * 9 + col;
    }

    private static boolean isHoveringRight(int row, double mx, double my) {
        int tx = rowSlotRight[row] + TRIGGER_RIGHT_OFFSET;
        int ty = rowY[row];
        return mx >= tx && mx < tx + TRIGGER_RIGHT_W
            && my >= ty && my < ty + TRIGGER_H;
    }

    private static boolean isHoveringLeft(int row, double mx, double my) {
        int rx = rowSlotLeft[row] + TRIGGER_LEFT_OFFSET;
        int lx = rx - TRIGGER_LEFT_W;
        int ty = rowY[row];
        return mx >= lx && mx < rx
            && my >= ty && my < ty + TRIGGER_H;
    }

    public static void checkHover(double mouseX, double mouseY) {
        int prev = hoveredRow;
        hoveredRow = -1;
        if (!visible || !rowsDetected) return;
        for (int r = 0; r < ROW_COUNT; r++) {
            if (isHoveringLeft(r, mouseX, mouseY)
                    || isHoveringRight(r, mouseX, mouseY)) {
                hoveredRow = r;
                break;
            }
        }
        if (hoveredRow >= 0 && prev != hoveredRow) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && config != null && config.soundEnabled()) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.3f, 1.8f);
            }
        }
    }

    public static void render(Minecraft mc, GuiGraphicsExtractor g) {
        if (!visible || !rowsDetected) return;
        for (int r = 0; r < ROW_COUNT; r++) {
            boolean hovered = (hoveredRow == r);
            int color = hovered ? GROOVE_HOVERED : GROOVE_DEFAULT;
            int gy = rowY[r] + GROOVE_Y_OFF;
            g.fill(rowSlotRight[r], gy,
                   rowSlotRight[r] + GROOVE_W, gy + GROOVE_HEIGHT, color);
            int lx = rowSlotLeft[r] - 3;
            g.fill(lx, gy, lx + GROOVE_W, gy + GROOVE_HEIGHT, color);
        }
    }
}
