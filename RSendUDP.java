
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

// java default
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;

// from unet.jar file
import edu.utulsa.unet.UDPSocket;
import edu.utulsa.unet.RSendUDPI;

/**
 *
 * @author ans7740
 */
public class RSendUDP implements RSendUDPI {
	static final String SERVER = "localhost";

	int MODE = 0; // 0 is stop and wait
	long WINDOW_SIZE = 5;
	long TIME_OUT = 1000; // in millisecond
	String senderFileName;
	int localPort = 12987;
	InetSocketAddress receiverInfo;
	int MTU = 20;

	int baseSeqOfWindow;
	int nextSeqInWindow;
	Vector<byte[]> packetsList;
	Timer timer;
	Semaphore s;
	boolean isTransferComplete;

	// Timeout task
	public class Timeout extends TimerTask {
		public void run() {
			try {
				s.acquire(); /***** enter CS *****/
				System.out.println("Sender: Timeout!");
				nextSeqInWindow = baseSeqOfWindow; // resets nextSeqNum
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

	public static void main(String[] args) {
		RSendUDP sender = new RSendUDP();
		sender.setMode(1);
		sender.setModeParameter(5);
		sender.setTimeout(10000);
		sender.setFilename("important.txt");
		sender.setLocalPort(23456);
		sender.setReceiver(new InetSocketAddress("172.17.34.56", 32456));
		sender.sendFile();
	}

	public boolean sendFile() {
		try {
			String localIPAddress = InetAddress.getLocalHost().getHostAddress();
			int localPort = getLocalPort();

			String destinationIPAddress = getReceiver().getAddress().getHostAddress();
			int destinationPort = getReceiver().getPort();
			String file = getFilename();
			System.out.println("Sending " + file + " from " + localIPAddress + ":" + localPort + " to " + destinationIPAddress
					+ ":" + destinationPort + " with " + new File(file).length() + " bytes");

			System.out.println("Sender: local IP: " + localIPAddress + " ARQ Algorithm: " + getModeName()
					+ " local UDP port: " + getLocalPort());

			baseSeqOfWindow = 1;
			nextSeqInWindow = 1;
			// this.path = path;
			// fileName = getFilename();
			s = new Semaphore(1);
			packetsList = new Vector<byte[]>(getWindowSize() - 1);
			isTransferComplete = false;

			int inDatagramDestinationPort = getLocalPort();
			int outDatagramDestinationPort = getReceiver().getPort();
			DatagramSocket outDatagramSocket, inDatagramSocket;
			// create sockets
			outDatagramSocket = new DatagramSocket(); // outgoing channel
			inDatagramSocket = new DatagramSocket(inDatagramDestinationPort); // incoming channel
			String destinationInetAddress = getReceiver().getHostName();

			// create threads to process data
			InConnectionThread th_in = new InConnectionThread(inDatagramSocket);
			OutConnectionThread th_out = new OutConnectionThread(outDatagramSocket, outDatagramDestinationPort,
					destinationInetAddress);
			th_in.start();
			th_out.start();

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
		return true;
	}

	// create segment and send
	public class OutConnectionThread extends Thread {
		private DatagramSocket outConnnectionSocket;
		private int destinationPort;
		private String destinationInetAddress;

		// constructor
		public OutConnectionThread(DatagramSocket outConnectionSocket, int destinationPort, String destinationInetAddress) {
			this.outConnnectionSocket = outConnectionSocket;
			this.destinationPort = destinationPort;
			this.destinationInetAddress = destinationInetAddress;
		}

		// constructs the packet prepended with header information
		public byte[] generatePacket(int sequenceNumber, byte[] dataBytes) {
			byte[] sequenceNumberBytes = ByteBuffer.allocate(4).putInt(sequenceNumber).array();

			// generate checksum
			CRC32 checksum = new CRC32();
			checksum.update(sequenceNumberBytes);
			checksum.update(dataBytes);
			byte[] checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array(); // checksum (8 bytes)

			// generate packet
			ByteBuffer packeByteBuffer = ByteBuffer.allocate(8 + 4 + dataBytes.length);
			packeByteBuffer.put(checksumBytes);
			packeByteBuffer.put(sequenceNumberBytes);
			packeByteBuffer.put(dataBytes);
			return packeByteBuffer.array();
		}

		public void run() {
			try {
				FileInputStream fileInputStream = new FileInputStream(senderFileName);
				// int fileSizeInBytes = new File(senderFileName).length();
				int counter = 1;
				try {
					boolean isFinalSequenceNum = false;
					while (!isTransferComplete) {
						// System.out
						// .println(nextSeqInWindow + "," + baseSeqOfWindow + "," + getWindowSize() +
						// "," + packetsList.size());

						int headerLength = 12;
						int payloadLength = MTU - headerLength;
						int numberOfFrame = (int) new File(getFilename()).length() / payloadLength;
						int sizeOfLastFrame = (int) new File(getFilename()).length() - (payloadLength * numberOfFrame);

						if (nextSeqInWindow < baseSeqOfWindow + getWindowSize() - 1 && !isFinalSequenceNum) {
							s.acquire();
							if (baseSeqOfWindow == nextSeqInWindow) {
								// first packet so start timer
								setTimer(true);
							}

							byte[] outData = new byte[getWindowSize()];

							// if in packetlist retrieve
							if (nextSeqInWindow < packetsList.size()) {
								outData = packetsList.get(nextSeqInWindow);
							}

							// else create and add to list
							else {

								byte[] dataBuffer = new byte[MTU];
								int dataLength = fileInputStream.read(dataBuffer, 0, payloadLength);

								// no more data to be read
								if (dataLength == -1) {
									isFinalSequenceNum = true;
									outData = generatePacket(nextSeqInWindow, new byte[0]);
								} else { // else if valid data
									byte[] dataBytes = copyOfRange(dataBuffer, 0, dataLength);
									outData = generatePacket(nextSeqInWindow, dataBytes);
								}

								// }
								packetsList.add(outData);
							}

							// System.out.println(" pack list " + packetsList);
							// sending the packet
							outConnnectionSocket.send(new DatagramPacket(outData, outData.length,
									InetAddress.getByName(destinationInetAddress), destinationPort));

							int totalBytes = outData.length;
							System.out.println(
									"Sender : Message " + nextSeqInWindow + " sent with " + totalBytes + " bytes of actual data");
;
							if (!isFinalSequenceNum) {
								nextSeqInWindow++;
							}
							s.release();
						}
						sleep(15);
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					setTimer(false);
					outConnnectionSocket.close();
					fileInputStream.close();
					System.out.println("Sender: Connection Socket Closed.");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}

	// check for ack
	public class InConnectionThread extends Thread {
		private DatagramSocket inConnectionSocket;

		// InConnectionThread constructor
		public InConnectionThread(DatagramSocket inConnectionSocket) {
			this.inConnectionSocket = inConnectionSocket;
		}

		// returns -1 if corrupted, else return Ack number
		int decodePacket(byte[] packet) {
			byte[] receivedCheckSumByte = copyOfRange(packet, 0, 8);
			byte[] ackNumBytes = copyOfRange(packet, 8, 12);
			CRC32 checksum = new CRC32();
			checksum.update(ackNumBytes);
			byte[] calculatedCheckSumByte = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();// checksum (8 bytes)
			if (Arrays.equals(receivedCheckSumByte, calculatedCheckSumByte))
				return ByteBuffer.wrap(ackNumBytes).getInt();
			else
				return -1;
		}

		// receiving process (updates base)
		public void run() {
			try {
				byte[] inData = new byte[12]; // ack packet with no data
				DatagramPacket inPacket = new DatagramPacket(inData, inData.length);
				try {
					// while there are still packets yet to be received by receiver
					while (!isTransferComplete) {

						inConnectionSocket.receive(inPacket);
						int ackNum = decodePacket(inData);
						System.out.println("Sender: Message " + ackNum + "acknowledged");
						// if ack is not corrupted
						if (ackNum != -1) {
							// if duplicate ack
							// if (baseSeqOfWindow == ackNum + 1) {
							// s.acquire(); /***** enter CS *****/
							// setTimer(false); // off timer
							// nextSeqInWindow = baseSeqOfWindow; // resets nextSeqInWindow
							// s.release(); /***** leave CS *****/
							// }
							// else if teardown ack
							if (ackNum == -2)
								isTransferComplete = true;
							// else normal ack
							else {
								baseSeqOfWindow = ackNum++; // update baseSeqOfWindow number
								s.acquire(); /***** enter CS *****/
								if (baseSeqOfWindow == nextSeqInWindow)
									setTimer(false); // if no more unacknowledged packets in pipe, off timer
								else
									setTimer(true); // else packet acknowledged, restart timer
								s.release(); /***** leave CS *****/
							}
						}
						// else if ack corrupted, do nothing
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					inConnectionSocket.close();
					System.out.println("Sender: inConnectionSocket closed!");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}// END CLASS InThread

	// Constructor
	public RSendUDP() {

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

	public int getWindowSize() {
		return (int) this.WINDOW_SIZE;
	}

	public void setFilename(String fname) {
		this.senderFileName = fname;
	}

	public String getFilename() {
		return this.senderFileName;
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

	// same as Arrays.copyOfRange in 1.6
	public byte[] copyOfRange(byte[] srcArr, int start, int end) {
		int length = (end > srcArr.length) ? srcArr.length - start : end - start;
		byte[] destArr = new byte[length];
		System.arraycopy(srcArr, start, destArr, 0, length);
		return destArr;
	}
}
