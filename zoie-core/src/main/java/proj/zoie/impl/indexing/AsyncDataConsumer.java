package proj.zoie.impl.indexing;
/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;

import org.apache.log4j.Logger;

import proj.zoie.api.DataConsumer;
import proj.zoie.api.LifeCycleCotrolledDataConsumer;
import proj.zoie.api.ZoieException;
import proj.zoie.api.ZoieHealth;

// hao: just for debugging
//import proj.zoie.api.DefaultZoieVersion;

/**
 * Runs a background thread that sends incoming data events to the background DataConsumer.
 * The incoming events are buffered locally and sent to background DataConsumer in batch.
 * <br><br>
 * The private member _batchSize is the 'soft' size limit of each event batch.
 * If the events are coming in too fast and
 * it already accumulate this many, then we block the incoming events until the number of
 * buffered events drop below this limit after some of them being sent to background
 * DataConsumer.
 * 
 * @param <V>
 */
public class AsyncDataConsumer<D> implements LifeCycleCotrolledDataConsumer<D> 
{
  private static final Logger log = Logger.getLogger(AsyncDataConsumer.class);
  
  private volatile ConsumerThread _consumerThread;
  private volatile DataConsumer<D> _consumer;
  private String _currentVersion;
  private volatile String _bufferedVersion;
  private final Comparator<String> _versionComparator;
  private LinkedList<DataEvent<D>> _batch;
  /**
   * The 'soft' size limit of each event batch. If the events are coming in too fast and
   * it already accumulate this many, then we block the incoming events until the number of
   * buffered events drop below this limit after some of them being sent to background
   * DataConsumer.
   */
  private int _batchSize;

  public AsyncDataConsumer(Comparator<String> versionComparator)
  {
    //_currentVersion = -1L;
    //_bufferedVersion = -1L;
    _currentVersion = null;
    _bufferedVersion = null;
    _versionComparator = versionComparator;
    _batch = new LinkedList<DataEvent<D>>();
    _batchSize = 1; // default
    _consumerThread = null;
  }

  /**
   * Start the background thread that batch-processes the incoming data events by sending them to the background DataConsumer.
   * <br>
   * If this method is not called, all threads trying to send in data events will eventually be blocked.
   */
  @Override
  public void start()
  {
    _consumerThread = new ConsumerThread();
    _consumerThread.setDaemon(true);
    _consumerThread.start();
  }
  
  /**
   * Stops the background thread.
   * <br>
   * It will stop the thread that sends data events to background DataConsumer.
   * If more data events comes in, the sender of those events will be blocked.
   */
  @Override
  public void stop()
  {
    _consumerThread.terminate();
  }
  
  /**
   * Set the background DataConsumer.
   * @param consumer the DataConsumer that actually consumes the data events.
   */
  public void setDataConsumer(DataConsumer<D> consumer)
  {
    synchronized(this)
    {
      _consumer = consumer;
    }
  }
  
  /**
   * Sets the size of each batch of events that it sends to background DataConsumer. <br><br>
   * The private member _batchSize is the 'soft' size limit of each event batch.
   * If the events are coming in too fast and
   * it already accumulate this many, then we block the incoming events until the number of
   * buffered events drop below this limit after some of them being sent to background
   * DataConsumer.
   * The actual size of each batch is variable, though the intention is that it is not bigger than the limit.
   * If the incoming batch is big, then the outgoing batch will be big too and likely bigger than the limit.
   * @param batchSize
   */
  public void setBatchSize(int batchSize)
  {
    synchronized(this)
    {
      _batchSize = Math.max(1, batchSize);
    }
  }
  
  /**
   * @return the intended limit of batch size.
   */
  public int getBatchSize()
  {
    synchronized(this)
    {
      return _batchSize;
    }
  }
  
  /**
   * @return the number of unprocessed events in buffered already.
   */
  public int getCurrentBatchSize()
  {
    synchronized(this)
    {
      return (_batch != null ? _batch.size() : 0);
    }
  }
  
  public String getCurrentVersion()
  {
    synchronized(this)
    {
      return _currentVersion;
    }
  }
  
  /**
   * Waits until all the buffered data events are processed.
   * @param timeout the max amount of time to wait in milliseconds. 
   * @throws ZoieException
   */
  public void flushEvents(long timeout) throws ZoieException
  {
    syncWithVersion(timeout, _bufferedVersion);
  }
  

