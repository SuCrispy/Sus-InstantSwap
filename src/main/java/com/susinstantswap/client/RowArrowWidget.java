package com.susinstantswap.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.susinstantswap.SwapLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

    private static final int TRIGGER_OVERLAP = 1;
    private static final int TRIGGER_H       = 17;

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
    /** Menu slot indices for each row, sorted left→right (col 0..8). */
    private static final int[][] rowSlots    = new int[ROW_COUNT][9];
    private static int panelLeft, panelRight;
    private static boolean rowsDetected = false;

    public static void detectRows(AbstractContainerScreen<?> screen, LocalPlayer player) {
        rowsDetected = false;

        panelLeft  = screen.getGuiLeft();
        int top    = screen.getGuiTop();
        panelRight = panelLeft + screen.getXSize();

        // containerSlot (9-35) → menu index map (built while iterating menu)
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

        int idx = 0;
        for (List<Slot> row : byY.values()) {
            if (row.size() != 9 || idx >= ROW_COUNT) continue;

            // Sort by containerSlot so col 0 = leftmost column
            row.sort((a, b) -> Integer.compare(a.getContainerSlot(), b.getContainerSlot()));

            int minX = Integer.MAX_VALUE, maxX = 0;
            for (int c = 0; c < 9; c++) {
                Slot s = row.get(c);
                if (s.x < minX) minX = s.x;
                if (s.x > maxX) maxX = s.x;
                rowSlots[idx][c] = slotToMenu.get(s.getContainerSlot());
            }

            rowY[idx]          = top + row.get(0).y;
            rowSlotLeft[idx]   = panelLeft + minX;
            rowSlotRight[idx]  = panelLeft + maxX + 18;
            idx++;
        }
        rowsDetected = (idx == ROW_COUNT);
        if (SwapLog.shouldDebug()) {
            SwapLog.debug("detectRows: found={}/{} screen={} left={} top={}",
                    idx, ROW_COUNT, screen.getClass().getSimpleName(), panelLeft, top);
            for (int i = 0; i < idx; i++) {
                SwapLog.debug("  row[{}]: y={} slotL={} slotR={} slots=[{},{},{},{},{},{},{},{},{}]",
                        i, rowY[i], rowSlotLeft[i], rowSlotRight[i],
                        rowSlots[i][0], rowSlots[i][1], rowSlots[i][2],
                        rowSlots[i][3], rowSlots[i][4], rowSlots[i][5],
                        rowSlots[i][6], rowSlots[i][7], rowSlots[i][8]);
            }
        }
    }

    /** Menu slot index for a given row + column. */
    public static int rowSlotIndex(int row, int col) {
        return rowsDetected ? rowSlots[row][col] : 9 + row * 9 + col;
    }

    private static boolean isHoveringRight(int row, double mx, double my) {
        int tx = rowSlotRight[row] - TRIGGER_OVERLAP;
        int ty = rowY[row];
        return mx >= tx && mx < panelRight + 2
            && my >= ty && my < ty + TRIGGER_H;
    }

    private static boolean isHoveringLeft(int row, double mx, double my) {
        int tx = panelLeft - 2;
        int tw = rowSlotLeft[row] - panelLeft + 1;
        int ty = rowY[row];
        return mx >= tx && mx < tx + tw
            && my >= ty && my < ty + TRIGGER_H;
    }

    public static void checkHover(double mouseX, double mouseY) {
        int prev = hoveredRow;
        hoveredRow = -1;
        if (!visible || !rowsDetected) {
            return;
        }
        for (int r = 0; r < ROW_COUNT; r++) {
            if (isHoveringLeft(r, mouseX, mouseY)
                    || isHoveringRight(r, mouseX, mouseY)) {
                hoveredRow = r;
                break;
            }
        }
        if (hoveredRow != prev && SwapLog.shouldDebug()) {
            SwapLog.debug("hover: {}→{}", prev, hoveredRow);
        }
        if (hoveredRow >= 0 && prev != hoveredRow) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null
                    && com.susinstantswap.SusInstantSwapMod.CONFIG != null
                    && com.susinstantswap.SusInstantSwapMod.CONFIG.soundEnabled.get()) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.3f, 1.8f);
            }
        }
    }

    public static void render(Minecraft mc, GuiGraphics g) {
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