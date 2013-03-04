package proj.zoie.impl.indexing.internal;
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexReader;

import proj.zoie.api.DirectoryManager;
import proj.zoie.api.DocIDMapperFactory;
import proj.zoie.api.IndexReaderFactory;
import proj.zoie.api.ZoieHealth;
import proj.zoie.api.ZoieIndexReader;
import proj.zoie.api.indexing.IndexReaderDecorator;

public class SearchIndexManager<R extends IndexReader> implements IndexReaderFactory<ZoieIndexReader<R>>
{
    private static final Logger log = Logger.getLogger(SearchIndexManager.class);
    
    public static enum Status
    {
      Sleep, Working
    }

	  private DirectoryManager _dirMgr;
	  private final	IndexReaderDecorator<R>	_indexReaderDecorator;

	  final DocIDMapperFactory _docIDMapperFactory;
	  private final DiskSearchIndex<R> _diskIndex;
	  
	  private volatile Status _diskIndexerStatus;
	  private volatile Mem<R> _mem;
	  /**
	   * the access lock for _mem for the purpose of proper reference counting
	   * for disk IndexReader
	   */
	  private final Object _memLock = new Object();
	  private final RAMIndexFactory<R> _ramIndexFactory;
	  
	  private volatile String _memAVersion = null;
	  private volatile String _memBVersion = null;
	  private volatile String _diskVersion = null;
	  
	  private final Comparator<String> _versionComparator;
	  
	  /**
	   * @param location 
	   * @param indexReaderDecorator
	   */
	  public SearchIndexManager(DirectoryManager dirMgr,Comparator<String> versionComparator,IndexReaderDecorator<R> indexReaderDecorator,DocIDMapperFactory docIDMapperFactory, RAMIndexFactory<R> ramIndexFactory)
	  {
	    _dirMgr = dirMgr;
	    _versionComparator = versionComparator;
	    _docIDMapperFactory = docIDMapperFactory;
	    _ramIndexFactory = ramIndexFactory;
	    if (indexReaderDecorator!=null)
	    {
	      _indexReaderDecorator=indexReaderDecorator;
	    }
	    else
	    {
	      throw new IllegalArgumentException("indexReaderDecorator cannot be null");
	    }
        _diskIndexerStatus = Status.Sleep;
        _diskIndex = new DiskSearchIndex<R>(_dirMgr, _indexReaderDecorator,this); 
        ZoieIndexReader<R> diskIndexReader = null;
        
        try
        {
          diskIndexReader = _diskIndex.getNewReader();
        }
        catch (IOException e)
        {
          log.error(e.getMessage(),e);
          return;
        }
        String version = _diskIndex.getVersion();
        RAMSearchIndex<R> memIndexA = _ramIndexFactory.newInstance(version, _indexReaderDecorator,this);
        Mem<R> mem = new Mem<R>(memIndexA, null, memIndexA, null, diskIndexReader);
        if (diskIndexReader != null)
        {
          diskIndexReader.incZoieRef();
        }
        _mem = mem;
	  }
	  
	  public DocIDMapperFactory getDocIDMapperFactory(){
		  return _docIDMapperFactory;
	  }
	  
    public int getDiskSegmentCount() throws IOException
    {
      return _diskIndex.getSegmentCount();
    }
    public String getDiskSegmentInfo()
    {
      return _diskIndex.getSegmentInfo();
    }

	  public int getRAMASegmentCount()
	  {
	    Mem<R> mem = _mem;
	    if (mem.get_memIndexA()==null) return -1;
	    int ret;
      try
      {
        ret = mem.get_memIndexA().getSegmentCount();
      } catch (IOException e)
      {
        ret = -1;
      }
      return ret;
	  }

	  public int getRAMBSegmentCount()
	  {
      Mem<R> mem = _mem;
      if (mem.get_memIndexB()==null) return -1;
      int ret;
      try
      {
        ret = mem.get_memIndexB().getSegmentCount();
      } catch (IOException e)
      {
        ret = -1;
      }
      return ret;
	  }

	  public void setNumLargeSegments(int numLargeSegments)
	  {
	    _diskIndex._mergePolicyParams.setNumLargeSegments(numLargeSegments);
	  }

	  public int getNumLargeSegments()
	  {
	    return _diskIndex._mergePolicyParams.getNumLargeSegments();
	  }

	  public void setMaxSmallSegments(int maxSmallSegments)
	  {
	    _diskIndex._mergePolicyParams.setMaxSmallSegments(maxSmallSegments);
	  }

