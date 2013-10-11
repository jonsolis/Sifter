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

import java.util.concurrent.ArrayBlockingQueue;

public class ForRealBlockingQueue<E> extends ArrayBlockingQueue<E> 
{
  public ForRealBlockingQueue(int maxSize)
  {
    super(maxSize);
  }

  @Override
  public boolean offer(E e)
  {
    // turn offer() and add() into a blocking calls (unless interrupted)
    try {
      put(e);
      return true;
    }
    catch(InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    return false;
  }
}
