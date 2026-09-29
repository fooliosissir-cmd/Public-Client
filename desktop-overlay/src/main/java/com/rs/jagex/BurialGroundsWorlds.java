package com.rs.jagex;

import com.rs.Loader;
public final class BurialGroundsWorlds {
    private record Destination(int id, String name, String lobby) {}
    private static final Destination[] DESTINATIONS = {
        new Destination(1, "Developer World", "127.0.0.1"),
        new Destination(3, "Main World", "201.79.51.16")
    };
    private static volatile WorldDescriptor[] displayed;
    private static boolean initialSelectionApplied;

    private BurialGroundsWorlds() {}

    static int currentDestination() {
        String host = Loader.IP_ADDRESS;
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) return 1;
        if ("201.79.51.16".equals(host)) return 3;
        return 0;
    }

    public static void installDisplayList() {
        WorldDescriptor[] result = new WorldDescriptor[DESTINATIONS.length];
        int displayIndex = 0;
        for (Destination destination : DESTINATIONS) {
            WorldType type = new WorldType();
            type.activity = destination.name();
            type.countryId = 225;
            WorldDescriptor descriptor = new WorldDescriptor() {
                @Override public WorldType getWorld() { return type; }
            };
            descriptor.worldNumber = destination.id();
            descriptor.unknown = destination.name();
            descriptor.ipAddress = destination.lobby();
            descriptor.port = 43595;
            descriptor.flags = 1;
            descriptor.playerCount = 0;
            int rawIndex = destination.id() - Class485.WORLD_LIST_START;
            if (currentDestination() == destination.id() && Class244.WORLD_LIST_DESCRIPTORS != null
                    && rawIndex >= 0 && rawIndex < Class244.WORLD_LIST_DESCRIPTORS.length) {
                WorldDescriptor raw = Class244.WORLD_LIST_DESCRIPTORS[rawIndex];
                if (raw != null) {
                    descriptor.port = raw.port == -1 ? 43595 : raw.port;
                    descriptor.flags = raw.flags;
                    descriptor.playerCount = raw.playerCount;
                }
            }
            result[displayIndex++] = descriptor;
        }
        displayed = result;
        ConnectionInfo.WORLD_DESCRIPTORS_BYID = result.clone();
        if (!initialSelectionApplied) {
            int requested = Integer.getInteger("burialgrounds.world", currentDestination());
            if (isRegistered(requested) && requested == currentDestination()) {
                WorldDescriptor selected = getDisplayedWorld(requested);
                Class62.setGameHost(requested, selected.ipAddress);
            }
            initialSelectionApplied = true;
        }
    }

    public static WorldDescriptor getDisplayedWorld(int id) {
        WorldDescriptor[] worlds = displayed;
        if (worlds != null) for (WorldDescriptor world : worlds)
            if (world.worldNumber == id) return world;
        return null;
    }

    public static boolean isRegistered(int id) { return destination(id) != null; }

    private static Destination destination(int id) {
        for (Destination destination : DESTINATIONS) if (destination.id() == id) return destination;
        return null;
    }

    public static boolean select(int id, String host) {
        Destination destination = destination(id);
        if (destination == null) return false;
        if (id == currentDestination()) return Class62.setGameHost(id, destination.lobby());
        // Called by the native world-selector script on the client game thread.
        // Returning false prevents the source script from logging into the old service.
        ServiceSwitchCoordinator.request(id);
        return false;
    }
}