	  public int getMaxSmallSegments()
	  {
	    return _diskIndex._mergePolicyParams.getMaxSmallSegments();
	  }

	  public void setPartialExpunge(boolean doPartialExpunge)
	  {
	    _diskIndex._mergePolicyParams.setPartialExpunge(doPartialExpunge);
	  }

	  public boolean getPartialExpunge()
	  {
	    return _diskIndex._mergePolicyParams.getPartialExpunge();
	  }

	  public void setMergeFactor(int mergeFactor)
	  {
		  _diskIndex._mergePolicyParams.setMergeFactor(mergeFactor);
	  }
	  
	  public int getMergeFactor()
	  {
		return _diskIndex._mergePolicyParams.getMergeFactor();
	  }
		
	  public void setMaxMergeDocs(int maxMergeDocs)
	  {
		  _diskIndex._mergePolicyParams.setMaxMergeDocs(maxMergeDocs);
	  }
		
	  public int getMaxMergeDocs()
	  {
		return _diskIndex._mergePolicyParams.getMaxMergeDocs();
	  }
	  
	  public void setUseCompoundFile(boolean useCompoundFile)
	  {
		  _diskIndex._mergePolicyParams.setUseCompoundFile(useCompoundFile);
	  }
	  
	  public boolean isUseCompoundFile()
	  {
	    return _diskIndex._mergePolicyParams.isUseCompoundFile();
	  }
	  
	  /**
	   * Gets the current disk indexer status
	   * @return
	   */
	  public Status getDiskIndexerStatus()
	  {
	    return _diskIndexerStatus;
	  }
	  
	  public List<ZoieIndexReader<R>> getIndexReaders()
	  throws IOException
	  {
	    ArrayList<ZoieIndexReader<R>> readers = new ArrayList<ZoieIndexReader<R>>();
	    ZoieIndexReader<R> reader = null;

	    synchronized(this)
      {
	      synchronized(_memLock)
	      {
	        Mem<R> mem = _mem;
	        RAMSearchIndex<R> memIndexB = mem.get_memIndexB();
	        RAMSearchIndex<R> memIndexA = mem.get_memIndexA();

	        // the following order, e.g. B,A,Disk matters, see ZoieIndexReader.getSubZoieReaderAccessor:
	        // when doing UID->docid mapping, the freshest index needs to be first

	        String currentVersion = null;
	        if (memIndexB != null)                           // load memory index B
	        {
            synchronized(memIndexB)
            {
              reader = memIndexB.openIndexReader();
              if (reader != null)
              {
                reader = reader.copy();
                reader.setDelDocIds();
                readers.add(reader);
              }
            }
            _memBVersion = memIndexB.getVersion();
	        }

	        if (memIndexA != null)                           // load memory index A
	        {
            synchronized(memIndexA)
            {
              reader = memIndexA.openIndexReader();
              if (reader != null)
              {
                reader = reader.copy();
                reader.setDelDocIds();
                readers.add(reader);
              }
            }
	          _memAVersion = memIndexA.getVersion();
	        }

	        // load disk index
	        {
	          reader = mem.get_diskIndexReader();
	          if (reader != null)
	          {
              reader = reader.copy();
	            reader.setDelDocIds();
	            readers.add(reader);
	          }
	          
	          _diskVersion = getCurrentDiskVersion();
	        }
	      }
	    }
	    return readers;
	  }
	  
	  

	@Override
	public String getCurrentReaderVersion() {
	  String version = _memAVersion;
	  if (_versionComparator.compare(version, _memBVersion)<0){
		  version = _memBVersion;
	  }
	  if (_versionComparator.compare(version, _diskVersion) < 0){
		  version = _diskVersion;
	  }
	  return version;
	  
	}
	
	public Comparator<String> getVersionComparator(){
	  return _versionComparator;
	}

	public void returnIndexReaders(List<ZoieIndexReader<R>> readers)
	  {
	    for(ZoieIndexReader<R> r : readers)
	    {
//	      try
//	      {
	        r.decZoieRef();//.decRef();
//	      } catch (IOException e)
//	      {
//	        log.error("error when decRef on reader ", e);
//	      }
	    }
	  }
	  
