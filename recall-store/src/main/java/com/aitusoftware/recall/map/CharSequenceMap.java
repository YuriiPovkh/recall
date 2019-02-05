/*
 * Copyright 2019 Aitu Software Limited.
 *
 * https://aitusoftware.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aitusoftware.recall.map;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import org.agrona.BitUtil;

/**
 * Map for storing a char-sequence against a <code>long</code> value.
 */
public final class CharSequenceMap
{
    private final ToIntFunction<CharSequence> hash;
    private final CharArrayCharSequence charBuffer = new CharArrayCharSequence();
    private final float loadFactor = 0.7f;
    private final IntFunction<ByteBuffer> bufferFactory = ByteBuffer::allocate;
    private final long missingValue;
    private ByteBuffer dataBuffer;
    private int totalEntryCount;
    private int liveEntryCount;
    private int entryCountToTriggerRehash;
    private int mask;
    private int entrySize;
    private int idOffset;
    private int maxCandidateIndex;

    /**
     * Constructor for the map.
     *
     * @param maxKeyLength max length of any key
     * @param initialSize  initial size of the map
     * @param missingValue value to return if the key is not present
     */
    public CharSequenceMap(final int maxKeyLength, final int initialSize, final long missingValue)
    {
        this(maxKeyLength, initialSize, CharSequenceMap::defaultHash, missingValue);
    }

    CharSequenceMap(
        final int maxKeyLength, final int initialSize,
        final ToIntFunction<CharSequence> hash, final long missingValue)
    {
        totalEntryCount = BitUtil.findNextPositivePowerOfTwo(initialSize);
        mask = totalEntryCount - 1;
        entrySize = (maxKeyLength + 3);
        idOffset = maxKeyLength + 1;
        dataBuffer = bufferFactory.apply(((maxKeyLength + 3) * Integer.BYTES) * totalEntryCount);
        entryCountToTriggerRehash = (int)(loadFactor * totalEntryCount);
        maxCandidateIndex = totalEntryCount * entrySize;
        this.hash = hash;
        this.missingValue = missingValue;
    }

    /**
     * Insert a value into the map.
     *
     * @param value value to use as a key
     * @param id    id to store
     */
    public void insert(final CharSequence value, final long id)
    {
        if (liveEntryCount > entryCountToTriggerRehash)
        {
            rehash();
        }
        final int index = entrySize * (hash.applyAsInt(value) & mask);
        if (isIndexPositionForValue(value, index))
        {
            insertEntry(value, id, index);
        }
        else
        {
            for (int i = 1; i < totalEntryCount; i++)
            {
                int candidateIndex = (index + (i * entrySize));
                if (candidateIndex >= maxCandidateIndex)
                {
                    candidateIndex -= maxCandidateIndex;
                }
                if (isIndexPositionForValue(value, candidateIndex))
                {
                    insertEntry(value, id, candidateIndex);
                    return;
                }
            }

            insert(value, id);
        }
    }

    /**
     * Searches the map for a given key.
     *
     * @param value      the key to search for
     * @return the retrieved value, or {@code missingValue} if it was not present
     */
    public long search(final CharSequence value)
    {
        int index = entrySize * (hash.applyAsInt(value) & mask);
        int entry = 0;
        while (entry < totalEntryCount)
        {
            if (dataBuffer.getInt((index * Integer.BYTES) % dataBuffer.capacity()) == 0)
            {
                break;
            }

            boolean matches = true;

            for (int i = 0; i < value.length(); i++)
            {
                if (dataBuffer.getInt(((dataOffset(index) + i) * Integer.BYTES) %
                    dataBuffer.capacity()) != value.charAt(i))
                {
                    matches = false;
                }
            }
            if (matches)
            {
                return readId(index, dataBuffer);
            }

            index += entrySize;
            entry++;
        }

        return missingValue;
    }

    private void insertEntry(final CharSequence value, final long id, final int index)
    {
        final int byteOffset = index * Integer.BYTES;
        dataBuffer.putInt(byteOffset, 1);
        dataBuffer.putInt(lengthOffset(index) * Integer.BYTES, value.length());
        for (int i = 0; i < value.length(); i++)
        {
            dataBuffer.putInt(byteOffset + (2 * Integer.BYTES) + (i * Integer.BYTES), value.charAt(i));
        }

        writeId(id, index);
        liveEntryCount++;
    }

    private void rehash()
    {
        final ByteBuffer oldBuffer = dataBuffer;
        final int oldEntryCount = totalEntryCount;

        dataBuffer = bufferFactory.apply(oldBuffer.capacity() * 2);
        totalEntryCount *= 2;
        mask = totalEntryCount - 1;
        entryCountToTriggerRehash = (int)(loadFactor * totalEntryCount);
        liveEntryCount = 0;
        maxCandidateIndex = totalEntryCount * entrySize;

        for (int i = 0; i < oldEntryCount; i++)
        {
            final int index = i * entrySize;
            if (oldBuffer.getInt(index * Integer.BYTES) != 0)
            {
                final long id = readId(index, oldBuffer);
                charBuffer.reset(oldBuffer, dataOffset(index), oldBuffer.getInt(lengthOffset(index) * Integer.BYTES));
                insert(charBuffer, id);
            }
        }
    }

    private void writeId(final long id, final int index)
    {
        dataBuffer.putLong((index + idOffset) * Integer.BYTES, id);
    }

    private long readId(final int index, final ByteBuffer backingBuffer)
    {
        return backingBuffer.getLong(((index + idOffset) * Integer.BYTES) % backingBuffer.capacity());
    }

    private boolean isIndexPositionForValue(final CharSequence value, final int index)
    {
        return dataBuffer.getInt(index * Integer.BYTES) == 0 || isExistingEntry(value, index);
    }

    private boolean isExistingEntry(final CharSequence value, final int index)
    {
        for (int i = 0; i < value.length(); i++)
        {
            if (dataBuffer.getInt((dataOffset(index) + i) * Integer.BYTES) != value.charAt(i))
            {
                return false;
            }
        }
        return true;
    }

    private static int defaultHash(final CharSequence value)
    {
        int hash = 0;
        for (int i = 0; i < value.length(); i++)
        {
            hash = (31 * hash) + value.charAt(i);
        }
        return hash;
    }

    private static int dataOffset(final int index)
    {
        return index + 2;
    }

    // TODO - length could indicate presence, set 0th element to -1 on start
    private static int lengthOffset(final int index)
    {
        return index + 1;
    }

    private static final class CharArrayCharSequence implements CharSequence
    {
        private ByteBuffer dataBuffer;
        private int offset;
        private int length;

        void reset(final ByteBuffer dataBuffer, final int offset, final int length)
        {
            this.dataBuffer = dataBuffer;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public int length()
        {
            return length;
        }

        @Override
        public char charAt(final int i)
        {
            return (char)dataBuffer.getInt((offset + i) * Integer.BYTES);
        }

        @Override
        public CharSequence subSequence(final int offset, final int length)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString()
        {
            final StringBuilder builder = new StringBuilder();
            for (int i = 0; i < length; i++)
            {
                builder.append(charAt(i));
            }
            return builder.toString();
        }
    }
}