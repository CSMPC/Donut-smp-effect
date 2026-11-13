package com.donutbets;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DonutBetsClient implements ClientModInitializer {

    // "Money $5.2M", "Money: 1,234,567", etc.
    private static final Pattern MONEY = Pattern.compile(
            "(?i)money\\W*\\$?\\s*([0-9][0-9,]*\\.?[0-9]*)\\s*([kmbt])?");
    // "Steve paid you $5M"
    private static final Pattern RECEIVED = Pattern.compile(
            "(?i)(\\w{1,16}) paid you \\$?\\s*([0-9][0-9,]*\\.?[0-9]*)\\s*([kmbt])?");
    // "You paid Steve $5M"
    private static final Pattern SENT = Pattern.compile(
            "(?i)you paid (\\w{1,16}) \\$?\\s*([0-9][0-9,]*\\.?[0-9]*)\\s*([kmbt])?");

    private static double balance = -1;
    private static double startBalance = -1;
    private static double received = 0;
    private static double sent = 0;
    private static final List<String> recent = new ArrayList<>();

    private static boolean visible = true;
    private static int posX = 4;
    private static int posY = 4;
    private static int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        // Read balance from the sidebar a few times per second.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null) {
                // Left the world: next join starts a new session.
                startBalance = -1;
                balance = -1;
                return;
            }
            tickCounter++;
            if (tickCounter % 10 != 0) return;
            for (String line : sidebarLines(client)) {
                Matcher m = MONEY.matcher(line);
                if (m.find()) {
                    balance = parse(m.group(1), m.group(2));
                    if (startBalance < 0) startBalance = balance;
                    break;
                }
            }
        });

        // Watch chat + action bar for payments.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> handleMessage(message.getString()));

        // Draw the box.
        HudRenderCallback.EVENT.register((DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!visible || client.player == null || client.options.hudHidden) return;

            List<String> lines = new ArrayList<>();
            lines.add("DonutBets");
            lines.add("Balance: " + (balance < 0 ? "?" : fmt(balance)));
            if (balance >= 0 && startBalance >= 0) {
                double diff = balance - startBalance;
                lines.add("Session: " + (diff >= 0 ? "+" : "-") + fmt(Math.abs(diff)));
            }
            lines.add("Received: " + fmt(received));
            lines.add("Sent: " + fmt(sent));
            for (String r : recent) lines.add(r);

            int width = 0;
            for (String l : lines) width = Math.max(width, client.textRenderer.getWidth(l));
            int height = lines.size() * 10;

            context.fill(posX - 2, posY - 2, posX + width + 2, posY + height, 0x90000000);
            int y = posY;
            for (String l : lines) {
                context.drawText(client.textRenderer, l, posX, y, 0xFFFFFFFF, true);
                y += 10;
            }
        });

        // Commands: /donutbets dump | reset | toggle | pos <x> <y>
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("donutbets")
                        .then(ClientCommandManager.literal("dump").executes(ctx -> {
                            List<String> lines = sidebarLines(MinecraftClient.getInstance());
                            ctx.getSource().sendFeedback(Text.literal("Sidebar lines: " + lines.size()));
                            for (String l : lines) ctx.getSource().sendFeedback(Text.literal("[" + l + "]"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reset").executes(ctx -> {
                            received = 0;
                            sent = 0;
                            recent.clear();
                            startBalance = balance;
                            ctx.getSource().sendFeedback(Text.literal("DonutBets reset."));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                            visible = !visible;
                            ctx.getSource().sendFeedback(Text.literal("DonutBets box " + (visible ? "on" : "off")));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("pos")
                                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer(0, 4000))
                                        .then(ClientCommandManager.argument("y", IntegerArgumentType.integer(0, 4000))
                                                .executes(ctx -> {
                                                    posX = IntegerArgumentType.getInteger(ctx, "x");
                                                    posY = IntegerArgumentType.getInteger(ctx, "y");
                                                    return 1;
                                                }))))));
    }

    private static void handleMessage(String text) {
        Matcher in = RECEIVED.matcher(text);
        if (in.find()) {
            double amt = parse(in.group(2), in.group(3));
            received += amt;
            addRecent("+" + fmt(amt) + " from " + in.group(1));
            return;
        }
        Matcher out = SENT.matcher(text);
        if (out.find()) {
            double amt = parse(out.group(2), out.group(3));
            sent += amt;
            addRecent("-" + fmt(amt) + " to " + out.group(1));
        }
    }

    private static void addRecent(String s) {
        recent.add(0, s);
        while (recent.size() > 5) recent.remove(recent.size() - 1);
    }

    private static List<String> sidebarLines(MinecraftClient client) {
        List<String> lines = new ArrayList<>();
        if (client.world == null) return lines;
        Scoreboard sb = client.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj == null) return lines;
        for (ScoreboardEntry e : sb.getScoreboardEntries(obj)) {
            Team team = sb.getScoreHolderTeam(e.owner());
            Text name = Team.decorateName(team, e.name());
            lines.add(name.getString());
        }
        return lines;
    }

    private static double parse(String num, String suffix) {
        double v;
        try {
            v = Double.parseDouble(num.replace(",", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
        if (suffix != null) {
            switch (suffix.toLowerCase()) {
                case "k": v *= 1_000d; break;
                case "m": v *= 1_000_000d; break;
                case "b": v *= 1_000_000_000d; break;
                case "t": v *= 1_000_000_000_000d; break;
                default: break;
            }
        }
        return v;
    }

    private static String fmt(double v) {
        if (v >= 1e12) return String.format("$%.2fT", v / 1e12);
        if (v >= 1e9) return String.format("$%.2fB", v / 1e9);
        if (v >= 1e6) return String.format("$%.2fM", v / 1e6);
        if (v >= 1e3) return String.format("$%.2fK", v / 1e3);
        return String.format("$%.0f", v);
    }
}