	  public synchronized void setDiskIndexerStatus(Status status)
	  {	    
	    // going from sleep to wake, disk index starts to index
	    // which according to the spec, index B is created and it starts to collect data
	    // IMPORTANT: do nothing if the status is not being changed.
	    if (_diskIndexerStatus != status)
	    {

	      log.info("updating batch indexer status from "+_diskIndexerStatus+" to "+status);
	      
	      if (status == Status.Working)
	      { // sleeping to working
	        String version = _diskIndex.getVersion();
	        Mem<R> oldMem = _mem;

	        RAMSearchIndex<R> memIndexA = oldMem.get_memIndexA();
	        if(memIndexA != null) memIndexA.closeIndexWriter();

	        RAMSearchIndex<R> memIndexB = _ramIndexFactory.newInstance(version, _indexReaderDecorator,this);
	        Mem<R> mem = new Mem<R>(memIndexA, memIndexB, memIndexB, memIndexA, oldMem.get_diskIndexReader());
	        synchronized(_memLock) 
	        {
            _mem = mem;
	        }
	        log.info("Current writable index is B, new B created");
	      }
	      else
	      {
	        // from working to sleep
	        ZoieIndexReader<R> diskIndexReader = null;
	        try
	        {
            synchronized(_diskIndex)
            {
              // a new reader is already loaded in loadFromIndex
              diskIndexReader = _diskIndex.openIndexReader();
              if (diskIndexReader != null)
                diskIndexReader.incZoieRef();
            }

            Mem<R> oldMem = _mem;
            Mem<R> mem = new Mem<R>(oldMem.get_memIndexB(), null, oldMem.get_memIndexB(), null, diskIndexReader);
            if (oldMem.get_memIndexA()!=null){oldMem.get_memIndexA().close();}
            lockAndSwapMem(diskIndexReader, oldMem.get_diskIndexReader(), mem);
            log.info("Current writable index is A, B is flushed");
	        }
	        catch (IOException e)
	        {
	          log.error(e.getMessage(),e);
	          return;
	        }
          finally
          {
            if (diskIndexReader != null)
              diskIndexReader.decZoieRef();
          }
	      }
	      _diskIndexerStatus = status;
	    }
	  }

	  public DiskSearchIndex<R> getDiskIndex()
	  {
	    return _diskIndex;
	  }

	  public RAMSearchIndex<R> getCurrentWritableMemoryIndex()
	  {
	    return _mem.get_currentWritable();
	  }
	  
	  public RAMSearchIndex<R> getCurrentReadOnlyMemoryIndex()
	  {
	    return _mem.get_currentReadOnly();
	  }
	  
	  /**
	   * Clean up
	   */
	  public void close(){
	    Mem<R> mem = _mem;
	    if (mem.get_memIndexA()!=null)
	    {
	      mem.get_memIndexA().close();
	    }
	    if (mem.get_memIndexB()!=null)
	    {
	      mem.get_memIndexB().close();
	    }
	    if (mem.get_diskIndexReader()!=null)
	    {
//	      try
//	      {
	        mem.get_diskIndexReader().decZoieRef();//.decRef();
            _diskIndex.close();
//          }
//	      catch (IOException e)
//          {
//	        log.error("error closing remaining diskReader pooled in mem: " + e);
//          }
	    }
	  }

	  
	  public String getCurrentDiskVersion() throws IOException
	  {
	    return _diskIndex.getVersion();
	  }

	  public int getDiskIndexSize()
	  {
	    return _diskIndex.getNumdocs();
	  }
	  
	  public int getRamAIndexSize()
	  {
        RAMSearchIndex<R> memIndexA = _mem.get_memIndexA();
	    return (memIndexA==null) ? 0 : memIndexA.getNumdocs();
	  }
	  
	  public String getRamAVersion()
	  {
        RAMSearchIndex<R> memIndexA = _mem.get_memIndexA();
	    return (memIndexA==null) ? null : memIndexA.getVersion();
	  }
	  
	  public int getRamBIndexSize()
	  {
        RAMSearchIndex<R> memIndexB = _mem.get_memIndexB();
	    return (memIndexB==null) ? 0 : memIndexB.getNumdocs();
	  }
	  
	  public String getRamBVersion()
	  {
	    RAMSearchIndex<R> memIndexB = _mem.get_memIndexB();
	    return (memIndexB==null) ? null : memIndexB.getVersion();
	  }
	  
	  /**
	   * utility method to delete a directory
	   * @param dir
	   * @throws IOException
	   */
	  private static void deleteDir(File dir) throws IOException
	  {
	    if (dir == null) return;
	    
	    if (dir.isDirectory())
	    {
	      File[] files=dir.listFiles();
	      for (File file : files)
	      {
	        deleteDir(file);
	      }
	      if (!dir.delete())
	      {
	        throw new IOException("cannot remove directory: "+dir.getAbsolutePath());
	      }
	    }
	    else
	    {
	      if (!dir.delete())
	      {
	        throw new IOException("cannot delete file: "+dir.getAbsolutePath());
	      }
	    }
	  }

