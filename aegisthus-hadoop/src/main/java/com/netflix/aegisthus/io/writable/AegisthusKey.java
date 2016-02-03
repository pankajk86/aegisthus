/**
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.aegisthus.io.writable;

import com.google.common.collect.ComparisonChain;
import org.apache.commons.io.Charsets;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;

import javax.annotation.Nonnull;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.Objects;

/**
 * This is the key that is output from the map job and input into the reduce job.  In most cases it represents a
 * key column pair so that the columns are sorted in the same order as they would appear in Cassandra when going into
 * the reduce job.  This key can also represent a row with no columns.  We preserve this information rather than
 * deleting these.
 */
public class AegisthusKey implements WritableComparable<AegisthusKey> {
    private String sourcePath;
    private ByteBuffer key;
    private ByteBuffer name;
    private Long timestamp;

    /**
     * This is used to construct an AegisthusKey entry for a row that does not have a column, for example a row with
     * all columns deleted.
     *
     * @param key the row key
     */
    public static AegisthusKey createKeyForRow(@Nonnull  ByteBuffer key, @Nonnull  String sourcePath) {
        AegisthusKey aegisthusKey = new AegisthusKey();
        aegisthusKey.key = key;
        aegisthusKey.sourcePath = sourcePath;

        return aegisthusKey;
    }

    /**
     * This is used to construct an AegisthusKey entry for a row that and column pair
     *
     * @param key the row key
     */
    public static AegisthusKey createKeyForRowColumnPair(@Nonnull ByteBuffer key,
                                                         @Nonnull  String sourcePath,
                                                         @Nonnull ByteBuffer name,
                                                         long timestamp) {
        AegisthusKey aegisthusKey = new AegisthusKey();
        aegisthusKey.key = key;
        aegisthusKey.sourcePath = sourcePath;
        aegisthusKey.name = name;
        aegisthusKey.timestamp = timestamp;

        return aegisthusKey;
    }

    @Override
    public int compareTo(@Nonnull AegisthusKey other) {
        return ComparisonChain.start()
                .compare(this.key, other.key)
                .compare(this.sourcePath, other.sourcePath)
                .result();
    }

    public int compareTo(@Nonnull AegisthusKey other, Comparator<ByteBuffer> nameComparator) {
        // This is a workaround for comparators not handling nulls properly
        // The case where name or timestamp is null should only happen when there has been a delete
        int result = this.key.compareTo(other.key);
        if (result != 0) {
            return result;
        }

        result = this.sourcePath.compareTo(other.sourcePath);
        if (result != 0) {
            return result;
        }

        if (this.name == null || this.timestamp == null) {
            return -1;
        } else if (other.name == null || other.timestamp == null) {
            return 1;
        }

        return ComparisonChain.start()
                .compare(this.name, other.name, nameComparator)
                .compare(this.timestamp, other.timestamp)
                .result();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {return true;}
        if (obj == null || getClass() != obj.getClass()) {return false;}
        final AegisthusKey other = (AegisthusKey) obj;
        return Objects.equals(this.key, other.key)
                && Objects.equals(this.sourcePath, other.sourcePath)
                && Objects.equals(this.name, other.name)
                && Objects.equals(this.timestamp, other.timestamp);
    }

    @Nonnull
    public String getSourcePath() {
        return sourcePath;
    }

    @Nonnull
    public ByteBuffer getKey() {
        return key;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, sourcePath, name, timestamp);
    }

    @Override
    public void readFields(DataInput dis) throws IOException {
        int length = dis.readInt();
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        this.key = ByteBuffer.wrap(bytes);

        // The (possibly empty) sourcePath
        length = dis.readInt();
        if (length > 0) {
            bytes = new byte[length];
            dis.readFully(bytes);
            this.sourcePath = new String(bytes, Charsets.UTF_8);
        } else {
            this.sourcePath = "";
        }

        // Optional column name
        length = dis.readInt();
        if (length > 0) {
            bytes = new byte[length];
            dis.readFully(bytes);
            this.name = ByteBuffer.wrap(bytes);
        } else {
            this.name = null;
        }

        // Optional timestamp
        if (dis.readBoolean()) {
            this.timestamp = dis.readLong();
        } else {
            this.timestamp = null;
        }
    }

    /**
     * Zero copy readFields.
     * Note: As defensive copying is not done, caller should not mutate b1 while using instance.
     * */
    public void readFields(byte[] bytes, int start, int length) {
        int pos = start; // start at the input position
        int keyLength = WritableComparator.readInt(bytes, pos);
        pos += 4; // move forward by the int that held the key length
        this.key = ByteBuffer.wrap(bytes, pos, keyLength);
        pos += keyLength; // move forward by the key length

        int pathLength = WritableComparator.readInt(bytes, pos);
        pos += 4; // move forward by the int that held the path length
        if (pathLength > 0) {
            this.sourcePath = new String(bytes, pos, pathLength, Charsets.UTF_8);
        } else {
            this.sourcePath = "";
        }
        pos += pathLength; // move forward by the path length

        int nameLength = WritableComparator.readInt(bytes, pos);
        pos += 4; // move forward by an int that held the name length
        if (nameLength > 0) {
            this.name = ByteBuffer.wrap(bytes, pos, nameLength);
        } else {
            this.name = null;
        }
        pos += nameLength; // move forward by the name length

        if (bytes[pos] == 0) {
            // pos += 1; // move forward by a boolean
            this.timestamp = null;
        } else {
            pos += 1; // move forward by a boolean
            this.timestamp = WritableComparator.readLong(bytes, pos);
            // pos += 8; // move forward by a long
        }
    }

    @Override
    public String toString() {
        return com.google.common.base.Objects.toStringHelper(this)
                .add("key", key)
                .add("name", name)
                .add("timestamp", timestamp)
                .toString();
    }

    @Override
    public void write(DataOutput dos) throws IOException {
        dos.writeInt(key.array().length);
        dos.write(key.array());

        if (sourcePath.isEmpty()) {
            dos.writeInt(0);
        } else {
            byte[] bytes = sourcePath.getBytes(Charsets.UTF_8);
            dos.writeInt(bytes.length);
            dos.write(bytes);
        }

        // Optional column name
        if (this.name == null) {
            dos.writeInt(0);
        } else {
            dos.writeInt(name.array().length);
            dos.write(name.array());
        }

        // Optional timestamp
        if (this.timestamp != null) {
            dos.writeBoolean(true);
            dos.writeLong(timestamp);
        } else {
            dos.writeBoolean(false);
        }
    }
}
