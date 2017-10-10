/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2017 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.cairo;

import com.questdb.PartitionBy;
import com.questdb.misc.Os;
import com.questdb.std.str.CompositePath;
import com.questdb.std.str.Path;
import com.questdb.store.ColumnType;
import com.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Test;

public class TableMetadataCorruptionTest extends AbstractCairoTest {

    @Test
    public void testColumnCountIsBeyondFileSize() throws Exception {
        final String[] names = new String[]{"a", "b", "c", "d", "b", "f"};
        final int[] types = new int[]{ColumnType.INT, ColumnType.INT, ColumnType.STRING, ColumnType.LONG, ColumnType.DATE, ColumnType.DATE};

        assertMetaConstructorFailure(
                names,
                types,
                names.length + 1,
                PartitionBy.NONE,
                5,
                "page outside of file boundary"
        );
    }

    @Test
    public void testDuplicateColumnName() throws Exception {
        final String[] names = new String[]{"a", "b", "c", "d", "b", "f"};
        final int[] types = new int[]{ColumnType.INT, ColumnType.INT, ColumnType.STRING, ColumnType.LONG, ColumnType.DATE, ColumnType.DATE};

        assertMetaConstructorFailure(
                names,
                types,
                names.length,
                PartitionBy.NONE,
                5,
                "Duplicate"
        );
    }

    @Test
    public void testIncorrectTimestampIndex1() throws Exception {
        final String[] names = new String[]{"a", "b", "c", "d", "e", "f"};
        final int[] types = new int[]{ColumnType.INT, ColumnType.INT, ColumnType.STRING, ColumnType.LONG, ColumnType.DATE, ColumnType.DATE};

        assertMetaConstructorFailure(
                names,
                types,
                names.length,
                PartitionBy.NONE,
                23,
                "Timestamp"
        );
    }

    @Test
    public void testIncorrectTimestampIndex2() throws Exception {
        final String[] names = new String[]{"a", "b", "c", "d", "e", "f"};
        final int[] types = new int[]{ColumnType.INT, ColumnType.INT, ColumnType.STRING, ColumnType.LONG, ColumnType.DATE, ColumnType.DATE};

        assertMetaConstructorFailure(
                names,
                types,
                names.length,
                PartitionBy.NONE,
                -2,
                "Timestamp"
        );
    }

    @Test
    public void testIncorrectTimestampIndex3() throws Exception {
        final String[] names = new String[]{"a", "b", "c", "d", "e", "f"};
        final int[] types = new int[]{ColumnType.INT, ColumnType.INT, ColumnType.STRING, ColumnType.LONG, ColumnType.DATE, ColumnType.DATE};

        int timestampIndex = 2; // this is incorrect because column is of type STRING
        Assert.assertEquals(ColumnType.STRING, types[timestampIndex]);
        assertMetaConstructorFailure(
                names,
                types,
                names.length,
                PartitionBy.NONE,
                timestampIndex,
                "STRING"
        );
    }

    @Test
    public void testInvalidColumnType() throws Exception {
        final String[] names = new String[]{"a", "b", "c", "d", "e", "f"};
        final int[] types = new int[]{ColumnType.INT, ColumnType.INT, 566, ColumnType.LONG, ColumnType.DATE, ColumnType.DATE};

        assertMetaConstructorFailure(
                names,
                types,
                names.length,
                PartitionBy.NONE,
                5,
                "Invalid column type"
        );
    }

    @Test
    public void testNullColumnName() throws Exception {
        final String[] names = new String[]{"a", "b", "c", null, "e", "f"};
        final int[] types = new int[]{ColumnType.INT, ColumnType.INT, ColumnType.STRING, ColumnType.LONG, ColumnType.DATE, ColumnType.DATE};

        assertMetaConstructorFailure(
                names,
                types,
                names.length,
                PartitionBy.NONE,
                5,
                "NULL column"
        );
    }

    @Test
    public void testTransitionIndexWhenColumnCountIsBeyondFileSize() throws Exception {
        // this test asserts that validator compares column count to file size, where
        // file is prepared to be smaller than count. On windows this setup does not work
        // because appender cannot truncate file to size smaller than default page size
        // when reader is open.
        if (Os.type != Os.WINDOWS) {
            assertTransitionIndexValidation(99);
        }
    }

    @Test
    public void testTransitionIndexWhenColumnCountOverflows() throws Exception {
        assertTransitionIndexValidation(Integer.MAX_VALUE - 1);
    }

    private void assertMetaConstructorFailure(String[] names, int[] types, int columnCount, int partitionType, int timestampIndex, String contains) throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (CompositePath path = new CompositePath()) {
                path.of(root).concat("x").put(Path.SEPARATOR).$();
                if (FilesFacadeImpl.INSTANCE.mkdirs(path, 509) == -1) {
                    throw CairoException.instance(FilesFacadeImpl.INSTANCE.errno()).put("Cannot create dir: ").put(path);
                }

                final int rootLen = path.length();
                try (AppendMemory mem = new AppendMemory()) {

                    mem.of(FilesFacadeImpl.INSTANCE, path.trimTo(rootLen).concat(TableUtils.META_FILE_NAME).$(), FilesFacadeImpl.INSTANCE.getPageSize());

                    mem.putInt(columnCount);
                    mem.putInt(partitionType);
                    mem.putInt(timestampIndex);
                    for (int i = 0; i < names.length; i++) {
                        mem.putInt(types[i]);
                    }
                    for (int i = 0; i < names.length; i++) {
                        mem.putStr(names[i]);
                    }
                }

                try {
                    new TableMetadata(FilesFacadeImpl.INSTANCE, path);
                    Assert.fail();
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getMessage(), contains);
                }
            }
        });
    }

    private void assertTransitionIndexValidation(int columnCount) throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (CompositePath path = new CompositePath()) {

                CairoTestUtils.createAllTable(root, PartitionBy.NONE);

                path.of(root).concat("all").concat(TableUtils.META_FILE_NAME).$();

                long len = FilesFacadeImpl.INSTANCE.length(path);

                try (TableMetadata metadata = new TableMetadata(FilesFacadeImpl.INSTANCE, path)) {
                    try (AppendMemory mem = new AppendMemory()) {
                        mem.of(FilesFacadeImpl.INSTANCE, path, FilesFacadeImpl.INSTANCE.getPageSize());
                        mem.putInt(columnCount);
                        mem.skip(len - 4);
                    }

                    try {
                        metadata.createTransitionIndex();
                    } catch (CairoException e) {
                        TestUtils.assertContains(e.getMessage(), "Incorrect columnCount");
                    }
                }
            }
        });

    }
}