  /**
   * Waits until all the buffered data events up to specified version are processed.
   * @param timeInMillis the max amount of time to wait in milliseconds.
   * @param version the version of events which it waits for.
   * @throws ZoieException
   */
  public void syncWithVersion(long timeInMillis, String version) throws ZoieException
  {
    if(_consumerThread == null) throw new ZoieException("not running");
    if (version == null)
    {
      log.info("buffered version is NULL. Nothing to flush.");
      return;
    }
    synchronized(this)
    {
      long timeRemaining = Long.MAX_VALUE;
      while(_currentVersion==null || _versionComparator.compare(_currentVersion, version) < 0)
      {
        if (log.isDebugEnabled())
        {
          if (timeRemaining > timeInMillis + 5000)
          log.debug("syncWithVersion: timeRemaining: " +timeInMillis+"ms current: " + _currentVersion + " expecting: " + version);
          timeRemaining = timeInMillis;
        }
        this.notifyAll();
    	  long now1 = System.currentTimeMillis();
        if(timeInMillis<=0)
        {
          throw new ZoieException("sync timed out at current: " + _currentVersion + " expecting: " + version);
        }
        try
        {
          long waitTime = Math.min(5000, timeInMillis);
          this.wait(waitTime);
        }
        catch(InterruptedException e)
        {
          log.warn(e.getMessage(), e);
        }
        long now2 = System.currentTimeMillis();
        timeInMillis-=(now2 - now1);
      }
    }
  }
  
  /**
   * consumption of a collection of data events. Note that this method may have a side
   * effect. That is it may empty the Collection passed in after execution. <br><br>
   * Duplicates and buffers the incoming data events.<br><br>
   * If too many (>=_batchSize) amount of data events are already buffered,
   * it waits until the background DataConsumer consumes some of the events before
   * it add new events to the buffer. This throttles the amount of events in each batch.
   * 
   * @param data
   * @throws ZoieException
   * @see proj.zoie.api.DataConsumer#consume(java.util.Collection)
   * 
   */
  public void consume(Collection<DataEvent<D>> data) throws ZoieException
  {
    if (data == null || data.size() == 0) return;
    
    synchronized(this)
    {
      while(_batch.size() >= _batchSize)
      {
        if(_consumerThread == null || !_consumerThread.isAlive() || _consumerThread._stop)
        {
          ZoieHealth.setFatal();
          throw new ZoieException("consumer thread has stopped");
        }
        try
        {
          this.notifyAll();
          this.wait();
        }
        catch (InterruptedException e)
        {
        }
      }
      for(DataEvent<D> event : data)
      {
        _bufferedVersion = (_bufferedVersion == null) ? event.getVersion() : (_versionComparator.compare(_bufferedVersion, event.getVersion()) < 0 ? event.getVersion() : _bufferedVersion);
        _batch.add(event);
      }
      if (log.isDebugEnabled())
      {
        log.debug("consume:receiving: buffered: " + _bufferedVersion);
      }
      this.notifyAll(); // wake up the thread waiting in flushBuffer()
    }
  }
  
  protected final void flushBuffer()
  {
    String version;
    LinkedList<DataEvent<D>> currentBatch;
    
    synchronized(this)
    {
      while(_batch.size() == 0)
      {
        if(_consumerThread._stop) return;
        try
        {
          this.notifyAll();
          this.wait(1000);
        }
        catch (InterruptedException e)
        {
        }
      }
      version = _currentVersion == null ? _bufferedVersion : ((_versionComparator.compare(_currentVersion, _bufferedVersion) < 0) ? _bufferedVersion : _currentVersion);
      currentBatch = _batch;
      _batch = new LinkedList<DataEvent<D>>();
      this.notifyAll(); // wake up the thread waiting in consume(...)
    }
    if (log.isDebugEnabled())
    {
      log.debug("flushBuffer: pre-flush: currentVersion: " + _currentVersion + " processing version: " + version +" of size: " + currentBatch.size());
    }
    
    if(_consumer != null)
    {
      try
      {
        _consumer.consume(currentBatch);
      }
      catch (Exception e)
      {
        log.error(e.getMessage(), e);
      }
    }
    
    synchronized(this)
    {
      _currentVersion = version;
      if (log.isDebugEnabled())
      {
        log.debug("flushBuffer: post-flush: currentVersion: " + _currentVersion);
      }
      this.notifyAll(); // wake up the thread waiting in syncWithVersion()
    }    
  }
  
  private final class ConsumerThread extends IndexingThread
  {
    boolean _stop = false;
    
    ConsumerThread()
    {
      super("ConsumerThread");
    }
    
    public void terminate()
    {
      _stop = true;
      synchronized(AsyncDataConsumer.this)
      {
        AsyncDataConsumer.this.notifyAll();
      }
    }
    
    public void run()
    {
      while(!_stop)
      {
        flushBuffer();
      }
    }
  }
  
  /**
   * @return the version number of events that it has received but not necessarily processed.
   * @see proj.zoie.api.DataConsumer#getVersion()
   */
  public String getVersion(){
    return _bufferedVersion;
  }

	/**
   * @return the version comparator.
   */
	public Comparator<String> getVersionComparator() {
    return _versionComparator;
  }
}
