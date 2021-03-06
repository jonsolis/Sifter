/**
 *
 * Sifter - Search Indexes for Text Evidence Relevantly
 *
 * Copyright (C) 2013, University of Texas at San Antonio (UTSA)
 *
 * Sifter is a digital forensics and e-discovery tool for conducting
 * text based string searches.  It clusters and ranks search hits
 * to improve investigative efficiency. Hit-level ranking uses a 
 * patent-pending ranking algorithm invented by Dr. Nicole Beebe at UTSA.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Jon Stewart, Lightbox Technologies
**/

package edu.utsa.sifter;

import java.io.*;
import java.util.concurrent.TimeUnit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// use this guy and a queue
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.document.Document;

import org.apache.tika.parser.AutoDetectParser;

public class FSRipReader {
  public volatile long BytesRead;
  public volatile long FileBytesRead;
  public volatile long FilesRead;

  final private ForRealBlockingQueue<Runnable> ToBeDone;
  final private ForRealBlockingQueue<byte[]>   BufferPool;
  final private ThreadPoolExecutor Workers;
  final private AutoDetectParser Tika;

  final private int LARGE_FILE_THRESHOLD;
  final private String TEMP_DIR;

  final private Sceadan Classifier;

  FSRipReader(final int numThreads, final int largeFileThreshold, final String tempDir, final String modelFile) throws InterruptedException, IOException {
    ToBeDone = new ForRealBlockingQueue<Runnable>(numThreads);
    Workers = new ThreadPoolExecutor(numThreads, numThreads, 1, TimeUnit.HOURS, ToBeDone);
    Tika = new AutoDetectParser();
    LARGE_FILE_THRESHOLD = largeFileThreshold * 1024 * 1024;

    final int capacity = numThreads + 2; // some slack to keep reading ahead if threads are busy
    BufferPool = new ForRealBlockingQueue<byte[]>(capacity);
    for (int i = 0; i < capacity; ++i) {
      BufferPool.put(new byte[LARGE_FILE_THRESHOLD]);
    }
    TEMP_DIR = tempDir;

    Classifier = new Sceadan(modelFile);
  }

  FileInfo writeLargeFile(final long id, final byte[] metadata, final InputStream in, final long rawSize) throws IOException {
    final byte[] buf = new byte[1024 * 64]; // 64KB
    File tmpPath;
    try {
      tmpPath = File.createTempFile("sifter", null, new File(TEMP_DIR));
    }
    catch (IOException ex) {
      System.err.println("Could not create temp file in " + TEMP_DIR);
      throw ex;
    }
    // System.out.println("allocating large tmp file " + tmpPath + ", " + rawSize + " bytes");
    tmpPath.deleteOnExit();

    RandomAccessFile out;
    try {
      out = new RandomAccessFile(tmpPath, "rw");
      out.setLength(rawSize); // boom! allocate all at once
    }
    catch (IOException ex) {
      System.err.println("Could not allocate size of temp file at " + tmpPath);
      throw ex;
    }
    long consumed = 0;
    int n;
    while (consumed < rawSize) {
      n = 0;
      try {
        int toRead = rawSize - consumed < buf.length ? (int)(rawSize - consumed): buf.length;
        n = in.read(buf, 0, toRead);
      }
      catch (IndexOutOfBoundsException ex) {
        System.err.println("consumed = " + consumed + ", fileSize = " + rawSize);
        throw ex;
      }
      if (n == -1) {
        throw new EOFException("Reached end of file in an unexpected place, expected " + rawSize + " bytes, got " + consumed);
      }
      out.write(buf, 0, n);
      if (consumed >> 24 != (consumed + n) >> 24) {
        // System.out.println("read " + (consumed + n) + " bytes so far");
      }
      consumed += n;
    }
    out.close();
    final FileInputStream tmpFileIn = new FileInputStream(tmpPath);
    return new FileInfo(id, metadata, new MarkableFileInputStream(tmpFileIn), rawSize);
  }

  FileInfo writeBufferFile(final long id, final byte[] metadata, final InputStream in, final int rawSize) throws IOException, InterruptedException {
    final byte[] contents = BufferPool.take(); // get byte array out of pool
    int consumed = 0;
    int n;
    while (consumed < rawSize) {
      n = 0;
      try {
        n = in.read(contents, consumed, rawSize - consumed);
      }
      catch (IndexOutOfBoundsException ex) {
        System.err.println("consumed = " + consumed + ", fileSize = " + rawSize + ". contents.length = " + contents.length);
        throw ex;
      }
      if (n == -1) {
        throw new EOFException("Reached end of file in an unexpected place, expected " + rawSize + " bytes, got " + consumed);
      }
      consumed += n;
    }
    return new FileInfo(id, metadata, new PublicByteArrayInputStream(contents, 0, consumed), rawSize);
  }

  boolean readData(InputStream in, IndexWriter index) throws IOException, InterruptedException {
    final DataInputStream input = new DataInputStream(in);
    int c;

    final ByteBuffer sizeBytes = ByteBuffer.allocate(8);

    boolean ret = false;
    long filepos = 0;

    try {
      while (true) {
        filepos = BytesRead;

        // read size of JSON metadata
        input.readFully(sizeBytes.array());
        sizeBytes.order(ByteOrder.LITTLE_ENDIAN);
        final int jsonSize = (int)sizeBytes.getLong(0);
        BytesRead += 8;

        // read JSON metadata
        final byte[] jsonBytes = new byte[jsonSize];
        input.readFully(jsonBytes);
        BytesRead += jsonSize;

        // read size of file contents
        input.readFully(sizeBytes.array());
        sizeBytes.order(ByteOrder.LITTLE_ENDIAN);
        final long fileSize = sizeBytes.getLong(0);
        BytesRead += 8;

        // System.out.println("Read JSON (" + jsonSize + " bytes), filepos = " + filepos + ", BytesRead = " + BytesRead + ", fileSize = " + fileSize);

        // read file contents
        final FileInfo item = fileSize > LARGE_FILE_THRESHOLD ? writeLargeFile(FilesRead * 2, jsonBytes, in, fileSize):
                                                                writeBufferFile(FilesRead * 2, jsonBytes, in, (int)fileSize);

        Workers.submit(new DocMakerTask(item, index, Tika, Classifier, BufferPool));

        ++FilesRead;// += item.hasSlack() ? 2: 1; // reserves ID space for the slack
        BytesRead += fileSize;
        FileBytesRead += fileSize;

        if ((BytesRead + fileSize)/(1024*1024*1024) - (BytesRead/(1024*1024*1024)) > 0) {
          System.out.println("Filepos = " + filepos + ", FileBytesRead = " + FileBytesRead + ", FilesRead = " + FilesRead);
        }
      }
    }
    catch (EOFException e) {
      if (filepos != BytesRead) { // EOF when we didn't expect it
        System.err.println("Exception reading dumpfiles data: " + e.toString());
        System.err.println("BytesRead is " + BytesRead);
        e.printStackTrace(System.err);
      }
      else {
        ret = true;
      }
    }
    catch (Exception e) {
      System.err.println("Exception reading dumpfiles data: " + e.toString());
      System.err.println("BytesRead is " + BytesRead);
      e.printStackTrace(System.err);
    }
    finally {
      System.err.println("Finished ingesting data, waiting for background threads to shutdown");
      Workers.shutdown();
      Workers.awaitTermination(1, TimeUnit.HOURS);
    }
    return ret;
  }
}
