/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.searchbox.searchboxsuggester;

import com.searchbox.searchboxsuggester.SuggestionResultSet.SuggestionResult;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.logging.Level;
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
    SuggesterTreeHolder suggester;
    protected List<String> fields;

    @Override
    public void init(NamedList args) {
        LOGGER.info(("Hit init"));
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


        ngrams = (Integer) args.get(SuggesterComponentParams.NGRAMS);
        if (ngrams == null) {
            ngrams = Integer.parseInt(SuggesterComponentParams.NGRAMS_DEFAULT);
        }


        fields = ((NamedList) args.get(SuggesterComponentParams.FIELDS)).getAll(SuggesterComponentParams.FIELD);


        LOGGER.info("buildOnCommit is " + buildOnCommit);
        LOGGER.info("buildOnOptimize is " + buildOnOptimize);
        LOGGER.info("storeDirname is " + storeDirname);
        LOGGER.info("Ngrams is " + ngrams);
        LOGGER.info("Fields is " + fields);


    }

    @Override
    public void prepare(ResponseBuilder rb) throws IOException {
        LOGGER.info(("Hit prepare"));
        SolrParams params = rb.req.getParams(); //--- I guess need to pull out suggester level params
    /*if (!params.getBool(COMPONENT_NAME, false)) {
         return;
         }
         SolrSpellChecker spellChecker = getSpellChecker(params);
         if (params.getBool(SPELLCHECK_BUILD, false)) {
         spellChecker.build(rb.req.getCore(), rb.req.getSearcher());
         rb.rsp.add("command", "build");
         } else if (params.getBool(SPELLCHECK_RELOAD, false)) {
         spellChecker.reload(rb.req.getCore(), rb.req.getSearcher());
         rb.rsp.add("command", "reload");
         }*/
    }

    @Override
    public void process(ResponseBuilder rb) throws IOException {
        LOGGER.info(("Hit process"));
        SolrParams params = rb.req.getParams(); //do work here
        boolean build = params.getBool(SuggesterComponentParams.COMPONENT_NAME + "." + SuggesterComponentParams.BUILD, false);
        SolrIndexSearcher searcher = rb.req.getSearcher();
        if (build) {


            SuggeterDataStructureBuilder sdsb = new SuggeterDataStructureBuilder(searcher, fields);
            suggester = sdsb.getSuggester();
            writeFile(storeDir);

        }
        
        if(suggester==null){
             throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                    "Model for SBsuggester not created, create using sbsuggester.build=true");
        }
        String query= params.get(SuggesterComponentParams.COMPONENT_NAME + "." + SuggesterComponentParams.QUERY,params.get(CommonParams.Q));
        LOGGER.info("Query:\t"+query);
        

        SuggestionResultSet suggestions = suggester.getSuggestions(searcher, fields, query);
        
        Integer numneeded = params.getInt(SuggesterComponentParams.COMPONENT_NAME + "." + SuggesterComponentParams.COUNT,SuggesterComponentParams.COUNT_DEFAULT);
        
        
        NamedList response = new SimpleOrderedMap();
        
         

        int numout = 0;
        for (SuggestionResult suggestion : suggestions.suggestions) {
            LOGGER.info(suggestion.suggestion + "\t" + suggestion.probability);
            response.add(suggestion.suggestion, suggestion.probability);
            numout++;
            if (numout > numneeded) {
                break;
            }
        }
        LOGGER.info("\n\n");
        
        rb.rsp.add(SuggesterComponentParams.COMPONENT_NAME, response);

        //doit

        /*if (!params.getBool(COMPONENT_NAME, false) || spellCheckers.isEmpty()) {
         return;
         }

        


         NamedList response = new SimpleOrderedMap();
         response.add("suggestions", suggestions);
         rb.rsp.add(COMPONENT_NAME, response);*/



    }

    public void inform(SolrCore core) {
        LOGGER.info(("Hit inform"));


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
        return "TODO";
        //throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getSource() {
        return "TODO";
        //throw new UnsupportedOperationException("Not supported yet.");
    }

    public void postCommit() {
        LOGGER.info("postCommit hit");

    }

    public void postSoftCommit() {
        LOGGER.info("postSoftCommit hit");

    }

    public void newSearcher(SolrIndexSearcher newSearcher, SolrIndexSearcher currentSearcher) {
        LOGGER.info("newSearcher hit");

    }

    public void writeFile(File dir) throws FileNotFoundException, IOException {
        LOGGER.info("Writing object to file");
        FileOutputStream fos = new FileOutputStream(dir + File.separator + "suggester.ser");
        ObjectOutputStream oos = new ObjectOutputStream(fos);
        oos.writeObject(suggester);
        oos.flush();
        oos.close();
        LOGGER.info("Done writing object to file");
    }

    private void readFile(File dir) throws IOException, FileNotFoundException, ClassNotFoundException {
        LOGGER.info("Reading object from file");
        FileInputStream fis = new FileInputStream(dir + File.separator + "suggester.ser");
        ObjectInputStream ois = new ObjectInputStream(fis);
        suggester = (SuggesterTreeHolder) ois.readObject();
        ois.close();
        LOGGER.info("Done reading object from file");
    }
}
