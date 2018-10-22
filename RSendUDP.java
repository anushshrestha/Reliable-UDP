
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

import java.net.DatagramPacket;
import java.net.InetAddress;
import edu.utulsa.unet.UDPSocket;
import edu.utulsa.unet.RSendUDPI;
import java.net.InetSocketAddress;

/**
 *
 * @author ans7740
 */
public class RSendUDP implements RSendUDPI {
    static final String SERVER = "localhost";

    int MODE = 0; // 0 is stop and wait
    long WINDOW_SIZE = 256;
    long TIME_OUT = 1000; // in millisecond
    String FILENAME;
    int localPort = 12987;
    InetSocketAddress receiverInfo = new InetSocketAddress("localhost", this.localPort);

    public static void main(String[] args) {

    }

    public boolean setMode(int mode) {
        this.MODE = mode;
        return true;
    }

    public int getMode() {
        return this.MODE;
    }

    public String getModeName() {
        if (this.MODE == 0)
            return "Stop and Wait";
        else
            return "Sliding Window";
    }

    public boolean setModeParameter(long n) {
        this.WINDOW_SIZE = n;
        return true;
    }

    public long getModeParameter() {
        return this.WINDOW_SIZE;
    }

    public void setFilename(String fname) {
        this.FILENAME = fname;
    }

    public String getFilename() {
        return this.FILENAME;
    }

    public boolean setTimeout(long timeout) {
        this.TIME_OUT = timeout;
        return true;
    }

    public long getTimeout() {
        return this.TIME_OUT;
    }

    public boolean setLocalPort(int port) {
        this.localPort = port;
        return true;
    }

    public int getLocalPort() {
        return this.localPort;
    }

    public boolean setReceiver(InetSocketAddress receiver) {
        this.receiverInfo = receiver;
        return true;
    }

    public InetSocketAddress getReceiver() {
        return this.receiverInfo;
    }

    public boolean sendFile() {
        try {
            System.out.println("Sender: local IP=" + InetAddress.getByName(SERVER) + "ARQ Algorithm:" + getModeName()
                    + "local UDP port" + getLocalPort());

            byte[] buffer = ("Hello World").getBytes();
            UDPSocket socket = new UDPSocket();

            socket.send(new DatagramPacket(buffer, buffer.length, InetAddress.getByName(SERVER), localPort));
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(e);
        }
        return true;
    }

}