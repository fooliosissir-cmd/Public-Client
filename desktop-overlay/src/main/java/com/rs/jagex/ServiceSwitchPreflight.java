package com.rs.jagex;

import com.rs.Loader;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

/** Read-only JS5 preflight. The caller supplies game-thread snapshots and schedules this off-thread. */
public final class ServiceSwitchPreflight {
    public static final int TIMEOUT_MILLIS = 3000;
    private static final int MAX_MASTER_BYTES = 65_536;
    private static final int NATIVE_OFFSET = 6 + 30 * 72;

    public enum Reason { UNKNOWN_SERVICE, INVALID_SOURCE, INVALID_TOKEN, INVALID_TARGET_REQUESTER, OFFLINE, TIMEOUT, PROTOCOL, SIGNATURE, INCOMPATIBLE_NATIVES }

    /** Safe to display/log: contains no endpoint-supplied message, token, payload or credentials. */
    public static final class Failure extends IOException {
        private final Reason reason;
        Failure(Reason reason) { super("Service cache preflight: " + reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }

    public static final class Result {
        private final ServiceLoginMemory.Service source, target;
        private final byte[] sourceNative, master;
        private final int indexCount;
        private Result(ServiceLoginMemory.Service source, ServiceLoginMemory.Service target, byte[] sourceNative, byte[] master) {
            this.source = source; this.target = target;
            this.sourceNative = sourceNative.clone(); this.master = master.clone();
            indexCount = Byte.toUnsignedInt(master[5]);
        }
        public ServiceLoginMemory.Service source() { return source; }
        public ServiceLoginMemory.Service target() { return target; }
        public int indexCount() { return indexCount; }
        public byte[] masterTable() { return master.clone(); }
        public byte[] sourceNativeIdentity() { return sourceNative.clone(); }
        public byte[] targetNativeIdentity() { return nativeIdentity(master); }
        public boolean nativeCompatible() { return Arrays.equals(sourceNative, targetNativeIdentity()); }

        /** Game-thread operation: binds only caller-owned workers, never publishes client globals. */
        public JS5Manager createTargetManager(JS5StandardRequester requester, JS5LocalRequester local) throws Failure {
            require(requester != null && requester.priorities() == 0 && requester.extras() == 0 && requester.current == null
                && (!(requester instanceof JS5StandardRequester_Sub1 nativeRequester) || nativeRequester.aClass202_7778 == null),
                Reason.INVALID_TARGET_REQUESTER);
            return validatedManager(master, requester, local);
        }
        @Override public String toString() { return "ServiceSwitchPreflight.Result[" + source + "->" + target + ", indices=" + indexCount + "]"; }
    }

    /** sourceNativeIdentity must be captured from the active validated source manager on the game thread. */
    public static Result probe(ServiceLoginMemory.Service source, ServiceLoginMemory.Service target,
                               byte[] sourceNativeIdentity, byte[] js5Token) throws Failure {
        return probe(source, target, sourceNativeIdentity, js5Token, ChannelWire::connect);
    }

    static Result probe(ServiceLoginMemory.Service source, ServiceLoginMemory.Service target,
                        byte[] sourceNativeIdentity, byte[] js5Token, Connector connector) throws Failure {
        if (source == null || target == null) throw new Failure(Reason.UNKNOWN_SERVICE);
        if (sourceNativeIdentity == null || sourceNativeIdentity.length != 72) throw new Failure(Reason.INVALID_SOURCE);
        if (js5Token == null || js5Token.length == 0 || js5Token.length > 128) throw new Failure(Reason.INVALID_TOKEN);
        byte[] sourceCopy = sourceNativeIdentity.clone(), token = js5Token.clone();
        Deadline deadline = new Deadline();
        try (Wire wire = connector.connect(target, deadline)) {
            byte[] table = request(wire, token, deadline);
            validate(table);
            deadline.check();
            if (!Arrays.equals(sourceCopy, nativeIdentity(table))) throw new Failure(Reason.INCOMPATIBLE_NATIVES);
            return new Result(source, target, sourceCopy, table);
        } catch (Failure failure) {
            throw failure;
        } catch (EOFException truncated) {
            throw new Failure(Reason.PROTOCOL);
        } catch (IOException offline) {
            throw new Failure(Reason.OFFLINE);
        } finally {
            Arrays.fill(token, (byte) 0);
            Arrays.fill(sourceCopy, (byte) 0);
        }
    }

    /** Package-private diagnostic seam; same pinned, bounded, validated read without a source comparison. */
    static byte[] inspect(ServiceLoginMemory.Service target, byte[] js5Token) throws Failure {
        if (target == null) throw new Failure(Reason.UNKNOWN_SERVICE);
        if (js5Token == null || js5Token.length == 0 || js5Token.length > 128) throw new Failure(Reason.INVALID_TOKEN);
        byte[] token = js5Token.clone();
        Deadline deadline = new Deadline();
        try (Wire wire = ChannelWire.connect(target, deadline)) {
            byte[] table = request(wire, token, deadline);
            validate(table); deadline.check(); return table;
        } catch (Failure failure) { throw failure; }
        catch (EOFException truncated) { throw new Failure(Reason.PROTOCOL); }
        catch (IOException offline) { throw new Failure(Reason.OFFLINE); }
        finally { Arrays.fill(token, (byte) 0); }
    }

    /** Same bounded framing as the audited ServiceCacheProbe; only archive255/255 is requested. */
    private static byte[] request(Wire wire, byte[] token, Deadline deadline) throws IOException {
        ByteBuffer handshake = ByteBuffer.allocate(15 + token.length);
        handshake.put((byte) 15).put((byte) (13 + token.length)).putInt(Loader.CLIENT_BUILD)
            .putInt(Loader.MAJOR_BUILD).putInt(Loader.MINOR_BUILD).put(token).put((byte) 0).flip();
        try { wire.write(handshake, deadline); }
        finally { Arrays.fill(handshake.array(), (byte) 0); }
        int response;
        do { deadline.check(); response = readByte(wire, deadline); } while (response == 25);
        require(response == 0, Reason.PROTOCOL);
        int priorities = Class446.method7436().length;
        require(priorities > 0 && priorities <= 32, Reason.PROTOCOL);
        wire.read(ByteBuffer.allocate(priorities * 4), deadline);
        ByteBuffer request = ByteBuffer.allocate(18);
        request.put(new byte[]{6,0,0,3,0,0,3,0,0,0,0,0,1,(byte)255}).putInt(255).flip();
        wire.write(request, deadline);
        require(readByte(wire, deadline) == 255 && readInt(wire, deadline) == 255, Reason.PROTOCOL);
        int compression = readByte(wire, deadline), length = readInt(wire, deadline);
        require(compression == 0 && length >= 1 && length <= MAX_MASTER_BYTES - 5, Reason.PROTOCOL);
        byte[] table = new byte[length + 5];
        ByteBuffer.wrap(table).put((byte) 0).putInt(length);
        int offset = 5, frameBytes = 10;
        while (offset < table.length) {
            deadline.check();
            if (frameBytes == 512) { require(readByte(wire, deadline) == 255, Reason.PROTOCOL); frameBytes = 1; }
            int amount = Math.min(table.length - offset, 512 - frameBytes);
            wire.read(ByteBuffer.wrap(table, offset, amount), deadline);
            offset += amount; frameBytes += amount;
        }
        return table;
    }

    static byte[] nativeIdentity(byte[] table) { return Arrays.copyOfRange(table, NATIVE_OFFSET, NATIVE_OFFSET + 72); }

    /** Pure validation seam for malformed-wire tests. Native validator still makes the trust decision. */
    static void validate(byte[] table) throws Failure {
        require(table != null && table.length >= 6 && table.length <= MAX_MASTER_BYTES, Reason.PROTOCOL);
        require(table[0] == 0 && ByteBuffer.wrap(table, 1, 4).getInt() == table.length - 5, Reason.PROTOCOL);
        int count = Byte.toUnsignedInt(table[5]), signatureOffset = 6 + count * 72;
        require(count > 30 && signatureOffset <= table.length, Reason.PROTOCOL);
        int signatureLength = table.length - signatureOffset;
        require(signatureLength >= 64 && signatureLength <= (Loader.RSA_PUBLIC_MODULUS.bitLength() + 7) / 8 + 1, Reason.PROTOCOL);
        // The legacy validator dumps malformed-length decrypted bytes. Check only that shape
        // first, without redirecting global stdout; native init then validates RSA/Whirlpool.
        byte[] signature = Arrays.copyOfRange(table, signatureOffset, table.length);
        byte[] decrypted = new BigInteger(signature).modPow(Loader.RSA_PUBLIC_EXPONENT, Loader.RSA_PUBLIC_MODULUS).toByteArray();
        require(decrypted.length == 64 || decrypted.length == 65, Reason.SIGNATURE);
        validatedManager(table, new JS5StandardRequester_Sub1(), null);
    }

    private static JS5Manager validatedManager(byte[] table, JS5StandardRequester requester, JS5LocalRequester local) throws Failure {
        JS5Manager manager = new JS5Manager(requester, local);
        require(manager.tableRequest != null, Reason.PROTOCOL);
        // Satisfy the native request from the independently authenticated snapshot and detach
        // its queue node, so requester initialization cannot re-request/overwrite that proof.
        manager.tableRequest.method13452();
        manager.tableRequest.stream = new ByteBuf(table.clone());
        manager.tableRequest.stream.index = table.length;
        manager.tableRequest.waiting = false;
        try { require(manager.init() && manager.grabWorkers.length == Byte.toUnsignedInt(table[5]), Reason.SIGNATURE); }
        catch (RuntimeException invalid) { throw new Failure(Reason.SIGNATURE); }
        return manager;
    }

    private static int readByte(Wire wire, Deadline deadline) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1); wire.read(buffer, deadline); return Byte.toUnsignedInt(buffer.array()[0]);
    }
    private static int readInt(Wire wire, Deadline deadline) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4); wire.read(buffer, deadline); return buffer.flip().getInt();
    }
    private static void require(boolean condition, Reason reason) throws Failure { if (!condition) throw new Failure(reason); }

    interface Connector { Wire connect(ServiceLoginMemory.Service service, Deadline deadline) throws IOException; }
    interface Wire extends AutoCloseable {
        void read(ByteBuffer target, Deadline deadline) throws IOException;
        void write(ByteBuffer source, Deadline deadline) throws IOException;
        @Override void close() throws IOException;
    }
    static final class Deadline {
        private final long end = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000L;
        void check() throws Failure { if (Thread.currentThread().isInterrupted() || System.nanoTime() >= end) throw new Failure(Reason.TIMEOUT); }
        long remainingMillis() throws Failure { check(); return Math.max(1L, (end - System.nanoTime() + 999_999) / 1_000_000); }
    }

    /** Nonblocking connect/read/write share one deadline; no DNS, helper threads or unbounded socket writes. */
    private static final class ChannelWire implements Wire {
        private final SocketChannel channel;
        private final Selector selector;
        private final SelectionKey key;
        private ChannelWire(SocketChannel channel, Selector selector, SelectionKey key) { this.channel=channel; this.selector=selector; this.key=key; }
        static Wire connect(ServiceLoginMemory.Service service, Deadline deadline) throws IOException {
            SocketChannel channel = SocketChannel.open();
            Selector selector = null;
            try {
                selector = Selector.open(); channel.configureBlocking(false);
                SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
                ChannelWire wire = new ChannelWire(channel, selector, key);
                String[] octets = service.host().split("\\.");
                require(octets.length == 4, Reason.UNKNOWN_SERVICE);
                byte[] ip = new byte[4];
                for (int i=0;i<4;i++) ip[i]=(byte)Integer.parseInt(octets[i]);
                deadline.check();
                if (!channel.connect(new InetSocketAddress(InetAddress.getByAddress(ip), service.lobbyPort())))
                    while (!channel.finishConnect()) wire.await(SelectionKey.OP_CONNECT, deadline);
                deadline.check();
                return wire;
            } catch (IOException | RuntimeException failure) {
                try { channel.close(); } finally { if(selector != null) selector.close(); }
                throw failure;
            }
        }
        private void await(int operation, Deadline deadline) throws IOException {
            key.interestOps(operation); selector.select(deadline.remainingMillis()); selector.selectedKeys().clear(); deadline.check();
        }
        public void read(ByteBuffer target, Deadline deadline) throws IOException {
            while(target.hasRemaining()) { deadline.check(); int n=channel.read(target); if(n<0)throw new EOFException(); if(n==0)await(SelectionKey.OP_READ,deadline); }
        }
        public void write(ByteBuffer source, Deadline deadline) throws IOException {
            while(source.hasRemaining()) { deadline.check(); if(channel.write(source)==0)await(SelectionKey.OP_WRITE,deadline); }
        }
        public void close() throws IOException { try { channel.close(); } finally { selector.close(); } }
    }
    private ServiceSwitchPreflight() {}
}
