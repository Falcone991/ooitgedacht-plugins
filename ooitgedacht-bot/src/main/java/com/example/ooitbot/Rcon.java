package com.example.ooitbot;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimale RCON-client (het ingebouwde "commando's op afstand"-protocol van
 * Minecraft). Houdt één verbinding open en maakt die opnieuw als hij wegvalt,
 * bv. omdat de server sliep.
 */
final class Rcon {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_EXEC = 2;
    // Bestaat niet; de server antwoordt hierop met "Unknown request". Dat gebruiken
    // we als eindmarkering, omdat lange antwoorden over meerdere pakketten verdeeld worden.
    private static final int TYPE_END_MARKER = 100;

    private final String host;
    private final int port;
    private final String password;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int nextId = 1;

    Rcon(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    /** Voert een commando uit en geeft de uitvoer terug. Gooit IOException als de server niet bereikbaar is. */
    synchronized String command(String command) throws IOException {
        connectIfNeeded();
        try {
            int id = nextId();
            int endId = nextId();
            send(id, TYPE_EXEC, command);
            send(endId, TYPE_END_MARKER, "");

            StringBuilder result = new StringBuilder();
            while (true) {
                Packet packet = read();
                if (packet.id == endId) break;
                if (packet.id == id) result.append(packet.body);
            }
            return result.toString();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
        in = null;
        out = null;
    }

    private void connectIfNeeded() throws IOException {
        if (socket != null && !socket.isClosed()) return;

        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(15000);
            socket = s;
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = s.getOutputStream();

            int id = nextId();
            send(id, TYPE_AUTH, password);
            Packet reply = read();
            if (reply.id == -1) {
                throw new IOException("RCON-wachtwoord klopt niet (server.rcon-password in config.yml)");
            }
        } catch (IOException e) {
            close();
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private int nextId() {
        if (nextId >= Integer.MAX_VALUE - 2) nextId = 1;
        return nextId++;
    }

    private void send(int id, int type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(14 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(10 + bytes.length);
        buffer.putInt(id);
        buffer.putInt(type);
        buffer.put(bytes);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        out.write(buffer.array());
        out.flush();
    }

    private Packet read() throws IOException {
        int length = Integer.reverseBytes(in.readInt());
        if (length < 10 || length > 1 << 20) throw new IOException("Ongeldig RCON-antwoord");
        byte[] data = new byte[length];
        in.readFully(data);
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        int type = buffer.getInt();
        String body = new String(data, 8, length - 10, StandardCharsets.UTF_8);
        return new Packet(id, type, body);
    }

    private static final class Packet {
        final int id;
        final int type;
        final String body;

        Packet(int id, int type, String body) {
            this.id = id;
            this.type = type;
            this.body = body;
        }
    }
}
