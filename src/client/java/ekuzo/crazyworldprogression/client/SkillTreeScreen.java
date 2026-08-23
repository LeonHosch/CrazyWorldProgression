package ekuzo.crazyworldprogression.client;

import ekuzo.crazyworldprogression.CrazyWorldProgression;
import ekuzo.crazyworldprogression.command.BalanceCommands.CurrencyDisplay;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillCosts;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeDefinition.SkillTreeType;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeNetworking;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.Balances;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.NodeSnapshot;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.SkillTreeSnapshot;
import ekuzo.crazyworldprogression.progression.skilltrees.SkillTreeService.TreeSnapshot;
import ekuzo.crazyworldprogression.client.SkillTreeLayout.ConnectionKey;
import ekuzo.crazyworldprogression.client.SkillTreeLayout.ConnectorRoute;
import ekuzo.crazyworldprogression.client.SkillTreeLayout.NodePosition;
import ekuzo.crazyworldprogression.client.SkillTreeLayout.RoutePoint;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementTabType;
import net.minecraft.client.gui.screens.advancements.AdvancementWidgetType;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ekuzo.crazyworldprogression.client.SkillTreeLayout.NODE_SIZE;

public final class SkillTreeScreen extends Screen {
    private static final Identifier WINDOW_TEXTURE =
            Identifier.withDefaultNamespace("textures/gui/advancements/window.png");
    private static final Identifier GLOBAL_BACKGROUND =
            Identifier.withDefaultNamespace("textures/block/blackstone.png");
    private static final Identifier PERSONAL_BACKGROUND =
            Identifier.withDefaultNamespace("textures/block/deepslate_tiles.png");
    private static final CurrencyIcon KINGDOM_POINTS_ICON = new CurrencyIcon(
            CrazyWorldProgression.id("currencies/kp-32x32.png"),
            7,
            4,
            18,
            24
    );
    private static final CurrencyIcon ECHELON_POINTS_ICON = new CurrencyIcon(
            CrazyWorldProgression.id("currencies/ep-32x32.png"),
            7,
            4,
            17,
            24
    );
    private static final CurrencyIcon FAKHRUL_CURRENCY_ICON = new CurrencyIcon(
            CrazyWorldProgression.id("currencies/fc-32x32.png"),
            5,
            4,
            22,
            24
    );
    private static final CurrencyIcon POWERFUL_SOULS_ICON = new CurrencyIcon(
            CrazyWorldProgression.id("currencies/ps-32x32.png"),
            8,
            4,
            16,
            24
    );
    private static final int WINDOW_WIDTH = 252;
    private static final int WINDOW_HEIGHT = 140;
    private static final int CANVAS_OFFSET_X = 9;
    private static final int CANVAS_OFFSET_Y = 18;
    private static final int CANVAS_WIDTH = 234;
    private static final int CANVAS_HEIGHT = 113;
    private static final int CANVAS_MARGIN = 18;
    private static final int MINIMUM_HOVER_WIDTH = 72;
    private static final int MAXIMUM_HOVER_WIDTH = 190;
    private static final int HOVER_HORIZONTAL_PADDING = 6;
    private static final int CURRENCY_ICON_SOURCE_SIZE = 32;
    private static final int CURRENCY_ICON_DISPLAY_SIZE = 9;
    private static final int CURRENCY_TEXT_ICON_GAP = 0;
    private static final int CURRENCY_TEXT_VERTICAL_OFFSET = 1;
    private static final int CURRENCY_ENTRY_GAP = 5;
    private static final int HIGHLIGHT_BORDER_SIZE = 1;
    private static final int HIGHLIGHT_CONTENT_PADDING = 2;
    private static final int HIGHLIGHT_BORDER_COLOR = 0xFFE6B84A;
    private static final int HIGHLIGHT_BACKGROUND_COLOR = 0xF02B2417;
    private static final int UNLOCKED_GOLD = 0xFFB7860B;
    private static final int EXCLUDED_FRAME_TINT = 0xFF2B2B2B;
    private static final int EXCLUDED_FRAME_HOVER_TINT = 0xFF202020;
    private static final int CONNECTION_GREEN = 0xFF55AA55;
    private static final int CONNECTION_AVAILABLE_RED = 0xFFCC3333;
    private static final int CONNECTION_UNAVAILABLE_GRAY = 0xFF7A7A7A;
    private static final int CONNECTION_EXCLUDED_DARK = EXCLUDED_FRAME_TINT;
    private static final int CONNECTION_OUTLINE_BLACK = 0xFF000000;
    private static final long STANDARD_HOLD_TO_UNLOCK_NANOSECONDS = 1_500_000_000L;
    private static final long CHOICE_HOLD_TO_UNLOCK_NANOSECONDS = 3_000_000_000L;
    private static final int HOLD_PROGRESS_SOUND_STEPS = 6;
    private static final long COMPLETION_POP_NANOSECONDS = 200_000_000L;
    private static final float PRESSED_NODE_SCALE = 0.90F;
    private static final float COMPLETION_NODE_SCALE = 1.15F;
    private final Screen parent;
    private final Map<String, NodePosition> nodePositions = new HashMap<>();
    private final Map<ConnectionKey, ConnectorRoute> connectorRoutes = new HashMap<>();
    private final Map<String, CanvasState> canvasStates = new HashMap<>();
    private final Map<String, ItemStack> iconCache = new HashMap<>();
    private SkillTreeSnapshot snapshot;
    private String selectedTreeId;
    private int leftPos;
    private int topPos;
    private boolean draggingCanvas;
    private String pressedSkillId;
    private String pendingTreeId;
    private String pendingSkillId;
    private String poppingSkillId;
    private long holdStartedAtNanoseconds;
    private long popStartedAtNanoseconds;
    private int lastHoldSoundStep;
    private int currentMouseX;
    private int currentMouseY;

    // Create an advancement-style skill-tree screen that returns to its opening screen.
    public SkillTreeScreen(Screen parent, SkillTreeSnapshot snapshot) {
        super(Component.translatable("screen.crazy-world-progression.skill_trees.title"));
        this.parent = parent;
        this.snapshot = snapshot;
        this.selectedTreeId = snapshot.trees().isEmpty() ? "" : snapshot.trees().getFirst().id();
    }

    // Replace balances and unlock state while preserving the selected tab and canvas position.
    public void updateSnapshot(SkillTreeSnapshot snapshot) {
        boolean completedUnlock = pendingTreeId != null
                && pendingSkillId != null
                && isUnlocked(snapshot, pendingTreeId, pendingSkillId);
        this.snapshot = snapshot;
        if (completedUnlock) {
            poppingSkillId = pendingSkillId;
            popStartedAtNanoseconds = System.nanoTime();
            playUiSound(SoundEvents.BUBBLE_POP, 0.9F, 0.8F);
        }
        pendingTreeId = null;
        pendingSkillId = null;
        if (snapshot.trees().stream().noneMatch(tree -> tree.id().equals(selectedTreeId))) {
            selectedTreeId = snapshot.trees().isEmpty() ? "" : snapshot.trees().getFirst().id();
        }
        rebuildNodeLayout();
    }

