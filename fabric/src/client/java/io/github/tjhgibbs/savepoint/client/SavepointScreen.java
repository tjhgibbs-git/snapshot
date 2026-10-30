package io.github.tjhgibbs.savepoint.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads.Entry;
import io.github.tjhgibbs.savepoint.mod.SavepointPayloads.ListPayload;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The main screen. One list (newest first) with a detail panel for the selected savepoint, a create row on top and
 * housekeeping in the footer:
 *
 * <pre>
 * +----------------------------------------------------------+
 * |                 Savepoints · My World                    |
 * | [ Name (optional)                ] [Create] [Quick save] |
 * | Using 1.2 GB of 10 GB (12%)            Full backup: none |
 * | [=====---------------------------------------------]     |
 * | +----------------------------+ +----------------------+ |
 * | | [img] Before the boss  *   | |  thumbnail            | |
 * | | [img] 3 min ago . 1.2 MB   | |  name, age, size      | |
 * | | ...                        | |  [Revert]             | |
 * | |                            | |  [Pin] [Rename]       | |
 * | |                            | |  [Delete]             | |
 * | +----------------------------+ +----------------------+ |
 * |      [Full backup now]   [Settings...]   [Done]          |
 * +----------------------------------------------------------+
 * </pre>
 *
 * Data comes from {@link ClientState}; every action is a C2S payload and the server answers with a fresh list.
 * Buttons that the server would refuse (cooldown, backup running, revert in progress) are disabled with the reason
 * in a tooltip, and Create/Revert show the remaining cooldown.
 */
public final class SavepointScreen extends Screen {
    private static final int PAD = 6;
    private static final int ROW_H = 33;
    private static final int THUMB_W = 48, THUMB_H = 27;
    private static final int MAX_CONTENT_W = 560;
    private static final int SCROLLBAR = 4;

    private final Screen parent;

    private EditBox nameBox;
    private Button createBtn, quickBtn, revertBtn, pinBtn, renameBtn, deleteBtn, backupBtn, settingsBtn;

    private int seenVersion = -1;
    private List<Entry> entries = List.of();
    private String selectedId;
    private int scroll;
    private boolean draggingScrollbar;
    private int ticks;

    // layout, set in layout()
    private int left, contentW, listX, listY, listW, listH, detX, detW, usageY;
    private int detButtonsTop;

    public SavepointScreen(Screen parent) {
        super(Component.translatable("savepoint.screen.title"));
        this.parent = parent;
    }

    // ---- layout ------------------------------------------------------------------------------------

    private void layout() {
        contentW = Math.min(width - PAD * 2, MAX_CONTENT_W);
        left = (width - contentW) / 2;
        detW = Math.clamp(contentW * 9 / 20, 140, 230);
        listW = contentW - detW - PAD;
        listX = left;
        detX = left + listW + PAD;
        usageY = 48;
        listY = usageY + 20;
        listH = Math.max(ROW_H, height - listY - 32);
    }

