/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.searchbox;

import com.searchbox.SuggestionResultSet.SuggestionResult;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Date;
import java.util.List;
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
    volatile long numRequests;
    volatile long numErrors;
    volatile long totalBuildTime;
    volatile long totalRequestsTime;
    volatile String lastbuildDate;
    SuggesterTreeHolder suggester;
    protected List<String> fields;
    private boolean keystate = true;

    @Override
    public void init(NamedList args) {
        LOGGER.debug(("Hit init"));

        super.init(args);
        this.initParams = args;


        LOGGER.trace("Checking license");
        /*--------LICENSE CHECK ------------ */
        String key = (String) args.get("key");
        if (key == null) {
            keystate = false;
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                    "Need to specify license key using <str name=\"key\"></str>.\n If you don't have a key email contact@searchbox.com to obtain one.");
        }
        if (!checkLicense(key, SuggesterComponentParams.PRODUCT_KEY)) {
            keystate = false;
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                    "License key is not valid for this product, email contact@searchbox.com to obtain one.");
        }

        LOGGER.trace("Done checking license");
        /*--------END LICENSE CHECK ------------ */

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

        fields = ((NamedList) args.get(SuggesterComponentParams.FIELDS)).getAll(SuggesterComponentParams.FIELD);
        if (fields == null) {
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
        LOGGER.debug("Fields is " + fields);


    }

    @Override
    public void prepare(ResponseBuilder rb) throws IOException {
        //none necessary
    }

    @Override
    public void process(ResponseBuilder rb) throws IOException {
        LOGGER.trace(("Hit process"));
        if (!keystate) {
            LOGGER.error("License key failure, no performing suggestion. Please email contact@searchbox.com for more information.");
            numErrors++;
            return;
        }
        SolrParams params = rb.req.getParams();

        boolean build = params.getBool(SuggesterComponentParams.PRODUCT_NAME + "." + SuggesterComponentParams.BUILD, false);
        SolrIndexSearcher searcher = rb.req.getSearcher();
        if (build) {
            long lstartTime = System.currentTimeMillis();
            buildAndWrite(searcher);
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
        
        int maxPhraseSearch= params.getInt(SuggesterComponentParams.PRODUCT_NAME + "." + SuggesterComponentParams.MAX_PHRASE_SEARCH,SuggesterComponentParams.MAX_PHRASE_SEARCH_DEFAULT);
        LOGGER.debug("maxPhraseSearch:\t"+maxPhraseSearch);
        SuggestionResultSet suggestions = suggester.getSuggestions(searcher, fields, query,maxPhraseSearch);

        Integer numneeded = params.getInt(SuggesterComponentParams.PRODUCT_NAME + "." + SuggesterComponentParams.COUNT, SuggesterComponentParams.COUNT_DEFAULT);


        NamedList response = new SimpleOrderedMap();

        int numout = 0;
        for (SuggestionResult suggestion : suggestions.suggestions) {
            LOGGER.debug(suggestion.suggestion + "\t" + suggestion.probability);
            response.add(suggestion.suggestion, suggestion.probability);
            numout++;
            if (numout > numneeded) {
                break;
            }
        }
        LOGGER.debug("\n\n");

        rb.rsp.add(SuggesterComponentParams.PRODUCT_NAME, response);
        totalRequestsTime += System.currentTimeMillis() - lstartTime;
    }

    public void inform(SolrCore core) {
        LOGGER.trace(("Hit inform"));


        if (storeDirname != null) {
            storeDir = new File(storeDirname);
            if (!storeDir.isAbsolute()) {
                storeDir = new File(core.getDataDir() + File.separator + storeDir); //where does core come from?!
            }
            if (!storeDir.exists()) {
                LOGGER.warn("Directory " + storeDir.getAbsolutePath() + " doesn't exist for re-load of suggester, creating emtpy directory, make sure to use suggester.build before first use!");
                storeDir.mkdirs();
            } else {
                try {
                    readFile(storeDir);
                } catch (Exception ex) {
                    LOGGER.error("Error loading sbsuggester model");
                }
            }
        }

        if (buildOnCommit || buildOnOptimize) {
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

    public void newSearcher(SolrIndexSearcher newSearcher, SolrIndexSearcher currentSearcher) {
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
                buildAndWrite(newSearcher);
            } else if (buildOnOptimize) {
                if (newSearcher.getIndexReader().leaves().size() == 1) {
                    buildAndWrite(newSearcher);
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
            LOGGER.error("There was a problem with load model from disk. Suggester will not work unless build=true option is passed." + e.getMessage());
        }
        LOGGER.info("Done reading object from file");
    }

    private boolean checkLicense(String key, String PRODUCT_KEY) {
        return com.searchbox.utils.DecryptLicense.checkLicense(key, PRODUCT_KEY);
    }

    private void buildAndWrite(SolrIndexSearcher searcher) {
        LOGGER.info("Building suggester model");
        SuggeterDataStructureBuilder sdsb = new SuggeterDataStructureBuilder(searcher, fields, ngrams, minDocFreq, minTermFreq,maxNumDocs);
        suggester = sdsb.getSuggester();
        sdsb=null;
        writeFile(storeDir);
        LOGGER.info("Done building and storing suggester model");
    }
}
