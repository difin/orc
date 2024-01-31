/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.orc.tools;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.orc.CompressionKind;
import org.apache.orc.OrcConf;
import org.apache.orc.OrcFile;
import org.apache.orc.TypeDescription;
import org.apache.orc.Writer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestJsonFileDump {

  public static String getFileFromClasspath(String name) {
    URL url = ClassLoader.getSystemResource(name);
    if (url == null) {
      throw new IllegalArgumentException("Could not find " + name);
    }
    return url.getPath();
  }

  Path workDir = new Path(System.getProperty("test.tmp.dir"));
  Configuration conf;
  FileSystem fs;
  Path testFilePath;

  @BeforeEach
  public void openFileSystem () throws Exception {
    conf = new Configuration();
    fs = FileSystem.getLocal(conf);
    fs.setWorkingDirectory(workDir);
    testFilePath = new Path("TestFileDump.testDump.orc");
    fs.delete(testFilePath, false);
  }

  @Test
  public void testJsonDump() throws Exception {
    TypeDescription schema =
        TypeDescription.fromString("struct<i:int,l:bigint,s:string>");
    schema.findSubtype("l")
        .setAttribute("test1", "value1")
        .setAttribute("test2","value2");
    conf.set(OrcConf.ENCODING_STRATEGY.getAttribute(), "COMPRESSION");
    conf.set(OrcConf.DICTIONARY_IMPL.getAttribute(), "rbtree");
    OrcFile.WriterOptions options = OrcFile.writerOptions(conf)
        .fileSystem(fs)
        .setSchema(schema)
        .stripeSize(100000)
        .compress(CompressionKind.ZLIB)
        .bufferSize(10000)
        .rowIndexStride(1000)
        .bloomFilterColumns("s");
    Writer writer = OrcFile.createWriter(testFilePath, options);
    Random r1 = new Random(1);
    String[] words = new String[]{"It", "was", "the", "best", "of", "times,",
        "it", "was", "the", "worst", "of", "times,", "it", "was", "the", "age",
        "of", "wisdom,", "it", "was", "the", "age", "of", "foolishness,", "it",
        "was", "the", "epoch", "of", "belief,", "it", "was", "the", "epoch",
        "of", "incredulity,", "it", "was", "the", "season", "of", "Light,",
        "it", "was", "the", "season", "of", "Darkness,", "it", "was", "the",
        "spring", "of", "hope,", "it", "was", "the", "winter", "of", "despair,",
        "we", "had", "everything", "before", "us,", "we", "had", "nothing",
        "before", "us,", "we", "were", "all", "going", "direct", "to",
        "Heaven,", "we", "were", "all", "going", "direct", "the", "other",
        "way"};
    VectorizedRowBatch batch = schema.createRowBatch(1000);
    for(int i=0; i < 21000; ++i) {
      ((LongColumnVector) batch.cols[0]).vector[batch.size] = r1.nextInt();
      ((LongColumnVector) batch.cols[1]).vector[batch.size] = r1.nextLong();
      if (i % 100 == 0) {
        batch.cols[2].noNulls = false;
        batch.cols[2].isNull[batch.size] = true;
      } else {
        ((BytesColumnVector) batch.cols[2]).setVal(batch.size,
            words[r1.nextInt(words.length)].getBytes(StandardCharsets.UTF_8));
      }
      batch.size += 1;
      if (batch.size == batch.getMaxSize()) {
        writer.addRowBatch(batch);
        batch.reset();
      }
    }
    if (batch.size > 0) {
      writer.addRowBatch(batch);
    }

    writer.close();
    PrintStream origOut = System.out;
    String outputFilename = "orc-file-dump.json";
    FileOutputStream myOut = new FileOutputStream(workDir + File.separator + outputFilename);

    // replace stdout and run command
    System.setOut(new PrintStream(myOut, true, StandardCharsets.UTF_8.toString()));
    FileDump.main(new String[]{testFilePath.toString(), "-j", "-p", "--rowindex=3"});
    System.out.flush();
    System.setOut(origOut);


    TestFileDump.checkOutput(outputFilename, workDir + File.separator + outputFilename);
  }

  @Test
  public void testDoubleNaNAndInfinite() throws Exception {
    TypeDescription schema = TypeDescription.fromString("struct<x:double>");
    Writer writer = OrcFile.createWriter(testFilePath,
        OrcFile.writerOptions(conf)
            .fileSystem(fs)
            .setSchema(schema));
    VectorizedRowBatch batch = schema.createRowBatch();
    DoubleColumnVector x = (DoubleColumnVector) batch.cols[0];
    int row = batch.size++;
    x.vector[row] = Double.NaN;
    row = batch.size++;
    x.vector[row] = Double.POSITIVE_INFINITY;
    row = batch.size++;
    x.vector[row] = 12.34D;
    if (batch.size != 0) {
      writer.addRowBatch(batch);
    }
    writer.close();

    assertEquals(3, writer.getNumberOfRows());

    PrintStream origOut = System.out;
    ByteArrayOutputStream myOut = new ByteArrayOutputStream();

    // replace stdout and run command
    System.setOut(new PrintStream(myOut, false, StandardCharsets.UTF_8));
    FileDump.main(new String[]{testFilePath.toString(), "-j"});
    System.out.flush();
    System.setOut(origOut);
    String[] lines = myOut.toString(StandardCharsets.UTF_8).split("\n");
    assertEquals("{\"fileName\":\"TestFileDump.testDump.orc\",\"fileVersion\":\"0.12\",\"writerVersion\":\"ORC_14\",\"softwareVersion\":\"ORC Java unknown\",\"numberOfRows\":3,\"compression\":\"ZSTD\",\"compressionBufferSize\":262144,\"schemaString\":\"struct<x:double>\",\"schema\":{\"columnId\":0,\"columnType\":\"STRUCT\",\"children\":{\"x\":{\"columnId\":1,\"columnType\":\"DOUBLE\"}}},\"calendar\":\"Julian/Gregorian\",\"stripeStatistics\":[{\"stripeNumber\":1,\"columnStatistics\":[{\"columnId\":0,\"count\":3,\"hasNull\":false},{\"columnId\":1,\"count\":3,\"hasNull\":false,\"bytesOnDisk\":27,\"min\":NaN,\"max\":NaN,\"sum\":NaN,\"type\":\"DOUBLE\"}]}],\"fileStatistics\":[{\"columnId\":0,\"count\":3,\"hasNull\":false},{\"columnId\":1,\"count\":3,\"hasNull\":false,\"bytesOnDisk\":27,\"min\":NaN,\"max\":NaN,\"sum\":NaN,\"type\":\"DOUBLE\"}],\"stripes\":[{\"stripeNumber\":1,\"stripeInformation\":{\"offset\":3,\"indexLength\":55,\"dataLength\":27,\"footerLength\":35,\"rowCount\":3},\"streams\":[{\"columnId\":0,\"section\":\"ROW_INDEX\",\"startOffset\":3,\"length\":11},{\"columnId\":1,\"section\":\"ROW_INDEX\",\"startOffset\":14,\"length\":44},{\"columnId\":1,\"section\":\"DATA\",\"startOffset\":58,\"length\":27}],\"encodings\":[{\"columnId\":0,\"kind\":\"DIRECT\"},{\"columnId\":1,\"kind\":\"DIRECT\"}]}],\"fileLength\":286,\"rawDataSize\":36,\"paddingLength\":0,\"paddingRatio\":0.0,\"status\":\"OK\"}", lines[0]);
  }
}