    @Override
    protected void init() {
        layout();
        String typed = nameBox == null ? "" : nameBox.getValue();
        // Wide enough for every label the button cycles through ("Create (10s)", "Saving..."), so none is clipped.
        int btnQuickW = Math.clamp(widest("savepoint.button.quick", "savepoint.button.quick_wait") + 12, 70, 110);
        int btnCreateW = Math.clamp(widest("savepoint.button.create", "savepoint.button.create_wait", "savepoint.button.saving") + 12, 56, 110);
        int boxW = contentW - btnQuickW - btnCreateW - 8;
        nameBox = new EditBox(font, left, 22, boxW, 20, Component.translatable("savepoint.field.name"));
        nameBox.setMaxLength(100);
        nameBox.setValue(typed);
        nameBox.setHint(Component.translatable("savepoint.field.name_hint"));
        addRenderableWidget(nameBox);
        createBtn = addRenderableWidget(Button.builder(Component.translatable("savepoint.button.create"), b -> create(false))
                .bounds(left + boxW + 4, 22, btnCreateW, 20).build());
        quickBtn = addRenderableWidget(Button.builder(Component.translatable("savepoint.button.quick"), b -> create(true))
                .bounds(left + boxW + btnCreateW + 8, 22, btnQuickW, 20).build());

        detButtonsTop = listY + listH - 3 * 20 - 2 * 2;
        int half = (detW - 2) / 2;
        revertBtn = addRenderableWidget(Button.builder(Component.translatable("savepoint.button.revert"), b -> revert())
                .bounds(detX, detButtonsTop, detW, 20).build());
        pinBtn = addRenderableWidget(Button.builder(Component.translatable("savepoint.button.pin"), b -> togglePin())
                .bounds(detX, detButtonsTop + 22, half, 20).build());
        renameBtn = addRenderableWidget(Button.builder(Component.translatable("savepoint.button.rename"), b -> rename())
                .bounds(detX + detW - half, detButtonsTop + 22, half, 20).build());
        deleteBtn = addRenderableWidget(Button.builder(Component.translatable("savepoint.button.delete"), b -> delete())
                .bounds(detX, detButtonsTop + 44, detW, 20).build());

        int fy = height - 26, fw = (contentW - 8) / 3;
        backupBtn = addRenderableWidget(Button.builder(Component.translatable("savepoint.button.backup"), b -> {
            ClientPlayNetworking.send(new SavepointPayloads.BackupPayload());
        }).bounds(left, fy, fw, 20).build());
        settingsBtn = addRenderableWidget(Button.builder(Component.translatable("savepoint.button.settings"),
                b -> minecraft.setScreenAndShow(new SavepointSettingsScreen(this))).bounds(left + fw + 4, fy, fw, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(left + (fw + 4) * 2, fy, contentW - (fw + 4) * 2, 20).build());

        setInitialFocus(nameBox);
        seenVersion = -1; // re-read the list into the new layout
        refreshFromState();
        updateButtons();
        if (ticks == 0) ClientState.requestList(); // on open only, not on every window resize
    }

    /** Widest of the given labels, with a two-digit countdown filled in. */
    private int widest(String... keys) {
        int w = 0;
        for (String k : keys) w = Math.max(w, font.width(Component.translatable(k, 88)));
        return w;
    }

    // ---- data ----------------------------------------------------------------------------------------

    private void refreshFromState() {
        if (seenVersion == ClientState.version()) return;
        seenVersion = ClientState.version();
        ListPayload l = ClientState.list();
        entries = l == null ? List.of() : l.entries();
        String created = ClientState.takeCreatedId();
        if (created != null && ClientState.byId(created) != null) {
            selectedId = created;
            scroll = 0;
        }
        if (selectedId == null || ClientState.byId(selectedId) == null) selectedId = entries.isEmpty() ? null : entries.getFirst().id();
        clampScroll();
    }

    private Entry selected() {
        for (Entry e : entries) if (e.id().equals(selectedId)) return e;
        return null;
    }

    private int selectedIndex() {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(selectedId)) return i;
        return -1;
    }

    private int maxScroll() {
        return Math.max(0, entries.size() * ROW_H - listH);
    }

    private void clampScroll() {
        scroll = Math.clamp(scroll, 0, maxScroll());
    }

    private void select(int index, boolean reveal) {
        if (entries.isEmpty()) return;
        index = Math.clamp(index, 0, entries.size() - 1);
        selectedId = entries.get(index).id();
        if (reveal) {
            int top = index * ROW_H, bottom = top + ROW_H;
            if (top < scroll) scroll = top;
            else if (bottom > scroll + listH) scroll = bottom - listH;
            clampScroll();
        }
    }

    // ---- actions ---------------------------------------------------------------------------------------

