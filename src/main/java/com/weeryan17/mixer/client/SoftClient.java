package com.weeryan17.mixer.client;

import com.google.common.collect.EvictingQueue;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.weeryan17.mixer.shared.command.data.CreateChannels;
import com.weeryan17.mixer.shared.command.data.IdentifyProperties;
import com.weeryan17.mixer.shared.command.data.Init;
import com.weeryan17.mixer.shared.command.meta.ClientType;
import com.weeryan17.mixer.shared.command.meta.CommandType;
import com.weeryan17.mixer.shared.models.ChannelInfo;
import com.weeryan17.mixer.shared.models.ChannelType;
import com.weeryan17.rudp.ReliableSocket;
import net.dongliu.gson.GsonJava8TypeAdapterFactory;
import org.jaudiolibs.jnajack.Jack;
import org.jaudiolibs.jnajack.JackClient;
import org.jaudiolibs.jnajack.JackException;
import org.jaudiolibs.jnajack.JackOptions;
import org.jaudiolibs.jnajack.JackPort;
import org.jaudiolibs.jnajack.JackPortConnectCallback;
import org.jaudiolibs.jnajack.JackPortFlags;
import org.jaudiolibs.jnajack.JackPortType;
import org.jaudiolibs.jnajack.JackProcessCallback;
import org.jaudiolibs.jnajack.JackStatus;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class SoftClient {

    public static void main(String... args) {
        try {
            new SoftClient().init(args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Socket udp = null;
    private Gson gson;
    private JackPort in1;
    private JackPort in2;
    private JackPort out1;
    private JackPort out2;
    private JackProcessCallback processCallback;

    private List<JackPort> outputs;

    public void init(String... args) throws JackException, IOException {
        gson = new GsonBuilder().registerTypeAdapterFactory(new GsonJava8TypeAdapterFactory()).setPrettyPrinting().create();

        Jack jack = Jack.getInstance();
        EnumSet<JackStatus> enumSet = EnumSet.noneOf(JackStatus.class);
        JackClient client = jack.openClient("mixer-test", EnumSet.noneOf(JackOptions.class), enumSet);
        in1 = client.registerPort("in-1", JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsInput, JackPortFlags.JackPortIsTerminal));
        in2 = client.registerPort("in-2", JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsInput, JackPortFlags.JackPortIsTerminal));

        out1 = client.registerPort("out-1", JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsOutput, JackPortFlags.JackPortIsTerminal));
        out2 = client.registerPort("out-2", JackPortType.AUDIO, EnumSet.of(JackPortFlags.JackPortIsOutput, JackPortFlags.JackPortIsTerminal));

        System.out.println(enumSet.stream().map(Enum::name).collect(Collectors.joining(", ")));

        outputs = new ArrayList<>();
        outputs.add(in1);
        outputs.add(in2);

        DatagramSocket datagramSocket = new DatagramSocket();
        datagramSocket.setBroadcast(true);

        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = serverSocket.accept();
                    InputStream in = socket.getInputStream();
                    String data = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject jsonObject = JsonParser.parseString(data).getAsJsonObject();
                    System.out.println(gson.toJson(jsonObject));
                    String ip = jsonObject.get("ip").getAsString();
                    int port = jsonObject.get("audio_port").getAsInt();
                    connect(ip, port);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("mixer", true);
        broadcast.addProperty("port", port);

        byte[] toSend = gson.toJson(broadcast).getBytes(StandardCharsets.UTF_8);

        DatagramPacket datagramPacket = new DatagramPacket(toSend, toSend.length, InetAddress.getByName("255.255.255.255"), 10255);
        datagramSocket.send(datagramPacket);

        processCallback = (client1, nframes) -> {
            if (udp == null) {
                return true;
            }
            List<FloatBuffer> buffers = queue.poll();
            if (buffers != null) {
                if (buffers.size() == 2) {
                    out1.getFloatBuffer().put(buffers.get(0));
                    out2.getFloatBuffer().put(buffers.get(1));
                } else {
                    System.out.println("Incorrect buffer amount");
                }
            }

            List<ByteBuffer> toSend1 = new ArrayList<>();
            int size = 0;
            for (JackPort port1 : outputs) {
                FloatBuffer fBuffer = port1.getFloatBuffer();
                int byteAmount = (fBuffer.remaining() * 4) + 4;
                ByteBuffer buffer = ByteBuffer.allocate(byteAmount);
                buffer.putInt(fBuffer.remaining());
                //System.out.println();
                while (fBuffer.hasRemaining()) {
                    float f = fBuffer.get();
                    //System.out.print(f + " ");
                    buffer.putFloat(f);
                }
                buffer.rewind();
                //System.out.println(buffer.remaining());
                toSend1.add(buffer);
                size += byteAmount;
            }
            if (toSend1.size() > 0) {
                ByteBuffer send = ByteBuffer.allocate(size + 4);
                send.putInt(toSend1.size());
                for (ByteBuffer buffer : toSend1) {
                    //System.out.println(buffer.remaining());
                    send.put(buffer);
                }
                send.rewind();
                byte[] audio = new byte[send.remaining()];
                send.get(audio);
                //System.out.println(bytesToHex(audio));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try {
                    GZIPOutputStream gzipOutputStream = new GZIPOutputStream(out);
                    gzipOutputStream.write(audio);
                    gzipOutputStream.flush();
                    gzipOutputStream.close();
                    audio = out.toByteArray();
                } catch (IOException ignored) {
                    ignored.printStackTrace();
                }
                try {
                    ByteBuffer buffer = ByteBuffer.allocate(4);
                    buffer.putInt(audio.length);
                    udp.getOutputStream().write(buffer.array());
                    udp.getOutputStream().write(audio);
                    udp.getOutputStream().flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return true;
        };

        client.setProcessCallback(processCallback);

        System.out.println("Activating");
        client.setPortConnectCallback(new JackPortConnectCallback() {
            @Override
            public void portsConnected(JackClient client, String portName1, String portName2) {
                System.out.println("Ports connected");
            }

            @Override
            public void portsDisconnected(JackClient client, String portName1, String portName2) {
                System.out.println("Ports disconnected");
            }
        });
        client.activate();
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    private WebSocketClient webSocketClient;
    private Queue<List<FloatBuffer>> queue = EvictingQueue.create(1);

    private void connect(String ip, int port) {

        webSocketClient = new WebSocketClient(URI.create("ws://" + ip + ":" + port)) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                System.out.println("Connected");
                JsonObject idCommand = new JsonObject();
                idCommand.addProperty("command", CommandType.IDENTIFY.getCommand());
                IdentifyProperties identifyProperties = new IdentifyProperties("Test Client", ClientType.VIRTUAL, "test");
                idCommand.add("data", gson.toJsonTree(identifyProperties));
                webSocketClient.send(gson.toJson(idCommand));
            }

            @Override
            public void onMessage(String s) {
                JsonElement element = JsonParser.parseString(s);
                System.out.println(gson.toJson(element));
                if (!element.isJsonObject()) {
                    return;
                }
                JsonObject obj = element.getAsJsonObject();
                String command = obj.get("command").getAsString();
                if (command.equals(CommandType.INIT.getCommand())) {
                    System.out.println("received init");
                    Init init = gson.fromJson(obj.get("data"), Init.class);
                    long beat = init.getHeartbeat();
                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            JsonObject beat = new JsonObject();
                            beat.addProperty("command", "heartbeat");
                            System.out.println("sending beat");
                            webSocketClient.send(gson.toJson(beat));
                        }
                    }, beat, beat);
                    List<ChannelInfo> channelInfos = new ArrayList<>();
                    channelInfos.add(new ChannelInfo("in-1", ChannelType.IN));
                    channelInfos.add(new ChannelInfo("in-2", ChannelType.IN));
                    channelInfos.add(new ChannelInfo("out-1", ChannelType.OUT));
                    channelInfos.add(new ChannelInfo("out-2", ChannelType.OUT));
                    CreateChannels createChannels = new CreateChannels(channelInfos);
                    JsonObject createCommand = new JsonObject();
                    createCommand.addProperty("command", CommandType.CREATE_CHANNELS.getCommand());
                    createCommand.add("data", gson.toJsonTree(createChannels));
                    webSocketClient.send(gson.toJson(createCommand));
                    try {
                        udp = new ReliableSocket(ip, init.getUdpPort());
                        OutputStream outputStream = udp.getOutputStream();
                        byte[] keyBytes = init.getKey().get().getBytes(StandardCharsets.UTF_8);
                        ByteBuffer buffer = ByteBuffer.allocate(keyBytes.length + 4);
                        buffer.putInt(keyBytes.length);
                        buffer.put(keyBytes);
                        outputStream.write(buffer.array());
                        InputStream stream = udp.getInputStream();
                        new Thread(() -> {
                            while (true) {
                                try {
                                    ByteBuffer inBuffer = ByteBuffer.wrap(stream.readNBytes(4));
                                    int amount = inBuffer.getInt();
                                    byte[] audio = stream.readNBytes(amount);
                                    try {
                                        GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(audio));
                                        audio = gzipInputStream.readAllBytes();
                                    } catch (IOException ignored) {
                                        ignored.printStackTrace();
                                    }
                                    ByteBuffer audioBuffer = ByteBuffer.wrap(audio);
                                    List<FloatBuffer> buffers = new ArrayList<>();
                                    int bufferAmount = audioBuffer.getInt();
                                    for (int i = 0; i < bufferAmount; i++) {
                                        int floatAmount = audioBuffer.getInt();
                                        FloatBuffer floatBuffer = FloatBuffer.allocate(floatAmount);
                                        for (int i2 = 0; i2 < floatAmount; i2++) {
                                            floatBuffer.put(audioBuffer.getFloat());
                                        }
                                        floatBuffer.rewind();
                                        buffers.add(floatBuffer);
                                    }
                                    queue.add(buffers);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }).start();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                System.out.println("closed");
            }

            @Override
            public void onError(Exception e) {

            }
        };
        webSocketClient.connect();
    }

}