  /**
   * Purges an index
   */
  public void purgeIndex()
  {
    log.info("purging index ...");
    _diskIndex.clearDeletes();
    _diskIndex.refresh();
    _diskIndex.closeIndexWriter();
    _dirMgr.purge();
    _diskIndex.refresh();
    if (_mem.get_memIndexA()!=null){_mem.get_memIndexA().close();}
    if (_mem.get_memIndexB()!=null){_mem.get_memIndexB().close();}
    RAMSearchIndex<R> memIndexA = _ramIndexFactory.newInstance(_diskIndex.getVersion(), _indexReaderDecorator, this);
    Mem<R> mem = new Mem<R>(memIndexA, null, memIndexA, null, null);
    synchronized(_memLock) 
    {
      _mem = mem;
    }
    log.info("index purged");
  }
	  
	  public void refreshDiskReader() throws IOException
	  {
		  log.info("refreshing disk reader ...");
		  ZoieIndexReader<R> diskIndexReader = null;
		  try
		  {
		    // load a new reader, not in the lock because this should be done in the background
		    // and should not contend with the readers
		    diskIndexReader = _diskIndex.getNewReader();
		  }
		  catch(IOException e)
		  {
	      ZoieHealth.setFatal();
		    log.error(e.getMessage(),e);
		    throw e;
		  }
		  
	    synchronized(_memLock)
	    {
	      Mem<R> oldMem = _mem;
	      ZoieIndexReader<R> oldDiskIndexReader = oldMem.get_diskIndexReader();
	      if (diskIndexReader != oldDiskIndexReader)
	      {
	        Mem<R> mem = new Mem<R>(oldMem.get_memIndexA(),
	            oldMem.get_memIndexB(),
	            oldMem.get_currentWritable(),
	            oldMem.get_currentReadOnly(),
	            diskIndexReader);
	        if (oldDiskIndexReader != null)
	        {
	          oldDiskIndexReader.decZoieRef();
	        }
	        diskIndexReader.incZoieRef();
	        _mem = mem;
	      }
	    }
		  log.info("disk reader refreshed");
	  }

  /**
   * swap the disk IndexReader cached in mem. In order to count the reference properly,
   * we need to lock the access to _mem so that it is safe to compare whether the old
   * and new disk IndexReader are different. decRef the old IndexReader only when they
   * differ.
   * @param diskIndexReader the new disk IndexReader
   * @param oldDiskReader the old disk IndexReader from old mem
   * @param mem the new mem
   */
  private void lockAndSwapMem(ZoieIndexReader<R> diskIndexReader, ZoieIndexReader<R>  oldDiskReader, Mem<R> mem)
  {
    synchronized(_memLock)
    {
      if (oldDiskReader != diskIndexReader)
      {
        if (oldDiskReader != null)
        {
            oldDiskReader.decZoieRef();
        }
        diskIndexReader.incZoieRef();
      }
      _mem = mem;
    }
  }

  private final static class Mem<R extends IndexReader>
  {
    private final RAMSearchIndex<R> _memIndexA;
    private final RAMSearchIndex<R> _memIndexB;
    private final RAMSearchIndex<R> _currentWritable;
    private final RAMSearchIndex<R> _currentReadOnly;
    private final ZoieIndexReader<R> _diskIndexReader;
    Mem(RAMSearchIndex<R> a, RAMSearchIndex<R> b, RAMSearchIndex<R> w, 
    	RAMSearchIndex<R> r, ZoieIndexReader<R> d)
    {
      _memIndexA = a;
      _memIndexB = b;
      _currentWritable = w;
      _currentReadOnly = r;
      _diskIndexReader = d;
    }
    
    protected RAMSearchIndex<R> get_memIndexA()
    {
      return _memIndexA;
    }

    protected RAMSearchIndex<R> get_memIndexB()
    {
      return _memIndexB;
    }

    protected RAMSearchIndex<R> get_currentWritable()
    {
      return _currentWritable;
    }

    protected RAMSearchIndex<R> get_currentReadOnly()
    {
      return _currentReadOnly;
    }

    protected ZoieIndexReader<R> get_diskIndexReader()
    {
      return _diskIndexReader;
    }
  }

  @Override
  public Analyzer getAnalyzer()
  {
    throw new UnsupportedOperationException();
  }
}
