package com.hk.pimote;

import com.hk.json.Json;
import com.hk.json.JsonObject;
import com.pi4j.io.gpio.*;
import com.pi4j.io.i2c.I2CBus;
import com.pi4j.io.i2c.I2CDevice;
import com.pi4j.io.i2c.I2CFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main extends Thread
{
    private static final List<Boolean> updates;
    private static final AtomicBoolean restart;
    private final List<SocketListener> clients;
    private static final DateFormat df;

    static {
        updates = Collections.synchronizedList(new ArrayList<Boolean>());
        restart = new AtomicBoolean(true);
        df = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss");
    }

    private Main() {
        this.setDaemon(true);
        this.clients = Collections.synchronizedList(new ArrayList<SocketListener>());
    }

    @Override
    public void run() {
        while (true) {
            System.out.println("Initiating Server");
            ServerSocket socket = null;
            Label_0081: {
                try {
                    socket = new ServerSocket(48952);
                    this.beginServer(socket);
                }
                catch (Exception e) {
                    e.printStackTrace();
                    try {
                        socket.close();
                    }
                    catch (IOException e2) {
                        e2.printStackTrace();
                    }
                    break Label_0081;
                }
                finally {
                    try {
                        socket.close();
                    }
                    catch (IOException e2) {
                        e2.printStackTrace();
                    }
                }
                try {
                    socket.close();
                }
                catch (IOException e2) {
                    e2.printStackTrace();
                }
            }
            System.out.println("Waiting 1 minute");
            wt(60000L);
        }
    }

    private void beginServer(final ServerSocket socket) throws IOException {
        System.out.println("Listening for clients");
        this.clients.clear();
        final AtomicBoolean running = new AtomicBoolean(true);
        while (running.get()) {
            final SocketListener client = new SocketListener(socket.accept(), running);
            this.clients.add(client);
            client.start();
        }
        Main.updates.clear();
    }

    public static void main(final String[] args) {
        if (args == null || args.length == 0) {
            final GpioController gpio = GpioFactory.getInstance();
            final GpioPinDigitalOutput swtch = gpio.provisionDigitalOutputPin(RaspiPin.GPIO_07, "myRelay", PinState.LOW);
            swtch.setShutdownOptions(true, PinState.LOW, PinPullResistance.OFF);
            System.out.println("Loaded GPIO Controller");
            System.out.println("Connected to pin " + swtch.getPin() + ", " + swtch.getName());
            try {
                final Main main = new Main();
                main.start();
                final AtomicBoolean running = new AtomicBoolean(true);
                final ConsoleIn cns = new ConsoleIn(main, running);
                cns.start();
                boolean last = true;
                for (int i = 0; i < 10; ++i) {
                    last = (i % 2 == 0);
                    System.out.println("Turning " + (last ? "on" : "off"));
                    swtch.setState(last);
                    wt(1000L);
                }
                System.out.println("Running...");
                while (running.get()) {
                    synchronized (Main.updates) {
                        if (!Main.updates.isEmpty()) {
                            last = Main.updates.get(Main.updates.size() - 1);
                            System.out.println("Updated to " + last);
                            swtch.setState(last);
                            Main.updates.clear();
                            for (final SocketListener client : main.clients) {
                                System.out.println("Notifying '" + client.id + "'");
                                client.send(last);
                            }
                        }
                    }
                    // monitorexit(Main.updates)
                    wt(100L);
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            gpio.shutdown();
            if (Main.restart.get()) {
                wt(15000L);
                main(args);
            }
        }
        else if ((args.length == 1 || args.length == 2) && args[0].equalsIgnoreCase("temp")) {
            final byte[] indicies = { -124, -60, -108, -44, -92, -28, -76, -12 };
            byte hex = indicies[0];
            if (args.length == 2) {
                final int j = Integer.parseInt(args[1]);
                if (j < 0 || j >= indicies.length) {
                    throw new IllegalArgumentException("Analog input out of bounds [0, " + indicies.length + "): " + j);
                }
                hex = indicies[j];
            }
            int value = 0;
            String date = null;
            try
            {
                final I2CBus bus = I2CFactory.getInstance(1);
                final I2CDevice device = bus.getDevice(75);
                device.write(hex);
                Thread.sleep(500L);
                value = device.read();
                bus.close();
                date = Main.df.format(new Date());
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            if(date == null)
                return;

            try
            {
                System.out.println("Registering at " + date + " (" + value + "/255)");
                final URL url = new URL("http://192.168.1.77:8000/sense-temp/" + date + "/" + value + "/submit");
                final HttpURLConnection conn = (HttpURLConnection)url.openConnection();
                conn.setReadTimeout(60000);
                conn.setConnectTimeout(60000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("Pragma", "no-cache");
                conn.setRequestProperty("Connection", "keep-alive");
                conn.setRequestProperty("REQ-TOK", "9b4c941a84a77d71729b783bcc2fdd7e");
                conn.setUseCaches(false);
                conn.setDoInput(true);
                conn.setDoOutput(true);
                conn.connect();
                final int response = conn.getResponseCode();
                final InputStream is = conn.getInputStream();
                final StringBuilder sb = new StringBuilder();
                final byte[] arr = new byte[1024];
                int len = 0;
                while ((len = is.read(arr)) > 0) {
                    sb.append(new String(arr, 0, len));
                }
                conn.disconnect();
                System.out.print("[" + response + "] ");
                System.out.println(sb);
            }
            catch (Exception e) {
                e.printStackTrace();
                writeToJson(value, date);
                System.out.println("Exception caught, writing to file...");
            }
        }
        else {
            System.err.println("Unknown-ass command: " + Arrays.toString(args));
        }
    }

    private static void writeToJson(int value, String date)
    {
        try
        {
            FileWriter wtr = new FileWriter("misses.json", true);
            JsonObject obj = new JsonObject();
            obj.put("date", date);
            obj.put("value", value);
            String str = Json.write(obj);
            wtr.append(str).append('\n');
            wtr.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public static void wt(final long ms) {
        try {
            Thread.sleep(ms);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private class SocketListener extends Thread
    {
        private final Socket socket;
        private final OutputStream out;
        private final InputStream in;
        private final AtomicBoolean running;
        private final String id;

        private SocketListener(final Socket socket, final AtomicBoolean running) throws IOException {
            this.running = running;
            this.socket = socket;
            this.setDaemon(true);
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            final char[] chs = new char[12];
            final String chars = "abcdefghijklmnopqrstuvwxyz";
            for (int i = 0; i < chs.length; ++i) {
                chs[i] = chars.charAt((int)(Math.random() * chars.length()));
                if (Math.random() > 0.5) {
                    chs[i] = Character.toUpperCase(chs[i]);
                }
            }
            this.id = new String(chs);
            System.out.println("New Connection '" + this.id + "' [" + socket.getInetAddress() + "]");
        }

        public void send(final boolean state) throws IOException {
            this.out.write(state ? 1 : 0);
        }

        @Override
        public void run() {
            System.out.println("Listening '" + this.id + "'");
            try {
                boolean stop = false;
                while (!stop) {
                    final int b = this.in.read();
                    System.out.println("Received " + b + " from '" + this.id + "'");
                    if (b == 0 || b == 1) {
                        Main.updates.add(b == 1);
                    }
                    else if (b == 3) {
                        this.running.set(false);
                    }
                    else {
                        stop = true;
                    }
                }
                this.socket.close();
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("Removing Client '" + this.id + "'");
            Main.this.clients.remove(this);
        }
    }

    private static class ConsoleIn extends Thread
    {
        private final Main main;
        private final AtomicBoolean running;

        private ConsoleIn(final Main main, final AtomicBoolean running) {
            this.main = main;
            this.running = running;
        }

        @Override
        public void run() {
            System.out.println("Attatched to console");
            final Scanner in = new Scanner(System.in);
            while (in.hasNextLine()) {
                final String line = in.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                if ("restart".equalsIgnoreCase(line)) {
                    this.running.set(false);
                }
                else if ("stop".equalsIgnoreCase(line)) {
                    this.running.set(false);
                    Main.restart.set(false);
                }
                else if ("on".equalsIgnoreCase(line) || "off".equalsIgnoreCase(line)) {
                    Main.updates.add("on".equalsIgnoreCase(line));
                }
                else {
                    if ("clients".equalsIgnoreCase(line)) {
                        synchronized (this.main.clients) {
                            System.out.println("Connected Clients (" + this.main.clients.size() + ")");
                            for (final SocketListener client : this.main.clients) {
                                System.out.println(client.id);
                            }
                            // monitorexit(Main.access$1(this.main))
                            continue;
                        }
                    }
                    System.out.println("Unknown command: '" + line + "'");
                }
            }
            in.close();
        }
    }
}