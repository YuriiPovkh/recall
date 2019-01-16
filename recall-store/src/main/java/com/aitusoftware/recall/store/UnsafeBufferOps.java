package com.aitusoftware.recall.store;

import org.agrona.concurrent.UnsafeBuffer;

public final class UnsafeBufferOps extends BufferOps<UnsafeBuffer>
{
    @Override
    void writeLong(final UnsafeBuffer buffer, final int offset, final long value)
    {
        buffer.putLong(offset, value);
    }

    @Override
    long readLong(final UnsafeBuffer buffer, final int offset)
    {
        return buffer.getLong(offset);
    }

    @Override
    void writeByte(final UnsafeBuffer buffer, final int offset, final byte value)
    {
        buffer.putByte(offset, value);
    }

    @Override
    byte readByte(final UnsafeBuffer buffer, final int offset)
    {
        return buffer.getByte(offset);
    }

    @Override
    protected void copyBytes(final UnsafeBuffer source, final UnsafeBuffer target, final int sourceOffset, final int targetOffset, final int length)
    {
        target.putBytes(targetOffset, source, sourceOffset, length);
    }
}