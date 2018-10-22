import java.util.concurrent.locks.Lock;

import packets.Packet;

import common.Layer;

/**
 * The {@code SendingWindow} is a queue of packets with an automatic sending
 * mechanism, ensuring reliability and flow control.
 * 
 * A packet is inserted by the application layer, through a call to
 * {@code add()} and it is then automatically periodically send until it is
 * removed by the transport layer on receipt of a corresponding ACK, through a
 * call to {@code remove}.
 */
public class SendingWindow {

    private final Layer under;

    /**
     * Constructs a {@code SendingWindow} of given size.
     * 
     * @param size           the size of the sending queue (the maximal number of
     *                       not acked packets which are kept in queue)
     * @param transportLayer the transport layer that uses this sending window, as
     *                       its members may be accessed
     * @param underLayer     the layer under, used to send through the network
     * @param externalLock   the lock that must be used for synchronizing. To
     *                       prevent spurious deadlocks, it should be the same lock
     *                       as used inside the transport layer. Then it is safe to
     *                       take a {@code Condition} from this lock.
     */
    public SendingWindow(int size, TransportLayer transportLayer, Layer underLayer, Lock externalLock) {
        // to be modified/extended
        under = underLayer;
    }

    /**
     * Adds the given packet at the end of this queue. This method may of course
     * block (the applicative thread) until there is a free space in buffer. No
     * check on the sequence number is performed by {@code add}.
     * 
     * When the output stream has been closed by the transport layer, @see
     * TransportInterface#outputClosed(), an {@code IllegalStateException} MUST be
     * thrown, as a call to {@code add} in such a case should not occur.
     * 
     * @param packet the packet to add
     * @throws IllegalStateException when output is closed
     */
    public void add(Packet packet) {
        // to be modified/extended
        under.send(packet);
    }

    /**
     * Removes the packet at the head of this queue, but only if it has the expected
     * sequence number.
     * 
     * The head of the queue is the oldest packet pushed by the sending application
     * and not yet acked. No other packet, but the head first, can be removed from
     * the buffer, otherwise an {@code IllegalStateException} MUST be thrown. This
     * additional check is to be implemented to help a proper implementation of the
     * protocol, knowing that the number of the first element of the queue is
     * predictable by the transport layer.
     * 
     * This method is to be called by the transport layer when a related ACK packet
     * is received. As it is run by the receiving thread from the layer under, it
     * should not block.
     * 
     * An {@code IllegalStateException} MUST also be thrown when {@code remove} is
     * called on an empty queue, as such a case should not occur.
     * 
     * @param seqNum the expected number for the packet at the head of this queue
     * @throws IllegalStateException when this queue is empty, or when the number of
     *                               the head packet is not as expected
     */
    public void remove(int seqNum) {
        // to be modified/extended
    }

    /**
     * At least, must terminate any ancillary thread, so that the JVM also
     * terminates. May also release memory resources.
     */
    public void close() { // to be completed as needed
    }

}
