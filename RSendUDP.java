import java.io.File;
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.Calendar;
import java.util.GregorianCalendar;

import edu.utulsa.unet.UDPSocket;
import edu.utulsa.unet.RSendUDPI;

/*
 *TODO : destination file empty - fix final packet having data from previous
 *         packet - first packet having larger size - on packet loss or delay in
 *         unet.properties, timer is not restarted
 */
/**
 *
 * @author anush shrestha
 * 
 * 
 */
public class RSendUDP implements RSendUDPI {

	final int FINAL_ACK_NO = -2;
	int MODE = 0; // 0 is stop and wait and 1 is sliding window
	long WINDOW_SIZE = 256;
	long TIME_OUT = 1000; // in millisecond
	String senderFileName;
	int localPort = 12987;
	InetSocketAddress receiverInfo;
	int MTU = 200;
	int headerLength = 4;

	int lastAckReceived = 0; // LAR last acknowledgement received
	int lastMsgSent = 1; // LFS last frame sent
	TreeMap<Integer, byte[]> packetList;
	Timer timer;
	Semaphore s;
	boolean isTransferComplete;
	UDPSocket dgSocket;
	int frameCounter = 0;
	int numberOfFrame;
	long fileLength;
	int timerCounter = 0;
	StartTime startTimer;

	class StartTime {
		private final double startMilSeconds;

		StartTime() {

			Calendar cal = new GregorianCalendar();
			int sec = cal.get(Calendar.SECOND);
			int min = cal.get(Calendar.MINUTE);
			int hour = cal.get(Calendar.HOUR_OF_DAY);
			int milliSec = cal.get(Calendar.MILLISECOND);
			startMilSeconds = milliSec + (sec * 1000) + (min * 60000) + (hour * 3600000);
		}

		double getTimeElapsed() {
			Calendar cal = new GregorianCalendar();
			double secElapsed = cal.get(Calendar.SECOND);
			double minElapsed = cal.get(Calendar.MINUTE);
			double hourElapsed = cal.get(Calendar.HOUR_OF_DAY);
			double milliSecElapsed = cal.get(Calendar.MILLISECOND);
			double currentMseconds = milliSecElapsed + (secElapsed * 1000) + (minElapsed * 60000) + (hourElapsed * 3600000);
			return (currentMseconds - startMilSeconds) / 1000;
		}
	}

	public static void main(String[] args) {
		RSendUDP sender = new RSendUDP();
		sender.setMode(1);
		sender.setModeParameter(512);
		sender.setTimeout(10000);
		sender.setFilename("important.txt");
		sender.setLocalPort(23456);
		sender.setReceiver(new InetSocketAddress("172.17.34.56", 32456));
		sender.sendFile();
	}

