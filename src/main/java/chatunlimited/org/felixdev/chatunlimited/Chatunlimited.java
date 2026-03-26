package org.felixdev.chatunlimited;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Chatunlimited extends JavaPlugin implements Listener {

    private String chatFormat;
    private List<String> hoverText;
    private boolean papiEnabled;
    private boolean antiSpamEnabled;
    private boolean perWorldChat;
    private final Map<UUID, Long> lastMessageTimes = new HashMap<>();
    private final Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})|<#([A-Fa-f0-9]{6})>");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPluginConfig();
        papiEnabled = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("chatunlimited").setExecutor(this);
    }

    private void loadPluginConfig() {
        reloadConfig();
        this.chatFormat = getConfig().getString("chat.format", "{prefix} {player} » {message}");
        this.hoverText = getConfig().getStringList("chat.hover");
        this.antiSpamEnabled = getConfig().getBoolean("antispam.enabled", true);
        this.perWorldChat = getConfig().getBoolean("chat.per-world", false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (antiSpamEnabled && !player.hasPermission("chatunlimited.bypass.antispam")) {
            if (!checkAntispam(player)) {
                event.setCancelled(true);
                return;
            }
        }

        event.setCancelled(true);

        // Separar formato para insertar el componente de nombre
        String[] parts = chatFormat.split("\\{player\\}", 2);
        String prefixFinal = translateColor(applyPlaceholders(parts.length > 0 ? parts[0] : "", player));
        String suffixTemplate = parts.length > 1 ? parts[1] : " » {message}";
        String suffixFinal = translateColor(applyPlaceholders(suffixTemplate, player)).replace("{message}", message);

        TextComponent finalComponent = new TextComponent("");

        // Añadir Prefijo
        for (BaseComponent bc : TextComponent.fromLegacyText(prefixFinal)) {
            finalComponent.addExtra(bc);
        }

        // Añadir Nombre con Hover
        TextComponent nameComponent = new TextComponent(player.getName());
        String hoverContent = hoverText.stream()
                .map(line -> translateColor(applyPlaceholders(line, player)))
                .collect(Collectors.joining("\n"));
        nameComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hoverContent)));
        nameComponent.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/msg " + player.getName() + " "));
        finalComponent.addExtra(nameComponent);

        // Añadir Sufijo + Mensaje
        for (BaseComponent bc : TextComponent.fromLegacyText(suffixFinal)) {
            finalComponent.addExtra(bc);
        }

        if (perWorldChat) {
            player.getWorld().getPlayers().forEach(p -> p.spigot().sendMessage(finalComponent));
        } else {
            Bukkit.getOnlinePlayers().forEach(p -> p.spigot().sendMessage(finalComponent));
        }
        Bukkit.getConsoleSender().sendMessage("[" + player.getWorld().getName() + "] " + player.getName() + ": " + message);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 1. Mensaje global de entrada
        String joinMsg = getConfig().getString("messages.join", "&a+ &7{player}");
        if (joinMsg != null && !joinMsg.isEmpty()) {
            event.setJoinMessage(translateColor(applyPlaceholders(joinMsg.replace("{player}", player.getName()), player)));
        }

        // 2. MOTD Privado al entrar (Mensaje en el chat para el jugador)
        List<String> motdLines = getConfig().getStringList("messages.motd-chat");
        if (motdLines != null && !motdLines.isEmpty()) {
            for (String line : motdLines) {
                player.sendMessage(translateColor(applyPlaceholders(line.replace("{player}", player.getName()), player)));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String msg = getConfig().getString("messages.quit", "&c- &7{player}");
        if (msg != null && !msg.isEmpty()) {
            event.setQuitMessage(translateColor(applyPlaceholders(msg.replace("{player}", event.getPlayer().getName()), event.getPlayer())));
        }
    }

    private boolean checkAntispam(Player player) {
        long now = System.currentTimeMillis();
        long last = lastMessageTimes.getOrDefault(player.getUniqueId(), 0L);
        int cooldown = getConfig().getInt("antispam.cooldown-time", 2) * 1000;

        if (now - last < cooldown) {
            String msg = getConfig().getString("antispam.cooldown-message", "&c¡Espera!");
            player.sendMessage(translateColor(msg.replace("{time}", String.valueOf((cooldown - (now - last)) / 1000 + 1))));
            return false;
        }
        lastMessageTimes.put(player.getUniqueId(), now);
        return true;
    }

    private String applyPlaceholders(String text, Player player) {
        if (text == null) return "";
        String result = text
                .replace("{world}", player.getWorld().getName())
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{max_players}", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("{ping}", String.valueOf(player.getPing()))
                .replace("{prefix}", getBasicPrefix(player))
                .replace("{rank}", getBasicRank(player));

        if (papiEnabled) {
            result = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, result);
        }
        return result;
    }

    private String translateColor(String text) {
        if (text == null) return "";
        Matcher matcher = hexPattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) replacement.append('§').append(c);
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    private String getBasicPrefix(Player p) {
        return p.isOp() ? "&c[Admin]" : "&7[Usuario]";
    }

    private String getBasicRank(Player p) {
        return p.isOp() ? "Admin" : "Miembro";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("chatunlimited.reload")) {
                sender.sendMessage(translateColor(getConfig().getString("messages.no-permission")));
                return true;
            }
            loadPluginConfig();
            sender.sendMessage(translateColor(getConfig().getString("messages.reload-success")));
            return true;
        }
        return false;
    }
}