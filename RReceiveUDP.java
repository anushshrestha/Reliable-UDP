import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import edu.utulsa.unet.UDPSocket;
import edu.utulsa.unet.RReceiveUDPI;

/**
 * @author anush shrestha
 */
public class RReceiveUDP implements RReceiveUDPI {

	int MODE = 0; // 0 is stop and wait
	long WINDOW_SIZE = 256; // bytes
	long TIME_OUT = 1000; // in millisecond
	String receiverFileName = "less_important.txt";
	int localPort = 12987;
	int MTU = 200;
	int prevSeqNum = 0; // previous sequence number received in-order
	int nextSeqNum = 1; // next expected sequence number
	TreeMap<Integer, byte[]> packetList;
	Timer timer;
	Semaphore s;
	boolean isTransferComplete;// (flag) if transfer is complete
	UDPSocket dgSocket;
	int numberOfFrame = -1;
	int lengthToUse;
	int headerLength = 12;
	int payLoadLength;
	int totalFileSize = 0;
	int extraBytesLastPayload = 0;
	int fileLength, totalTime;

	public static void main(String[] args) {
		RReceiveUDP receiver = new RReceiveUDP();
		receiver.setMode(1);
		receiver.setModeParameter(512);
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
			MTU = dgSocket.getSendBufferSize();
			payLoadLength = MTU - headerLength;
			rThread.start();
			sThread.start();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
		return true;
	}

	public String getKeysFromWindow() {
		String allKeys = "";
		for (Map.Entry<Integer, byte[]> entry : packetList.entrySet()) {
			allKeys = allKeys + " " + entry.getKey();
		}
		return allKeys;
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
						+ getModeName() + " , local UDP port: " + localPort);

				System.out.println("Receiver : Listening in local port : " + localPort);

				try {

					boolean isFinalPacketReceived = false;
					boolean isStatPacketReceived = false;
					File file = new File(getFilename());
					fos = new FileOutputStream(file);
					while (!isTransferComplete) {
						byte[] inData = new byte[MTU]; // message data is MTU

						DatagramPacket inPacket = new DatagramPacket(inData, inData.length); // incoming packet

						// transfer completes if last packet received, no of frame is equal to current
						// seq no
						dgSocket.receive(inPacket);

						rAddress = inPacket.getAddress();
						rPort = inPacket.getPort();

						int seqNum = ByteBuffer.wrap(copyOfRange(inData, 0, 4)).getInt();

						if (prevSeqNum == numberOfFrame) {
							nextSeqNum = prevSeqNum; // no need to wait next packet
							isTransferComplete = true;

							System.out.println("Receiver : Successfully received " + getFilename());
							dgSocket.close();
							System.exit(0);
							// " ("
							// + (file.length() - extraBytesLastPayload) + ") bytes");
							// System.exit(0);
						} else {

							if (seqNum <= prevSeqNum || packetList.containsKey(seqNum)) {
								byte[] ackPkt = generatePacket(prevSeqNum);

								dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));

								System.out.println("Receiver : Message " + seqNum + " re-received. Resend ack [Discarded] "
										+ " Next Expected Msg : " + nextSeqNum + " Window : " + getKeysFromWindow());
								// if (packetList.containsKey(seqNum)) {
								// packetList.remove(seqNum);
								// }
							} else {
								// first time transmitted
								// dont discard package until within window size or is waiting packet
								// first packet has no of frames
								lengthToUse = ByteBuffer.wrap(copyOfRange(inData, 4, 8)).getInt();

								numberOfFrame = ByteBuffer.wrap(copyOfRange(inData, 8, 12)).getInt();

								byte[] payload = new byte[MTU - headerLength]; // header length 4, no of frame 4
								payload = copyOfRange(inData, headerLength, payLoadLength);

								// if final packet
								// if (inPacket.getLength() == 4) {
								// // send ack
								// byte[] ackPkt = generatePacket(-2);
								// dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));
								// isFinalPacketReceived = true;
								// System.out.println("Receiver : EOF Received. Send Ack : EOF Buffer : " +
								// getKeysFromWindow());
								// last statistics packet
								// in order, packet as expected
								if (seqNum == nextSeqNum) {
									// // if (seqNum <= numberOfFrame) {
									// System.out.println("pay:" + payload);
									// byte[] numberOfFrameBytes = ByteBuffer.allocate(4).putInt(100).array();

									if (seqNum == numberOfFrame) {
										int extraBytesLastPayload = MTU - headerLength - lengthToUse;

										byte[] ackPkt = generatePacket(seqNum);

										fos.write(payload);

										for (int i = 0; i < 5; i++) {
											dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));
											sleep(1000);
										}

										System.out.println("Receiver : " + "In order Received : " + seqNum + " Send Ack : " + seqNum
												+ " Next Expected Msg : None " + "  Window : " + getKeysFromWindow());

										if (getMode() == 0 && seqNum == numberOfFrame) {
											System.out.println("Receiver : Successfully received " + getFilename());
											dgSocket.close();
											System.exit(0);
										}

										if (seqNum == prevSeqNum) {
											System.out.println("Receiver : Successfully received " + getFilename());
											dgSocket.close();
											System.exit(0);
										}

									} else {

										fos.write(payload);
										// current in order might be in buffer before due to out of order
										if (packetList.containsKey(seqNum)) {
											packetList.remove(seqNum);
										}

										if (prevSeqNum <= numberOfFrame) {
											prevSeqNum = seqNum; // previous received in order
											nextSeqNum++; // next expected
										}

										byte[] ackPkt = generatePacket(prevSeqNum);

										dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));

