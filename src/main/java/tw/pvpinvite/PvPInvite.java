package tw.pvpinvite;

import org.bukkit.event.entity.PlayerDeathEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PvPInvite extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter {

    private static final long INVITE_TIMEOUT_MS = 60_000L;

    /*
     * inviter UUID -> invite
     */
    private final Map<UUID, Invite> pendingByInviter = new HashMap<>();

    /*
     * target UUID -> invite
     */
    private final Map<UUID, Invite> pendingByTarget = new HashMap<>();

    /*
     * Active PvP pairs.
     */
    private final Set<PairKey> activePairs = new HashSet<>();


    @Override
    public void onEnable() {

        Bukkit.getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("pvp"))
                .setExecutor(this);

        Objects.requireNonNull(getCommand("pvp"))
                .setTabCompleter(this);

        /*
         * 每秒檢查過期邀請。
         * 不會每秒寫 Log。
         */
        Bukkit.getScheduler().runTaskTimer(
                this,
                this::cleanupExpiredInvites,
                20L,
                20L
        );

        getLogger().info("PvPInvite enabled.");
    }


    @Override
    public void onDisable() {

        pendingByInviter.clear();
        pendingByTarget.clear();
        activePairs.clear();
    }


    /*
     * 清理過期邀請
     */
    private void cleanupExpiredInvites() {

        long now = System.currentTimeMillis();

        Iterator<Invite> iterator =
                pendingByInviter.values().iterator();

        while (iterator.hasNext()) {

            Invite invite = iterator.next();

            if (invite.expiresAt <= now) {

                iterator.remove();

                pendingByTarget.remove(invite.target);

                Player target =
                        Bukkit.getPlayer(invite.target);

                if (target != null) {

                    target.sendMessage(
                            Component.text(
                                    "⚔ PvP 邀請已過期。",
                                    NamedTextColor.GRAY
                            )
                    );
                }
            }
        }
    }


    /*
     * 發送 PvP 邀請
     */
    private void sendInvite(Player inviter, Player target) {

        if (inviter.equals(target)) {

            inviter.sendMessage(
                    Component.text(
                            "不能邀請自己。",
                            NamedTextColor.RED
                    )
            );

            return;
        }


        PairKey pair =
                new PairKey(
                        inviter.getUniqueId(),
                        target.getUniqueId()
                );


        if (activePairs.contains(pair)) {

            inviter.sendMessage(
                    Component.text(
                            "你們已經處於 PvP 狀態。",
                            NamedTextColor.RED
                    )
            );

            return;
        }


        /*
         * 發送者已經有邀請
         */
        Invite oldOutgoing =
                pendingByInviter.get(
                        inviter.getUniqueId()
                );

        if (oldOutgoing != null) {

            inviter.sendMessage(
                    Component.text(
                            "你已經有一個待處理的 PvP 邀請。",
                            NamedTextColor.RED
                    )
            );

            return;
        }


        /*
         * 發送者自己正在被邀請
         */
        Invite oldIncoming =
                pendingByTarget.get(
                        inviter.getUniqueId()
                );

        if (oldIncoming != null) {

            inviter.sendMessage(
                    Component.text(
                            "你有一個尚未處理的 PvP 邀請，請先接受或拒絕。",
                            NamedTextColor.RED
                    )
            );

            return;
        }


        /*
         * 對方已經有邀請
         */
        if (pendingByTarget.containsKey(
                target.getUniqueId()
        )) {

            inviter.sendMessage(
                    Component.text(
                            target.getName()
                                    + " 已經有一個待處理的 PvP 邀請。",
                            NamedTextColor.RED
                    )
            );

            return;
        }


        Invite invite = new Invite(
                inviter.getUniqueId(),
                target.getUniqueId(),
                System.currentTimeMillis()
                        + INVITE_TIMEOUT_MS
        );


        pendingByInviter.put(
                inviter.getUniqueId(),
                invite
        );

        pendingByTarget.put(
                target.getUniqueId(),
                invite
        );


        inviter.sendMessage(
                Component.text(
                        "已向 "
                                + target.getName()
                                + " 發送 PvP 邀請。",
                        NamedTextColor.GREEN
                )
        );


        /*
         * 接受按鈕
         */
        Component accept =
                Component.text(
                        " ✓ 接受 ",
                        NamedTextColor.GREEN
                )
                .clickEvent(
                        ClickEvent.runCommand(
                                "/pvp accept"
                        )
                )
                .hoverEvent(
                        HoverEvent.showText(
                                Component.text(
                                        "接受 PvP 邀請"
                                )
                        )
                );


        /*
         * 拒絕按鈕
         */
        Component deny =
                Component.text(
                        " ✕ 拒絕 ",
                        NamedTextColor.RED
                )
                .clickEvent(
                        ClickEvent.runCommand(
                                "/pvp deny"
                        )
                )
                .hoverEvent(
                        HoverEvent.showText(
                                Component.text(
                                        "拒絕 PvP 邀請"
                                )
                        )
                );


        target.sendMessage(
                Component.text(
                        "⚔ PvP 邀請",
                        NamedTextColor.GOLD
                )
        );


        target.sendMessage(
                Component.text(
                        inviter.getName()
                                + " 邀請你進行 PvP！",
                        NamedTextColor.WHITE
                )
        );


        target.sendMessage(
                accept
                        .append(
                                Component.text("   ")
                        )
                        .append(deny)
        );


        target.sendMessage(
                Component.text(
                        "邀請將在 60 秒後失效。",
                        NamedTextColor.GRAY
                )
        );
    }


    /*
     * 接受邀請
     */
    private void acceptInvite(Player target) {

        Invite invite =
                pendingByTarget.remove(
                        target.getUniqueId()
                );


        if (invite == null) {

            target.sendMessage(
                    Component.text(
                            "沒有有效的 PvP 邀請。",
                            NamedTextColor.RED
                    )
            );

            return;
        }


        if (invite.expiresAt
                <= System.currentTimeMillis()) {

            pendingByInviter.remove(
                    invite.inviter
            );

            target.sendMessage(
                    Component.text(
                            "PvP 邀請已過期。",
                            NamedTextColor.RED
                    )
            );

            return;
        }


        pendingByInviter.remove(
                invite.inviter
        );


        Player inviter =
                Bukkit.getPlayer(invite.inviter);


        if (inviter == null) {

            target.sendMessage(
                    Component.text(
                            "邀請者已離線，邀請失效。",
                            NamedTextColor.RED
                    )
            );

            return;
        }


        PairKey pair =
                new PairKey(
                        invite.inviter,
                        invite.target
                );


        activePairs.add(pair);


        Component message =
                Component.text(
                        "⚔ PvP 已開始！ ",
                        NamedTextColor.GOLD
                )
                .append(
                        Component.text(
                                inviter.getName()
                                        + " ↔ "
                                        + target.getName(),
                                NamedTextColor.WHITE
                        )
                );


        inviter.sendMessage(message);
        target.sendMessage(message);
    }


    /*
     * 拒絕邀請
     */
    private void denyInvite(Player target) {

        Invite invite =
                pendingByTarget.remove(
                        target.getUniqueId()
                );


        if (invite == null) {

            target.sendMessage(
                    Component.text(
                            "沒有有效的 PvP 邀請。",
                            NamedTextColor.RED
                    )
            );

            return;
        }


        if (invite.expiresAt
                <= System.currentTimeMillis()) {

            pendingByInviter.remove(
                    invite.inviter
            );

            target.sendMessage(
                    Component.text(
                            "PvP 邀請已過期。",
                            NamedTextColor.RED
                    )
            );

            return;
        }


        pendingByInviter.remove(
                invite.inviter
        );


        target.sendMessage(
                Component.text(
                        "已拒絕 PvP 邀請。",
                        NamedTextColor.GRAY
                )
        );


        Player inviter =
                Bukkit.getPlayer(invite.inviter);


        if (inviter != null) {

            inviter.sendMessage(
                    Component.text(
                            target.getName()
                                    + " 拒絕了你的 PvP 邀請。",
                            NamedTextColor.RED
                    )
            );
        }
    }


    /*
     * 取消 PvP
     */
    private void cancelPvP(Player player) {

        UUID uuid =
                player.getUniqueId();


        activePairs.removeIf(
                pair -> pair.contains(uuid)
        );


        /*
         * 取消發出的邀請
         */
        Invite outgoing =
                pendingByInviter.remove(uuid);


        if (outgoing != null) {

            pendingByTarget.remove(
                    outgoing.target
            );
        }


        /*
         * 取消收到的邀請
         */
        Invite incoming =
                pendingByTarget.remove(uuid);


        if (incoming != null) {

            pendingByInviter.remove(
                    incoming.inviter
            );
        }


        player.sendMessage(
                Component.text(
                        "你的 PvP 狀態與待處理邀請已取消。",
                        NamedTextColor.YELLOW
                )
        );
    }


    /*
     * 判斷兩個玩家是否可以 PvP
     */
    private boolean isPvPAllowed(
            UUID first,
            UUID second
    ) {

        return activePairs.contains(
                new PairKey(first, second)
        );
    }


    /*
     * PvP 傷害控制
     *
     * 沒有接受邀請：
     *     玩家打玩家 = 阻擋
     *
     * 已接受：
     *     配對雙方 = 可以打
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onDamage(
            EntityDamageByEntityEvent event
    ) {

        Player attacker =
                getAttackingPlayer(
                        event.getDamager()
                );


        Player victim =
                event.getEntity()
                        instanceof Player player
                        ? player
                        : null;


        if (attacker == null
                || victim == null
                || attacker.equals(victim)) {

            return;
        }


        if (!isPvPAllowed(
                attacker.getUniqueId(),
                victim.getUniqueId()
        )) {

            event.setCancelled(true);
        }
    }


    /*
     * 支援玩家直接攻擊以及弓箭等 Projectile
     */
    private Player getAttackingPlayer(
            Entity entity
    ) {

        if (entity instanceof Player player) {

            return player;
        }


        if (entity instanceof Projectile projectile
                && projectile.getShooter()
                        instanceof Player player) {

            return player;
        }


        return null;
    }


   /*
 * 玩家死亡
 *
 * PvP 對戰中只要其中一人死亡：
 * 1. 立即結束 PvP
 * 2. 宣布勝負
 * 3. 移除這組 active pair
 */
