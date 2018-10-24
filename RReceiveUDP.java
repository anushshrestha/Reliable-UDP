
import java.awt.List;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;

// Import from inet.java
import edu.utulsa.unet.UDPSocket; //import java.net.DatagramSocket;
import edu.utulsa.unet.RReceiveUDPI;

public class RReceiveUDP implements RReceiveUDPI {

	int MODE = 0; // 0 is stop and wait
	long WINDOW_SIZE = 256; // bytes
	long TIME_OUT = 1000; // in millisecond
	String receiverFileName;
	int localPort = 32456;

	int prevSeqNum; // previous sequence number received in-order
	int nextSeqNum; // next expected sequence number
	boolean isTransferComplete;// (flag) if transfer is complete
	int MTU = 20;
	int pkt_size = MTU;
	Semaphore s;
	TreeMap<Integer, byte[]> packetsList;

	Timer timer;

	public RReceiveUDP() {
		try {
			System.out.println("Receiver: local IP: " + InetAddress.getLocalHost().getHostAddress() + " ARQ Algorithm: "
					+ getModeName() + " ,local UDP port: " + getLocalPort());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		RReceiveUDP receiver = new RReceiveUDP();
		receiver.setMode(1);
		receiver.setModeParameter(5);
		receiver.setFilename("less_important.txt");
		receiver.setLocalPort(32456);
		receiver.receiveFile();
	}

	public int getLargestContinuousSequenceNumber(Vector<byte[]> packetsList) {
		// Object minObj = Collections.min(packetsList.get(nextSeqNum));
		return nextSeqNum;
	}

	public boolean receiveFile() {
		try {
			int prevSeqNum = -1; // previous sequence number received in-order
			int nextSeqNum = 0; // next expected sequence number
			boolean isTransferComplete = false; // (flag) if transfer is complete
			s = new Semaphore(1);
			packetsList = new TreeMap<Integer, byte[]>();

			int inDatagramDestinationPort = getLocalPort();

			int outDatagramDestinationPort = 23456;
			DatagramSocket outDatagramSocket, inDatagramSocket;
			// create sockets
			outDatagramSocket = new DatagramSocket(); // outgoing channel
			inDatagramSocket = new DatagramSocket(32456); // incoming channel

			// create threads to process data
			InConnectionThread th_in = new InConnectionThread(inDatagramSocket);
			OutConnectionThread th_out = new OutConnectionThread(outDatagramSocket, outDatagramDestinationPort,
					inDatagramDestinationPort);
			th_in.start();
			th_out.start();

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
		return true;
	}

	// check valid packet and build file
	public class InConnectionThread extends Thread {
		DatagramSocket inConnectionSocket, outConnectionSocket;
		int sourcePort;
		int destinationPort;

		// InConnectionThread constructor
		public InConnectionThread(DatagramSocket inConnectionSocket) {
			this.inConnectionSocket = inConnectionSocket;
		}

		public void run() {
			try {
				FileOutputStream fos = new FileOutputStream(receiverFileName);
				outConnectionSocket = new DatagramSocket();
				System.out.println("Receiver: Listening ");
				try {
					byte[] in_data = new byte[pkt_size]; // message data in packet
					DatagramPacket in_pkt = new DatagramPacket(in_data, in_data.length); // incoming packet
					InetAddress dst_addr = InetAddress.getByName("172.17.34.56");
					File file = new File(receiverFileName);

					boolean isFinalSequeunceNum = false;
					int windowSize = 0;
					while (!isTransferComplete) {
						if (packetsList.size() <= getWindowSize() && !isFinalSequeunceNum) {
							s.acquire();
							try {
								System.out.println("1");
								inConnectionSocket.receive(in_pkt);
								System.out.println("2");
							} catch (IOException e) {
								e.printStackTrace();
							}

							byte[] received_checksum = copyOfRange(in_data, 0, 8);
							CRC32 checksum = new CRC32();
							checksum.update(copyOfRange(in_data, 8, in_pkt.getLength()));
							byte[] calculated_checksum = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();

							// if packet is not corrupted
							if (Arrays.equals(received_checksum, calculated_checksum)) {

								byte[] inData = new byte[getWindowSize()];

								int seqNum = ByteBuffer.wrap(copyOfRange(in_data, 8, 12)).getInt();
								System.out.println("Receiver: Received sequence number: " + seqNum);

								// in order or not in order store if in limit of window

								byte[] payload = new byte[in_pkt.getLength() - 12];
								payload = copyOfRange(in_data, 12, in_pkt.getLength() - 12);
								packetsList.put(seqNum, payload);

								// if final packet
								if (in_pkt.getLength() == 12) {
									// send ack
									byte[] ackPkt = generatePacket(-2);
									outConnectionSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, destinationPort));
									isFinalSequeunceNum = true;
									System.out.println("Receiver: All packets received!");
								} else { // other packet

									// in order = update pointer and remove
									if (seqNum == nextSeqNum) {
										int largestContinuousSeqNum = nextSeqNum;
										// update pointer and create file to largest seq number

										// for latest in order packet

										fos.write(packetsList.get(nextSeqNum));
										byte[] ackPkt = generatePacket(prevSeqNum);
										outConnectionSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, destinationPort));
										System.out.println("Receiver: Sent Ack for " + seqNum);
										packetsList.remove(nextSeqNum);
										prevSeqNum = seqNum; // previous received in order
										nextSeqNum++; // next expected

										if (packetsList.size() > 1) {
											// for other pending acks
											for (int i = nextSeqNum; i <= packetsList.lastKey(); i++) {
												if (packetsList.containsKey(i)) {
													fos.write(packetsList.get(nextSeqNum));
													packetsList.remove(nextSeqNum);
													prevSeqNum = seqNum; // previous received in order
													nextSeqNum++; // next expected
													largestContinuousSeqNum = i;
												}
											}
											// send ack only for largest one
											ackPkt = generatePacket(largestContinuousSeqNum);
											outConnectionSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr, destinationPort));
											System.out.println("Receiver: Sent Ack for " + seqNum);
										}

									}
								}
							} // else packet is corrupted
							else {
								System.out.println("Receiver: Corrupt packet dropped.");
								// byte[] ackPkt = generatePacket(prevSeqNum);
								// outConnectionSocket.send(new DatagramPacket(ackPkt, ackPkt.length, dst_addr,
								// destinationPort));
								// System.out.println("Receiver: Sent duplicate Ack " + prevSeqNum);
							}
							s.release();
						} else {
							System.out.println("Receiver: Out of window. Packet dropped.");
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
					System.exit(-1);
				} finally {
					setTimer(false);
					inConnectionSocket.close();
					fos.close();
					System.out.println("Receiver: inConnectionSocket closed!");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}

	}

