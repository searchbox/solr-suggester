/*******************************************************************************
 * Copyright Searchbox - http://www.searchbox.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.searchbox;

import com.searchbox.SuggestionResultSet.SuggestionResult;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.analysis.util.CharFilterFactory;
import org.apache.lucene.analysis.util.ResourceLoader;
import org.apache.lucene.analysis.util.ResourceLoaderAware;
import org.apache.lucene.analysis.util.TokenFilterFactory;
import org.apache.lucene.analysis.util.WordlistLoader;
import org.apache.lucene.util.IOUtils;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrEventListener;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author andrew
 */
public class SuggesterComponent extends SearchComponent implements SolrCoreAware, SolrEventListener {

    private static Logger LOGGER = LoggerFactory.getLogger(SuggesterComponent.class);
    protected NamedList initParams;
    protected File storeDir;
    protected String storeDirname;
    protected Boolean buildOnOptimize = false;
    protected Boolean buildOnCommit = false;
    protected Integer ngrams;
    protected Integer minDocFreq;
    protected Integer minTermFreq;
    protected Integer maxNumDocs;
    protected String nonpruneFileName;
    protected ResourceLoader resouceloader;
    protected String stopWordFile;
    volatile long numRequests;
    volatile long numErrors;
    volatile long totalBuildTime;
    volatile long totalRequestsTime;
    volatile String lastbuildDate;
    
    SuggesterTreeHolder suggester;
    protected String  gfields[];
    private List<String> stopwords = new ArrayList<String>();
   
    
    @Override
    public void init(NamedList args) {  //standard loading of options from config file, discussed in documentation
        LOGGER.debug(("Hit init"));

        super.init(args);
        this.initParams = args;

        buildOnOptimize = Boolean.parseBoolean((String) args.get(SuggesterComponentParams.BUILD_ON_OPTIMIZE));
        if (buildOnOptimize == null) {
            buildOnOptimize = Boolean.parseBoolean(SuggesterComponentParams.BUILD_ON_OPTIMIZE_DEFAULT);
        }

        buildOnCommit = Boolean.parseBoolean((String) args.get(SuggesterComponentParams.BUILD_ON_COMMIT));
        if (buildOnCommit == null) {
            buildOnCommit = Boolean.parseBoolean(SuggesterComponentParams.BUILD_ON_COMMIT_DEFAULT);
        }

        storeDirname = (String) args.get(SuggesterComponentParams.STOREDIR);
        if (storeDirname == null) {
            storeDirname = SuggesterComponentParams.STOREDIR_DEFAULT;
        }
        
        stopWordFile = (String) args.get(SuggesterComponentParams.STOP_WORD_LOCATION);
        if (stopWordFile == null) {
            stopWordFile = SuggesterComponentParams.STOP_WORD_LOCATION_DEFAULT;
        }
        
        nonpruneFileName = (String) args.get(SuggesterComponentParams.NONPRUNEFILE);
        
        ngrams = (Integer) args.get(SuggesterComponentParams.NGRAMS);
        if (ngrams == null) {
            ngrams = Integer.parseInt(SuggesterComponentParams.NGRAMS_DEFAULT);
        }

        minDocFreq = (Integer) args.get(SuggesterComponentParams.MINDOCFREQ);
        if (minDocFreq == null) {
            minDocFreq = SuggesterComponentParams.MINDOCFREQ_DEFAULT;
        }

        minTermFreq = (Integer) args.get(SuggesterComponentParams.MINTERMFREQ);
        if (minTermFreq == null) {
            minTermFreq = SuggesterComponentParams.MINTERMFREQ_DEFAULT;
        }

        maxNumDocs= (Integer) args.get(SuggesterComponentParams.MAXNUMDOCS);
        if (maxNumDocs== null) {
            maxNumDocs= SuggesterComponentParams.MAXNUMDOCS_DEFAULT;
        }
        
        NamedList fields= ((NamedList) args.get(SuggesterComponentParams.FIELDS));
        if(fields==null)
        {
                 throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                    "Need to specify at least one field");
        }
       
