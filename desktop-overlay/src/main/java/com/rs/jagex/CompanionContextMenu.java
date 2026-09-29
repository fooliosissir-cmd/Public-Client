package com.rs.jagex;

import java.util.HashMap;
import java.util.Map;

final class CompanionContextMenu {
    static final int CALLBACK = -72701;
    private static final String PREFIX = "bgcompanion-v1";
    private record Marker(String username, String displayName) {}
    private static final Map<Integer, Marker> roster = new HashMap<>();
    private static String token;
    private static boolean requested;

    static void clear() {
        roster.clear();
        token = null;
        requested = false;
    }

    static boolean receive(Object[] args) {
        if (args.length == 0 || !Integer.valueOf(CALLBACK).equals(args[0])) return false;
        roster.clear();
        token = null;
        if (BurialGroundsWorlds.currentDestination() != 1 && BurialGroundsWorlds.currentDestination() != 3 || args.length != 3 ||
                !(args[1] instanceof String session) || !session.matches("[a-f0-9]{32}") ||
                !(args[2] instanceof String snapshot) || snapshot.length() > 8192) return true;
        Map<Integer, Marker> parsed = new HashMap<>();
        if (!snapshot.isEmpty()) for (String row : snapshot.split(";", -1)) {
            String[] parts = row.split("\\|", -1);
            if (parts.length != 3 || !parts[0].matches("[0-9]{1,4}") ||
                    !parts[1].matches("[a-z0-9_]{1,32}") || !parts[2].matches("[A-Za-z0-9 _-]{1,32}")) return true;
            int index = Integer.parseInt(parts[0]);
            if (index < 1 || index > 2047 || parsed.put(index, new Marker(parts[1], parts[2])) != null || parsed.size() > 64) return true;
        }
        roster.putAll(parsed);
        token = session;
        requested = true;
        return true;
    }

    static boolean matches(int index, String displayName) {
        Marker marker = roster.get(index);
        return token != null && marker != null && marker.displayName.equals(displayName);
    }

    private static boolean sendCommand(String command) {
        if (BurialGroundsWorlds.currentDestination() != 1 && BurialGroundsWorlds.currentDestination() != 3 || client.GAME_STATE != GameState.LOGGED_INGAME) return false;
        ISAACCipher outKeys = client.GAME_CONNECTION_CONTEXT.outKeys;
        if (outKeys == null || client.GAME_CONNECTION_CONTEXT.getConnection() == null) return false;
        TCPPacket packet = TCPPacket.createPacket(ClientProt.COMMAND, outKeys);
        if (packet == null) return false;
        packet.buffer.writeByte(command.length() + 3);
        packet.buffer.writeByte(0);
        packet.buffer.writeByte(0);
        packet.buffer.writeString(command);
        client.GAME_CONNECTION_CONTEXT.queuePacket(packet);
        return true;
    }

    static void request() {
        if (BurialGroundsWorlds.currentDestination() != 1 && BurialGroundsWorlds.currentDestination() != 3 || client.GAME_STATE != GameState.LOGGED_INGAME) return;
        if (!requested) {
            requested = sendCommand(PREFIX + " hello");
        }
    }

    static void append(PlayerEntity target) {
        if (BurialGroundsWorlds.currentDestination() != 1 && BurialGroundsWorlds.currentDestination() != 3 || client.GAME_STATE != GameState.LOGGED_INGAME) return;
        request();
        if (!matches(target.index, target.username)) return;
        String section = "Companion: " + target.username;
        add("Dismiss", section, MenuAction.COMPANION_DISMISS, target);
        add("Wait here", section, MenuAction.COMPANION_WAIT, target);
        add("Assist my fights", section, MenuAction.COMPANION_ASSIST, target);
        add("Follow me", section, MenuAction.COMPANION_FOLLOW, target);
        String supplies = "Supplies: " + target.username;
        add("Magic gear", supplies, MenuAction.COMPANION_MAGIC, target);
        add("Ranged gear", supplies, MenuAction.COMPANION_RANGED, target);
        add("Melee gear", supplies, MenuAction.COMPANION_MELEE, target);
        add("Potions", supplies, MenuAction.COMPANION_POTIONS, target);
        add("Food", supplies, MenuAction.COMPANION_FOOD, target);
        String party = "Party: " + target.username;
        add("Dismiss all", party, MenuAction.COMPANION_PARTY_DISMISS, target);
        add("Wait here", party, MenuAction.COMPANION_PARTY_WAIT, target);
        add("Assist my fights", party, MenuAction.COMPANION_PARTY_ASSIST, target);
        add("Follow me", party, MenuAction.COMPANION_PARTY_FOLLOW, target);
    }

    private static void add(String label, String section, MenuAction action, PlayerEntity target) {
        PlayerModel.method4032(label, section, -1, action, -1, target.index, 0, 0,
                true, false, 0x434f4d5000000000L | ((long) sectionId(commandId(action)) << 16) | target.index, false);
    }

    static boolean hasEntries() {
        for (MenuActionEvent event = (MenuActionEvent) Class20.aClass482_171.head(); event != null;
                event = (MenuActionEvent) Class20.aClass482_171.next())
            if (commandId(event.menuAction) != 0) return true;
        return false;
    }

    private static int commandId(MenuAction action) {
        if (action == null) return 0;
        return switch (action) {
            case COMPANION_FOLLOW -> 1;
            case COMPANION_ASSIST -> 2;
            case COMPANION_WAIT -> 3;
            case COMPANION_DISMISS -> 4;
            case COMPANION_FOOD -> 5;
            case COMPANION_POTIONS -> 6;
            case COMPANION_MELEE -> 7;
            case COMPANION_RANGED -> 8;
            case COMPANION_MAGIC -> 9;
            case COMPANION_PARTY_FOLLOW -> 10;
            case COMPANION_PARTY_ASSIST -> 11;
            case COMPANION_PARTY_WAIT -> 12;
            case COMPANION_PARTY_DISMISS -> 13;
            default -> 0;
        };
    }

    private static int sectionId(int command) { return command <= 4 ? 0 : command <= 9 ? 1 : 2; }

    static String sectionLabel(int command, String name) {
        return (command <= 4 ? "Companion: " : command <= 9 ? "Supplies: " : "Party: ") + name;
    }

    static boolean dispatch(MenuActionEvent event) {
        int command = commandId(event.menuAction);
        if (command == 0) return false;
        int index = (int) event.aLong9584;
        if (client.GAME_STATE != GameState.LOGGED_INGAME || BurialGroundsWorlds.currentDestination() != 1 && BurialGroundsWorlds.currentDestination() != 3 ||
                index < 1 || index >= client.PLAYER_LIST.length) return true;
        PlayerEntity target = client.PLAYER_LIST[index];
        if (target == null || !matches(index, target.username) ||
                !event.aString9588.equals(sectionLabel(command, target.username))) return true;
        sendCommand(PREFIX + " " + command + " " + index + " " + roster.get(index).username + " " + token);
        return true;
    }
}
