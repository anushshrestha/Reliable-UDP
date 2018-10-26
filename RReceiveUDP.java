import java.awt.List;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.Vector;
import java.util.Map.Entry;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;

import edu.utulsa.unet.UDPSocket;
import edu.utulsa.unet.RReceiveUDPI;

public class RReceiveUDP implements RReceiveUDPI {

	int MODE = 0; // 0 is stop and wait
	long WINDOW_SIZE = 10; // bytes
	long TIME_OUT = 1000; // in millisecond
	String receiverFileName;
	int localPort = 32456;
	int MTU = 20;

	int prevSeqNum = 0; // previous sequence number received in-order
	int nextSeqNum = 1; // next expected sequence number
	TreeMap<Integer, byte[]> packetList;
	Timer timer;
	Semaphore s;
	boolean isTransferComplete;// (flag) if transfer is complete
	UDPSocket dgSocket;
	int numberOfFrame = 0;

	public static void main(String[] args) {
		RReceiveUDP receiver = new RReceiveUDP();
		receiver.setMode(1);
		receiver.setModeParameter(5);
		receiver.setFilename("less_important.txt");
		receiver.setLocalPort(32456);
		receiver.receiveFile();
	}

	public boolean receiveFile() {

		boolean isTransferComplete = false; // (flag) if transfer is complete
		s = new Semaphore(1);
		packetList = new TreeMap<Integer, byte[]>();

		try {
			dgSocket = new UDPSocket(localPort);
			// create threads to process data
			receiveThread rThread = new receiveThread();
			sendThread sThread = new sendThread();
			rThread.start();
			sThread.start();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
		return true;
	}

	// check valid packet and build file
	public class receiveThread extends Thread {
		int localPort = getLocalPort();
		int rPort;
		InetAddress rAddress;
		FileOutputStream fos = null;

		public void run() {
			try {
				System.out.println("Receiver : local IP: " + InetAddress.getLocalHost().getHostAddress() + " ARQ Algorithm: "
						+ getModeName() + " ,local UDP port: " + localPort);

				System.out.println("Receiver : Listening on " + localPort);

				byte[] inData = new byte[MTU]; // message data in packet
				try {
					DatagramPacket inPacket = new DatagramPacket(inData, inData.length); // incoming packet
					File file = new File(getFilename());
					fos = new FileOutputStream(receiverFileName);

					boolean isFinalPacketReceived = false;
					while (!isTransferComplete) {
						// transfer completes if last packet received, no of frame is equal to current
						// seq no
						if (isFinalPacketReceived && prevSeqNum == numberOfFrame) {
							isTransferComplete = true;
							nextSeqNum = prevSeqNum; // no need to wait next packet

							System.out
									.println("Receiver : Successfully received " + getFilename() + " (" + file.length() + ") bytes");

						} else {
							// transfer not completed
							dgSocket.receive(inPacket);

							rAddress = inPacket.getAddress();
							rPort = inPacket.getPort();

							int seqNum = ByteBuffer.wrap(copyOfRange(inData, 0, 4)).getInt();

							// packet retransmitted just ack no changes
							if (seqNum <= prevSeqNum) {
								byte[] ackPkt = generatePacket(prevSeqNum);
								dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));
								if (packetList.containsKey(seqNum)) {
									packetList.remove(seqNum);
								}
							} else {
								// first time transmitted
								// dont discard package until within window size or is waiting packet

								byte[] payload;

								// first packet has no of frames
								if (nextSeqNum == 1 && prevSeqNum == 1) {
									numberOfFrame = ByteBuffer.wrap(copyOfRange(inData, 4, 8)).getInt();
									payload = new byte[MTU - 4 - 4]; // header length 4, no of frame 4
									payload = copyOfRange(inData, 4 + 4, MTU);
								} else {
									// in order or not in order store in list
									payload = new byte[MTU - 4];
									payload = copyOfRange(inData, 4, MTU);
								}
								// if final packet
								if (inPacket.getLength() == 4) {
									// send ack
									byte[] ackPkt = generatePacket(-2);
									dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));
									isFinalPacketReceived = true;
									System.out.println("Receiver : EOF packet received. Added to packetlist. Current window size : "
											+ packetList.size());

								}

								// in order, packet as expected
								if (seqNum == nextSeqNum) {

									fos.write(payload);
									prevSeqNum = seqNum; // previous received in order
									nextSeqNum++; // next expected
									byte[] ackPkt = generatePacket(prevSeqNum);
									dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));
									System.out.println("Receiver : Sent Acknowledge for message " + seqNum);
									numberOfFrame++;

									// now check in buffer
									// for other pending acks, select largest continuous ack, remove from list,
									// change pointers
									if (packetList.size() > 1) {
										int largestContinuousSeqNum = 0;
										for (int i = nextSeqNum; i <= packetList.lastKey(); i++) {
											if (packetList.containsKey(i)) {
												ackPkt = generatePacket(prevSeqNum);
												dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));
												fos.write(packetList.get(nextSeqNum));
												packetList.remove(nextSeqNum);
												prevSeqNum = seqNum; // previous received in order
												nextSeqNum++; // next expected
												largestContinuousSeqNum = i;
											} else {
												break;
											}
										}
										// send ack only for largest one
										ackPkt = generatePacket(largestContinuousSeqNum);
										dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));
										System.out.println("Receiver : Sent Acknowledge for message " + seqNum);
										numberOfFrame++;
									}

								} else {
									// not in order so now check for window to buffer
									if (packetList.size() < getWindowSize()) {
										s.acquire();
										packetList.put(seqNum, payload);
										System.out.println("Receiver : Received message: " + seqNum
												+ "Current window size : " + packetList.size());
										// normal packets
										// for first packet
										s.release();
									}
								}
								// package discarded
								// System.out.println("Waiting for " + nextSeqNum + " and packetlist size" +
								// packetList.size());

								sleep(5);
							}

						}
					}
				} catch (Exception e) {
					e.printStackTrace();
					System.exit(-1);
				} finally {
					dgSocket.close();
					fos.close();
					System.out.println("Receiver : DatagramSocket closed.");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}

	}

	// send ack
	public class sendThread extends Thread {
		private DatagramSocket dgSocket;
		private int localPort;
		private int rPort;
	}

	// generate Ack packet
	public byte[] generatePacket(int ackNum) {
		byte[] ackNumBytes = ByteBuffer.allocate(4).putInt(ackNum).array();
		// construct Ack packet
		ByteBuffer pktBuf = ByteBuffer.allocate(4);
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
				System.out.println("Sender : Timeout!");
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