	// send ack
	public class OutConnectionThread extends Thread {

		private DatagramSocket inConnectionSocket, outConnnectionSocket;
		private int sourcePort;
		private int destinationPort;

		// constructor
		public OutConnectionThread(DatagramSocket outConnectionSocket, int sourcePort, int destinationPort) {
			this.outConnnectionSocket = outConnectionSocket;
			this.destinationPort = destinationPort;
			this.sourcePort = sourcePort;
		}

	}

	// generate Ack packet
	public byte[] generatePacket(int ackNum) {
		byte[] ackNumBytes = ByteBuffer.allocate(4).putInt(ackNum).array();
		// calculate checksum
		CRC32 checksum = new CRC32();
		checksum.update(ackNumBytes);
		// construct Ack packet
		ByteBuffer pktBuf = ByteBuffer.allocate(12);
		pktBuf.put(ByteBuffer.allocate(8).putLong(checksum.getValue()).array());
		pktBuf.put(ackNumBytes);
		return pktBuf.array();
	}

	// same as Arrays.copyOfRange in 1.6
	public byte[] copyOfRange(byte[] srcArr, int start, int end) {
		int length = (end > srcArr.length) ? srcArr.length - start : end - start;
		byte[] destArr = new byte[length];
		System.arraycopy(srcArr, start, destArr, 0, length);
		return destArr;
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
		this.receiverFileName = fname;
	}

	public String getFilename() {
		return this.receiverFileName;
	}

	public boolean setLocalPort(int port) {
		this.localPort = port;
		return true;
	}

	public int getLocalPort() {
		return this.localPort;
	}

	public int getWindowSize() {
		return (int) this.WINDOW_SIZE;
	}

	// Timeout task
	public class Timeout extends TimerTask {
		public void run() {
			try {
				s.acquire(); /***** enter CS *****/
				System.out.println("Sender: Timeout!");
				nextSeqNum = prevSeqNum; // resets nextSeqNum
				s.release(); /***** leave CS *****/
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}// END CLASS Timeout

	// to start or stop the timer
	public void setTimer(boolean isNewTimer) {
		if (timer != null)
			timer.cancel();
		if (isNewTimer) {
			timer = new Timer();
			timer.schedule(new Timeout(), TIME_OUT);
		}
	}
}