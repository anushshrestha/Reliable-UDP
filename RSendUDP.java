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
import java.util.Map;

import edu.utulsa.unet.UDPSocket;
import edu.utulsa.unet.RSendUDPI;

/**
 * @author anush shrestha
 */
public class RSendUDP implements RSendUDPI {

	final int FINAL_ACK_NO = -2;
	final int RETRANSMISSION_LIMIT = 7;
	int MODE = 0; // 0 is stop and wait and 1 is sliding window
	long WINDOW_SIZE = 256;
	long TIME_OUT = 1000; // in millisecond
	String senderFileName = "important.txt";
	int localPort = 12987;
	int MTU = 200;
	int headerLength = 4 + 4 + 4; // sequence no + lengthto use, number of frame

	int lastAckReceived = 0; // LAR last acknowledgement received
	int lastMsgSent = 1; // LFS last frame sent
	TreeMap<Integer, byte[]> packetList;
	TreeMap<Integer, Timer> timerList;
	Timer timer;
	Semaphore s;
	boolean isTransferComplete;
	UDPSocket dgSocket;
	int frameCounter = 0;
	int numberOfFrame;
	long fileLength;
	int timerCounter = 0;
	StartTime startTimer;
	String localIPAddress, fileName;
	int retransmission = 0;

	int payloadLength;
	int sizeOfLastPayload;
	int rPort;
	int finalReTransmission = 0;
	boolean timerisOut = false;
	FileInputStream fis = null;
	String destinationIPAddress;
	InetSocketAddress receiverInfo;
	InetAddress destionationInetAdress;
	int currentWindowSize;
	int allowedWindowSize = 1;
	String rInetAddress;

	boolean isFinalAckReceived = false;
	boolean isStatAckReceived = false;

	public static void main(String[] args) {
		RSendUDP sender = new RSendUDP();
		sender.setMode(1);
		sender.setModeParameter(512);
		sender.setTimeout(10000);
		sender.setFilename("important.txt");
		sender.setLocalPort(23456);
		sender.setReceiver(new InetSocketAddress("localhost", 32456));
		sender.sendFile();
	}

