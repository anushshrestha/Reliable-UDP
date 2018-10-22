import java.net.DatagramPacket;
import edu.utulsa.unet.UDPSocket; //import java.net.DatagramSocket;
import java.net.InetAddress;
import edu.utulsa.unet.RReceiveUDPI;

public class RReceiveUDP implements RReceiveUDPI {

	int MODE = 0; // 0 is stop and wait
	long WINDOW_SIZE = 256;
	long TIME_OUT = 1000; // in millisecond
	String FILENAME;
	int localPort = 32456;

	public static void main(String[] args) {
		setMode(1);
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

	public boolean setLocalPort(int port) {
		this.localPort = port;
		return true;
	}

	public int getLocalPort() {
		return this.localPort;
	}

	public boolean receiveFile() {
		try {

			System.out.println("Receiver: local IP=" + InetAddress.getLocalHost().getHostAddress() + "ARQ Algorithm:"
					+ getModeName() + "local UDP port" + getLocalPort());

			byte[] buffer = new byte[11];
			UDPSocket socket = new UDPSocket(localPort);
			DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
			socket.receive(packet);
			InetAddress client = packet.getAddress();
			System.out.println(" Received'" + new String(buffer) + "' from " + packet.getAddress().getHostAddress()
					+ " with sender port " + packet.getPort());
		} catch (Exception e) {
			e.printStackTrace();
		}

		return true;
	}

}