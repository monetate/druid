/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.frame.field;

import org.apache.datasketches.memory.WritableMemory;
import org.apache.druid.frame.FrameType;
import org.apache.druid.query.monomorphicprocessing.RuntimeShapeInspector;
import org.apache.druid.segment.ColumnValueSelector;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes the values of the type ARRAY<X> where X is a numeric type to row based frames.
 * The format of the array written is as follows:
 * <p>
 * Format:
 * - 1 Byte - {@link #NULL_ROW} or {@link #NON_NULL_ROW} denoting whether the array itself is null
 * - If the array is null, then the writer stops here
 * - If the array is not null, then it proceeds to the following steps
 * <p>
 * For each value in the non-null array:
 * - 1 Byte - {@link NumericFieldWriter#ARRAY_ELEMENT_NULL_BYTE} or {@link NumericFieldWriter#ARRAY_ELEMENT_NOT_NULL_BYTE}
 * denothing whether the proceeding value is null or not.
 * - ElementSize Bytes - The encoded value of the element
 * <p>
 * Once all the values in the non-null arrays are over, writes {@link #ARRAY_TERMINATOR}. This is to aid the byte
 * comparison, and also let the reader know that the number of elements in the array are over.
 * <p>
 * The format doesn't add the number of elements in the array at the beginning, so that the serialization of the arrays
 * are byte-by-byte comparable.
 * <p>
 * Examples:
 * 1. null
 * | Bytes  | Value | Interpretation              |
 * |--------|-------|-----------------------------|
 * | 1      | 0x00  | Denotes that the array null |
 * <p>
 * 2. [] (empty array)
 * | Bytes  | Value | Interpretation                     |
 * |--------|----- -|------------------------------------|
 * | 1      | 0x01  | Denotes that the array is not null |
 * | 2      | 0x00  | End of the array                   |
 * <p>
 * 3. [5L, null, 6L]
 * | Bytes   | Value        | Interpretation                                                                    |
 * |---------|--------------|-----------------------------------------------------------------------------------|
 * | 1       | 0x01         | Denotes that the array is not null                                                |
 * | 2       | 0x02         | Denotes that the next element is not null                                         |
 * | 3-10    | transform(5) | Representation of 5                                                               |
 * | 11      | 0x01         | Denotes that the next element is null                                             |
 * | 12-19   | transform(0) | Representation of 0 (default value, the reader will ignore it if SqlCompatible mode is on |
 * | 20      | 0x02         | Denotes that the next element is not null                                         |
 * | 21-28   | transform(6) | Representation of 6                                                               |
 * | 29      | 0x00         | End of array                                                                      |
 */
public class NumericArrayFieldWriter implements FieldWriter
{

  /**
   * Denotes that the array itself is null
   */
  public static final byte NULL_ROW = 0x00;

  /**
   * Denotes that the array is non null
   */
  public static final byte NON_NULL_ROW = 0x01;

  /**
   * Marks the end of the array. Since {@link #NULL_ROW}  and {@link #ARRAY_TERMINATOR} will only occur at different
   * locations, therefore there is no clash in keeping both's values at 0x00
   */
  public static final byte ARRAY_TERMINATOR = 0x00;

  private final ColumnValueSelector selector;
  private final NumericFieldWriterFactory writerFactory;

  /**
   * Returns the writer for ARRAY<LONG>
   */
  public static NumericArrayFieldWriter getLongArrayFieldWriter(final ColumnValueSelector selector)
  {
    return new NumericArrayFieldWriter(selector, LongFieldWriter::forArray);
  }

  /**
   * Returns the writer for ARRAY<FLOAT>
   */
  public static NumericArrayFieldWriter getFloatArrayFieldWriter(
      final ColumnValueSelector selector,
      final FrameType frameType
  )
  {
    return new NumericArrayFieldWriter(selector, s -> FloatFieldWriter.forArray(s, frameType));
  }

  /**
   * Returns the writer for ARRAY<DOUBLE>
   */
  public static NumericArrayFieldWriter getDoubleArrayFieldWriter(
      final ColumnValueSelector selector,
      final FrameType frameType
  )
  {
    return new NumericArrayFieldWriter(selector, s -> DoubleFieldWriter.forArray(s, frameType));
  }

  public NumericArrayFieldWriter(final ColumnValueSelector selector, NumericFieldWriterFactory writerFactory)
  {
    this.selector = selector;
    this.writerFactory = writerFactory;
  }

  @Override
  public long writeTo(WritableMemory memory, long position, long maxSize)
  {
    final Object[] row = (Object[]) selector.getObject();
    if (row == null) {
      int requiredSize = Byte.BYTES;
      if (requiredSize > maxSize) {
        return -1;
      }
      memory.putByte(position, NULL_ROW);
      return requiredSize;
    } else {
      // Create a columnValueSelector to write the individual elements re-using the NumericFieldWriter
      AtomicInteger index = new AtomicInteger(0);
      ColumnValueSelector<Number> columnValueSelector = new ColumnValueSelector<>()
      {
        @Override
        public double getDouble()
        {
          final Number n = getObject();
          assert n != null;
          return n.doubleValue();
        }

        @Override
        public float getFloat()
        {
          final Number n = getObject();
          assert n != null;
          return n.floatValue();
        }

        @Override
        public long getLong()
        {
          final Number n = getObject();
          assert n != null;
          return n.longValue();
        }

        @Override
        public void inspectRuntimeShape(RuntimeShapeInspector inspector)
        {

        }

        @Override
        public boolean isNull()
        {
          return getObject() == null;
        }

        @Nullable
        @Override
        public Number getObject()
        {
          return (Number) row[index.get()];
        }

        @Override
        public Class<? extends Number> classOfObject()
        {
          return Number.class;
        }
      };

      NumericFieldWriter writer = writerFactory.get(columnValueSelector);

      // First byte is reserved for null marker of the array
      // Next [(1 + Numeric Size) x Number of elements of array] bytes are reserved for the elements of the array and
      //  their null markers
      // Last byte is reserved for array termination
      int requiredSize = Byte.BYTES + (writer.getNumericSizeBytes() + Byte.BYTES) * row.length + Byte.BYTES;

      if (requiredSize > maxSize) {
        return -1;
      }

      long offset = 0;
      memory.putByte(position + offset, NON_NULL_ROW);
      offset += Byte.BYTES;

      for (; index.get() < row.length; index.incrementAndGet()) {
        writer.writeTo(
            memory,
            position + offset,
            maxSize - offset
        );
        offset += Byte.BYTES + writer.getNumericSizeBytes();
      }

      memory.putByte(position + offset, ARRAY_TERMINATOR);

      return requiredSize;

    }
  }

  @Override
  public void close()
  {
    // Do nothing
  }
}