	public boolean sendFile() {
		s = new Semaphore(1);
		packetList = new TreeMap<Integer, byte[]>();
		timerList = new TreeMap<Integer, Timer>();
		isTransferComplete = false;
		destionationInetAdress = getReceiver().getAddress();
		startTimer = new StartTime();

		try {
			// create threads to process data
			localIPAddress = InetAddress.getLocalHost().getHostAddress();
			fileName = getFilename();
			fileLength = new File(fileName).length();

			dgSocket = new UDPSocket(getLocalPort());
			MTU = dgSocket.getSendBufferSize();
			payloadLength = MTU - headerLength;

			rInetAddress = getReceiver().getHostName();
			rPort = getReceiver().getPort();

			int totalBytesToSend = (int) fileLength;
			numberOfFrame = totalBytesToSend / payloadLength + 1;

			sizeOfLastPayload = totalBytesToSend % payloadLength;

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

	public void sendPayload(int lastMsgSent) {
		boolean isFinalSequenceNum = false;
		InetSocketAddress address = new InetSocketAddress(localIPAddress, localPort);

		byte[] outData = new byte[MTU];
		// check in buffer if ack lost need to re send
		if (packetList.containsKey(lastMsgSent)) {
			// re-sending
			outData = packetList.get(lastMsgSent);
		} else {
			outData = new byte[MTU];

			int dataLength = 0;
			byte[] dataBuffer, dataBytes;

			if (lastMsgSent == numberOfFrame) { // lastMsgSend = pointer to next msg to send
				payloadLength = sizeOfLastPayload;
			}

			byte[] lengthToUseBytes = ByteBuffer.allocate(4).putInt(payloadLength).array();
			byte[] numberOfFrameBytes = ByteBuffer.allocate(4).putInt(numberOfFrame).array();

			try {

				dataBuffer = new byte[payloadLength];
				dataLength = fis.read(dataBuffer, 0, payloadLength);
				// last payload read means all un ack packets are in window
				if (dataLength == -1) { // no more data to be read
					isFinalSequenceNum = true;

					// outData = generatePacket(lastMsgSent, new byte[0]);
					// System.out.println("Sender : File read completed");
					// fis.close();
					// after sending add to window
				}
				dataBytes = copyOfRange(dataBuffer, 0, payloadLength);
				ByteBuffer BB = ByteBuffer.allocate(MTU - 4); // except seq number
				BB.put(lengthToUseBytes);
				BB.put(numberOfFrameBytes);
				BB.put(dataBytes);
				outData = generatePacket(lastMsgSent, BB.array());
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		try {
			dgSocket.send(new DatagramPacket(outData, outData.length, destionationInetAdress, rPort));
			if (packetList.size() < getWindowSize()) {
				packetList.put(lastMsgSent, outData);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		// after sending add to window
		int totalBytes = outData.length;
		System.out.println("Sender : Message " + lastMsgSent + " sent with " + payloadLength + " bytes of actual data."
				+ " Last ack received : " + lastAckReceived + " Added : " + lastMsgSent + " Window :" + getKeysFromWindow());
	}

	public void prepareValues() {
		// add 4 bytes because in first frame 4 byte is taken by num of frames
		// + 1 adding last frame

		int localPort = getLocalPort();
		destinationIPAddress = getReceiver().getAddress().getHostAddress();

		System.out.println("Sending " + fileName + " from " + localIPAddress + ":" + localPort + " to "
				+ destinationIPAddress + ":" + rPort + " with " + fileLength + " bytes");
		System.out.println("Sender : Using " + getModeName());
		System.out.println("Sender : No of frames: " + numberOfFrame + " and Size of Last payload : " + sizeOfLastPayload
				+ " MTU : " + MTU);
	}

	// create segment and send
	public class sendThread extends Thread {

		public void run() {
			try {
				prepareValues();

				currentWindowSize = lastMsgSent - lastAckReceived;
				fis = new FileInputStream(senderFileName);
				allowedWindowSize = 1;
				if (getMode() != 0) {
					allowedWindowSize = getWindowSize();
				}
				// System.out.println("START: current window size " + currentWindowSize + ",
				// allowed window size "
				// + allowedWindowSize + "packet list size " + packetList.size());
				while (!isTransferComplete && currentWindowSize <= allowedWindowSize && !packetList.containsKey(lastMsgSent)
						&& packetList.size() < allowedWindowSize) {

					if (lastAckReceived == numberOfFrame) {
						lastMsgSent = lastAckReceived; // no need to send next packet

						isTransferComplete = true;
						lastMsgSent = lastAckReceived; // no need to send next packet
						System.out.println("Sender : Successfully transferred " + getFilename() + " (" + fileLength + ") bytes in "
								+ startTimer.getTimeElapsed() + " seconds");
						dgSocket.close();
						System.exit(0);

					} else {
						// System.out.println("here");
						s.acquire();
						sendPayload(lastMsgSent);

						setTimer(true, lastMsgSent);
						if (lastMsgSent < numberOfFrame) {
							lastMsgSent++;
						}
						s.release();
					}

					// here either window full or final received
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			} finally {
				// dgSocket.close();
				// System.out.println("Sender : Connection Socket Closed.");
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
					isFinalAckReceived = false;
					isStatAckReceived = false;
					// while there are still packets yet to be received by receiver
					while (!isTransferComplete) {
						dgSocket.receive(inPacket);
						int ackNum = decodePacket(inData);
						int totalTime = (int) getTimeout() * timerCounter;

						if (isFinalAckReceived && isStatAckReceived) {
							isTransferComplete = true;
							lastMsgSent = lastAckReceived; // no need to send next packet

							// dgSocket.close();
						}

						if (lastAckReceived == numberOfFrame) {
							isFinalAckReceived = true;

							System.out.println("Sender : Successfully transferred " + getFilename() + " (" + fileLength + ") in "
									+ startTimer.getTimeElapsed() + " seconds");
							dgSocket.close();
							System.exit(0);

						} else {

							// discard the packet
							if (lastAckReceived >= ackNum) {
								System.out.println("Sender : Message " + ackNum + " re-acknowledged. [Discarded] " + " Window : "
										+ getKeysFromWindow());

								if (packetList.containsKey(ackNum)) {
									packetList.remove(ackNum);
								}
								// if re-ack send next
								s.acquire();
								sendPayload(lastMsgSent);

								setTimer(true, lastMsgSent);
								if (lastMsgSent < numberOfFrame) {
									lastMsgSent++;
								}
								s.release();

							} else {
								int dummyAckNum = ackNum + 1;

								if (ackNum < numberOfFrame) {
									s.acquire();
									if (packetList.containsKey(ackNum)) {
										packetList.remove(ackNum);
									}

									while (packetList.containsKey(ackNum - 1)) {
										packetList.remove(ackNum - 1);
										if (timerList.containsKey(ackNum - 1)) {
											timerList.get(ackNum - 1).cancel();
											timerList.remove(ackNum);
										}
									}

									lastAckReceived = ackNum; // update lastAckReceived number
									System.out.println("Sender : Message " + ackNum + " acknowledged. Waiting for Ack : " + dummyAckNum
											+ " Removed : " + ackNum + "  Window : " + getKeysFromWindow());

									setTimer(false, ackNum); // else packet acknowledged, restart timer
									s.release();

									// if buffer free
									if (packetList.size() < allowedWindowSize) {
										s.acquire();
										sendPayload(lastMsgSent);

										setTimer(true, lastMsgSent);
										if (lastMsgSent < numberOfFrame) {
											lastMsgSent++;
										}
										s.release();
									}

								}

								if (ackNum == numberOfFrame) {
									s.acquire();
									if (packetList.containsKey(ackNum)) {
										packetList.remove(ackNum);
									}
									lastAckReceived = ackNum; // update lastAckReceived number
									System.out
											.println("Sender : Message " + ackNum + " acknowledged." + " Window : " + getKeysFromWindow());

									setTimer(false, ackNum); // else packet acknowledged, restart timer
									s.release();

								}
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

	public String getRunningTimerList() {
		String allKeys = "";
		for (Map.Entry<Integer, Timer> entry : timerList.entrySet()) {
			allKeys = allKeys + " " + entry.getKey();
		}
		return allKeys;
	}

	// Timeout task
	public class Timeout extends TimerTask {
		int seqNum;
		UDPSocket dgSocket;
		InetAddress destinationInetAddress;

		public Timeout(int seqNum, UDPSocket dgSocket, InetAddress destinationInetAddress) {
			this.seqNum = seqNum;
			this.dgSocket = dgSocket;
			this.destinationInetAddress = destinationInetAddress;
		}

		// this function runs after timeout
		public void run() {
			try {
				s.acquire();

				// // remove previous
				// if (timerList.containsKey(seqNum)) {
				// Timer ackTimer = timerList.get(seqNum);
				// ackTimer.cancel();
				// timerList.remove(seqNum);
				// }

				// if this seq is not acked and lesser than last ack remove it
				if (seqNum > lastAckReceived) {
					// if this seq is in buffer
					if (packetList.containsKey(seqNum)) {
						try {
							byte[] resendData = packetList.get(seqNum);
							dgSocket.send(new DatagramPacket(resendData, resendData.length, destinationInetAddress, rPort));
							System.out.println("Sender : Timeout. Resend Message : " + seqNum);
							setTimer(true, seqNum);
						} catch (Exception e) {
							e.printStackTrace();
						}
						retransmission++;
					}
				} else {
					int lowerSeqNum = seqNum;
					while (timerList.containsKey(lowerSeqNum)) {
						Timer ackTimer = timerList.get(lowerSeqNum);
						ackTimer.cancel();
						timerList.remove(lowerSeqNum);
						lowerSeqNum--;
					}
					this.cancel();
				}
				s.release();

				if (retransmission / numberOfFrame > RETRANSMISSION_LIMIT) {
					if (lastAckReceived == numberOfFrame - 1) {
						System.out.println("Sender : Successfully transferred " + getFilename() + " (" + fileLength + ") bytes in "
								+ startTimer.getTimeElapsed() + " seconds");
						dgSocket.close();
						System.exit(0);
					} else {
						// System.out.println("Sender : Re-transmission limit reached. Please re-run.");
						// System.exit(0);

						System.out.println("Sender : Successfully transferred " + getFilename() + " (" + fileLength + ") bytes in "
								+ startTimer.getTimeElapsed() + " seconds");
						dgSocket.close();
						System.exit(0);
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	// to start or stop the timer
	public void setTimer(boolean isNewTimer, int seqNum) {
		if (isNewTimer) {
			if (timerList.size() <= getWindowSize()) {
				timer = new Timer();
				Timeout packetTimeOut = new Timeout(seqNum, dgSocket, destionationInetAdress);
				timer.schedule(packetTimeOut, TIME_OUT, TIME_OUT);
				timerList.put(seqNum, timer);

				System.out.println("Sender : Timer for " + seqNum + " started. Timer list : " + getRunningTimerList());
			}
		} else {
			// reaches here after ack is received
			// if last ack receive end
			if (seqNum == numberOfFrame) {
				System.out.println("Sender : Successfully transferred " + getFilename() + " (" + fileLength + ") bytes in "
						+ startTimer.getTimeElapsed() + " seconds");
				dgSocket.close();
				System.exit(0);
			}

			// first close and remove its timer
			if (timerList.containsKey(seqNum)) {
				Timer ackTimer = timerList.get(seqNum);
				ackTimer.cancel();
				timerList.remove(seqNum);
				System.out.println("Sender : Timer for Message : " + seqNum + " closed. Timer list : " + getRunningTimerList());

				// cancel all timers below last ack received
				for (int i = 1; i < lastAckReceived; i++) {
					if (timerList.containsKey(i)) {
						timerList.get(i).cancel();
						timerList.remove(i);
					}
				}

				// if window is has empty space and packet to be sent, triggger send payload
				// while (packetList.size() < allowedWindowSize && lastMsgSent <= numberOfFrame
				// && lastAckReceived != numberOfFrame) {

				// if (lastMsgSent == numberOfFrame && !packetList.isEmpty()) {
				// // packet has space and other packets needs to be re-send
				// if (packetList.size() == 1 && packetList.containsKey(lastMsgSent)) {
				// // only final packet remaining to send
				// // if (finalReTransmission == 15) {
				// // // only final packet remaining
				// // System.out.println("Sender : Final packet Re-transmission limit reached.
				// // Please re-run.");
				// // System.exit(0);
				// // }
				// for (int i = 0; i < 2; i++) {
				// // finalReTransmission++;
				// sendPayload(lastMsgSent);
				// }
				// if (lastMsgSent < numberOfFrame) {
				// lastMsgSent++;
				// }
				// }
				// }

			} else {
				System.out.println("Sender : Timer for Message : " + seqNum + " has already ended.");
			}
			if (getMode() == 0 && packetList.size() < allowedWindowSize) {
				System.out.println("Sender : Resend from timer for Msg : " + lastMsgSent);
				sendPayload(lastMsgSent);
				setTimer(true, lastMsgSent);
				if (lastMsgSent < numberOfFrame) {
					lastMsgSent++;
				}
			}
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
		} else {
			this.MODE = mode;
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

	public String getKeysFromWindow() {
		String allKeys = "";
		for (Map.Entry<Integer, byte[]> entry : packetList.entrySet()) {
			allKeys = allKeys + " " + entry.getKey();
		}
		return allKeys;
	}

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
}