    private void create(boolean quick) {
        if (!createAllowed()) return;
        String name = quick ? "" : nameBox.getValue().strip();
        ClientState.markCreateSent();
        ClientPlayNetworking.send(new SavepointPayloads.CreatePayload(name, quick));
        nameBox.setValue("");
        updateButtons();
    }

    private void revert() {
        Entry e = selected();
        if (e != null && revertAllowed()) minecraft.setScreenAndShow(RevertConfirmScreen.create(this, e, false));
    }

    private void togglePin() {
        Entry e = selected();
        if (e != null && editAllowed()) ClientPlayNetworking.send(new SavepointPayloads.PinPayload(e.id(), !e.pinned()));
    }

    private void rename() {
        Entry e = selected();
        if (e == null || !editAllowed()) return;
        minecraft.setScreenAndShow(new PromptScreen(this, Component.translatable("savepoint.rename.title"), e.name(),
                name -> ClientPlayNetworking.send(new SavepointPayloads.RenamePayload(e.id(), name))));
    }

    private void delete() {
        Entry e = selected();
        if (e == null || !editAllowed()) return;
        Component body = Component.translatable(e.pinned() ? "savepoint.delete.body_pinned" : "savepoint.delete.body", ClientFmt.bytes(e.uniqueBytes()));
        minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
            minecraft.setScreenAndShow(this);
            if (yes) ClientPlayNetworking.send(new SavepointPayloads.DeletePayload(e.id()));
        }, Component.translatable("savepoint.delete.title", Names.shorten(e.name(), 36)), body,
                Component.translatable("savepoint.button.delete"), CommonComponents.GUI_CANCEL));
    }

    // ---- what is allowed right now -------------------------------------------------------------------------

    private boolean online() {
        return ClientPlayNetworking.canSend(SavepointPayloads.RequestListPayload.TYPE) && ClientState.list() != null;
    }

    private boolean backupRunning() {
        ListPayload l = ClientState.list();
        return l != null && l.backupRunning();
    }

    private boolean editAllowed() {
        return online() && !backupRunning() && !RevertCoordinator.isActive();
    }

    private boolean createAllowed() {
        return editAllowed() && ClientState.cooldownSeconds() == 0 && !ClientState.createInFlight();
    }

    private boolean revertAllowed() {
        return editAllowed() && ClientState.cooldownSeconds() == 0 && !ClientState.createInFlight() && selected() != null;
    }

    private Component reason(boolean cooldownApplies) {
        if (!online()) return Component.translatable("savepoint.reason.offline");
        if (RevertCoordinator.isActive()) return Component.translatable("savepoint.reason.reverting");
        if (backupRunning()) return Component.translatable("savepoint.reason.backup");
        if (ClientState.createInFlight()) return Component.translatable("savepoint.reason.saving");
        if (cooldownApplies && ClientState.cooldownSeconds() > 0) return Component.translatable("savepoint.reason.cooldown", ClientState.cooldownSeconds());
        return null;
    }

    private String lastLabels = "";

    private void updateButtons() {
        int cd = ClientState.cooldownSeconds();
        boolean saving = ClientState.createInFlight();
        Entry sel = selected();
        Component whyCreate = reason(true);
        Component whyEdit = reason(false);
        createBtn.active = createAllowed();
        quickBtn.active = createAllowed();
        revertBtn.active = revertAllowed() && sel != null;
        pinBtn.active = editAllowed() && sel != null;
        renameBtn.active = editAllowed() && sel != null;
        deleteBtn.active = editAllowed() && sel != null;
        ListPayload l = ClientState.list();
        backupBtn.active = editAllowed() && !entries.isEmpty() && !(l != null && l.backupRunning());
        settingsBtn.active = true;

        String labels = cd + "|" + saving + "|" + (sel != null && sel.pinned()) + "|" + backupRunning() + "|" + (whyCreate == null) + "|" + (whyEdit == null);
        if (labels.equals(lastLabels)) return;
        lastLabels = labels;
        createBtn.setMessage(saving ? Component.translatable("savepoint.button.saving") : cd > 0
                ? Component.translatable("savepoint.button.create_wait", cd) : Component.translatable("savepoint.button.create"));
        quickBtn.setMessage(cd > 0 && !saving ? Component.translatable("savepoint.button.quick_wait", cd) : Component.translatable("savepoint.button.quick"));
        revertBtn.setMessage(cd > 0 ? Component.translatable("savepoint.button.revert_wait", cd) : Component.translatable("savepoint.button.revert"));
        pinBtn.setMessage(Component.translatable(sel != null && sel.pinned() ? "savepoint.button.unpin" : "savepoint.button.pin"));
        backupBtn.setMessage(Component.translatable(backupRunning() ? "savepoint.button.backup_running" : "savepoint.button.backup"));
        Tooltip create = whyCreate != null ? Tooltip.create(whyCreate) : null;
        Tooltip edit = whyEdit != null ? Tooltip.create(whyEdit) : null;
        createBtn.setTooltip(create != null ? create : Tooltip.create(Component.translatable("savepoint.tip.create")));
        quickBtn.setTooltip(create != null ? create : Tooltip.create(Component.translatable("savepoint.tip.quick", SavepointClient.quickSaveKey.getTranslatedKeyMessage())));
        revertBtn.setTooltip(create != null ? create : Tooltip.create(Component.translatable("savepoint.tip.revert")));
        pinBtn.setTooltip(edit != null ? edit : Tooltip.create(Component.translatable("savepoint.tip.pin")));
        renameBtn.setTooltip(edit);
        deleteBtn.setTooltip(edit);
        backupBtn.setTooltip(edit != null ? edit : Tooltip.create(Component.translatable("savepoint.tip.backup")));
    }

    // ---- ticking & input -------------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        ticks++;
        // While a zip is being written the server cannot push a list (its status changes silently): poll.
        if (backupRunning() && ticks % 40 == 0) ClientState.requestList();
        // The cooldown ends by itself; no server round trip needed to re-enable the buttons.
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (nameBox.isFocused() && event.isConfirmation()) {
            create(false);
            return true;
        }
        if (!nameBox.isFocused()) {
            int idx = selectedIndex();
            int page = Math.max(1, listH / ROW_H - 1);
            switch (event.key()) {
                case InputConstants.KEY_UP -> select(idx - 1, true);
                case InputConstants.KEY_DOWN -> select(idx + 1, true);
                case InputConstants.KEY_PAGEUP -> select(idx - page, true);
                case InputConstants.KEY_PAGEDOWN -> select(idx + page, true);
                case InputConstants.KEY_HOME -> select(0, true);
                case InputConstants.KEY_END -> select(entries.size() - 1, true);
                case InputConstants.KEY_DELETE -> delete();
                default -> {
                    return super.keyPressed(event);
                }
            }
            return true;
        }
        return super.keyPressed(event);
    }

    private boolean inList(double mx, double my) {
        return mx >= listX && mx < listX + listW && my >= listY && my < listY + listH;
    }

    private int scrollbarThumbHeight() {
        return Math.max(12, listH * listH / Math.max(1, entries.size() * ROW_H));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double mx = event.x(), my = event.y();
        if (!inList(mx, my)) return false;
        if (maxScroll() > 0 && mx >= listX + listW - SCROLLBAR - 1) {
            draggingScrollbar = true;
            dragScrollbarTo(my);
            return true;
        }
        int idx = (int) ((my - listY + scroll) / ROW_H);
        if (idx >= 0 && idx < entries.size()) {
            select(idx, false);
            nameBox.setFocused(false);
            setFocused(null);
            return true;
        }
        return false;
    }

    private void dragScrollbarTo(double my) {
        int thumb = scrollbarThumbHeight();
        double f = (my - listY - thumb / 2.0) / Math.max(1, listH - thumb);
        scroll = (int) Math.round(Math.clamp(f, 0, 1) * maxScroll());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScrollbar) {
            dragScrollbarTo(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (inList(x, y) && maxScroll() > 0) {
            scroll -= (int) Math.round(scrollY * ROW_H * 0.75);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    // ---- rendering --------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        refreshFromState();
        updateButtons();
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        ListPayload l = ClientState.list();

        // Title line: "Savepoints · world name"
        String world = l == null ? "" : l.worldName();
        Component title = Component.empty().append(this.title.copy().withStyle(ChatFormatting.WHITE));
        int titleW = font.width(title);
        int worldRoom = Math.max(0, contentW - titleW - 20);
        String shownWorld = world.isEmpty() ? "" : font.plainSubstrByWidth(world, worldRoom - font.width("…")).length() < world.length()
                ? font.plainSubstrByWidth(world, worldRoom - font.width("…")) + "…" : world;
        int total = titleW + (shownWorld.isEmpty() ? 0 : font.width("  ·  " + shownWorld));
        int tx = (width - total) / 2;
        g.text(font, title, tx, 8, 0xFFFFFFFF);
        if (!shownWorld.isEmpty()) g.text(font, "  ·  " + shownWorld, tx + titleW, 8, 0xFF9A9A9A);

        // Usage + backup status
        Component status = backupStatus(l);
        int statusW = Math.min(font.width(status), contentW / 2);
        UsageBar.draw(g, font, l, left, usageY, contentW, contentW - statusW - 8);
        String shownStatus = font.width(status) > statusW ? font.plainSubstrByWidth(status.getString(), statusW - font.width("…")) + "…" : status.getString();
        g.text(font, shownStatus, left + contentW - statusW, usageY, backupStatusColor(l));

        extractList(g, mouseX, mouseY, l);
        extractDetail(g);
    }

    private Component backupStatus(ListPayload l) {
        if (l == null) return CommonComponents.EMPTY;
        if (l.backupRunning()) return Component.translatable("savepoint.backup.running");
        if (l.backupDoneThisSession()) return Component.translatable("savepoint.backup.done");
        return Component.translatable("savepoint.backup.none");
    }

    private int backupStatusColor(ListPayload l) {
        if (l == null) return 0xFF909090;
        return l.backupRunning() ? 0xFFFFD866 : l.backupDoneThisSession() ? 0xFF7FD67F : 0xFF909090;
    }

    private void extractList(GuiGraphicsExtractor g, int mouseX, int mouseY, ListPayload l) {
        g.fill(listX, listY, listX + listW, listY + listH, 0x70000000);
        g.outline(listX, listY, listW, listH, 0x40FFFFFF);
        if (entries.isEmpty()) {
            extractEmptyState(g, l);
            return;
        }
        g.enableScissor(listX + 1, listY + 1, listX + listW - 1, listY + listH - 1);
        long now = System.currentTimeMillis();
        int textRight = listX + listW - (maxScroll() > 0 ? SCROLLBAR + 2 : 2);
        Entry hoverEntry = null;
        for (int i = 0; i < entries.size(); i++) {
            int y = listY + i * ROW_H - scroll;
            if (y + ROW_H < listY || y > listY + listH) continue;
            Entry e = entries.get(i);
            boolean sel = e.id().equals(selectedId);
            boolean hover = inList(mouseX, mouseY) && mouseY >= y && mouseY < y + ROW_H && mouseX < textRight;
            if (sel) {
                g.fill(listX + 1, y, textRight, y + ROW_H, 0xFF000000);
                g.outline(listX + 1, y, textRight - listX - 1, ROW_H, 0xFFFFFFFF);
            } else if (hover) {
                g.fill(listX + 1, y, textRight, y + ROW_H, 0x40FFFFFF);
            }
            if (hover) hoverEntry = e;
            int tx = listX + 4, ty = y + (ROW_H - THUMB_H) / 2;
            drawThumb(g, e, tx, ty, THUMB_W, THUMB_H);
            int textX = tx + THUMB_W + 5;

            String badge = badgeText(e);
            int badgeW = badge == null ? 0 : font.width(badge) + 6;
            int nameRight = textRight - 4 - (badge == null ? 0 : badgeW + 3);
            String name = e.name();
            int starW = e.pinned() ? font.width("★ ") : 0;
            if (e.pinned()) g.text(font, "★", textX, y + 6, 0xFFFFC21A, false);
            int room = Math.max(8, nameRight - textX - starW);
            boolean cut = font.width(name) > room;
            if (cut) name = font.plainSubstrByWidth(name, room - font.width("…")) + "…";
            g.text(font, name, textX + starW, y + 6, sel || hover ? 0xFFFFFFFF : 0xFFE0E0E0, false);
            if (badge != null) {
                int bx = textRight - 4 - badgeW;
                g.fill(bx, y + 4, bx + badgeW, y + 15, badgeColor(e));
                g.text(font, badge, bx + 3, y + 6, 0xFFFFFFFF, false);
            }
            Component sub = Component.empty().append(ClientFmt.age(now - e.createdMillis())).append(" · " + ClientFmt.bytes(e.uniqueBytes()));
            String subText = sub.getString();
            if (font.width(subText) > textRight - textX - 2) subText = font.plainSubstrByWidth(subText, textRight - textX - 2 - font.width("…")) + "…";
            g.text(font, subText, textX, y + 19, 0xFF9A9A9A, false);
        }
        g.disableScissor();
        if (hoverEntry != null && font.width(hoverEntry.name()) > listW - THUMB_W - 40) {
            g.setTooltipForNextFrame(font, Component.literal(hoverEntry.name()), mouseX, mouseY);
        }
        if (maxScroll() > 0) {
            int thumb = scrollbarThumbHeight();
            int ty = listY + (listH - thumb) * scroll / maxScroll();
            int sx = listX + listW - SCROLLBAR - 1;
            g.fill(sx, listY + 1, sx + SCROLLBAR, listY + listH - 1, 0x40000000);
            g.fill(sx, ty, sx + SCROLLBAR, ty + thumb, 0xFF808080);
            g.fill(sx, ty, sx + SCROLLBAR - 1, ty + thumb - 1, 0xFFC0C0C0);
        }
    }

    private void drawThumb(GuiGraphicsExtractor g, Entry e, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        if (!Thumbs.draw(g, e.id(), e.hasThumbnail(), x, y, w, h)) {
            g.fill(x, y, x + w, y + h, 0xFF2A2A2A);
        }
    }

    private String badgeText(Entry e) {
        return switch (e.kind()) {
            case "quick" -> Component.translatable("savepoint.badge.quick").getString();
            case "undo" -> Component.translatable("savepoint.badge.undo").getString();
            case "session" -> Component.translatable("savepoint.badge.auto").getString();
            default -> e.auto() ? Component.translatable("savepoint.badge.auto").getString() : null;
        };
    }

    private int badgeColor(Entry e) {
        return switch (e.kind()) {
            case "quick" -> 0xFF3B72C4;
            case "undo" -> 0xFFB86A1E;
            default -> 0xFF666666;
        };
    }

    private void extractEmptyState(GuiGraphicsExtractor g, ListPayload l) {
        int cx = listX + listW / 2, w = listW - 24;
        Component head;
        Component how;
        if (!ClientPlayNetworking.canSend(SavepointPayloads.RequestListPayload.TYPE)) {
            head = Component.translatable("savepoint.empty.offline");
            how = CommonComponents.EMPTY;
        } else if (l == null) {
            head = Component.translatable("savepoint.empty.loading");
            how = CommonComponents.EMPTY;
        } else {
            head = Component.translatable("savepoint.empty.title");
            how = Component.translatable("savepoint.empty.how", SavepointClient.quickSaveKey.getTranslatedKeyMessage());
        }
        List<FormattedCharSequence> lines = font.split(how, w);
        int total = 12 + lines.size() * 10;
        int y = listY + (listH - total) / 2;
        g.centeredText(font, head, cx, y, 0xFFFFFFFF);
        y += 14;
        for (FormattedCharSequence line : lines) {
            g.centeredText(font, line, cx, y, 0xFFA0A0A0);
            y += 10;
        }
    }

    private void extractDetail(GuiGraphicsExtractor g) {
        g.fill(detX, listY, detX + detW, detButtonsTop - 4, 0x70000000);
        g.outline(detX, listY, detW, detButtonsTop - 4 - listY, 0x40FFFFFF);
        Entry e = selected();
        int bottom = detButtonsTop - 6;
        if (e == null) {
            if (!entries.isEmpty()) g.centeredText(font, Component.translatable("savepoint.detail.none"), detX + detW / 2, listY + 8, 0xFF909090);
            return;
        }
        int x = detX + 5, w = detW - 10, y = listY + 5;
        long now = System.currentTimeMillis();
        List<FormattedCharSequence> name = font.split(Component.literal(Names.shorten(e.name(), 70)), w);
        name = name.subList(0, Math.min(2, name.size()));
        // Pinned wins: a pinned quick or undo savepoint is neither replaced nor pruned, so its kind text would be wrong.
        Component kind = e.pinned() ? Component.translatable("savepoint.detail.kind.pinned") : switch (e.kind()) {
            case "quick" -> Component.translatable("savepoint.detail.kind.quick");
            case "undo" -> Component.translatable("savepoint.detail.kind.undo");
            case "session" -> Component.translatable("savepoint.detail.kind.auto");
            default -> null;
        };
        int kindColor = e.pinned() ? 0xFFFFC21A : e.kind().equals("undo") ? 0xFFE0A060 : e.kind().equals("quick") ? 0xFF80B0F0 : 0xFFC8C8C8;
        Component taken = Component.translatable("savepoint.detail.taken", ClientFmt.age(now - e.createdMillis()));
        Component size = Component.translatable("savepoint.detail.size", ClientFmt.bytes(e.logicalBytes()));
        Component frees = Component.translatable("savepoint.detail.frees", ClientFmt.bytes(e.uniqueBytes()));

        // The text has priority; the thumbnail gets what is left (and is skipped when that would be tiny).
        int textH = name.size() * 10 + 2 + (1 + 1 + 1) * 10 + (kind == null ? 0 : font.split(kind, w).size() * 10);
        int thumbH = Math.min(w * 9 / 16, bottom - y - textH - 4);
        if (thumbH >= 36) {
            int thumbW = thumbH * 16 / 9;
            drawThumb(g, e, x + (w - thumbW) / 2 + 1, y + 1, thumbW - 2, thumbH - 2);
            y += thumbH + 4;
        }
        for (FormattedCharSequence l : name) {
            g.text(font, l, x, y, 0xFFFFFFFF, false);
            y += 10;
        }
        y += 2;
        y = line(g, x, y, w, bottom, taken, 0xFFC8C8C8);
        if (kind != null) y = line(g, x, y, w, bottom, kind, kindColor);
        y = line(g, x, y, w, bottom, size, 0xFF9A9A9A);
        line(g, x, y, w, bottom, frees, 0xFF9A9A9A);
    }

    /** Wrapped text that stops before {@code bottom}; returns the next free y. */
    private int line(GuiGraphicsExtractor g, int x, int y, int w, int bottom, Component text, int color) {
        for (FormattedCharSequence l : font.split(text, w)) {
            if (y + 9 > bottom) return y;
            g.text(font, l, x, y, color, false);
            y += 10;
        }
        return y;
    }

    @Override
    public void removed() {
        Thumbs.release();
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    /** Must stay false: the integrated server would pause, and it is the one that answers our requests. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
