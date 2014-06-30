/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2014 Jeff Nelson, Cinchapi Software Collective
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.cinchapi.concourse.server.io;

import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.text.MessageFormat;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

import com.google.common.base.Preconditions;

/**
 * A simple file based and memory mapped collection of "bits" that are either
 * "true" or "false".
 * <p>
 * Since this class is memory mapped, the collection is a fixed length. Each
 * component of the bit set has a "{@code boolean}" value and each bit is
 * indexed by nonnegative integers.
 * </p>
 * <p>
 * By default, all the bits in the set initially have a value of {@code false}
 * if the backing file does not exist prior to the creation of the bit set.
 * Otherwise, the default behaviour is undefined.
 * </p>
 * 
 * @author jnelson
 */
@ThreadSafe
public class MappedBitSet {

    // TODO unit tests

    /**
     * Create a new {@link MappedBitSet} that is stored at {@code file} and
     * contains {@code numBits}.
     * 
     * @param file
     * @param numBits
     * @return the new MappedBitSet
     */
    public static MappedBitSet create(String file, int numBits) {
        return new MappedBitSet(file, numBits);
    }

    /**
     * Load the {@link MappedBitSet} that is stored in {@code file}.
     * 
     * @param file
     * @return the MappedBitSet
     */
    public static MappedBitSet load(String file) {
        return new MappedBitSet(file, (int) FileSystem.getFileSize(file)
                * NUM_BITS_PER_BYTE);
    }

    /**
     * The number of bits that are included in a single byte.
     */
    private static final byte NUM_BITS_PER_BYTE = 8;

    /**
     * The memory-mapped content of the bit set.
     */
    private final MappedByteBuffer bitSet;

    /**
     * The master lock that is responsible for ThreadSafe concurrency controls.
     */
    private final ReentrantReadWriteLock masterLock = new ReentrantReadWriteLock();

    /**
     * Construct a new instance.
     * 
     * @param file
     * @param numBits
     */
    private MappedBitSet(String file, int numBits) {
        this.bitSet = FileSystem.map(file, MapMode.READ_WRITE, 0, numBits
                / NUM_BITS_PER_BYTE);
    }

    /**
     * Atomically check the bit at {@code index} and flip it the complement of
     * its current value if its current value is equal to {@code expected}.
     * 
     * @param index
     * @param expected
     * @return {@code true} if the comparison and flip succeeds, {@code false}
     *         otherwise
     */
    public boolean compareAndFlip(long index, boolean expected) {
        masterLock.writeLock().lock();
        try {
            if(get(index) == expected) {
                flip(index);
                return true;
            }
            else {
                return false;
            }
        }
        finally {
            masterLock.writeLock().unlock();
        }
    }

    /**
     * Set the bit at the specified index to the complement of its current
     * value.
     * 
     * @param index
     */
    public void flip(long index) {
        masterLock.writeLock().lock();
        try {
            Position position = getPosition(index);
            bitSet.position(position.getByteIndex());
            byte content = bitSet.get();
            bitSet.position(position.getByteIndex());
            bitSet.put(Bits.flip(position.getBitIndex(), content));
            bitSet.force(); //TODO ugh this is so fucking slow
        }
        finally {
            masterLock.writeLock().unlock();
        }
    }

    /**
     * Return the bit value (true/false) at {@code index}.
     * 
     * @param index
     * @return the bit value
     */
    public boolean get(long index) {
        masterLock.readLock().lock();
        try {
            Position position = getPosition(index);
            bitSet.position(position.getByteIndex());
            return Bits.get(position.getBitIndex(), bitSet.get()) == 1 ? true
                    : false;
        }
        finally {
            masterLock.readLock().unlock();
        }
    }

    /**
     * Return the size of this bit set.
     * 
     * @return the size
     */
    public long size() {
        return bitSet.capacity() * NUM_BITS_PER_BYTE;
    }

    /**
     * Return the {@link Position} where the bit {@code index} is contained.
     * 
     * @param index
     * @return the index Position
     */
    private Position getPosition(long index) {
        return new Position((int) index / NUM_BITS_PER_BYTE,
                (byte) (index % NUM_BITS_PER_BYTE));
    }

    /**
     * Encapsulates the position of a bit in the {@link MappedBitSet} by
     * referencing a global byte position and a relative bit position within
     * that byte.
     * 
     * @author jnelson
     */
    @Immutable
    private class Position {

        private final int bytePos;
        private final byte bitPos;

        /**
         * Construct a new instance.
         * 
         * @param bytePos
         * @param bitPos
         */
        public Position(int bytePos, byte bitPos) {
            Preconditions.checkArgument(bytePos < bitSet.capacity());
            Preconditions.checkArgument(bitPos < NUM_BITS_PER_BYTE);
            this.bytePos = bytePos;
            this.bitPos = bitPos;
        }

        /**
         * Return the relative bit position.
         * 
         * @return the relative bit position
         */
        public byte getBitIndex() {
            return bitPos;
        }

        /**
         * Return the global byte position.
         * 
         * @return the global byte position
         */
        public int getByteIndex() {
            return bytePos;
        }

        @Override
        public String toString() {
            return MessageFormat.format("Byte {0} : Bit {1}", bytePos, bitPos);
        }
    }

}