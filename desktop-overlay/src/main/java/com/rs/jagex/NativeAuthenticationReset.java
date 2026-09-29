package com.rs.jagex;

import java.io.IOException;
import java.util.Arrays;

/**
 * One-shot, game-thread authentication teardown for a committed service change.
 * Deliberately unhooked: the coordinator must stop old producers before invoking this
 * callback, then rebuild the target login page before asking the login adapter to restore.
 * No endpoints, cache resources, account creation, or remembered service credentials change here.
 */
public final class NativeAuthenticationReset implements Runnable {
    private final Thread owner = Thread.currentThread();
    private final BufferedConnectionContext lobby, game;
    private final Connection lobbyTransport, gameTransport;
    private boolean consumed;

    /** Capture on the game thread while the source is a stable lobby or manual login page. */
    public static NativeAuthenticationReset prepare() {
        requireSafeStage();
        return new NativeAuthenticationReset();
    }

    private NativeAuthenticationReset() {
        lobby = client.LOBBY_CONNECTION_CONTEXT;
        game = client.GAME_CONNECTION_CONTEXT;
        if (lobby == null || game == null || lobby == game)
            throw new IllegalStateException("Distinct native authentication contexts required");
        lobbyTransport = lobby.getConnection();
        gameTransport = game.getConnection();
    }

    private static void requireSafeStage() {
        if ((client.GAME_STATE != GameState.UNK_0 && client.GAME_STATE != GameState.UNK_5)
                || Login.getLoginStage() != LoginStage.NONE_2
                || Class192.ACCOUNT_CREATION_STAGE != null)
            throw new IllegalStateException("Authentication reset requires a stable lobby or terminated login");
    }

    @Override public void run() { resetAtCommit(); }

    public void resetAtCommit() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Authentication reset must remain on its game thread");
        if (consumed) throw new IllegalStateException("Authentication reset already consumed");
        requireSafeStage();
        if (lobby != client.LOBBY_CONNECTION_CONTEXT || game != client.GAME_CONNECTION_CONTEXT
                || lobbyTransport != lobby.getConnection() || gameTransport != game.getConnection())
            throw new IllegalStateException("Source authentication transport changed");
        consumed = true;
        // Attempt both closes even when one fails. The enclosing login adapter then remains
        // suspended on failure; no partially reset source may start a target handshake.
        RuntimeException failure = closeAndErase(lobby);
        RuntimeException gameFailure = closeAndErase(game);
        if (failure == null) failure = gameFailure;
        else if (gameFailure != null) failure.addSuppressed(gameFailure);

        for (int i = 0; i < TCPPacket.index; i++) erasePacket(TCPPacket.OUTGOING_PACKETS[i]);
        // Entries above index are stale pool aliases, not ownership of active packets.
        Arrays.fill(TCPPacket.OUTGOING_PACKETS, null);
        TCPPacket.index = 0;
        erase(client.aByteArray7152);
        client.aByteArray7152 = null;
        erase(Class500.ISAAC_SEED);
        Class500.ISAAC_SEED = null;
        erase(Class14.ACCOUNT_CREATION_ISAAC_KEYS);
        Class14.ACCOUNT_CREATION_ISAAC_KEYS = null;
        Class14.anInt133 = Class14.anInt134 = 0;
        Class9.aString99 = Class9.aString102 = "";
        Static.LOBBY_AUTH_TOKEN = "";
        Class9.aLong77 = -1L;
        Class9.aLong86 = 0L;
        Class9.anInt76 = -1;
        Class9.socialNetworkLogin = Class9.aBool74 = Class9.aBool71 = false;
        Class9.anInt90 = Class9.anInt104 = Class9.anInt103 = Class9.anInt113 = 0;
        Class9.anInt72 = Class9.anInt106 = Class9.anInt107 = -2;
        Class9.anInt108 = Class9.anInt109 = Class9.anInt94 = Class9.anInt112 = -1;
        Class110.anInt1105 = VarNPCMap.anInt1965 = 0;
        Class9.CURRENT_CONNECTION_CONTEXT = lobby;
        Login.setLobbyStage(LobbyStage.LOGGED_OUT);
        Login.setLoginStage(LoginStage.NONE_2);
        if (failure != null) throw new IllegalStateException("Native authentication teardown failed", failure);
    }

    private static RuntimeException closeAndErase(BufferedConnectionContext context) {
        Connection old = context.getConnection();
        RuntimeException failure = null;
        try {
            // Abort the old socket before native joins, so a blocked writer cannot wait for
            // an unresponsive source server to drain bytes. No new transport is opened here.
            if (old instanceof AsyncConnection async) {
                try { async.socket.close(); }
                catch (IOException | RuntimeException problem) { failure = failed(failure, problem); }
            }
            try { if (old != null) old.end(); }
            catch (RuntimeException problem) { failure = failed(failure, problem); }
            if (old instanceof AsyncConnection async) {
                try { eraseStoppedTransport(async); }
                catch (RuntimeException problem) { failure = failed(failure, problem); }
            }
        } finally {
            context.reset();
            context.eraseAuthenticationState();
        }
        return failure;
    }

    private static RuntimeException failed(RuntimeException previous, Exception problem) {
        RuntimeException next = new IllegalStateException("Old authentication transport did not terminate cleanly", problem);
        if (previous == null) return next;
        previous.addSuppressed(next);
        return previous;
    }

    private static void eraseStoppedTransport(AsyncConnection connection) {
        AsyncInputStream input = connection.inputStream;
        AsyncOutputStream output = connection.outputStream;
        if (input.aThread3398.isAlive() || output.aThread3445.isAlive())
            throw new IllegalStateException("Authentication I/O workers are still active");
        erase(input.buffer);
        input.currIndex = input.offset = 0;
        erase(output.aByteArray3441);
        output.anInt3443 = output.anInt3444 = 0;
    }

    static void erasePacket(TCPPacket packet) {
        if (packet == null) return;
        eraseBuffer(packet.buffer);
        // Do not return sensitive storage to the byte-array pool.
        if (packet.buffer != null) packet.buffer.buffer = null;
        packet.buffer = null;
        packet.packet = null;
        packet.anInt7680 = packet.outgoingSize = 0;
    }

    static void eraseBuffer(ByteBuf buffer) {
        if (buffer == null) return;
        erase(buffer.buffer);
        buffer.index = 0;
        if (buffer instanceof ByteBuf.Bit bits) {
            if (bits.isaac != null) bits.isaac.erase();
            bits.isaac = null;
            bits.anInt9608 = 0;
        }
    }

    private static void erase(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    private static void erase(int[] ints) { if (ints != null) Arrays.fill(ints, 0); }
}
