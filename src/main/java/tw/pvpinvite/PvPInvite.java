package tw.pvpinvite;

import org.bukkit.event.entity.PlayerDeathEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;

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

        private final Map<UUID, TeamRoom> roomsByPlayer = new HashMap<>();

        private final Set<TeamRoom> activeTeamBattles = new HashSet<>();

        private final Map<UUID, TeamInvite> pendingTeamInvites = new HashMap<>();

                private final Map<UUID, BossBar> activeBossBars = new HashMap<>();


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
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            cleanupExpiredInvites();
            updateBossBars();
        }, 20L, 20L);

        getLogger().info("PvPInvite enabled.");
    }


    @Override
    public void onDisable() {

        pendingByInviter.clear();
        pendingByTarget.clear();
        activePairs.clear();
        roomsByPlayer.clear();
        activeTeamBattles.clear();
        pendingTeamInvites.clear();
        activeBossBars.values().forEach(BossBar::removeAll);
        activeBossBars.clear();
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


        showInviteDialog(target, inviter.getName());
    }


    private void showModeSelectionDialog(Player inviter) {

        ActionButton oneVsOne =
                ActionButton.create(
                        Component.text("單挑（1v1）", NamedTextColor.GREEN),
                        Component.text("選擇一名玩家進行單挑"),
                        200,
                        DialogAction.staticAction(
                                ClickEvent.runCommand("/pvp select-1v1")
                        )
                );

        ActionButton teamBattle =
                ActionButton.create(
                        Component.text("多人組隊（1+v1+）", NamedTextColor.AQUA),
                        Component.text("建立或查看兩隊 PvP 房間"),
                        200,
                        DialogAction.staticAction(
                                ClickEvent.runCommand("/pvp select-team")
                        )
                );

        Dialog dialog = Dialog.create(builder -> builder
                .empty()
                .base(
                        DialogBase.create(
                                Component.text("選擇 PvP 模式", NamedTextColor.GOLD),
                                Component.text("選擇 PvP 模式"),
                                true,
                                false,
                                DialogBase.DialogAfterAction.CLOSE,
                                List.of(
                                        DialogBody.plainMessage(
                                                Component.text(
                                                        "請先選擇要進行的 PvP 模式。",
                                                        NamedTextColor.WHITE
                                                )
                                        )
                                ),
                                List.of()
                        )
                )
                .type(DialogType.multiAction(List.of(oneVsOne, teamBattle), null, 2))
        );

        inviter.showDialog(dialog);
    }


    private void showTeamRoomDialog(Player player) {

        TeamRoom room = roomsByPlayer.get(player.getUniqueId());

        if (room == null) {
            room = new TeamRoom(player.getUniqueId());
            roomsByPlayer.put(player.getUniqueId(), room);
            room.teamA.add(player.getUniqueId());
        }

        boolean owner = room.owner.equals(player.getUniqueId());
        boolean inBattle = activeTeamBattles.contains(room);
        List<ActionButton> buttons = new ArrayList<>();

        buttons.add(ActionButton.create(
                Component.text("A隊（" + room.teamA.size() + "人）", NamedTextColor.GREEN),
                Component.text(teamList(room.teamA)),
                180,
                DialogAction.staticAction(ClickEvent.runCommand("/pvp team-view a"))
        ));
        buttons.add(ActionButton.create(
                Component.text("B隊（" + room.teamB.size() + "人）", NamedTextColor.RED),
                Component.text(teamList(room.teamB)),
                180,
                DialogAction.staticAction(ClickEvent.runCommand("/pvp team-view b"))
        ));

        if (owner && !inBattle) {
            buttons.add(ActionButton.create(
                    Component.text("邀請玩家", NamedTextColor.YELLOW),
                    Component.text("選擇要加入的隊伍"),
                    180,
                    DialogAction.staticAction(ClickEvent.runCommand("/pvp team-invite"))
            ));
            buttons.add(ActionButton.create(
                    Component.text("開戰", NamedTextColor.GOLD),
                    Component.text("兩隊都有人後開始戰鬥"),
                    180,
                    DialogAction.staticAction(ClickEvent.runCommand("/pvp team-start"))
            ));
        }

        if (!owner && !inBattle) {
            buttons.add(ActionButton.create(
                    Component.text("退出房間", NamedTextColor.GRAY),
                    Component.text("離開目前的多人房間"),
                    180,
                    DialogAction.staticAction(ClickEvent.runCommand("/pvp team-leave"))
            ));
        }

        String position = room.teamA.contains(player.getUniqueId()) ? "你在A隊"
                : room.teamB.contains(player.getUniqueId()) ? "你在B隊" : "你不在隊伍中";
        List<DialogBody> body = List.of(DialogBody.plainMessage(Component.text(
                "多人組隊（1+v1+）\n" + position + "\n房主：" + playerName(room.owner)
                        + (inBattle ? "\n戰鬥進行中" : ""),
                NamedTextColor.WHITE)));

        Dialog dialog = Dialog.create(builder -> builder
                .empty()
                .base(DialogBase.create(
                        Component.text("多人組隊（1+v1+）", NamedTextColor.GOLD),
                        Component.text("多人組隊（1+v1+）"), true, false,
                        DialogBase.DialogAfterAction.CLOSE, body, List.of()))
                .type(DialogType.multiAction(buttons, null, 2)));

        player.showDialog(dialog);
    }


    private String teamList(Set<UUID> team) {
        if (team.isEmpty()) {
            return "目前沒有玩家";
        }
        return team.stream().map(this::playerName).collect(java.util.stream.Collectors.joining(", "));
    }


    private String playerName(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player == null ? uuid.toString().substring(0, 8) : player.getName();
    }


        private void showTeamView(Player player, String teamName) {
                TeamRoom room = roomsByPlayer.get(player.getUniqueId());
                if (room == null) {
                        player.sendMessage(Component.text("你目前不在多人房間。", NamedTextColor.RED));
                        return;
                }

                Set<UUID> team = teamName.equalsIgnoreCase("b") ? room.teamB : room.teamA;
                List<ActionButton> buttons = new ArrayList<>();
                if (room.owner.equals(player.getUniqueId()) && !activeTeamBattles.contains(room)) {
                        for (UUID member : team) {
                                if (!member.equals(room.owner)) {
                                        buttons.add(ActionButton.create(
                                                        Component.text("踢出 " + playerName(member), NamedTextColor.RED),
                                                        Component.text("從房間移除此玩家"), 180,
                                                        DialogAction.staticAction(ClickEvent.runCommand(
                                                                        "/pvp team-kick " + member))));
                                }
                        }
                }
                buttons.add(ActionButton.create(
                                Component.text("返回房間", NamedTextColor.GRAY),
                                Component.text("查看兩個小隊"), 180,
                                DialogAction.staticAction(ClickEvent.runCommand("/pvp team-room"))));

                Dialog dialog = Dialog.create(builder -> builder
                                .empty()
                                .base(DialogBase.create(
                                                Component.text(teamName.equalsIgnoreCase("b") ? "B隊玩家" : "A隊玩家", NamedTextColor.GOLD),
                                                Component.text("小隊玩家列表"), true, false,
                                                DialogBase.DialogAfterAction.CLOSE,
                                                List.of(DialogBody.plainMessage(Component.text(teamList(team), NamedTextColor.WHITE))),
                                                List.of()))
                                .type(DialogType.multiAction(buttons, null, 2)));
                player.showDialog(dialog);
        }


        private void showTeamInviteDialog(Player owner) {
                TeamRoom room = roomsByPlayer.get(owner.getUniqueId());
                if (room == null || !room.owner.equals(owner.getUniqueId())) {
                        owner.sendMessage(Component.text("只有房主可以邀請玩家。", NamedTextColor.RED));
                        return;
                }

                List<ActionButton> buttons = new ArrayList<>();
                for (Player target : Bukkit.getOnlinePlayers()) {
                        if (roomsByPlayer.containsKey(target.getUniqueId()) || target.equals(owner)) {
                                continue;
                        }
                        buttons.add(ActionButton.create(
                                        Component.text(target.getName()), Component.text("邀請加入多人房間"), 180,
                                        DialogAction.staticAction(ClickEvent.runCommand(
                                                        "/pvp team-invite-player " + target.getName()))));
                }
                if (buttons.isEmpty()) {
                        owner.sendMessage(Component.text("目前沒有可邀請的在線玩家。", NamedTextColor.GRAY));
                        return;
                }
                buttons.add(ActionButton.create(
                                Component.text("返回房間", NamedTextColor.GRAY), Component.text("返回多人房間"), 180,
                                DialogAction.staticAction(ClickEvent.runCommand("/pvp team-room"))));
                Dialog dialog = Dialog.create(builder -> builder
                                .empty()
                                .base(DialogBase.create(
                                                Component.text("邀請玩家", NamedTextColor.GOLD), Component.text("邀請玩家加入房間"),
                                                true, false, DialogBase.DialogAfterAction.CLOSE,
                                                List.of(DialogBody.plainMessage(Component.text("先選擇玩家，再選擇加入的隊伍。", NamedTextColor.WHITE))),
                                                List.of()))
                                .type(DialogType.multiAction(buttons, null, 2)));
                owner.showDialog(dialog);
        }


        private void chooseTeamForInvite(Player owner, Player target) {
                TeamRoom room = roomsByPlayer.get(owner.getUniqueId());
                if (room == null || !room.owner.equals(owner.getUniqueId())) {
                        return;
                }
                List<ActionButton> buttons = List.of(
                                ActionButton.create(Component.text("加入A隊", NamedTextColor.GREEN), Component.text("邀請加入A隊"), 180,
                                                DialogAction.staticAction(ClickEvent.runCommand("/pvp team-send " + target.getName() + " a"))),
                                ActionButton.create(Component.text("加入B隊", NamedTextColor.RED), Component.text("邀請加入B隊"), 180,
                                                DialogAction.staticAction(ClickEvent.runCommand("/pvp team-send " + target.getName() + " b"))));
                Dialog dialog = Dialog.create(builder -> builder
                                .empty()
                                .base(DialogBase.create(Component.text("選擇隊伍", NamedTextColor.GOLD), Component.text("選擇隊伍"),
                                                true, false, DialogBase.DialogAfterAction.CLOSE,
                                                List.of(DialogBody.plainMessage(Component.text("邀請 " + target.getName() + " 加入哪一隊？", NamedTextColor.WHITE))), List.of()))
                                .type(DialogType.multiAction(buttons, null, 2)));
                owner.showDialog(dialog);
        }


        private void sendTeamInvite(Player owner, Player target, boolean teamB) {
                TeamRoom room = roomsByPlayer.get(owner.getUniqueId());
                if (room == null || !room.owner.equals(owner.getUniqueId()) || roomsByPlayer.containsKey(target.getUniqueId())) {
                        owner.sendMessage(Component.text("無法邀請這名玩家。", NamedTextColor.RED));
                        return;
                }
                pendingTeamInvites.put(target.getUniqueId(), new TeamInvite(owner.getUniqueId(), teamB));
                owner.sendMessage(Component.text("已邀請 " + target.getName() + " 加入" + (teamB ? "B隊" : "A隊") + "。", NamedTextColor.GREEN));
                showTeamInviteAcceptance(target, owner.getName(), teamB);
        }


            private void showTeamInviteAcceptance(Player target, String ownerName, boolean teamB) {
                ActionButton accept = ActionButton.create(
                        Component.text("接受", NamedTextColor.GREEN), Component.text("加入多人房間"), 160,
                        DialogAction.staticAction(ClickEvent.runCommand("/pvp team-accept")));
                ActionButton deny = ActionButton.create(
                        Component.text("拒絕", NamedTextColor.RED), Component.text("拒絕房間邀請"), 160,
                        DialogAction.staticAction(ClickEvent.runCommand("/pvp team-deny")));
                Dialog dialog = Dialog.create(builder -> builder
                        .empty()
                        .base(DialogBase.create(
                                Component.text("多人房間邀請", NamedTextColor.GOLD), Component.text("多人房間邀請"),
                                true, false, DialogBase.DialogAfterAction.CLOSE,
                                List.of(DialogBody.plainMessage(Component.text(
                                        ownerName + " 邀請你加入多人房間的" + (teamB ? "B隊" : "A隊") + "。", NamedTextColor.WHITE))),
                                List.of()))
                        .type(DialogType.multiAction(List.of(accept, deny), null, 2)));
                target.showDialog(dialog);
            }


        private void acceptTeamInvite(Player player) {
                TeamInvite invite = pendingTeamInvites.remove(player.getUniqueId());
                TeamRoom room = invite == null ? null : roomsByPlayer.get(invite.owner);
                if (invite == null || room == null || activeTeamBattles.contains(room)) {
                        player.sendMessage(Component.text("沒有有效的多人房間邀請。", NamedTextColor.RED));
                        return;
                }
                roomsByPlayer.put(player.getUniqueId(), room);
                (invite.teamB ? room.teamB : room.teamA).add(player.getUniqueId());
                player.sendMessage(Component.text("你已加入多人房間的" + (invite.teamB ? "B隊" : "A隊") + "。", NamedTextColor.GREEN));
                showTeamRoomDialog(player);
        }


        private void leaveTeamRoom(Player player) {
                TeamRoom room = roomsByPlayer.get(player.getUniqueId());
                if (room == null) {
                        return;
                }
                if (room.owner.equals(player.getUniqueId())) {
                        player.sendMessage(Component.text("房主不能退出或被踢出房間。", NamedTextColor.RED));
                        return;
                }
                removeFromRoom(player.getUniqueId(), room);
                player.sendMessage(Component.text("你已退出多人房間。", NamedTextColor.GRAY));
        }


        private void kickFromTeamRoom(Player owner, UUID target) {
                TeamRoom room = roomsByPlayer.get(owner.getUniqueId());
                if (room == null || !room.owner.equals(owner.getUniqueId()) || target.equals(room.owner)
                                || activeTeamBattles.contains(room) || !roomsByPlayer.containsKey(target)) {
                        owner.sendMessage(Component.text("無法踢出這名玩家。", NamedTextColor.RED));
                        return;
                }
                removeFromRoom(target, room);
                Player kicked = Bukkit.getPlayer(target);
                if (kicked != null) {
                        kicked.sendMessage(Component.text("你已被房主踢出多人房間。", NamedTextColor.RED));
                }
                showTeamRoomDialog(owner);
        }


        private void removeFromRoom(UUID uuid, TeamRoom room) {
                roomsByPlayer.remove(uuid);
                room.teamA.remove(uuid);
                room.teamB.remove(uuid);
        }


    private void showPlayerSelectionDialog(Player inviter) {

        List<ActionButton> playerButtons =
                new ArrayList<>();

        for (Player target : Bukkit.getOnlinePlayers()) {

            if (target.equals(inviter)) {
                continue;
            }

            playerButtons.add(
                    ActionButton.create(
                            Component.text(target.getName()),
                            Component.text("邀請 " + target.getName() + " 進行 PvP"),
                            160,
                            DialogAction.staticAction(
                                    ClickEvent.runCommand(
                                            "/pvp " + target.getName()
                                    )
                            )
                    )
            );
        }

        if (playerButtons.isEmpty()) {

            inviter.sendMessage(
                    Component.text(
                            "目前沒有其他在線玩家可以邀請。",
                            NamedTextColor.GRAY
                    )
            );

            return;
        }

        Dialog dialog = Dialog.create(builder -> builder
                .empty()
                .base(
                        DialogBase.create(
                                Component.text("選擇 PvP 對手", NamedTextColor.GOLD),
                                Component.text("選擇 PvP 對手"),
                                true,
                                false,
                                DialogBase.DialogAfterAction.CLOSE,
                                List.of(
                                        DialogBody.plainMessage(
                                                Component.text(
                                                        "選擇一名在線玩家發送 PvP 邀請。",
                                                        NamedTextColor.WHITE
                                                )
                                        )
                                ),
                                List.of()
                        )
                )
                .type(DialogType.multiAction(playerButtons, null, 2))
        );

        inviter.showDialog(dialog);
    }


    private void showInviteDialog(Player target, String inviterName) {

        ActionButton accept =
                ActionButton.create(
                        Component.text("接受", NamedTextColor.GREEN),
                        Component.text("接受 PvP 邀請"),
                        160,
                        DialogAction.staticAction(
                                ClickEvent.runCommand("/pvp accept")
                        )
                );

        ActionButton deny =
                ActionButton.create(
                        Component.text("拒絕", NamedTextColor.RED),
                        Component.text("拒絕 PvP 邀請"),
                        160,
                        DialogAction.staticAction(
                                ClickEvent.runCommand("/pvp deny")
                        )
                );

        Dialog dialog = Dialog.create(builder -> builder
                .empty()
                .base(
                        DialogBase.create(
                                Component.text("PvP 邀請", NamedTextColor.GOLD),
                                Component.text("PvP 邀請"),
                                true,
                                false,
                                DialogBase.DialogAfterAction.CLOSE,
                                List.of(
                                        DialogBody.plainMessage(
                                                Component.text(
                                                        inviterName
                                                                + " 邀請你進行 PvP！\n\n"
                                                                + "邀請將在 60 秒後失效。",
                                                        NamedTextColor.WHITE
                                                )
                                        )
                                ),
                                List.of()
                        )
                )
                .type(DialogType.multiAction(List.of(accept, deny), null, 2))
        );

        target.showDialog(dialog);
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
        updateBossBars();


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
        updateBossBars();


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

                if (activePairs.contains(new PairKey(first, second))) {
                        return true;
                }
                TeamRoom room = roomsByPlayer.get(first);
                return room != null && activeTeamBattles.contains(room)
                        && room.allMembers().contains(second)
                                && room.teamA.contains(first) != room.teamA.contains(second);
    }


        private void updateBossBars() {
                Set<UUID> battlePlayers = new HashSet<>();

                for (PairKey pair : activePairs) {
                        updateSingleBossBar(pair.first(), pair.second(), battlePlayers);
                        updateSingleBossBar(pair.second(), pair.first(), battlePlayers);
                }

                for (TeamRoom room : activeTeamBattles) {
                        for (UUID member : room.allMembers()) {
                                updateTeamBossBar(member, room, battlePlayers);
                        }
                }

                Iterator<Map.Entry<UUID, BossBar>> iterator = activeBossBars.entrySet().iterator();
                while (iterator.hasNext()) {
                        Map.Entry<UUID, BossBar> entry = iterator.next();
                        if (!battlePlayers.contains(entry.getKey())) {
                                entry.getValue().removeAll();
                                iterator.remove();
                        }
                }
        }


        private void updateSingleBossBar(UUID viewerId, UUID opponentId, Set<UUID> battlePlayers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                Player opponent = Bukkit.getPlayer(opponentId);
                if (viewer == null || opponent == null) {
                        return;
                }

                double progress = healthPercent(opponent);
                BossBar bossBar = getBossBar(viewer);
                bossBar.setTitle("對手 " + opponent.getName() + " 血量 " + percent(progress) + "%");
                bossBar.setProgress(progress);
                battlePlayers.add(viewerId);
        }


        private void updateTeamBossBar(UUID viewerId, TeamRoom room, Set<UUID> battlePlayers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null) {
                        return;
                }

                Set<UUID> enemyTeam = room.teamA.contains(viewerId) ? room.teamB : room.teamA;
                double maximumHealth = 0.0;
                double currentHealth = 0.0;
                for (UUID member : enemyTeam) {
                        Player enemy = Bukkit.getPlayer(member);
                        if (enemy != null) {
                                maximumHealth += enemy.getMaxHealth();
                                currentHealth += Math.max(0.0, enemy.getHealth());
                        }
                }

                double progress = maximumHealth <= 0.0 ? 0.0 : clamp(currentHealth / maximumHealth);
                BossBar bossBar = getBossBar(viewer);
                bossBar.setTitle("敵隊總血量 " + percent(progress) + "%");
                bossBar.setProgress(progress);
                bossBar.setColor(BarColor.RED);
                battlePlayers.add(viewerId);
        }


        private BossBar getBossBar(Player viewer) {
                BossBar bossBar = activeBossBars.computeIfAbsent(
                                viewer.getUniqueId(),
                                ignored -> Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID));
                if (!bossBar.getPlayers().contains(viewer)) {
                        bossBar.addPlayer(viewer);
                }
                return bossBar;
        }


        private double healthPercent(Player player) {
                return player.getMaxHealth() <= 0.0
                                ? 0.0
                                : clamp(player.getHealth() / player.getMaxHealth());
        }


        private double clamp(double value) {
                return Math.max(0.0, Math.min(1.0, value));
        }


        private String percent(double value) {
                return String.format(Locale.ROOT, "%.0f", value * 100.0);
        }


        private void startTeamBattle(Player owner) {
                TeamRoom room = roomsByPlayer.get(owner.getUniqueId());
                if (room == null || !room.owner.equals(owner.getUniqueId())) {
                        owner.sendMessage(Component.text("只有房主可以開戰。", NamedTextColor.RED));
                        return;
                }
                if (room.teamA.isEmpty() || room.teamB.isEmpty()) {
                        owner.sendMessage(Component.text("A隊與B隊都至少需要一名玩家才能開戰。", NamedTextColor.RED));
                        return;
                }
                activeTeamBattles.add(room);
                updateBossBars();
                for (UUID member : room.allMembers()) {
                        Player player = Bukkit.getPlayer(member);
                        if (player != null) {
                                player.sendMessage(Component.text("多人組隊戰鬥開始！A隊與B隊可以交戰，隊友不會互相傷害。", NamedTextColor.GOLD));
                        }
                }
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


        private boolean isDead(UUID uuid) {
                Player player = Bukkit.getPlayer(uuid);
                return player == null || player.isDead();
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

                TeamRoom teamRoom = roomsByPlayer.get(loser.getUniqueId());
                if (teamRoom != null && activeTeamBattles.contains(teamRoom)) {
                        boolean teamADefeated = teamRoom.teamA.stream().allMatch(this::isDead);
                        boolean teamBDefeated = teamRoom.teamB.stream().allMatch(this::isDead);
                        boolean teamDefeated = teamADefeated || teamBDefeated;
                        if (teamDefeated) {
                                activeTeamBattles.remove(teamRoom);
                                updateBossBars();
                                String winner = teamADefeated ? "B隊" : "A隊";
                                Bukkit.broadcast(Component.text(
                                                "多人組隊戰鬥結束！" + winner + "獲勝。",
                                                NamedTextColor.GOLD));
                        }
                        return;
                }

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

                TeamRoom teamRoom = roomsByPlayer.get(uuid);
                if (teamRoom != null) {
                        if (teamRoom.owner.equals(uuid)) {
                                for (UUID member : teamRoom.allMembers()) {
                                        Player player = Bukkit.getPlayer(member);
                                        if (player != null && !member.equals(uuid)) {
                                                player.sendMessage(Component.text("房主離線，多人房間已關閉。", NamedTextColor.GRAY));
                                        }
                                        roomsByPlayer.remove(member);
                                }
                                activeTeamBattles.remove(teamRoom);
                                updateBossBars();
                        } else {
                                removeFromRoom(uuid, teamRoom);
                        }
                }


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
        updateBossBars();
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

                        showModeSelectionDialog(player);

            return true;
        }


        switch (args[0].toLowerCase(Locale.ROOT)) {

                        case "select-1v1" -> showPlayerSelectionDialog(player);

                        case "select-team", "team-room" -> showTeamRoomDialog(player);

                        case "team-view" -> {
                                if (args.length == 2) {
                                        showTeamView(player, args[1]);
                                }
                        }

                        case "team-invite" -> showTeamInviteDialog(player);

                        case "team-invite-player" -> {
                                if (args.length == 2) {
                                        Player target = Bukkit.getPlayerExact(args[1]);
                                        if (target != null) {
                                                chooseTeamForInvite(player, target);
                                        }
                                }
                        }

                        case "team-send" -> {
                                if (args.length == 3) {
                                        Player target = Bukkit.getPlayerExact(args[1]);
                                        if (target != null) {
                                                sendTeamInvite(player, target, args[2].equalsIgnoreCase("b"));
                                        }
                                }
                        }

                        case "team-accept" -> acceptTeamInvite(player);

                        case "team-deny" -> {
                                if (pendingTeamInvites.remove(player.getUniqueId()) != null) {
                                        player.sendMessage(Component.text("已拒絕多人房間邀請。", NamedTextColor.GRAY));
                                }
                        }

                        case "team-leave" -> leaveTeamRoom(player);

                        case "team-kick" -> {
                                if (args.length == 2) {
                                        try {
                                                kickFromTeamRoom(player, UUID.fromString(args[1]));
                                        } catch (IllegalArgumentException exception) {
                                                player.sendMessage(Component.text("無效的玩家。", NamedTextColor.RED));
                                        }
                                }
                        }

                        case "team-start" -> startTeamBattle(player);

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
                                    "status",
                                    "select-team",
                                    "team-room",
                                    "team-accept",
                                    "team-leave",
                                    "team-start"
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
        private static final class TeamRoom {

                private final UUID owner;
                private final Set<UUID> teamA = new HashSet<>();
                private final Set<UUID> teamB = new HashSet<>();

                private TeamRoom(UUID owner) {
                        this.owner = owner;
                }

                private Set<UUID> allMembers() {
                        Set<UUID> members = new HashSet<>(teamA);
                        members.addAll(teamB);
                        return members;
                }
        }


        private record TeamInvite(UUID owner, boolean teamB) {}


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