										System.out.println("Receiver : In order Received : " + seqNum + ". Send Ack : " + seqNum
												+ " Next Expected Msg : " + nextSeqNum + "  Window : " + getKeysFromWindow());

										// now check in buffer
										// for other pending acks, select largest continuous ack, remove from
										// list,change pointers
										if (packetList.containsKey(nextSeqNum)) {
											int largestContinuousSeqNum = nextSeqNum;
											while (packetList.containsKey(nextSeqNum)) {
												fos.write(packetList.get(nextSeqNum));
												packetList.remove(nextSeqNum);
												System.out.println("Receiver : Found next msg in window. Removed : " + nextSeqNum);
												largestContinuousSeqNum = nextSeqNum;
												if (prevSeqNum < numberOfFrame) {
													prevSeqNum = nextSeqNum; // previous received in order
													nextSeqNum++; // next expected
												}
											}

											// send ack only for largest one
											ackPkt = generatePacket(largestContinuousSeqNum);

											dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));

											System.out.println("Receiver : Received [Highest msg from window] : " + largestContinuousSeqNum
													+ ". Send Ack : " + largestContinuousSeqNum);

											if (largestContinuousSeqNum == numberOfFrame) {
												for (int i = 0; i < 2; i++) {
													dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));
												}
												System.out.println("Receiver : Successfully received " + getFilename());
												dgSocket.close();
												System.exit(0);
											}
										}
									}
								} else { // not in order so now check for window to buffer
									if (!packetList.containsKey(seqNum)) {
										if (packetList.size() < getWindowSize()) {
											s.acquire();
											packetList.put(seqNum, payload);
											System.out.println("Receiver : Out of order Received: " + seqNum + " Next Expected Msg : "
													+ nextSeqNum + "  Window : " + getKeysFromWindow());
											// normal packets
											// for first packet
											s.release();
										}
									}
									// not in order and window full so discarded
									// so send ack back of last ack received

									byte[] ackPkt = generatePacket(prevSeqNum);
									for (int i = 0; i < 5; i++) {
										dgSocket.send(new DatagramPacket(ackPkt, ackPkt.length, rAddress, rPort));
									}
									System.out
											.println("Receiver : Re-send ack for Msg : " + prevSeqNum + "  Window : " + getKeysFromWindow());
								}

							}
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
					System.exit(0);
				} finally {
					// dgSocket.close();
					fos.close();
					// System.out.println("Receiver : DatagramSocket closed.");
				}
			} catch (

			Exception e) {
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
		if (mode == 0) {
			this.setModeParameter(1);
		}
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
		if (getMode() == 0) {
			this.WINDOW_SIZE = 1;
		} else {
			this.WINDOW_SIZE = n;
		}
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
				s.acquire();
				System.out.println("Sender : Timeout!");
				nextSeqNum = prevSeqNum; // resets nextSeqNum
				s.release();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

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