        gfields = (String [])fields.getAll(SuggesterComponentParams.FIELD).toArray(new String[0]);
        if (gfields == null) {
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                    "Need to specify at least one field");
        }

        LOGGER.debug("maxNumDocs is "+maxNumDocs);
        LOGGER.debug("minDocFreq is " + minDocFreq);
        LOGGER.debug("minTermFreq is " + minTermFreq);
        LOGGER.debug("buildOnCommit is " + buildOnCommit);
        LOGGER.debug("buildOnOptimize is " + buildOnOptimize);
        LOGGER.debug("storeDirname is " + storeDirname);
        LOGGER.debug("Ngrams is " + ngrams);
        LOGGER.debug("Fields is " + gfields);
        LOGGER.debug("Nonprune file is " + nonpruneFileName);

    }

    @Override
    public void prepare(ResponseBuilder rb) throws IOException {
        //none necessary
    }

    @Override
    public void process(ResponseBuilder rb) throws IOException { //actually do the request
        LOGGER.trace(("Hit process"));
        SolrParams params = rb.req.getParams();
        String [] fields = params.getParams(SuggesterComponentParams.FIELDS+"."+SuggesterComponentParams.FIELD);  //see what fields we should be using for query
        
        if(fields==null){
            
            fields=gfields;
        }else
        {
            for(String field: fields){
               LOGGER.info("Using overrode fields:"+field);
        
            }
        }
        boolean build = params.getBool(SuggesterComponentParams.PRODUCT_NAME + "." + SuggesterComponentParams.BUILD, false);
        SolrIndexSearcher searcher = rb.req.getSearcher();
        if (build) {		//request has requested rebuilding of the dictionary
            long lstartTime = System.currentTimeMillis();
            buildAndWrite(searcher,fields);
            totalBuildTime += System.currentTimeMillis() - lstartTime;
            lastbuildDate = new Date().toString();
        }

        if (suggester == null) {
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                    "Model for SBsuggester not created, create using sbsuggester.build=true");
        }
        String query = params.get(SuggesterComponentParams.PRODUCT_NAME + "." + SuggesterComponentParams.QUERY, params.get(CommonParams.Q));
        LOGGER.debug("Query:\t" + query);
        if (query == null) {
            LOGGER.warn("No query, returning..maybe was just used for  building index?");
            numErrors++;
            return;
        }

        long lstartTime = System.currentTimeMillis();
        numRequests++;
        
        int maxPhraseSearch= params.getInt(SuggesterComponentParams.PRODUCT_NAME + "." + SuggesterComponentParams.MAX_PHRASE_SEARCH,SuggesterComponentParams.MAX_PHRASE_SEARCH_DEFAULT); //maximum number of phrases to look though
        LOGGER.debug("maxPhraseSearch:\t"+maxPhraseSearch);
        SuggestionResultSet suggestions = suggester.getSuggestions(searcher, fields, query,maxPhraseSearch); // actually find suggestions

        Integer numneeded = params.getInt(SuggesterComponentParams.PRODUCT_NAME + "." + SuggesterComponentParams.COUNT, SuggesterComponentParams.COUNT_DEFAULT);


        NamedList response = new SimpleOrderedMap();

        int numout = 0;
        for (SuggestionResult suggestion : suggestions.suggestions) { // stick results in an response object
            LOGGER.debug(suggestion.suggestion + "\t" + suggestion.probability);
            response.add(suggestions.myval+suggestion.suggestion, suggestion.probability);
            numout++;
            if (numout > numneeded) {
                break;
            }
        }
        LOGGER.debug("\n\n");

        rb.rsp.add(SuggesterComponentParams.PRODUCT_NAME, response);
        totalRequestsTime += System.currentTimeMillis() - lstartTime;
    }

    @Override
    public void inform(SolrCore core) {  //run on loadup of solr
        LOGGER.trace(("Hit inform"));
        loadStopWords(core.getResourceLoader()); // pull in stop words which will be used later
        if (storeDirname != null) {
            storeDir = new File(storeDirname);
            if (!storeDir.isAbsolute()) {
                storeDir = new File(core.getDataDir() + File.separator + storeDir); 
            }
            if (!storeDir.exists()) {
                LOGGER.warn("Directory " + storeDir.getAbsolutePath() + " doesn't exist for re-load of suggester, creating emtpy directory, make sure to use suggester.build before first use!");
                storeDir.mkdirs();
            } else {
                try {
                    readFile(storeDir); // load premade dictionary object
                } catch (Exception ex) {
                    LOGGER.error("Error loading sbsuggester model");
                }
            }
        }

        if (buildOnCommit || buildOnOptimize) { // check to see if the new searcher should trigger a build on optimize or commit
            LOGGER.info("Registering newSearcher listener for Searchbox Suggester: ");
            core.registerNewSearcherListener(this);
        }
    }

    @Override
    public String getDescription() {
        return "Searchbox Suggester";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public String getSource() {
        return "http://www.searchbox.com";
    }

    @Override
    public NamedList<Object> getStatistics() {

        NamedList all = new SimpleOrderedMap<Object>();
        all.add("requests", "" + numRequests);
        all.add("errors", "" + numErrors);
        all.add("totalBuildTime(ms)", "" + totalBuildTime);
        all.add("totalRequestTime(ms)", "" + totalRequestsTime);
        if (lastbuildDate == null) {
            lastbuildDate = "N/A";
        }
        all.add("lastBuildDate", lastbuildDate);

        return all;
    }

    public void postCommit() {
        LOGGER.trace("postCommit hit");

    }

    public void postSoftCommit() {
        LOGGER.trace("postSoftCommit hit");

    }

    public void newSearcher(SolrIndexSearcher newSearcher, SolrIndexSearcher currentSearcher) { // new searcher event used to create suggestion model if config flags are appropriately set
        LOGGER.trace("newSearcher hit");
        if (currentSearcher == null) {
            // firstSearcher event
            try {
                LOGGER.info("Loading suggester model.");
                readFile(storeDir);

            } catch (Exception e) {
                LOGGER.error("Exception in reloading suggester model.");
            }
        } else {
            // newSearcher event
            if (buildOnCommit) {
                buildAndWrite(newSearcher,gfields);
            } else if (buildOnOptimize) {
                if (newSearcher.getIndexReader().leaves().size() == 1) {
                    buildAndWrite(newSearcher,gfields);
                } else {
                    LOGGER.info("Index is not optimized therefore skipping building suggester index");
                }
            }
        }
    }

    public void writeFile(File dir) { 
        LOGGER.info("Writing suggester model to file");
        try {
            FileOutputStream fos = new FileOutputStream(dir + File.separator + "suggester.ser");
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(suggester);
            oos.flush();
            oos.close();
        } catch (Exception e) {
            LOGGER.error("There was a problem with saving model to disk. Suggester will still work because model is in memory." + e.getMessage());
        }
        LOGGER.info("Done writing suggester model to file");
    }

    private void readFile(File dir) {
        LOGGER.info("Reading object from file");
        try {
            FileInputStream fis = new FileInputStream(dir + File.separator + "suggester.ser");
            BufferedInputStream bis = new BufferedInputStream(fis);
            ObjectInputStream ois = new ObjectInputStream(bis);
            suggester = (SuggesterTreeHolder) ois.readObject();
            ois.close();
        } catch (Exception e) {
            LOGGER.error("There was a problem with load model from disk. Suggester will not work unless build=true option is passed. Stack Message: " + e.getMessage());
        }
        LOGGER.info("Done reading object from file");
    }

    private void buildAndWrite(SolrIndexSearcher searcher, String[] fields) {
        LOGGER.info("Building suggester model");
        SuggeterDataStructureBuilder sdsb = new SuggeterDataStructureBuilder(searcher, fields, ngrams, minDocFreq, minTermFreq, maxNumDocs, nonpruneFileName, stopwords);
        suggester = sdsb.getSuggester();
        sdsb = null;
        writeFile(storeDir);
        LOGGER.info("Done building and storing suggester model");
    }

    public void loadStopWords(ResourceLoader rl) {
        BufferedReader in = null;
        try {
            LOGGER.info("Trying to use custom stopwords:\t" + stopWordFile);
            stopwords = getLines(rl, stopWordFile.trim());
            return;
        } catch (Exception ex) {
            LOGGER.info("Error using custom stopwords");
        }
        
        try {
            LOGGER.info("Using Builtin stopwords (english default)");
            in = new BufferedReader(new InputStreamReader((getClass().getResourceAsStream(stopWordFile))));
            String line;
            while ((line = in.readLine()) != null) {
                stopwords.add(line.trim().toLowerCase());
            }
            in.close();
        } catch (Exception ex) {
            LOGGER.error("Error loading stopwords: " + ex.getMessage());
        }
    }
    
    protected final List<String> getLines(ResourceLoader loader, String resource) throws IOException {
        return WordlistLoader.getLines(loader.openResource(resource), IOUtils.CHARSET_UTF_8);
    }
}
