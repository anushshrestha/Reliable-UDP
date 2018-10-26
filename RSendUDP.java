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
import java.util.TreeMap;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;

import edu.utulsa.unet.UDPSocket;
import edu.utulsa.unet.RSendUDPI;

/**
 *
 * @author anush shrestha
 */
public class RSendUDP implements RSendUDPI {

  final int FINAL_ACK_NO = -2;
  int MODE = 0; // 0 is stop and wait and 1 is sliding window
  long WINDOW_SIZE = 256;
  long TIME_OUT = 1000; // in millisecond
  String senderFileName;
  int localPort = 12987;
  InetSocketAddress receiverInfo;
  int MTU = 20;
  int headerLength = 4;

  int baseSeqOfWindow = 1; // LAR last acknowledgement received
  int nextSeqInWindow = 1; // LFS last frame sent
  TreeMap<Integer, byte[]> packetList;
  Timer timer;
  Semaphore s;
  boolean isTransferComplete;
  UDPSocket dgSocket;
  int frameCounter = 0;
  int numberOfFrame;
  long fileLength;
  int timerCounter = 0;

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
    // this.path = path;
    // fileName = getFilename();
    s = new Semaphore(1);
    packetList = new TreeMap<Integer, byte[]>();
    isTransferComplete = false;

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
        System.out.println("No of frames: " + numberOfFrame + " and Size of Last frame: " + sizeOfLastFrame);
        try {
          boolean isFinalSequenceNum = false;

          while (!isTransferComplete) {
            int dataLength = 0;
            byte[] dataBuffer, dataBytes;

            if (nextSeqInWindow < baseSeqOfWindow + getWindowSize() - 1 && !isFinalSequenceNum) {
              s.acquire();
              if (baseSeqOfWindow == nextSeqInWindow) { // first packet so start timer
                setTimer(true);
              }
              if (nextSeqInWindow < packetList.size()) { // if in packetlist retrieve
                outData = packetList.get(nextSeqInWindow);
              } else { // else create and add to list

                // first packet
                if (nextSeqInWindow == 1) {
                  byte[] numberOfFrameBytes = ByteBuffer.allocate(4).putInt(numberOfFrame).array();
                  dataBuffer = new byte[MTU - 8];
                  dataLength = fis.read(dataBuffer, 0, payloadLength - 4);
                  dataBytes = copyOfRange(dataBuffer, 0, dataLength);
                  ByteBuffer BB = ByteBuffer.allocate(MTU);
                  BB.put(numberOfFrameBytes);
                  BB.put(dataBytes);
                  outData = generatePacket(nextSeqInWindow, BB.array());
                } else {
                  dataBuffer = new byte[MTU];
                  dataLength = fis.read(dataBuffer, 0, payloadLength);

                  if (dataLength == -1) { // no more data to be read
                    isFinalSequenceNum = true;
                    outData = generatePacket(nextSeqInWindow, new byte[0]);
                  } else { // else if valid data
                    dataBytes = copyOfRange(dataBuffer, 0, dataLength);
                    outData = generatePacket(nextSeqInWindow, dataBytes);
                  }
                }
                packetList.put(nextSeqInWindow, outData);
              }

              // rInetAddress
              // sending the packet
              dgSocket.send(new DatagramPacket(outData, outData.length, InetAddress.getByName("localhost"), rPort));

              int totalBytes = outData.length;

              if (dataLength == -1) {
                System.out.println("Sender : Message EOF packet sent with " + totalBytes + " bytes of actual data");
              } else {
                System.out.println(
                    "Sender : Message " + nextSeqInWindow + " sent with " + totalBytes + " bytes of actual data.");
              }

              if (!isFinalSequenceNum) {
                nextSeqInWindow++;
              }
              s.release();
            }
            sleep(10);
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          setTimer(false);
          dgSocket.close();
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

            int totalTime = (int) getTimeout() * timerCounter;
            if (isFinalAckReceived && baseSeqOfWindow == numberOfFrame) {
              isTransferComplete = true;
              nextSeqInWindow = baseSeqOfWindow; // no need to send next packet
              System.out.println("Sender : Successfully transferred " + getFilename() + "(" + fileLength + ") in "
                  + totalTime + "seconds");
            } else {

              dgSocket.receive(inPacket);
              int ackNum = decodePacket(inData);

              if (ackNum == FINAL_ACK_NO) {
                packetList.remove(ackNum);
                isFinalAckReceived = true;
                System.out.println("Sender : Message EOF acknowledged. EOF removed from packetList");
              } else { // else normal ack

                // packetList.remove(ackNum);
                System.out.println("Sender : Message " + ackNum + " acknowledged.");
                // packetList.remove(ackNum);

                baseSeqOfWindow = ackNum; // update baseSeqOfWindow number

                s.acquire(); /***** enter CS *****/
                if (baseSeqOfWindow == nextSeqInWindow)
                  setTimer(false); // if no more unacknowledged packets in pipe, off timer
                else {
                  setTimer(true); // else packet acknowledged, restart timer
                }
                s.release(); /***** leave CS *****/
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
        System.out.println("Sender : Timer restarted.");
        timerCounter++;
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