@EventHandler
public void onDeath(PlayerDeathEvent event) {

    Player loser = event.getEntity();

    UUID loserUUID = loser.getUniqueId();

    PairKey battle = null;

    /*
     * 找到死亡玩家目前的 PvP 對手
     */
    for (PairKey pair : activePairs) {

        if (pair.contains(loserUUID)) {

            battle = pair;
            break;
        }
    }

    /*
     * 死亡者沒有正在 PvP
     */
    if (battle == null) {
        return;
    }

    /*
     * 找出勝者
     */
    UUID winnerUUID =
            battle.first().equals(loserUUID)
                    ? battle.second()
                    : battle.first();

    Player winner =
            Bukkit.getPlayer(winnerUUID);

    /*
     * 移除 PvP 狀態
     */
    activePairs.remove(battle);

    /*
     * 宣布結果
     */
    loser.sendMessage(
            Component.text(
                    "☠ 你輸了！",
                    NamedTextColor.RED
            )
    );

    if (winner != null) {

        winner.sendMessage(
                Component.text(
                        "⚔ 你贏了！",
                        NamedTextColor.GREEN
                )
        );

        winner.sendMessage(
                Component.text(
                        loser.getName()
                                + " 已被你擊敗。",
                        NamedTextColor.YELLOW
                )
        );
    }

    /*
     * 廣播這場 PvP 結束
     */
    Bukkit.broadcast(
            Component.text(
                    "⚔ PvP 對戰結束！ ",
                    NamedTextColor.GOLD
            )
            .append(
                    Component.text(
                            loser.getName(),
                            NamedTextColor.RED
                    )
            )
            .append(
                    Component.text(
                            " 敗北，",
                            NamedTextColor.WHITE
                    )
            )
            .append(
                    Component.text(
                            winner != null
                                    ? winner.getName()
                                    : "對手",
                            NamedTextColor.GREEN
                    )
            )
            .append(
                    Component.text(
                            " 獲勝！",
                            NamedTextColor.WHITE
                    )
            )
    );
}
   
    /*
     * 玩家離線
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        UUID uuid =
                event.getPlayer().getUniqueId();


        /*
         * 取消發出的邀請
         */
        Invite outgoing =
                pendingByInviter.remove(uuid);


        if (outgoing != null) {

            pendingByTarget.remove(
                    outgoing.target
            );


            Player target =
                    Bukkit.getPlayer(
                            outgoing.target
                    );


            if (target != null) {

                target.sendMessage(
                        Component.text(
                                "PvP 邀請者已離線，邀請失效。",
                                NamedTextColor.GRAY
                        )
                );
            }
        }


        /*
         * 取消收到的邀請
         */
        Invite incoming =
                pendingByTarget.remove(uuid);


        if (incoming != null) {

            pendingByInviter.remove(
                    incoming.inviter
            );


            Player inviter =
                    Bukkit.getPlayer(
                            incoming.inviter
                    );


            if (inviter != null) {

                inviter.sendMessage(
                        Component.text(
                                "PvP 邀請對象已離線，邀請失效。",
                                NamedTextColor.GRAY
                        )
                );
            }
        }


        /*
         * 離線後結束 PvP
         */
        activePairs.removeIf(
                pair -> pair.contains(uuid)
        );
    }


    /*
     * 指令
     */
    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (!(sender instanceof Player player)) {

            sender.sendMessage(
                    "此指令只能由玩家使用。"
            );

            return true;
        }


        if (args.length == 0) {

            player.sendMessage(
                    Component.text(
                            "/pvp <玩家> | /pvp accept | /pvp deny | /pvp cancel | /pvp status",
                            NamedTextColor.YELLOW
                    )
            );

            return true;
        }


        switch (args[0].toLowerCase(Locale.ROOT)) {

            case "accept" -> acceptInvite(player);

            case "deny" -> denyInvite(player);

            case "cancel", "leave" ->
                    cancelPvP(player);

            case "status" -> {

                boolean active =
                        activePairs.stream()
                                .anyMatch(
                                        pair -> pair.contains(
                                                player.getUniqueId()
                                        )
                                );


                if (active) {

                    player.sendMessage(
                            Component.text(
                                    "目前 PvP 狀態：啟用",
                                    NamedTextColor.GREEN
                            )
                    );

                } else {

                    player.sendMessage(
                            Component.text(
                                    "目前沒有啟用的 PvP 對戰。",
                                    NamedTextColor.GRAY
                            )
                    );
                }
            }


            default -> {

                if (args.length != 1) {

                    player.sendMessage(
                            Component.text(
                                    "用法：/pvp <玩家>",
                                    NamedTextColor.RED
                            )
                    );

                    return true;
                }


                Player target =
                        Bukkit.getPlayerExact(
                                args[0]
                        );


                if (target == null) {

                    player.sendMessage(
                            Component.text(
                                    "找不到在線玩家。",
                                    NamedTextColor.RED
                            )
                    );

                    return true;
                }


                sendInvite(
                        player,
                        target
                );
            }
        }


        return true;
    }


    /*
     * Tab Complete
     */
    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {

        if (!(sender instanceof Player player)) {

            return Collections.emptyList();
        }


        if (args.length == 1) {

            List<String> result =
                    new ArrayList<>(
                            List.of(
                                    "accept",
                                    "deny",
                                    "cancel",
                                    "status"
                            )
                    );


            for (Player online :
                    Bukkit.getOnlinePlayers()) {

                if (!online.equals(player)) {

                    result.add(
                            online.getName()
                    );
                }
            }


            String prefix =
                    args[0].toLowerCase(
                            Locale.ROOT
                    );


            return result.stream()
                    .filter(
                            name -> name
                                    .toLowerCase(Locale.ROOT)
                                    .startsWith(prefix)
                    )
                    .toList();
        }


        return Collections.emptyList();
    }


    /*
     * 邀請資料
     */
    private record Invite(
            UUID inviter,
            UUID target,
            long expiresAt
    ) {}


    /*
     * 無順序玩家配對
     *
     * A -> B
     * B -> A
     *
     * 會被視為同一組。
     */
    private record PairKey(
            UUID first,
            UUID second
    ) {

        PairKey {

            if (first.compareTo(second) > 0) {

                UUID temp = first;

                first = second;
                second = temp;
            }
        }


        boolean contains(UUID uuid) {

            return first.equals(uuid)
                    || second.equals(uuid);
        }
    }
}