	public boolean sendFile() {
		// this.path = path;
		// fileName = getFilename();
		s = new Semaphore(1);
		packetList = new TreeMap<Integer, byte[]>();
		isTransferComplete = false;

		startTimer = new StartTime();

		try {
			// create threads to process data
			dgSocket = new UDPSocket(getLocalPort());
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

	// create segment and send
	public class sendThread extends Thread {
		String rInetAddress;
		int rPort;
		FileInputStream fis = null;

		public void run() {
			try {
				String localIPAddress = InetAddress.getLocalHost().getHostAddress();
				int localPort = getLocalPort();

				InetSocketAddress address = new InetSocketAddress(localIPAddress, localPort);
				rInetAddress = getReceiver().getHostName();
				rPort = getReceiver().getPort();
				fis = new FileInputStream(senderFileName);
				byte[] outData = new byte[getWindowSize()];

				String destinationIPAddress = getReceiver().getAddress().getHostAddress();

				String fileName = getFilename();
				fileLength = new File(fileName).length();

				int payloadLength = MTU - headerLength;

				// add 4 bytes because in first frame 4 byte is taken by num of frames
				// + 1 adding last frame
				int totalBytesToSend = (int) fileLength + 4;
				numberOfFrame = totalBytesToSend / payloadLength + 1;
				int sizeOfLastFrame = totalBytesToSend % payloadLength;

				System.out.println("Sending " + fileName + " from " + localIPAddress + ":" + localPort + " to "
						+ destinationIPAddress + ":" + rPort + " with " + fileLength + " bytes");
				System.out.println("Sender : Using " + getModeName());
				System.out.println("Sender : No of frames: " + numberOfFrame + " and Size of Last frame: " + sizeOfLastFrame);

				try {
					boolean isFinalSequenceNum = false;

					while (!isTransferComplete) {
						int dataLength = 0;
						byte[] dataBuffer, dataBytes;

						if (lastAckReceived == numberOfFrame) {
							isTransferComplete = true;
							lastMsgSent = lastAckReceived; // no need to send next packet
							System.out.println("Sender : Successfully transferred " + getFilename() + " (" + fileLength + ") in "
									+ startTimer.getTimeElapsed() + " seconds");
							System.exit(0);
							// send statistics packet
						} else {
							// if next to be send is within window size from last ack received
							int currentWindowSize = lastMsgSent - lastAckReceived;

							// if smaller than window size and not final packet
							if (currentWindowSize < getWindowSize()) {
								// System.out.println("joy");
								s.acquire();
								if (packetList.containsKey(lastMsgSent)) { // if in packetlist retrieve
									outData = packetList.get(lastMsgSent);
								} else { // else create and add to list only till window size
									// first packet
									setTimer(true);
									if (lastMsgSent == 1) {
										System.out.println("asdfasdfas");

										byte[] numberOfFrameBytes = ByteBuffer.allocate(4).putInt(numberOfFrame).array();
										dataBuffer = new byte[MTU - 4]; // for first packet 4 less
										dataLength = fis.read(dataBuffer, 0, payloadLength - 4);
										dataBytes = copyOfRange(dataBuffer, 0, dataLength);
										ByteBuffer BB = ByteBuffer.allocate(MTU);
										BB.put(numberOfFrameBytes);
										BB.put(dataBytes);
										outData = generatePacket(lastMsgSent, BB.array());
									} else {
										// if (packetList.size() <= getWindowSize()) {
										dataBuffer = new byte[MTU];
										dataLength = fis.read(dataBuffer, 0, payloadLength);
										if (dataLength == -1) { // no more data to be read
											// isFinalSequenceNum = true;
											// outData = generatePacket(lastMsgSent, new byte[0]);
											// System.out.println("Sender : File read completed");
										} else { // not last packet
											dataBytes = copyOfRange(dataBuffer, 0, dataLength);
											outData = generatePacket(lastMsgSent, dataBytes);
										}
										// }
										packetList.put(lastMsgSent, outData);
									}
								}

								// rInetAddress
								// sending the packet
								dgSocket.send(new DatagramPacket(outData, outData.length, InetAddress.getByName("localhost"), rPort));

								int totalBytes = outData.length;

								// if (dataLength == -1) {
								// System.out.println("Sender : Message EOF packet sent with " + totalBytes + "
								// bytes of actual data");
								// } else {

								System.out.println("Sender : Message " + lastMsgSent + " sent with " + totalBytes
										+ " bytes of actual data." + " Last ack received : " + lastAckReceived);
								// }

								if (lastMsgSent < numberOfFrame) {
									lastMsgSent++;
								}
								s.release();
							}
							// else {
							// // out of window, waiting for ack for first packet in buffer
							// int nextExpectedAck = lastAckReceived + 1;
							// System.out.println("Sender : Window full. To slide window, Waiting ACK for :
							// " + nextExpectedAck);
							// sleep(300);
							// }
							sleep(5);
							// setTimer(true);
						}
						// here either window full or final received

					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					setTimer(false);
					// dgSocket.close();
					fis.close();
					// System.out.println("Sender : Connection Socket Closed.");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}

	// returns -1 if corrupted, else return Ack number
	int decodePacket(byte[] pkt) {
		return ByteBuffer.wrap(copyOfRange(pkt, 0, 4)).getInt();
	}

	// check for ack
	public class receiveThread extends Thread {
		// receiving process (updates base)
		public void run() {
			try {

				byte[] inData = new byte[4]; // ack packet with no data
				DatagramPacket inPacket = new DatagramPacket(inData, inData.length);
				try {
					boolean isFinalAckReceived = false;
					// while there are still packets yet to be received by receiver
					while (!isTransferComplete) {
						dgSocket.receive(inPacket);
						int ackNum = decodePacket(inData);

						int totalTime = (int) getTimeout() * timerCounter;

						if (lastAckReceived == numberOfFrame) {
							isTransferComplete = true;
							lastMsgSent = lastAckReceived; // no need to send next packet
							System.out.println("Sender : Successfully transferred " + getFilename() + " (" + fileLength + ") in "
									+ startTimer.getTimeElapsed() + " seconds");

							// dgSocket.close();
						} else {

							// discard the packet
							if (lastAckReceived > ackNum) {
								System.out.println("Sender : Message " + ackNum + " acknowledged. [Discarded] ");
							} else {
								// if (ackNum == FINAL_ACK_NO) {
								// packetList.remove(ackNum);
								// isFinalAckReceived = true;
								// System.out.println("Sender : Message EOF acknowledged. EOF removed from
								// packetList");
								// } else { // else normal ack

								int dummyAckNum = ackNum + 1;
								// packetList.remove(ackNum);
								System.out.println("Sender : Message " + ackNum + " acknowledged. Waiting for Ack : " + dummyAckNum);
								// packetList.remove(ackNum);

								if (ackNum <= numberOfFrame) {
									lastAckReceived = ackNum; // update lastAckReceived number
									s.acquire(); /***** enter CS *****/
									if (lastAckReceived == lastMsgSent) {
										setTimer(false); // if no more unacknowledged packets in pipe, off timer
									} else {
										setTimer(true); // else packet acknowledged, restart timer
									}
									s.release(); /***** leave CS *****/
								}
								// }
							}
						}

					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					System.out.println("Sender : DatagramSocket closed.");
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}

	// Timeout task
	public class Timeout extends TimerTask {
		public void run() {
			try {
				s.acquire(); /***** enter CS *****/
				lastMsgSent = lastAckReceived + 1; // resets nextSeqNum
				System.out.println("Sender : Timout. Resending from Message : " + lastMsgSent);
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
			System.out.println("timer started");
			timer.schedule(new Timeout(), TIME_OUT);
		}
	}

	// constructs the packet prepended with header information
	public byte[] generatePacket(int sequenceNumber, byte[] dataBytes) {
		byte[] sequenceNumberBytes = ByteBuffer.allocate(4).putInt(sequenceNumber).array();
		// generate packet
		ByteBuffer packeByteBuffer = ByteBuffer.allocate(4 + dataBytes.length);
		packeByteBuffer.put(sequenceNumberBytes);
		packeByteBuffer.put(dataBytes);
		return packeByteBuffer.array();
	}

	public boolean setMode(int mode) {
		if (mode == 0) {
			this.WINDOW_SIZE = 1;
		}
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
		if (getMode() != 0) {
			this.WINDOW_SIZE = n;
		}
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