    // Check whether a returned server snapshot confirms one requested skill unlock.
    private static boolean isUnlocked(SkillTreeSnapshot snapshot, String treeId, String skillId) {
        return snapshot.trees().stream()
                .filter(tree -> tree.id().equals(treeId))
                .flatMap(tree -> tree.skills().stream())
                .anyMatch(skill -> skill.id().equals(skillId) && skill.unlocked());
    }

    // Position the vanilla-sized window, add its Done button, and lay out the selected tree.
    @Override
    protected void init() {
        leftPos = (width - WINDOW_WIDTH) / 2;
        topPos = Math.max(32, (height - WINDOW_HEIGHT - 72) / 2);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds(leftPos, topPos + WINDOW_HEIGHT + 28, WINDOW_WIDTH, 20)
                .build());
        rebuildNodeLayout();
    }

    // Rebuild graph geometry and update the selected tab's canvas bounds.
    private void rebuildNodeLayout() {
        nodePositions.clear();
        connectorRoutes.clear();
        TreeSnapshot tree = selectedTree();
        if (tree == null) {
            return;
        }
        SkillTreeLayout.Result layout = SkillTreeLayout.build(tree);
        nodePositions.putAll(layout.nodePositions());
        connectorRoutes.putAll(layout.connectorRoutes());
        SkillTreeLayout.Bounds bounds = layout.bounds();
        canvasStates.computeIfAbsent(tree.id(), ignored -> new CanvasState()).setBounds(
                bounds.minimumX(),
                bounds.minimumY(),
                bounds.maximumX(),
                bounds.maximumY()
        );
    }

    // Render the vanilla advancement window, its scrollable canvas, tabs, balances, and widgets.
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        currentMouseX = mouseX;
        currentMouseY = mouseY;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                WINDOW_TEXTURE,
                leftPos,
                topPos,
                0.0F,
                0.0F,
                WINDOW_WIDTH,
                WINDOW_HEIGHT,
                256,
                256
        );
        drawCanvas(graphics);
        drawTabs(graphics);
        drawWindowText(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        drawHoverContent(graphics, mouseX, mouseY);
    }

    // Draw the textured, clipped node canvas and every connector and node inside it.
    private void drawCanvas(GuiGraphicsExtractor graphics) {
        TreeSnapshot tree = selectedTree();
        int canvasX = canvasX();
        int canvasY = canvasY();
        graphics.enableScissor(canvasX, canvasY, canvasX + CANVAS_WIDTH, canvasY + CANVAS_HEIGHT);
        if (tree == null) {
            graphics.fill(canvasX, canvasY, canvasX + CANVAS_WIDTH, canvasY + CANVAS_HEIGHT, 0xFF101010);
        } else {
            drawTiledBackground(graphics, tree);
            drawConnections(graphics, tree, true);
            drawConnections(graphics, tree, false);
            drawBranchChoiceLabels(graphics, tree);
            drawNodes(graphics, tree);
        }
        graphics.disableScissor();
    }

    // Tile a tree-specific vanilla block texture behind the panning nodes.
    private void drawTiledBackground(GuiGraphicsExtractor graphics, TreeSnapshot tree) {
        CanvasState state = selectedCanvasState();
        Identifier texture = tree.type() == SkillTreeType.GLOBAL ? GLOBAL_BACKGROUND : PERSONAL_BACKGROUND;
        int originX = canvasX() + Math.floorMod((int) Math.floor(state.offsetX), 16) - 16;
        int originY = canvasY() + Math.floorMod((int) Math.floor(state.offsetY), 16) - 16;
        for (int x = originX; x < canvasX() + CANVAS_WIDTH; x += 16) {
            for (int y = originY; y < canvasY() + CANVAS_HEIGHT; y += 16) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, 16, 16, 16, 16);
            }
        }
    }

    // Draw outlined dependency connectors while preserving branch-choice color priority at shared trunks.
    private void drawConnections(GuiGraphicsExtractor graphics, TreeSnapshot tree, boolean outline) {
        Map<String, NodeSnapshot> byId = new HashMap<>();
        tree.skills().forEach(skill -> byId.put(skill.id(), skill));
        Map<String, Integer> childCounts = new HashMap<>();
        tree.skills().forEach(skill -> skill.previous().forEach(parentId -> childCounts.merge(parentId, 1, Integer::sum)));
        List<RenderedConnection> connections = new ArrayList<>();
        for (NodeSnapshot skill : tree.skills()) {
            for (String previousId : skill.previous()) {
                NodeSnapshot previousSkill = byId.get(previousId);
                ConnectorRoute route = connectorRoutes.get(new ConnectionKey(previousId, skill.id()));
                if (route == null || previousSkill == null) {
                    continue;
                }
                int color = connectorColor(previousSkill, skill, childCounts.getOrDefault(previousId, 0));
                connections.add(new RenderedConnection(route, color, connectorPriority(color)));
            }
        }
        connections.sort((first, second) -> Integer.compare(first.priority(), second.priority()));
        for (RenderedConnection connection : connections) {
            drawConnector(
                    graphics,
                    connection.route(),
                    outline ? CONNECTION_OUTLINE_BLACK : connection.color(),
                    outline ? 4 : 2
            );
        }
    }

    // Select red for open restricted branches, green for chosen branches, and black for exclusions.
    private static int connectorColor(NodeSnapshot parent, NodeSnapshot child, int childCount) {
        if (child.excluded()) {
            return CONNECTION_EXCLUDED_DARK;
        }
        if (parent.following() > 0 && childCount > 1) {
            return child.unlocked() ? CONNECTION_GREEN : CONNECTION_AVAILABLE_RED;
        }
        return parent.unlocked() ? CONNECTION_GREEN : CONNECTION_UNAVAILABLE_GRAY;
    }

    // Draw excluded paths first and chosen paths last so a shared fork trunk keeps the active color.
    private static int connectorPriority(int color) {
        if (color == CONNECTION_EXCLUDED_DARK) {
            return 0;
        }
        if (color == CONNECTION_UNAVAILABLE_GRAY) {
            return 1;
        }
        if (color == CONNECTION_AVAILABLE_RED) {
            return 2;
        }
        return 3;
    }

    // Draw each restricted split's remaining-choice ratio above its shared connector trunk.
    private void drawBranchChoiceLabels(GuiGraphicsExtractor graphics, TreeSnapshot tree) {
        for (NodeSnapshot parent : tree.skills()) {
            List<NodeSnapshot> branches = tree.skills().stream()
                    .filter(child -> child.previous().contains(parent.id()))
                    .toList();
            if (parent.following() <= 0 || branches.size() <= 1) {
                continue;
            }
            List<ConnectorRoute> routes = branches.stream()
                    .map(child -> connectorRoutes.get(new ConnectionKey(parent.id(), child.id())))
                    .filter(route -> route != null && route.points().size() > 1)
                    .toList();
            NodePosition parentPosition = nodePositions.get(parent.id());
            if (routes.isEmpty() || parentPosition == null) {
                continue;
            }
            int junctionX = routes.stream()
                    .mapToInt(route -> route.points().get(1).x())
                    .min()
                    .orElse(parentPosition.x() + NODE_SIZE);
            int branchLimit = Math.min(parent.following(), branches.size());
            long chosenBranches = branches.stream().filter(NodeSnapshot::unlocked).count();
            long choicesRemaining = Math.max(0L, branchLimit - chosenBranches);
            Component ratio = Component.literal(choicesRemaining + "/" + branches.size());
            int color = choicesRemaining > 0L ? CONNECTION_AVAILABLE_RED : CONNECTION_EXCLUDED_DARK;
            int ratioX = localScreenX(junctionX) - font.width(ratio) / 2;
            int ratioY = localScreenY(parentPosition.y() + NODE_SIZE / 2) - font.lineHeight - 2;
            graphics.text(font, ratio, ratioX, ratioY, color, true);
        }
    }

    // Draw every horizontal and vertical segment of one cached orthogonal connector route.
    private void drawConnector(
            GuiGraphicsExtractor graphics,
            ConnectorRoute route,
            int color,
            int thickness
    ) {
        List<RoutePoint> points = route.points();
        for (int index = 1; index < points.size(); index++) {
            RoutePoint start = points.get(index - 1);
            RoutePoint end = points.get(index);
            drawConnectorSegment(
                    graphics,
                    localScreenX(start.x()),
                    localScreenY(start.y()),
                    localScreenX(end.x()),
                    localScreenY(end.y()),
                    color,
                    thickness
            );
        }
    }

    // Draw one axis-aligned connector segment with enough overlap to close its corners.
    private static void drawConnectorSegment(
            GuiGraphicsExtractor graphics,
            int startX,
            int startY,
            int endX,
            int endY,
            int color,
            int thickness
    ) {
        int half = thickness / 2;
        if (startY == endY) {
            graphics.fill(
                    Math.min(startX, endX) - half,
                    startY - half,
                    Math.max(startX, endX) + half + 1,
                    startY + thickness - half,
                    color
            );
            return;
        }
        graphics.fill(
                startX - half,
                Math.min(startY, endY) - half,
                startX + thickness - half,
                Math.max(startY, endY) + half + 1,
                color
        );
    }

    // Draw each skill as a vanilla advancement frame containing its configured item icon.
    private void drawNodes(GuiGraphicsExtractor graphics, TreeSnapshot tree) {
        for (NodeSnapshot skill : tree.skills()) {
            NodePosition position = nodePositions.get(skill.id());
            if (position == null) {
                continue;
            }
            int x = nodeScreenX(position);
            int y = nodeScreenY(position);
            drawScaledNode(graphics, skill, x, y);
        }
    }

    // Scale a complete node around its center for held and completion feedback.
    private void drawScaledNode(GuiGraphicsExtractor graphics, NodeSnapshot skill, int x, int y) {
        float scale = nodeScale(skill);
        if (scale != 1.0F) {
            float centerX = x + NODE_SIZE / 2.0F;
            float centerY = y + NODE_SIZE / 2.0F;
            graphics.pose().pushMatrix();
            graphics.pose().translate(centerX, centerY);
            graphics.pose().scale(scale, scale);
            graphics.pose().translate(-centerX, -centerY);
        }
        drawNodeLayers(graphics, skill, x, y);
        if (scale != 1.0F) {
            graphics.pose().popMatrix();
        }
    }

    // Draw one node with its state-owned frame tint, radial progress, and configured item.
    private void drawNodeLayers(GuiGraphicsExtractor graphics, NodeSnapshot skill, int x, int y) {
        drawNodeIcon(graphics, skill, x, y, isHovered(skill), holdProgress(skill));
    }

    // Draw the shared tinted frame, optional hold fill, and item used by nodes and hover cards.
    private void drawNodeIcon(
            GuiGraphicsExtractor graphics,
            NodeSnapshot skill,
            int x,
            int y,
            boolean hovered,
            double progress
    ) {
        NodeVisualStyle visualStyle = nodeVisualStyle(skill);
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                visualStyle.widgetType().frameSprite(AdvancementType.TASK),
                x,
                y,
                NODE_SIZE,
                NODE_SIZE,
                visualStyle.frameTint(hovered)
        );
        if (progress > 0.0D) {
            drawClockwiseBackgroundFill(graphics, x + 4, y + 4, 18, progress, UNLOCKED_GOLD);
        }
        graphics.item(icon(skill.icon()), x + 5, y + 5);
    }

    // Return the visual node scale for a completion pop or held-button depression.
    private float nodeScale(NodeSnapshot skill) {
        if (skill.id().equals(poppingSkillId)) {
            return COMPLETION_NODE_SCALE;
        }
        return isPressedOver(skill) ? PRESSED_NODE_SCALE : 1.0F;
    }

    // Fill an icon background clockwise from twelve o'clock using a pixelated radial sweep.
    private static void drawClockwiseBackgroundFill(
            GuiGraphicsExtractor graphics,
            int left,
            int top,
            int size,
            double progress,
            int color
    ) {
        double maximumAngle = Math.PI * 2.0D * progress;
        double center = size / 2.0D;
        for (int vertical = 0; vertical < size; vertical++) {
            for (int horizontal = 0; horizontal < size; horizontal++) {
                double deltaX = horizontal + 0.5D - center;
                double deltaY = vertical + 0.5D - center;
                double clockwiseAngle = Math.atan2(deltaX, -deltaY);
                if (clockwiseAngle < 0.0D) {
                    clockwiseAngle += Math.PI * 2.0D;
                }
                if (clockwiseAngle <= maximumAngle) {
                    graphics.fill(
                            left + horizontal,
                            top + vertical,
                            left + horizontal + 1,
                            top + vertical + 1,
                            color
                    );
                }
            }
        }
    }

    // Draw vanilla advancement tabs and their configured item icons around the window.
    private void drawTabs(GuiGraphicsExtractor graphics) {
        for (int index = 0; index < snapshot.trees().size(); index++) {
            TabPlacement placement = tabPlacement(index);
            if (placement == null) {
                break;
            }
            TreeSnapshot tree = snapshot.trees().get(index);
            boolean selected = tree.id().equals(selectedTreeId);
            placement.type().extractRenderState(
                    graphics,
                    leftPos + placement.type().getX(placement.index()),
                    topPos + placement.type().getY(placement.index()),
                    selected,
                    placement.index()
            );
            placement.type().extractIcon(graphics, leftPos, topPos, placement.index(), icon(tree.icon()));
        }
    }

    // Draw the screen title, selected tree name, highlighted balances, and king warning.
    private void drawWindowText(GuiGraphicsExtractor graphics) {
        graphics.centeredText(font, title, width / 2, topPos - 18, 0xFFFFFFFF);
        TreeSnapshot tree = selectedTree();
        if (tree != null) {
            graphics.text(font, Component.literal(displayedTreeName(tree)), leftPos + 8, topPos + 6, 0xFF404040, false);
        }

        drawBalances(graphics, tree, snapshot.balances());
        int messageY = topPos + WINDOW_HEIGHT + 52;
        if (tree != null && tree.type() == SkillTreeType.GLOBAL && !snapshot.electedKing()) {
            Component warning = Component.translatable("screen.crazy-world-progression.skill_trees.king_only_spend")
                    .withStyle(ChatFormatting.RED);
            graphics.centeredText(font, warning, width / 2, messageY, 0xFFFFFFFF);
        }
    }

    // Draw the selected tree's relevant balances together inside one highlighted box.
    private void drawBalances(GuiGraphicsExtractor graphics, TreeSnapshot tree, Balances balances) {
        if (tree == null) {
            return;
        }
        List<CurrencyAmount> currencies = balanceCurrencies(tree, balances);
        int contentWidth = currencyRowWidth(currencies);
        int badgeWidth = highlightedContentWidth(contentWidth);
        int badgeX = (width - badgeWidth) / 2;
        int badgeY = topPos + WINDOW_HEIGHT + 7;
        drawHighlightedBackground(graphics, badgeX, badgeY, badgeWidth, highlightedBadgeHeight());
        int currencyX = badgeX + HIGHLIGHT_BORDER_SIZE + HIGHLIGHT_CONTENT_PADDING;
        for (CurrencyAmount currency : currencies) {
            drawCurrencyAmount(
                    graphics,
                    currency,
                    currencyX,
                    badgeY + HIGHLIGHT_BORDER_SIZE + HIGHLIGHT_CONTENT_PADDING
            );
            currencyX += currencyAmountWidth(currency) + CURRENCY_ENTRY_GAP;
        }
    }

    // Return KP plus king FC for global trees or the player's three personal currencies.
    private static List<CurrencyAmount> balanceCurrencies(TreeSnapshot tree, Balances balances) {
        if (tree.type() == SkillTreeType.GLOBAL) {
            return List.of(
                    new CurrencyAmount(
                            balances.kingdomPoints(),
                            CurrencyDisplay.KINGDOM_POINTS,
                            KINGDOM_POINTS_ICON,
                            true
                    ),
                    new CurrencyAmount(
                            balances.kingFakhrulCurrency(),
                            CurrencyDisplay.FAKHRUL_CURRENCY,
                            FAKHRUL_CURRENCY_ICON,
                            true
                    )
            );
        }
        return List.of(
                new CurrencyAmount(
                        balances.echelonPoints(),
                        CurrencyDisplay.ECHELON_POINTS,
                        ECHELON_POINTS_ICON,
                        true
                ),
                new CurrencyAmount(
                        balances.fakhrulCurrency(),
                        CurrencyDisplay.FAKHRUL_CURRENCY,
                        FAKHRUL_CURRENCY_ICON,
                        true
                ),
                new CurrencyAmount(
                        balances.powerfulSouls(),
                        CurrencyDisplay.POWERFUL_SOULS,
                        POWERFUL_SOULS_ICON,
                        true
                )
        );
    }

    // Draw a vanilla-inspired hover card or a tab-name tooltip under the cursor.
    private void drawHoverContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        TreeSnapshot tree = selectedTree();
        NodeSnapshot hovered = tree == null ? null : hoveredNode(tree, mouseX, mouseY);
        if (hovered != null) {
            drawNodeHover(graphics, tree, hovered);
            return;
        }
        for (int index = 0; index < snapshot.trees().size(); index++) {
            TabPlacement placement = tabPlacement(index);
            if (placement != null && placement.type().isMouseOver(
                    leftPos,
                    topPos,
                    placement.index(),
                    mouseX,
                    mouseY
            )) {
                graphics.setTooltipForNextFrame(
                        Component.literal(displayedTreeName(snapshot.trees().get(index))),
                        mouseX,
                        mouseY
                );
                return;
            }
        }
    }

    // Draw a content-sized hover card directly below its node without covering the hovered icon.
    private void drawNodeHover(GuiGraphicsExtractor graphics, TreeSnapshot tree, NodeSnapshot skill) {
        NodePosition position = nodePositions.get(skill.id());
        String displayedName = displayedSkillName(skill);
        String displayedDescription = displayedSkillDescription(skill);
        boolean showCosts = !skill.unlocked();
        List<CurrencyAmount> costs = showCosts
                ? formatCosts(tree.type(), skill.costs(), snapshot.balances())
                : List.of();
        boolean free = showCosts && costs.isEmpty();
        Component status = nodeStatus(tree, skill);
        int widestContent = Math.max(
                40 + Math.max(font.width(displayedName), font.width(status)),
                2 * HOVER_HORIZONTAL_PADDING + widestLineWidth(displayedDescription)
        );
        if (showCosts) {
            int costContentWidth = free
                    ? font.width(Component.translatable("screen.crazy-world-progression.skill_trees.free"))
                    : currencyRowWidth(costs);
            widestContent = Math.max(
                    widestContent,
                    highlightedContentWidth(costContentWidth) + 2 * HOVER_HORIZONTAL_PADDING
            );
        }
        int hoverWidth = Math.max(MINIMUM_HOVER_WIDTH, Math.min(MAXIMUM_HOVER_WIDTH, widestContent));
        List<FormattedCharSequence> description = displayedDescription.isBlank()
                ? List.of()
                : font.split(
                        Component.literal(displayedDescription),
                        hoverWidth - 2 * HOVER_HORIZONTAL_PADDING
                );
        int bodyStart = 34;
        int descriptionHeight = description.size() * font.lineHeight;
        int costY = bodyStart + descriptionHeight + (description.isEmpty() ? 0 : 3);
        int hoverHeight = showCosts
                ? costY + font.lineHeight + 5
                : description.isEmpty() ? 32 : bodyStart + descriptionHeight + 5;
        int nodeX = nodeScreenX(position);
        int nodeY = nodeScreenY(position);
        int hoverX = nodeX + NODE_SIZE / 2 - hoverWidth / 2;
        hoverX = Math.max(4, Math.min(width - hoverWidth - 4, hoverX));
        int hoverY = nodeY + NODE_SIZE;
        drawHoverCardBackground(graphics, skill, hoverX, hoverY, hoverWidth, hoverHeight);
        drawNodeIcon(graphics, skill, hoverX + 4, hoverY + 4, true, 0.0D);
        graphics.text(font, Component.literal(displayedName), hoverX + 34, hoverY + 6, 0xFFFFFFFF, false);
        graphics.text(font, status, hoverX + 34, hoverY + 17, 0xFFFFFFFF, false);

        int textY = hoverY + bodyStart;
        for (FormattedCharSequence line : description) {
            graphics.text(font, line, hoverX + HOVER_HORIZONTAL_PADDING, textY, 0xFFAA00AA, false);
            textY += font.lineHeight;
        }
        if (showCosts) {
            drawHighlightedCost(graphics, costs, free, hoverX, hoverY + costY, hoverWidth);
        }
    }

    // Return the German skill name only while the client is using a German locale.
    private String displayedSkillName(NodeSnapshot skill) {
        return usesGermanSkillText() ? skill.germanName() : skill.name();
    }

    // Return the German skill description only while the client is using a German locale.
    private String displayedSkillDescription(NodeSnapshot skill) {
        return usesGermanSkillText() ? skill.germanDescription() : skill.description();
    }

    // Return the German tab name only while the client is using a German locale.
    private String displayedTreeName(TreeSnapshot tree) {
        return usesGermanSkillText() ? tree.germanName() : tree.name();
    }

    // Check the live client language code so changing languages does not require reconnecting.
    private boolean usesGermanSkillText() {
        return minecraft.getLanguageManager().getSelected().toLowerCase(Locale.ROOT).startsWith("de_");
    }

    // Draw a strictly bounded card with a state-colored header and vanilla-dark body.
    private static void drawHoverCardBackground(
            GuiGraphicsExtractor graphics,
            NodeSnapshot skill,
            int hoverX,
            int hoverY,
            int hoverWidth,
            int hoverHeight
    ) {
        int headerColor = nodeVisualStyle(skill).headerColor();
        graphics.fill(hoverX, hoverY, hoverX + hoverWidth, hoverY + hoverHeight, 0xFF000000);
        graphics.fill(hoverX + 1, hoverY + 1, hoverX + hoverWidth - 1, hoverY + 31, headerColor);
        if (hoverHeight > 32) {
            graphics.fill(hoverX + 1, hoverY + 31, hoverX + hoverWidth - 1, hoverY + hoverHeight - 1, 0xF0202020);
        }
    }

    // Draw the colored currency cost inside a compact gold-edged badge in the dark body.
    private void drawHighlightedCost(
            GuiGraphicsExtractor graphics,
            List<CurrencyAmount> costs,
            boolean free,
            int hoverX,
            int costY,
            int hoverWidth
    ) {
        Component freeText = Component.translatable("screen.crazy-world-progression.skill_trees.free")
                .withStyle(ChatFormatting.GREEN);
        int contentWidth = free ? font.width(freeText) : currencyRowWidth(costs);
        int badgeWidth = highlightedContentWidth(contentWidth);
        int badgeX = hoverX + (hoverWidth - badgeWidth) / 2;
        int badgeY = costY - HIGHLIGHT_BORDER_SIZE - HIGHLIGHT_CONTENT_PADDING;
        drawHighlightedBackground(graphics, badgeX, badgeY, badgeWidth, highlightedBadgeHeight());
        if (free) {
            graphics.centeredText(
                    font,
                    freeText,
                    hoverX + hoverWidth / 2,
                    costY,
                    0xFFFFFFFF
            );
            return;
        }
        int currencyX = badgeX + HIGHLIGHT_BORDER_SIZE + HIGHLIGHT_CONTENT_PADDING;
        for (CurrencyAmount currency : costs) {
            drawCurrencyAmount(graphics, currency, currencyX, costY);
            currencyX += currencyAmountWidth(currency) + CURRENCY_ENTRY_GAP;
        }
    }

    // Draw the shared gold edge and dark interior used by cost and balance badges.
    private static void drawHighlightedBackground(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int badgeWidth,
            int badgeHeight
    ) {
        graphics.fill(x, y, x + badgeWidth, y + badgeHeight, HIGHLIGHT_BORDER_COLOR);
        graphics.fill(
                x + HIGHLIGHT_BORDER_SIZE,
                y + HIGHLIGHT_BORDER_SIZE,
                x + badgeWidth - HIGHLIGHT_BORDER_SIZE,
                y + badgeHeight - HIGHLIGHT_BORDER_SIZE,
                HIGHLIGHT_BACKGROUND_COLOR
        );
    }

    // Draw one colored amount followed by its small icon.
    private void drawCurrencyAmount(
            GuiGraphicsExtractor graphics,
            CurrencyAmount currency,
            int x,
            int y
    ) {
        Component amount = Component.literal(Long.toString(currency.amount()))
                .withStyle(currency.affordable() ? currency.display().color() : ChatFormatting.GRAY);
        graphics.text(font, amount, x, y + CURRENCY_TEXT_VERTICAL_OFFSET, 0xFFFFFFFF, false);
        int iconX = x + font.width(amount) + CURRENCY_TEXT_ICON_GAP;
        int iconWidth = currencyIconDisplayWidth(currency.icon());
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                currency.icon().texture(),
                iconX,
                y,
                currency.icon().u(),
                currency.icon().v(),
                iconWidth,
                CURRENCY_ICON_DISPLAY_SIZE,
                currency.icon().width(),
                currency.icon().height(),
                CURRENCY_ICON_SOURCE_SIZE,
                CURRENCY_ICON_SOURCE_SIZE
        );
    }

    // Scale a tightly cropped icon proportionally to the shared display height.
    private static int currencyIconDisplayWidth(CurrencyIcon icon) {
        return Math.max(1, Math.round(icon.width() * CURRENCY_ICON_DISPLAY_SIZE / (float) icon.height()));
    }

    // Return the width of one amount-and-icon pair without badge padding.
    private int currencyAmountWidth(CurrencyAmount currency) {
        return font.width(Long.toString(currency.amount()))
                + CURRENCY_TEXT_ICON_GAP
                + currencyIconDisplayWidth(currency.icon());
    }

    // Return the complete content width of a multi-currency row.
    private int currencyRowWidth(List<CurrencyAmount> currencies) {
        return currencies.stream().mapToInt(this::currencyAmountWidth).sum()
                + CURRENCY_ENTRY_GAP * Math.max(0, currencies.size() - 1);
    }

    // Add symmetric border and padding to a raw badge-content width.
    private static int highlightedContentWidth(int contentWidth) {
        return contentWidth + 2 * (HIGHLIGHT_BORDER_SIZE + HIGHLIGHT_CONTENT_PADDING);
    }

    // Return the common badge height with equal padding on every side of a text line.
    private int highlightedBadgeHeight() {
        return font.lineHeight + 2 * (HIGHLIGHT_BORDER_SIZE + HIGHLIGHT_CONTENT_PADDING);
    }

    // Return the precise purchase state shown immediately beneath a skill's name.
    private Component nodeStatus(TreeSnapshot tree, NodeSnapshot skill) {
        if (skill.unlocked()) {
            return Component.translatable("screen.crazy-world-progression.skill_trees.status.already_unlocked")
                    .withStyle(ChatFormatting.GREEN);
        }
        if (skill.excluded()) {
            return Component.translatable("screen.crazy-world-progression.skill_trees.status.branch_excluded")
                    .withStyle(ChatFormatting.RED);
        }
        if (!skill.prerequisitesUnlocked()) {
            return Component.translatable("screen.crazy-world-progression.skill_trees.status.previous_required")
                    .withStyle(ChatFormatting.GRAY);
        }
        if (!skill.affordable()) {
            return Component.translatable("screen.crazy-world-progression.skill_trees.status.not_enough_currency")
                    .withStyle(ChatFormatting.RED);
        }
        if (skill.canPurchase()) {
            return Component.translatable("screen.crazy-world-progression.skill_trees.status.hold_to_unlock")
                    .withStyle(ChatFormatting.YELLOW);
        }
        if (tree.type() == SkillTreeType.GLOBAL && !snapshot.electedKing()) {
            return Component.translatable("screen.crazy-world-progression.skill_trees.status.king_only_unlock")
                    .withStyle(ChatFormatting.RED);
        }
        return Component.translatable("screen.crazy-world-progression.skill_trees.status.unavailable")
                .withStyle(ChatFormatting.GRAY);
    }

    // Return the width of the longest explicit description line before wrapping.
    private int widestLineWidth(String text) {
        int widest = 0;
        for (String line : text.split("\\R", -1)) {
            widest = Math.max(widest, font.width(line));
        }
        return widest;
    }

    // Resolve the complete visual style for one authoritative node state.
    private static NodeVisualStyle nodeVisualStyle(NodeSnapshot skill) {
        if (skill.unlocked()) {
            return NodeVisualStyle.UNLOCKED;
        }
        if (skill.excluded()) {
            return NodeVisualStyle.EXCLUDED;
        }
        return skill.canPurchase() ? NodeVisualStyle.SKILLABLE : NodeVisualStyle.UNAVAILABLE;
    }

    // Return the current hold progress only while the cursor remains over the pressed node.
    private double holdProgress(NodeSnapshot skill) {
        if (skill.id().equals(pendingSkillId)) {
            return 1.0D;
        }
        if (!skill.canPurchase()
                || !skill.id().equals(pressedSkillId)
                || holdStartedAtNanoseconds == 0L
                || !isPressedOver(skill)) {
            return 0.0D;
        }
        long elapsed = System.nanoTime() - holdStartedAtNanoseconds;
        long duration = holdDurationNanoseconds(selectedTree(), skill);
        return Math.max(0.0D, Math.min(1.0D, elapsed / (double) duration));
    }

    // Select three seconds for restricted branch choices and 1.5 seconds for ordinary nodes.
    private static long holdDurationNanoseconds(TreeSnapshot tree, NodeSnapshot skill) {
        return isChoiceNode(tree, skill)
                ? CHOICE_HOLD_TO_UNLOCK_NANOSECONDS
                : STANDARD_HOLD_TO_UNLOCK_NANOSECONDS;
    }

    // Check whether a node is a direct option at a YAML-restricted split.
    private static boolean isChoiceNode(TreeSnapshot tree, NodeSnapshot skill) {
        if (tree == null) {
            return false;
        }
        for (String parentId : skill.previous()) {
            NodeSnapshot parent = tree.skills().stream()
                    .filter(candidate -> candidate.id().equals(parentId))
                    .findFirst()
                    .orElse(null);
            if (parent == null || parent.following() <= 0) {
                continue;
            }
            long branchCount = tree.skills().stream()
                    .filter(candidate -> candidate.previous().contains(parentId))
                    .count();
            if (branchCount > 1L) {
                return true;
            }
        }
        return false;
    }

    // Check whether the held mouse gesture is still positioned on the same node.
    private boolean isPressedOver(NodeSnapshot skill) {
        NodeSnapshot hovered = hoveredNode(selectedTree(), currentMouseX, currentMouseY);
        return skill.id().equals(pressedSkillId) && hovered != null && hovered.id().equals(skill.id());
    }

    // Check whether the cursor currently rests over a particular skill node.
    private boolean isHovered(NodeSnapshot skill) {
        NodeSnapshot hovered = hoveredNode(selectedTree(), currentMouseX, currentMouseY);
        return hovered != null && hovered.id().equals(skill.id());
    }

    // Format costs normally when affordable and grey out each individually insufficient currency.
    private static List<CurrencyAmount> formatCosts(SkillTreeType type, SkillCosts costs, Balances balances) {
        List<CurrencyAmount> result = new ArrayList<>();
        if (type == SkillTreeType.GLOBAL && costs.kingdomPoints() > 0L) {
            appendCost(
                    result,
                    costs.kingdomPoints(),
                    CurrencyDisplay.KINGDOM_POINTS,
                    KINGDOM_POINTS_ICON,
                    balances.kingdomPoints() >= costs.kingdomPoints()
            );
        }
        if (type == SkillTreeType.PERSONAL && costs.echelonPoints() > 0L) {
            appendCost(
                    result,
                    costs.echelonPoints(),
                    CurrencyDisplay.ECHELON_POINTS,
                    ECHELON_POINTS_ICON,
                    balances.echelonPoints() >= costs.echelonPoints()
            );
        }
        if (costs.fakhrulCurrency() > 0L) {
            appendCost(
                    result,
                    costs.fakhrulCurrency(),
                    CurrencyDisplay.FAKHRUL_CURRENCY,
                    FAKHRUL_CURRENCY_ICON,
                    (type == SkillTreeType.GLOBAL
                            ? balances.kingFakhrulCurrency()
                            : balances.fakhrulCurrency()) >= costs.fakhrulCurrency()
            );
        }
        if (type == SkillTreeType.PERSONAL && costs.powerfulSouls() > 0L) {
            appendCost(
                    result,
                    costs.powerfulSouls(),
                    CurrencyDisplay.POWERFUL_SOULS,
                    POWERFUL_SOULS_ICON,
                    balances.powerfulSouls() >= costs.powerfulSouls()
            );
        }
        return result;
    }

    // Append one cost together with its icon and individual affordability state.
    private static void appendCost(
            List<CurrencyAmount> costs,
            long amount,
            CurrencyDisplay currency,
            CurrencyIcon icon,
            boolean affordable
    ) {
        costs.add(new CurrencyAmount(amount, currency, icon, affordable));
    }

    // Handle Done, tabs, the start of hold-to-unlock, and canvas dragging.
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        for (int index = 0; index < snapshot.trees().size(); index++) {
            TabPlacement placement = tabPlacement(index);
            if (placement != null && placement.type().isMouseOver(
                    leftPos,
                    topPos,
                    placement.index(),
                    event.x(),
                    event.y()
            )) {
                selectTree(snapshot.trees().get(index).id());
                return true;
            }
        }
        if (event.button() == 0 && isInsideCanvas(event.x(), event.y())) {
            TreeSnapshot tree = selectedTree();
            NodeSnapshot node = tree == null ? null : hoveredNode(tree, event.x(), event.y());
            if (node != null) {
                pressedSkillId = node.id();
                holdStartedAtNanoseconds = node.canPurchase() && !node.id().equals(pendingSkillId)
                        ? System.nanoTime()
                        : 0L;
                lastHoldSoundStep = 0;
                playUiSound(SoundEvents.STONE_BUTTON_CLICK_ON, 1.0F, 0.45F);
                currentMouseX = (int) event.x();
                currentMouseY = (int) event.y();
            } else if (node == null) {
                draggingCanvas = true;
            }
            return true;
        }
        return false;
    }

    // Pan the selected tree while the player drags inside its canvas.
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pressedSkillId != null && event.button() == 0) {
            return true;
        }
        if (draggingCanvas && event.button() == 0) {
            selectedCanvasState().pan(dragX, dragY);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    // Cancel an incomplete hold or stop canvas panning when the mouse is released.
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && pressedSkillId != null) {
            playUiSound(SoundEvents.STONE_BUTTON_CLICK_OFF, 1.0F, 0.35F);
            clearHoldToUnlock();
            return true;
        }
        if (event.button() == 0 && draggingCanvas) {
            draggingCanvas = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    // Complete a purchase after the node's uninterrupted ordinary or choice hold duration.
    @Override
    public void tick() {
        super.tick();
        long now = System.nanoTime();
        if (poppingSkillId != null && now - popStartedAtNanoseconds >= COMPLETION_POP_NANOSECONDS) {
            poppingSkillId = null;
            popStartedAtNanoseconds = 0L;
        }
        if (pressedSkillId == null) {
            return;
        }
        TreeSnapshot tree = selectedTree();
        NodeSnapshot hovered = hoveredNode(tree, currentMouseX, currentMouseY);
        if (hovered == null || !hovered.id().equals(pressedSkillId) || !hovered.canPurchase()) {
            holdStartedAtNanoseconds = 0L;
            lastHoldSoundStep = 0;
            return;
        }
        if (holdStartedAtNanoseconds == 0L) {
            holdStartedAtNanoseconds = now;
            lastHoldSoundStep = 0;
            return;
        }
        long holdElapsed = now - holdStartedAtNanoseconds;
        long holdDuration = holdDurationNanoseconds(tree, hovered);
        if (holdElapsed >= holdDuration) {
            String completedSkillId = pressedSkillId;
            pendingTreeId = tree.id();
            pendingSkillId = completedSkillId;
            playUiSound(SoundEvents.STONE_BUTTON_CLICK_OFF, 1.0F, 0.35F);
            clearHoldToUnlock();
            purchase(tree.id(), completedSkillId);
            return;
        }
        playHoldProgressSound(holdElapsed, holdDuration);
    }

    // Play one rising timer tick whenever the hold crosses another progress interval.
    private void playHoldProgressSound(long elapsedNanoseconds, long holdDurationNanoseconds) {
        int soundStep = (int) (elapsedNanoseconds * HOLD_PROGRESS_SOUND_STEPS / holdDurationNanoseconds);
        if (soundStep <= lastHoldSoundStep) {
            return;
        }
        lastHoldSoundStep = soundStep;
        float pitch = 0.75F + 0.1F * soundStep;
        playUiSound(SoundEvents.NOTE_BLOCK_HAT.value(), pitch, 0.3F);
    }

    // Play one non-positional vanilla sound through the player's UI sound channel.
    private void playUiSound(SoundEvent sound, float pitch, float volume) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    // Reset every field involved in the current hold-to-unlock gesture.
    private void clearHoldToUnlock() {
        pressedSkillId = null;
        holdStartedAtNanoseconds = 0L;
        lastHoldSoundStep = 0;
    }

    // Scroll the selected tree in both axes using Minecraft's wheel deltas.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isInsideCanvas(mouseX, mouseY) && selectedTree() != null) {
            selectedCanvasState().pan(horizontalAmount * 16.0D, verticalAmount * 16.0D);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // Switch to another YAML-backed tab without resetting a previously visited canvas.
    private void selectTree(String treeId) {
        clearHoldToUnlock();
        draggingCanvas = false;
        selectedTreeId = treeId;
        rebuildNodeLayout();
    }

    // Send a clicked node to the server for authoritative validation and deduction.
    private static void purchase(String treeId, String skillId) {
        ClientPlayNetworking.send(new SkillTreeNetworking.PurchaseSkillPayload(treeId, skillId));
    }

    // Return the node currently under the cursor inside the clipped canvas.
    private NodeSnapshot hoveredNode(TreeSnapshot tree, double mouseX, double mouseY) {
        if (tree == null || !isInsideCanvas(mouseX, mouseY)) {
            return null;
        }
        for (NodeSnapshot skill : tree.skills()) {
            NodePosition position = nodePositions.get(skill.id());
            if (position == null) {
                continue;
            }
            int x = nodeScreenX(position);
            int y = nodeScreenY(position);
            if (mouseX >= x && mouseX < x + NODE_SIZE && mouseY >= y && mouseY < y + NODE_SIZE) {
                return skill;
            }
        }
        return null;
    }

    // Resolve and cache a configured item id, falling back to a book when it is invalid.
    private ItemStack icon(String itemId) {
        return iconCache.computeIfAbsent(itemId, key -> {
            Identifier identifier = Identifier.tryParse(key);
            if (identifier == null) {
                return new ItemStack(Items.BOOK);
            }
            return new ItemStack(BuiltInRegistries.ITEM.getOptional(identifier).orElse(Items.BOOK));
        });
    }

    // Place global tabs on the left and personal tabs on the right of the window.
    private TabPlacement tabPlacement(int absoluteIndex) {
        TreeSnapshot tree = snapshot.trees().get(absoluteIndex);
        int scopeIndex = 0;
        for (int index = 0; index < absoluteIndex; index++) {
            if (snapshot.trees().get(index).type() == tree.type()) {
                scopeIndex++;
            }
        }
        AdvancementTabType primary = tree.type() == SkillTreeType.GLOBAL
                ? AdvancementTabType.LEFT
                : AdvancementTabType.RIGHT;
        if (scopeIndex < primary.getMax()) {
            return new TabPlacement(primary, scopeIndex);
        }

        AdvancementTabType overflow = tree.type() == SkillTreeType.GLOBAL
                ? AdvancementTabType.ABOVE
                : AdvancementTabType.BELOW;
        int overflowIndex = scopeIndex - primary.getMax();
        return overflowIndex < overflow.getMax() ? new TabPlacement(overflow, overflowIndex) : null;
    }

    // Return the selected tab or null while no definitions are available.
    private TreeSnapshot selectedTree() {
        return snapshot.trees().stream()
                .filter(tree -> tree.id().equals(selectedTreeId))
                .findFirst()
                .orElse(null);
    }

    // Return the mutable panning state belonging to the selected tree.
    private CanvasState selectedCanvasState() {
        return canvasStates.computeIfAbsent(selectedTreeId, ignored -> new CanvasState());
    }

    // Convert a node's local horizontal position into a screen coordinate.
    private int nodeScreenX(NodePosition position) {
        return localScreenX(position.x());
    }

    // Convert a node's local vertical position into a screen coordinate.
    private int nodeScreenY(NodePosition position) {
        return localScreenY(position.y());
    }

    // Convert any graph-local horizontal coordinate into a screen coordinate.
    private int localScreenX(int localX) {
        return canvasX() + (int) Math.round(selectedCanvasState().offsetX) + localX;
    }

    // Convert any graph-local vertical coordinate into a screen coordinate.
    private int localScreenY(int localY) {
        return canvasY() + (int) Math.round(selectedCanvasState().offsetY) + localY;
    }

    // Return the left edge of the advancement canvas.
    private int canvasX() {
        return leftPos + CANVAS_OFFSET_X;
    }

    // Return the top edge of the advancement canvas.
    private int canvasY() {
        return topPos + CANVAS_OFFSET_Y;
    }

    // Test whether a pointer coordinate falls inside the clipped advancement canvas.
    private boolean isInsideCanvas(double mouseX, double mouseY) {
        return mouseX >= canvasX()
                && mouseX < canvasX() + CANVAS_WIDTH
                && mouseY >= canvasY()
                && mouseY < canvasY() + CANVAS_HEIGHT;
    }

    // Close back to the inventory or screen that originally opened this overlay.
    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    // Keep gameplay paused only when the parent screen would already pause it.
    @Override
    public boolean isPauseScreen() {
        return parent != null && parent.isPauseScreen();
    }

    private record RenderedConnection(ConnectorRoute route, int color, int priority) {
    }

    private enum NodeVisualStyle {
        UNLOCKED(AdvancementWidgetType.OBTAINED, 0xFFFFFFFF, 0xFFD7D7D7, UNLOCKED_GOLD),
        SKILLABLE(AdvancementWidgetType.UNOBTAINED, 0xFFFFFFFF, 0xFFD7D7D7, 0xFF929292),
        UNAVAILABLE(AdvancementWidgetType.UNOBTAINED, 0xFFAFAFAF, 0xFF939393, 0xFF4A4A4A),
        EXCLUDED(
                AdvancementWidgetType.UNOBTAINED,
                EXCLUDED_FRAME_TINT,
                EXCLUDED_FRAME_HOVER_TINT,
                EXCLUDED_FRAME_TINT
        );

        private final AdvancementWidgetType widgetType;
        private final int normalTint;
        private final int hoverTint;
        private final int headerColor;

        // Store the frame and colors belonging to one node state.
        NodeVisualStyle(
                AdvancementWidgetType widgetType,
                int normalTint,
                int hoverTint,
                int headerColor
        ) {
            this.widgetType = widgetType;
            this.normalTint = normalTint;
            this.hoverTint = hoverTint;
            this.headerColor = headerColor;
        }

        // Return the direct frame tint for the current pointer state.
        private int frameTint(boolean hovered) {
            return hovered ? hoverTint : normalTint;
        }

        // Return the vanilla advancement frame family for this state.
        private AdvancementWidgetType widgetType() {
            return widgetType;
        }

        // Return the matching hover-card header color.
        private int headerColor() {
            return headerColor;
        }
    }

    private record TabPlacement(AdvancementTabType type, int index) {
    }

    private record CurrencyAmount(
            long amount,
            CurrencyDisplay display,
            CurrencyIcon icon,
            boolean affordable
    ) {
    }

    private record CurrencyIcon(
            Identifier texture,
            int u,
            int v,
            int width,
            int height
    ) {
    }

    private static final class CanvasState {
        private double offsetX;
        private double offsetY;
        private int minimumX;
        private int minimumY;
        private int maximumX;
        private int maximumY;
        private boolean centered;

        // Update the content bounds and center the canvas only on its first layout.
        private void setBounds(int minimumX, int minimumY, int maximumX, int maximumY) {
            this.minimumX = minimumX;
            this.minimumY = minimumY;
            this.maximumX = maximumX;
            this.maximumY = maximumY;
            if (!centered) {
                offsetX = (CANVAS_WIDTH - minimumX - maximumX) / 2.0D;
                offsetY = (CANVAS_HEIGHT - minimumY - maximumY) / 2.0D;
                centered = true;
            }
            clamp();
        }

        // Apply a drag or wheel delta and keep the skill graph within useful pan limits.
        private void pan(double horizontalAmount, double verticalAmount) {
            offsetX += horizontalAmount;
            offsetY += verticalAmount;
            clamp();
        }

        // Keep large graphs reachable and keep small graphs centered in the window.
        private void clamp() {
            offsetX = clampAxis(offsetX, minimumX, maximumX, CANVAS_WIDTH);
            offsetY = clampAxis(offsetY, minimumY, maximumY, CANVAS_HEIGHT);
        }

        // Clamp one axis or center it when its content is smaller than the canvas.
        private static double clampAxis(double value, int minimum, int maximum, int viewportSize) {
            int contentSize = maximum - minimum;
            if (contentSize + CANVAS_MARGIN * 2 <= viewportSize) {
                return (viewportSize - minimum - maximum) / 2.0D;
            }
            double minimumOffset = viewportSize - maximum - CANVAS_MARGIN;
            double maximumOffset = CANVAS_MARGIN - minimum;
            return Math.max(minimumOffset, Math.min(maximumOffset, value));
        }
    }
}
